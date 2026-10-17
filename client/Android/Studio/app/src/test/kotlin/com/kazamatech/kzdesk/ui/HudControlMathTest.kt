package com.kazamatech.kzdesk.ui

import android.view.KeyEvent
import com.freerdp.freerdpcore.utils.KeyboardMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class HudControlMathTest {
    @Test
    fun dragScrollAccumulatesSmallMovesAndKeepsWheelDirection() {
        // deltaY 2px -> 6 unit: masih di bawah satu batch (12), jadi belum
        // dikirim dan sisanya dibawa ke langkah berikutnya.
        val first = scrollWheelStep(deltaY = 2f, remainder = 0f, speed = 1f)
        assertEquals(0, first.units)
        assertEquals(-6f, first.remainder, 0.001f)

        val second = scrollWheelStep(deltaY = 2f, remainder = first.remainder, speed = 1f)
        assertEquals(-12, second.units)
        assertEquals(0f, second.remainder, 0.001f)

        assertEquals(120, scrollWheelStep(-40f, 0f, 1f).units)
        assertEquals(-120, scrollWheelStep(40f, 0f, 1f).units)
    }

    @Test
    fun accumulatorResetsBetweenGestures() {
        val accumulator = ScrollWheelAccumulator()
        assertEquals(0, accumulator.consume(2f, 1f))
        assertEquals(-12, accumulator.consume(2f, 1f))
        accumulator.reset()
        assertEquals(0, accumulator.consume(2f, 1f))
    }

    @Test
    fun dragScrollBoundsExtremeAndNonFiniteInput() {
        val extreme = scrollWheelStep(Float.MAX_VALUE, 0f, 2.5f)
        assertTrue(extreme.units in -1800..0)
        assertTrue(extreme.remainder in -12f..12f)
        assertEquals(0, scrollWheelStep(Float.NaN, Float.NaN, Float.NaN).units)
    }

    @Test
    fun resizingKeepsButtonCenterAndClampsAtViewportEdges() {
        val key = HudKey("test", HudKind.MOUSE_LEFT, "Left", x = 0.5f, y = 0.4f, size = 64f)
        val resized = resizeHudKeyPreservingCenter(key, 120f, 400f, 800f)
        assertEquals(120f, resized.size, 0.001f)
        val oldCenterX = key.x * (400f - key.size) + key.size / 2f
        val newCenterX = resized.x * (400f - resized.size) + resized.size / 2f
        val oldCenterY = key.y * (800f - key.size) + key.size / 2f
        val newCenterY = resized.y * (800f - resized.size) + resized.size / 2f
        assertEquals(oldCenterX, newCenterX, 0.01f)
        assertEquals(oldCenterY, newCenterY, 0.01f)

        val edge = resizeHudKeyPreservingCenter(key.copy(x = 1f, y = 1f), 120f, 200f, 200f)
        assertEquals(1f, edge.x, 0.001f)
        assertEquals(1f, edge.y, 0.001f)
    }

    @Test
    fun everyHudCatalogKeyAndComboTranslatesToWindowsVirtualKey() {
        val mapper = KeyboardMapper()
        assertEquals(0x0D, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_ENTER))
        assertEquals(0x08, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_DEL))
        assertEquals(0x1B, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(0x09, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_TAB))
        assertEquals(0x20, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_SPACE))
        assertEquals(0xA2, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(0xA0, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(0xA4, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(0x15B, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(0x15D, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_MENU))

        // KEYCODE_BACK/HOME/APP_SWITCH TIDAK ada di tabel KeyboardMapper. Tombol
        // HUD yang memakainya akan tampak ada di panel tetapi tidak mengirim
        // apa-apa ke PC — itu sebabnya "Select" di katalog gamepad memakai
        // MOVE_HOME, bukan BACK. Loop katalog di bawah yang menangkap kasusnya;
        // assertion ini mendokumentasikan penyebabnya supaya tidak diulang.
        assertEquals(0, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_BACK))
        assertEquals(0, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_HOME))
        assertNotEquals(0, mapper.translateAndroidKeyCode(KeyEvent.KEYCODE_MOVE_HOME))

        HudKeyCatalog.groups.flatMap { it.third }.forEach { option ->
            when (option.kind) {
                HudKind.KEY -> {
                    val vk = mapper.translateAndroidKeyCode(option.keyCode)
                    assertNotEquals("Unmapped HUD key: ${option.label}", 0, vk)
                }
                HudKind.COMBO -> {
                    assertTrue("Empty combo: ${option.label}", option.combo.isNotEmpty())
                    option.combo.forEach { code ->
                        val vk = mapper.translateAndroidKeyCode(code)
                        assertNotEquals("Unmapped combo member in ${option.label}: $code", 0, vk)
                    }
                }
                else -> Unit
            }
        }
    }

    @Test
    fun hudGestureThresholdRequiresTouchSlopAndDraggableOrScrollControl() {
        assertFalse(shouldStartHudButtonGesture(travelledPx = 24f, touchSlopPx = 18f, mappingMode = false, kind = HudKind.COMBO))
        assertFalse(shouldStartHudButtonGesture(travelledPx = 24f, touchSlopPx = 18f, mappingMode = false, kind = HudKind.KEY))
        assertFalse(shouldStartHudButtonGesture(travelledPx = 12f, touchSlopPx = 18f, mappingMode = true, kind = HudKind.KEY))
        assertTrue(shouldStartHudButtonGesture(travelledPx = 20f, touchSlopPx = 18f, mappingMode = true, kind = HudKind.KEY))
        assertTrue(shouldStartHudButtonGesture(travelledPx = 20f, touchSlopPx = 18f, mappingMode = false, kind = HudKind.SCROLL_SLIDER))
    }

    @Test
    fun catalogModifiersAndHoldClickHaveIntendedDefaultActions() {
        assertTrue(
            HudKeyCatalog.modifiers.all {
                it.defaultAction == HudAction.TOGGLE || it.defaultAction == HudAction.ONE_SHOT
            },
        )
        assertTrue(HudKeyCatalog.modifiers.any { it.defaultAction == HudAction.ONE_SHOT })
        val holdClick = HudKeyCatalog.mouse.first { it.label.contains("tahan") }
        assertEquals(HudAction.HOLD, holdClick.defaultAction)
    }

    @Test
    fun layoutNormalizationPreservesOneShotDeduplicatesIdsAndClampsBounds() {
        val sample = listOf(
            HudKey(
                id = "ctrl_1x",
                kind = HudKind.KEY,
                label = "Ctrl (1x)",
                keyCode = KeyEvent.KEYCODE_CTRL_LEFT,
                action = HudAction.ONE_SHOT,
                x = 1.5f,
                y = -0.2f,
                size = 72f,
            ),
            HudKey(
                id = "ctrl_1x",
                kind = HudKind.COMBO,
                label = "Ctrl+C",
                combo = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C),
                action = HudAction.ONE_SHOT,
                x = 0.50f,
                y = 0.65f,
                size = 64f,
            ),
        )
        val imported = HudKey.normalizeImportedKeys(sample)
        assertEquals(2, imported.size)
        assertEquals(HudAction.ONE_SHOT, imported[0].action)
        assertEquals(1f, imported[0].x, 0.001f)
        assertEquals(0f, imported[0].y, 0.001f)
        assertEquals(HudAction.TAP, imported[1].action)
        assertNotEquals(imported[0].id, imported[1].id)
        assertTrue(HudKey.isModifierKeyCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertFalse(HudKey.isModifierKeyCode(KeyEvent.KEYCODE_C))
    }

    @Test
    fun hudProfilePresetsProduceValidNonEmptyLayoutsAndMappedKeys() {
        val mapper = KeyboardMapper()
        HudProfilePreset.entries.forEach { preset ->
            val keys = HudKey.presetLayout(preset, 46f)
            assertTrue("Preset $preset produced empty layout", keys.isNotEmpty())
            assertTrue("Preset $preset exceeded 36 keys", keys.size <= 36)
            keys.forEach { key ->
                assertTrue("Button size out of compact range: ${key.size}", key.size in HudKey.MIN_SIZE..54f)
                assertTrue(key.x in 0f..1f)
                assertTrue(key.y in 0f..1f)
                if (key.kind == HudKind.KEY) {
                    assertNotEquals(0, mapper.translateAndroidKeyCode(key.keyCode))
                } else if (key.kind == HudKind.COMBO || (key.kind == HudKind.MACRO && key.combo.isNotEmpty())) {
                    key.combo.forEach { code ->
                        assertNotEquals(0, mapper.translateAndroidKeyCode(code))
                    }
                }
            }
        }
    }

    @Test
    fun trackpadPhysicsSensitivityAccelerationEdgeScrollAndInertia() {
        val (noAccelX, noAccelY) = applyTrackpadDelta(10f, 0f, sensitivity = 1.5f, acceleration = false)
        assertEquals(15f, noAccelX, 0.01f)
        assertEquals(0f, noAccelY, 0.01f)

        val (accelX, _) = applyTrackpadDelta(24f, 0f, sensitivity = 1.0f, acceleration = true)
        assertTrue("Accelerated delta ($accelX) should exceed raw 24f", accelX > 24f)

        assertTrue(isInRightEdgeScrollZone(touchX = 390f, viewportWidthPx = 400f, zoneWidthPx = 28f, enabled = true))
        assertFalse(isInRightEdgeScrollZone(touchX = 200f, viewportWidthPx = 400f, zoneWidthPx = 28f, enabled = true))
        assertFalse(isInRightEdgeScrollZone(touchX = 395f, viewportWidthPx = 400f, zoneWidthPx = 28f, enabled = false))

        val step1 = inertialScrollDecayStep(20f, friction = 0.85f)
        assertEquals(17f, step1, 0.01f)
        assertEquals(0f, inertialScrollDecayStep(0.5f, friction = 0.85f), 0.001f)
    }

    @Test
    fun iconFirstHudGlyphResolvesVectorIconsForWindowsMouseAndNavigationKeys() {
        val winKey = HudKey(id = "win", kind = HudKind.KEY, label = "Win", keyCode = KeyEvent.KEYCODE_META_LEFT)
        val leftMouse = HudKey(id = "ml", kind = HudKind.MOUSE_LEFT, label = "Kiri")
        val rightMouse = HudKey(id = "mr", kind = HudKind.MOUSE_RIGHT, label = "Kanan")
        val midMouse = HudKey(id = "mm", kind = HudKind.MOUSE_MIDDLE, label = "Tengah")
        val swapMouse = HudKey(id = "ms", kind = HudKind.MOUSE_SWAP, label = "Kiri⇄Kanan")
        val enterKey = HudKey(id = "ent", kind = HudKind.KEY, label = "Enter", keyCode = KeyEvent.KEYCODE_ENTER)
        val upKey = HudKey(id = "up", kind = HudKind.KEY, label = "↑", keyCode = KeyEvent.KEYCODE_DPAD_UP)

        assertTrue(hudIconFor(winKey) != null)
        assertTrue(hudIconFor(leftMouse) != null)
        assertTrue(hudIconFor(rightMouse) != null)
        assertTrue(hudIconFor(midMouse) != null)
        assertTrue(hudIconFor(swapMouse) != null)
        assertTrue(hudIconFor(enterKey) != null)
        assertTrue(hudIconFor(upKey) != null)
        assertTrue(HudProfilePreset.entries.contains(HudProfilePreset.CUSTOM))
    }

    @Test
    fun stickOffsetIsClampedToTheBaseCircleAndKeepsItsDirection() {
        val clamped = clampStickOffset(300f, 300f, 50f)
        val magnitude = sqrt(clamped[0] * clamped[0] + clamped[1] * clamped[1])
        assertEquals(50f, magnitude, 0.01f)
        assertEquals("diagonal harus tetap diagonal", clamped[0], clamped[1], 1e-4f)

        val inside = clampStickOffset(10f, -5f, 50f)
        assertEquals(10f, inside[0], 0f)
        assertEquals(-5f, inside[1], 0f)
    }

    @Test
    fun stickAxisIsNormalizedAndToleratesBadInput() {
        val full = stickAxis(0f, -500f, 50f)
        assertEquals(0f, full[0], 1e-4f)
        assertEquals(-1f, full[1], 1e-4f)

        val zero = stickAxis(0f, 0f, 50f)
        assertEquals(0f, zero[0], 0f)
        assertEquals(0f, zero[1], 0f)

        // Radius dan input tidak masuk akal tidak boleh menghasilkan NaN/Infinity,
        // karena nilai ini diteruskan ke gerak kursor.
        val bad = stickAxis(Float.NaN, 10f, 0f)
        assertTrue(bad[0].isFinite())
        assertTrue(bad[1].isFinite())
    }

    @Test
    fun stickGestureMayStartOutsideMappingModeButMouseKeysStillMayNot() {
        assertTrue(shouldStartHudButtonGesture(40f, 16f, false, HudKind.PAD_STICK))
        assertTrue(shouldStartHudButtonGesture(40f, 16f, false, HudKind.SCROLL_SLIDER))
        assertFalse(shouldStartHudButtonGesture(40f, 16f, false, HudKind.MOUSE_LEFT))
        // Di bawah touch slop belum boleh memulai gestur.
        assertFalse(shouldStartHudButtonGesture(4f, 16f, false, HudKind.PAD_STICK))
    }

    @Test
    fun `delta pertama lewat tanpa perataan`() {
        val (dx, dy) = smoothPointerDelta(
            prevDx = Float.NaN,
            prevDy = Float.NaN,
            dx = 42f,
            dy = -9f,
            alpha = 0.45f,
        )
        assertEquals(42f, dx, 1e-4f)
        assertEquals(-9f, dy, 1e-4f)
    }

    @Test
    fun `perataan menarik delta ke arah nilai sekarang secara proporsional`() {
        val (dx, dy) = smoothPointerDelta(0f, 0f, 10f, 20f, 0.5f)
        assertEquals(5f, dx, 1e-4f)
        assertEquals(10f, dy, 1e-4f)
    }

    @Test
    fun `alpha dijepit ke rentang aman`() {
        val stiff = smoothPointerDelta(0f, 0f, 100f, 100f, 0f)
        assertTrue(stiff.first >= 0f && stiff.first <= 100f)
        val loose = smoothPointerDelta(0f, 0f, 100f, 100f, 5f)
        assertEquals(100f, loose.first, 1e-3f)
        assertEquals(100f, loose.second, 1e-3f)
    }

    @Test
    fun `delta tak berhingga dinetralkan`() {
        val (dx, dy) = smoothPointerDelta(3f, 3f, Float.POSITIVE_INFINITY, Float.NaN, 0.45f)
        assertEquals(0f, dx, 1e-4f)
        assertEquals(0f, dy, 1e-4f)
    }
}
