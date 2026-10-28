package dev.smartdns.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPolicyTest {
    private val rule = DnsRule("MyPhone", "dns.google")
    private val automatic = Desired.Apply(PrivateDnsTarget(PrivateDnsMode.OPPORTUNISTIC))
    private val hosted = Desired.Apply(PrivateDnsTarget(PrivateDnsMode.HOSTNAME, "dns.google"))

    @Test
    fun `target hotspot means automatic`() {
        assertEquals(automatic, DnsPolicy.desired(ConnectionState.Wifi("MyPhone"), rule))
        assertTrue(DnsPolicy.onTargetHotspot(ConnectionState.Wifi("MyPhone"), rule))
    }

    @Test
    fun `other wifi means dot`() {
        assertEquals(hosted, DnsPolicy.desired(ConnectionState.Wifi("Cafe"), rule))
        assertFalse(DnsPolicy.onTargetHotspot(ConnectionState.Wifi("Cafe"), rule))
    }

    @Test
    fun `unreadable ssid fails closed to dot`() {
        assertEquals(hosted, DnsPolicy.desired(ConnectionState.Wifi(null), rule))
        assertFalse(DnsPolicy.onTargetHotspot(ConnectionState.Wifi(null), rule))
    }

    @Test
    fun `cellular ethernet and vpn mean dot`() {
        for (transport in OtherTransport.entries) {
            assertEquals(hosted, DnsPolicy.desired(ConnectionState.Other(transport), rule))
        }
    }

    @Test
    fun `offline keeps the system value`() {
        assertEquals(Desired.Keep, DnsPolicy.desired(ConnectionState.Offline, rule))
    }

    @Test
    fun `missing rule keeps the system value`() {
        assertEquals(Desired.Keep, DnsPolicy.desired(ConnectionState.Wifi("Cafe"), null))
        assertEquals(Desired.Keep, DnsPolicy.desired(ConnectionState.Other(OtherTransport.CELLULAR), null))
    }

    @Test
    fun `ssid match is exact`() {
        assertEquals(hosted, DnsPolicy.desired(ConnectionState.Wifi("myphone"), rule))
        assertEquals(hosted, DnsPolicy.desired(ConnectionState.Wifi("MyPhone "), rule))
    }
}
