package com.kazamatech.kzdesk.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * Bahasa aplikasi.
 *
 * Dua bahasa penuh untuk seluruh alur utama: Indonesia (default kalau bahasa
 * sistem Indonesia) dan English. Pilihan disimpan di prefs dan dipakai
 * seluruh layar lewat [KzText.t].
 */
enum class KzLang(val code: String, val label: String) {
    ID("id", "Indonesia"),
    EN("en", "English"),
    ;

    companion object {
        /** Pilih default sekali dari bahasa sistem. */
        fun fromSystem(context: Context): KzLang {
            val tag = Locale.getDefault().language
            if (tag.equals("in", true) || tag.equals("id", true)) return ID
            val cfg: Configuration = context.resources.configuration
            val cfgTag = cfg.locales[0]?.language.orEmpty()
            return if (cfgTag.equals("in", true) || cfgTag.equals("id", true)) ID else EN
        }
    }
}

/**
 * Kunci teks. Semua label UI yang dipakai di alur utama ada di sini.
 * Ditulis sebagai objek (bukan resource XML) karena banyak yang dipakai
 * sebagai argumen langsung atau dibentuk runtime.
 */
object KzText {
    /**
     * Bahasa aktif sebagai state Compose — BUKA volatile biasa. Dulu ini
     * `@Volatile var`, jadi mengganti bahasa tidak memberi tahu siapa pun:
     * layar cuma kebagian render ulang kalau kebetulan ada state lain yang
     * berubah, dan setengah UI tampil dengan bahasa lama. Sekarang setiap
     * `kz()` / `t()` / `kzLang()` berlangganan state ini, jadi satu ketuk
     * di pemilih bahasa mengganti SELURUH teks app seketika.
     */
    private val langState = mutableStateOf(KzLang.ID)

    fun setLang(value: KzLang) {
        langState.value = value
    }

    /** Aman dibaca dari thread mana pun (non-composable). */
    fun current(): KzLang = langState.value

    fun t(key: String): String =
        table[key]?.get(current()) ?: table[key]?.get(KzLang.ID) ?: key

    fun t(key: String, vararg args: Any?): String {
        var out = t(key)
        args.forEachIndexed { i, a -> out = out.replace("{" + i + "}", a.toString()) }
        return out
    }

    private val table: Map<String, Map<KzLang, String>> = mapOf(
        // ---- shell / home ----
        "app.tagline" to mapOf(
            KzLang.ID to "Klien RDP Android untuk Windows dan Windows Server.",
            KzLang.EN to "Android RDP client for Windows and Windows Server.",
        ),
        "home.devices" to mapOf(KzLang.ID to "Perangkat", KzLang.EN to "Devices"),
        "home.display" to mapOf(KzLang.ID to "Tampilan", KzLang.EN to "Display"),
        "home.keyboard" to mapOf(KzLang.ID to "Keyboard & HUD", KzLang.EN to "Keyboard & HUD"),
        "home.transport" to mapOf(KzLang.ID to "Transport default", KzLang.EN to "Default transport"),
        "home.security" to mapOf(KzLang.ID to "Keamanan", KzLang.EN to "Security"),
        "home.about" to mapOf(KzLang.ID to "Tentang", KzLang.EN to "About"),
        "home.language" to mapOf(KzLang.ID to "Bahasa", KzLang.EN to "Language"),
        "home.language.sub" to mapOf(
            KzLang.ID to "Bahasa aplikasi; berlaku langsung",
            KzLang.EN to "App language; applies immediately",
        ),
        "home.new" to mapOf(KzLang.ID to "Perangkat baru", KzLang.EN to "New device"),
        "home.connect" to mapOf(KzLang.ID to "Sambung", KzLang.EN to "Connect"),
        "home.edit" to mapOf(KzLang.ID to "Ubah", KzLang.EN to "Edit"),
        "home.delete" to mapOf(KzLang.ID to "Hapus", KzLang.EN to "Delete"),
        "home.empty.title" to mapOf(
            KzLang.ID to "Belum ada perangkat",
            KzLang.EN to "No devices yet",
        ),
        "home.empty.body" to mapOf(
            KzLang.ID to "Tambahkan alamat Windows atau Windows Server untuk mulai.",
            KzLang.EN to "Add a Windows or Windows Server address to start.",
        ),

        // ---- about + support ----
        "about.version" to mapOf(KzLang.ID to "Versi app", KzLang.EN to "App version"),
        "about.engine" to mapOf(KzLang.ID to "Mesin RDP", KzLang.EN to "RDP engine"),
        "about.developer" to mapOf(KzLang.ID to "Pengembang", KzLang.EN to "Developer"),
        "about.license" to mapOf(KzLang.ID to "Lihat lisensi", KzLang.EN to "View license"),
        "about.license.title" to mapOf(KzLang.ID to "Lisensi", KzLang.EN to "License"),
        "about.license.body" to mapOf(
            KzLang.ID to "FreeRDP — Apache License 2.0\n" +
                "Copyright (C) 2012-2026 FreeRDP contributors.\n\n" +
                "KzDesk Remote — \u00a9 KazamaDev.\n" +
                "Font: Space Grotesk & Inter (SIL Open Font License 1.1).",
            KzLang.EN to "FreeRDP — Apache License 2.0\n" +
                "Copyright (C) 2012-2026 FreeRDP contributors.\n\n" +
                "KzDesk Remote — \u00a9 KazamaDev.\n" +
                "Fonts: Space Grotesk & Inter (SIL Open Font License 1.1).",
        ),
        "about.support.title" to mapOf(
            KzLang.ID to "Dukung saya",
            KzLang.EN to "Support me",
        ),
        "about.support.body" to mapOf(
            KzLang.ID to "Aplikasi ini dikembangkan oleh KazamaDev tanpa iklan dan tanpa " +
                "pelacakan. Kalau berguna, dukungan apa pun dipakai langsung untuk " +
                "biaya build, server uji, dan waktu ngoding.",
            KzLang.EN to "This app is developed by KazamaDev, without ads and without " +
                "tracking. If it helps you, any support goes straight to build " +
                "costs, test servers, and coding time.",
        ),
        "about.support.saweria" to mapOf(
            KzLang.ID to "Donasi",
            KzLang.EN to "Donate",
        ),
        "about.support.github" to mapOf(
            KzLang.ID to "Situs resmi",
            KzLang.EN to "Official website",
        ),
        "about.support.star" to mapOf(
            KzLang.ID to "Beri bintang di GitHub",
            KzLang.EN to "Star on GitHub",
        ),
        "about.support.starred" to mapOf(
            KzLang.ID to "Terima kasih",
            KzLang.EN to "Thanks",
        ),
        "about.thanks" to mapOf(
            KzLang.ID to "Terima kasih sudah memakai KzDesk Remote.",
            KzLang.EN to "Thanks for using KzDesk Remote.",
        ),

        // ---- panel sesi ----
        "panel.input" to mapOf(KzLang.ID to "Input", KzLang.EN to "Input"),
        "panel.pointer" to mapOf(KzLang.ID to "Pointer", KzLang.EN to "Pointer"),
        "panel.buttons" to mapOf(KzLang.ID to "Tombol", KzLang.EN to "Buttons"),
        "panel.keyboard" to mapOf(KzLang.ID to "Keyboard", KzLang.EN to "Keyboard"),
        "panel.screen" to mapOf(KzLang.ID to "Layar", KzLang.EN to "Screen"),
        "panel.session" to mapOf(KzLang.ID to "Sesi", KzLang.EN to "Session"),
        "panel.tips" to mapOf(KzLang.ID to "Tips", KzLang.EN to "Tips"),
        "panel.close" to mapOf(KzLang.ID to "Tutup", KzLang.EN to "Close"),
        "panel.addKey" to mapOf(KzLang.ID to "Tambah tombol", KzLang.EN to "Add button"),
        "panel.editDone" to mapOf(KzLang.ID to "Selesai atur", KzLang.EN to "Done editing"),
        "panel.mapMode" to mapOf(KzLang.ID to "Atur posisi", KzLang.EN to "Edit layout"),
        "panel.resetKeys" to mapOf(
            KzLang.ID to "Kembalikan tombol bawaan",
            KzLang.EN to "Restore default buttons",
        ),
        "panel.disconnect" to mapOf(KzLang.ID to "Putuskan sesi", KzLang.EN to "Disconnect"),
        "panel.screenshot" to mapOf(KzLang.ID to "Ambil screenshot", KzLang.EN to "Screenshot"),
        "pointer.show" to mapOf(KzLang.ID to "Tampilkan pointer", KzLang.EN to "Show pointer"),
        "keys.hold" to mapOf(KzLang.ID to "Tahan", KzLang.EN to "Hold"),
        "keys.tap" to mapOf(KzLang.ID to "Sekali klik", KzLang.EN to "Single tap"),
        "keys.toggle" to mapOf(KzLang.ID to "Toggle", KzLang.EN to "Toggle"),
        "keys.inToolbar" to mapOf(KzLang.ID to "Di toolbar", KzLang.EN to "In toolbar"),
        "keys.onlyScreen" to mapOf(
            KzLang.ID to "Hanya di layar",
            KzLang.EN to "Screen only",
        ),
        "keys.editTitle" to mapOf(KzLang.ID to "UBAH TOMBOL", KzLang.EN to "EDIT BUTTON"),
        "keys.addTitle" to mapOf(KzLang.ID to "TAMBAH TOMBOL", KzLang.EN to "ADD BUTTON"),
        "input.switchHint" to mapOf(
            KzLang.ID to "Pindah ke sentuh langsung",
            KzLang.EN to "Switch to direct touch",
        ),
    )
}

/** Nama bahasa untuk switcher. */
object LangPrefs {
    @Volatile private var loaded = false

    fun install(context: Context): KzLang {
        val sp = context.getSharedPreferences("kzdesk.lang", Context.MODE_PRIVATE)
        val stored = sp.getString("lang", null)
        val lang = stored?.let { code ->
            KzLang.entries.firstOrNull { it.code == code }
        } ?: KzLang.fromSystem(context)
        KzText.setLang(lang)
        loaded = true
        return lang
    }

    fun current(): KzLang = KzText.current()

    fun set(context: Context, lang: KzLang) {
        context.getSharedPreferences("kzdesk.lang", Context.MODE_PRIVATE)
            .edit()
            .putString("lang", lang.code)
            .apply()
        KzText.setLang(lang)
    }

    fun isLoaded(): Boolean = loaded
}

/**
 * Bahasa aktif sebagai state Compose: membaca ini membuat composable
 * berlangganan perubahan bahasa — begitu [LangPrefs.set] dipanggil, semua
 * teks yang memakai `kz()` / `t()` render ulang sendiri.
 */
@Composable
fun kzLang(): KzLang = KzText.current()

/** Pintasan composable: pakai [KzText.t] dengan recomposition saat bahasa ganti. */
@Composable
fun t(key: String): String {
    kzLang()
    return KzText.t(key)
}

/**
 * Teks dua bahasa langsung di tempat: [id] dipakai kalau bahasa Indonesia,
 * [en] kalau English. Ditulis berdampingan dengan pemakaiannya supaya tidak
 * ada kunci terjemahan yang lupa diisi, dan ikut render ulang saat bahasa
 * berganti. Argumen mengisi placeholder {0}, {1}, ...
 */
@Composable
fun kz(id: String, en: String, vararg args: Any?): String =
    fill(if (kzLang() == KzLang.EN) en else id, args)

/** Versi non-composable (notice, callback) — pakai bahasa yang sedang aktif. */
fun kzNow(id: String, en: String, vararg args: Any?): String =
    fill(if (KzText.current() == KzLang.EN) en else id, args)

private fun fill(template: String, args: Array<out Any?>): String {
    if (args.isEmpty()) return template
    var out = template
    args.forEachIndexed { i, a -> out = out.replace("{$i}", a.toString()) }
    return out
}
