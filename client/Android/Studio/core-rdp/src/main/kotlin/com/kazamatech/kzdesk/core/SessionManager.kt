package com.kazamatech.kzdesk.core

import android.content.Context
import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.application.SessionState as CoreSession
import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M1 — SessionManager: satu pintu untuk seluruh lifecycle sesi RDP KzDesk.
 *
 * Membungkus inti FreeRDP ([GlobalApp], [LibFreeRDP]) dengan:
 *  - state machine eksplisit ([SessionState] via [state])
 *  - callback prompt (sertifikat, credential) dengan safe-default
 *    (tanpa [Listener] = ditolak — aman default, PLAN §5.2)
 *  - input forwarding (cursor/key/unicode/clipboard) untuk HUD (M2)
 *  - [GraphicsSink]: event grafik/pointer diteruskan ke surface KzDesk (M2)
 *  - [telemetry]: sampel 500ms untuk panel Stats HUD (M2)
 *  - pre-flight TCP sebelum connect (M1.2b): kegagalan cepat dengan
 *    diagnosa "unreachable" (RDP off / Windows Home / firewall)
 *
 * Thread:
 *  - [state] thread-safe (StateFlow) — baca dari mana saja,
 *    subscribe di main (UI/Compose).
 *  - Prompt ([Listener.onCertificatePrompt], [Listener.onCredentialsPrompt])
 *    dipanggil di thread RDP native dan BLOCKING sampai `reply(...)`
 *    dipanggil (semantik sama dengan dialog upstream `SessionDialogs`).
 *    UI memanggil `reply` dari thread mana pun.
 *  - `connect()` block terjadi di worker internal, bukan di caller.
 */
class SessionManager(context: Context) {

    interface Listener {
        fun onStateChanged(state: SessionState) {}

        /**
         * Sertifikat TLS server perlu persetujuan. Blok sampai [reply] dipanggil.
         * Default (tanpa listener / timeout): DENY.
         */
        fun onCertificatePrompt(info: CertificateInfo, reply: (Int) -> Unit) {}

        /**
         * Server minta credential (NLA/CredSSP). Blok sampai [reply] dipanggil.
         * [username]/[domain] berisi prefill dari profil.
         */
        fun onCredentialsPrompt(
            username: String?,
            domain: String?,
            reply: (username: String?, domain: String?, password: String?) -> Unit,
        ) {}

        /** Clipboard teks dari remote (dipanggil di thread RDP — post ke main). */
        fun onRemoteClipboardText(text: String) {}

        /** Clipboard gambar dari remote (dipanggil di thread RDP). */
        fun onRemoteClipboardImage(data: ByteArray) {}

        /**
         * Status jembatan audio/mic UDP:4433 (suara PC ke HP & mic HP ke PC).
         * Dipanggil dari thread bridge; UI harus post ke main.
         */
        fun onAudioBridge(state: String) {}

        /** Level meter bridge 0..100 (tx = mic HP, rx = suara PC) untuk HUD. */
        fun onAudioLevel(tx: Int, rx: Int) {}
    }

    private val appContext = context.applicationContext
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "kzdesk-rdp") }
    /** Serializes native connect-start against release/free of the same instance. */
    private val lifecycleLock = Any()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Tahap koneksi (di-update dari worker + event listener; baca di UI). */
    private val _stage = MutableStateFlow(Stage.IDLE)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    @Volatile private var watchdogToken = 0
    private var buildInfoLogged = false

    @Volatile private var core: CoreSession? = null
    @Volatile private var listener: Listener? = null
    @Volatile private var sink: GraphicsSink? = null
    @Volatile private var released = false
    @Volatile private var lastProfile: ConnectionProfile? = null

    // Telemetry (M2+)
    private val frameCounter = AtomicInteger(0)
    @Volatile private var resolution = intArrayOf(0, 0)
    @Volatile private var lastRttMs: Int = -1
    @Volatile private var activeCodec: String = "H.264 AVC444"
    @Volatile private var activeUdp: Boolean = true
    @Volatile private var activeBpp: Int = 32
    @Volatile private var activeRelayLabel: String = "RDP"
    @Volatile private var activeNetworkLabel: String = "Wi-Fi"
    @Volatile private var activeDynamicResolution: Boolean = true
    @Volatile private var connectedAtMs: Long = 0L
    @Volatile private var userInitiatedDisconnect: Boolean = false
    @Volatile private var autoReconnectAttempts: Int = 0
    @Volatile private var quicAudioBridge: QuicAudioBridge? = null
    @Volatile private var pendingAudioTap: ((ByteArray, Int, Int) -> Unit)? = null

    /**
     * Pasang/lepas sadapan PCM audio PC untuk perekaman.
     *
     * Bridge audio baru dibuat saat koneksi siap, jadi permintaannya disimpan
     * dan dipasang ulang begitu bridge-nya ada.
     */
    fun setAudioPcmTap(tap: ((ByteArray, Int, Int) -> Unit)?) {
        pendingAudioTap = tap
        quicAudioBridge?.tap = tap
    }
    /** Status teks bridge audio/mic untuk diagnostik & HUD. */
    @Volatile var audioBridgeState: String = "idle"
        private set
    @Volatile private var lastDispLayoutW: Int = 0
    @Volatile private var lastDispLayoutH: Int = 0
    @Volatile private var lastDispScale: Int = 100
    @Volatile private var lastDispSentAtMs: Long = 0L
    @Volatile private var lastSentClipboardHash: Int = 0
    @Volatile private var lastSentClipboardAtMs: Long = 0L

    /** Versi FreeRDP native (untuk about/diagnostics). */
    fun freeRdpVersion(): String = LibFreeRDP.getVersion()

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Sink grafik — wajib di-set SEBELUM [connect] agar tidak ada frame yang hilang. */
    fun setGraphicsSink(sink: GraphicsSink?) {
        this.sink = sink
    }

    /** Count a coalesced graphics invalidation scheduled near display refresh. */
    fun recordCoalescedGraphicsInvalidation() {
        frameCounter.incrementAndGet()
    }

    /** Instance native sesi aktif, 0L jika tidak ada. */
    fun instance(): Long = core?.getInstance() ?: 0L

    // ------------------------------------------------------------------
    // Telemetry (M2)
    // ------------------------------------------------------------------

    /**
     * Sampel telemetri tiap [TELEMETRY_INTERVAL_MS] — dikoleksi oleh UI
     * (collectAsState) selama composition hidup. `fps` = invalidasi grafik
     * yang dikoaleskan per detik (window 500ms, diekstrapolasi 2x), bukan FPS host.
     */
    val telemetry: Flow<TelemetrySample> = flow {
        var tick = 0
        while (true) {
            val curState = state.value
            if (curState is SessionState.Connected && tick % 4 == 0) {
                activeNetworkLabel = detectActiveNetworkLabel()
                val p = lastProfile
                if (p != null && tick % 10 == 0) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            // Probe TCP nyata ke host:port layanan (handshake =
                            // 1 RTT). `isReachable` dulu jarang berhasil di Android
                            // tanpa root (ICMP dilarang, fallback port 7 tertutup).
                            val t0 = System.nanoTime()
                            java.net.Socket().use { probe ->
                                probe.connect(java.net.InetSocketAddress(p.host, p.port), 750)
                            }
                            val sample = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                            lastRttMs = RttSmoother.next(lastRttMs, sample)
                        }
                    }
                }
            }
            tick++
            val frames = frameCounter.getAndSet(0)
            val r = resolution
            emit(
                TelemetrySample(
                    state = curState,
                    width = r[0],
                    height = r[1],
                    fps = frames * 2,
                    rttMs = lastRttMs,
                    codecLabel = activeCodec,
                    udpActive = activeUdp,
                    colorDepth = activeBpp,
                    relayLabel = activeRelayLabel,
                    networkLabel = activeNetworkLabel,
                ),
            )
            delay(TELEMETRY_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private fun detectActiveNetworkLabel(): String = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return@runCatching "Unknown"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return@runCatching "Offline"
        val vpn = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        val base = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data Seluler"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Network"
        }
        if (vpn) "$base + VPN" else base
    }.getOrDefault("Wi-Fi")

    private fun sessionUri(profile: ConnectionProfile): android.net.Uri {
        val storedOptions = RdpOptions.of(appContext, profile.id)
        val options = storedOptions.resolveForActiveNetwork(appContext)
        activeCodec = options.activeCodecLabel()
        activeUdp = options.udpTransport
        activeBpp = options.colorDepth
        activeDynamicResolution = options.dynamicResolution && !options.pcConnectMode && !options.consoleAdmin
        connectedAtMs = 0L
        lastDispLayoutW = 0
        lastDispLayoutH = 0
        lastDispScale = 100
        lastDispSentAtMs = 0L
        activeNetworkLabel = detectActiveNetworkLabel()
        val proto = if (options.udpTransport) "UDP" else "TCP"
        activeRelayLabel = when {
            options.gateway != null && options.gateway.host.isNotBlank() -> "RDP via RD Gateway ($proto)"
            profile.host.startsWith("100.") || profile.host.endsWith(".ts.net", ignoreCase = true) -> "RDP via Tailscale ($proto)"
            options.pcConnectMode -> "RDP PC profile ($proto)"
            else -> "RDP Direct ($proto)"
        }
        if (options.lowBandwidth && !storedOptions.lowBandwidth) {
            ConnectionLog.add("CM: jaringan seluler/terbatas terdeteksi -> otomatis aktifkan lowBandwidth (AVC420)")
        }
        ConnectionLog.add(
            "CM: opsi sesi profile=${options.streamProfile.name} audio=${options.audioMode.name} " +
                "mic=${options.microphone} clip=${options.clipboard} drive=${options.localDrive} " +
                "udp=${options.udpTransport} lowbw=${options.lowBandwidth} h264=${options.h264} wallpaper=${options.desktopWallpaper} " +
                "bpp=${options.colorDepth} sec=${options.securityProtocol.name} gateway=${options.gateway?.id ?: "-"}"
        )
        val prefs = appContext.getSharedPreferences("kzdesk.remote.display", Context.MODE_PRIVATE)
        val remoteScale = prefs.getInt("${profile.id}.remote_dpi", 100)
            .let { if (it in REMOTE_DESKTOP_SCALE_FACTORS) it else 100 }
        lastDispScale = if (options.pcConnectMode) 100 else remoteScale
        val key = "${profile.id}.resolution"
        val stored = if (prefs.contains(key)) prefs.getString(key, null) else null
        val metrics = appContext.resources.displayMetrics
        val resolution = SmartResolution.requestedForSession(
            storedPreset = stored,
            isPcConnectMode = options.pcConnectMode,
            viewportW = metrics.widthPixels,
            viewportH = metrics.heightPixels,
        )
        when {
            options.pcConnectMode -> ConnectionLog.add(
                "CM: Koneksi PC mempertahankan resolusi monitor host (tanpa /size)",
            )
            stored == null || stored == SmartResolution.AUTOMATIC_PRESET ||
                stored == SmartResolution.LEGACY_AUTOMATIC_PRESET -> ConnectionLog.add(
                "CM: resolusi otomatis (standar 720p) -> $resolution",
            )
            stored == SmartResolution.FOLLOW_PRESET -> ConnectionLog.add(
                "CM: resolusi mengikuti viewport HP (tanpa /size)",
            )
            stored != null && SmartResolution.parse(stored) == null -> ConnectionLog.add(
                "CM: preset resolusi tidak valid; fallback otomatis 720p -> $resolution",
            )
        }

        val base = RdpUri.build(profile, options)
        val uriBuilder = base.buildUpon()
        if (!options.pcConnectMode && remoteScale in REMOTE_DESKTOP_SCALE_FACTORS && remoteScale != 100) {
            uriBuilder.appendQueryParameter("scale-desktop", remoteScale.toString())
        }
        if (resolution == null) return uriBuilder.build()
        ConnectionLog.add("CM: remote resolution preset=$resolution scale=${remoteScale}%")
        return uriBuilder.appendQueryParameter("size", resolution).build()
    }

    /**
     * Mulai koneksi (idempotent: dipanggil saat sudah Connecting/Connected
     * = diabaikan dengan log).
     *
     * Pre-flight: probe TCP ke [ConnectionProfile.host]:port. Gagal probe
     * = Error("unreachable") tanpa menyentuh native (diagnosa cepat:
     * RDP off / Windows Home / firewall / host salah).
     */
    /** Info build inti (versi + kanal penting) untuk layar diagnostik. */
    fun buildInfo(): String = coreBuildInfo()

    fun connect(profile: ConnectionProfile) {
        val session = synchronized(lifecycleLock) {
            if (released) return
            val cur = _state.value
            if (cur is SessionState.Connected || cur is SessionState.Authenticating ||
                (cur is SessionState.Connecting && core != null)
            ) {
                Log.w(TAG, "connect() diabaikan, state=$cur")
                return
            }
            if (core != null) {
                // Jangan menimpa instance yang belum memberi callback terminal:
                // freeInstance() menunggu worker native dan retry harus menunggu
                // callback itu (atau user menutup sesi, lalu release membersihkan).
                ConnectionLog.add("CM: retry ditunda; instance native lama belum terminal")
                Log.w(TAG, "connect() ditunda; instance native lama masih aktif")
                return
            }
            val uri = sessionUri(profile)
            // Never log the RDP URI: its query may contain the plaintext password.
            ConnectionLog.add("CM: native createSession mulai (${profile.host}:${profile.port})")
            val created = GlobalApp.createSession(uri, appContext)
            val inst = created.getInstance()
            ConnectionLog.add("CM: createSession ok inst=$inst")
            created.setUIEventListener(uiListenerFor(inst))
            GlobalApp.registerSessionListener(inst, coreListenerFor(inst))
            core = created
            lastProfile = profile
            transition(SessionState.Connecting)
            setStage(Stage.PROBE)
            startWatchdog(inst)
            logConnectStart()
            created
        }
        val inst = session.getInstance()
        worker.execute {
            val quicProbe = runCatching {
                LibFreeRDP.quicProbeHost(profile.host, 4433, 450)
            }.getOrDefault("")
            if (quicProbe.contains("\"ok\":true")) {
                ConnectionLog.add("QUIC host-agent probe OK (ancillary path; session video remains RDP): $quicProbe")
            }
            val probeStartNs = System.nanoTime()
            val reachable = tcpReachable(profile.host, profile.port, TCP_PROBE_TIMEOUT_MS)
            if (reachable) {
                lastRttMs = ((System.nanoTime() - probeStartNs) / 1_000_000L).toInt().coerceAtLeast(1)
            }
            if (!isCurrent(inst)) return@execute
            ConnectionLog.add(
                if (reachable) "TCP ${profile.host}:${profile.port} OK"
                else "TCP ${profile.host}:${profile.port} GAGAL"
            )
            if (!reachable) {
                cleanupTerminalSession(inst)
                if (!userInitiatedDisconnect && !released && connectedAtMs > 0L && autoReconnectAttempts in 1..7) {
                    autoReconnectAttempts++
                    ConnectionLog.add("CM: jaringan belum pulih, tunggu 2s untuk auto-reconnect #${autoReconnectAttempts}")
                    mainHandler.postDelayed({
                        if (!released && !userInitiatedDisconnect) {
                            lastProfile?.let { connect(it) }
                        }
                    }, 2_000L)
                    return@execute
                }
                ConnectionLog.add(
                    "CM: " + RdpFailure.detailLine(
                        code = ERROR_UNREACHABLE,
                        message = "",
                        instance = 0L,
                        host = profile.host,
                        port = profile.port,
                    )
                )
                transition(
                    SessionState.Error(
                        ERROR_UNREACHABLE,
                        "Tidak bisa menghubungi ${profile.host}:${profile.port}. Kemungkinan: " +
                            "RDP nonaktif, firewall memblokir, nama host salah, atau target " +
                            "Windows Home (tidak punya server RDP).",
                        errorCode = RdpFailure.codeFor(ERROR_UNREACHABLE, ""),
                    )
                )
                return@execute
            }
            try {
                synchronized(lifecycleLock) {
                    if (!isCurrent(inst) || released) return@execute
                    setStage(Stage.HANDSHAKE)
                    ConnectionLog.add("CM: worker: session.connect mulai (native parse args + spawn connect thread)")
                    session.connect(appContext) // native worker starts before lock is released
                    ConnectionLog.add("CM: worker: session.connect kembali — connect thread jalan")
                }
            } catch (t: Throwable) {
                ConnectionLog.addThrowable("CM: worker: session.connect EXCEPTION", t)
                Log.w(TAG, "connect() exception", t)
                if (isCurrent(inst)) {
                    ConnectionLog.add(
                        "CM: " + RdpFailure.detailLine(
                            code = "connect_exception",
                            message = t.message.orEmpty(),
                            instance = inst,
                            host = lastProfile?.host,
                            port = lastProfile?.port ?: 0,
                        )
                    )
                    transition(
                        SessionState.Error(
                            "connect_exception",
                            t.message ?: "exception",
                            errorCode = RdpFailure.codeFor("connect_exception", t.message.orEmpty()),
                            connectionId = inst,
                        )
                    )
                    cleanupTerminalSession(inst)
                }
            }
        }
    }

    /** Batalkan koneksi yang sedang berjalan (tanpa menunggu timeout server). */
    fun cancelConnection() {
        userInitiatedDisconnect = true
        stopWatchdog()
        val inst = core?.getInstance() ?: return
        if (!LibFreeRDP.cancelConnection(inst)) {
            Log.w(TAG, "cancelConnection() gagal (state mungkin sudah terminal)")
        }
        // Jaga-jaga: kalau event failure tidak datang, jangan stuck di Connecting
        mainHandler.postDelayed({
            val s = _state.value
            if (isCurrent(inst) && (s is SessionState.Connecting || s is SessionState.Authenticating)) {
                transition(
                    SessionState.Error(
                        "cancelled",
                        "Pembatalan belum dikonfirmasi engine; tutup sesi untuk menghentikan koneksi dengan aman.",
                    )
                )
                ConnectionLog.add("cancel fallback: menunggu callback terminal native sebelum free")
            }
        }, CANCEL_FALLBACK_MS)
    }

    /** Ajaikan disconnect normal (dari sisi kita). */
    fun disconnect() {
        userInitiatedDisconnect = true
        stopWatchdog()
        val inst = core?.getInstance() ?: return
        transition(SessionState.Disconnecting)
        if (!LibFreeRDP.disconnect(inst)) {
            // native menolak — anggap sudah putus dan bebaskan instance terminal
            if (isCurrent(inst)) {
                transition(
                    SessionState.Error(
                        "disconnect_unconfirmed",
                        "Engine belum mengonfirmasi putus; tutup sesi untuk melepas koneksi dengan aman.",
                    )
                )
                ConnectionLog.add("disconnect ditolak native; instance dipertahankan sampai callback terminal/release")
            }
        } else {
            mainHandler.postDelayed({
                if (isCurrent(inst) && _state.value is SessionState.Disconnecting) {
                    ConnectionLog.add("disconnect fallback: native event tidak datang; minta cancel dan tunggu terminal")
                    runCatching { LibFreeRDP.cancelConnection(inst) }
                    transition(
                        SessionState.Error(
                            "disconnect_unconfirmed",
                            "Engine belum mengonfirmasi putus; tutup sesi untuk melepas koneksi dengan aman.",
                        )
                    )
                }
            }, DISCONNECT_FALLBACK_MS)
        }
    }

    /**
     * Lepas sesi + resource. Panggil saat Activity selesai (onDestroy).
     * Sesudah ini [SessionManager] tidak bisa dipakai lagi.
     */
    fun release() {
        synchronized(lifecycleLock) {
            if (released) return
            released = true
            stopWatchdog()
            val inst = core?.getInstance()
            core = null
            sink = null
            listener = null
            if (inst != null && inst != 0L) {
                GlobalApp.unregisterSessionListener(inst)
                try {
                    LibFreeRDP.disconnect(inst)
                } catch (t: Throwable) {
                    Log.w(TAG, "disconnect on release gagal", t)
                }
                // Free only after any in-flight session.connect() call has
                // crossed the same lock and registered the native worker.
                GlobalApp.freeSession(inst)
            }
            worker.shutdownNow()
        }
        if (_state.value is SessionState.Connected || _state.value is SessionState.Connecting ||
            _state.value is SessionState.Authenticating || _state.value is SessionState.Disconnecting
        ) {
            transition(SessionState.Idle)
        }
    }

    // ------------------------------------------------------------------
    // Input forwarding (dipakai HUD M2; pass-through tipis ke native)
    // ------------------------------------------------------------------

    fun sendCursorEvent(x: Int, y: Int, flags: Int): Boolean {
        val inst = core?.getInstance() ?: return false
        return LibFreeRDP.sendCursorEvent(inst, x, y, flags)
    }

    fun sendKeyEvent(keycode: Int, down: Boolean): Boolean {
        val inst = core?.getInstance() ?: return false
        return LibFreeRDP.sendKeyEvent(inst, keycode, down)
    }

    fun sendUnicodeKeyEvent(code: Int, down: Boolean): Boolean {
        val inst = core?.getInstance() ?: return false
        return LibFreeRDP.sendUnicodeKeyEvent(inst, code, down)
    }

    /**
     * Ketik string sebagai unicode key events, atau bila teks panjang (>24 karakter)
     * gunakan jalur clipboard + Ctrl+V secara bertahap agar antrean 512 event native
     * tidak pernah penuh / disconnect.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val inst = core?.getInstance() ?: return
        if (text.length > 24) {
            Thread {
                val synced = sendClipboardData(text)
                if (synced) {
                    try { Thread.sleep(95) } catch (_: InterruptedException) {}
                    // Kirim Ctrl+V (VK_LCONTROL = 0xA2, VK_KEY_V = 0x56)
                    LibFreeRDP.sendKeyEvent(inst, 0xA2, true)
                    LibFreeRDP.sendKeyEvent(inst, 0x56, true)
                    LibFreeRDP.sendKeyEvent(inst, 0x56, false)
                    LibFreeRDP.sendKeyEvent(inst, 0xA2, false)
                } else {
                    var i = 0
                    var countInBatch = 0
                    while (i < text.length && isCurrent(inst)) {
                        val cp = Character.codePointAt(text, i)
                        LibFreeRDP.sendUnicodeKeyEvent(inst, cp, true)
                        LibFreeRDP.sendUnicodeKeyEvent(inst, cp, false)
                        i += Character.charCount(cp)
                        countInBatch++
                        if (countInBatch >= 18) {
                            countInBatch = 0
                            try { Thread.sleep(14) } catch (_: InterruptedException) { break }
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            return
        }
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            LibFreeRDP.sendUnicodeKeyEvent(inst, cp, true)
            LibFreeRDP.sendUnicodeKeyEvent(inst, cp, false)
            i += Character.charCount(cp)
        }
    }

    fun sendClipboardData(data: String): Boolean {
        val inst = core?.getInstance() ?: return false
        if (data.isEmpty()) return false
        // Batasi panjang teks clipboard maksimal 120.000 karakter (~240 KB UTF-16LE)
        // dan normalisasi newline ke CRLF agar kanal CLIPRDR Windows (rdpclip.exe)
        // tidak pernah overflow/disconnect saat menyalin file .txt besar.
        val capped = if (data.length > 120_000) data.substring(0, 120_000) else data
        val safeText = if (capped.contains('\n') && !capped.contains("\r\n")) {
            capped.replace("\n", "\r\n").let { if (it.length > 120_000) it.substring(0, 120_000) else it }
        } else {
            capped
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val hash = safeText.hashCode()
        if (hash == lastSentClipboardHash && now - lastSentClipboardAtMs < 350L) {
            return true
        }
        lastSentClipboardHash = hash
        lastSentClipboardAtMs = now
        return runCatching { LibFreeRDP.sendClipboardData(inst, safeText) }.getOrDefault(false)
    }

    fun sendClipboardImageData(data: ByteArray, mimeType: String = "image/png"): Boolean {
        val inst = core?.getInstance() ?: return false
        if (data.isEmpty()) return false
        return runCatching {
            LibFreeRDP.sendClipboardImageData(inst, data, mimeType)
        }.getOrDefault(false)
    }

    /**
     * Kunci layar desktop Windows secara instan (`Win+L` -> `VK_LWIN = 0x5B`,
     * `VK_KEY_L = 0x4C`). Dipakai untuk fitur Panic-Lock dan Auto-Lock saat
     * sesi ditinggal / ditutup.
     */
    fun lockRemoteSession(): Boolean {
        val inst = core?.getInstance() ?: return false
        val winDown = LibFreeRDP.sendKeyEvent(inst, 0x5B, true)
        val lDown = LibFreeRDP.sendKeyEvent(inst, 0x4C, true)
        LibFreeRDP.sendKeyEvent(inst, 0x4C, false)
        LibFreeRDP.sendKeyEvent(inst, 0x5B, false)
        return winDown && lDown
    }

    /**
     * Ubah ukuran desktop remote TANPA reconnect lewat kanal DISP
     * (`/dynamic-resolution`). Balikan false = server/kanal belum siap,
     * pemanggil boleh jatuh ke jalur reconnect.
     */
    fun resizeRemote(width: Int, height: Int, desktopScaleFactor: Int = 100): Boolean {
        if (_state.value !is SessionState.Connected) return false
        if (!activeDynamicResolution) return false
        if (desktopScaleFactor !in REMOTE_DESKTOP_SCALE_FACTORS) return false
        val inst = core?.getInstance() ?: return false
        val boundedWidth = width.coerceIn(640, 8192)
        val w = boundedWidth - boundedWidth % 2
        val h = height.coerceIn(480, 8192)
        val curRes = resolution
        if ((curRes[0] == w && curRes[1] == h && lastDispScale == desktopScaleFactor) ||
            (lastDispLayoutW == w && lastDispLayoutH == h && lastDispScale == desktopScaleFactor)
        ) {
            return true
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (connectedAtMs > 0L && now - connectedAtMs < 900L) {
            return false
        }
        if (now - lastDispSentAtMs < 350L) {
            return false
        }
        val ok = runCatching {
            LibFreeRDP.sendMonitorLayout(inst, w, h, desktopScaleFactor)
        }.getOrDefault(false)
        if (ok) {
            lastDispLayoutW = w
            lastDispLayoutH = h
            lastDispScale = desktopScaleFactor
            lastDispSentAtMs = now
        }
        ConnectionLog.add(
            "CM: resizeRemote ${w}x${h} scale=${desktopScaleFactor}% -> " +
                if (ok) "dikirim (DISP)" else "ditolak",
        )
        return ok
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun setStage(st: Stage) {
        if (_stage.value != st) {
            _stage.value = st
            ConnectionLog.add("stage -> $st")
        }
    }

    private fun startWatchdog(inst: Long) {
        val token = ++watchdogToken
        mainHandler.postDelayed({
            if (token != watchdogToken) return@postDelayed
            val s = _state.value
            if (isCurrent(inst) && (s is SessionState.Connecting || s is SessionState.Authenticating)) {
                stopWatchdog()
                ConnectionLog.add("WATCHDOG: koneksi menggantung >${CONNECT_WATCHDOG_MS}ms")
                runCatching { LibFreeRDP.cancelConnection(inst) }
                ConnectionLog.add(
                    "CM: " + RdpFailure.detailLine(
                        code = "connect_timeout",
                        message = "",
                        instance = inst,
                        host = lastProfile?.host,
                        port = lastProfile?.port ?: 0,
                    )
                )
                transition(
                    SessionState.Error(
                        "connect_timeout",
                        "Koneksi menggantung lebih dari ${CONNECT_WATCHDOG_MS / 1000} detik. " +
                            "Kemungkinan: firewall memblokir setelah TCP, server lambat, atau " +
                            "NLA/TLS tidak selesai. Tekan Detail untuk log.",
                        errorCode = RdpFailure.codeFor("connect_timeout", ""),
                        connectionId = inst,
                    )
                )
                ConnectionLog.add("watchdog: instance ditahan sampai callback terminal native")
            }
        }, CONNECT_WATCHDOG_MS)
    }

    private fun stopWatchdog() {
        watchdogToken++
    }

    private fun logConnectStart() {
        if (!buildInfoLogged) {
            buildInfoLogged = true
            ConnectionLog.add("BUILD: ${buildInfo()}")
        }
        // Do not write account names or secret credential material into exportable logs.
        ConnectionLog.add("connect mulai")
    }

    private fun transition(s: SessionState) {
        val prev = _state.value
        _state.value = s
        if (prev != s) {
            Log.v(TAG, "state: $prev -> $s")
            ConnectionLog.add("state: $prev -> $s${(s as? SessionState.Error)?.let { " [${it.code}] ${it.message}" } ?: ""}")
            listener?.onStateChanged(s)
        }
    }

    private fun isCurrent(inst: Long): Boolean = core?.getInstance() == inst

    /**
     * Bebaskan hanya instance yang masih menjadi sesi aktif dan jalur native
     * sudah terminal (atau probe TCP gagal sebelum worker native dimulai).
     * FreeRDP.freeInstance menunggu worker native berhenti;
     * lifecycleLock mencegah release/retry membebaskan instance dua kali.
     */
    private fun cleanupTerminalSession(inst: Long) {
        synchronized(lifecycleLock) {
            quicAudioBridge?.stop()
            quicAudioBridge = null
            val session = core ?: return
            if (session.getInstance() != inst) return
            stopWatchdog()
            GlobalApp.unregisterSessionListener(inst)
            core = null
            try {
                GlobalApp.freeSession(inst)
                ConnectionLog.add("CM: terminal session freed inst=$inst")
            } catch (t: Throwable) {
                Log.e(TAG, "gagal melepas terminal session inst=$inst", t)
                ConnectionLog.addThrowable("CM: gagal melepas terminal session inst=$inst", t)
            }
        }
    }

    /** Probe TCP sederhana (juga gagal saat DNS/resolver gagal). */
    private fun tcpReachable(host: String, port: Int, timeoutMs: Long): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
        }
        true
    } catch (t: Throwable) {
        false
    }

    private fun coreListenerFor(inst: Long): GlobalApp.SessionEventListener =
        object : GlobalApp.SessionEventListener {
            // Callback sudah di-dispatch ke main thread oleh GlobalApp
            override fun onConnectionSuccess() {
                if (!isCurrent(inst)) return
                stopWatchdog()
                userInitiatedDisconnect = false
                autoReconnectAttempts = 0
                connectedAtMs = android.os.SystemClock.elapsedRealtime()
                setStage(Stage.READY)
                ConnectionLog.add("koneksi SUKSES")
                lastProfile?.let { p ->
                    val opts = runCatching { RdpOptions.of(appContext, p.id) }.getOrDefault(RdpOptions())
                    quicAudioBridge?.stop()
                    // Hindari suara dobel: mode Perangkat sudah memutar suara lewat
                    // kanal RDP (rdpsnd), jadi bridge hanya mengantar suara di mode
                    // "Audio di PC" (REMOTE).
                    val speakerOn = opts.quicAudio && opts.audioMode == KzAudioMode.REMOTE
                    val micOn = opts.usesQuicMicrophone()
                    if (speakerOn || micOn) {
                        audioBridgeState = "start…"
                        listener?.onAudioBridge(audioBridgeState)
                        ConnectionLog.add(
                            "AUDIO: bridge UDP :4433 speaker=$speakerOn mic=$micOn " +
                                "gain=${opts.micGainDb}dB gate=${opts.micGateDb}dB ns=${opts.micNoiseSuppression}"
                        )
                        quicAudioBridge = QuicAudioBridge(
                            host = p.host,
                            port = 4433,
                            enableSpeaker = speakerOn,
                            enableMic = micOn,
                            micGainDb = opts.micGainDb,
                            micGateDb = opts.micGateDb,
                            micNoiseSuppression = opts.micNoiseSuppression,
                            micAgc = opts.micAgc,
                            onStatus = { st ->
                                audioBridgeState = st
                                ConnectionLog.add("AUDIO: $st")
                                listener?.onAudioBridge(st)
                            },
                            onLevel = { tx, rx -> listener?.onAudioLevel(tx, rx) },
                        ).also { bridge ->
                            // Sadapan dipasang SEBELUM start() supaya paket audio
                            // pertama tidak terlewat oleh thread penerima.
                            pendingAudioTap?.let { tap -> bridge.tap = tap }
                            bridge.start()
                        }
                    } else {
                        audioBridgeState = "off (audio & mic mati di pengaturan sesi)"
                        listener?.onAudioBridge(audioBridgeState)
                    }
                }
                transition(SessionState.Connected)
            }

            override fun onConnectionFailure() {
                if (!isCurrent(inst)) return
                stopWatchdog()
                ConnectionLog.add("koneksi GAGAL (event native)")
                val p = lastProfile
                val nativeDetail = runCatching {
                    LibFreeRDP.getLastErrorString(inst).trim()
                }.getOrDefault("")
                val isAuthFailure = nativeDetail.lowercase().let { lower ->
                    lower.contains("logon") || lower.contains("password") ||
                        lower.contains("access_denied") || lower.contains("denied")
                }
                cleanupTerminalSession(inst)
                if (!userInitiatedDisconnect && !released && !isAuthFailure &&
                    connectedAtMs > 0L && autoReconnectAttempts < 6 && p != null
                ) {
                    autoReconnectAttempts++
                    ConnectionLog.add("CM: sinyal putus sesaat, auto-reconnect #${autoReconnectAttempts}...")
                    transition(SessionState.Connecting)
                    mainHandler.postDelayed({
                        if (!released && !userInitiatedDisconnect) connect(p)
                    }, 1_500L)
                    return
                }
                val summary = if (p != null) {
                    "Gagal koneksi ke ${p.host}:${p.port}"
                } else {
                    "Gagal koneksi"
                }
                val msg = if (nativeDetail.isNotBlank()) {
                    "$summary — Detail FreeRDP: $nativeDetail"
                } else {
                    summary
                }
                ConnectionLog.add(
                    "CM: " + RdpFailure.detailLine(
                        code = "connect_failed",
                        message = nativeDetail,
                        instance = inst,
                        host = p?.host,
                        port = p?.port ?: 0,
                    )
                )
                transition(
                    SessionState.Error(
                        "connect_failed",
                        msg,
                        errorCode = RdpFailure.codeFor("connect_failed", nativeDetail),
                        connectionId = inst,
                    )
                )
            }

            override fun onDisconnected() {
                if (!isCurrent(inst)) return
                stopWatchdog()
                setStage(Stage.IDLE)
                ConnectionLog.add("disconnect (event native)")
                val nativeDetail = runCatching {
                    LibFreeRDP.getLastErrorString(inst).trim()
                }.getOrDefault("").takeIf { detail ->
                    val lower = detail.lowercase()
                    lower.contains("another user connected") ||
                        lower.contains("active session limit timer") ||
                        lower.contains("idle session limit timer") ||
                        lower.contains("server denied connection")
                }
                val p = lastProfile
                cleanupTerminalSession(inst)
                if (!userInitiatedDisconnect && !released && nativeDetail == null &&
                    connectedAtMs > 0L && autoReconnectAttempts < 6 && p != null
                ) {
                    autoReconnectAttempts++
                    ConnectionLog.add("CM: sesi terputus mendadak, auto-reconnect #${autoReconnectAttempts}...")
                    transition(SessionState.Connecting)
                    mainHandler.postDelayed({
                        if (!released && !userInitiatedDisconnect) connect(p)
                    }, 1_500L)
                    return
                }
                transition(SessionState.Disconnected(nativeDetail))
            }
        }

    /**
     * Implementasi UIEventListener inti. Event grafik/pointer diteruskan
     * ke [sink] (surface KzDesk, M2); prompt = safe-default.
     */
    private fun uiListenerFor(inst: Long): LibFreeRDP.UIEventListener =
        object : LibFreeRDP.UIEventListener {

            override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
                resolution = intArrayOf(width, height)
                Log.v(TAG, "settings ${width}x${height} @${bpp}bpp")
            }

            override fun OnAuthenticate(
                username: StringBuilder,
                domain: StringBuilder,
                password: StringBuilder,
            ): Boolean {
                val l = listener
                if (l == null) {
                    Log.w(TAG, "NLA prompt tanpa listener — tolak (safe default)")
                    return false
                }
                if (!isCurrent(inst)) return false
                setStage(Stage.AUTH)
                ConnectionLog.add("prompt NLA/credential")
                transition(SessionState.Authenticating)
                val latch = CountDownLatch(1)
                var ok = false
                l.onCredentialsPrompt(
                    username.toString(),
                    domain.toString(),
                ) { u, d, p ->
                    if (u != null) {
                        username.setLength(0); username.append(u)
                    }
                    if (d != null) {
                        domain.setLength(0); domain.append(d)
                    }
                    if (p != null) {
                        password.setLength(0); password.append(p)
                    }
                    // reply tanpa satu pun nilai = user batal -> tolak
                    ok = (u != null || d != null || p != null)
                    latch.countDown()
                }
                latch.await(PROMPT_TIMEOUT_SEC, TimeUnit.SECONDS)
                return ok
            }

            override fun OnGatewayAuthenticate(
                username: StringBuilder,
                domain: StringBuilder,
                password: StringBuilder,
            ): Boolean = OnAuthenticate(username, domain, password)

            override fun OnVerifiyCertificateEx(
                host: String,
                port: Long,
                commonName: String,
                subject: String,
                issuer: String,
                fingerprint: String,
                flags: Long,
            ): Int = certificatePrompt(
                CertificateInfo(host, port.toInt(), commonName, subject, issuer, fingerprint, flags)
            )

            override fun OnVerifyChangedCertificateEx(
                host: String,
                port: Long,
                commonName: String,
                subject: String,
                issuer: String,
                fingerprint: String,
                oldSubject: String,
                oldIssuer: String,
                oldFingerprint: String,
                flags: Long,
            ): Int {
                // same prompt; FLAG_CHANGED sudah di-set inti — UI menampilkan
                // warning "sertifikat berubah" + fingerprint lama vs baru.
                return certificatePrompt(
                    CertificateInfo(host, port.toInt(), commonName, subject, issuer,
                                    fingerprint, flags)
                )
            }

            override fun OnExperimentalFeature(feature: Int): Boolean = true

            override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
                sink?.onGraphicsUpdate(x, y, width, height)
            }

            override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
                resolution = intArrayOf(width, height)
                lastDispLayoutW = width
                lastDispLayoutH = height
                sink?.onGraphicsResize(width, height, bpp)
            }

            override fun OnRemoteClipboardChanged(data: String) {
                listener?.onRemoteClipboardText(data)
            }

            override fun OnRemoteClipboardImageChanged(data: ByteArray?) {
                if (data != null && data.isNotEmpty()) {
                    listener?.onRemoteClipboardImage(data)
                }
            }

            override fun OnPointerSet(
                pixels: IntArray?,
                width: Int,
                height: Int,
                hotX: Int,
                hotY: Int,
            ) {
                sink?.onPointerSet(pixels, width, height, hotX, hotY)
            }

            override fun OnPointerSetNull() {
                sink?.onPointerSetNull()
            }

            override fun OnPointerSetDefault() {
                sink?.onPointerSetDefault()
            }

            override fun OnRailWindowUpdate(windowId: Long, width: Int, height: Int, pixels: IntArray?) {}
            override fun OnRailWindowMove(
                windowId: Long,
                x: Int,
                y: Int,
                w: Int,
                h: Int,
            ) {}
            override fun OnRailWindowHide(windowId: Long) {}
            override fun OnRailWindowDestroy(windowId: Long) {}
            override fun OnRailSessionEnd() {}
            override fun OnRailMonitoredDesktop(windowIds: LongArray?, activeWindowId: Long) {}
        }

    /** Prompt sertifikat — blocking (thread RDP), safe default = DENY. */
    private fun certificatePrompt(info: CertificateInfo): Int {
        ConnectionLog.add("prompt sertifikat ${info.host}:${info.port} fp=${info.fingerprint.take(32)}… flags=${info.flags}")
        val l = listener
        if (l == null) {
            Log.w(TAG, "cert prompt untuk ${info.host}:${info.port} tanpa listener — DENY")
            return CertificateInfo.VERIFY_DENY
        }
        val latch = CountDownLatch(1)
        var result = CertificateInfo.VERIFY_DENY
        l.onCertificatePrompt(info) {
            result = it
            latch.countDown()
        }
        latch.await(PROMPT_TIMEOUT_SEC, TimeUnit.SECONDS)
        return result
    }

    /** Tahap koneksi — untuk progres UI + diagnosa. */
    enum class Stage { IDLE, PROBE, HANDSHAKE, AUTH, READY }

    companion object {
        private const val TAG = "KzDeskSession"
        private const val PROMPT_TIMEOUT_SEC = 120L
        private const val CANCEL_FALLBACK_MS = 5_000L
        private const val DISCONNECT_FALLBACK_MS = 8_000L
        private const val TCP_PROBE_TIMEOUT_MS = 2_500L
        private const val TELEMETRY_INTERVAL_MS = 500L
        private const val CONNECT_WATCHDOG_MS = 30_000L
        private val REMOTE_DESKTOP_SCALE_FACTORS = setOf(100, 125, 150, 175, 200, 250, 300, 400, 500)

        /** Code [SessionState.Error] untuk probe TCP gagal (host tak terjangkau). */
        const val ERROR_UNREACHABLE = "unreachable"

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}

/**
 * Info build inti FreeRDP yang benar-benar terpasang di APK ini: versi +
 * daftar kanal/fitur penting. Dipakai layar diagnostik supaya pertanyaan
 * "audio/mic/clipboard ada di build ini atau tidak" bisa dijawab dari HP,
 * bukan dari tebakan.
 */
fun coreBuildInfo(): String = runCatching {
    "FreeRDP ${LibFreeRDP.getVersion()} | ${LibFreeRDP.getFeatureSummary()} | ${LibFreeRDP.getQuicEngineInfo()}"
}.getOrDefault("FreeRDP (info build tidak terbaca)")
