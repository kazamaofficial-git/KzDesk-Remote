package com.kazamatech.kzdesk.ui

import android.view.KeyEvent
import com.kazamatech.kzdesk.core.KzAudioMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model tombol HUD sesi.
 *
 * Aturan produk: SATU tombol = SATU aksi, berbentuk lingkaran dengan aksen
 * kontras, dan tiap tombol bisa digeser, diubah ukurannya, serta diatur aksinya sendiri
 * (sekali klik / tahan / toggle). Tidak ada tombol gabungan.
 *
 * Semua tombol disimpan per perangkat (layar sesi bisa beda-beda layout).
 */
enum class HudAction(val title: String, val titleEn: String, val detail: String, val detailEn: String) {
    TAP("Sekali klik", "Single tap", "Tekan lalu lepas", "Press and release"),
    ONE_SHOT("Sekali pakai", "One-shot", "Aktif untuk 1 tombol/klik berikutnya lalu lepas otomatis", "Arms for the next key/click then auto-releases"),
    HOLD("Tahan", "Hold", "Aktif selama ditahan (drag, pilih banyak)", "Active while held (drag, multi-select)"),
    TOGGLE("Toggle", "Toggle", "Sekali klik nyala, klik lagi mati", "Tap to turn on, tap again to turn off"),
}

enum class HudKind(val title: String, val titleEn: String) {
    MOUSE_LEFT("Klik kiri", "Left click"),
    MOUSE_RIGHT("Klik kanan", "Right click"),
    MOUSE_MIDDLE("Klik tengah", "Middle click"),
    MOUSE_SWAP("Tukar klik kiri/kanan", "Swap left/right click"),
    SCROLL_UP("Scroll naik", "Scroll up"),
    SCROLL_DOWN("Scroll turun", "Scroll down"),
    SCROLL_SLIDER("Geser scroll", "Swipe to scroll"),
    PAD_STICK("Joystick analog", "Analog stick"),
    INPUT_SWITCH("Ganti mode input", "Switch input mode"),
    KEYBOARD("Buka keyboard", "Show keyboard"),
    KEY("Tombol keyboard", "Keyboard key"),
    COMBO("Kombinasi", "Combo"),
    MACRO("Makro otomatis", "Auto macro"),
}

enum class HudProfilePreset(
    val title: String,
    val titleEn: String,
    val detail: String,
    val detailEn: String,
) {
    STANDARD(
        "Standar",
        "Standard",
        "Klik kiri/kanan/tengah, scroll, tukar klik, dan ganti mode",
        "Left/right/middle click, scroll, swap click, and input mode switch",
    ),
    CODING(
        "Coding / Terminal",
        "Coding / Terminal",
        "Esc, Tab, Ctrl 1x, Copy/Paste/Save, Alt+Tab, Enter, dan klik",
        "Esc, Tab, Ctrl 1x, Copy/Paste/Save, Alt+Tab, Enter, and clicks",
    ),
    GAMING(
        "Gaming / WASD",
        "Gaming / WASD",
        "D-pad WASD di kiri, Space/Shift/E/Esc & klik di kanan",
        "WASD cluster on left, Space/Shift/E/Esc & mouse clicks on right",
    ),
    OFFICE(
        "Office / Kerja",
        "Office / Work",
        "Copy, Paste, Undo, Find, Alt+Tab, Win+D, Geser Scroll, dan klik",
        "Copy, Paste, Undo, Find, Alt+Tab, Win+D, Swipe Scroll, and clicks",
    ),
    MEDIA(
        "Desain / Video",
        "Design / Video",
        "Play/Pause (Space), Step ◀/▶, Undo/Redo, Del, Save, dan klik",
        "Play/Pause (Space), Step ◀/▶, Undo/Redo, Del, Save, and clicks",
    ),
    CUSTOM(
        "Kustom (Buat Sendiri)",
        "Custom (Build Your Own)",
        "Susun dan simpan tata letak tombol HUD sesuai kebutuhanmu sendiri",
        "Create and save your own custom HUD button layout",
    ),
    FULLPAD(
        "Gamepad Lengkap",
        "Full Gamepad",
        "Dua stik analog, D-Pad, A/B/X/Y, L1/R1/L2/R2, Start & Select — siap main",
        "Two analog sticks, D-Pad, A/B/X/Y, L1/R1/L2/R2, Start & Select — ready to play",
    ),
}

data class HudKey(
    val id: String,
    val kind: HudKind,
    val label: String,
    val keyCode: Int = 0,
    val shift: Boolean = false,
    val combo: List<Int> = emptyList(),
    val action: HudAction = HudAction.TAP,
    /** Posisi ternormalisasi 0..1 dari kiri-atas area sesi. */
    val x: Float = 0.78f,
    val y: Float = 0.42f,
    /** Diameter tombol HUD (dp). */
    val size: Float = 46f,
    /** Teks perintah/snippet yang diketik otomatis untuk [HudKind.MACRO]. */
    val macroText: String = "",
    /** Kirim tombol Enter otomatis sesudah [macroText] selesai diketik. */
    val macroSendEnter: Boolean = true,
    /**
     * Tombol milik preset Gamepad: dirender dengan rupa khusus gamepad
     * (permintaan pemilik 2026-10-10 — kontrol gamepad tidak boleh memakai
     * rupa tombol overlay biasa).
     */
    val pad: Boolean = false,
    /** Mode joystick ini; null = ikut pengaturan global. Hanya dipakai [HudKind.PAD_STICK]. */
    val stickMode: HudStickMode? = null,
    /** Sensitivitas gerak kursor/kamera (0.3..3). */
    val stickSens: Float = HudStickMath.DEFAULT_SENS,
    /** Deadzone radial (0.05..0.5). */
    val stickDead: Float = HudStickMath.DEFAULT_DEAD,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("label", label)
        put("code", keyCode)
        put("shift", shift)
        put("combo", JSONArray(combo))
        put("action", action.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("size", size.toDouble())
        if (macroText.isNotEmpty()) {
            put("macroText", macroText)
            put("macroEnter", macroSendEnter)
        }
        if (pad) put("pad", true)
        if (kind == HudKind.PAD_STICK) {
            stickMode?.let { put("stickMode", it.name) }
            if (stickSens != HudStickMath.DEFAULT_SENS) put("stickSens", stickSens.toDouble())
            if (stickDead != HudStickMath.DEFAULT_DEAD) put("stickDead", stickDead.toDouble())
        }
    }

    companion object {
        const val MIN_SIZE = 34f
        const val MAX_SIZE = 96f
        const val DEFAULT_SIZE = 46f

        fun fromJson(o: JSONObject): HudKey? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val kind = runCatching { HudKind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
            val parsedAction = runCatching { HudAction.valueOf(o.optString("action")) }
                .getOrDefault(HudAction.TAP)
            val action = parsedAction.takeIf { it in allowedActions(kind) } ?: HudAction.TAP
            val comboJson = o.optJSONArray("combo") ?: JSONArray()
            val combo = (0 until comboJson.length()).map { comboJson.optInt(it) }
            return HudKey(
                id = id,
                kind = kind,
                label = o.optString("label", kind.title),
                keyCode = o.optInt("code"),
                shift = o.optBoolean("shift"),
                combo = combo,
                action = action,
                x = o.optDouble("x", 0.78).toFloat().coerceIn(0f, 1f),
                y = o.optDouble("y", 0.42).toFloat().coerceIn(0f, 1f),
                size = o.optDouble("size", 46.0).toFloat().coerceIn(MIN_SIZE, MAX_SIZE),
                macroText = o.optString("macroText", ""),
                macroSendEnter = o.optBoolean("macroEnter", true),
                pad = o.optBoolean("pad", false),
                stickMode = runCatching { HudStickMode.valueOf(o.optString("stickMode")) }.getOrNull(),
                stickSens = o.optDouble("stickSens", HudStickMath.DEFAULT_SENS.toDouble()).toFloat()
                    .coerceIn(HudStickMath.MIN_SENS, HudStickMath.MAX_SENS),
                stickDead = o.optDouble("stickDead", HudStickMath.DEFAULT_DEAD.toDouble()).toFloat()
                    .coerceIn(HudStickMath.MIN_DEAD, HudStickMath.MAX_DEAD),
            )
        }

        fun encode(list: List<HudKey>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun decode(raw: String?): List<HudKey>? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { fromJson(it) }
                }
            }.getOrNull()
        }

        /** Set awal ringkas; jarak baris aman juga di layar landscape pendek. */
        fun defaults(size: Float = DEFAULT_SIZE): List<HudKey> {
            val diameter = size.coerceIn(MIN_SIZE, 54f)
            return listOf(
                HudKey("kiri", HudKind.MOUSE_LEFT, "Kiri", x = 0.05f, y = 0.84f),
                HudKey("kanan", HudKind.MOUSE_RIGHT, "Kanan", x = 0.16f, y = 0.84f),
                HudKey("tengah", HudKind.MOUSE_MIDDLE, "Tengah", x = 0.27f, y = 0.84f),
                HudKey("naik", HudKind.SCROLL_UP, "Naik", x = 0.05f, y = 0.70f),
                HudKey("turun", HudKind.SCROLL_DOWN, "Turun", x = 0.16f, y = 0.70f),
                HudKey("switch", HudKind.INPUT_SWITCH, "Mode", x = 0.27f, y = 0.70f),
            ).map { it.copy(size = diameter) }
        }

        /** Hasilkan set tombol siap pakai berdasarkan [HudProfilePreset] dengan ukuran ringkas. */
        fun presetLayout(preset: HudProfilePreset, size: Float = DEFAULT_SIZE): List<HudKey> {
            val d = size.coerceIn(MIN_SIZE, 54f)
            val s = (d * 0.88f).coerceIn(MIN_SIZE, 48f)
            val ctrl = KeyEvent.KEYCODE_CTRL_LEFT
            val shift = KeyEvent.KEYCODE_SHIFT_LEFT
            val alt = KeyEvent.KEYCODE_ALT_LEFT
            val win = KeyEvent.KEYCODE_META_LEFT
            val list = when (preset) {
                HudProfilePreset.STANDARD -> return defaults(d)
                HudProfilePreset.CUSTOM -> listOf(
                    HudKey("kiri", HudKind.MOUSE_LEFT, "Kiri", x = 0.05f, y = 0.84f, size = d),
                    HudKey("kanan", HudKind.MOUSE_RIGHT, "Kanan", x = 0.16f, y = 0.84f, size = d),
                    HudKey("win", HudKind.KEY, "Win", keyCode = win, x = 0.27f, y = 0.84f, size = s),
                    HudKey("mswap", HudKind.MOUSE_SWAP, "L⇄R", x = 0.05f, y = 0.70f, size = s),
                )
                HudProfilePreset.CODING -> listOf(
                    HudKey("c_esc", HudKind.KEY, "Esc", keyCode = KeyEvent.KEYCODE_ESCAPE, x = 0.04f, y = 0.14f, size = s),
                    HudKey("c_tab", HudKind.KEY, "Tab", keyCode = KeyEvent.KEYCODE_TAB, x = 0.04f, y = 0.30f, size = s),
                    HudKey("c_ctrl", HudKind.KEY, "Ctrl 1x", keyCode = ctrl, action = HudAction.ONE_SHOT, x = 0.04f, y = 0.46f, size = s),
                    HudKey("c_win", HudKind.KEY, "Win", keyCode = win, x = 0.04f, y = 0.62f, size = s),
                    HudKey("c_copy", HudKind.COMBO, "Ctrl+C", combo = listOf(ctrl, KeyEvent.KEYCODE_C), x = 0.04f, y = 0.82f, size = s),
                    HudKey("c_paste", HudKind.COMBO, "Ctrl+V", combo = listOf(ctrl, KeyEvent.KEYCODE_V), x = 0.15f, y = 0.82f, size = s),
                    HudKey("c_save", HudKind.COMBO, "Ctrl+S", combo = listOf(ctrl, KeyEvent.KEYCODE_S), x = 0.26f, y = 0.82f, size = s),
                    HudKey("c_undo", HudKind.COMBO, "Ctrl+Z", combo = listOf(ctrl, KeyEvent.KEYCODE_Z), x = 0.37f, y = 0.82f, size = s),
                    HudKey("c_alttab", HudKind.COMBO, "Alt+Tab", combo = listOf(alt, KeyEvent.KEYCODE_TAB), x = 0.84f, y = 0.16f, size = s),
                    HudKey("c_enter", HudKind.KEY, "Enter", keyCode = KeyEvent.KEYCODE_ENTER, x = 0.84f, y = 0.34f, size = s),
                    HudKey("kiri", HudKind.MOUSE_LEFT, "Kiri", x = 0.72f, y = 0.54f, size = d),
                    HudKey("kanan", HudKind.MOUSE_RIGHT, "Kanan", x = 0.84f, y = 0.54f, size = d),
                )
                HudProfilePreset.GAMING -> listOf(
                    HudKey("g_w", HudKind.KEY, "W", keyCode = KeyEvent.KEYCODE_W, action = HudAction.HOLD, x = 0.13f, y = 0.52f, size = s),
                    HudKey("g_a", HudKind.KEY, "A", keyCode = KeyEvent.KEYCODE_A, action = HudAction.HOLD, x = 0.04f, y = 0.68f, size = s),
                    HudKey("g_s", HudKind.KEY, "S", keyCode = KeyEvent.KEYCODE_S, action = HudAction.HOLD, x = 0.13f, y = 0.68f, size = s),
                    HudKey("g_d", HudKind.KEY, "D", keyCode = KeyEvent.KEYCODE_D, action = HudAction.HOLD, x = 0.22f, y = 0.68f, size = s),
                    HudKey("g_shift", HudKind.KEY, "Shift", keyCode = shift, action = HudAction.HOLD, x = 0.04f, y = 0.84f, size = s),
                    HudKey("g_space", HudKind.KEY, "Space", keyCode = KeyEvent.KEYCODE_SPACE, action = HudAction.TAP, x = 0.22f, y = 0.84f, size = s),
                    HudKey("g_esc", HudKind.KEY, "Esc", keyCode = KeyEvent.KEYCODE_ESCAPE, x = 0.04f, y = 0.16f, size = s),
                    HudKey("g_e", HudKind.KEY, "E", keyCode = KeyEvent.KEYCODE_E, x = 0.84f, y = 0.28f, size = s),
                    HudKey("kiri", HudKind.MOUSE_LEFT, "Kiri", action = HudAction.HOLD, x = 0.72f, y = 0.52f, size = d),
                    HudKey("kanan", HudKind.MOUSE_RIGHT, "Kanan", action = HudAction.TAP, x = 0.84f, y = 0.52f, size = d),
                )
                HudProfilePreset.OFFICE -> listOf(
                    HudKey("o_copy", HudKind.COMBO, "Ctrl+C", combo = listOf(ctrl, KeyEvent.KEYCODE_C), x = 0.04f, y = 0.16f, size = s),
                    HudKey("o_paste", HudKind.COMBO, "Ctrl+V", combo = listOf(ctrl, KeyEvent.KEYCODE_V), x = 0.04f, y = 0.34f, size = s),
                    HudKey("o_undo", HudKind.COMBO, "Ctrl+Z", combo = listOf(ctrl, KeyEvent.KEYCODE_Z), x = 0.04f, y = 0.52f, size = s),
                    HudKey("o_find", HudKind.COMBO, "Ctrl+F", combo = listOf(ctrl, KeyEvent.KEYCODE_F), x = 0.04f, y = 0.70f, size = s),
                    HudKey("o_win", HudKind.KEY, "Win", keyCode = win, x = 0.04f, y = 0.86f, size = s),
                    HudKey("o_alttab", HudKind.COMBO, "Alt+Tab", combo = listOf(alt, KeyEvent.KEYCODE_TAB), x = 0.84f, y = 0.16f, size = s),
                    HudKey("o_wind", HudKind.COMBO, "Win+D", combo = listOf(win, KeyEvent.KEYCODE_D), x = 0.72f, y = 0.16f, size = s),
                    HudKey("o_scroll", HudKind.SCROLL_SLIDER, "Geser", x = 0.84f, y = 0.34f, size = s),
                    HudKey("kiri", HudKind.MOUSE_LEFT, "Kiri", x = 0.72f, y = 0.54f, size = d),
                    HudKey("kanan", HudKind.MOUSE_RIGHT, "Kanan", x = 0.84f, y = 0.54f, size = d),
                )
                HudProfilePreset.MEDIA -> listOf(
                    HudKey("m_space", HudKind.KEY, "Space", keyCode = KeyEvent.KEYCODE_SPACE, x = 0.04f, y = 0.16f, size = s),
                    HudKey("m_left", HudKind.KEY, "\u25c0", keyCode = KeyEvent.KEYCODE_DPAD_LEFT, x = 0.04f, y = 0.34f, size = s),
                    HudKey("m_right", HudKind.KEY, "\u25b6", keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, x = 0.15f, y = 0.34f, size = s),
                    HudKey("m_undo", HudKind.COMBO, "Ctrl+Z", combo = listOf(ctrl, KeyEvent.KEYCODE_Z), x = 0.04f, y = 0.52f, size = s),
                    HudKey("m_redo", HudKind.COMBO, "Ctrl+Y", combo = listOf(ctrl, KeyEvent.KEYCODE_Y), x = 0.15f, y = 0.52f, size = s),
                    HudKey("m_del", HudKind.KEY, "Del", keyCode = KeyEvent.KEYCODE_FORWARD_DEL, x = 0.04f, y = 0.70f, size = s),
                    HudKey("m_save", HudKind.COMBO, "Ctrl+S", combo = listOf(ctrl, KeyEvent.KEYCODE_S), x = 0.15f, y = 0.70f, size = s),
                    HudKey("m_scroll", HudKind.SCROLL_SLIDER, "Geser", x = 0.84f, y = 0.24f, size = s),
                    HudKey("kiri", HudKind.MOUSE_LEFT, "Kiri", action = HudAction.HOLD, x = 0.72f, y = 0.52f, size = d),
                    HudKey("kanan", HudKind.MOUSE_RIGHT, "Kanan", x = 0.84f, y = 0.52f, size = d),
                )
                HudProfilePreset.FULLPAD -> listOf(
                    // Stik & D-Pad kiri, ABXY & stik kanan, shoulder di atas —
                    // susunan gamepad sungguhan tanpa perlu menyusun manual.
                    HudKey("f_lstick", HudKind.PAD_STICK, "L", x = 0.06f, y = 0.68f, size = d, pad = true),
                    HudKey("f_dpad_up", HudKind.KEY, "\u25b2", keyCode = KeyEvent.KEYCODE_DPAD_UP, x = 0.08f, y = 0.26f, size = s, pad = true),
                    HudKey("f_dpad_down", HudKind.KEY, "\u25bc", keyCode = KeyEvent.KEYCODE_DPAD_DOWN, x = 0.08f, y = 0.42f, size = s, pad = true),
                    HudKey("f_dpad_left", HudKind.KEY, "\u25c0", keyCode = KeyEvent.KEYCODE_DPAD_LEFT, x = 0.02f, y = 0.34f, size = s, pad = true),
                    HudKey("f_dpad_right", HudKind.KEY, "\u25b6", keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, x = 0.14f, y = 0.34f, size = s, pad = true),
                    HudKey("f_l1", HudKind.SCROLL_UP, "L1", x = 0.02f, y = 0.12f, size = s, pad = true),
                    HudKey("f_l2", HudKind.KEY, "L2", keyCode = KeyEvent.KEYCODE_PAGE_UP, x = 0.12f, y = 0.12f, size = s, pad = true),
                    HudKey("f_select", HudKind.KEY, "Select", keyCode = KeyEvent.KEYCODE_MOVE_HOME, x = 0.40f, y = 0.12f, size = s, pad = true),
                    HudKey("f_start", HudKind.KEY, "Start", keyCode = KeyEvent.KEYCODE_MENU, x = 0.52f, y = 0.12f, size = s, pad = true),
                    HudKey("f_r2", HudKind.KEY, "R2", keyCode = KeyEvent.KEYCODE_PAGE_DOWN, x = 0.80f, y = 0.12f, size = s, pad = true),
                    HudKey("f_r1", HudKind.SCROLL_DOWN, "R1", x = 0.90f, y = 0.12f, size = s, pad = true),
                    HudKey("f_x", HudKind.KEY, "X", keyCode = KeyEvent.KEYCODE_ENTER, x = 0.86f, y = 0.32f, size = s, pad = true),
                    HudKey("f_y", HudKind.KEY, "Y", keyCode = KeyEvent.KEYCODE_ESCAPE, x = 0.80f, y = 0.40f, size = s, pad = true),
                    HudKey("f_a", HudKind.MOUSE_LEFT, "A", action = HudAction.TAP, x = 0.92f, y = 0.40f, size = s, pad = true),
                    HudKey("f_b", HudKind.MOUSE_RIGHT, "B", x = 0.86f, y = 0.48f, size = s, pad = true),
                    HudKey("f_rstick", HudKind.PAD_STICK, "R", x = 0.88f, y = 0.68f, size = d, pad = true),
                )
            }
            return list
        }

        /** Action yang benar-benar punya makna untuk jenis kontrol terkait. */
        fun allowedActions(kind: HudKind): List<HudAction> = when (kind) {
            HudKind.KEY ->
                listOf(HudAction.TAP, HudAction.ONE_SHOT, HudAction.HOLD, HudAction.TOGGLE)
            HudKind.MOUSE_LEFT, HudKind.MOUSE_RIGHT, HudKind.MOUSE_MIDDLE ->
                listOf(HudAction.TAP, HudAction.HOLD, HudAction.TOGGLE)
            else -> listOf(HudAction.TAP)
        }

        fun isModifierKeyCode(keyCode: Int): Boolean = keyCode in setOf(
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
        )

        fun exportLayoutJson(list: List<HudKey>): String = JSONObject().apply {
            put("app", "KzDesk Remote")
            put("version", 1)
            val arr = JSONArray()
            list.take(36).forEach { arr.put(it.toJson()) }
            put("keys", arr)
        }.toString()

        fun normalizeImportedKeys(
            items: List<HudKey>,
            defaultSize: Float = DEFAULT_SIZE,
        ): List<HudKey> {
            val seenIds = mutableSetOf<String>()
            return items
                .filterNot { it.kind == HudKind.KEYBOARD || it.id.startsWith("aux_") || it.id.isBlank() }
                .take(36)
                .mapIndexed { i, item ->
                    val uniqueId = if (seenIds.add(item.id)) item.id else "${item.id}_$i"
                    seenIds.add(uniqueId)
                    val validAction = item.action.takeIf { it in allowedActions(item.kind) } ?: HudAction.TAP
                    item.copy(
                        id = uniqueId,
                        action = validAction,
                        x = item.x.coerceIn(0f, 1f),
                        y = item.y.coerceIn(0f, 1f),
                        size = item.size.coerceIn(MIN_SIZE, MAX_SIZE).takeIf { it > 0f } ?: defaultSize.coerceIn(MIN_SIZE, MAX_SIZE),
                    )
                }
        }

        fun importLayoutJson(raw: String?, defaultSize: Float = DEFAULT_SIZE): List<HudKey>? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val arr = runCatching {
                if (trimmed.startsWith("{")) {
                    JSONObject(trimmed).optJSONArray("keys")
                } else {
                    JSONArray(trimmed)
                }
            }.getOrNull() ?: return null
            val parsed = (0 until minOf(arr.length(), 36)).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
            return normalizeImportedKeys(parsed, defaultSize).takeIf { it.isNotEmpty() }
        }

        /**
         * Layout lama (ronde 4 dan sebelumnya) memakai konsep "baris atas
         * keyboard" dengan id `aux_*`. Baris itu sudah dihapus dari produk,
         * jadi entri `aux_*` dibuang. Ukuran tombol lama yang terlalu besar
         * (>=60dp) dikecilkan ke ukuran ringkas baru.
         */
        fun migrate(list: List<HudKey>, defaultSize: Float = DEFAULT_SIZE): List<HudKey> {
            if (list.isEmpty()) return defaults(defaultSize)
            val legacyStock = list.size == 7 && list.all { key ->
                val stock = when (key.id) {
                    "kiri" -> key.kind == HudKind.MOUSE_LEFT && key.label == "Kiri" && key.x == 0.84f && key.y == 0.40f
                    "kanan" -> key.kind == HudKind.MOUSE_RIGHT && key.label == "Kanan" && key.x == 0.84f && key.y == 0.52f
                    "tengah" -> key.kind == HudKind.MOUSE_MIDDLE && key.label == "Tengah" && key.x == 0.84f && key.y == 0.64f
                    "naik" -> key.kind == HudKind.SCROLL_UP && key.label == "Naik" && key.x == 0.72f && key.y == 0.40f
                    "turun" -> key.kind == HudKind.SCROLL_DOWN && key.label == "Turun" && key.x == 0.72f && key.y == 0.52f
                    "switch" -> key.kind == HudKind.INPUT_SWITCH && key.label == "Mode" && key.x == 0.72f && key.y == 0.64f
                    "keyboard" -> key.kind == HudKind.KEYBOARD && key.label == "Keyboard" && key.x == 0.84f && key.y == 0.76f
                    else -> false
                }
                stock && key.action == HudAction.TAP && key.size == 56f && key.keyCode == 0 &&
                    !key.shift && key.combo.isEmpty()
            }
            if (legacyStock) return defaults(defaultSize)
            val kept = list.filterNot {
                it.id.startsWith("aux_") || it.id == "keyboard" || it.kind == HudKind.KEYBOARD
            }
            if (kept.isEmpty()) return defaults(defaultSize)
            val compactDefault = defaultSize.coerceIn(MIN_SIZE, 50f)
            return kept.map { key ->
                val normalizedAction = if (key.action in allowedActions(key.kind)) key.action else HudAction.TAP
                val normalizedSize = if (key.size >= 60f) compactDefault else key.size.coerceIn(MIN_SIZE, MAX_SIZE)
                key.copy(action = normalizedAction, size = normalizedSize)
            }
        }
    }
}

/**
 * Tombol mouse yang bisa dikirim HUD. Dulu dideklarasikan di SessionControls
 * lama; sekarang di model HUD karena HUD yang memakainya.
 */
enum class KzMouseButton { LEFT, RIGHT, MIDDLE }


/** Satu pilihan di daftar "tambah tombol". */
data class HudKeyOption(
    val group: String,
    val label: String,
    val kind: HudKind,
    val keyCode: Int = 0,
    val shift: Boolean = false,
    val combo: List<Int> = emptyList(),
    /** Label bahasa Inggris; label utama bahasa Indonesia. */
    val labelEn: String = label,
    val defaultAction: HudAction = HudAction.TAP,
    val macroText: String = "",
    val macroSendEnter: Boolean = true,
)

/**
 * Katalog tombol yang bisa ditambahkan. Sengaja pilih yang benar-benar
 * dipetakan KeyboardMapper inti; tombol eksotis yang tidak ada padanannya
 * dibuang supaya tidak jadi tombol mati.
 */
object HudKeyCatalog {

    private const val CTRL = KeyEvent.KEYCODE_CTRL_LEFT
    private const val SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT
    private const val ALT = KeyEvent.KEYCODE_ALT_LEFT
    private const val WIN = KeyEvent.KEYCODE_META_LEFT

    val mouse = listOf(
        HudKeyOption("Aksi", "Klik kiri", HudKind.MOUSE_LEFT, labelEn = "Left click"),
        HudKeyOption("Aksi", "Klik kanan", HudKind.MOUSE_RIGHT, labelEn = "Right click"),
        HudKeyOption("Aksi", "Klik tengah", HudKind.MOUSE_MIDDLE, labelEn = "Middle click"),
        HudKeyOption("Aksi", "Tukar Klik Kiri ⇄ Kanan", HudKind.MOUSE_SWAP, labelEn = "Swap Left ⇄ Right Click"),
        HudKeyOption("Aksi", "Scroll naik", HudKind.SCROLL_UP, labelEn = "Scroll up"),
        HudKeyOption("Aksi", "Scroll turun", HudKind.SCROLL_DOWN, labelEn = "Scroll down"),
        HudKeyOption("Aksi", "Geser scroll", HudKind.SCROLL_SLIDER, labelEn = "Swipe to scroll"),
        HudKeyOption("Aksi", "Joystick analog", HudKind.PAD_STICK, labelEn = "Analog stick"),
        HudKeyOption("Aksi", "Ganti mode input", HudKind.INPUT_SWITCH, labelEn = "Switch input mode"),
        HudKeyOption(
            "Aksi",
            "Klik kiri (tahan = drag)",
            HudKind.MOUSE_LEFT,
            labelEn = "Left click (hold = drag)",
            defaultAction = HudAction.HOLD,
        ),
    )

    val modifiers = listOf(
        HudKeyOption("Modifier", "Ctrl", HudKind.KEY, CTRL, defaultAction = HudAction.TOGGLE),
        HudKeyOption("Modifier", "Shift", HudKind.KEY, SHIFT, defaultAction = HudAction.TOGGLE),
        HudKeyOption("Modifier", "Alt", HudKind.KEY, ALT, defaultAction = HudAction.TOGGLE),
        HudKeyOption("Modifier", "Win", HudKind.KEY, WIN, defaultAction = HudAction.TOGGLE),
        HudKeyOption("Modifier", "Ctrl (1x)", HudKind.KEY, CTRL, labelEn = "Ctrl (1x)", defaultAction = HudAction.ONE_SHOT),
        HudKeyOption("Modifier", "Shift (1x)", HudKind.KEY, SHIFT, labelEn = "Shift (1x)", defaultAction = HudAction.ONE_SHOT),
        HudKeyOption("Modifier", "Alt (1x)", HudKind.KEY, ALT, labelEn = "Alt (1x)", defaultAction = HudAction.ONE_SHOT),
        HudKeyOption("Modifier", "Win (1x)", HudKind.KEY, WIN, labelEn = "Win (1x)", defaultAction = HudAction.ONE_SHOT),
    )

    val general = listOf(
        HudKeyOption("Single Key", "Esc", HudKind.KEY, KeyEvent.KEYCODE_ESCAPE),
        HudKeyOption("Single Key", "Tab", HudKind.KEY, KeyEvent.KEYCODE_TAB),
        HudKeyOption("Single Key", "Enter", HudKind.KEY, KeyEvent.KEYCODE_ENTER),
        HudKeyOption("Single Key", "Backspace", HudKind.KEY, KeyEvent.KEYCODE_DEL),
        HudKeyOption("Single Key", "Del", HudKind.KEY, KeyEvent.KEYCODE_FORWARD_DEL),
        HudKeyOption("Single Key", "Space", HudKind.KEY, KeyEvent.KEYCODE_SPACE),
        HudKeyOption("Single Key", "Ins", HudKind.KEY, KeyEvent.KEYCODE_INSERT),
        HudKeyOption("Single Key", "Home", HudKind.KEY, KeyEvent.KEYCODE_MOVE_HOME),
        HudKeyOption("Single Key", "End", HudKind.KEY, KeyEvent.KEYCODE_MOVE_END),
        HudKeyOption("Single Key", "PgUp", HudKind.KEY, KeyEvent.KEYCODE_PAGE_UP),
        HudKeyOption("Single Key", "PgDn", HudKind.KEY, KeyEvent.KEYCODE_PAGE_DOWN),
        HudKeyOption("Single Key", "PrtSc", HudKind.KEY, KeyEvent.KEYCODE_SYSRQ),
        HudKeyOption("Single Key", "Pause", HudKind.KEY, KeyEvent.KEYCODE_BREAK),
        HudKeyOption("Single Key", "ScrLk", HudKind.KEY, KeyEvent.KEYCODE_SCROLL_LOCK),
        HudKeyOption("Single Key", "Menu", HudKind.KEY, KeyEvent.KEYCODE_MENU),
        HudKeyOption("Single Key", "Caps", HudKind.KEY, KeyEvent.KEYCODE_CAPS_LOCK),
    )

    val arrows = listOf(
        HudKeyOption("Panah", "\u25c0", HudKind.KEY, KeyEvent.KEYCODE_DPAD_LEFT),
        HudKeyOption("Panah", "\u25b2", HudKind.KEY, KeyEvent.KEYCODE_DPAD_UP),
        HudKeyOption("Panah", "\u25bc", HudKind.KEY, KeyEvent.KEYCODE_DPAD_DOWN),
        HudKeyOption("Panah", "\u25b6", HudKind.KEY, KeyEvent.KEYCODE_DPAD_RIGHT),
    )

    val topNumbers = (0..9).map { digit ->
        HudKeyOption("Angka", "$digit", HudKind.KEY, KeyEvent.KEYCODE_0 + digit)
    }

    val functionKeys = (1..12).map {
        HudKeyOption("F1 - F12", "F$it", HudKind.KEY, KeyEvent.KEYCODE_F1 + (it - 1))
    }

    val numpad = (0..9).map {
        HudKeyOption("Numpad", "Num $it", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_0 + it, labelEn = "Num $it")
    } + listOf(
        HudKeyOption("Numpad", "NumLk", HudKind.KEY, KeyEvent.KEYCODE_NUM_LOCK, labelEn = "NumLk"),
        HudKeyOption("Numpad", "Num /", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_DIVIDE, labelEn = "Num /"),
        HudKeyOption("Numpad", "Num *", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_MULTIPLY, labelEn = "Num *"),
        HudKeyOption("Numpad", "Num -", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, labelEn = "Num -"),
        HudKeyOption("Numpad", "Num +", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_ADD, labelEn = "Num +"),
        HudKeyOption("Numpad", "Num .", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_DOT, labelEn = "Num ."),
        HudKeyOption("Numpad", "Num \u21b5", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_ENTER, labelEn = "Num \u21b5"),
        HudKeyOption("Numpad", "Num =", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_EQUALS, labelEn = "Num ="),
        HudKeyOption("Numpad", "Num (", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN, labelEn = "Num ("),
        HudKeyOption("Numpad", "Num )", HudKind.KEY, KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN, labelEn = "Num )"),
    )

    val letters = ('A'..'Z').map { ch ->
        HudKeyOption("Huruf", "$ch", HudKind.KEY, KeyEvent.KEYCODE_A + (ch - 'A'))
    }

    val symbols = listOf(
        HudKeyOption("Simbol", "/", HudKind.KEY, KeyEvent.KEYCODE_SLASH),
        HudKeyOption("Simbol", "\\", HudKind.KEY, KeyEvent.KEYCODE_BACKSLASH),
        HudKeyOption("Simbol", "-", HudKind.KEY, KeyEvent.KEYCODE_MINUS),
        HudKeyOption("Simbol", "=", HudKind.KEY, KeyEvent.KEYCODE_EQUALS),
        HudKeyOption("Simbol", "[", HudKind.KEY, KeyEvent.KEYCODE_LEFT_BRACKET),
        HudKeyOption("Simbol", "]", HudKind.KEY, KeyEvent.KEYCODE_RIGHT_BRACKET),
        HudKeyOption("Simbol", ";", HudKind.KEY, KeyEvent.KEYCODE_SEMICOLON),
        HudKeyOption("Simbol", "'", HudKind.KEY, KeyEvent.KEYCODE_APOSTROPHE),
        HudKeyOption("Simbol", ",", HudKind.KEY, KeyEvent.KEYCODE_COMMA),
        HudKeyOption("Simbol", ".", HudKind.KEY, KeyEvent.KEYCODE_PERIOD),
        HudKeyOption("Simbol", "`", HudKind.KEY, KeyEvent.KEYCODE_GRAVE),
        HudKeyOption("Simbol", "*", HudKind.KEY, KeyEvent.KEYCODE_STAR),
        HudKeyOption("Simbol", "+", HudKind.KEY, KeyEvent.KEYCODE_PLUS),
        HudKeyOption("Simbol", "@", HudKind.KEY, KeyEvent.KEYCODE_AT),
        HudKeyOption("Simbol", "#", HudKind.KEY, KeyEvent.KEYCODE_POUND),
    )

    val singleKeys = general + arrows + topNumbers + symbols

    /** Pilihan tombol utama untuk pembuat kombinasi bebas di editor. */
    val comboMainKeys: List<HudKeyOption> = letters + functionKeys + singleKeys + numpad

    private val keyNames: Map<Int, String> by lazy {
        (singleKeys + functionKeys + letters + numpad)
            .filter { it.kind == HudKind.KEY }
            .associate { it.keyCode to it.label }
    }

    /** Nama mudah baca untuk satu keyCode; dipakai editor kombinasi. */
    fun keyName(code: Int): String = when (code) {
        android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT, android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
        android.view.KeyEvent.KEYCODE_ALT_LEFT, android.view.KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
        android.view.KeyEvent.KEYCODE_META_LEFT, android.view.KeyEvent.KEYCODE_META_RIGHT -> "Win"
        else -> keyNames[code] ?: "Key"
    }

    val combos = listOf(
        HudKeyOption("Kombinasi", "Ctrl+C", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_C)),
        HudKeyOption("Kombinasi", "Ctrl+V", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_V)),
        HudKeyOption("Kombinasi", "Ctrl+X", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_X)),
        HudKeyOption("Kombinasi", "Ctrl+Z", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_Z)),
        HudKeyOption("Kombinasi", "Ctrl+Y", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_Y)),
        HudKeyOption("Kombinasi", "Ctrl+A", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_A)),
        HudKeyOption("Kombinasi", "Ctrl+S", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_S)),
        HudKeyOption("Kombinasi", "Ctrl+F", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_F)),
        HudKeyOption("Kombinasi", "Ctrl+P", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_P)),
        HudKeyOption("Kombinasi", "Ctrl+W", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_W)),
        HudKeyOption("Kombinasi", "Ctrl+T", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_T)),
        HudKeyOption("Kombinasi", "Ctrl+N", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_N)),
        HudKeyOption("Kombinasi", "Ctrl+R", HudKind.COMBO, combo = listOf(CTRL, KeyEvent.KEYCODE_R)),
        HudKeyOption("Kombinasi", "Ctrl+Shift+T", HudKind.COMBO, combo = listOf(CTRL, SHIFT, KeyEvent.KEYCODE_T)),
        HudKeyOption("Kombinasi", "Ctrl+Shift+Esc", HudKind.COMBO, combo = listOf(CTRL, SHIFT, KeyEvent.KEYCODE_ESCAPE)),
        HudKeyOption("Kombinasi", "Ctrl+Alt+Del", HudKind.COMBO, combo = listOf(CTRL, ALT, KeyEvent.KEYCODE_FORWARD_DEL)),
        HudKeyOption("Kombinasi", "Alt+Tab", HudKind.COMBO, combo = listOf(ALT, KeyEvent.KEYCODE_TAB)),
        HudKeyOption("Kombinasi", "Alt+F4", HudKind.COMBO, combo = listOf(ALT, KeyEvent.KEYCODE_F4)),
        HudKeyOption("Kombinasi", "Alt+Enter", HudKind.COMBO, combo = listOf(ALT, KeyEvent.KEYCODE_ENTER)),
        HudKeyOption("Kombinasi", "Win+D", HudKind.COMBO, combo = listOf(WIN, KeyEvent.KEYCODE_D)),
        HudKeyOption("Kombinasi", "Win+E", HudKind.COMBO, combo = listOf(WIN, KeyEvent.KEYCODE_E)),
        HudKeyOption("Kombinasi", "Win+R", HudKind.COMBO, combo = listOf(WIN, KeyEvent.KEYCODE_R)),
        HudKeyOption("Kombinasi", "Win+L", HudKind.COMBO, combo = listOf(WIN, KeyEvent.KEYCODE_L)),
        HudKeyOption("Kombinasi", "Win+Tab", HudKind.COMBO, combo = listOf(WIN, KeyEvent.KEYCODE_TAB)),
        HudKeyOption("Kombinasi", "Win+Shift+S", HudKind.COMBO, combo = listOf(WIN, SHIFT, KeyEvent.KEYCODE_S)),
        HudKeyOption("Kombinasi", "Win+I", HudKind.COMBO, combo = listOf(WIN, KeyEvent.KEYCODE_I)),
        HudKeyOption("Kombinasi", "Win+X", HudKind.COMBO, combo = listOf(WIN, KeyEvent.KEYCODE_X)),
    )

    val macros = listOf(
        HudKeyOption(
            "Makro",
            "CMD",
            HudKind.MACRO,
            combo = listOf(WIN, KeyEvent.KEYCODE_R),
            macroText = "cmd",
            macroSendEnter = true,
            labelEn = "CMD",
        ),
        HudKeyOption(
            "Makro",
            "PShell",
            HudKind.MACRO,
            combo = listOf(WIN, KeyEvent.KEYCODE_R),
            macroText = "powershell",
            macroSendEnter = true,
            labelEn = "PShell",
        ),
        HudKeyOption(
            "Makro",
            "TaskMgr",
            HudKind.COMBO,
            combo = listOf(CTRL, SHIFT, KeyEvent.KEYCODE_ESCAPE),
            labelEn = "TaskMgr",
        ),
        HudKeyOption(
            "Makro",
            "Kunci PC",
            HudKind.COMBO,
            combo = listOf(WIN, KeyEvent.KEYCODE_L),
            labelEn = "Lock PC",
        ),
        HudKeyOption(
            "Makro",
            "Snip",
            HudKind.COMBO,
            combo = listOf(WIN, SHIFT, KeyEvent.KEYCODE_S),
            labelEn = "Snip",
        ),
        HudKeyOption(
            "Makro",
            "Desk+",
            HudKind.COMBO,
            combo = listOf(WIN, CTRL, KeyEvent.KEYCODE_D),
            labelEn = "Desk+",
        ),
        HudKeyOption(
            "Makro",
            "Desk\u25c0",
            HudKind.COMBO,
            combo = listOf(WIN, CTRL, KeyEvent.KEYCODE_DPAD_LEFT),
            labelEn = "Desk\u25c0",
        ),
        HudKeyOption(
            "Makro",
            "Desk\u25b6",
            HudKind.COMBO,
            combo = listOf(WIN, CTRL, KeyEvent.KEYCODE_DPAD_RIGHT),
            labelEn = "Desk\u25b6",
        ),
        HudKeyOption(
            "Makro",
            "Ketik...",
            HudKind.MACRO,
            macroText = "ipconfig /all",
            macroSendEnter = true,
            labelEn = "Type...",
        ),
    )

    /**
     * Grup Gamepad untuk overlay kontrol.
     *
     * Inilah pengganti bagian gamepad yang hilang bersama overlay lama: dulu
     * D-Pad, A/B/X/Y, dan shoulder hanya bisa didapat dari overlay gamepad
     * terpisah. Sekarang semuanya pilihan tombol biasa di overlay kontrol, jadi
     * posisinya bebas diatur seperti tombol lain.
     *
     * Sengaja memakai HudKind yang sudah ada (PAD_STICK, KEY, MOUSE_LEFT/RIGHT,
     * SCROLL_UP/DOWN) alih-alih menambah anggota enum baru: menambah anggota
     * HudKind memaksa perubahan di empat `when` exhaustive (labelFor, glyph,
     * hudIconFor, runHudKey) tanpa menambah kemampuan apa pun di sini.
     *
     * Penamaan mengikuti pemetaan gamepad fisik di KzGamepadActionMapper
     * (A = klik kiri, B = klik kanan, X = Enter, Y = Escape, shoulder = satu
     * takik scroll) supaya pad di layar dan pad Bluetooth berperilaku sama.
     */
    val gamepad = listOf(
        HudKeyOption("Gamepad", "Joystick analog", HudKind.PAD_STICK, labelEn = "Analog stick"),
        HudKeyOption("Gamepad", "D-Pad \u25b2", HudKind.KEY, KeyEvent.KEYCODE_DPAD_UP, labelEn = "D-Pad Up"),
        HudKeyOption("Gamepad", "D-Pad \u25bc", HudKind.KEY, KeyEvent.KEYCODE_DPAD_DOWN, labelEn = "D-Pad Down"),
        HudKeyOption("Gamepad", "D-Pad \u25c0", HudKind.KEY, KeyEvent.KEYCODE_DPAD_LEFT, labelEn = "D-Pad Left"),
        HudKeyOption("Gamepad", "D-Pad \u25b6", HudKind.KEY, KeyEvent.KEYCODE_DPAD_RIGHT, labelEn = "D-Pad Right"),
        HudKeyOption("Gamepad", "A \u00b7 Klik kiri", HudKind.MOUSE_LEFT, labelEn = "A \u00b7 Left click"),
        HudKeyOption("Gamepad", "B \u00b7 Klik kanan", HudKind.MOUSE_RIGHT, labelEn = "B \u00b7 Right click"),
        HudKeyOption("Gamepad", "X \u00b7 Enter", HudKind.KEY, KeyEvent.KEYCODE_ENTER, labelEn = "X \u00b7 Enter"),
        HudKeyOption("Gamepad", "Y \u00b7 Esc", HudKind.KEY, KeyEvent.KEYCODE_ESCAPE, labelEn = "Y \u00b7 Escape"),
        HudKeyOption("Gamepad", "L1 \u00b7 Scroll naik", HudKind.SCROLL_UP, labelEn = "L1 \u00b7 Scroll up"),
        HudKeyOption("Gamepad", "R1 \u00b7 Scroll turun", HudKind.SCROLL_DOWN, labelEn = "R1 \u00b7 Scroll down"),
        // Melengkapi susunan gamepad sungguhan. Sebelumnya hanya ada stick,
        // D-Pad, ABXY, dan L1/R1 sehingga tidak ada trigger, tidak ada
        // Start/Select, dan tidak ada stick kedua. Semua entri baru memakai
        // HudKind yang sudah ada (KEY dengan kode tombol, MOUSE_MIDDLE,
        // PAD_STICK) sehingga tidak menambah anggota enum dan tidak merusak
        // empat `when` exhaustive atas HudKind.
        HudKeyOption("Gamepad", "Joystick analog 2", HudKind.PAD_STICK, labelEn = "Analog stick 2"),
        HudKeyOption("Gamepad", "L2 \u00b7 Page Up", HudKind.KEY, KeyEvent.KEYCODE_PAGE_UP, labelEn = "L2 \u00b7 Page Up"),
        HudKeyOption("Gamepad", "R2 \u00b7 Page Down", HudKind.KEY, KeyEvent.KEYCODE_PAGE_DOWN, labelEn = "R2 \u00b7 Page Down"),
        HudKeyOption("Gamepad", "L3 \u00b7 Klik tengah", HudKind.MOUSE_MIDDLE, labelEn = "L3 \u00b7 Middle click"),
        HudKeyOption("Gamepad", "R3 \u00b7 Tab", HudKind.KEY, KeyEvent.KEYCODE_TAB, labelEn = "R3 \u00b7 Tab"),
        HudKeyOption("Gamepad", "Start \u00b7 Menu", HudKind.KEY, KeyEvent.KEYCODE_MENU, labelEn = "Start \u00b7 Menu"),
        // "Select" memakai Home, bukan Back: KEYCODE_BACK, KEYCODE_HOME, dan
        // KEYCODE_APP_SWITCH tidak ada di tabel KeyboardMapper (modul vendored
        // freeRDPCore), jadi tombol yang memakainya akan tampak ada di panel
        // tetapi tidak mengirim apa-apa ke PC.
        HudKeyOption("Gamepad", "Select \u00b7 Home", HudKind.KEY, KeyEvent.KEYCODE_MOVE_HOME, labelEn = "Select \u00b7 Home"),
    )

    val groups: List<Triple<String, String, List<HudKeyOption>>> = listOf(
        // Gamepad di urutan pertama: pengguna mengeluh pilihan gamepad hilang,
        // jadi grup ini yang terbuka saat dialog "Tambah tombol" muncul.
        Triple("Gamepad", "Gamepad", gamepad),
        Triple("Kombinasi", "Combos", combos),
        Triple("Makro", "Macros", macros),
        Triple("F1 - F12", "F1 - F12", functionKeys),
        Triple("Single Key", "Single Key", singleKeys),
        Triple("Numpad", "Numpad", numpad),
        Triple("Huruf A-Z", "Letters A-Z", letters),
        Triple("Modifier", "Modifiers", modifiers),
        Triple("Mouse & Scroll", "Mouse & Scroll", mouse),
    )

    /** Dipakai dialog "ubah tombol": label ringkas untuk aksi non-keyboard. */
    fun labelFor(kind: HudKind): String = when (kind) {
        HudKind.MOUSE_LEFT -> "Kiri"
        HudKind.MOUSE_RIGHT -> "Kanan"
        HudKind.MOUSE_MIDDLE -> "Tengah"
        HudKind.MOUSE_SWAP -> "L⇄R"
        HudKind.SCROLL_UP -> "Naik"
        HudKind.SCROLL_DOWN -> "Turun"
        HudKind.SCROLL_SLIDER -> "Geser"
        HudKind.INPUT_SWITCH -> "Mode"
        else -> "Tombol"
    }

    /** Versi bahasa Inggris dari [labelFor]. */
    fun labelForEn(kind: HudKind): String = when (kind) {
        HudKind.MOUSE_LEFT -> "L"
        HudKind.MOUSE_RIGHT -> "R"
        HudKind.MOUSE_MIDDLE -> "M"
        HudKind.MOUSE_SWAP -> "L⇄R"
        HudKind.SCROLL_UP -> "Up"
        HudKind.SCROLL_DOWN -> "Down"
        HudKind.SCROLL_SLIDER -> "Swipe"
        HudKind.INPUT_SWITCH -> "Mode"
        else -> "Key"
    }
}

/** Satu-satunya tempat yang tahu arti KzAudioMode buat label UI. */
internal fun KzAudioMode.shortTitle(): String = when (this) {
    KzAudioMode.DEVICE -> "Perangkat"
    KzAudioMode.REMOTE -> "Remote"
    KzAudioMode.OFF -> "Mati"
}

internal fun KzAudioMode.shortTitleEn(): String = when (this) {
    KzAudioMode.DEVICE -> "Device"
    KzAudioMode.REMOTE -> "Remote"
    KzAudioMode.OFF -> "Off"
}
