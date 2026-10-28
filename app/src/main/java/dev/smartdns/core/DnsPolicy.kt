package dev.smartdns.core

enum class OtherTransport { CELLULAR, ETHERNET, VPN, OTHER }

sealed interface ConnectionState {
    data object Offline : ConnectionState

    /** [ssid] is null when the platform redacts it (no location permission / location off). */
    data class Wifi(val ssid: String?) : ConnectionState

    data class Other(val transport: OtherTransport) : ConnectionState
}

/**
 * Pure policy. Every branch is deliberate:
 *  - offline: there is no network to resolve on, so leave the system alone;
 *  - Wi-Fi with an unreadable SSID: treat it as "not the target" and fail closed to DoT, because
 *    the alternative silently leaks DNS whenever location is turned off;
 *  - any other transport: DoT.
 */
object DnsPolicy {
    fun desired(connection: ConnectionState, rule: DnsRule?): Desired {
        if (rule == null) return Desired.Keep
        return when (connection) {
            ConnectionState.Offline -> Desired.Keep
            is ConnectionState.Wifi ->
                if (connection.ssid != null && connection.ssid == rule.hotspotSsid) {
                    Desired.Apply(PrivateDnsTarget.automatic())
                } else {
                    Desired.Apply(PrivateDnsTarget.hosted(rule.dotHost))
                }
            is ConnectionState.Other -> Desired.Apply(PrivateDnsTarget.hosted(rule.dotHost))
        }
    }

    fun onTargetHotspot(connection: ConnectionState, rule: DnsRule?): Boolean =
        rule != null && connection is ConnectionState.Wifi && connection.ssid == rule.hotspotSsid
}
