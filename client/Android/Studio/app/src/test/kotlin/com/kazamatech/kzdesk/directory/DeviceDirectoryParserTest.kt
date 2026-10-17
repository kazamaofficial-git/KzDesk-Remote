package com.kazamatech.kzdesk.directory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDirectoryParserTest {
    private fun ok(vararg c: String) =
        """{"status":"ok","display_id":"123-456-789","hostname":"PC","username":"KzDesk","candidates":[${c.joinToString(",")}]}"""

    private fun cand(kind: String, host: String, port: Int = 3389) = """{"kind":"$kind","host":"$host","port":$port}"""

    @Test fun normalizesIds() {
        assertEquals("123456789", DeviceDirectoryParser.normalizeId("123-456-789"))
        assertEquals("123456789", DeviceDirectoryParser.normalizeId(" 123 456 789 "))
        assertNull(DeviceDirectoryParser.normalizeId("012345678"))
        assertNull(DeviceDirectoryParser.normalizeId("12345678"))
        assertNull(DeviceDirectoryParser.normalizeId("12345678a"))
        assertNull(DeviceDirectoryParser.normalizeId(null))
        assertEquals("123-456-789", DeviceDirectoryParser.formatId("123456789"))
    }

    @Test fun parsesFoundAndOrdersLanFirst() {
        val r = DeviceDirectoryParser.parse(200, ok(cand("public", "203.0.113.5"), cand("vpn", "100.64.1.2"), cand("lan", "192.168.1.9")))
        r as DirectoryResult.Found
        assertEquals(listOf("lan", "vpn", "public"), r.candidates.map { it.kind })
        assertEquals("KzDesk", r.username)
        assertEquals("123-456-789", r.displayId)
    }

    @Test fun dropsBadCandidatesAndTreatsEmptyAsOffline() {
        val r = DeviceDirectoryParser.parse(200, ok(cand("lan", "", 3389), cand("lan", "10.0.0.1", 70000)))
        assertEquals(DirectoryResult.Failed(DirectoryError.OFFLINE), r)
    }

    @Test fun mapsStatusCodes() {
        assertEquals(DirectoryError.WRONG_ID_OR_PASSWORD, (DeviceDirectoryParser.parse(404, """{"status":"not_found_or_wrong_password"}""") as DirectoryResult.Failed).error)
        assertEquals(DirectoryError.OFFLINE, (DeviceDirectoryParser.parse(409, """{"status":"offline"}""") as DirectoryResult.Failed).error)
        assertEquals(DirectoryError.INVALID_INPUT, (DeviceDirectoryParser.parse(400, "{}") as DirectoryResult.Failed).error)
        assertEquals(DirectoryError.SERVER, (DeviceDirectoryParser.parse(500, "oops") as DirectoryResult.Failed).error)
        val rl = DeviceDirectoryParser.parse(429, """{"status":"rate_limited","retry_after_ms":4500}""") as DirectoryResult.Failed
        assertEquals(DirectoryError.RATE_LIMITED, rl.error)
        assertEquals(4500L, rl.retryAfterMs)
    }

    @Test fun garbageBodyOn200IsAServerError() {
        assertEquals(DirectoryResult.Failed(DirectoryError.SERVER), DeviceDirectoryParser.parse(200, "not json"))
        assertEquals(DirectoryResult.Failed(DirectoryError.SERVER), DeviceDirectoryParser.parse(200, """{"status":"nope"}"""))
    }

    @Test fun everyErrorHasBilingualText() {
        DirectoryError.entries.forEach { e ->
            val (id, en) = DeviceDirectoryParser.message(e, 3000)
            assertTrue(id.isNotBlank() && en.isNotBlank())
        }
    }
}
