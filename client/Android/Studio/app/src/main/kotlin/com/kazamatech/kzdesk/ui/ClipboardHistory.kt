package com.kazamatech.kzdesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Riwayat clipboard — fitur khas KzDesk.
 *
 * Menyimpan maksimal [MAX_ITEMS] teks terakhir yang disalin di HP selama
 * sesi berjalan (Android hanya mengizinkan app membaca clipboard saat
 * berada di depan, dan sesi remote memang selalu di depan). Satu ketukan
 * menyalin ulang entri ke clipboard; kalau kanal clipboard sesi aktif,
 * sinkronisasi otomatis yang sudah ada langsung meneruskannya ke PC.
 *
 * Tidak ada clipboard JARAK JAUH yang disimpan di sini — hanya klip milik
 * HP sendiri, dan isinya tidak pernah dikirim ke mana pun selain PC yang
 * sedang tersambung lewat kanal clipboard RDP.
 */
object ClipboardHistory {
    data class Entry(val id: Long, val text: String, val atMillis: Long)

    private const val SP_NAME = "kzdesk.clipboard"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 10
    private const val MAX_TEXT_LENGTH = 4_000

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Muat riwayat tersimpan; aman dipanggil berulang. */
    fun load(context: Context) {
        val raw = runCatching {
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null)
        }.getOrNull() ?: return
        _entries.value = parse(raw)
    }

    /** Catat satu klip baru. Duplikat beruntun dan klip kosong diabaikan. */
    fun record(context: Context, text: String?) {
        val clean = text?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LENGTH) ?: return
        val current = _entries.value
        if (current.firstOrNull()?.text == clean) return
        val entry = Entry(
            id = System.currentTimeMillis(),
            text = clean,
            atMillis = System.currentTimeMillis(),
        )
        _entries.value = (listOf(entry) + current.filter { it.text != clean }).take(MAX_ITEMS)
        persist(context)
    }

    fun remove(context: Context, id: Long) {
        _entries.value = _entries.value.filter { it.id != id }
        persist(context)
    }

    fun clear(context: Context) {
        _entries.value = emptyList()
        persist(context)
    }

    /**
     * Salin entri ke clipboard HP. Listener clipboard sesi yang sudah ada
     * meneruskannya ke PC bila kanal clipboard aktif — tidak ada jalur
     * pengiriman baru di sini.
     */
    fun copyToPhone(context: Context, entry: Entry): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@runCatching false
        cm.setPrimaryClip(ClipData.newPlainText("KzDesk", entry.text))
        true
    }.getOrDefault(false)

    private fun persist(context: Context) {
        runCatching {
            val arr = JSONArray()
            _entries.value.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("text", e.text)
                        put("at", e.atMillis)
                    },
                )
            }
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, arr.toString())
                .apply()
        }
    }

    private fun parse(raw: String): List<Entry> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(o.optLong("id"), text.take(MAX_TEXT_LENGTH), o.optLong("at"))
        }.take(MAX_ITEMS)
    }.getOrDefault(emptyList())
}
