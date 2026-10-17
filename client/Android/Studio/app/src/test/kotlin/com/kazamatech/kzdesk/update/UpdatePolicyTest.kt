package com.kazamatech.kzdesk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val sha = "ab".repeat(32)

    private fun body(update: Boolean = true, url: String = "https://github.com/kazamadev/KzDesk-Remote/releases/download/v1.2.0/a.apk", hash: String = sha) =
        """{"status":"ok","update_available":$update,"mandatory":false,"version":"1.2.0","version_code":10200,
            "notes":"n","release_url":"https://github.com/x","asset":{"abi":"arm64-v8a","file":"a.apk","url":"$url","size":1234,"sha256":"$hash"}}"""

    @Test fun parsesAvailableUpdate() {
        val i = UpdatePolicy.parse(body())!!
        assertTrue(i.available)
        assertEquals(10200L, i.versionCode)
        assertEquals("arm64-v8a", i.abi)
        assertEquals(1234L, i.sizeBytes)
    }

    @Test fun rejectsGarbageAndErrorStatus() {
        assertNull(UpdatePolicy.parse("not json"))
        assertNull(UpdatePolicy.parse("""{"status":"unavailable"}"""))
    }

    @Test fun updateWithoutVerifiableAssetIsNeverAvailable() {
        assertFalse(UpdatePolicy.parse(body(hash = "xyz"))!!.available)
        assertFalse(UpdatePolicy.parse(body(url = "http://github.com/a.apk"))!!.available)
        assertFalse(UpdatePolicy.parse(body(url = "https://evil.example/a.apk"))!!.available)
        assertNotNull(UpdatePolicy.parse(body(update = false)))
        assertFalse(UpdatePolicy.parse(body(update = false))!!.available)
    }

    @Test fun trustedHostsAreStrict() {
        val base = "https://kzdesk.kazamasite.biz.id"
        assertTrue(UpdatePolicy.isTrustedDownloadUrl("https://github.com/o/r/releases/download/v1/a.apk", base))
        assertTrue(UpdatePolicy.isTrustedDownloadUrl("https://objects.githubusercontent.com/x", base))
        assertTrue(UpdatePolicy.isTrustedDownloadUrl("https://kzdesk.kazamasite.biz.id/dl/app/arm64-v8a", base))
        assertFalse(UpdatePolicy.isTrustedDownloadUrl("https://github.com.evil.com/a.apk", base))
        assertFalse(UpdatePolicy.isTrustedDownloadUrl("https://evilgithub.com/a.apk", base))
        assertFalse(UpdatePolicy.isTrustedDownloadUrl("https://user:pw@github.com/a.apk", base))
        assertFalse(UpdatePolicy.isTrustedDownloadUrl("ftp://github.com/a.apk", base))
        assertFalse(UpdatePolicy.isTrustedDownloadUrl("%%%", base))
    }

    @Test fun checkIntervalGate() {
        val h = 3_600_000L
        assertTrue(UpdatePolicy.shouldCheck(0, 1000, false))
        assertFalse(UpdatePolicy.shouldCheck(1000, 1000 + 5 * h, false))
        assertTrue(UpdatePolicy.shouldCheck(1000, 1000 + 6 * h, false))
        assertTrue(UpdatePolicy.shouldCheck(1000, 1001, true))
        assertTrue("clock went backwards", UpdatePolicy.shouldCheck(5000, 100, false))
    }

    @Test fun abiQueryKeepsOrderAndDropsUnknown() {
        assertEquals("arm64-v8a,armeabi-v7a", UpdatePolicy.abiQuery(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("", UpdatePolicy.abiQuery(arrayOf("mips")))
    }
}
