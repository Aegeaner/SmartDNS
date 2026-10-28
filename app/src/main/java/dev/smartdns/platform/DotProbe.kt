package dev.smartdns.platform

import android.os.SystemClock
import java.io.IOException
import java.net.InetSocketAddress
import java.security.GeneralSecurityException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ProbeOutcome {
    data class Ok(val millis: Int) : ProbeOutcome

    data class Failed(val reason: String) : ProbeOutcome
}

/**
 * A real DoT handshake: TCP 853, SNI, and HTTPS-style hostname verification against the presented
 * certificate. A mistyped DoT host in "hostname" mode takes DNS down device-wide, so this is worth
 * checking before saving.
 */
class DotProbe {
    suspend fun probe(host: String, timeoutMillis: Int = DEFAULT_TIMEOUT_MS): ProbeOutcome =
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            var socket: SSLSocket? = null
            try {
                val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                socket = ssl
                ssl.connect(InetSocketAddress(host, DOT_PORT), timeoutMillis)
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                params.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = params
                ssl.soTimeout = timeoutMillis
                ssl.startHandshake()
                ProbeOutcome.Ok((SystemClock.elapsedRealtime() - started).toInt())
            } catch (e: IOException) {
                ProbeOutcome.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: GeneralSecurityException) {
                ProbeOutcome.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { socket?.close() }
            }
        }

    private companion object {
        const val DOT_PORT = 853
        const val DEFAULT_TIMEOUT_MS = 5_000
    }
}
