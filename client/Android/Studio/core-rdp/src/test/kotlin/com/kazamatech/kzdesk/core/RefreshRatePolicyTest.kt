package com.kazamatech.kzdesk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRatePolicyTest {
    @Test
    fun automaticModePrefersHighestRefreshSupported() {
        assertEquals(120f, RefreshRatePolicy.choose(null, listOf(60f, 90f, 120f))!!, 0.01f)
    }

    @Test
    fun requestedRateUsesNextSupportedMode() {
        assertEquals(90f, RefreshRatePolicy.choose(75, listOf(60f, 90f, 120f))!!, 0.01f)
    }

    @Test
    fun requestedRateFallsBackToDeviceMaximum() {
        // Oppo A3s-class 60 Hz panels cannot display 90/120 Hz frames.
        assertEquals(60f, RefreshRatePolicy.choose(120, listOf(60f))!!, 0.01f)
    }

    @Test
    fun invalidAndEmptyModesAreIgnored() {
        assertNull(RefreshRatePolicy.choose(null, listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f)))
    }
}
