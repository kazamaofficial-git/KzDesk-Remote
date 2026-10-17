package com.kazamatech.kzdesk.ui

import android.view.KeyEvent as K
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HudGlyphTest {
    private fun combo(vararg c: Int) = HudGlyphResolver.glyphFor(HudKind.COMBO, combo = c.toList())
    private fun key(code: Int, label: String = "") = HudGlyphResolver.glyphFor(HudKind.KEY, keyCode = code, label = label)
    private val ctrl = K.KEYCODE_CTRL_LEFT

    @Test fun commonShortcutsGetTheirOwnIcon() {
        assertEquals(HudGlyph.COPY, combo(ctrl, K.KEYCODE_C))
        assertEquals(HudGlyph.PASTE, combo(ctrl, K.KEYCODE_V))
        assertEquals(HudGlyph.CUT, combo(ctrl, K.KEYCODE_X))
        assertEquals(HudGlyph.SAVE, combo(ctrl, K.KEYCODE_S))
        assertEquals(HudGlyph.SELECT_ALL, combo(ctrl, K.KEYCODE_A))
        assertEquals(HudGlyph.UNDO, combo(ctrl, K.KEYCODE_Z))
        assertEquals(HudGlyph.REDO, combo(ctrl, K.KEYCODE_Y))
        assertEquals(HudGlyph.PRINT, combo(ctrl, K.KEYCODE_P))
        assertEquals(HudGlyph.REFRESH, combo(ctrl, K.KEYCODE_R))
        assertEquals(HudGlyph.TASK_MANAGER, combo(ctrl, K.KEYCODE_SHIFT_LEFT, K.KEYCODE_ESCAPE))
        assertEquals(HudGlyph.EXPLORER, combo(K.KEYCODE_META_LEFT, K.KEYCODE_E))
        assertEquals(HudGlyph.DESKTOP, combo(K.KEYCODE_META_LEFT, K.KEYCODE_D))
    }

    @Test fun navigationAndMediaKeysGetIcons() {
        assertEquals(HudGlyph.PAGE_UP, key(K.KEYCODE_PAGE_UP))
        assertEquals(HudGlyph.PAGE_DOWN, key(K.KEYCODE_PAGE_DOWN))
        assertEquals(HudGlyph.HOME_KEY, key(K.KEYCODE_MOVE_HOME))
        assertEquals(HudGlyph.END_KEY, key(K.KEYCODE_MOVE_END))
        assertEquals(HudGlyph.DELETE, key(K.KEYCODE_FORWARD_DEL))
        assertEquals(HudGlyph.BACKSPACE, key(K.KEYCODE_DEL))
        assertEquals(HudGlyph.REFRESH, key(K.KEYCODE_F5))
        assertEquals(HudGlyph.FULLSCREEN, key(K.KEYCODE_F11))
        assertEquals(HudGlyph.VOLUME_MUTE, key(K.KEYCODE_VOLUME_MUTE))
    }

    @Test fun lettersAndModifiersKeepTheirTextLabel() {
        // Huruf/angka/Esc/Ctrl/Alt/F-key sudah paling jelas sebagai teks.
        assertNull(key(K.KEYCODE_W, "W"))
        assertNull(key(K.KEYCODE_ESCAPE, "Esc"))
        assertNull(key(ctrl, "Ctrl"))
        assertNull(key(K.KEYCODE_F2, "F2"))
    }

    @Test fun joystickIsNotTheGamepadGlyph() {
        assertEquals(HudGlyph.JOYSTICK, HudGlyphResolver.glyphFor(HudKind.PAD_STICK))
    }

    @Test fun macrosAlwaysHaveAnIcon() {
        assertEquals(HudGlyph.TERMINAL, HudGlyphResolver.glyphFor(HudKind.MACRO, label = "CMD"))
        assertEquals(HudGlyph.MACRO, HudGlyphResolver.glyphFor(HudKind.MACRO, label = "Email"))
    }

    @Test fun everyKindResolvesWithoutCrashing() {
        HudKind.entries.forEach { kind ->
            HudGlyphResolver.glyphFor(kind, K.KEYCODE_A, listOf(ctrl, K.KEYCODE_A), "x")
        }
        assertNotNull(HudGlyphResolver.glyphFor(HudKind.MOUSE_LEFT))
    }
}
