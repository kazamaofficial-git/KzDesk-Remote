package com.kazamatech.kzdesk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartResolutionTest {

    @Test
    fun automaticRequestUses720pForPortraitAndLandscape() {
        assertEquals(
            "1280x720",
            SmartResolution.requestedForSession(null, false, 1080, 2400),
        )
        assertEquals(
            "1280x720",
            SmartResolution.requestedForSession("automatic", false, 2400, 1080),
        )
        assertEquals(
            "1280x720",
            SmartResolution.requestedForSession("smart169", false, 2560, 1600),
        )
    }

    @Test
    fun pcConnectNeverForcesASavedVirtualResolution() {
        listOf(null, "automatic", "smart169", "1280x720", "1920x1080", "follow").forEach { preset ->
            assertNull(SmartResolution.requestedForSession(preset, true, 2400, 1080))
        }
    }

    @Test
    fun followModeLeavesTheRemoteSizeUnpinned() {
        assertNull(SmartResolution.requestedForSession("follow", false, 2400, 1080))
    }

    @Test
    fun explicitResolutionIsPreservedAndInvalidValueFallsBackTo720p() {
        assertEquals(
            "1920x1080",
            SmartResolution.requestedForSession("1920x1080", false, 2400, 1080),
        )
        assertEquals(
            "1280x720",
            SmartResolution.requestedForSession("invalid", false, 2400, 1080),
        )
    }
}
