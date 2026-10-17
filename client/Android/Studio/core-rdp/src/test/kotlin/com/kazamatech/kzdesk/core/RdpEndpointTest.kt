package com.kazamatech.kzdesk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RdpEndpointTest {
    @Test
    fun parsesHostWithDefaultAndOptionalPort() {
        assertEquals(RdpEndpoint("rdp.example"), parseRdpEndpoint("rdp.example"))
        assertEquals(RdpEndpoint("192.0.2.4", 3390), parseRdpEndpoint("192.0.2.4:3390"))
        assertEquals(RdpEndpoint("rdp.example", 3390), parseRdpEndpoint(" rdp.example:3390 "))
    }

    @Test
    fun parsesIpv6WithDefaultOrBracketedPort() {
        assertEquals(RdpEndpoint("2001:db8::1"), parseRdpEndpoint("2001:db8::1"))
        assertEquals(RdpEndpoint("2001:db8::1", 3390), parseRdpEndpoint("[2001:db8::1]:3390"))
    }

    @Test
    fun rejectsInvalidPortSyntax() {
        assertNull(parseRdpEndpoint("rdp.example:"))
        assertNull(parseRdpEndpoint("rdp.example:abc"))
        assertNull(parseRdpEndpoint("rdp.example:0"))
        assertNull(parseRdpEndpoint("rdp.example:65536"))
        assertNull(parseRdpEndpoint("[2001:db8::1]:abc"))
        assertNull(parseRdpEndpoint(""))
    }

    @Test
    fun formatsExistingSavedPortWithoutChangingDefault() {
        assertEquals("rdp.example", formatRdpEndpoint("rdp.example", 3389))
        assertEquals("rdp.example:3390", formatRdpEndpoint("rdp.example", 3390))
        assertEquals("[2001:db8::1]:3390", formatRdpEndpoint("2001:db8::1", 3390))
    }

    @Test
    fun encodesAndParsesPcIdRoundTrip() {
        val pcId = encodeIpv4ToPcId("192.168.1.50")
        assertEquals("323-223-5826", pcId)
        assertEquals(RdpEndpoint("192.168.1.50", 3389), parsePcIdOrEndpoint("323-223-5826"))
        assertEquals(RdpEndpoint("192.168.1.50", 3390), parsePcIdOrEndpoint("323-223-5826:3390"))
        assertEquals(RdpEndpoint("192.168.1.50", 3389), parsePcIdOrEndpoint("XY-C0A8-0132"))
        assertEquals(RdpEndpoint("100.84.12.9", 3389), parsePcIdOrEndpoint(encodeIpv4ToPcId("100.84.12.9")!!))
    }
}
