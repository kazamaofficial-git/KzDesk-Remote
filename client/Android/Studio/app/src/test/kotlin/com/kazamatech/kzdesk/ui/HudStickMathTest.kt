package com.kazamatech.kzdesk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudStickMathTest {
    @Test fun insideDeadzoneIsZero() {
        assertEquals(0f to 0f, HudStickMath.shapeAnalog(0.1f, 0.05f, 0.15f, 1f))
        assertEquals(0f to 0f, HudStickMath.shapeDigital(0.1f, 0.05f, 0.15f))
    }

    @Test fun analogStartsSmoothlyAndReachesFullScale() {
        val (x1, _) = HudStickMath.shapeAnalog(0.16f, 0f, 0.15f, 1f)
        assertTrue("just outside the dead zone must be tiny", x1 in 0f..0.05f)
        val (x2, y2) = HudStickMath.shapeAnalog(1f, 0f, 0.15f, 1f)
        assertEquals(1f, x2, 1e-4f); assertEquals(0f, y2, 1e-4f)
    }

    @Test fun sensitivityScalesAndIsClamped() {
        val (x, _) = HudStickMath.shapeAnalog(1f, 0f, 0.15f, 2f)
        assertEquals(2f, x, 1e-4f)
        val (big, _) = HudStickMath.shapeAnalog(1f, 0f, 0.15f, 99f)
        assertEquals(HudStickMath.MAX_SENS, big, 1e-4f)
    }

    @Test fun directionIsPreserved() {
        val (x, y) = HudStickMath.shapeAnalog(0.6f, 0.6f, 0.15f, 1f)
        assertEquals(x, y, 1e-5f)
        assertEquals(0.6f to -0.4f, HudStickMath.shapeDigital(0.6f, -0.4f, 0.15f))
    }

    @Test fun modeMapsToCoreMode() {
        assertEquals(com.kazamatech.kzdesk.ui.input.KzStickMode.WASD, HudStickMode.WASD.toKzMode())
        assertEquals(com.kazamatech.kzdesk.ui.input.KzStickMode.POINTER, HudStickMode.POINTER.toKzMode())
    }
}
