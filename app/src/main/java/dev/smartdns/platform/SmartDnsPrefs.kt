package dev.smartdns.platform

import android.content.Context
import androidx.core.content.edit
import dev.smartdns.core.DnsRule
import dev.smartdns.core.EnforceError
import dev.smartdns.core.PrivateDnsMode
import dev.smartdns.core.PrivateDnsTarget
import dev.smartdns.core.ReconcileResult
import dev.smartdns.core.RuleValidation

/**
 * The rule and the last reconcile outcome. Plain SharedPreferences: two strings and a few scalars,
 * no index to rebuild, and every write is a single editor transaction.
 */
class SmartDnsPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
        }

    val ssid: String get() = prefs.getString(KEY_SSID, "").orEmpty()

    val host: String get() = prefs.getString(KEY_HOST, "").orEmpty()

    fun rule(): DnsRule? = RuleValidation.ruleOrNull(ssid, host)

    fun saveRule(ssidRaw: String, hostRaw: String) {
        prefs.edit {
            putString(KEY_SSID, RuleValidation.normalizeSsid(ssidRaw))
            putString(KEY_HOST, RuleValidation.normalizeHost(hostRaw))
        }
    }

    val lastCheckAt: Long get() = prefs.getLong(KEY_LAST_AT, 0L)

    val lastChanged: Boolean get() = prefs.getBoolean(KEY_LAST_CHANGED, false)

    val lastError: EnforceError?
        get() = prefs.getString(KEY_LAST_ERROR, null)?.let { stored ->
            EnforceError.entries.firstOrNull { it.name == stored }
        }

    val lastTarget: PrivateDnsTarget?
        get() = PrivateDnsMode.fromValue(prefs.getString(KEY_LAST_MODE, null))?.let { mode ->
            PrivateDnsTarget(mode, prefs.getString(KEY_LAST_SPECIFIER, "").orEmpty())
        }

    fun record(result: ReconcileResult, at: Long) {
        prefs.edit {
            putLong(KEY_LAST_AT, at)
            putBoolean(KEY_LAST_CHANGED, result.changed)
            putString(KEY_LAST_ERROR, result.error?.name)
            putString(KEY_LAST_MODE, result.target?.mode?.value)
            putString(KEY_LAST_SPECIFIER, result.target?.specifier.orEmpty())
        }
    }

    private companion object {
        const val FILE = "smart_dns"
        const val KEY_ENABLED = "enabled"
        const val KEY_SSID = "ssid"
        const val KEY_HOST = "host"
        const val KEY_LAST_AT = "last_check_at"
        const val KEY_LAST_CHANGED = "last_changed"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_MODE = "last_mode"
        const val KEY_LAST_SPECIFIER = "last_specifier"
    }
}
