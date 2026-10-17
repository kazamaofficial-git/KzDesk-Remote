package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.ui.input.KzStickMode
import com.kazamatech.kzdesk.core.QuicAudioBridge
import androidx.compose.runtime.SideEffect
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.KeyEvent
import android.view.WindowManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.input.ImeAction
import com.kazamatech.kzdesk.ui.components.KzDialog
import com.kazamatech.kzdesk.ui.components.KzNoticeBus
import com.kazamatech.kzdesk.ui.components.KzField
import com.kazamatech.kzdesk.ui.components.KzOverlay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.freerdp.freerdpcore.utils.ClipboardImageProvider
import com.freerdp.freerdpcore.utils.Mouse
import com.kazamatech.kzdesk.core.CertificateInfo
import com.kazamatech.kzdesk.KzSessionService
import com.kazamatech.kzdesk.core.ConnectionLog
import com.kazamatech.kzdesk.core.ConnectionProfile
import com.kazamatech.kzdesk.core.RdpFailure
import com.kazamatech.kzdesk.core.RdpOptions
import com.kazamatech.kzdesk.core.SessionManager
import com.kazamatech.kzdesk.core.SmartResolution
import com.kazamatech.kzdesk.core.SessionState
import com.kazamatech.kzdesk.core.TelemetrySample
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzNoticeHost
import com.kazamatech.kzdesk.ui.components.rememberXyNotice
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.kazamatech.kzdesk.privacy.ActiveSessionStatsReporter
import java.util.UUID
import kotlin.math.roundToInt

private data class CertPrompt(
    val info: CertificateInfo,
    val oldFingerprint: String?,
    val reply: (Int) -> Unit,
)

private data class NlaPrompt(
    val user: String?,
    val domain: String?,
    val reply: (String?, String?, String?) -> Unit,
)

/**
 * Layar sesi KzDesk.
 *
 * Susunan lapisan (bawah ke atas):
 *  1. Surface RDP (SessionView + keyboard inti) via [AndroidView]
 *  2. Lapisan gesture trackpad (hanya mode trackpad)
 *  3. Kontrol sesi: pointer tunggal, cluster tombol mouse, tombol keyboard,
 *     handle panel
 *
 * Tidak ada bar atas berisi host/IP: info itu pindah ke panel (handle panah
 * di tepi kanan). Selama connecting, layar menampilkan wallpaper perangkat
 * yang diburamkan + langkah koneksi, bukan layar hitam kosong.
 */
/**
 * Pecah preset resolusi "1920x1080" jadi pasangan lebar/tinggi.
 * Balikan null kalau bukan format itu atau di luar batas yang masuk akal.
 */
private fun parseSize(preset: String): Pair<Int, Int>? {
    val parts = preset.split('x')
    if (parts.size != 2) return null
    val w = parts[0].toIntOrNull() ?: return null
    val h = parts[1].toIntOrNull() ?: return null
    if (w !in 640..8192 || w % 2 != 0 || h !in 480..8192) return null
    return w to h
}

internal fun normalizedRemoteResolution(width: Int, height: Int): Pair<Int, Int> {
    val boundedWidth = width.coerceIn(640, 8192)
    return (boundedWidth - boundedWidth % 2) to height.coerceIn(480, 8192)
}

internal fun remoteResolutionMatches(expected: Pair<Int, Int>, width: Int, height: Int): Boolean =
    expected.first == width && expected.second == height

private suspend fun SessionManager.awaitRemoteResolution(
    expected: Pair<Int, Int>,
    timeoutMs: Long = 3_000,
): Boolean = withTimeoutOrNull(timeoutMs) {
    telemetry.first { sample -> remoteResolutionMatches(expected, sample.width, sample.height) }
    true
} ?: false

/** Satuan gerak pointer per defleksi penuh joystick HUD; sama dengan gamepad fisik. */
private const val HUD_STICK_POINTER_UNITS = 18f

@Composable
fun KzDeskSessionScreen(
    profile: ConnectionProfile,
    manager: SessionManager,
    controller: SessionSurfaceController,
    onExit: () -> Unit,
    onDisplayRefreshPreferenceChange: (Int) -> Unit,
    /** Mode stick yang berlaku sekarang; dibaca tiap gerak, bukan saat komposisi. */
    stickModeProvider: () -> KzStickMode = { KzStickMode.POINTER },
    /** Sumbu stick untuk mode WASD/Panah, nilainya -1..1. */
    onStickKeys: (Float, Float) -> Unit = { _, _ -> },
    onStickKeysMode: (KzStickMode, Float, Float) -> Unit = { _, _, _ -> },
    /** Dipanggil saat pengguna memilih mode stick di panel sesi. */
    onStickModeChange: (KzStickMode) -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val appPrefs = remember(appContext) { AppPrefs(appContext) }
    fun exitSession() {
        appPrefs.clearAutoResumeSession(profile.id)
        onExit()
    }
    // Kotak posisi kursor untuk perekaman. Dideklarasikan sebelum remoteRecorder
    // karena cursorX/cursorY baru tersedia jauh di bawah, sementara provider-nya
    // harus ada saat perekam dibuat.
    val pointerBox = remember { RecordingPointerBox() }
    // Audio PC datang dari bridge audio QUIC sebagai PCM16 stereo 24 kHz.
    // Sadapannya dipasang/dilepas oleh perekam, jadi sesi hanya menjembatani.
    val recordingAudioSource = remember(manager) {
        object : RecordingAudioSource {
            override val sampleRate: Int = QuicAudioBridge.SAMPLE_RATE
            override val channelCount: Int = QuicAudioBridge.CHANNEL_COUNT
            override fun attachPcmTap(tap: ((ByteArray, Int, Int) -> Unit)?) {
                manager.setAudioPcmTap(tap)
            }
        }
    }
    val remoteRecorder = remember(controller, appContext, pointerBox, recordingAudioSource) {
        RemoteScreenRecorder(
            context = appContext,
            // mutable = true supaya kursor bisa digambar ke frame; frame ini
            // memang salinan baru tiap iterasi, bukan bitmap permukaan langsung.
            frameProvider = { controller.copyRemoteBitmap(mutable = true) },
            captureProtected = { appPrefs.flagSecure },
            pointerProvider = { pointerBox.current() },
            // Gambar frame langsung ke buffer perekam, tanpa salinan bitmap per
            // frame. frameProvider tetap ada sebagai jalur cadangan.
            frameDrawer = { canvas, dest -> controller.drawRemoteFrameInto(canvas, dest) },
            audioSource = recordingAudioSource,
        )
    }
    var recordingState by remember(remoteRecorder) {
        mutableStateOf<RemoteRecordingState>(RemoteRecordingState.Idle)
    }
    DisposableEffect(remoteRecorder) {
        onDispose { remoteRecorder.close() }
    }
    val sessionScope = rememberCoroutineScope()
    val state by manager.state.collectAsState(initial = SessionState.Idle)
    val stage by manager.stage.collectAsState(initial = SessionManager.Stage.IDLE)
    val telemetry by manager.telemetry.collectAsState(initial = TelemetrySample.EMPTY)

    // Anonymous active-session counting is on by default and can be disabled in Privacy settings.
    // Best-effort heartbeat uses a fresh random session UUID; no host/profile/account details.
    LaunchedEffect(state is SessionState.Connected, profile.id) {
        if (state !is SessionState.Connected) return@LaunchedEffect
        if (!appPrefs.shareActiveSessionStats) return@LaunchedEffect
        val statsSessionId = UUID.randomUUID()
        try {
            while (isActive && manager.state.value is SessionState.Connected && appPrefs.shareActiveSessionStats) {
                withContext(Dispatchers.IO) {
                    ActiveSessionStatsReporter.heartbeat(statsSessionId)
                }
                delay(30_000L)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                ActiveSessionStatsReporter.end(statsSessionId)
            }
        }
    }
    LaunchedEffect(state is SessionState.Connected, remoteRecorder) {
        if (state !is SessionState.Connected) remoteRecorder.stop()
    }

    val prefs = remember { SessionPrefs(context) }
    val trustStore = remember { CertificateTrustStore(context) }
    val clipboardSyncEnabled = remember(profile.id) {
        runCatching { RdpOptions.of(context, profile.id).clipboard }.getOrDefault(false)
    }
    LaunchedEffect(profile.id, controller) {
        controller.batterySaverMode = runCatching {
            RdpOptions.of(context, profile.id).batterySaver
        }.getOrDefault(false)
    }
    val wall = remember(profile.id) {
        KzWall.NEUTRAL.forDevice(profile.label ?: profile.host)
    }
    var certPrompt by remember { mutableStateOf<CertPrompt?>(null) }
    var nlaPrompt by remember { mutableStateOf<NlaPrompt?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmCancelConnect by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var activeProfile by remember(profile.id) { mutableStateOf(profile) }
    var consoleAdminMode by remember(profile.id) {
        mutableStateOf(runCatching { RdpOptions.of(context, profile.id).consoleAdmin }.getOrDefault(false))
    }
    var swapMouseButtons by remember { mutableStateOf(prefs.swapMouseButtons) }
    var zoom by remember { mutableFloatStateOf(prefs.zoom(profile.id)) }
    var boundInstance by remember { mutableStateOf(0L) }
    var inputMode by remember { mutableIntStateOf(prefs.inputMode.ordinal) }
    var pointerVisible by remember { mutableStateOf(true) }
    // Kursor overlay disembunyikan saat mouse fisik terpasang: kursor remote
    // sudah tampil di stream — dua kursor sekaligus terlihat seperti bug
    // "mouse double" yang dilaporkan penguji.
    val extMouseState = remember {
        (context as? KzDeskSessionActivity)?.externalMouseConnectedState
            ?: mutableStateOf(false)
    }
    val extMouseConnected by extMouseState
    // Keyboard HP (IME) — satu-satunya keyboard. Board keyboard virtual dan
    // toolbar di atas keyboard sudah dihapus dari produk (ronde 5).
    var keyboardShown by remember(profile.id) { mutableStateOf(prefs.keyboardShown(profile.id)) }
    var hudKeys by remember(profile.id) {
        // Layout lama dimigrasi tanpa mengubah posisi/ukuran yang sudah diatur.
        mutableStateOf(HudKey.migrate(prefs.hudKeys(profile.id), prefs.hudButtonSize))
    }
    var mappingMode by remember { mutableStateOf(false) }
    var remoteCursor by remember { mutableStateOf<RemoteCursor?>(null) }
    val notice = rememberXyNotice()
    var autoFit by remember { mutableStateOf(prefs.autoFit) }
    var remoteDpi by remember(profile.id) {
        mutableIntStateOf(DisplayPrefs.remoteDpi(context, profile.id))
    }
    var lastRemoteDpiRequest by remember(profile.id) { mutableIntStateOf(100) }
    /** Ukuran area gambar (tanpa kontrol), dipakai untuk resize yang akurat. */
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    /** Resize yang harus dikirim ulang setelah reconnect (ganti resolusi). */
    var pendingResize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Info teknis untuk laporan bug: versi FreeRDP + ringkasan build JNI.
    val coreInfo = remember {
        runCatching {
            listOf(
                kzNow("Inti RDP {0}", "RDP engine {0}", manager.freeRdpVersion()),
                manager.buildInfo().lineSequence().firstOrNull()?.take(120).orEmpty(),
            ).filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
    val latchedKeys = remember(profile.id) { mutableStateMapOf<String, Boolean>() }
    val oneShotKeys = remember(profile.id) { mutableStateMapOf<String, Boolean>() }
    var controlsOverlayOpen by remember { mutableStateOf(false) }
    var controlsBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }

    fun setHudKeys(list: List<HudKey>) {
        hudKeys = list
        prefs.setHudKeys(profile.id, list)
        if (prefs.hudProfile(profile.id) == HudProfilePreset.CUSTOM) {
            prefs.setCustomHudKeys(profile.id, list)
        }
    }
    var cursorX by remember { mutableFloatStateOf(0f) }
    var cursorY by remember { mutableFloatStateOf(0f) }
    var cursorInit by remember { mutableStateOf(false) }
    // Perataan delta kursor antar-frame (EMA) — lihat smoothPointerDelta.
    var smoothPrevDx by remember { mutableFloatStateOf(Float.NaN) }
    var smoothPrevDy by remember { mutableFloatStateOf(Float.NaN) }
    var applyingResolution by remember { mutableStateOf(false) }
    // Sesi pernah tersambung? Dipakai auto-reconnect: koneksi yang putus
    // sendiri disambung ulang, tapi kegagalan sambung awal tidak diulang.
    var everConnected by remember { mutableStateOf(false) }
    var reconnectAttempt by remember { mutableIntStateOf(0) }
    var reconnecting by remember { mutableStateOf(false) }
    var waitingForNativeRelease by remember { mutableStateOf(false) }
    var userDisconnect by remember { mutableStateOf(false) }
    /** Clipboard terakhir yang datang dari remote (tombol "tempel ke HP"). */
    var lastRemoteClipboard by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) {
        if (state is SessionState.Connected) {
            appPrefs.rememberAutoResumeSession(profile.id)
            everConnected = true
            reconnectAttempt = 0
            reconnecting = false
        }
    }

    // Jangan akhiri UI sesi saat transport terputus sementara. Tetap di layar,
    // retry tanpa batas dengan backoff (2, 4, 8, 16, lalu 30 detik), dan biarkan
    // user menghentikannya sendiri. Error autentikasi/cert tetap berhenti retry.
    LaunchedEffect(state, everConnected, userDisconnect, applyingResolution) {
        val currentState = state
        if (!everConnected || userDisconnect || applyingResolution || !prefs.autoReconnect) {
            if (userDisconnect || applyingResolution || !prefs.autoReconnect) {
                reconnecting = false
                waitingForNativeRelease = false
            }
            return@LaunchedEffect
        }
        val retryable = currentState is SessionState.Disconnected ||
            (currentState is SessionState.Error && currentState.code in setOf("unreachable", "connect_timeout"))
        if (!retryable) {
            if (currentState is SessionState.Error) {
                reconnecting = false
                waitingForNativeRelease = false
            }
            return@LaunchedEffect
        }
        reconnecting = true
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        val waitMs = when (reconnectAttempt) {
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            4 -> 16_000L
            else -> 30_000L
        }
        kotlinx.coroutines.delay(waitMs)
        if (!userDisconnect && !applyingResolution && reconnecting) {
            // SessionManager intentionally retains a native instance until its
            // terminal callback; never race a retry against native cleanup.
            waitingForNativeRelease = manager.instance() != 0L
            while (!userDisconnect && !applyingResolution && reconnecting && manager.instance() != 0L) {
                kotlinx.coroutines.delay(500)
            }
            waitingForNativeRelease = false
            if (!userDisconnect && !applyingResolution && reconnecting) {
                boundInstance = 0L
                manager.connect(activeProfile)
            }
        }
    }

    val remoteWidth = if (telemetry.width > 0) telemetry.width else 1920
    val remoteHeight = if (telemetry.height > 0) telemetry.height else 1080

    // Petunjuk sekali saja: di sesi pertama, jelaskan bahwa tombol terkunci
    // dan cara memindahkannya (ini yang dulu bikin kontrol terasa "gajelas").
    LaunchedEffect(profile.id) {
        if (!prefs.hudTipShown) {
            prefs.hudTipShown = true
            kotlinx.coroutines.delay(900)
            notice.show(
                kzNow(
                    "Tombol kontrol terkunci. Gunakan \"Atur posisi\" dari menu panel kanan atas untuk memindahkannya.",
                    "Control buttons are locked. Use \"Edit layout\" from the top-right session panel to move them.",
                ),
            )
        }
    }

    LaunchedEffect(profile.id) {
        rotationFor(context, profile.id).let { mode ->
            (context as? Activity)?.requestedOrientation = when (mode) {
                "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        }
    }

    LaunchedEffect(Unit) {
        manager.setListener(object : SessionManager.Listener {
            override fun onCertificatePrompt(info: CertificateInfo, reply: (Int) -> Unit) {
                val stored = trustStore.fingerprint(info.host, info.port)
                if (stored != null && stored == info.fingerprint) {
                    reply(CertificateInfo.VERIFY_ACCEPT)
                    return
                }
                val activity = context as? Activity
                if (activity == null) {
                    reply(CertificateInfo.VERIFY_DENY)
                    return
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        reply(CertificateInfo.VERIFY_DENY)
                    } else {
                        certPrompt = CertPrompt(info, stored, reply)
                    }
                }
            }

            override fun onCredentialsPrompt(
                username: String?,
                domain: String?,
                reply: (String?, String?, String?) -> Unit,
            ) {
                val activity = context as? Activity
                if (activity == null) {
                    reply(null, null, null)
                    return
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        reply(null, null, null)
                    } else {
                        nlaPrompt = NlaPrompt(username, domain, reply)
                    }
                }
            }

            override fun onRemoteClipboardText(text: String) {
                val activity = context as? Activity ?: return
                activity.runOnUiThread {
                    val clipboardEnabled = runCatching {
                        RdpOptions.of(activity, profile.id).clipboard
                    }.getOrDefault(false)
                    if (!activity.isFinishing && !activity.isDestroyed && clipboardEnabled) {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val applied = try {
                            (activity as? KzDeskSessionActivity)?.noteRemoteClipboardText(text)
                            cm.setPrimaryClip(ClipboardImageProvider.createTextClip(activity, "rdp", text))
                            true
                        } catch (e: RuntimeException) {
                            // Never log remote clipboard contents or exception details.
                            ConnectionLog.add("clipboard: Android clipboard service rejected remote text")
                            KzNoticeBus.post(
                                kzNow(
                                    "Clipboard remote gagal disalin ke HP; isi tidak dicatat",
                                    "Remote clipboard could not be copied to phone; contents were not logged",
                                ),
                            )
                            false
                        }
                        if (applied) lastRemoteClipboard = text
                    }
                }
            }

            override fun onRemoteClipboardImage(data: ByteArray) {
                val activity = context as? Activity ?: return
                activity.runOnUiThread {
                    val clipboardEnabled = runCatching {
                        RdpOptions.of(activity, profile.id).clipboard
                    }.getOrDefault(false)
                    if (!activity.isFinishing && !activity.isDestroyed && clipboardEnabled && data.isNotEmpty()) {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        runCatching {
                            (activity as? KzDeskSessionActivity)?.noteRemoteClipboardImage(data)
                            ClipboardImageProvider.setImageData(data)
                            cm.setPrimaryClip(
                                android.content.ClipData.newUri(
                                    activity.contentResolver,
                                    "rdp-image",
                                    ClipboardImageProvider.CONTENT_URI,
                                ),
                            )
                        }
                    }
                }
            }
        })
    }

    LaunchedEffect(profile.id) {
        // IME bisa ditutup lewat tombol Back Android, bukan hanya dari rail.
        // Sinkronkan status rail dengan inset IME yang benar-benar terlihat.
        controller.onImeChanged = { heightPx ->
            val visible = heightPx > 0
            keyboardShown = visible
            prefs.setKeyboardShown(profile.id, visible)
            (context as? KzDeskSessionActivity)?.onSessionImeVisibilityChanged(visible)
        }
        controller.onRemoteCursor = { cursor -> remoteCursor = cursor }
        controller.onCursorMoved = { x, y ->
            cursorInit = true
            cursorX = x.toFloat().coerceIn(0f, remoteWidth.toFloat())
            cursorY = y.toFloat().coerceIn(0f, remoteHeight.toFloat())
        }
        controller.refreshImeInsets()
    }

    LaunchedEffect(Unit) {
        controller.onZoomChanged = { z ->
            zoom = z
            prefs.setZoom(profile.id, z)
        }
        controller.setInstance(manager.instance())
    }

    // Foreground service: sesi tetap hidup saat app ditinggal ke latar, dan
    // notifikasinya memberi jalan pulang + tombol putus.
    LaunchedEffect(state) {
        val label = profile.label ?: "${profile.host}:${profile.port}"
        val active = manager.instance() != 0L || state is SessionState.Connecting ||
            state is SessionState.Authenticating || state is SessionState.Connected ||
            state is SessionState.Disconnecting
        KzSessionRegistry.setActive(profile.id, active)
        if (active) manager.instance().takeIf { it != 0L }?.let(controller::setInstance)
        if (state is SessionState.Connected) {
            KzSessionService.start(context, label)
        } else if (KzSessionRegistry.activeCount() == 0) {
            KzSessionService.stop(context)
        }
    }

    LaunchedEffect(state) {
        if (state is SessionState.Connected) {
            val instance = manager.instance()
            if (instance != 0L && boundInstance != instance) {
                boundInstance = instance
                lastRemoteDpiRequest = DisplayPrefs.remoteDpi(context, profile.id)
                controller.bind(instance)
                controller.setImeVisible(keyboardShown)
                (context as? KzDeskSessionActivity)?.syncPhoneClipboardToRemote()
                when {
                    // Zoom sendiri menang; kalau belum pernah diatur dan auto-fit
                    // menyala, seluruh desktop dimuat (taskbar ikut kelihatan).
                    prefs.hasZoom(profile.id) -> controller.applyZoom(zoom)
                    prefs.autoFit -> controller.fitToScreen()
                    else -> {
                        val dpi = DisplayPrefs.dpi(context, profile.id) / 100f
                        if (dpi != 1f) controller.applyZoom(dpi) else controller.fitToScreen()
                    }
                }
            }
        }
    }

    val isPcConnectMode = remember(profile.id) {
        runCatching { RdpOptions.of(context, profile.id).pcConnectMode }.getOrDefault(false)
    }
    val isConsoleAdminMode = remember(profile.id) {
        runCatching {
            val o = RdpOptions.of(context, profile.id)
            o.pcConnectMode || o.consoleAdmin
        }.getOrDefault(false)
    }

    // Restore remote DPI on each new native instance, and apply user changes
    // through DISP without touching the local zoom slider.
    LaunchedEffect(state, boundInstance, remoteDpi, pendingResize, telemetry.width, telemetry.height) {
        if (isPcConnectMode || isConsoleAdminMode) return@LaunchedEffect
        if (state !is SessionState.Connected || boundInstance == 0L || pendingResize != null) {
            return@LaunchedEffect
        }
        if (remoteDpi == lastRemoteDpiRequest) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        var sent = false
        for (attempt in 0 until 8) {
            if (state !is SessionState.Connected || boundInstance == 0L) return@LaunchedEffect
            val w = telemetry.width
            val h = telemetry.height
            if (w in 640..8192 && h in 480..8192 && manager.resizeRemote(w, h, remoteDpi)) {
                sent = true
                break
            }
            kotlinx.coroutines.delay(500)
        }
        if (sent) {
            lastRemoteDpiRequest = remoteDpi
        } else {
            remoteDpi = lastRemoteDpiRequest
            DisplayPrefs.setRemoteDpi(context, profile.id, remoteDpi)
            notice.show(
                kzNow(
                    "DPI remote tidak tersedia: server atau kanal Display Control belum siap.",
                    "Remote DPI is unavailable: the server or Display Control channel is not ready.",
                ),
            )
        }
    }

    val configuration = LocalConfiguration.current
    LaunchedEffect(state, configuration.screenWidthDp, configuration.screenHeightDp, viewport) {
        if (state !is SessionState.Connected || applyingResolution) return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        if (isPcConnectMode || isConsoleAdminMode) {
            if (autoFit) controller.fitToScreen()
            return@LaunchedEffect
        }
        if (autoFit) controller.fitToScreen()
        kotlinx.coroutines.delay(950)
        if (state !is SessionState.Connected || applyingResolution) return@LaunchedEffect
        when (DisplayPrefs.resolution(context, profile.id)) {
            // Otomatis uses conservative 720p instead of forcing FHD on landscape phones.
            DisplayPrefs.AUTOMATIC -> {
                val size = SmartResolution.parse(
                    SmartResolution.forViewport(viewport.width, viewport.height),
                )
                if (size != null && !remoteResolutionMatches(size, telemetry.width, telemetry.height)) {
                    manager.resizeRemote(size.first, size.second, remoteDpi)
                }
                if (autoFit) controller.fitToScreen()
            }

            // Eksplisit ikuti layar HP (rasio HP) — pilihan user, bukan default.
            DisplayPrefs.FOLLOW -> {
                val target = normalizedRemoteResolution(viewport.width, viewport.height)
                if (!remoteResolutionMatches(target, telemetry.width, telemetry.height)) {
                    manager.resizeRemote(target.first, target.second, remoteDpi)
                }
                if (autoFit) controller.fitToScreen()
            }

            else -> Unit
        }
    }

    // Kalau ukuran layar berubah (rotasi), desktop remote ikut menyesuaikan.
    LaunchedEffect(viewport) {
        if (state is SessionState.Connected && autoFit && viewport.width > 0) {
            controller.fitToScreen()
        }
    }

    LaunchedEffect(state, applyingResolution) {
        if (applyingResolution && (state is SessionState.Disconnected || state is SessionState.Error)) {
            while (manager.instance() != 0L) {
                kotlinx.coroutines.delay(120)
            }
            applyingResolution = false
            boundInstance = 0L
            manager.connect(activeProfile)
        }
    }

    // Setelah reconnect, verifikasi ukuran yang diminta dari telemetry server.
    LaunchedEffect(state) {
        val want = pendingResize ?: return@LaunchedEffect
        if (state is SessionState.Connected) {
            var confirmed = manager.awaitRemoteResolution(want, timeoutMs = 1_500)
            if (!confirmed) {
                var queued = false
                for (attempt in 0 until 8) {
                    if (manager.resizeRemote(want.first, want.second, remoteDpi)) {
                        queued = true
                        break
                    }
                    delay(500)
                }
                confirmed = queued && manager.awaitRemoteResolution(want)
            }
            if (confirmed) {
                lastRemoteDpiRequest = remoteDpi
                controller.fitToScreen()
                notice.show(kzNow("Resolusi terkonfirmasi: {0} x {1}", "Resolution confirmed: {0} x {1}", want.first, want.second))
            } else {
                val actual = if (telemetry.width > 0) "${telemetry.width} x ${telemetry.height}" else kzNow("belum dilaporkan", "not reported")
                notice.show(
                    kzNow(
                        "Host melaporkan {0}; resolusi {1} x {2} tidak terkonfirmasi.",
                        "Host reports {0}; resolution {1} x {2} was not confirmed.",
                        actual, want.first, want.second,
                    ),
                )
            }
            pendingResize = null
        }
    }

    LaunchedEffect(state) {
        val act = context as? Activity ?: return@LaunchedEffect
        if (state is SessionState.Connected) {
            act.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            act.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val overlayActive = controlsOverlayOpen || certPrompt != null || nlaPrompt != null || confirmDisconnect || confirmCancelConnect || showLog
    LaunchedEffect(overlayActive) {
        controller.setOverlayActive(overlayActive)
    }

    LaunchedEffect(state, certPrompt != null, nlaPrompt != null, confirmDisconnect, confirmCancelConnect, showLog, controlsBackHandler) {
        (context as? KzDeskSessionActivity)?.backHandler = {
            when {
                certPrompt != null || nlaPrompt != null -> true
                showLog -> {
                    showLog = false
                    true
                }
                controlsBackHandler?.invoke() == true -> true
                confirmDisconnect -> {
                    confirmDisconnect = false
                    true
                }
                confirmCancelConnect -> {
                    confirmCancelConnect = false
                    true
                }
                state is SessionState.Connected -> {
                    confirmDisconnect = true
                    true
                }
                state is SessionState.Connecting || state is SessionState.Authenticating -> {
                    confirmCancelConnect = true
                    true
                }
                else -> false
            }
        }
    }

    fun reportInputDispatchFailure() {
        (context as? KzDeskSessionActivity)?.reportInputDispatchFailure()
    }
    fun sendCursorEvent(x: Int, y: Int, flags: Int) {
        if (!manager.sendCursorEvent(x, y, flags) && state is SessionState.Connected) {
            reportInputDispatchFailure()
        }
    }
    fun cursor(): Offset {
        if (!cursorInit) {
            cursorInit = true
            cursorX = remoteWidth / 2f
            cursorY = remoteHeight / 2f
        }
        return Offset(cursorX, cursorY)
    }

    fun movePointer(dxScreen: Float, dyScreen: Float) {
        // Ratakan dulu supaya sapuan di game tidak patah-patah ("muter-muter"),
        // baru terapkan sensitivitas/akselerasi seperti biasa.
        val (flatDx, flatDy) = smoothPointerDelta(
            prevDx = smoothPrevDx,
            prevDy = smoothPrevDy,
            dx = dxScreen,
            dy = dyScreen,
            alpha = 0.45f,
        )
        smoothPrevDx = flatDx
        smoothPrevDy = flatDy
        val (scaledDx, scaledDy) = applyTrackpadDelta(
            dx = flatDx,
            dy = flatDy,
            sensitivity = prefs.pointerSensitivity,
            acceleration = prefs.pointerAcceleration,
        )
        val scale = if (zoom > 0.05f) zoom else 1f
        cursorX = (cursorX + scaledDx / scale).coerceIn(0f, remoteWidth.toFloat())
        cursorY = (cursorY + scaledDy / scale).coerceIn(0f, remoteHeight.toFloat())
        val c = cursor()
        sendCursorEvent(c.x.roundToInt(), c.y.roundToInt(), Mouse.getMoveEvent())
    }

    fun sendButton(button: KzMouseButton, down: Boolean) {
        val c = cursor()
        val effectiveButton = if (swapMouseButtons) {
            when (button) {
                KzMouseButton.LEFT -> KzMouseButton.RIGHT
                KzMouseButton.RIGHT -> KzMouseButton.LEFT
                KzMouseButton.MIDDLE -> KzMouseButton.MIDDLE
            }
        } else button
        val flags = when (effectiveButton) {
            KzMouseButton.LEFT -> Mouse.getLeftButtonEvent(context, down)
            KzMouseButton.RIGHT -> Mouse.getRightButtonEvent(context, down)
            KzMouseButton.MIDDLE -> Mouse.getMiddleButtonEvent(down)
        }
        sendCursorEvent(c.x.roundToInt(), c.y.roundToInt(), flags)
    }

    fun sendScrollUnits(units: Int) {
        if (units == 0) return
        val c = cursor()
        var remaining = units
        while (remaining != 0) {
            val batch = remaining.coerceIn(-Mouse.WHEEL_DELTA, Mouse.WHEEL_DELTA)
            sendCursorEvent(
                c.x.roundToInt(),
                c.y.roundToInt(),
                Mouse.getScrollEvent(context, batch),
            )
            remaining -= batch
        }
    }

    fun sendScroll(notches: Int) = sendScrollUnits(Mouse.WHEEL_DELTA * notches)

    /**
     * Buka/tutup keyboard HP. Sumber kebenarannya state IME yang sebenarnya,
     * bukan state lokal: kalau user menutup keyboard lewat tombol back
     * Android, tombol rail tetap tahu keadaan aslinya.
     */
    fun openKeyboard() {
        keyboardShown = true
        prefs.setKeyboardShown(profile.id, true)
        controller.setImeVisible(true)
    }

    /** Kirim aksi satu tombol HUD (down=true tekan, false lepas). */
    fun runHudKey(key: HudKey, down: Boolean) {
        when (key.kind) {
            // Sumbu joystick dialirkan lewat onStickAxis saat jari bergerak,
            // bukan sebagai aksi tekan/lepas, jadi tidak ada yang perlu dikirim.
            HudKind.PAD_STICK -> Unit
            HudKind.MOUSE_LEFT -> sendButton(KzMouseButton.LEFT, down)
            HudKind.MOUSE_RIGHT -> sendButton(KzMouseButton.RIGHT, down)
            HudKind.MOUSE_MIDDLE -> sendButton(KzMouseButton.MIDDLE, down)
            HudKind.MOUSE_SWAP -> if (down) {
                val next = !swapMouseButtons
                swapMouseButtons = next
                prefs.swapMouseButtons = next
                notice.show(
                    if (next) kzNow("Klik Kiri ⇄ Kanan ditukar", "Left ⇄ Right click swapped")
                    else kzNow("Klik Kiri & Kanan normal", "Left & Right click normal"),
                )
            }
            HudKind.SCROLL_UP -> if (down) sendScroll(1)
            HudKind.SCROLL_DOWN -> if (down) sendScroll(-1)
            HudKind.SCROLL_SLIDER -> Unit
            HudKind.KEYBOARD -> if (down) openKeyboard()

            HudKind.INPUT_SWITCH -> if (down) {
                val next = if (InputMode.entries[inputMode] == InputMode.TRACKPAD) {
                    InputMode.DIRECT
                } else {
                    InputMode.TRACKPAD
                }
                inputMode = next.ordinal
                prefs.inputMode = next
                if (next == InputMode.DIRECT) controller.setImeVisible(keyboardShown)
            }

            HudKind.KEY -> {
                if (key.shift && down) controller.sendVirtualKey(KeyEvent.KEYCODE_SHIFT_LEFT, true)
                controller.sendVirtualKey(key.keyCode, down)
                if (key.shift && !down) controller.sendVirtualKey(KeyEvent.KEYCODE_SHIFT_LEFT, false)
            }

            HudKind.COMBO -> if (down) controller.sendCombo(key.combo)

            HudKind.MACRO -> if (down) {
                sessionScope.launch {
                    if (key.combo.isNotEmpty()) {
                        controller.sendCombo(key.combo)
                        if (key.macroText.isNotEmpty()) delay(220)
                    }
                    if (key.macroText.isNotEmpty()) {
                        manager.sendText(key.macroText)
                        if (key.macroSendEnter) {
                            delay(60)
                            controller.sendVirtualKey(KeyEvent.KEYCODE_ENTER, true)
                            controller.sendVirtualKey(KeyEvent.KEYCODE_ENTER, false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Fase dari layer tombol -> aksi. TAP = tekan+lepas, ONE_SHOT = aktif untuk
     * 1 aksi berikutnya lalu lepas otomatis, HOLD = aktif selama ditahan,
     * TOGGLE = nyala/mati dengan latch terpisah per tombol.
     */
    fun syncHudModifierState() {
        controller.hasActiveHudModifiers = hudKeys.any { key ->
            latchedKeys[key.id] == true && key.kind == HudKind.KEY && HudKey.isModifierKeyCode(key.keyCode)
        }
    }

    fun consumeOneShotKeys() {
        if (oneShotKeys.isEmpty()) return
        val armedIds = oneShotKeys.filterValues { it }.keys.toList()
        oneShotKeys.clear()
        armedIds.forEach { id ->
            latchedKeys.remove(id)
            hudKeys.firstOrNull { it.id == id }?.let { key -> runHudKey(key, false) }
        }
        syncHudModifierState()
    }

    DisposableEffect(controller, clipboardSyncEnabled) {
        val act = context as? KzDeskSessionActivity
        controller.onInputKeyConsumed = {
            (context as? Activity)?.runOnUiThread {
                consumeOneShotKeys()
            }
        }
        controller.onImeSnippetCommitted = { snippet ->
            if (clipboardSyncEnabled && snippet.length > 1) {
                act?.noteRemoteClipboardText(snippet)
                sessionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    manager.sendClipboardData(snippet)
                }
            }
        }
        act?.onExternalPointerDelta = { dx, dy -> movePointer(dx, dy) }
        act?.onExternalScrollUnits = { units -> sendScrollUnits(units) }
        act?.onExternalMouseClick = { btn, down ->
            sendButton(btn, down)
            if (!down) consumeOneShotKeys()
        }
        act?.setGyroMouseActive(prefs.gyroMouseEnabled)
        onDispose {
            controller.onInputKeyConsumed = null
            controller.onImeSnippetCommitted = null
            controller.hasActiveHudModifiers = false
            act?.onExternalPointerDelta = null
            act?.onExternalScrollUnits = null
            act?.onExternalMouseClick = null
            act?.setGyroMouseActive(false)
        }
    }

    fun releaseLatchedKeys() {
        hudKeys.filter { latchedKeys[it.id] == true }.forEach { key -> runHudKey(key, false) }
        latchedKeys.clear()
        oneShotKeys.clear()
        syncHudModifierState()
    }

    fun shouldConsumeOneShotAfter(key: HudKey): Boolean = when (key.kind) {
        HudKind.KEY -> !HudKey.isModifierKeyCode(key.keyCode)
        HudKind.COMBO, HudKind.MACRO, HudKind.MOUSE_LEFT, HudKind.MOUSE_RIGHT, HudKind.MOUSE_MIDDLE -> true
        else -> false
    }

    fun handleHudPhase(key: HudKey, phase: HudPhase) {
        when (key.action) {
            HudAction.HOLD -> runHudKey(key, phase == HudPhase.DOWN)

            HudAction.TOGGLE -> {
                if (phase != HudPhase.TAP) return
                val on = latchedKeys[key.id] == true
                oneShotKeys.remove(key.id)
                latchedKeys[key.id] = !on
                runHudKey(key, !on)
                syncHudModifierState()
            }

            HudAction.ONE_SHOT -> {
                if (phase != HudPhase.TAP) return
                val on = oneShotKeys[key.id] == true || latchedKeys[key.id] == true
                if (on) {
                    oneShotKeys.remove(key.id)
                    latchedKeys.remove(key.id)
                    runHudKey(key, false)
                } else {
                    oneShotKeys[key.id] = true
                    latchedKeys[key.id] = true
                    runHudKey(key, true)
                }
                syncHudModifierState()
            }

            HudAction.TAP -> when (phase) {
                HudPhase.DOWN -> runHudKey(key, true)
                HudPhase.UP -> {
                    runHudKey(key, false)
                    if (shouldConsumeOneShotAfter(key)) consumeOneShotKeys()
                }
                HudPhase.TAP -> {
                    runHudKey(key, true)
                    runHudKey(key, false)
                    if (shouldConsumeOneShotAfter(key)) consumeOneShotKeys()
                }
            }
        }
    }

    val connected = state is SessionState.Connected
    var privacyCurtain by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = it },
    ) {
        AndroidView(
            factory = { ctx -> controller.buildViewTree(ctx) },
            modifier = Modifier
                .fillMaxSize()
                // Latar ala macOS: permukaan remote diblur selama panel/menu
                // terbuka. Modifier.blur hanya berjalan di Android 12+ (API 31);
                // di bawah itu otomatis no-op sehingga scrim panel yang menjaga
                // kontras. Blur dipasang di surface, bukan di panel, karena
                // Modifier.blur memblur konten yang ditempeli -- bukan yang ada
                // di belakangnya.
,
        )


        // Lapisan gesture trackpad: menutup surface supaya sentuhan tidak
        // diteruskan langsung sebagai klik di posisi jari.
        if (connected && (mappingMode || controlsOverlayOpen || privacyCurtain)) {
            // Mode layout atau panel terbuka harus mengisolasi seluruh layar dari surface FreeRDP.
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .pointerInput(mappingMode, controlsOverlayOpen, privacyCurtain) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )
        }

        if (connected && !privacyCurtain && !mappingMode && !controlsOverlayOpen && InputMode.entries[inputMode] == InputMode.TRACKPAD) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(zoom, remoteWidth, remoteHeight) {
                        awaitPointerEventScope {
                            var lastMousePos: Offset? = null
                            while (true) {
                                val ev = awaitPointerEvent()
                                val firstChange = ev.changes.firstOrNull() ?: continue
                                if (firstChange.type == androidx.compose.ui.input.pointer.PointerType.Mouse) {
                                    when (ev.type) {
                                        androidx.compose.ui.input.pointer.PointerEventType.Move -> {
                                            if (!firstChange.pressed) {
                                                val prev = lastMousePos
                                                lastMousePos = firstChange.position
                                                if (prev != null) {
                                                    val d = firstChange.position - prev
                                                    if (d.getDistance() > 0.1f) movePointer(d.x, d.y)
                                                }
                                            } else {
                                                lastMousePos = firstChange.position
                                            }
                                        }
                                        androidx.compose.ui.input.pointer.PointerEventType.Scroll -> {
                                            val scrollY = firstChange.scrollDelta.y
                                            if (scrollY != 0f) {
                                                sendScroll(if (scrollY < 0f) 1 else -1)
                                                firstChange.consume()
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(zoom, remoteWidth, remoteHeight) {
                        trackpadGestures(
                            scrollSpeed = prefs.scrollSpeed,
                            edgeScrollEnabled = prefs.edgeScrollZone,
                            onMove = { dx, dy -> movePointer(dx, dy) },
                            onTap = {
                                sendButton(KzMouseButton.LEFT, true)
                                sendButton(KzMouseButton.LEFT, false)
                                consumeOneShotKeys()
                            },
                            onTwoFingerTap = {
                                sendButton(KzMouseButton.RIGHT, true)
                                sendButton(KzMouseButton.RIGHT, false)
                                consumeOneShotKeys()
                            },
                            onScrollUnits = { sendScrollUnits(it) },
                            onFlingScroll = { lastVelocityY ->
                                if (prefs.inertialScroll && kotlin.math.abs(lastVelocityY) > 3f) {
                                    sessionScope.launch {
                                        var vel = lastVelocityY
                                        val acc = ScrollWheelAccumulator()
                                        while (vel != 0f && state is SessionState.Connected) {
                                            val u = acc.consume(vel, prefs.scrollSpeed)
                                            if (u != 0) sendScrollUnits(u)
                                            delay(16)
                                            vel = inertialScrollDecayStep(vel)
                                        }
                                    }
                                }
                            },
                            onButton = { button, down ->
                                sendButton(button, down)
                                if (!down) consumeOneShotKeys()
                            },
                        )
                    },
            )
        }

        if (connected && !privacyCurtain) {
            val pointerScreen = controller.remoteToScreen(cursor().x, cursor().y)
                ?: Offset(-1000f, -1000f)
            // Perbarui kotak kursor untuk thread perekaman. SideEffect, bukan
            // assignment langsung di badan komposisi, supaya tidak menulis saat
            // recomposition dibatalkan.
            val recordingCursorBitmap = remoteCursor?.bitmap
            val recordingHotX = remoteCursor?.hotX ?: 0
            val recordingHotY = remoteCursor?.hotY ?: 0
            SideEffect {
                pointerBox.x = cursorX
                pointerBox.y = cursorY
                pointerBox.remoteWidth = remoteWidth
                pointerBox.remoteHeight = remoteHeight
                pointerBox.cursor = recordingCursorBitmap
                pointerBox.hotX = recordingHotX
                pointerBox.hotY = recordingHotY
                pointerBox.visible = pointerVisible
            }
            val recordingActive = recordingState is RemoteRecordingState.Recording
            val recordingBusy = recordingState is RemoteRecordingState.Preparing ||
                recordingState is RemoteRecordingState.Stopping
            val recordingLabel = when (recordingState) {
                RemoteRecordingState.Preparing -> kz("Menyiapkan…", "Preparing…")
                RemoteRecordingState.Stopping -> kz("Menyimpan…", "Saving…")
                RemoteRecordingState.Recording -> kz("Hentikan & simpan", "Stop & save")
                else -> kz("Mulai rekam PC", "Record PC")
            }
            val recordingStatus = when (val current = recordingState) {
                RemoteRecordingState.Idle -> null
                RemoteRecordingState.Preparing -> kz("Menyiapkan encoder video…", "Preparing the video encoder…")
                RemoteRecordingState.Recording -> kz("Merekam desktop PC + audio PC", "Recording the PC desktop + PC audio")
                RemoteRecordingState.Stopping -> kz("Menutup file MP4…", "Finalizing the MP4…")
                is RemoteRecordingState.Saved -> kz("Tersimpan: {0}", "Saved: {0}", current.location)
                is RemoteRecordingState.Failed -> when (current.reason) {
                    "secure" -> kz("Proteksi layar aktif; perekaman diblokir.", "Screen-capture protection is on; recording is blocked.")
                    "no_frame" -> kz("Frame desktop PC belum siap. Tunggu sampai layar tampil.", "The PC desktop frame is not ready yet. Wait for the remote screen.")
                    "save" -> kz("MP4 gagal disimpan ke penyimpanan lokal.", "Couldn't save the MP4 to local storage.")
                    else -> kz("Perekaman gagal. Coba lagi; sebagian perangkat mungkin tidak mendukung encoder ini.", "Recording failed. Retry; this device may not support the required encoder.")
                }
            }
            SessionControls(
                deviceId = profile.id,
                hostLabel = activeProfile.label ?: "${activeProfile.host}:${activeProfile.port}",
                statusText = kz("Terhubung", "Connected"),
                remoteSize = if (telemetry.width > 0) "${telemetry.width} x ${telemetry.height}" else kz("menunggu server", "waiting for server"),
                zoomPercent = (zoom * 100).roundToInt(),
                remoteDpi = remoteDpi,
                telemetry = telemetry,
                pointerScreen = pointerScreen,
                pointerVisible = pointerVisible && InputMode.entries[inputMode] == InputMode.TRACKPAD &&
                    !extMouseConnected,
                remoteCursor = remoteCursor,
                zoom = zoom,
                inputMode = InputMode.entries[inputMode],
                externalInputDeviceConnected = !isPcConnectMode &&
                    (context as? KzDeskSessionActivity)?.externalInputDeviceConnected == true,
                onInputModeChange = { mode ->
                    inputMode = mode.ordinal
                    prefs.inputMode = mode
                    if (mode == InputMode.DIRECT) controller.setImeVisible(keyboardShown)
                },
                keys = hudKeys,
                onKeysChange = { setHudKeys(it) },
                latchedKeyIds = latchedKeys.filterValues { it }.keys.toSet(),
                onKeyEdited = { updated ->
                    val old = hudKeys.firstOrNull { it.id == updated.id }
                    if (old != null && latchedKeys[old.id] == true &&
                        (old.kind != updated.kind || old.action != updated.action)
                    ) {
                        runHudKey(old, false)
                        latchedKeys.remove(old.id)
                        oneShotKeys.remove(old.id)
                        syncHudModifierState()
                    }
                    setHudKeys(hudKeys.map { if (it.id == updated.id) updated else it })
                },
                onDeleteKey = { deleted ->
                    val old = hudKeys.firstOrNull { it.id == deleted.id } ?: deleted
                    if (latchedKeys[old.id] == true) runHudKey(old, false)
                    latchedKeys.remove(old.id)
                    oneShotKeys.remove(old.id)
                    syncHudModifierState()
                    setHudKeys(hudKeys.filterNot { it.id == deleted.id })
                },
                mappingMode = mappingMode,
                onMappingModeChange = {
                    mappingMode = it
                    if (it) notice.show(kzNow("Geser tombol ke posisi yang kamu mau", "Drag the buttons where you want them"))
                },
                onPhase = { key, phase -> handleHudPhase(key, phase) },
                onScrollUnits = { sendScrollUnits(it) },
                // Joystick HUD memakai jalur gerak pointer yang sama dengan
                // trackpad dan gamepad fisik: 18 unit per defleksi penuh.
                stickModeProvider = stickModeProvider,
                onStickModeChange = onStickModeChange,
                onStickKeysMode = onStickKeysMode,
                onStickPointer = { x, y ->
                    if (x != 0f || y != 0f) {
                        movePointer(x * HUD_STICK_POINTER_UNITS, y * HUD_STICK_POINTER_UNITS)
                    }
                },
                onStickAxis = { x, y ->
                    // Mode POINTER memakai jalur gerak pointer yang sama dengan
                    // trackpad dan gamepad fisik. Mode WASD/Panah diserahkan ke
                    // mapper inti lewat onStickKeys supaya joystick HUD dan
                    // gamepad fisik berperilaku identik.
                    when (stickModeProvider()) {
                        KzStickMode.POINTER ->
                            if (x != 0f || y != 0f) {
                                movePointer(x * HUD_STICK_POINTER_UNITS, y * HUD_STICK_POINTER_UNITS)
                            }
                        // Sengaja tanpa syarat "bukan nol": saat stick kembali ke
                        // tengah HUD mengirim (0,0), dan itu justru yang dipakai
                        // mapper untuk melepas tombol arah.
                        else -> onStickKeys(x, y)
                    }
                },
                onZoomIn = { controller.zoomIn() },
                onZoomOut = { controller.zoomOut() },
                onFit = { controller.fitToScreen() },
                onZoomActual = { controller.applyZoom(1f) },
                onZoomScale = { percent -> controller.applyZoom(percent / 100f) },
                onRemoteDpiChange = { scale ->
                    if (scale in DisplayPrefs.remoteDpiOptions) {
                        remoteDpi = scale
                        DisplayPrefs.setRemoteDpi(context, profile.id, scale)
                    }
                },
                onDisplayRefreshPreferenceChange = onDisplayRefreshPreferenceChange,
                recordingLabel = recordingLabel,
                recordingStatus = recordingStatus,
                recordingActionEnabled = !recordingBusy,
                recordingActive = recordingActive,
                onToggleRecording = {
                    when {
                        recordingActive -> remoteRecorder.stop()
                        recordingBusy -> Unit
                        else -> {
                            recordingState = RemoteRecordingState.Preparing
                            if (!remoteRecorder.start { recordingState = it }) {
                                recordingState = RemoteRecordingState.Failed("failed")
                            }
                        }
                    }
                },
                onScreenshot = {
                    val act = context as? Activity ?: return@SessionControls
                    sessionScope.launch {
                        val uri: Uri = controller.captureScreenshot(act) ?: run {
                            notice.show(kzNow("Screenshot gagal atau surface belum siap", "Screenshot failed or surface is not ready"))
                            return@launch
                        }
                        notice.show(
                            kzNow(
                                "Screenshot disimpan ke Pictures/KzDesk",
                                "Screenshot saved to Pictures/KzDesk",
                            ),
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        act.startActivity(
                            Intent.createChooser(
                                send,
                                kzNow("Bagikan screenshot", "Share screenshot"),
                            ),
                        )
                    }
                },
                onDisconnect = {
                    // Jangan tandai "putus oleh user" di sini: dialog konfirmasi
                    // bisa dibatalkan. Kalau flag-nya nyala padahal user batal,
                    // auto-reconnect mati permanen untuk putusan koneksi asli.
                    confirmDisconnect = true
                },
                onGoHome = {
                    // Ke beranda TANPA memutus sesi: activity hanya dipindah ke
                    // belakang, koneksi + service tetap hidup; buka lagi dari
                    // kartu "Sesi aktif" di beranda atau notifikasi.
                    (context as? Activity)?.moveTaskToBack(true)
                },
                onPointerVisibilityChange = { pointerVisible = it },
                onResetCluster = {
                    // Kembalikan tombol HUD ke set bawaan (klik kiri/kanan/
                    // tengah, scroll naik/turun, ganti mode input).
                    releaseLatchedKeys()
                    setHudKeys(HudKey.defaults(prefs.hudButtonSize))
                },
                onRotationChange = { mode ->
                    (context as? Activity)?.requestedOrientation = when (mode) {
                        "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                    }
                    // Rotasi mengubah bentuk layar: ukuran dikirim ulang oleh
                    // efek yang memantau ukuran area gambar.
                    notice.show(kzNow("Orientasi diubah ke {0}", "Orientation set to {0}", mode))
                },
                onResolutionChange = { preset ->
                    val size = when (preset) {
                        DisplayPrefs.AUTOMATIC ->
                            SmartResolution.parse(
                                SmartResolution.forViewport(viewport.width, viewport.height),
                            )

                        DisplayPrefs.FOLLOW -> viewport.width to viewport.height
                        else -> parseSize(preset)
                    }
                    val target = size?.takeIf { it.first > 0 && it.second > 0 }
                        ?.let { normalizedRemoteResolution(it.first, it.second) }
                    if (target == null) {
                        notice.show(kzNow("Resolusi tidak valid untuk viewport saat ini", "Resolution is invalid for the current viewport"))
                    } else if (remoteResolutionMatches(target, telemetry.width, telemetry.height)) {
                        controller.fitToScreen()
                        notice.show(kzNow("Resolusi terkonfirmasi: {0} x {1}", "Resolution confirmed: {0} x {1}", target.first, target.second))
                    } else if (isPcConnectMode || isConsoleAdminMode) {
                        controller.fitToScreen()
                        notice.show(
                            kzNow(
                                "Mode Konsol (/admin) mengikuti resolusi monitor host ({0} x {1})",
                                "Console (/admin) mode follows the host monitor resolution ({0} x {1})",
                                telemetry.width,
                                telemetry.height,
                            ),
                        )
                    } else {
                        sessionScope.launch {
                            var queued = false
                            for (attempt in 0 until 6) {
                                if (manager.resizeRemote(target.first, target.second, remoteDpi)) {
                                    queued = true
                                    break
                                }
                                delay(350)
                            }
                            val confirmed = queued && manager.awaitRemoteResolution(target)
                            if (confirmed) {
                                controller.fitToScreen()
                                notice.show(kzNow("Resolusi terkonfirmasi: {0} x {1}", "Resolution confirmed: {0} x {1}", target.first, target.second))
                            } else if (!RdpOptions.of(context, profile.id).dynamicResolution) {
                                pendingResize = target
                                applyingResolution = true
                                manager.disconnect()
                                notice.show(kzNow("Ukuran belum berubah; menyambung ulang dengan resolusi pilihan", "Size not confirmed; reconnecting with the selected resolution"))
                            } else {
                                controller.fitToScreen()
                                notice.show(
                                    kzNow(
                                        "Resolusi {0} x {1} disimpan (akan diterapkan saat sesi berikutnya bila kanal DISP ditolak host)",
                                        "Resolution {0} x {1} saved (will apply on next session if host ignores DISP)",
                                        target.first,
                                        target.second,
                                    ),
                                )
                            }
                        }
                    }
                },
                onOpenKeyboard = { openKeyboard() },
                lastClipboard = lastRemoteClipboard,
                clipboardSyncEnabled = clipboardSyncEnabled,
                // Sesi lain: buka home tanpa memutus sesi ini (keep-alive
                // default menyala, jadi sesi tetap jalan di latar).
                onOpenHome = {
                    // Sesi tetap jalan di latar, tetapi tidak auto-resume begitu
                    // pengguna sengaja memilih kembali ke Home.
                    (context as? KzDeskSessionActivity)?.markIntentionalHomeNavigation()
                    controller.blurInput()
                    runCatching {
                        context.startActivity(
                            android.content.Intent(context, KzDeskHomeActivity::class.java)
                                .putExtra(EXTRA_SKIP_AUTO_RESUME, true)
                                .addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                                ),
                        )
                    }
                },
                // Teks bebas (unicode) — untuk password/URL/karakter yang
                // tidak ada di pemetaan tombol HUD.
                onSendText = { manager.sendText(it) },
                onSendRemoteClipboardText = { text ->
                    // Kiriman manual juga masuk riwayat clipboard.
                    ClipboardHistory.record(context, text)
                    clipboardSyncEnabled && manager.sendClipboardData(text)
                },
                activeUsername = activeProfile.username,
                consoleAdminMode = consoleAdminMode,
                onSwitchConsoleMode = { useConsole ->
                    consoleAdminMode = useConsole
                    val currentUser = activeProfile.username.orEmpty()
                    val nextUser = when {
                        useConsole && (currentUser.isBlank() || currentUser.equals("KzDesk", ignoreCase = true)) -> "runneradmin"
                        !useConsole && currentUser.equals("runneradmin", ignoreCase = true) -> "KzDesk"
                        else -> activeProfile.username
                    }
                    activeProfile = activeProfile.copy(username = nextUser)
                    runCatching {
                        RdpOptions.of(context, activeProfile.id)
                            .copy(consoleAdmin = useConsole)
                            .write(context, activeProfile.id)
                    }
                    applyingResolution = true
                    manager.disconnect()
                    notice.show(
                        if (useConsole) {
                            kzNow("Beralih ke Konsol Runner ({0} · /admin)...", "Switching to Runner Console ({0} · /admin)...", nextUser ?: "admin")
                        } else {
                            kzNow("Beralih ke Sesi Virtual ({0} · Multi-User)...", "Switching to Virtual Session ({0} · Multi-User)...", nextUser ?: "RDP")
                        },
                    )
                },
                onSwitchUserSession = { newUser, newDomain, newPass ->
                    val wantConsole = newUser.equals("runneradmin", ignoreCase = true) ||
                        newUser.equals("Administrator", ignoreCase = true)
                    consoleAdminMode = wantConsole
                    runCatching {
                        RdpOptions.of(context, activeProfile.id)
                            .copy(consoleAdmin = wantConsole)
                            .write(context, activeProfile.id)
                    }
                    activeProfile = activeProfile.copy(
                        username = newUser,
                        domain = newDomain ?: activeProfile.domain,
                        password = newPass ?: activeProfile.password,
                    )
                    applyingResolution = true
                    manager.disconnect()
                    notice.show(
                        kzNow(
                            "Beralih sesi ke user {0} ({1})...",
                            "Switching session to user {0} ({1})...",
                            newUser,
                            if (wantConsole) "/admin" else "Multi-User",
                        ),
                    )
                },
                swapMouseButtons = swapMouseButtons,
                onSwapMouseButtonsChange = { swapped ->
                    swapMouseButtons = swapped
                    prefs.swapMouseButtons = swapped
                    notice.show(
                        if (swapped) kzNow("Klik Kiri ⇄ Kanan ditukar", "Left ⇄ Right click swapped")
                        else kzNow("Klik Kiri & Kanan normal", "Left & Right click normal"),
                    )
                },
                onLockRemotePc = {
                    if (manager.lockRemoteSession()) {
                        notice.show(kzNow("Layar PC dikunci (Win+L dikirim)", "PC screen locked (Win+L sent)"))
                    } else {
                        notice.show(kzNow("Gagal mengirim perintah kunci PC", "Failed to send PC lock command"))
                    }
                },
                onActivatePrivacyCurtain = {
                    privacyCurtain = true
                },
                onGyroMouseChanged = { enabled ->
                    (context as? KzDeskSessionActivity)?.setGyroMouseActive(enabled && connected)
                },
                onOverlayActiveChange = { controlsOverlayOpen = it },
                onRegisterBackHandler = { controlsBackHandler = it },
                coreInfo = coreInfo,
                notice = notice,
            )

            // Pesan app sendiri (bukan Toast bawaan Android) — satu bahasa
            // visual dengan panel, ikut tema.
            KzNoticeHost(
                state = notice,
                modifier = Modifier.align(Alignment.TopCenter),
            )

        }

        if (connected && privacyCurtain) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFA040507))
                    .zIndex(32f)
                    .clickable { privacyCurtain = false }
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        kz("LAYAR STEALTH / PRIVACY CURTAIN AKTIF", "STEALTH / PRIVACY CURTAIN ACTIVE"),
                        color = Color(0xFF9BA2AC),
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        kz(
                            "Sesi ke {0} tetap berjalan aman di latar. Ketuk layar untuk membuka kembali tampilan.",
                            "Session to {0} stays running safely. Tap anywhere to restore the display.",
                            profile.label ?: "${profile.host}:${profile.port}",
                        ),
                        color = Color(0xFF5D6675),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (state is SessionState.Connecting || state is SessionState.Authenticating) {
            if (reconnecting && everConnected) {
                ReconnectingOverlay(
                    attempt = reconnectAttempt,
                    waitingForNativeRelease = waitingForNativeRelease,
                    onStop = { confirmCancelConnect = true },
                )
            } else {
                ConnectingScreen(
                    wall = wall,
                    deviceLabel = profile.label ?: "${profile.host}:${profile.port}",
                    stage = stage,
                    onCancel = { confirmCancelConnect = true },
                )
            }
        }

        if (reconnecting && everConnected &&
            (state is SessionState.Disconnected || state is SessionState.Error)
        ) {
            ReconnectingOverlay(
                attempt = reconnectAttempt,
                waitingForNativeRelease = waitingForNativeRelease,
                onStop = { confirmCancelConnect = true },
            )
        }

        if (state is SessionState.Disconnected && !applyingResolution && !reconnecting && !userDisconnect) {
            DisconnectedScreen(
                wall = wall,
                deviceLabel = activeProfile.label ?: activeProfile.host,
                detail = (state as SessionState.Disconnected).detail,
                onReconnect = {
                    boundInstance = 0L
                    manager.connect(activeProfile)
                },
                onExit = ::exitSession,
            )
        }

        if ((state is SessionState.Disconnected || state is SessionState.Disconnecting) && applyingResolution) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    kz("Menerapkan tampilan / sesi baru...", "Applying new display / session..."),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    val err = state as? SessionState.Error
    val active = certPrompt != null || nlaPrompt != null

    certPrompt?.let { p ->
        CertificateDialog(
            info = p.info,
            oldFingerprint = p.oldFingerprint,
            onReply = { code ->
                p.reply(code)
                certPrompt = null
            },
            onTrustRemember = {
                trustStore.trust(p.info.host, p.info.port, p.info.fingerprint)
                p.reply(CertificateInfo.VERIFY_ACCEPT)
                certPrompt = null
            },
        )
    }
    if (!active) nlaPrompt?.let { p ->
        NlaDialog(p) { u, d, pw ->
            p.reply(u, d, pw)
            nlaPrompt = null
        }
    }
    if (!active && !reconnecting && !applyingResolution && !userDisconnect) err?.let { e ->
        KzOverlay(
            title = kz("Tidak dapat tersambung", "Unable to connect"),
            onDismiss = { exitSession() },
        ) {
            // Penjelasan yang bisa ditindak dulu, pesan mentah di bawahnya
            // supaya laporan bug tetap punya isi teknis.
            val hint = RdpErrors.hint(e.code, e.message)
            if (hint != null) {
                Text(kz(hint.first, hint.second), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    e.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(e.message, style = MaterialTheme.typography.bodyMedium)
            }
            // Baris teknis selalu tampil: kode kategori KzDesk + ID koneksi.
            // Rujukannya sama persis dengan baris "CM: Kode 0x… · ID koneksi …"
            // di log, jadi laporan bug bisa dicocokkan tanpa menebak.
            Spacer(Modifier.height(8.dp))
            Text(
                kz("Kode", "Code") + " " + RdpFailure.formatCode(e.errorCode) +
                    "   ·   " + kz("ID koneksi", "Connection ID") + " " +
                    RdpFailure.formatConnectionId(e.connectionId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    text = kz("Detail", "Details"),
                    onClick = { showLog = true },
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    text = kz("Reset resolusi", "Reset resolution"),
                    onClick = {
                        DisplayPrefs.setResolution(context, activeProfile.id, DisplayPrefs.AUTOMATIC)
                        boundInstance = 0L
                        manager.connect(activeProfile)
                    },
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            KzPillButton(
                text = kz("Coba lagi", "Try again"),
                onClick = {
                    showLog = false
                    boundInstance = 0L
                    manager.connect(activeProfile)
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            KzPillButton(
                text = kz("Tutup", "Close"),
                onClick = { exitSession() },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (!active && err != null && showLog && !userDisconnect) {
        KzOverlay(title = kz("Log koneksi", "Connection log"), onDismiss = { showLog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ConnectionLog.last(60).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            KzPillButton(
                text = kz("Tutup", "Close"),
                onClick = { showLog = false },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (!active && err == null && confirmDisconnect) {
        KzDialog(
            title = kz("Putuskan sesi?", "Disconnect session?"),
            body = kz("Putuskan sesi sekarang dan kembali ke beranda?", "Disconnect the session now and return to home?"),
            confirmLabel = kz("Putuskan", "Disconnect"),
            onConfirm = {
                confirmDisconnect = false
                userDisconnect = true
                reconnecting = false
                (context as? KzDeskSessionActivity)?.markExplicitDisconnect(profile.id)
                appPrefs.clearAutoResumeSession(profile.id)
                releaseLatchedKeys()
                if (context.let { runCatching { AppPrefs(it).autoLockRemoteOnLeave }.getOrDefault(false) }) {
                    manager.lockRemoteSession()
                }
                manager.disconnect()
                exitSession()
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmDisconnect = false },
        )
    }
    if (!active && confirmCancelConnect) {
        KzDialog(
            title = kz("Batalkan koneksi?", "Cancel connection?"),
            body = kz(
                "Hentikan proses menyambung ke server dan kembali ke beranda?",
                "Stop connecting to the server and return to home?",
            ),
            confirmLabel = kz("Batalkan", "Cancel connection"),
            onConfirm = {
                confirmCancelConnect = false
                userDisconnect = true
                reconnecting = false
                appPrefs.clearAutoResumeSession(profile.id)
                manager.cancelConnection()
                exitSession()
            },
            dismissLabel = kz("Lanjutkan", "Keep trying"),
            onDismiss = { confirmCancelConnect = false },
        )
    }
}

// =============================================================
// Overlay koneksi
// =============================================================

private fun stepIndex(stage: SessionManager.Stage): Int = when (stage) {
    SessionManager.Stage.IDLE -> 0
    SessionManager.Stage.PROBE -> 1
    SessionManager.Stage.HANDSHAKE -> 2
    SessionManager.Stage.AUTH -> 3
    SessionManager.Stage.READY -> 4
}

private val connectSteps = listOf(
    "Menyiapkan sesi" to "Preparing session",
    "Memeriksa jaringan" to "Checking network",
    "Security connect" to "Security connect",
    "Autentikasi (NLA)" to "Authentication (NLA)",
    "Menyiapkan desktop" to "Preparing desktop",
)

@Composable
private fun ReconnectingOverlay(
    attempt: Int,
    waitingForNativeRelease: Boolean,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(18.dp)
            .zIndex(20f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KzSpinner(size = 24.dp, color = MaterialTheme.colorScheme.primary)
        Text(
            kz("Jaringan terputus sementara", "Temporary network interruption"),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            kz(
                if (waitingForNativeRelease) {
                    "Menunggu sesi native menutup dengan aman; percobaan {0} akan dilanjutkan setelahnya."
                } else {
                    "Sesi tetap terbuka. Menunggu jaringan lalu mencoba lagi (percobaan {0})."
                },
                if (waitingForNativeRelease) {
                    "Waiting for the native session to close safely; attempt {0} will continue afterward."
                } else {
                    "Session is kept open. Waiting for the network and retrying (attempt {0})."
                },
                attempt,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        KzPillButton(kz("Berhenti mencoba", "Stop retrying"), onStop, primary = false, compact = true)
    }
}

@Composable
private fun ConnectingScreen(
    wall: KzWall,
    deviceLabel: String,
    stage: SessionManager.Stage,
    onCancel: () -> Unit,
) {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    val active = stepIndex(stage)

    Box(Modifier.fillMaxSize()) {
        // Blur layar penuh berbiaya tinggi di HP kelas menengah -> pakai dim saja.
        KzWallpaper(wall, blurRadius = 0.dp, dim = 0.35f)
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                deviceLabel,
                color = Color.White.copy(alpha = 0.96f),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${elapsed}s",
                color = Color.White.copy(alpha = 0.76f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                connectSteps.forEachIndexed { index, (title, titleEn) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                index < active -> androidx.compose.material3.Icon(
                                    KzIcons.Check,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(14.dp),
                                )
                                index == active -> KzSpinner(
                                    modifier = Modifier.size(14.dp),
                                    size = 14.dp,
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                                else -> Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.28f)),
                                )
                            }
                        }
                        Text(
                            kz(title, titleEn),
                            color = Color.White.copy(alpha = if (index <= active) 0.96f else 0.72f),
                            fontSize = 14.sp,
                            fontWeight = if (index == active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            KzPillButton(kz("Batalkan", "Cancel"), onCancel, primary = false, compact = true)
        }
    }
}

@Composable
private fun DisconnectedScreen(
    wall: KzWall,
    deviceLabel: String,
    detail: String?,
    onReconnect: () -> Unit,
    onExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        KzWallpaper(wall, blurRadius = 0.dp, dim = 0.40f)
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                kz("Sesi terputus", "Session ended"),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(deviceLabel, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            val disconnectHint = RdpErrors.disconnectedHint(detail)
            Text(
                disconnectHint?.let { kz(it.first, it.second) }
                    ?: kz(
                        "Sesi remote berakhir; penyebab spesifik tidak diberikan.",
                        "The remote session ended; no specific cause was provided.",
                    ),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(20.dp))
            KzPillButton(kz("Sambungkan lagi", "Reconnect"), onReconnect, compact = true)
            Spacer(Modifier.height(8.dp))
            KzPillButton(kz("Kembali ke home", "Back to home"), onExit, primary = false, compact = true)
        }
    }
}

// =============================================================
// Gesture trackpad
// =============================================================

private enum class TrackpadGestureMode { SINGLE_PENDING, MOVE, LEFT_DRAG, TWO_PENDING, SCROLL, RIGHT_DRAG, CANCELLED }

/**
 * Trackpad: satu jari geser = pointer, tap = klik kiri, tahan diam lalu geser
 * = drag kiri. Dua jari geser = scroll, tap = klik kanan, tahan lalu geser =
 * drag kanan. Perubahan jumlah jari merebase centroid agar two-finger tap tidak
 * salah dibaca sebagai swipe. Button UP selalu dikirim saat gesture dibatalkan.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.trackpadGestures(
    scrollSpeed: Float,
    edgeScrollEnabled: Boolean = false,
    onMove: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onTwoFingerTap: () -> Unit,
    onScrollUnits: (Int) -> Unit,
    onFlingScroll: (Float) -> Unit = {},
    onButton: (KzMouseButton, Boolean) -> Unit,
) {
    awaitEachGesture {
        val slop = viewConfiguration.touchSlop
        val holdTimeout = viewConfiguration.longPressTimeoutMillis
        val edgeZonePx = 28.dp.toPx()
        val first = awaitFirstDown(requireUnconsumed = false)
        first.consume()

        val startedInEdgeScroll = isInRightEdgeScrollZone(
            touchX = first.position.x,
            viewportWidthPx = size.width.toFloat(),
            zoneWidthPx = edgeZonePx,
            enabled = edgeScrollEnabled,
        )
        var mode = if (startedInEdgeScroll) TrackpadGestureMode.SCROLL else TrackpadGestureMode.SINGLE_PENDING
        var activePointers = 1
        var maxPointers = 1
        var travel = 0f
        var pendingMove = Offset.Zero
        val scrollAccumulator = ScrollWheelAccumulator()
        var lastCentroid = first.position
        var lastScrollDeltaY = 0f
        var deadline = first.uptimeMillis + holdTimeout
        var holdEligible = !startedInEdgeScroll
        var tapEligible = !startedInEdgeScroll
        var heldButton: KzMouseButton? = null

        fun emitScroll(deltaY: Float) {
            lastScrollDeltaY = deltaY
            scrollAccumulator.consume(deltaY, scrollSpeed)
                .takeIf { it != 0 }
                ?.let(onScrollUnits)
        }

        fun beginLongPress() {
            when {
                mode == TrackpadGestureMode.SINGLE_PENDING && activePointers == 1 -> {
                    heldButton = KzMouseButton.LEFT
                    onButton(KzMouseButton.LEFT, true)
                    mode = TrackpadGestureMode.LEFT_DRAG
                    tapEligible = false
                }
                mode == TrackpadGestureMode.TWO_PENDING && activePointers == 2 -> {
                    heldButton = KzMouseButton.RIGHT
                    onButton(KzMouseButton.RIGHT, true)
                    mode = TrackpadGestureMode.RIGHT_DRAG
                    tapEligible = false
                }
            }
        }

        try {
            while (true) {
                val waitingForHold = holdEligible &&
                    (mode == TrackpadGestureMode.SINGLE_PENDING || mode == TrackpadGestureMode.TWO_PENDING)
                val remaining = deadline - SystemClock.uptimeMillis()
                if (waitingForHold && remaining <= 0L) {
                    beginLongPress()
                    continue
                }
                val event = if (waitingForHold) {
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    beginLongPress()
                    continue
                }

                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    event.changes.forEach { it.consume() }
                    break
                }
                val pointerCount = pressed.size
                val centroid = pressed.fold(Offset.Zero) { acc, change -> acc + change.position } /
                    pointerCount.toFloat()
                maxPointers = maxOf(maxPointers, pointerCount)

                if (pointerCount >= 3) {
                    heldButton?.let { onButton(it, false) }
                    heldButton = null
                    mode = TrackpadGestureMode.CANCELLED
                    tapEligible = false
                    holdEligible = false
                    activePointers = pointerCount
                    lastCentroid = centroid
                    event.changes.forEach { it.consume() }
                    continue
                }

                if (pointerCount != activePointers) {
                    // Jangan hitung loncatan centroid saat finger count berubah.
                    if (pointerCount == 2) {
                        if (mode == TrackpadGestureMode.SINGLE_PENDING || mode == TrackpadGestureMode.MOVE) {
                            val hadMoved = mode == TrackpadGestureMode.MOVE
                            mode = TrackpadGestureMode.TWO_PENDING
                            deadline = SystemClock.uptimeMillis() + holdTimeout
                            holdEligible = true
                            travel = 0f
                            pendingMove = Offset.Zero
                            scrollAccumulator.reset()
                            if (hadMoved) tapEligible = false
                        }
                    } else if (pointerCount == 1 && maxPointers >= 2 && mode == TrackpadGestureMode.TWO_PENDING) {
                        // Masih boleh menyelesaikan two-finger tap saat jari kedua lepas,
                        // tetapi jangan mengubah satu jari tersisa menjadi right-hold.
                        holdEligible = false
                    }
                    activePointers = pointerCount
                    lastCentroid = centroid
                    event.changes.forEach { it.consume() }
                    continue
                }

                val delta = centroid - lastCentroid
                lastCentroid = centroid
                val distance = delta.getDistance()
                if (distance > 0.15f) {
                    travel += distance
                    when (mode) {
                        TrackpadGestureMode.SINGLE_PENDING -> {
                            pendingMove += delta
                            if (travel > slop) {
                                mode = TrackpadGestureMode.MOVE
                                tapEligible = false
                                onMove(pendingMove.x, pendingMove.y)
                                pendingMove = Offset.Zero
                            }
                        }
                        TrackpadGestureMode.MOVE -> onMove(delta.x, delta.y)
                        TrackpadGestureMode.TWO_PENDING -> {
                            if (activePointers < 2) {
                                // Setelah satu jari terangkat, jari tersisa tidak
                                // boleh berubah menjadi scroll satu-jari.
                                if (travel > slop) tapEligible = false
                            } else if (travel > slop) {
                                mode = TrackpadGestureMode.SCROLL
                                tapEligible = false
                                emitScroll(delta.y)
                            }
                        }
                        TrackpadGestureMode.SCROLL -> {
                            emitScroll(delta.y)
                        }
                        TrackpadGestureMode.LEFT_DRAG, TrackpadGestureMode.RIGHT_DRAG ->
                            onMove(delta.x, delta.y)
                        TrackpadGestureMode.CANCELLED -> Unit
                    }
                }
                event.changes.forEach { it.consume() }
            }

            if (mode == TrackpadGestureMode.SCROLL && kotlin.math.abs(lastScrollDeltaY) > 2f) {
                onFlingScroll(lastScrollDeltaY)
            } else if (heldButton == null && tapEligible && travel <= slop) {
                when {
                    maxPointers >= 2 -> onTwoFingerTap()
                    maxPointers == 1 -> onTap()
                }
            }
        } finally {
            heldButton?.let { onButton(it, false) }
        }
    }
}

private fun rotationFor(context: Context, deviceId: String): String =
    DisplayPrefs.rotation(context, deviceId)

// =============================================================
// Dialog sertifikat & NLA (sama seperti sebelumnya, tanpa jalur cloud)
// =============================================================

@Composable
private fun CertificateDialog(
    info: CertificateInfo,
    oldFingerprint: String?,
    onReply: (Int) -> Unit,
    onTrustRemember: () -> Unit,
) {
    var showCertificateDetails by remember(info.host, info.port, info.fingerprint) { mutableStateOf(false) }
    KzOverlay(
        title = if (info.isChanged) {
            kz("Sertifikat server berubah", "Server certificate changed")
        } else {
            kz("Percaya sertifikat server?", "Trust server certificate?")
        },
        onDismiss = null,
    ) {
        Text("${info.host}:${info.port}", style = MaterialTheme.typography.bodyMedium)
        if (info.isGateway) {
            Text(kz("Jenis: RDP Gateway", "Type: RDP Gateway"), style = MaterialTheme.typography.bodySmall)
        }
        if (info.isMismatch) {
            Text(
                kz(
                    "PERINGATAN: nama sertifikat tidak cocok dengan host",
                    "WARNING: certificate name does not match the host",
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (info.isChanged) {
            Text(
                kz(
                    "PERINGATAN: sertifikat berubah dari yang pernah diterima.",
                    "WARNING: certificate changed from the one accepted before.",
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (oldFingerprint != null && oldFingerprint != info.fingerprint) {
            Text(
                kz(
                    "PERINGATAN: sertifikat BERUBAH dari yang pernah kamu percaya.",
                    "WARNING: the certificate CHANGED from the one you trusted.",
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = "${if (showCertificateDetails) "▾" else "▸"} ${kz("Detail sertifikat", "Certificate details")}",
            modifier = Modifier.fillMaxWidth().clickable { showCertificateDetails = !showCertificateDetails },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (showCertificateDetails) {
            Text("Subject: ${info.subject}", style = MaterialTheme.typography.bodySmall)
            Text("Issuer: ${info.issuer}", style = MaterialTheme.typography.bodySmall)
            Text(kz("Fingerprint SHA-256:", "Fingerprint SHA-256:"), style = MaterialTheme.typography.bodySmall)
            Text(info.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (oldFingerprint != null && oldFingerprint != info.fingerprint) {
                Text(kz("Fingerprint tersimpan: {0}", "Stored fingerprint: {0}", oldFingerprint),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        KzPillButton(
            text = kz("Percaya & ingat", "Trust & remember"),
            onClick = onTrustRemember,
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KzPillButton(
                text = kz("Percaya (sekali)", "Trust (once)"),
                onClick = { onReply(CertificateInfo.VERIFY_ACCEPT) },
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            KzPillButton(
                text = kz("Tolak kali ini", "Deny this time"),
                onClick = { onReply(CertificateInfo.VERIFY_DENY) },
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NlaDialog(
    p: NlaPrompt,
    onReply: (String?, String?, String?) -> Unit,
) {
    var user by remember { mutableStateOf(p.user ?: "") }
    var domain by remember { mutableStateOf(p.domain ?: "") }
    var pass by remember { mutableStateOf("") }
    KzOverlay(title = kz("Masuk ke server", "Sign in to server"), onDismiss = null) {
        Text(
            kz("Server meminta kredensial (NLA/CredSSP).", "The server asks for credentials (NLA/CredSSP)."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KzField(value = user, onValueChange = { user = it }, label = kz("Username", "Username"))
        KzField(
            value = domain,
            onValueChange = { domain = it },
            label = kz("Domain (opsional)", "Domain (optional)"),
        )
        KzField(
            value = pass,
            onValueChange = { pass = it },
            label = kz("Password", "Password"),
            isPassword = true,
            imeAction = ImeAction.Done,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KzPillButton(
                text = kz("Masuk", "Sign in"),
                onClick = {
                    onReply(
                        user.ifBlank { null },
                        domain.ifBlank { null },
                        pass.ifBlank { null },
                    )
                },
                enabled = user.isNotEmpty() || pass.isNotEmpty(),
                compact = true,
                modifier = Modifier.weight(1f),
            )
            KzPillButton(
                text = kz("Batal", "Cancel"),
                onClick = { onReply(null, null, null) },
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}


