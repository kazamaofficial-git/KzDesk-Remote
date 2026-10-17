package com.kazamatech.kzdesk.directory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrTest {
    @Test fun roundTrip() {
        val p = PairingQr.parse(PairingQr.build("123456789", "Ab3dEfGh9k"))!!
        assertEquals("123456789", p.id)
        assertEquals("Ab3dEfGh9k", p.password)
    }

    @Test fun decodesPercentEncodedPasswords() {
        assertEquals("a b&c", PairingQr.parse("kzdesk://pair?v=1&id=123456789&pw=a%20b%26c")!!.password)
    }

    @Test fun rejectsForeignOrMalformedPayloads() {
        assertNull(PairingQr.parse(null))
        assertNull(PairingQr.parse("https://example.com/?id=123456789&pw=abcd"))
        assertNull(PairingQr.parse("kzdesk://pair?v=2&id=123456789&pw=abcd"))
        assertNull(PairingQr.parse("kzdesk://pair?v=1&id=12345&pw=abcd"))
        assertNull(PairingQr.parse("kzdesk://pair?v=1&id=123456789&pw=ab"))
        assertNull(PairingQr.parse("kzdesk://pair?v=1&id=123456789"))
        assertNull(PairingQr.parse("kzdesk://pair?" + "x".repeat(600)))
    }
}
