package dev.smartdns.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleValidationTest {
    @Test
    fun `ssid is trimmed and unquoted`() {
        assertEquals("MyPhone", RuleValidation.normalizeSsid("  \"MyPhone\" "))
        assertNull(RuleValidation.ssidProblem("\"MyPhone\""))
    }

    @Test
    fun `ssid rejects empty and over 32 bytes`() {
        assertEquals(RuleProblem.SSID_EMPTY, RuleValidation.ssidProblem("   "))
        assertEquals(RuleProblem.SSID_TOO_LONG, RuleValidation.ssidProblem("a".repeat(33)))
        // 17 two-byte characters are 34 bytes even though the string is 17 characters long.
        assertEquals(RuleProblem.SSID_TOO_LONG, RuleValidation.ssidProblem("é".repeat(17)))
        assertNull(RuleValidation.ssidProblem("é".repeat(16)))
    }

    @Test
    fun `ssid spaces and unicode are legal`() {
        assertNull(RuleValidation.ssidProblem("My Phone 5G"))
        assertNull(RuleValidation.ssidProblem("热点"))
    }

    @Test
    fun `host is lowercased and the root dot is dropped`() {
        assertEquals("dns.google", RuleValidation.normalizeHost(" DNS.Google. "))
        assertEquals("dns.google", RuleValidation.normalizeHost("dns.google"))
        assertNull(RuleValidation.hostProblem("DNS.Google."))
    }

    @Test
    fun `host rejects literals`() {
        assertEquals(RuleProblem.HOST_IS_IP, RuleValidation.hostProblem("1.1.1.1"))
        assertEquals(RuleProblem.HOST_IS_IP, RuleValidation.hostProblem("2606:4700:4700::1111"))
        assertEquals(RuleProblem.HOST_IS_IP, RuleValidation.hostProblem("[2606:4700::1111]"))
    }

    @Test
    fun `host rejects schemes paths and spaces`() {
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("https://dns.google"))
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("dns.google/dns-query"))
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("dns google"))
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("dns..google"))
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("_dns.google"))
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("-dns.google"))
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("dns-.google"))
        assertEquals(RuleProblem.HOST_LABEL_INVALID, RuleValidation.hostProblem("dns.google:853"))
    }

    @Test
    fun `host rejects single labels and numeric tlds`() {
        assertEquals(RuleProblem.HOST_NEEDS_DOT, RuleValidation.hostProblem("localhost"))
        assertEquals(RuleProblem.HOST_NEEDS_DOT, RuleValidation.hostProblem("dns.123"))
    }

    @Test
    fun `host enforces RFC 1035 lengths`() {
        val label63 = "a".repeat(63)
        assertNull(RuleValidation.hostProblem("$label63.example"))
        assertEquals(RuleProblem.HOST_LABEL_TOO_LONG, RuleValidation.hostProblem("${"a".repeat(64)}.example"))

        val longName = List(4) { label63 }.joinToString(".")
        assertEquals(255, longName.length)
        assertEquals(RuleProblem.HOST_TOO_LONG, RuleValidation.hostProblem(longName))

        val fits = List(4) { label63 }.joinToString(".").dropLast(2)
        assertNull(RuleValidation.hostProblem(fits))
    }

    @Test
    fun `rule requires both halves to be valid`() {
        assertNull(RuleValidation.ruleOrNull("", "dns.google"))
        assertNull(RuleValidation.ruleOrNull("MyPhone", "1.1.1.1"))
        assertEquals(
            DnsRule("MyPhone", "dns.google"),
            RuleValidation.ruleOrNull(" \"MyPhone\" ", "DNS.Google."),
        )
    }

    @Test
    fun `visible ssid hides redaction and blanks`() {
        assertNull(RuleValidation.visibleSsid(null))
        assertNull(RuleValidation.visibleSsid(""))
        assertNull(RuleValidation.visibleSsid("\"\""))
        assertNull(RuleValidation.visibleSsid("\"<unknown ssid>\""))
        assertEquals("MyPhone", RuleValidation.visibleSsid("\"MyPhone\""))
    }
}
