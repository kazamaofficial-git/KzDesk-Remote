package com.kazamatech.kzdesk.pcstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KzVirtualPadTest {
    @Test
    fun radialDeadzoneZeroesDriftAndRescalesToFullDeflection() {
        val inside = KzPadAxis.deadzone(0.05f, 0.05f)
        assertEquals(0f, inside[0], 0f)
        assertEquals(0f, inside[1], 0f)

        val full = KzPadAxis.deadzone(1f, 0f)
        assertEquals(1f, full[0], 1e-4f)
        assertEquals(0f, full[1], 1e-4f)

        // Diagonal deflection must keep its direction, not snap to an axis.
        val diagonal = KzPadAxis.deadzone(0.7f, 0.7f)
        assertTrue(diagonal[0] > 0f && diagonal[1] > 0f)
        assertEquals(diagonal[0], diagonal[1], 1e-6f)
    }

    @Test
    fun axisAndTriggerScalingStayInsideWireRange() {
        assertEquals(32_767, KzPadAxis.toInt16(1f))
        assertEquals(-32_767, KzPadAxis.toInt16(-1f))
        assertEquals(0, KzPadAxis.toInt16(0f))
        // Out-of-range input (a pad reporting >1.0) must clamp, never overflow.
        assertEquals(32_767, KzPadAxis.toInt16(4.5f))
        assertEquals(-32_767, KzPadAxis.toInt16(-4.5f))

        assertEquals(255, KzPadAxis.triggerToByte(1f))
        assertEquals(0, KzPadAxis.triggerToByte(0f))
        assertEquals(0, KzPadAxis.triggerToByte(-1f))
        assertEquals(255, KzPadAxis.triggerToByte(9f))
        assertEquals(128, KzPadAxis.triggerToByte(0.5f))
    }

    @Test
    fun dpadFromAxisReportsOnlyDeflectedDirections() {
        assertEquals(0, KzPadAxis.dpadFromAxis(0f, 0f))
        assertEquals(KzGamepadPacketV1.DPAD_UP, KzPadAxis.dpadFromAxis(0f, -1f))
        assertEquals(KzGamepadPacketV1.DPAD_DOWN, KzPadAxis.dpadFromAxis(0f, 1f))
        assertEquals(KzGamepadPacketV1.DPAD_LEFT, KzPadAxis.dpadFromAxis(-1f, 0f))
        assertEquals(
            KzGamepadPacketV1.DPAD_UP or KzGamepadPacketV1.DPAD_RIGHT,
            KzPadAxis.dpadFromAxis(0.9f, -0.9f),
        )
        // Below threshold stays centered so the pointer does not twitch.
        assertEquals(0, KzPadAxis.dpadFromAxis(0.3f, -0.3f))
    }

    @Test
    fun trackerEncodesButtonsSticksAndTriggersIntoOneFrame() {
        val tracker = KzVirtualPadTracker()
        assertTrue(tracker.isIdle())

        tracker.setLeftStick(1f, 0f)
        tracker.setRightStick(0f, -1f)
        tracker.setTrigger(left = 1f, right = 0.5f)
        tracker.setButton(KzPadButton.A, true)
        tracker.setButton(KzPadButton.RIGHT_SHOULDER, true)
        assertFalse(tracker.isIdle())

        val frame = tracker.nextFrame(timestampMs = 1_234L)
        assertEquals(1L, frame.sequence)
        assertEquals(1_234L, frame.timestampMs32)
        assertEquals(32_767, frame.state.leftX)
        assertEquals(-32_767, frame.state.rightY)
        assertEquals(255, frame.state.leftTrigger)
        assertEquals(128, frame.state.rightTrigger)
        assertEquals(
            KzGamepadPacketV1.A or KzGamepadPacketV1.RIGHT_SHOULDER,
            frame.state.buttons,
        )

        // The frame must survive a wire round-trip unchanged.
        val decoded = KzGamepadPacketV1.decode(KzGamepadPacketV1.encode(frame.sequence, frame.timestampMs32, frame.state))
        assertEquals(frame.state, decoded.state)
        assertEquals(frame.sequence, decoded.sequence)
    }

    @Test
    fun releasingEveryControlReturnsToIdleAndStopsMarkingDirty() {
        val tracker = KzVirtualPadTracker()
        tracker.setButton(KzPadButton.A, true)
        assertTrue(tracker.consumeDirty())
        assertFalse(tracker.consumeDirty())

        tracker.setButton(KzPadButton.A, false)
        assertTrue(tracker.consumeDirty())
        assertTrue(tracker.isIdle())
        assertEquals(0, tracker.state().buttons)
    }

    @Test
    fun sequenceWrapsInsideUint32AndStaysOrderable() {
        val tracker = KzVirtualPadTracker()
        var last = 0L
        repeat(3) {
            val frame = tracker.nextFrame(1L)
            assertTrue(KzGamepadPacketV1.isNewerSequence(frame.sequence, last))
            last = frame.sequence
        }
        assertEquals(3L, last)

        // Wraparound must still read as "newer", not older.
        val nearMax = 0xFFFF_FFFEL
        assertTrue(KzGamepadPacketV1.isNewerSequence(1L, nearMax))
        assertFalse(KzGamepadPacketV1.isNewerSequence(nearMax, 1L))
        // An unchanged sequence is not newer (guards against replaying a frame).
        assertFalse(KzGamepadPacketV1.isNewerSequence(5L, 5L))
        assertTrue(abs(5L - 5L) == 0L)
    }
}
