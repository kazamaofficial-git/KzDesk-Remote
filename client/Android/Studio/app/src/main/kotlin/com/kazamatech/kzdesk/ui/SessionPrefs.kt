package com.kazamatech.kzdesk.ui

import android.content.Context
import com.kazamatech.kzdesk.core.SmartResolution
import com.kazamatech.kzdesk.ui.input.KzPadCluster
import com.kazamatech.kzdesk.ui.input.KzPadLayout
import com.kazamatech.kzdesk.ui.input.KzPadPos
import com.kazamatech.kzdesk.ui.input.KzStickMode

/** Cara input di layar sesi. */
enum class InputMode(val title: String, val titleEn: String, val detail: String, val detailEn: String) {
    TRACKPAD(
        "Trackpad", "Trackpad",
        "1 jari: geser pointer, ketuk = kiri, tahan lalu geser = drag kiri. 2 jari: geser = scroll, ketuk = kanan, tahan lalu geser = drag kanan.",
        "1 finger: move pointer, tap = left click, hold then drag = left-drag. 2 fingers: swipe = scroll, tap = right click, hold then drag = right-drag.",
    ),
    DIRECT(
        "Sentuh langsung", "Direct touch",
        "Sentuh langsung di titik yang dituju",
        "Touch the exact point you want to hit",
    ),
}

enum class PointerStyle(val title: String, val titleEn: String) {
    DOT("Titik", "Dot"),
    ARROW("Panah", "Arrow"),
}

/**
 * Latar tombol kontrol di layar sesi. Desktop remote bisa putih terang, jadi
 * tombol border-only putih bisa hilang; rasa gelap tipis bikin ikon selalu
 * kelihatan tanpa menutupi gambar.
 */
enum class HudPlate(val title: String, val titleEn: String) {
    DARK("Gelap tegas", "Dark"),
    // Keep ordinal 1 for existing preferences; this is now a softer dark plate, not white.
    LIGHT("Gelap lembut", "Soft dark"),
    NONE("Transparan", "Transparent"),
}

/**
 * Preferensi kontrol sesi (file `kzdesk.input`) + preferensi per-perangkat
 * (file `kzdesk.session`). Dipisah: yang global dibawa ke semua perangkat,
 * yang per-perangkat (zoom, keyboard, tombol HUD) disimpan per id.
 *
 * Ronde 8: semua kunci model lama yang sudah tidak dibaca siapa pun
 * (keyboard overlay, cluster, panel, corner) dibuang dari kode —
 * nilainya dibiarkan di file supaya tidak ada migrasi yang bisa pecah.
 */
class SessionPrefs(context: Context) {

    private val input = context.applicationContext
        .getSharedPreferences(INPUT_FILE, Context.MODE_PRIVATE)
    private val device = context.applicationContext
        .getSharedPreferences(DEVICE_FILE, Context.MODE_PRIVATE)

    // ---- global ----

    var inputMode: InputMode
        get() = InputMode.entries.getOrElse(input.getInt(KEY_INPUT_MODE, 0)) { InputMode.TRACKPAD }
        set(v) = input.edit().putInt(KEY_INPUT_MODE, v.ordinal).apply()

    var pointerStyle: PointerStyle
        get() = PointerStyle.entries.getOrElse(input.getInt(KEY_POINTER_STYLE, 0)) { PointerStyle.DOT }
        set(v) = input.edit().putInt(KEY_POINTER_STYLE, v.ordinal).apply()

    /** Ukuran pointer di layar, dp. */
    var pointerSize: Float
        get() = input.getFloat(KEY_POINTER_SIZE, 22f).coerceIn(10f, 52f)
        set(v) = input.edit().putFloat(KEY_POINTER_SIZE, v.coerceIn(10f, 52f)).apply()

    /** Latar tombol kontrol; default gelap tipis supaya ikon kelihatan di desktop terang. */
    var hudPlate: HudPlate
        get() = HudPlate.entries.getOrElse(input.getInt(KEY_HUD_PLATE, 0)) { HudPlate.DARK }
        set(v) = input.edit().putInt(KEY_HUD_PLATE, v.ordinal).apply()

    /** Transparansi seluruh tombol HUD (0 = transparan, 1 = penuh). */
    var hudOpacity: Float
        get() = input.getFloat(KEY_HUD_OPACITY, 0.88f).coerceIn(0f, 1f)
        set(v) = input.edit().putFloat(KEY_HUD_OPACITY, v.coerceIn(0f, 1f)).apply()

    /** Sembunyikan tombol HUD ketika ada keyboard/mouse/gamepad eksternal. */
    var autoHideHudOnExternalInput: Boolean
        get() = input.getBoolean(KEY_AUTO_HIDE_HUD_EXTERNAL, true)
        set(v) = input.edit().putBoolean(KEY_AUTO_HIDE_HUD_EXTERNAL, v).apply()

    var haptics: Boolean
        get() = input.getBoolean(KEY_HAPTICS, true)
        set(v) = input.edit().putBoolean(KEY_HAPTICS, v).apply()

    /**
     * Muat seluruh desktop setiap sesi dibuka / setelah resolusi berubah.
     * Default ON: kalau tidak, tepi bawah desktop (taskbar Windows) sering
     * tidak masuk layar dan kelihatan seperti "tenggelam".
     */
    var autoFit: Boolean
        get() = input.getBoolean(KEY_AUTO_FIT, true)
        set(v) = input.edit().putBoolean(KEY_AUTO_FIT, v).apply()

    /**
     * Sambung ulang otomatis setelah koneksi yang sudah aktif putus sendiri.
     * Jeda naik sampai 30 detik; user dapat menghentikan retry dari layar sesi.
     */
    var autoReconnect: Boolean
        get() = input.getBoolean(KEY_AUTO_RECONNECT, true)
        set(v) = input.edit().putBoolean(KEY_AUTO_RECONNECT, v).apply()

    /** Petunjuk pertama dipakai sekali saja: jelaskan cara memindah tombol. */
    var hudTipShown: Boolean
        get() = input.getBoolean(KEY_HUD_TIP, false)
        set(v) = input.edit().putBoolean(KEY_HUD_TIP, v).apply()

    /** Pengali kecepatan scroll (1.0 = one notch per 40px drag). */
    var scrollSpeed: Float
        get() = input.getFloat(KEY_SCROLL_SPEED, 1f).coerceIn(0.4f, 2.5f)
        set(v) = input.edit().putFloat(KEY_SCROLL_SPEED, v.coerceIn(0.4f, 2.5f)).apply()

    /** Pengali sensitivitas gerakan kursor trackpad (0.4x .. 2.5x). */
    var pointerSensitivity: Float
        get() = input.getFloat(KEY_POINTER_SENSITIVITY, 1f).coerceIn(0.4f, 2.5f)
        set(v) = input.edit().putFloat(KEY_POINTER_SENSITIVITY, v.coerceIn(0.4f, 2.5f)).apply()

    /** Akselerasi kecepatan pointer (sapuan cepat bergerak lebih jauh). */
    var pointerAcceleration: Boolean
        get() = input.getBoolean(KEY_POINTER_ACCEL, true)
        set(v) = input.edit().putBoolean(KEY_POINTER_ACCEL, v).apply()

    /** Kinetic / inertial scroll saat jari dilepas sesudah menggeser scroll. */
    var inertialScroll: Boolean
        get() = input.getBoolean(KEY_INERTIAL_SCROLL, true)
        set(v) = input.edit().putBoolean(KEY_INERTIAL_SCROLL, v).apply()

    /** Zona scroll tepi kanan layar pada mode Trackpad. */
    var edgeScrollZone: Boolean
        get() = input.getBoolean(KEY_EDGE_SCROLL, true)
        set(v) = input.edit().putBoolean(KEY_EDGE_SCROLL, v).apply()

    /** Pemetaan stik kontroler Bluetooth / USB Gamepad ke pointer & tombol. */
    var gamepadEnabled: Boolean
        get() = input.getBoolean(KEY_GAMEPAD, true)
        set(v) = input.edit().putBoolean(KEY_GAMEPAD, v).apply()

    /**
     * Gamepad virtual di layar (joystick + D-pad + tombol). State yang sama juga
     * bisa dikirim sebagai paket gamepad ke host PC/Game Stream.
     */
    var virtualPadEnabled: Boolean
        get() = input.getBoolean(KEY_VIRTUAL_PAD, false)
        set(v) = input.edit().putBoolean(KEY_VIRTUAL_PAD, v).apply()

    /**
     * Tata letak gamepad virtual per perangkat: posisi tiap klaster dalam
     * pecahan ukuran layar, supaya pad tidak menutupi overlay lain dan tetap
     * pas saat orientasi berubah.
     */
    fun virtualPadLayout(deviceId: String): Map<KzPadCluster, KzPadPos>? =
        KzPadLayout.decode(device.getString("$deviceId.padlayout", null))

    fun setVirtualPadLayout(deviceId: String, layout: Map<KzPadCluster, KzPadPos>) {
        device.edit().putString("$deviceId.padlayout", KzPadLayout.encode(layout)).apply()
    }

    /**
     * Tujuan stik kiri pada gamepad virtual: kursor, tombol WASD, atau tombol
     * panah. Disimpan sebagai ordinal [KzStickMode].
     */

    /** Varian menu sesi: popup tengah (default) atau drawer samping. */
    var menuDrawer: Boolean
        get() = input.getBoolean(KEY_MENU_DRAWER, false)
        set(v) = input.edit().putBoolean(KEY_MENU_DRAWER, v).apply()
    var virtualPadStickMode: KzStickMode
        get() = KzStickMode.entries.getOrElse(input.getInt(KEY_VIRTUAL_PAD_STICK, KzStickMode.WASD.ordinal)) {
            KzStickMode.WASD
        }
        set(v) = input.edit().putInt(KEY_VIRTUAL_PAD_STICK, v.ordinal).apply()

    /** Skala ukuran gamepad virtual (0.7x - 1.4x). */
    var virtualPadScale: Float
        get() = input.getFloat(KEY_VIRTUAL_PAD_SCALE, 1f)
        set(v) = input.edit().putFloat(KEY_VIRTUAL_PAD_SCALE, KzPadLayout.clampScale(v)).apply()

    /** Mode Gyro Air-Mouse (miringkan HP untuk menggerakkan kursor). */
    var gyroMouseEnabled: Boolean
        get() = input.getBoolean(KEY_GYRO_MOUSE, false)
        set(v) = input.edit().putBoolean(KEY_GYRO_MOUSE, v).apply()

    /** Pill telemetri live (FPS tampilan, RTT ms, codec, dan UDP) di layar sesi. */
    var showTelemetryPill: Boolean
        get() = input.getBoolean(KEY_TELEMETRY_PILL, true)
        set(v) = input.edit().putBoolean(KEY_TELEMETRY_PILL, v).apply()

    /** Tukar fungsi klik kiri dan klik kanan mouse (Mouse Left/Right Switch). */
    var swapMouseButtons: Boolean
        get() = input.getBoolean(KEY_SWAP_MOUSE, false)
        set(v) = input.edit().putBoolean(KEY_SWAP_MOUSE, v).apply()

    var showLeft: Boolean
        get() = input.getBoolean(KEY_BTN_LEFT, true)
        set(v) = input.edit().putBoolean(KEY_BTN_LEFT, v).apply()

    var showRight: Boolean
        get() = input.getBoolean(KEY_BTN_RIGHT, true)
        set(v) = input.edit().putBoolean(KEY_BTN_RIGHT, v).apply()

    var showMiddle: Boolean
        get() = input.getBoolean(KEY_BTN_MIDDLE, false)
        set(v) = input.edit().putBoolean(KEY_BTN_MIDDLE, v).apply()

    var showScroll: Boolean
        get() = input.getBoolean(KEY_BTN_SCROLL, true)
        set(v) = input.edit().putBoolean(KEY_BTN_SCROLL, v).apply()

    var showSwitch: Boolean
        get() = input.getBoolean(KEY_BTN_SWITCH, true)
        set(v) = input.edit().putBoolean(KEY_BTN_SWITCH, v).apply()

    /** Default diameter for newly created/reset HUD buttons; compact 46dp default. */
    var hudButtonSize: Float
        get() = input.getFloat(KEY_HUD_SIZE, 46f).coerceIn(34f, 64f)
        set(v) = input.edit().putFloat(KEY_HUD_SIZE, v.coerceIn(34f, 64f)).apply()

    // ---- per perangkat ----

    fun zoom(id: String): Float = device.getFloat("$id.zoom", 1f)

    fun hasZoom(id: String): Boolean = device.contains("$id.zoom")

    fun setZoom(id: String, zoom: Float) = device.edit().putFloat("$id.zoom", zoom).apply()

    fun keyboardShown(id: String): Boolean = device.getBoolean("$id.keyboard", false)

    fun setKeyboardShown(id: String, shown: Boolean) {
        device.edit().putBoolean("$id.keyboard", shown).apply()
    }

    /**
     * Tombol HUD sesi (bulat): satu tombol = satu aksi.
     * Disimpan per perangkat karena posisi layer bergantung ukuran layar.
     */
    fun hudKeys(deviceId: String): List<HudKey> {
        HudKey.decode(device.getString("$deviceId.hudkeys", null))?.let { return it }
        // Belum pernah diubah: susun set bawaan, tapi hormati preferensi lama
        // (toggle cluster ronde 2) supaya tombol yang sengaja dimatikan user
        // tidak muncul lagi begitu model tombol baru dipakai.
        var keys = HudKey.defaults(hudButtonSize)
        if (!showLeft) keys = keys.filterNot { it.id == "kiri" }
        if (!showRight) keys = keys.filterNot { it.id == "kanan" }
        if (!showMiddle) keys = keys.filterNot { it.id == "tengah" }
        if (!showScroll) keys = keys.filterNot { it.id == "naik" || it.id == "turun" }
        if (!showSwitch) keys = keys.filterNot { it.id == "switch" }
        return keys
    }

    fun setHudKeys(deviceId: String, keys: List<HudKey>) {
        device.edit().putString("$deviceId.hudkeys", HudKey.encode(keys)).apply()
    }

    fun customHudKeys(deviceId: String): List<HudKey>? =
        HudKey.decode(device.getString("$deviceId.hudkeys.custom", null))

    fun setCustomHudKeys(deviceId: String, keys: List<HudKey>) {
        device.edit().putString("$deviceId.hudkeys.custom", HudKey.encode(keys)).apply()
    }

    fun hudButtonsVisible(deviceId: String): Boolean =
        device.getBoolean("$deviceId.hud-visible", true)

    fun setHudButtonsVisible(deviceId: String, visible: Boolean) {
        device.edit().putBoolean("$deviceId.hud-visible", visible).apply()
    }

    fun hudProfile(deviceId: String): HudProfilePreset =
        HudProfilePreset.entries.getOrElse(device.getInt("$deviceId.hudprofile", 0)) {
            HudProfilePreset.STANDARD
        }

    fun setHudProfile(deviceId: String, preset: HudProfilePreset) {
        device.edit().putInt("$deviceId.hudprofile", preset.ordinal).apply()
    }

    companion object {
        private const val INPUT_FILE = "kzdesk.input"
        private const val DEVICE_FILE = "kzdesk.session"
        private const val KEY_INPUT_MODE = "input_mode"
        private const val KEY_POINTER_STYLE = "pointer_style"
        private const val KEY_POINTER_SIZE = "pointer_size"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_HUD_TIP = "hud_tip"
        private const val KEY_HUD_PLATE = "hud_plate"
        private const val KEY_HUD_OPACITY = "hud_opacity"
        private const val KEY_AUTO_HIDE_HUD_EXTERNAL = "auto_hide_hud_external"
        private const val KEY_AUTO_FIT = "auto_fit"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_POINTER_SENSITIVITY = "pointer_sensitivity"
        private const val KEY_POINTER_ACCEL = "pointer_accel"
        private const val KEY_INERTIAL_SCROLL = "inertial_scroll"
        private const val KEY_EDGE_SCROLL = "edge_scroll"
        private const val KEY_GAMEPAD = "gamepad_enabled"
        private const val KEY_VIRTUAL_PAD = "virtual_pad_enabled"
        private const val KEY_VIRTUAL_PAD_SCALE = "virtual_pad_scale"
        private const val KEY_VIRTUAL_PAD_STICK = "virtual_pad_stick_mode_v2"
        private const val KEY_MENU_DRAWER = "menu_drawer" // v2: default pindah ke WASD (pemilik: stik mouse membingungkan)
        private const val KEY_GYRO_MOUSE = "gyro_mouse"
        private const val KEY_TELEMETRY_PILL = "telemetry_pill"
        private const val KEY_SWAP_MOUSE = "swap_mouse_buttons"
        private const val KEY_BTN_LEFT = "btn_left"
        private const val KEY_BTN_RIGHT = "btn_right"
        private const val KEY_BTN_MIDDLE = "btn_middle"
        private const val KEY_BTN_SCROLL = "btn_scroll"
        private const val KEY_BTN_SWITCH = "btn_switch"
        private const val KEY_HUD_SIZE = "hud_size_v2"
    }
}

/**
 * Preferensi tampilan per perangkat (file `kzdesk.remote.display`) —
 * dibaca juga oleh [com.kazamatech.kzdesk.core.SessionManager] saat menyusun
 * argumen `/size:` untuk sesi.
 */
object DisplayPrefs {

    /** Automatic uses the conservative standard 16:9 desktop size, 1280x720. */
    const val AUTOMATIC = SmartResolution.AUTOMATIC_PRESET

    /** Follow the phone's aspect ratio; intended for fullscreen media, not desktop work. */
    const val FOLLOW = SmartResolution.FOLLOW_PRESET

    /** Legacy value; treated as [AUTOMATIC] when read. */
    const val SMART_16_9 = SmartResolution.LEGACY_AUTOMATIC_PRESET

    /**
     * UI choices: automatic standard 720p, phone viewport, then explicit
     * 16:9 sizes so the selected desktop dimensions are never ambiguous.
     */
    val resolutionGroups: List<ResolutionGroup> = listOf(
        ResolutionGroup(
            "Pintar", "Smart",
            listOf(
                ResolutionOption(
                    AUTOMATIC,
                    "Otomatis — Standar 720p (1280×720)",
                    "Automatic — standard 720p (1280×720)",
                ),
                ResolutionOption(
                    FOLLOW,
                    "Ikuti layar HP (rasio HP)",
                    "Follow phone screen (phone ratio)",
                ),
            ),
        ),
        ResolutionGroup(
            "16:9 standar", "16:9 standard",
            listOf(
                ResolutionOption("1280x720", "HD 720 · 1280×720", "HD 720 · 1280×720"),
                ResolutionOption("1600x900", "900p · 1600×900", "900p · 1600×900"),
                ResolutionOption("1920x1080", "FHD · 1920×1080", "FHD · 1920×1080"),
                ResolutionOption("2560x1440", "QHD · 2560×1440", "QHD · 2560×1440"),
                ResolutionOption("3840x2160", "4K · 3840×2160", "4K · 3840×2160"),
            ),
        ),
    )

    /** Daftar datar untuk UI pemilihan (layar perangkat & sesi). */
    val resolutionOptions: List<ResolutionOption> = resolutionGroups.flatMap { it.items }

    /** Nilai orientasi yang disimpan. Label UI dilokalkan saat render. */
    val rotations = listOf("Auto", "Portrait", "Landscape")

    /** Judul grup/opsi resolusi (dua bahasa). */
    data class ResolutionGroup(
        val id: String,
        val en: String,
        val items: List<ResolutionOption>,
    )

    data class ResolutionOption(val value: String, val id: String, val en: String)

    /** Validasi "WxH" manual (batas sama dengan SessionManager). */
    fun parseCustom(value: String): String? {
        val parts = value.trim().lowercase().split('x')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        if (w !in 640..8192 || w % 2 != 0 || h !in 480..8192) return null
        return "${w}x$h"
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun resolution(context: Context, id: String): String =
        sp(context).getString("$id.resolution", AUTOMATIC)?.let {
            if (it == SMART_16_9) AUTOMATIC else it
        } ?: AUTOMATIC

    fun setResolution(context: Context, id: String, value: String) =
        sp(context).edit().putString("$id.resolution", value).apply()

    fun rotation(context: Context, id: String): String =
        sp(context).getString("$id.rotation", "Auto") ?: "Auto"

    fun setRotation(context: Context, id: String, value: String) =
        sp(context).edit().putString("$id.rotation", value).apply()

    /** Skala tampilan awal saat sesi dibuka, 80..200 (%). */
    fun dpi(context: Context, id: String): Int =
        sp(context).getInt("$id.dpi", 100).coerceIn(80, 200)

    fun setDpi(context: Context, id: String, value: Int) =
        sp(context).edit().putInt("$id.dpi", value.coerceIn(80, 200)).apply()

    /** Windows DesktopScaleFactor sent over RDP Display Control (not local zoom). */
    val remoteDpiOptions = listOf(100, 125, 150, 175, 200, 250, 300, 400, 500)

    /**
     * Skala Windows yang diminta ke host. Default 100% (tanpa penskalaan):
     * skala bukan 100% membuat aplikasi lama yang tidak DPI-aware direntangkan
     * bitmap oleh Windows sehingga teksnya bergerigi/pecah. Karena itu
     * penskalaan tidak lagi diminta secara default — user yang memang ingin
     * UI Windows lebih besar tetap bisa memilihnya di panel sesi.
     */
    fun remoteDpi(context: Context, id: String): Int =
        sp(context).getInt("$id.remote_dpi", 100).let { if (it in remoteDpiOptions) it else 100 }

    fun setRemoteDpi(context: Context, id: String, value: Int): Boolean {
        if (value !in remoteDpiOptions) return false
        sp(context).edit().putInt("$id.remote_dpi", value).apply()
        return true
    }

    fun clear(context: Context, id: String) {
        sp(context).edit()
            .remove("$id.resolution")
            .remove("$id.rotation")
            .remove("$id.dpi")
            .remove("$id.remote_dpi")
            .apply()
    }

    private const val FILE = "kzdesk.remote.display"
}
