package dev.smartdns.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.smartdns.MainActivity
import dev.smartdns.R
import dev.smartdns.core.ConnectionState
import dev.smartdns.core.DnsEnforcer
import dev.smartdns.core.DnsRule
import dev.smartdns.core.EnforceError
import dev.smartdns.core.PrivateDnsMode
import dev.smartdns.core.ReconcileResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Event-driven monitor. There is no timer and no polling: the system wakes this service only when
 *
 *  - the default network changes (connectivity callbacks),
 *  - Private DNS is changed by someone else (content observer on the two settings keys),
 *  - location is turned on or off, which changes whether the SSID is readable,
 *  - the screen turns on, which covers drift accumulated while the device dozed,
 *  - the app (re)starts the service: first run, boot, rule save, "apply now".
 *
 * Bursts are debounced into one reconcile, and a reconcile reads the live system value before
 * deciding to write, so a value that already matches costs nothing but the read.
 */
class DnsMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconcileMutex = Mutex()

    private lateinit var prefs: SmartDnsPrefs
    private lateinit var snapshot: NetworkSnapshot
    private lateinit var enforcer: DnsEnforcer
    private lateinit var cm: ConnectivityManager

    private var debounce: Job? = null
    private var lastNotificationText: String? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule()
    }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = schedule()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = SmartDnsPrefs(this)
        snapshot = NetworkSnapshot(this)
        enforcer = DnsEnforcer(PrivateDnsSettings(contentResolver))
        cm = getSystemService(ConnectivityManager::class.java)
        ServiceRuntime.running = true
        createChannel()
        startInForeground(getString(R.string.notif_title))
        registerListeners()
        scope.launch { reconcile() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { reconcile() }
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceRuntime.running = false
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        runCatching { contentResolver.unregisterContentObserver(settingsObserver) }
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerListeners() {
        cm.registerDefaultNetworkCallback(networkCallback)
        val resolver = contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(PrivateDnsSettings.KEY_MODE), false, settingsObserver)
        resolver.registerContentObserver(Settings.Global.getUriFor(PrivateDnsSettings.KEY_SPECIFIER), false, settingsObserver)
        resolver.registerContentObserver(Settings.Secure.getUriFor(SettingsKeys.LOCATION_MODE), false, settingsObserver)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Coalesces a burst of callbacks; the latest state is read when the debounce fires. */
    private fun schedule() {
        if (debounce?.isActive == true) return
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            reconcile()
        }
    }

    private suspend fun reconcile() = reconcileMutex.withLock {
        if (!prefs.enabled) {
            stopSelf()
            return@withLock
        }
        val rule = prefs.rule()
        val (connection, result) = withContext(Dispatchers.IO) {
            val state = snapshot.read()
            state to enforcer.reconcile(state, rule, enabled = true)
        }
        prefs.record(result, System.currentTimeMillis())
        updateNotification(notificationText(connection, rule, result))
    }

    private fun notificationText(
        connection: ConnectionState,
        rule: DnsRule?,
        result: ReconcileResult,
    ): String {
        val target = result.target
        return when {
            result.error == EnforceError.WRITE_DENIED -> getString(R.string.notif_denied)
            rule == null -> getString(R.string.notif_no_rule)
            connection is ConnectionState.Offline -> getString(R.string.notif_idle)
            target?.mode == PrivateDnsMode.OPPORTUNISTIC -> getString(R.string.notif_auto)
            target != null -> getString(R.string.notif_dot, target.specifier)
            else -> getString(R.string.notif_idle)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground(text: String) {
        lastNotificationText = text
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    /**
     * Notification-only update. `setOngoing(false)` is deliberate: on Android 13+ the user can
     * swipe a foreground-service notification away and the service keeps running.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled(), which covers POST_NOTIFICATIONS.
    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dns)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "monitor"
        const val NOTIFICATION_ID = 1
        const val DEBOUNCE_MS = 400L
    }
}
