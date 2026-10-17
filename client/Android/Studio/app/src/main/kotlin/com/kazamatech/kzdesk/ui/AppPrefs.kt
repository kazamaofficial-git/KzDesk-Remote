package com.kazamatech.kzdesk.ui

import android.content.Context

/**
 * Preferensi global app (SP "kzdesk.app"):
 *  - mode tema (0 = ikut sistem, 1 = gelap, 2 = terang)
 *  - auto-disconnect saat background (dipakai kalau keep-alive dimatikan)
 *  - keep-alive: sesi jalan terus di latar lewat foreground service
 */
class AppPrefs(context: Context) {
    private val sp =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var themeMode: Int
        get() = sp.getInt(KEY_THEME, 1)
        set(v) = sp.edit().putInt(KEY_THEME, v).apply()

    var autoDisconnect: Boolean
        get() = sp.getBoolean(KEY_BG_DISCONNECT, true)
        set(v) = sp.edit().putBoolean(KEY_BG_DISCONNECT, v).apply()

    /** Picture-in-Picture otomatis saat sesi aktif dan pengguna menekan Home. */
    var pip: Boolean
        get() = sp.getBoolean("pip", true)
        set(v) = sp.edit().putBoolean("pip", v).apply()

    /** Panduan pemula sudah pernah ditampilkan. */
    var guideSeen: Boolean
        get() = sp.getBoolean("guide_seen", false)
        set(v) = sp.edit().putBoolean("guide_seen", v).apply()

    /**
     * Sesi tetap jalan saat app ditinggal ke latar (foreground service +
     * notifikasi). Default ON: RDP itu pekerjaan yang sedang dilihat user,
     * bukan proses yang boleh diputus Android begitu layar dialihkan.
     */
    /** Varian menu sesi: popup tengah (default) atau drawer samping. */
    var menuDrawer: Boolean
        get() = sp.getBoolean("menu_drawer", false)
        set(v) = sp.edit().putBoolean("menu_drawer", v).apply()

    var keepAlive: Boolean
        get() = sp.getBoolean(KEY_KEEP_ALIVE, true)
        set(v) = sp.edit().putBoolean(KEY_KEEP_ALIVE, v).apply()

    /** Reopen the last connected profile once on app return; manual disconnect clears the marker. */
    var autoResumeLastSession: Boolean
        get() = sp.getBoolean(KEY_AUTO_RESUME_LAST_SESSION, true)
        set(v) {
            val edit = sp.edit().putBoolean(KEY_AUTO_RESUME_LAST_SESSION, v)
            if (!v) edit.remove(KEY_AUTO_RESUME_PROFILE_ID).remove(KEY_AUTO_RESUME_SAVED_AT)
            edit.apply()
        }

    /** Re-hide system bars after the phone keyboard opens during a session (opt-in). */
    var keepSystemBarsHiddenWhenKeyboardOpens: Boolean
        get() = sp.getBoolean(KEY_KEEP_SYSTEM_BARS_HIDDEN_ON_IME, false)
        set(v) = sp.edit().putBoolean(KEY_KEEP_SYSTEM_BARS_HIDDEN_ON_IME, v).apply()

    val autoResumeProfileId: String?
        get() = sp.getString(KEY_AUTO_RESUME_PROFILE_ID, null)

    val autoResumeSavedAtMillis: Long
        get() = sp.getLong(KEY_AUTO_RESUME_SAVED_AT, 0L)

    /** Stores only the profile identifier and timestamp; no password or credential material. */
    fun rememberAutoResumeSession(profileId: String) {
        if (!autoResumeLastSession || profileId.isBlank()) return
        sp.edit()
            .putString(KEY_AUTO_RESUME_PROFILE_ID, profileId)
            .putLong(KEY_AUTO_RESUME_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** A deliberate disconnect/cancel must not silently reconnect next time. */
    fun clearAutoResumeSession(profileId: String? = null) {
        val savedProfileId = autoResumeProfileId ?: return
        if (profileId != null && savedProfileId != profileId) return
        sp.edit().remove(KEY_AUTO_RESUME_PROFILE_ID).remove(KEY_AUTO_RESUME_SAVED_AT).apply()
    }

    // ---- default fitur untuk perangkat BARU (diatur di General) ----
    // Perangkat yang sudah tersimpan tidak ikut berubah: nilainya tetap
    // diatur per perangkat di layar Ubah perangkat.

    var defaultUdp: Boolean
        get() = sp.getBoolean("default_udp", true)
        set(v) = sp.edit().putBoolean("default_udp", v).apply()

    var defaultNetAuto: Boolean
        get() = sp.getBoolean("default_netauto", true)
        set(v) = sp.edit().putBoolean("default_netauto", v).apply()

    var defaultH264: Boolean
        get() = sp.getBoolean("default_h264", true)
        set(v) = sp.edit().putBoolean("default_h264", v).apply()

    var defaultDynamicResolution: Boolean
        get() = sp.getBoolean("default_dynres", true)
        set(v) = sp.edit().putBoolean("default_dynres", v).apply()

    var defaultClipboard: Boolean
        get() = sp.getBoolean("default_clipboard", true)
        set(v) = sp.edit().putBoolean("default_clipboard", v).apply()

    var defaultLocalDrive: Boolean
        get() = sp.getBoolean("default_drive", false)
        set(v) = sp.edit().putBoolean("default_drive", v).apply()

    var defaultStreamProfile: Int
        get() = sp.getInt("default_stream_profile", 0).coerceIn(0, 5)
        set(v) = sp.edit().putInt("default_stream_profile", v.coerceIn(0, 5)).apply()

    var defaultColorDepth: Int
        get() = sp.getInt("default_bpp", 32).let { if (it in setOf(16, 24, 32)) it else 32 }
        set(v) = sp.edit().putInt("default_bpp", if (v in setOf(16, 24, 32)) v else 32).apply()

    var defaultAsyncUpdate: Boolean
        get() = sp.getBoolean("default_async_upd", false)
        set(v) = sp.edit().putBoolean("default_async_upd", v).apply()

    var defaultAsyncChannels: Boolean
        get() = sp.getBoolean("default_async_ch", false)
        set(v) = sp.edit().putBoolean("default_async_ch", v).apply()

    var defaultSecurityProtocol: Int
        get() = sp.getInt("default_sec_proto", 0).coerceIn(0, 3)
        set(v) = sp.edit().putInt("default_sec_proto", v.coerceIn(0, 3)).apply()

    var defaultTlsSecLevel: Int
        get() = sp.getInt("default_tls_sec", -1).coerceIn(-1, 2)
        set(v) = sp.edit().putInt("default_tls_sec", v.coerceIn(-1, 2)).apply()

    // ---- Keamanan & Privasi App ----

    /** Blokir tangkapan layar / screen recording / preview Recents Android (`FLAG_SECURE`). */
    var flagSecure: Boolean
        get() = sp.getBoolean(KEY_FLAG_SECURE, false)
        set(v) = sp.edit().putBoolean(KEY_FLAG_SECURE, v).apply()

    var screenCaptureProtection: Boolean
        get() = flagSecure
        set(v) { flagSecure = v }

    /** Wajibkan PIN / Pola / Biometrik HP sebelum membuka sesi tersimpan. */
    var requireDeviceLock: Boolean
        get() = sp.getBoolean(KEY_REQUIRE_LOCK, false)
        set(v) = sp.edit().putBoolean(KEY_REQUIRE_LOCK, v).apply()

    /** Kirim `Win+L` otomatis untuk mengunci PC saat sesi diputus dari HP. */
    var autoLockRemoteOnLeave: Boolean
        get() = sp.getBoolean(KEY_AUTO_LOCK_REMOTE, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_LOCK_REMOTE, v).apply()

    /** Bersihkan clipboard HP otomatis saat sesi RDP ditutup. */
    var clearClipboardOnDisconnect: Boolean
        get() = sp.getBoolean(KEY_CLEAR_CLIPBOARD, false)
        set(v) = sp.edit().putBoolean(KEY_CLEAR_CLIPBOARD, v).apply()

    /** Anonymous active-session counting; enabled by default and easy to disable. */
    var shareActiveSessionStats: Boolean
        get() = sp.getBoolean(KEY_SHARE_ACTIVE_SESSION_STATS, true)
        set(v) = sp.edit().putBoolean(KEY_SHARE_ACTIVE_SESSION_STATS, v).apply()

    companion object {
        private const val NAME = "kzdesk.app"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_BG_DISCONNECT = "bg_disconnect"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_AUTO_RESUME_LAST_SESSION = "auto_resume_last_session"
        private const val KEY_AUTO_RESUME_PROFILE_ID = "auto_resume_profile_id"
        private const val KEY_AUTO_RESUME_SAVED_AT = "auto_resume_saved_at"
        private const val KEY_KEEP_SYSTEM_BARS_HIDDEN_ON_IME = "keep_system_bars_hidden_on_ime"
        const val AUTO_RESUME_WINDOW_MS = 15 * 60_000L
        private const val KEY_FLAG_SECURE = "flag_secure"
        private const val KEY_REQUIRE_LOCK = "require_device_lock"
        private const val KEY_AUTO_LOCK_REMOTE = "auto_lock_remote"
        private const val KEY_CLEAR_CLIPBOARD = "clear_clipboard_disconnect"
        private const val KEY_SHARE_ACTIVE_SESSION_STATS = "share_active_session_stats"
    }
}
