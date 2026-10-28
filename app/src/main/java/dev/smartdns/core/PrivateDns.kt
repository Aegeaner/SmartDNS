package dev.smartdns.core

/**
 * The three values the platform stores in Settings.Global.private_dns_mode. These strings are the
 * AOSP on-disk values, not the localized labels; "自动" in the system UI is OPPORTUNISTIC.
 */
enum class PrivateDnsMode(val value: String) {
    OFF("off"),
    OPPORTUNISTIC("opportunistic"),
    HOSTNAME("hostname");

    companion object {
        fun fromValue(value: String?): PrivateDnsMode? = entries.firstOrNull { it.value == value }
    }
}

/** What the system currently holds. Either half can be null when the key has never been written. */
data class PrivateDnsState(val mode: String?, val specifier: String?)

/** What this app wants the system to hold. */
data class PrivateDnsTarget(val mode: PrivateDnsMode, val specifier: String = "") {
    companion object {
        fun automatic() = PrivateDnsTarget(PrivateDnsMode.OPPORTUNISTIC)

        fun hosted(host: String) = PrivateDnsTarget(PrivateDnsMode.HOSTNAME, host)
    }
}

sealed interface Desired {
    /** Nothing to enforce: offline, monitoring off, or the rule is incomplete. */
    data object Keep : Desired

    data class Apply(val target: PrivateDnsTarget) : Desired
}
