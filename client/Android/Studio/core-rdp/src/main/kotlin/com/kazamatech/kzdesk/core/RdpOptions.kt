package com.kazamatech.kzdesk.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Opsi sesi per-perangkat. Semua nilai di sini dipetakan ke argumen FreeRDP
 * lewat query URI (lihat [RdpUri]) — jadi tiap toggle di UI benar-benar
 * mengubah perilaku native, bukan dekorasi.
 */
enum class KzAudioMode(
    val wire: Int,
    val title: String,
    val detail: String,
    val titleEn: String = title,
    val detailEn: String = detail,
) {
    DEVICE(
        0, "Putar di perangkat ini", "Audio dari remote keluar di speaker HP",
        "Play on this device", "Remote audio comes out of the phone speaker",
    ),
    REMOTE(
        1, "Putar di komputer remote", "Audio tetap di server (hemat bandwidth)",
        "Play on the remote computer", "Audio stays on the server (saves bandwidth)",
    ),
    OFF(
        2, "Matikan audio", "Tidak ada kanal audio",
        "Mute audio", "No audio channel at all",
    ),
}

/**
 * Profil preset streaming & latensi. Memilih preset akan mengisi flag
 * pipeline grafis, kompresi, dan dekorasi desktop Windows secara otomatis.
 */
enum class KzStreamProfile(
    val title: String,
    val detail: String,
    val titleEn: String,
    val detailEn: String,
) {
    AUTO(
        "Otomatis (Adaptif)",
        "Ikuti deteksi jaringan aktif (Wi-Fi penuh / seluler hemat)",
        "Automatic (Adaptive)",
        "Follow active network detection (full Wi-Fi / cellular saver)",
    ),
    ULTRA_LOW_LATENCY(
        "Responsif (preset RDP)",
        "AVC420, UDP RDP, async update, dan efek desktop minimal; latensi aktual bergantung host dan jaringan.",
        "Responsive (RDP preset)",
        "AVC420, RDP UDP, async updates, and minimal desktop effects; actual latency depends on host and network.",
    ),
    BALANCED(
        "Seimbang",
        "AVC444 + async update + font tajam ClearType tanpa wallpaper berat",
        "Balanced",
        "AVC444 + async update + crisp ClearType fonts without heavy wallpaper",
    ),
    HIGH_VISUAL(
        "Visual Tajam (Desain)",
        "AVC444 32-bit penuh + ClearType + Aero + tema & wallpaper",
        "High Visual (Design)",
        "Full 32-bit AVC444 + ClearType + Aero + themes & wallpaper",
    ),
    DATA_SAVER(
        "Hemat Kuota (Seluler)",
        "AVC420 16-bit + kompresi level 2 + matikan semua animasi desktop",
        "Data Saver (Cellular)",
        "16-bit AVC420 + level-2 compression + disable all desktop animations",
    ),
    CUSTOM(
        "Kustom Manual",
        "Atur sendiri kedalaman warna, async pipeline, kompresi, dan efek desktop",
        "Custom Manual",
        "Manually tune color depth, async pipeline, compression, and desktop effects",
    ),
}

/** Protokol autentikasi & enkripsi transport RDP (`/sec:nla|tls|rdp`). */
enum class KzSecurityProtocol(
    val wire: String?,
    val title: String,
    val detail: String,
    val titleEn: String,
    val detailEn: String,
) {
    AUTO(
        null,
        "Otomatis (Negosiasi)",
        "Pilih NLA/TLS terbaik yang didukung server",
        "Automatic (Negotiate)",
        "Negotiate the strongest NLA/TLS protocol supported by the host",
    ),
    NLA(
        "nla",
        "NLA / CredSSP Wajib",
        "Autentikasi level jaringan sebelum sesi layar dibuka (paling aman)",
        "NLA / CredSSP Required",
        "Authenticate at the network layer before creating a display session",
    ),
    TLS(
        "tls",
        "TLS Standar",
        "Enkripsi TLS tanpa CredSSP (berguna bila NLA dimatikan di host)",
        "Standard TLS",
        "TLS encryption without CredSSP (useful when host NLA is disabled)",
    ),
    RDP(
        "rdp",
        "RDP Klasik (Legacy)",
        "Kompatibilitas untuk mesin Windows lama / VM internal",
        "Classic RDP (Legacy)",
        "Compatibility mode for legacy Windows machines or internal VMs",
    ),
}

/** Profil penyesuaian encoder GPU pada PC Host (NVIDIA NVENC, AMD AMF, Intel QSV, atau CPU). */
enum class KzGpuProfile(
    val title: String,
    val detail: String,
    val titleEn: String,
    val detailEn: String,
) {
    AUTO(
        "Otomatis (Deteksi Host)",
        "Ikuti konfigurasi adapter grafis bawaan Windows",
        "Automatic (Host Default)",
        "Follow Windows host default graphics adapter settings",
    ),
    NVIDIA(
        "NVIDIA GeForce / RTX (NVENC)",
        "AVC444 32-bit + Level-0 Compression + Async Queue untuk latensi NVENC terendah",
        "NVIDIA GeForce / RTX (NVENC)",
        "AVC444 32-bit + Level-0 Compression + Async Queue for lowest NVENC latency",
    ),
    AMD(
        "AMD Radeon RX (AMF)",
        "AVC444 32-bit + Level-1 Framing + Async Update/Channel untuk pipeline AMF",
        "AMD Radeon RX (AMF)",
        "AVC444 32-bit + Level-1 Framing + Async Update/Channel for AMF pipeline",
    ),
    INTEL(
        "Intel Arc / Iris Xe (QuickSync)",
        "AVC420 24-bit + QSV Low-Power + Async Update untuk efisiensi decoder/encoder",
        "Intel Arc / Iris Xe (QuickSync)",
        "AVC420 24-bit + QSV Low-Power + Async Update for QSV efficiency",
    ),
    SOFTWARE(
        "Tanpa GPU Diskrit (CPU / Standard)",
        "AVC420 16-bit + Kompresi Level 2 agar ringan di PC tanpa GPU dedicated",
        "No Discrete GPU (CPU / Standard)",
        "AVC420 16-bit + Level-2 Compression for PCs without a dedicated GPU",
    ),
}

/**
 * Preset profil PC (nama tipe dipertahankan untuk kompatibilitas penyimpanan).
 * Preset ini masih mengatur opsi FreeRDP/RDP; bukan transport stream mandiri.
 */
enum class KzPcStreamEngine(
    val title: String,
    val detail: String,
    val titleEn: String,
    val detailEn: String,
    val badge: String,
) {
    DIRECT_GAME_ULTRA(
        "Profil Game (RDP)",
        "Preset RDP untuk gameplay; stream game mandiri belum tersedia",
        "Game Profile (RDP)",
        "RDP tuning preset; standalone game streaming is not available yet",
        "GAME 60/120",
    ),
    DIRECT_STUDIO_444(
        "Profil Kreator (RDP)",
        "Preset visual RDP; jalur 4:4:4 mandiri belum tersedia",
        "Creator Profile (RDP)",
        "RDP visual preset; a standalone 4:4:4 path is not available yet",
        "4:4:4 STUDIO",
    ),
    DIRECT_CINEMA(
        "Profil Multimedia (RDP)",
        "Preset multimedia RDP; audio/video memakai jalur RDP yang aktif",
        "Multimedia Profile (RDP)",
        "RDP multimedia preset; uses the active RDP audio/video path",
        "CINEMA",
    ),
    DIRECT_LAN_TURBO(
        "Profil LAN (RDP)",
        "Preset RDP untuk LAN; tidak mengubah transport menjadi stream mandiri",
        "LAN Profile (RDP)",
        "RDP preset for LAN; does not switch to standalone streaming",
        "LAN TURBO",
    ),
}

/** RDP Gateway (RD Gateway). Port default 443. */
data class KzGateway(
    val host: String,
    val port: Int = 443,
    val username: String? = null,
    val password: String? = null,
    val domain: String? = null,
) {
    val id: String get() = "$host:$port"
}

data class RdpOptions(
    val audioMode: KzAudioMode = KzAudioMode.DEVICE,
    val microphone: Boolean = false,
    /**
     * Kirim suara PC langsung ke HP lewat UDP/QUIC :4433 (WASAPI Loopback
     * di Host Agent) sehingga audio tetap terdengar walau `Remote Audio`
     * RDP dimatikan demi VB-CABLE / KzDesk Virtual Microphone.
     */
    val quicAudio: Boolean = true,
    /** DSP mic HP: gain pre-amp dalam dB (-12..+24). */
    val micGainDb: Int = 6,
    /** DSP mic HP: noise gate dBFS (-70..0); makin tinggi makin agresif. */
    val micGateDb: Int = -42,
    /** DSP mic HP: noise suppression (high-pass + expander). */
    val micNoiseSuppression: Boolean = true,
    /** DSP mic HP: automatic gain control. */
    val micAgc: Boolean = true,
    val clipboard: Boolean = true,
    val localDrive: Boolean = false,
    val camera: Boolean = false,
    /** RDP-UDP (multitransport, FEC). Lebih halus di jaringan mobile. */
    val udpTransport: Boolean = true,
    val networkAutoDetect: Boolean = true,
    /** Low-bandwidth profile and AVC420 hint; off preserves current defaults. */
    val lowBandwidth: Boolean = false,
    /** RemoteFX/H.264 (GFX AVC444) untuk desktop yang berubah cepat. */
    val h264: Boolean = true,

    /**
     * `/dynamic-resolution` — nyalakan kanal DISP supaya ukuran desktop
     * remote bisa diubah saat sesi sudah jalan (tanpa reconnect).
     * Default nyala; tanpa ini server mengunci resolusi saat connect.
     */
    val dynamicResolution: Boolean = true,
    val gateway: KzGateway? = null,

    // ---- Streaming & Latency Tuning ----
    val pcConnectMode: Boolean = false,

    /** ID Device (9 digit) bila perangkat ini memakai mode ID + password; kosong = RDP langsung via IP. */
    val directoryId: String = "",
    val pcStreamEngine: KzPcStreamEngine = KzPcStreamEngine.DIRECT_GAME_ULTRA,
    /** Preferred handset display refresh for PC Connect; it does not force the host stream FPS. */
    val pcTargetFps: Int = 60,
    val pcBitrateMbps: Int = 25,
    val rawInputMouse: Boolean = true,
    val streamProfile: KzStreamProfile = KzStreamProfile.AUTO,
    val gpuProfile: KzGpuProfile = KzGpuProfile.AUTO,
    /** Mode hemat baterai HP: gabungkan frame burst (VSYNC coalescing) & kurangi beban dekoder. */
    val batterySaver: Boolean = false,
    /** Kedalaman warna desktop remote: 16, 24, atau 32 bit per piksel (`/bpp:`). */
    val colorDepth: Int = 32,
    /** Target kecepatan bingkai sesi (30 atau 60 FPS). */
    val targetFps: Int = 60,
    /** `+async-update`: pisahkan antrean render GDI dari thread jaringan. */
    val asyncUpdate: Boolean = false,
    /** `+async-channels`: proses kanal virtual (clipboard/audio/drive) secara asinkron. */
    val asyncChannels: Boolean = false,
    /** Level kompresi paket RDP: 0 = mati (`-compression`), 1 = standar, 2 = maksimum (`/compression-level:2`). */
    val compressionLevel: Int = 1,
    /** `+fonts`: ClearType font smoothing agar teks kode/dokumen tajam. */
    val fontSmoothing: Boolean = true,
    /** `+wallpaper`: tampilkan wallpaper desktop remote. */
    val desktopWallpaper: Boolean = false,
    /** `+window-drag`: gambar isi jendela saat digeser. */
    val windowDrag: Boolean = false,
    /** `+menu-anims`: animasi buka/tutup menu Windows. */
    val menuAnimations: Boolean = false,
    /** `+themes` / `-themes`: tema visual Windows. */
    val visualThemes: Boolean = true,
    /** `+aero`: komposisi desktop DWM/Aero. */
    val desktopComposition: Boolean = true,

    // ---- Security & Session Hardening ----
    val securityProtocol: KzSecurityProtocol = KzSecurityProtocol.AUTO,
    /** Level keamanan cipher OpenSSL TLS (`-1` = bawaan, `0` = kompatibel server lama, `1` = standar, `2` = ketat). */
    val tlsSecLevel: Int = -1,
    /** `/admin`: sambung ke sesi konsol/admin fisik. */
    val consoleAdmin: Boolean = false,
    /** `/restricted-admin`: jangan kirim kredensial plaintext ke host target (anti credential theft). */
    val restrictedAdmin: Boolean = false,
    /** `/shell:...`: jalankan program tertentu langsung saat sesi mulai. */
    val remoteProgram: String? = null,
    /** `/shell-dir:...`: direktori kerja awal untuk program sesi. */
    val remoteWorkDir: String? = null,

    // ---- Wake-on-LAN (WoL) & SSH Tunnel / Jump-Host ----
    val macAddress: String? = null,
    val wolBroadcast: String = "255.255.255.255",
    val wolPort: Int = 9,
    val sshHost: String? = null,
    val sshPort: Int = 22,
    val sshUser: String? = null,
    val sshLocalPort: Int = 0,
) {
    /**
     * Terapkan preset streaming & latensi ke opsi ini dan kembalikan salinan baru.
     */
    fun withStreamProfile(profile: KzStreamProfile): RdpOptions = when (profile) {
        KzStreamProfile.AUTO -> copy(
            streamProfile = KzStreamProfile.AUTO,
            networkAutoDetect = true,
            lowBandwidth = false,
            h264 = true,
            colorDepth = 32,
            targetFps = 60,
            // Dulu false di preset default. Keduanya memisahkan kerja render GDI
            // dan kanal virtual dari thread jaringan, jadi frame tidak menunggu
            // antrean jaringan selesai -- murni soal latensi, tanpa mengubah
            // kualitas gambar. AUTO adalah preset bawaan, jadi menonaktifkannya
            // berarti mayoritas sesi jalan dengan latensi yang tidak perlu.
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 1,
            fontSmoothing = true,
            desktopWallpaper = false,
            windowDrag = false,
            menuAnimations = false,
            visualThemes = true,
            desktopComposition = true,
        )
        KzStreamProfile.ULTRA_LOW_LATENCY -> copy(
            streamProfile = KzStreamProfile.ULTRA_LOW_LATENCY,
            udpTransport = true,
            networkAutoDetect = false,
            lowBandwidth = true,
            h264 = true,
            colorDepth = 24,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 1,
            fontSmoothing = true,
            desktopWallpaper = false,
            windowDrag = false,
            menuAnimations = false,
            visualThemes = false,
            desktopComposition = false,
        )
        KzStreamProfile.BALANCED -> copy(
            streamProfile = KzStreamProfile.BALANCED,
            udpTransport = true,
            networkAutoDetect = true,
            lowBandwidth = false,
            h264 = true,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = false,
            compressionLevel = 1,
            fontSmoothing = true,
            desktopWallpaper = false,
            windowDrag = false,
            menuAnimations = false,
            visualThemes = true,
            desktopComposition = false,
        )
        KzStreamProfile.HIGH_VISUAL -> copy(
            streamProfile = KzStreamProfile.HIGH_VISUAL,
            udpTransport = true,
            networkAutoDetect = false,
            lowBandwidth = false,
            h264 = true,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = false,
            compressionLevel = 1,
            fontSmoothing = true,
            desktopWallpaper = true,
            windowDrag = true,
            menuAnimations = true,
            visualThemes = true,
            desktopComposition = true,
        )
        KzStreamProfile.DATA_SAVER -> copy(
            streamProfile = KzStreamProfile.DATA_SAVER,
            udpTransport = true,
            networkAutoDetect = true,
            lowBandwidth = true,
            h264 = true,
            colorDepth = 16,
            targetFps = 30,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 2,
            fontSmoothing = true,
            desktopWallpaper = false,
            windowDrag = false,
            menuAnimations = false,
            visualThemes = false,
            desktopComposition = false,
        )
        KzStreamProfile.CUSTOM -> copy(streamProfile = KzStreamProfile.CUSTOM)
    }

    /**
     * Terapkan optimasi spesifik vendor GPU Host (NVIDIA NVENC, AMD AMF, Intel QuickSync, atau CPU).
     */
    fun withGpuProfile(gpu: KzGpuProfile): RdpOptions = when (gpu) {
        KzGpuProfile.AUTO -> copy(gpuProfile = KzGpuProfile.AUTO)
        KzGpuProfile.NVIDIA -> copy(
            gpuProfile = KzGpuProfile.NVIDIA,
            udpTransport = true,
            h264 = true,
            lowBandwidth = false,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 0,
        )
        KzGpuProfile.AMD -> copy(
            gpuProfile = KzGpuProfile.AMD,
            udpTransport = true,
            h264 = true,
            lowBandwidth = false,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 1,
        )
        KzGpuProfile.INTEL -> copy(
            gpuProfile = KzGpuProfile.INTEL,
            udpTransport = true,
            h264 = true,
            lowBandwidth = true,
            colorDepth = 24,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 1,
        )
        KzGpuProfile.SOFTWARE -> copy(
            gpuProfile = KzGpuProfile.SOFTWARE,
            udpTransport = true,
            h264 = true,
            lowBandwidth = true,
            colorDepth = 16,
            targetFps = 30,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 2,
        )
    }

    /**
     * Terapkan preset opsi PC. Saat ini nilainya tetap dipakai oleh jalur FreeRDP/RDP;
     * fungsi ini tidak membuat sesi streaming PC mandiri.
     */
    fun withPcStreamEngine(engine: KzPcStreamEngine): RdpOptions = when (engine) {
        KzPcStreamEngine.DIRECT_GAME_ULTRA -> copy(
            pcConnectMode = true,
            pcStreamEngine = KzPcStreamEngine.DIRECT_GAME_ULTRA,
            pcTargetFps = if (pcTargetFps in setOf(30, 60, 90, 120)) pcTargetFps else 60,
            pcBitrateMbps = pcBitrateMbps.coerceIn(15, 80),
            udpTransport = true,
            networkAutoDetect = true,
            lowBandwidth = false,
            h264 = true,
            dynamicResolution = false,
            consoleAdmin = true,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 0,
            fontSmoothing = true,
            desktopWallpaper = false,
            windowDrag = false,
            menuAnimations = false,
            visualThemes = true,
            desktopComposition = true,
        )
        KzPcStreamEngine.DIRECT_STUDIO_444 -> copy(
            pcConnectMode = true,
            pcStreamEngine = KzPcStreamEngine.DIRECT_STUDIO_444,
            pcBitrateMbps = pcBitrateMbps.coerceIn(25, 80),
            udpTransport = true,
            networkAutoDetect = false,
            lowBandwidth = false,
            h264 = true,
            dynamicResolution = false,
            consoleAdmin = true,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 0,
            fontSmoothing = true,
            desktopWallpaper = true,
            windowDrag = true,
            menuAnimations = false,
            visualThemes = true,
            desktopComposition = true,
        )
        KzPcStreamEngine.DIRECT_CINEMA -> copy(
            pcConnectMode = true,
            pcStreamEngine = KzPcStreamEngine.DIRECT_CINEMA,
            pcBitrateMbps = pcBitrateMbps.coerceIn(15, 60),
            udpTransport = true,
            networkAutoDetect = true,
            lowBandwidth = false,
            h264 = true,
            dynamicResolution = false,
            consoleAdmin = true,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 1,
            fontSmoothing = true,
            desktopWallpaper = true,
            windowDrag = false,
            menuAnimations = false,
            visualThemes = true,
            desktopComposition = true,
        )
        KzPcStreamEngine.DIRECT_LAN_TURBO -> copy(
            pcConnectMode = true,
            pcStreamEngine = KzPcStreamEngine.DIRECT_LAN_TURBO,
            pcBitrateMbps = 65,
            udpTransport = true,
            networkAutoDetect = false,
            lowBandwidth = false,
            h264 = true,
            dynamicResolution = false,
            consoleAdmin = true,
            colorDepth = 32,
            targetFps = 60,
            asyncUpdate = true,
            asyncChannels = true,
            compressionLevel = 0,
            fontSmoothing = true,
            desktopWallpaper = true,
            windowDrag = true,
            menuAnimations = true,
            visualThemes = true,
            desktopComposition = true,
        )
    }

    /**
     * Saat deteksi bandwidth otomatis aktif dan jaringan yang dipakai adalah
     * seluler/metered atau bandwidth downstream sangat rendah (<5 Mbps),
     * otomatis gunakan profil hemat bandwidth (broadband-low + AVC420).
     */
    fun resolveForLink(
        isMetered: Boolean,
        isCellular: Boolean,
        downstreamKbps: Int = Int.MAX_VALUE,
    ): RdpOptions {
        if (lowBandwidth || !networkAutoDetect) return this
        val constrained = isCellular || isMetered || (downstreamKbps in 1..4_999)
        return if (constrained) copy(lowBandwidth = true) else this
    }

    fun resolveForActiveNetwork(context: Context): RdpOptions {
        if (lowBandwidth || !networkAutoDetect) return this
        return runCatching {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching this
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val downstream = caps?.linkDownstreamBandwidthKbps ?: Int.MAX_VALUE
            val isMetered = (cm.isActiveNetworkMetered ||
                (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))) &&
                (isCellular || downstream < 15_000)
            resolveForLink(isMetered = isMetered, isCellular = isCellular, downstreamKbps = downstream)
        }.getOrDefault(this)
    }

    /** Label singkat codec aktif untuk indikator telemetri. */
    fun activeCodecLabel(): String {
        val base = when {
            !h264 -> "RFX"
            lowBandwidth || streamProfile == KzStreamProfile.ULTRA_LOW_LATENCY ||
                streamProfile == KzStreamProfile.DATA_SAVER -> "AVC420"
            else -> "AVC444"
        }
        val gpuTag = when (gpuProfile) {
            KzGpuProfile.NVIDIA -> "NVENC"
            KzGpuProfile.AMD -> "AMF"
            KzGpuProfile.INTEL -> "QSV"
            KzGpuProfile.SOFTWARE -> "CPU"
            KzGpuProfile.AUTO -> null
        }
        return if (gpuTag != null) "$base ($gpuTag)" else base
    }

    fun write(context: Context, deviceId: String) {
        val sp = context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        sp.putBoolean("$deviceId.pc_mode", pcConnectMode)
        sp.putInt("$deviceId.pc_engine", pcStreamEngine.ordinal)
        sp.putInt("$deviceId.pc_fps", pcTargetFps)
        sp.putInt("$deviceId.pc_bitrate", pcBitrateMbps)
        sp.putBoolean("$deviceId.raw_mouse", rawInputMouse)
        sp.putInt("$deviceId.audio", audioMode.ordinal)
        sp.putBoolean("$deviceId.mic", microphone)
        sp.putString("$deviceId.dirid", directoryId)
        sp.putBoolean("$deviceId.quic_audio", quicAudio)
        sp.putInt("$deviceId.mic_gain", micGainDb)
        sp.putInt("$deviceId.mic_gate", micGateDb)
        sp.putBoolean("$deviceId.mic_ns", micNoiseSuppression)
        sp.putBoolean("$deviceId.mic_agc", micAgc)
        sp.putBoolean("$deviceId.clipboard", clipboard)
        sp.putBoolean("$deviceId.drive", localDrive)
        sp.putBoolean("$deviceId.camera", camera)
        sp.putBoolean("$deviceId.udp", udpTransport)
        sp.putBoolean("$deviceId.netauto", networkAutoDetect)
        sp.putBoolean("$deviceId.lowbw", lowBandwidth)
        sp.putBoolean("$deviceId.h264", h264)
        sp.putBoolean("$deviceId.dynres", dynamicResolution)
        sp.putInt("$deviceId.stream_profile", streamProfile.ordinal)
        sp.putInt("$deviceId.gpu_profile", gpuProfile.ordinal)
        sp.putBoolean("$deviceId.bat_saver", batterySaver)
        sp.putInt("$deviceId.bpp", colorDepth)
        sp.putInt("$deviceId.fps", targetFps)
        sp.putBoolean("$deviceId.async_upd", asyncUpdate)
        sp.putBoolean("$deviceId.async_ch", asyncChannels)
        sp.putInt("$deviceId.comp_lvl", compressionLevel)
        sp.putBoolean("$deviceId.fonts", fontSmoothing)
        sp.putBoolean("$deviceId.wallpaper", desktopWallpaper)
        sp.putBoolean("$deviceId.windrag", windowDrag)
        sp.putBoolean("$deviceId.menuanim", menuAnimations)
        sp.putBoolean("$deviceId.themes", visualThemes)
        sp.putBoolean("$deviceId.aero", desktopComposition)
        sp.putInt("$deviceId.clarity_v2", 2)
        sp.putInt("$deviceId.audio_dsp_v1", 1)
        sp.putInt("$deviceId.sec_proto", securityProtocol.ordinal)
        sp.putInt("$deviceId.tls_sec", tlsSecLevel)
        sp.putBoolean("$deviceId.admin", consoleAdmin)
        sp.putBoolean("$deviceId.restricted_admin", restrictedAdmin)
        sp.putString("$deviceId.remote_prog", remoteProgram.orEmpty())
        sp.putString("$deviceId.remote_dir", remoteWorkDir.orEmpty())
        sp.putString("$deviceId.wol_mac", macAddress.orEmpty())
        sp.putString("$deviceId.wol_bcast", wolBroadcast)
        sp.putInt("$deviceId.wol_port", wolPort)
        sp.putString("$deviceId.ssh_host", sshHost.orEmpty())
        sp.putInt("$deviceId.ssh_port", sshPort)
        sp.putString("$deviceId.ssh_user", sshUser.orEmpty())
        sp.putInt("$deviceId.ssh_lport", sshLocalPort)
        val g = gateway
        if (g == null || g.host.isBlank()) {
            sp.remove("$deviceId.ghost").remove("$deviceId.gport")
                .remove("$deviceId.guser").remove("$deviceId.gpass")
                .remove("$deviceId.gdomain")
        } else {
            sp.putString("$deviceId.ghost", g.host)
            sp.putInt("$deviceId.gport", g.port)
            sp.putString("$deviceId.guser", g.username.orEmpty())
            sp.putString("$deviceId.gpass", g.password.orEmpty())
            sp.putString("$deviceId.gdomain", g.domain.orEmpty())
        }
        sp.apply()
    }

    companion object {
        private const val FILE = "kzdesk.rdp.options"

        private val KEYS = listOf(
            "pc_mode", "pc_engine", "pc_fps", "pc_bitrate", "raw_mouse",
            "audio", "mic", "clipboard", "drive", "camera", "udp",
            "netauto", "lowbw", "h264", "dynres", "ghost", "gport", "guser", "gpass", "gdomain",
            "stream_profile", "gpu_profile", "bat_saver", "bpp", "fps", "async_upd", "async_ch", "comp_lvl",
            "fonts", "wallpaper", "windrag", "menuanim", "themes", "aero", "clarity_v2",
            "sec_proto", "tls_sec", "admin", "restricted_admin", "remote_prog", "remote_dir",
            "wol_mac", "wol_bcast", "wol_port", "ssh_host", "ssh_port", "ssh_user", "ssh_lport",
        )

        /** Bersihkan semua opsi milik satu perangkat (dipakai saat device dihapus). */
        fun clear(context: Context, deviceId: String) {
            val editor = context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            KEYS.forEach { editor.remove("$deviceId.$it") }
            editor.apply()
        }

        fun of(context: Context, deviceId: String): RdpOptions {
            val sp = context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val audio = KzAudioMode.entries.getOrElse(sp.getInt("$deviceId.audio", 0)) {
                KzAudioMode.DEVICE
            }
            val streamProfile = KzStreamProfile.entries.getOrElse(
                sp.getInt("$deviceId.stream_profile", 0),
            ) { KzStreamProfile.AUTO }
            val gpuProfile = KzGpuProfile.entries.getOrElse(
                sp.getInt("$deviceId.gpu_profile", 0),
            ) { KzGpuProfile.AUTO }
            val secProto = KzSecurityProtocol.entries.getOrElse(
                sp.getInt("$deviceId.sec_proto", 0),
            ) { KzSecurityProtocol.AUTO }
            val pcEngine = KzPcStreamEngine.entries.getOrElse(
                sp.getInt("$deviceId.pc_engine", 0),
            ) { KzPcStreamEngine.DIRECT_GAME_ULTRA }
            val gatewayHost = sp.getString("$deviceId.ghost", "").orEmpty()
            val gateway = if (gatewayHost.isBlank()) {
                null
            } else {
                KzGateway(
                    host = gatewayHost,
                    port = sp.getInt("$deviceId.gport", 443),
                    username = sp.getString("$deviceId.guser", "")?.ifBlank { null },
                    password = sp.getString("$deviceId.gpass", "")?.ifBlank { null },
                    domain = sp.getString("$deviceId.gdomain", "")?.ifBlank { null },
                )
            }
            val migratedV2 = sp.getInt("$deviceId.clarity_v2", 0) >= 2
            val isDataSaver = streamProfile == KzStreamProfile.DATA_SAVER
            return RdpOptions(
                pcConnectMode = sp.getBoolean("$deviceId.pc_mode", false),
                pcStreamEngine = pcEngine,
                pcTargetFps = sp.getInt("$deviceId.pc_fps", 60).let { if (it in setOf(30, 60, 90, 120)) it else 60 },
                pcBitrateMbps = sp.getInt("$deviceId.pc_bitrate", 25).coerceIn(5, 80),
                rawInputMouse = sp.getBoolean("$deviceId.raw_mouse", true),
                audioMode = audio,
                microphone = sp.getBoolean("$deviceId.mic", false),
                directoryId = sp.getString("$deviceId.dirid", "").orEmpty(),
                quicAudio = sp.getBoolean("$deviceId.quic_audio", true),
                micGainDb = sp.getInt("$deviceId.mic_gain", 6).coerceIn(-12, 24),
                micGateDb = sp.getInt("$deviceId.mic_gate", -42).coerceIn(-70, 0),
                micNoiseSuppression = sp.getBoolean("$deviceId.mic_ns", true),
                micAgc = sp.getBoolean("$deviceId.mic_agc", true),
                clipboard = sp.getBoolean("$deviceId.clipboard", true),
                localDrive = sp.getBoolean("$deviceId.drive", false),
                camera = sp.getBoolean("$deviceId.camera", false),
                udpTransport = sp.getBoolean("$deviceId.udp", true),
                networkAutoDetect = sp.getBoolean("$deviceId.netauto", true),
                lowBandwidth = if (migratedV2 || isDataSaver) sp.getBoolean("$deviceId.lowbw", false) else false,
                h264 = sp.getBoolean("$deviceId.h264", true),
                dynamicResolution = sp.getBoolean("$deviceId.dynres", true),
                gateway = gateway,
                streamProfile = streamProfile,
                gpuProfile = gpuProfile,
                batterySaver = sp.getBoolean("$deviceId.bat_saver", false),
                colorDepth = if (migratedV2 || isDataSaver) {
                    sp.getInt("$deviceId.bpp", 32).let { if (it in setOf(16, 24, 32)) it else 32 }
                } else {
                    32
                },
                targetFps = sp.getInt("$deviceId.fps", 60).let { if (it in setOf(30, 60)) it else 60 },
                asyncUpdate = sp.getBoolean("$deviceId.async_upd", false),
                asyncChannels = sp.getBoolean("$deviceId.async_ch", false),
                compressionLevel = sp.getInt("$deviceId.comp_lvl", 1).coerceIn(0, 2),
                fontSmoothing = if (migratedV2) sp.getBoolean("$deviceId.fonts", true) else true,
                desktopWallpaper = sp.getBoolean("$deviceId.wallpaper", false),
                windowDrag = sp.getBoolean("$deviceId.windrag", false),
                menuAnimations = sp.getBoolean("$deviceId.menuanim", false),
                visualThemes = sp.getBoolean("$deviceId.themes", true),
                desktopComposition = if (migratedV2) sp.getBoolean("$deviceId.aero", true) else true,
                securityProtocol = secProto,
                tlsSecLevel = sp.getInt("$deviceId.tls_sec", -1).coerceIn(-1, 2),
                consoleAdmin = sp.getBoolean("$deviceId.admin", false),
                restrictedAdmin = sp.getBoolean("$deviceId.restricted_admin", false),
                remoteProgram = sp.getString("$deviceId.remote_prog", "")?.ifBlank { null },
                remoteWorkDir = sp.getString("$deviceId.remote_dir", "")?.ifBlank { null },
                macAddress = sp.getString("$deviceId.wol_mac", "")?.ifBlank { null },
                wolBroadcast = sp.getString("$deviceId.wol_bcast", "255.255.255.255")
                    ?.ifBlank { "255.255.255.255" } ?: "255.255.255.255",
                wolPort = sp.getInt("$deviceId.wol_port", 9).coerceIn(1, 65535),
                sshHost = sp.getString("$deviceId.ssh_host", "")?.ifBlank { null },
                sshPort = sp.getInt("$deviceId.ssh_port", 22).coerceIn(1, 65535),
                sshUser = sp.getString("$deviceId.ssh_user", "")?.ifBlank { null },
                sshLocalPort = sp.getInt("$deviceId.ssh_lport", 0).coerceIn(0, 65535),
            )
        }
    }
}
