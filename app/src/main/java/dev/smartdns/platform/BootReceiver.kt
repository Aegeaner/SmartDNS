package dev.smartdns.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED are foreground-service start exemptions, so the monitor
 * comes back after a reboot or an update without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = SmartDnsPrefs(context)
        if (prefs.enabled && prefs.rule() != null) {
            MonitorServiceControl.start(context)
        }
    }
}
