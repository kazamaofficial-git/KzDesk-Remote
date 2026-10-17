package com.kazamatech.kzdesk.ui

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Template Controller: tata letak tombol + joystick HUD yang diberi nama dan bisa
 * diimpor/diekspor sebagai JSON.
 *
 * Format berkas (`schema` 1):
 * ```
 * { "format": "kzdesk.controller-template", "schema": 1,
 *   "name": "FPS Joystick", "author": "KazamaDev", "description": "...",
 *   "keys": [ { "id": "...", "kind": "KEY", ... }, ... ] }
 * ```
 * Impor juga menerima: ekspor tata letak lama (`{"app":...,"keys":[...]}`), array
 * tombol polos, dan bundel (`kzdesk.controller-template-bundle` berisi `templates`).
 */
data class ControllerTemplate(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val keys: List<HudKey>,
    val createdAt: Long = 0L,
    val builtIn: Boolean = false,
)

sealed interface TemplateParse {
    data class Ok(
        val templates: List<ControllerTemplate>,
        /** Tombol yang dibuang karena tidak dikenal/rusak. */
        val skippedKeys: Int,
        /** Jumlah tombol makro (mengetik teks otomatis ke PC) — UI wajib meminta konfirmasi. */
        val macroCount: Int,
    ) : TemplateParse

    data class Error(val id: String, val en: String) : TemplateParse
}

object ControllerTemplates {
    const val FORMAT = "kzdesk.controller-template"
    const val BUNDLE_FORMAT = "kzdesk.controller-template-bundle"
    const val SCHEMA = 1
    const val MAX_BYTES = 256 * 1024
    const val MAX_KEYS = 36
    const val MAX_TEMPLATES = 50
    const val MAX_BUNDLE = 20
    const val MAX_NAME = 40
    const val MAX_AUTHOR = 40
    const val MAX_DESCRIPTION = 200
    const val MAX_MACRO_TEXT = 300

    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")

    private fun clean(value: String, max: Int): String =
        value.replace(CONTROL_CHARS, " ").trim().take(max)

    // ---------------------------------------------------------------- tulis
    private fun toObject(t: ControllerTemplate): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("schema", SCHEMA)
        put("name", t.name)
        if (t.author.isNotEmpty()) put("author", t.author)
        if (t.description.isNotEmpty()) put("description", t.description)
        put("keys", JSONArray().also { arr -> t.keys.take(MAX_KEYS).forEach { arr.put(it.toJson()) } })
    }

    /** JSON berformat rapi untuk dibagikan/disimpan sebagai berkas. */
    fun toJson(t: ControllerTemplate): String = toObject(t).toString(2)

    fun bundleToJson(list: List<ControllerTemplate>): String = JSONObject().apply {
        put("format", BUNDLE_FORMAT)
        put("schema", SCHEMA)
        put("templates", JSONArray().also { arr -> list.take(MAX_BUNDLE).forEach { arr.put(toObject(it)) } })
    }.toString(2)

    /** Nama berkas yang aman untuk ekspor. */
    fun fileName(t: ControllerTemplate): String {
        val slug = t.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "template" }
        return "kzdesk-$slug.json"
    }

    // ---------------------------------------------------------------- baca
    fun parse(
        raw: String?,
        defaultSize: Float = HudKey.DEFAULT_SIZE,
        newId: () -> String = { UUID.randomUUID().toString() },
        now: () -> Long = { System.currentTimeMillis() },
    ): TemplateParse {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return TemplateParse.Error("JSON kosong.", "The JSON is empty.")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return TemplateParse.Error("Berkas terlalu besar (maks 256 KB).", "File is too large (max 256 KB).")
        }
        val root: Any = runCatching { if (text.startsWith("[")) JSONArray(text) else JSONObject(text) }.getOrNull()
            ?: return TemplateParse.Error("Bukan JSON yang valid.", "Not valid JSON.")

        val sources: List<JSONObject> = when {
            root is JSONArray -> listOf(JSONObject().put("keys", root))
            root is JSONObject && root.optString("format") == BUNDLE_FORMAT -> {
                val arr = root.optJSONArray("templates") ?: JSONArray()
                (0 until minOf(arr.length(), MAX_BUNDLE)).mapNotNull { arr.optJSONObject(it) }
            }
            else -> listOf(root as JSONObject)
        }
        if (sources.isEmpty()) return TemplateParse.Error("Bundel tidak berisi template.", "The bundle has no templates.")

        var skipped = 0
        var macros = 0
        val out = ArrayList<ControllerTemplate>()
        for (src in sources) {
            if (src.optInt("schema", 1) > SCHEMA) {
                return TemplateParse.Error(
                    "Template dibuat dengan versi KzDesk yang lebih baru. Perbarui aplikasi.",
                    "This template was made with a newer KzDesk. Please update the app.",
                )
            }
            val arr = src.optJSONArray("keys") ?: continue
            val limit = minOf(arr.length(), MAX_KEYS)
            if (arr.length() > MAX_KEYS) skipped += arr.length() - MAX_KEYS
            val parsed = ArrayList<HudKey>()
            for (i in 0 until limit) {
                val key = arr.optJSONObject(i)?.let { HudKey.fromJson(it) }
                if (key == null) skipped++ else parsed += key
            }
            val normalized = HudKey.normalizeImportedKeys(parsed, defaultSize)
            skipped += parsed.size - normalized.size
            if (normalized.isEmpty()) continue
            // Makro dari sumber luar tidak boleh otomatis menekan Enter di PC.
            val safe = normalized.map { k ->
                if (k.kind == HudKind.MACRO) {
                    macros++
                    k.copy(macroSendEnter = false, macroText = k.macroText.take(MAX_MACRO_TEXT))
                } else k
            }
            out += ControllerTemplate(
                id = newId(),
                name = clean(src.optString("name"), MAX_NAME).ifEmpty { "Template" },
                author = clean(src.optString("author"), MAX_AUTHOR),
                description = clean(src.optString("description"), MAX_DESCRIPTION),
                keys = safe,
                createdAt = now(),
            )
        }
        if (out.isEmpty()) {
            return TemplateParse.Error("Tidak ada tombol yang valid di JSON ini.", "No valid buttons found in this JSON.")
        }
        return TemplateParse.Ok(out, skipped, macros)
    }

    // ----------------------------------------------------------- penyimpanan
    fun encodeList(list: List<ControllerTemplate>): String =
        JSONArray().also { arr -> list.take(MAX_TEMPLATES).forEach { arr.put(toObject(it).put("id", it.id).put("createdAt", it.createdAt)) } }.toString()

    fun decodeList(raw: String?): List<ControllerTemplate> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until minOf(arr.length(), MAX_TEMPLATES)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() && seen.add(it) } ?: return@mapNotNull null
            val keysArr = o.optJSONArray("keys") ?: return@mapNotNull null
            val keys = (0 until minOf(keysArr.length(), MAX_KEYS)).mapNotNull { k -> keysArr.optJSONObject(k)?.let { HudKey.fromJson(it) } }
            if (keys.isEmpty()) return@mapNotNull null
            ControllerTemplate(
                id = id,
                name = clean(o.optString("name"), MAX_NAME).ifEmpty { "Template" },
                author = clean(o.optString("author"), MAX_AUTHOR),
                description = clean(o.optString("description"), MAX_DESCRIPTION),
                keys = keys,
                createdAt = o.optLong("createdAt", 0L),
            )
        }
    }

    // --------------------------------------------------------------- bawaan
    fun builtIns(): List<ControllerTemplate> {
        val fromPresets = HudProfilePreset.entries.filter { it != HudProfilePreset.CUSTOM }.map { p ->
            ControllerTemplate(
                id = "builtin.${p.name.lowercase()}",
                name = p.title,
                author = "KazamaDev",
                description = p.detail,
                keys = HudKey.presetLayout(p),
                builtIn = true,
            )
        }
        return fromPresets + fpsTemplate()
    }

    /** Joystick kiri = WASD, joystick kanan = kamera (gerak kursor) + tombol aksi umum FPS. */
    fun fpsTemplate(): ControllerTemplate = ControllerTemplate(
        id = "builtin.fps_joystick",
        name = "FPS Joystick",
        author = "KazamaDev",
        description = "Joystick WASD di kiri, joystick kamera di kanan, tembak/bidik, lompat, lari, jongkok.",
        builtIn = true,
        keys = listOf(
            HudKey("fps_move", HudKind.PAD_STICK, "Gerak", x = 0.06f, y = 0.56f, size = 92f, stickMode = HudStickMode.WASD),
            HudKey("fps_look", HudKind.PAD_STICK, "Kamera", x = 0.80f, y = 0.56f, size = 92f, stickMode = HudStickMode.POINTER, stickSens = 1.6f),
            HudKey("fps_sprint", HudKind.KEY, "Lari", keyCode = KeyEvent.KEYCODE_SHIFT_LEFT, action = HudAction.HOLD, x = 0.06f, y = 0.36f),
            HudKey("fps_crouch", HudKind.KEY, "Jongkok", keyCode = KeyEvent.KEYCODE_CTRL_LEFT, action = HudAction.TOGGLE, x = 0.17f, y = 0.36f),
            HudKey("fps_fire", HudKind.MOUSE_LEFT, "Tembak", action = HudAction.HOLD, x = 0.66f, y = 0.70f),
            HudKey("fps_aim", HudKind.MOUSE_RIGHT, "Bidik", action = HudAction.HOLD, x = 0.66f, y = 0.50f),
            HudKey("fps_jump", HudKind.KEY, "Lompat", keyCode = KeyEvent.KEYCODE_SPACE, x = 0.90f, y = 0.34f),
            HudKey("fps_reload", HudKind.KEY, "R", keyCode = KeyEvent.KEYCODE_R, x = 0.80f, y = 0.34f),
            HudKey("fps_use", HudKind.KEY, "E", keyCode = KeyEvent.KEYCODE_E, x = 0.70f, y = 0.30f),
            HudKey("fps_menu", HudKind.KEY, "Esc", keyCode = KeyEvent.KEYCODE_ESCAPE, x = 0.46f, y = 0.06f),
        ),
    )

    /** Ganti nama (dan penulis) saat menyimpan tata letak saat ini sebagai template baru. */
    fun fromCurrent(name: String, keys: List<HudKey>, id: String = UUID.randomUUID().toString(), now: Long = System.currentTimeMillis()) =
        ControllerTemplate(
            id = id,
            name = clean(name, MAX_NAME).ifEmpty { "Template" },
            keys = keys.take(MAX_KEYS),
            createdAt = now,
        )
}
