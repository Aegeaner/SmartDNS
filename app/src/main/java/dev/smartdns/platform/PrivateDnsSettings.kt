package dev.smartdns.platform

import android.content.ContentResolver
import android.provider.Settings
import dev.smartdns.core.PrivateDnsMode
import dev.smartdns.core.PrivateDnsPort
import dev.smartdns.core.PrivateDnsState
import dev.smartdns.core.PrivateDnsTarget

/**
 * Settings.Global.private_dns_mode / private_dns_specifier. Both keys are @hide, so they are
 * spelled out here instead of referencing the SDK constants; the values are AOSP's on-disk strings.
 *
 * Writes need WRITE_SECURE_SETTINGS (signature|privileged|development). Without it the provider
 * throws SecurityException, which is reported as a refused write instead of crashing.
 */
class PrivateDnsSettings(private val resolver: ContentResolver) : PrivateDnsPort {
    override fun read(): PrivateDnsState {
        val mode = runCatching { Settings.Global.getString(resolver, KEY_MODE) }.getOrNull()
        val specifier = runCatching { Settings.Global.getString(resolver, KEY_SPECIFIER) }.getOrNull()
        return PrivateDnsState(mode, specifier)
    }

    override fun write(target: PrivateDnsTarget): Boolean = try {
        when (target.mode) {
            // The specifier goes first: the resolver must never observe hostname mode without a
            // host, which would make DNS fail-closed for the duration between the two writes.
            PrivateDnsMode.HOSTNAME ->
                Settings.Global.putString(resolver, KEY_SPECIFIER, target.specifier) &&
                    Settings.Global.putString(resolver, KEY_MODE, target.mode.value)

            // Opposite order: stop asking for a hostname before clearing it.
            PrivateDnsMode.OPPORTUNISTIC, PrivateDnsMode.OFF ->
                Settings.Global.putString(resolver, KEY_MODE, target.mode.value) &&
                    Settings.Global.putString(resolver, KEY_SPECIFIER, target.specifier)
        }
    } catch (e: SecurityException) {
        false
    }

    companion object {
        const val KEY_MODE = "private_dns_mode"
        const val KEY_SPECIFIER = "private_dns_specifier"
    }
}
