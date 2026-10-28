package dev.smartdns.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.smartdns.core.ConnectionState
import dev.smartdns.core.Desired
import dev.smartdns.core.DnsEnforcer
import dev.smartdns.core.DnsPolicy
import dev.smartdns.core.DnsRule
import dev.smartdns.core.EnforceError
import dev.smartdns.core.PrivateDnsState
import dev.smartdns.core.PrivateDnsTarget
import dev.smartdns.core.RuleProblem
import dev.smartdns.core.RuleValidation
import dev.smartdns.platform.DotProbe
import dev.smartdns.platform.MonitorServiceControl
import dev.smartdns.platform.NetworkSnapshot
import dev.smartdns.platform.PrivateDnsSettings
import dev.smartdns.platform.ProbeOutcome
import dev.smartdns.platform.ServiceRuntime
import dev.smartdns.platform.SettingsKeys
import dev.smartdns.platform.SmartDnsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MonitorUiState(
    val enabled: Boolean,
    val serviceRunning: Boolean,
    val ssidDraft: String,
    val hostDraft: String,
    val ssidProblem: RuleProblem?,
    val hostProblem: RuleProblem?,
    val draftRule: DnsRule?,
    val savedRule: DnsRule?,
    val connection: ConnectionState,
    val desired: Desired,
    val systemState: PrivateDnsState,
    val lastCheckAt: Long,
    val lastChanged: Boolean,
    val lastError: EnforceError?,
    val lastTarget: PrivateDnsTarget?,
    val writeGranted: Boolean,
    val locationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val locationEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val batteryExempt: Boolean,
    val adbCommand: String,
)

sealed interface ProbeState {
    data object Idle : ProbeState

    data object Invalid : ProbeState

    data object Running : ProbeState

    data class Ok(val millis: Int) : ProbeState

    data class Failed(val reason: String) : ProbeState
}

/**
 * Reads the live world (system setting, default network, permission grants) rather than keeping a
 * mirror of it, and re-reads on the same events the service reacts to. No polling: the only timer
 * is a short one-shot refresh after the user starts or stops the service, so the switch reflects
 * what actually happened.
 */
class MonitorViewModel(private val app: Application) : ViewModel() {
    private val prefs = SmartDnsPrefs(app)
    private val snapshot = NetworkSnapshot(app)
    private val settings = PrivateDnsSettings(app.contentResolver)
    private val enforcer = DnsEnforcer(settings)
    private val probe = DotProbe()

    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(readState(prefs.ssid, prefs.host))
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probeState: StateFlow<ProbeState> = _probe.asStateFlow()

    private var observing = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
    }

    fun onUiStarted() {
        startObserving()
        refresh()
        // Foreground activity: allowed to start the FGS, and this doubles as one immediate reconcile.
        if (prefs.enabled && prefs.rule() != null) {
            MonitorServiceControl.start(app)
            // onCreate flips ServiceRuntime.running on the main thread after this call returns.
            refreshAfterServiceSettles()
        }
    }

    fun onUiStopped() {
        stopObserving()
    }

    fun onSsidDraftChange(value: String) {
        _state.update { it.withDrafts(ssid = value, host = it.hostDraft) }
    }

    fun onHostDraftChange(value: String) {
        _state.update { it.withDrafts(ssid = it.ssidDraft, host = value) }
    }

    fun saveRule() {
        val current = _state.value
        val rule = RuleValidation.ruleOrNull(current.ssidDraft, current.hostDraft) ?: return
        prefs.saveRule(rule.hotspotSsid, rule.dotHost)
        _state.update { it.withDrafts(rule.hotspotSsid, rule.dotHost) }
        // Restarting the service makes it reconcile once with the new rule right away.
        if (prefs.enabled) {
            MonitorServiceControl.start(app)
            refreshAfterServiceSettles()
        }
        refresh()
    }

    fun setMonitoring(on: Boolean) {
        if (on && prefs.rule() == null) return
        prefs.enabled = on
        if (on) MonitorServiceControl.start(app) else MonitorServiceControl.stop(app)
        refresh()
        refreshAfterServiceSettles()
    }

    fun applyNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = enforcer.reconcile(snapshot.read(), prefs.rule(), prefs.enabled)
            prefs.record(result, System.currentTimeMillis())
            publish()
        }
    }

    fun restoreAutomatic() {
        viewModelScope.launch(Dispatchers.IO) {
            settings.write(PrivateDnsTarget.automatic())
            publish()
        }
    }

    fun probeDot() {
        val current = _state.value
        if (RuleValidation.hostProblem(current.hostDraft) != null) {
            _probe.value = ProbeState.Invalid
            return
        }
        val host = RuleValidation.normalizeHost(current.hostDraft)
        _probe.value = ProbeState.Running
        viewModelScope.launch {
            _probe.value = when (val outcome = probe.probe(host)) {
                is ProbeOutcome.Ok -> ProbeState.Ok(outcome.millis)
                is ProbeOutcome.Failed -> ProbeState.Failed(outcome.reason)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { publish() }
    }

    override fun onCleared() {
        stopObserving()
    }

    private suspend fun publish() {
        val current = _state.value
        val fresh = withContext(Dispatchers.IO) { readState(current.ssidDraft, current.hostDraft) }
        _state.update { latest ->
            // Drafts may have moved while the world was being read; keep the newest keystrokes.
            fresh.withDrafts(latest.ssidDraft, latest.hostDraft)
        }
    }

    private fun refreshAfterServiceSettles() {
        viewModelScope.launch {
            delay(SERVICE_SETTLE_MS)
            publish()
        }
    }

    private fun readState(ssid: String, host: String): MonitorUiState {
        val savedRule = prefs.rule()
        val connection = snapshot.read()
        val location = app.getSystemService(android.location.LocationManager::class.java)
        return MonitorUiState(
            enabled = prefs.enabled,
            serviceRunning = ServiceRuntime.running,
            ssidDraft = ssid,
            hostDraft = host,
            ssidProblem = if (ssid.isBlank()) null else RuleValidation.ssidProblem(ssid),
            hostProblem = if (host.isBlank()) null else RuleValidation.hostProblem(host),
            draftRule = RuleValidation.ruleOrNull(ssid, host),
            savedRule = savedRule,
            connection = connection,
            desired = DnsPolicy.desired(connection, savedRule),
            systemState = settings.read(),
            lastCheckAt = prefs.lastCheckAt,
            lastChanged = prefs.lastChanged,
            lastError = prefs.lastError,
            lastTarget = prefs.lastTarget,
            writeGranted = granted(Manifest.permission.WRITE_SECURE_SETTINGS),
            locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            // Before Android 10 background location is implied by the foreground grant.
            backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            locationEnabled = location != null && LocationManagerCompat.isLocationEnabled(location),
            notificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled(),
            batteryExempt = app.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(app.packageName) == true,
            adbCommand = "adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS\n" +
                "adb shell pm grant ${app.packageName} android.permission.ACCESS_BACKGROUND_LOCATION",
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun startObserving() {
        if (observing) return
        observing = true
        runCatching { cm?.registerDefaultNetworkCallback(networkCallback) }
        val resolver = app.contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(PrivateDnsSettings.KEY_MODE), false, settingsObserver)
        resolver.registerContentObserver(Settings.Global.getUriFor(PrivateDnsSettings.KEY_SPECIFIER), false, settingsObserver)
        resolver.registerContentObserver(Settings.Secure.getUriFor(SettingsKeys.LOCATION_MODE), false, settingsObserver)
    }

    private fun stopObserving() {
        if (!observing) return
        observing = false
        runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        runCatching { app.contentResolver.unregisterContentObserver(settingsObserver) }
    }

    companion object {
        private const val SERVICE_SETTLE_MS = 400L

        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { MonitorViewModel(app) }
        }
    }
}

private fun MonitorUiState.withDrafts(ssid: String, host: String): MonitorUiState = copy(
    ssidDraft = ssid,
    hostDraft = host,
    ssidProblem = if (ssid.isBlank()) null else RuleValidation.ssidProblem(ssid),
    hostProblem = if (host.isBlank()) null else RuleValidation.hostProblem(host),
    draftRule = RuleValidation.ruleOrNull(ssid, host),
)
