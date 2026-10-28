package dev.smartdns.core

/**
 * The user's rule: on [hotspotSsid] Private DNS must be automatic, everywhere else it must be the
 * DNS-over-TLS host [dotHost]. Both fields are stored normalized and validated.
 */
data class DnsRule(val hotspotSsid: String, val dotHost: String)

enum class RuleProblem {
    SSID_EMPTY,
    SSID_TOO_LONG,
    HOST_EMPTY,
    HOST_IS_IP,
    HOST_TOO_LONG,
    HOST_LABEL_INVALID,
    HOST_LABEL_TOO_LONG,
    HOST_NEEDS_DOT,
}

object RuleValidation {
    /** An 802.11 SSID is at most 32 bytes; the platform truncates anything longer at the radio. */
    const val SSID_MAX_BYTES = 32

    /** RFC 1035 name limit. */
    const val HOST_MAX_LENGTH = 253

    private const val LABEL_MAX_LENGTH = 63
    private const val UNKNOWN_SSID = "<unknown ssid>"

    private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
    private val IPV4 = Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")
    private val IPV6_BODY = Regex("[0-9a-f:.]+")

    /** WifiInfo quotes a UTF-8 SSID; users paste both forms. */
    fun normalizeSsid(raw: String): String = raw.trim().removeSurrounding("\"").trim()

    /** A trailing root dot is legal but Android compares the bare name. */
    fun normalizeHost(raw: String): String = raw.trim().trimEnd('.').lowercase()

    /**
     * The SSID as the platform reports it, or null when the platform refuses to reveal it
     * (no location permission or location services off) or there is no such network.
     */
    fun visibleSsid(raw: String?): String? {
        val ssid = raw?.removeSurrounding("\"")?.trim().orEmpty()
        return ssid.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    fun ssidProblem(raw: String): RuleProblem? {
        val ssid = normalizeSsid(raw)
        return when {
            ssid.isEmpty() -> RuleProblem.SSID_EMPTY
            ssid.toByteArray(Charsets.UTF_8).size > SSID_MAX_BYTES -> RuleProblem.SSID_TOO_LONG
            else -> null
        }
    }

    fun hostProblem(raw: String): RuleProblem? {
        val host = normalizeHost(raw)
        if (host.isEmpty()) return RuleProblem.HOST_EMPTY
        // DoT specifiers are names validated against a certificate, never literals. A colon alone
        // is not enough: "https://dns.google" must fail as a name, not pass as an IPv6 literal.
        if (host.startsWith("[") || IPV4.matches(host) || (host.contains(':') && IPV6_BODY.matches(host))) {
            return RuleProblem.HOST_IS_IP
        }
        if (host.length > HOST_MAX_LENGTH) return RuleProblem.HOST_TOO_LONG

        val labels = host.split('.')
        if (labels.any { it.length > LABEL_MAX_LENGTH }) return RuleProblem.HOST_LABEL_TOO_LONG
        if (labels.any { it.isEmpty() || !LABEL.matches(it) }) return RuleProblem.HOST_LABEL_INVALID
        // A single-label or numeric-TLD name cannot carry a publicly trusted certificate.
        if (labels.size < 2 || labels.last().all(Char::isDigit)) return RuleProblem.HOST_NEEDS_DOT
        return null
    }

    fun ruleOrNull(ssid: String, host: String): DnsRule? =
        if (ssidProblem(ssid) == null && hostProblem(host) == null) {
            DnsRule(normalizeSsid(ssid), normalizeHost(host))
        } else {
            null
        }
}
