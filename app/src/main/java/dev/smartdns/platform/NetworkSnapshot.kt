package dev.smartdns.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import dev.smartdns.core.ConnectionState
import dev.smartdns.core.OtherTransport
import dev.smartdns.core.RuleValidation

/**
 * The default network as a [ConnectionState].
 *
 * The transport is classified from NetworkCapabilities, but the SSID comes from WifiManager:
 * AOSP ConnectivityService calls createWithLocationInfoSanitizedIfNecessaryWhenParceled with
 * includeLocationSensitiveInfo=false for the synchronous getNetworkCapabilities() call, so the
 * Wi-Fi transport info in those capabilities is always location-redacted. getConnectionInfo()
 * applies the caller's own location grant instead.
 */
class NetworkSnapshot(private val context: Context) {
    fun read(): ConnectionState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ConnectionState.Offline
        val network = cm.activeNetwork ?: return ConnectionState.Offline
        val capabilities = cm.getNetworkCapabilities(network) ?: return ConnectionState.Offline
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                ConnectionState.Wifi(currentSsid())
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                ConnectionState.Other(OtherTransport.CELLULAR)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                ConnectionState.Other(OtherTransport.ETHERNET)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                ConnectionState.Other(OtherTransport.VPN)
            else -> ConnectionState.Other(OtherTransport.OTHER)
        }
    }

    /**
     * WifiManager.getConnectionInfo() is deprecated in favour of network callbacks, but a callback
     * would only hand us the SSID asynchronously, and the synchronous capabilities are redacted by
     * design. The platform still returns the real SSID here when the app holds a location grant
     * and location services are on; otherwise it returns "<unknown ssid>", which becomes null.
     */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val raw = try {
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        } catch (e: SecurityException) {
            null
        }
        return RuleValidation.visibleSsid(raw)
    }
}
