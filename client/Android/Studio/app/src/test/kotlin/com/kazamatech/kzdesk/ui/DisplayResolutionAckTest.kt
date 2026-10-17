package com.kazamatech.kzdesk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayResolutionAckTest {
    @Test
    fun onlyReportedRequestedDimensionsCountAsConfirmed() {
        val requested = 1920 to 1080
        assertTrue(remoteResolutionMatches(requested, 1920, 1080))
        assertFalse(remoteResolutionMatches(requested, 1280, 720))
        assertFalse(remoteResolutionMatches(requested, 1920, 1078))
    }

    @Test
    fun requestedDimensionsUseTheSameBoundsAsTheRdpSender() {
        assertEquals(1080 to 2340, normalizedRemoteResolution(1081, 2340))
        assertEquals(640 to 480, normalizedRemoteResolution(200, 100))
        assertEquals(8192 to 8192, normalizedRemoteResolution(9000, 9000))
    }
}
