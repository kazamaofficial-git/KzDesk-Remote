package com.kazamatech.kzdesk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    @Test fun redactsIpsEmailsAndDeviceIds() {
        val out = CrashReporter.redact("ConnectException: failed to 192.168.1.20:3389 for me@example.com id 123456789 host id-123456789.kzdesk.invalid")
        assertFalse(out.contains("192.168.1.20"))
        assertFalse(out.contains("me@example.com"))
        assertFalse(out.contains("123456789"))
        assertTrue(out.contains("<ip>") && out.contains("<email>") && out.contains("<id>"))
    }

    @Test fun keepsStackFramesReadable() {
        val frame = "at com.kazamatech.kzdesk.ui.Foo.bar(Foo.kt:42)"
        assertTrue(CrashReporter.redact(frame).contains("Foo.kt:42"))
    }

    @Test fun truncatesHugeTraces() {
        assertEquals(8000, CrashReporter.redact("x".repeat(50_000)).length)
    }
}
