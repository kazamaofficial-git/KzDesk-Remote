package com.kazamatech.kzdesk.ui.input

import com.kazamatech.kzdesk.pcstream.KzGamepadPacketV1
import com.kazamatech.kzdesk.pcstream.KzGamepadState
import com.kazamatech.kzdesk.ui.KzMouseButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KzGamepadActionMapperTest {
    private class Recorder : KzGamepadOutput {
        val pointer = mutableListOf<Pair<Float, Float>>()
        val scroll = mutableListOf<Int>()
        val clicks = mutableListOf<Pair<KzMouseButton, Boolean>>()
        val keys = mutableListOf<Pair<KzPadKey, Boolean>>()

        override fun pointerDelta(dx: Float, dy: Float) { pointer += dx to dy }
        override fun scrollUnits(units: Int) { scroll += units }
        override fun mouseClick(button: KzMouseButton, down: Boolean) { clicks += button to down }
        override fun key(key: KzPadKey, down: Boolean) { keys += key to down }
    }

    private fun state(buttons: Int = 0, leftX: Int = 0, leftY: Int = 0, rightY: Int = 0) =
        KzGamepadState(buttons = buttons, leftX = leftX, leftY = leftY, rightY = rightY)

    @Test
    fun leftStickMovesPointerAtEighteenUnitsPerDeflection() {
        val out = Recorder()
        KzGamepadActionMapper.apply(state(leftX = 32_767, leftY = -32_767), null, out)
        assertEquals(1, out.pointer.size)
        assertEquals(18f, out.pointer[0].first, 0.01f)
        assertEquals(-18f, out.pointer[0].second, 0.01f)
        assertTrue(out.scroll.isEmpty() && out.clicks.isEmpty() && out.keys.isEmpty())
    }

    @Test
    fun centeredStickEmitsNothingSoThePointerNeverDrifts() {
        val out = Recorder()
        KzGamepadActionMapper.apply(state(), null, out)
        assertTrue(out.pointer.isEmpty())
    }

    @Test
    fun buttonPressIsEdgeTriggeredAndNeverSpamsWhileHeld() {
        val out = Recorder()
        val idle = state()
        val pressed = state(buttons = KzGamepadPacketV1.A)

        KzGamepadActionMapper.apply(pressed, idle, out)
        KzGamepadActionMapper.apply(pressed, pressed, out)
        KzGamepadActionMapper.apply(pressed, pressed, out)
        KzGamepadActionMapper.apply(idle, pressed, out)

        assertEquals(listOf(KzMouseButton.LEFT to true, KzMouseButton.LEFT to false), out.clicks)
    }

    @Test
    fun faceButtonsMapToTheSameActionsAsThePhysicalPad() {
        val out = Recorder()
        KzGamepadActionMapper.apply(state(buttons = KzGamepadPacketV1.B), null, out)
        KzGamepadActionMapper.apply(state(buttons = KzGamepadPacketV1.X), null, out)
        KzGamepadActionMapper.apply(state(buttons = KzGamepadPacketV1.Y), null, out)

        assertEquals(KzMouseButton.RIGHT to true, out.clicks.single())
        assertEquals(KzPadKey.ENTER to true, out.keys[0])
        assertEquals(KzPadKey.ESCAPE to true, out.keys[1])
    }

    @Test
    fun dpadEmitsArrowKeysAndShouldersEmitOneScrollNotch() {
        val out = Recorder()
        KzGamepadActionMapper.apply(state(buttons = KzGamepadPacketV1.DPAD_UP), null, out)
        assertEquals(KzPadKey.ARROW_UP to true, out.keys.single())

        val scrollOut = Recorder()
        KzGamepadActionMapper.apply(state(buttons = KzGamepadPacketV1.LEFT_SHOULDER), null, scrollOut)
        assertEquals(listOf(120), scrollOut.scroll)

        val rightOut = Recorder()
        KzGamepadActionMapper.apply(state(buttons = KzGamepadPacketV1.RIGHT_SHOULDER), null, rightOut)
        assertEquals(listOf(-120), rightOut.scroll)
    }

    @Test
    fun rightStickScrollsProportionallyAndTriggersOnlyOnRisingEdge() {
        val out = Recorder()
        KzGamepadActionMapper.apply(state(rightY = 32_767), null, out)
        assertEquals(listOf(-48), out.scroll)

        val trigger = Recorder()
        val released = KzGamepadState(rightTrigger = 0)
        val pulled = KzGamepadState(rightTrigger = 200)
        KzGamepadActionMapper.apply(pulled, released, trigger)
        KzGamepadActionMapper.apply(pulled, pulled, trigger)
        assertEquals(listOf(-120), trigger.scroll)
    }

    @Test
    fun wasdModeSendsKeyboardKeysInsteadOfMovingThePointer() {
        val out = Recorder()
        val up = state(leftY = -32_767)
        KzGamepadActionMapper.apply(up, null, out, KzStickMode.WASD)
        assertEquals(listOf(KzPadKey.W to true), out.keys)
        assertTrue("stik tidak boleh menggerakkan kursor di mode WASD", out.pointer.isEmpty())

        // Melepas stik harus mengangkat tombol, bukan membiarkannya tersangkut.
        val released = Recorder()
        KzGamepadActionMapper.apply(state(), up, released, KzStickMode.WASD)
        assertEquals(listOf(KzPadKey.W to false), released.keys)
    }

    @Test
    fun diagonalStickPressesTwoKeysAndReversingSwapsThemInOneFrame() {
        val out = Recorder()
        val upRight = state(leftX = 32_767, leftY = -32_767)
        KzGamepadActionMapper.apply(upRight, null, out, KzStickMode.WASD)
        assertEquals(setOf(KzPadKey.W to true, KzPadKey.D to true), out.keys.toSet())

        val down = Recorder()
        val downLeft = state(leftX = -32_767, leftY = 32_767)
        KzGamepadActionMapper.apply(downLeft, upRight, down, KzStickMode.WASD)
        assertEquals(
            setOf(KzPadKey.W to false, KzPadKey.D to false, KzPadKey.S to true, KzPadKey.A to true),
            down.keys.toSet(),
        )
    }

    @Test
    fun arrowModeUsesArrowKeysAndIgnoresSmallDeflection() {
        val out = Recorder()
        KzGamepadActionMapper.apply(state(leftX = -32_767), null, out, KzStickMode.ARROWS)
        assertEquals(listOf(KzPadKey.ARROW_LEFT to true), out.keys)

        val small = Recorder()
        // 0.3 defleksi di bawah ambang 0.5 -> tidak ada tombol.
        KzGamepadActionMapper.apply(state(leftX = (0.3f * 32_767).toInt()), null, small, KzStickMode.ARROWS)
        assertTrue(small.keys.isEmpty())
        assertTrue(small.pointer.isEmpty())
    }

    @Test
    fun pointerModeIsUnchangedByTheNewStickModes() {
        val out = Recorder()
        KzGamepadActionMapper.apply(state(leftX = 32_767, leftY = -32_767), null, out)
        assertEquals(1, out.pointer.size)
        assertTrue(out.keys.isEmpty())
    }

    @Test
    fun directionsHelperMatchesTheDocumentedThreshold() {
        assertEquals(0, KzGamepadActionMapper.directions(0f, 0f).size)
        assertEquals(
            setOf(KzStickDirection.UP, KzStickDirection.RIGHT),
            KzGamepadActionMapper.directions(0.9f, -0.9f),
        )
        assertEquals(0, KzGamepadActionMapper.directions(0.49f, -0.49f).size)
        assertEquals(KzPadKey.S, KzGamepadActionMapper.keyFor(KzStickDirection.DOWN, KzStickMode.WASD))
        assertEquals(
            KzPadKey.ARROW_DOWN,
            KzGamepadActionMapper.keyFor(KzStickDirection.DOWN, KzStickMode.ARROWS),
        )
    }

    @Test
    fun everyEmittedValueStaysFiniteAndBounded() {
        val out = Recorder()
        for (axis in intArrayOf(-32_768, -1, 0, 1, 32_767)) {
            KzGamepadActionMapper.apply(state(leftX = axis, leftY = axis, rightY = axis), null, out)
        }
        out.pointer.forEach { (dx, dy) ->
            assertTrue(abs(dx) <= 18.01f && abs(dy) <= 18.01f)
        }
        out.scroll.forEach { assertTrue(abs(it) <= 120) }
    }
}
