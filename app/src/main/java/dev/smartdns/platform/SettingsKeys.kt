package dev.smartdns.platform

/**
 * Secure-setting keys that Android does not expose (or exposes only as a deprecated constant).
 * Values are the on-disk strings the platform itself writes.
 */
internal object SettingsKeys {
    /** Settings.Secure.LOCATION_MODE: the master location toggle the platform flips. */
    const val LOCATION_MODE = "location_mode"
}
