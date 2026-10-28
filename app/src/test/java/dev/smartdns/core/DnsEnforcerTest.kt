package dev.smartdns.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsEnforcerTest {
    private val rule = DnsRule("MyPhone", "dns.google")
    private val target = ConnectionState.Wifi("Cafe")

    private class FakePort(
        var state: PrivateDnsState = PrivateDnsState("off", ""),
        private val accept: Boolean = true,
        private val apply: Boolean = true,
    ) : PrivateDnsPort {
        val writes = mutableListOf<PrivateDnsTarget>()
        var reads = 0

        override fun read(): PrivateDnsState {
            reads++
            return state
        }

        override fun write(target: PrivateDnsTarget): Boolean {
            writes += target
            if (apply) state = PrivateDnsState(target.mode.value, target.specifier)
            return accept
        }
    }

    @Test
    fun `disabled monitoring writes nothing and does not even read`() {
        val port = FakePort()
        val result = DnsEnforcer(port).reconcile(ConnectionState.Wifi("Cafe"), rule, enabled = false)
        assertEquals(Desired.Keep, result.desired)
        assertFalse(result.changed)
        assertNull(result.error)
        assertEquals(0, port.reads)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `matching value is left untouched`() {
        val port = FakePort(PrivateDnsState("hostname", "dns.google"))
        val result = DnsEnforcer(port).reconcile(target, rule, enabled = true)
        assertEquals(Desired.Apply(PrivateDnsTarget.hosted("dns.google")), result.desired)
        assertFalse(result.changed)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `writes dot off the hotspot`() {
        val port = FakePort()
        val result = DnsEnforcer(port).reconcile(target, rule, enabled = true)
        assertTrue(result.changed)
        assertNull(result.error)
        assertEquals(listOf(PrivateDnsTarget(PrivateDnsMode.HOSTNAME, "dns.google")), port.writes)
    }

    @Test
    fun `writes automatic on the hotspot and clears the specifier`() {
        val port = FakePort(PrivateDnsState("hostname", "dns.google"))
        val result = DnsEnforcer(port).reconcile(ConnectionState.Wifi("MyPhone"), rule, enabled = true)
        assertTrue(result.changed)
        assertEquals(listOf(PrivateDnsTarget(PrivateDnsMode.OPPORTUNISTIC, "")), port.writes)
        assertEquals(PrivateDnsState("opportunistic", ""), port.state)
    }

    @Test
    fun `second reconcile is a no-op`() {
        val port = FakePort()
        val enforcer = DnsEnforcer(port)
        enforcer.reconcile(target, rule, enabled = true)
        val again = enforcer.reconcile(target, rule, enabled = true)
        assertFalse(again.changed)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun `denied write is reported and not retried in the same pass`() {
        val port = FakePort(accept = false)
        val result = DnsEnforcer(port).reconcile(target, rule, enabled = true)
        assertFalse(result.changed)
        assertEquals(EnforceError.WRITE_DENIED, result.error)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun `write accepted but ignored is reported`() {
        val port = FakePort(apply = false)
        val result = DnsEnforcer(port).reconcile(target, rule, enabled = true)
        assertFalse(result.changed)
        assertEquals(EnforceError.WRITE_NOT_APPLIED, result.error)
    }

    @Test
    fun `empty stored specifier equals an unset one for automatic`() {
        val port = FakePort(PrivateDnsState("opportunistic", null))
        val result = DnsEnforcer(port).reconcile(ConnectionState.Wifi("MyPhone"), rule, enabled = true)
        assertFalse(result.changed)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `offline does not write`() {
        val port = FakePort()
        val result = DnsEnforcer(port).reconcile(ConnectionState.Offline, rule, enabled = true)
        assertEquals(Desired.Keep, result.desired)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `incomplete rule does not read or write`() {
        val port = FakePort()
        val result = DnsEnforcer(port).reconcile(target, null, enabled = true)
        assertEquals(Desired.Keep, result.desired)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `unreadable ssid writes dot`() {
        val port = FakePort()
        val result = DnsEnforcer(port).reconcile(ConnectionState.Wifi(null), rule, enabled = true)
        assertEquals(PrivateDnsMode.HOSTNAME, result.target?.mode)
        assertEquals(1, port.writes.size)
    }
}
