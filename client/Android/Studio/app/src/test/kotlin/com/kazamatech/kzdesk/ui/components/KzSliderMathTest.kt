package com.kazamatech.kzdesk.ui.components

import androidx.compose.ui.graphics.Color
import com.kazamatech.kzdesk.ui.HudKey
import com.kazamatech.kzdesk.ui.HudKind
import com.kazamatech.kzdesk.ui.HudPlate
import com.kazamatech.kzdesk.ui.hudPalette
import com.kazamatech.kzdesk.ui.updateHudEditorDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KzSliderMathTest {
    @Test
    fun continuousSliderMapsAndClampsFractions() {
        val range = 10f..110f
        assertEquals(10f, sliderValueAtFraction(0f, range, 0), 0.001f)
        assertEquals(60f, sliderValueAtFraction(0.5f, range, 0), 0.001f)
        assertEquals(110f, sliderValueAtFraction(1f, range, 0), 0.001f)
        assertEquals(10f, sliderValueAtFraction(-1f, range, 0), 0.001f)
        assertEquals(110f, sliderValueAtFraction(2f, range, 0), 0.001f)
    }

    @Test
    fun steppedSliderSnapsAndKeepsBothEndpoints() {
        val range = 0f..100f
        assertEquals(0f, sliderValueAtFraction(0f, range, 3), 0.001f)
        assertEquals(25f, sliderValueAtFraction(0.24f, range, 3), 0.001f)
        assertEquals(75f, sliderValueAtFraction(0.76f, range, 3), 0.001f)
        assertEquals(100f, sliderValueAtFraction(1f, range, 3), 0.001f)
    }

    @Test
    fun invalidRangeAndNanFractionFailClosed() {
        assertEquals(5f, sliderValueAtFraction(0.5f, 5f..5f, 0), 0.001f)
        assertEquals(10f, sliderValueAtFraction(Float.NaN, 10f..20f, 0), 0.001f)
    }

    @Test
    fun editorDraftTracksOnlyTheSelectedButton() {
        val selected = HudKey("selected", HudKind.KEY, "Ctrl")
        val resized = selected.copy(size = 104f)
        val other = HudKey("other", HudKind.KEY, "Alt")
        assertEquals(resized, updateHudEditorDraft(selected, resized))
        assertEquals(selected, updateHudEditorDraft(selected, other))
    }

    @Test
    fun lightPreferenceNoLongerCreatesAWhiteIconPlate() {
        assertNotEquals(Color.White, hudPalette(HudPlate.LIGHT).plate)
        assertEquals(Color.Transparent, hudPalette(HudPlate.NONE).plate)
    }
}
