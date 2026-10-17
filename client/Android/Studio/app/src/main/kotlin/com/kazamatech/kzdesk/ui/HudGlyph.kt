package com.kazamatech.kzdesk.ui

import android.view.KeyEvent as K

/**
 * Jenis ikon tombol HUD. Pemilihan ikon dipisah dari Compose supaya bisa diuji di JVM;
 * pemetaan ke ImageVector ada di [hudIconFor].
 */
enum class HudGlyph {
    CLICK_LEFT, CLICK_RIGHT, CLICK_MIDDLE, MOUSE_SWAP, SCROLL_UP, SCROLL_DOWN, SCROLL_SLIDER,
    JOYSTICK, INPUT_SWITCH, KEYBOARD,
    WINDOWS, ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, ENTER, BACKSPACE, TAB, SPACE, SHIFT, MENU,
    SCREENSHOT, DELETE, HOME_KEY, END_KEY, PAGE_UP, PAGE_DOWN, REFRESH, FULLSCREEN,
    PLAY_PAUSE, VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, NEXT_TRACK, PREV_TRACK,
    LOCK, DESKTOP, EXPLORER, SETTINGS, SWITCH_WINDOWS, COPY, PASTE, CUT, UNDO, REDO, FIND,
    CLOSE, SAVE, SELECT_ALL, PRINT, NEW_TAB, TASK_MANAGER, ZOOM_IN, ZOOM_OUT, TERMINAL, MACRO,
}

object HudGlyphResolver {
    private val CTRL = K.KEYCODE_CTRL_LEFT
    private val ALT = K.KEYCODE_ALT_LEFT
    private val SHIFT = K.KEYCODE_SHIFT_LEFT
    private val WIN = K.KEYCODE_META_LEFT

    fun glyphFor(kind: HudKind, keyCode: Int = 0, combo: List<Int> = emptyList(), label: String = ""): HudGlyph? = when (kind) {
        HudKind.MOUSE_LEFT -> HudGlyph.CLICK_LEFT
        HudKind.MOUSE_RIGHT -> HudGlyph.CLICK_RIGHT
        HudKind.MOUSE_MIDDLE -> HudGlyph.CLICK_MIDDLE
        HudKind.MOUSE_SWAP -> HudGlyph.MOUSE_SWAP
        HudKind.SCROLL_UP -> HudGlyph.SCROLL_UP
        HudKind.SCROLL_DOWN -> HudGlyph.SCROLL_DOWN
        HudKind.SCROLL_SLIDER -> HudGlyph.SCROLL_SLIDER
        HudKind.PAD_STICK -> HudGlyph.JOYSTICK
        HudKind.INPUT_SWITCH -> HudGlyph.INPUT_SWITCH
        HudKind.KEYBOARD -> HudGlyph.KEYBOARD
        HudKind.KEY -> keyGlyph(keyCode, label)
        HudKind.COMBO -> comboGlyph(combo)
        HudKind.MACRO -> if (label.equals("CMD", true) || label.equals("PShell", true) || label.equals("Terminal", true)) {
            HudGlyph.TERMINAL
        } else {
            HudGlyph.MACRO
        }
    }

    private fun keyGlyph(code: Int, label: String): HudGlyph? = when (code) {
        K.KEYCODE_META_LEFT, K.KEYCODE_META_RIGHT -> HudGlyph.WINDOWS
        K.KEYCODE_DPAD_UP -> HudGlyph.ARROW_UP
        K.KEYCODE_DPAD_DOWN -> HudGlyph.ARROW_DOWN
        K.KEYCODE_DPAD_LEFT -> HudGlyph.ARROW_LEFT
        K.KEYCODE_DPAD_RIGHT -> HudGlyph.ARROW_RIGHT
        K.KEYCODE_ENTER, K.KEYCODE_NUMPAD_ENTER -> HudGlyph.ENTER
        K.KEYCODE_DEL -> HudGlyph.BACKSPACE
        K.KEYCODE_FORWARD_DEL -> HudGlyph.DELETE
        K.KEYCODE_TAB -> HudGlyph.TAB
        K.KEYCODE_SPACE -> HudGlyph.SPACE
        K.KEYCODE_SHIFT_LEFT, K.KEYCODE_SHIFT_RIGHT -> HudGlyph.SHIFT
        K.KEYCODE_MENU -> HudGlyph.MENU
        K.KEYCODE_SYSRQ -> HudGlyph.SCREENSHOT
        K.KEYCODE_MOVE_HOME -> HudGlyph.HOME_KEY
        K.KEYCODE_MOVE_END -> HudGlyph.END_KEY
        K.KEYCODE_PAGE_UP -> HudGlyph.PAGE_UP
        K.KEYCODE_PAGE_DOWN -> HudGlyph.PAGE_DOWN
        K.KEYCODE_F5 -> HudGlyph.REFRESH
        K.KEYCODE_F11 -> HudGlyph.FULLSCREEN
        K.KEYCODE_MEDIA_PLAY_PAUSE, K.KEYCODE_MEDIA_PLAY, K.KEYCODE_MEDIA_PAUSE -> HudGlyph.PLAY_PAUSE
        K.KEYCODE_MEDIA_NEXT -> HudGlyph.NEXT_TRACK
        K.KEYCODE_MEDIA_PREVIOUS -> HudGlyph.PREV_TRACK
        K.KEYCODE_VOLUME_UP -> HudGlyph.VOLUME_UP
        K.KEYCODE_VOLUME_DOWN -> HudGlyph.VOLUME_DOWN
        K.KEYCODE_VOLUME_MUTE -> HudGlyph.VOLUME_MUTE
        else -> if (label.startsWith("Win", ignoreCase = true)) HudGlyph.WINDOWS else null
    }

    private fun comboGlyph(c: List<Int>): HudGlyph? = when {
        c == listOf(WIN, K.KEYCODE_L) -> HudGlyph.LOCK
        c == listOf(WIN, K.KEYCODE_D) -> HudGlyph.DESKTOP
        c == listOf(WIN, K.KEYCODE_E) -> HudGlyph.EXPLORER
        c == listOf(WIN, K.KEYCODE_I) -> HudGlyph.SETTINGS
        c == listOf(WIN, K.KEYCODE_R) -> HudGlyph.TERMINAL
        c == listOf(WIN, K.KEYCODE_TAB) || c == listOf(ALT, K.KEYCODE_TAB) -> HudGlyph.SWITCH_WINDOWS
        c == listOf(WIN, SHIFT, K.KEYCODE_S) -> HudGlyph.SCREENSHOT
        c == listOf(CTRL, K.KEYCODE_C) -> HudGlyph.COPY
        c == listOf(CTRL, K.KEYCODE_V) -> HudGlyph.PASTE
        c == listOf(CTRL, K.KEYCODE_X) -> HudGlyph.CUT
        c == listOf(CTRL, K.KEYCODE_Z) -> HudGlyph.UNDO
        c == listOf(CTRL, K.KEYCODE_Y) -> HudGlyph.REDO
        c == listOf(CTRL, K.KEYCODE_F) -> HudGlyph.FIND
        c == listOf(CTRL, K.KEYCODE_S) -> HudGlyph.SAVE
        c == listOf(CTRL, K.KEYCODE_A) -> HudGlyph.SELECT_ALL
        c == listOf(CTRL, K.KEYCODE_P) -> HudGlyph.PRINT
        c == listOf(CTRL, K.KEYCODE_R) -> HudGlyph.REFRESH
        c == listOf(CTRL, K.KEYCODE_T) -> HudGlyph.NEW_TAB
        c == listOf(CTRL, K.KEYCODE_W) -> HudGlyph.CLOSE
        c == listOf(ALT, K.KEYCODE_F4) -> HudGlyph.CLOSE
        c == listOf(CTRL, SHIFT, K.KEYCODE_ESCAPE) -> HudGlyph.TASK_MANAGER
        c == listOf(CTRL, K.KEYCODE_EQUALS) || c == listOf(CTRL, K.KEYCODE_PLUS) ||
            c == listOf(CTRL, K.KEYCODE_NUMPAD_ADD) -> HudGlyph.ZOOM_IN
        c == listOf(CTRL, K.KEYCODE_MINUS) || c == listOf(CTRL, K.KEYCODE_NUMPAD_SUBTRACT) -> HudGlyph.ZOOM_OUT
        else -> null
    }
}
