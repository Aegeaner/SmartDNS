package dev.smartdns.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Same-process liveness flag. If the process dies the flag dies with it, so it never reports a
 * service that is not there; onDestroy flips it back for an orderly stop.
 */
object ServiceRuntime {
    @Volatile
    var running: Boolean = false
}

object MonitorServiceControl {
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, DnsMonitorService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, DnsMonitorService::class.java))
    }
}
