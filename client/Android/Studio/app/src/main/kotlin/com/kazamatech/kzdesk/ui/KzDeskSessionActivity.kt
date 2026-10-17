package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.push.PushManager
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.util.Rational
import android.content.res.Configuration
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kazamatech.kzdesk.security.CrashLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.pcstream.KzGamepadState
import com.kazamatech.kzdesk.ui.input.KzGamepadActionMapper
import com.kazamatech.kzdesk.ui.input.KzPadLayout
import com.kazamatech.kzdesk.ui.input.KzStickMode
import com.kazamatech.kzdesk.ui.input.KzGamepadOutput
import com.kazamatech.kzdesk.ui.input.KzPadKey
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Build
import com.kazamatech.kzdesk.KzSessionService
import com.kazamatech.kzdesk.core.ConnectionLog
import com.kazamatech.kzdesk.core.ConnectionProfile
import com.kazamatech.kzdesk.core.RdpOptions
import com.kazamatech.kzdesk.core.RefreshRatePolicy
import com.kazamatech.kzdesk.core.SessionManager
import com.kazamatech.kzdesk.core.SessionState
import com.freerdp.freerdpcore.utils.ClipboardImageProvider
import com.kazamatech.kzdesk.ui.components.KzNoticeBus
import com.kazamatech.kzdesk.ui.theme.KzDeskTheme
import com.kazamatech.kzdesk.ui.theme.KzThemeState
import com.kazamatech.kzdesk.ui.theme.kzDark
import java.util.concurrent.Executors

/**
 * M2 — activity sesi KzDesk (Compose): surface RDP + HUD.
 *
 * Satu sesi = satu aktivitas. Lifecycle:
 *  - onCreate : SessionManager + SessionSurfaceController dibuat, sink
 *    di-set SEBELUM connect, layar immersive, connect(profile)
 *  - onStop   : policy background (M1.2b) — disconnect otomatis setelah
 *    [BACKGROUND_DISCONNECT_DELAY_MS] kalau masih Connected
 *  - onDestroy: controller.release() + manager.release()
 *
 * Back: ditangani layar Compose ([KzDeskSessionScreen] memasang
 * [backHandler]); default = finish.
 */
class KzDeskSessionActivity : ComponentActivity() {

    /** Id profil sesi ini; dipakai untuk melepas diri dari registry sesi. */
    private var sessionId: String? = null
    private var sessionImeVisible = false
    private var userNavigatedHome = false
    private var explicitDisconnect = false

    /** Explicit navigation to Home should not leave a pending auto-resume marker. */
    fun markIntentionalHomeNavigation() {
        userNavigatedHome = true
        sessionId?.let { appPrefs?.clearAutoResumeSession(it) }
    }

    /** Prevent onStop from restoring a marker after an explicit Disconnect. */
    fun markExplicitDisconnect(profileId: String) {
        explicitDisconnect = true
        appPrefs?.clearAutoResumeSession(profileId)
    }

    /**
     * Label sesi (nama perangkat atau host:port) — dipakai ulang saat service
     * foreground di-ping dari [onStop]. Dulu label dikirim `null` di sini, jadi
     * begitu app pindah ke latar, teks notifikasi melompat balik ke
     * "KzDesk Remote" dan nama perangkatnya hilang.
     */
    private var sessionLabel: String? = null

    lateinit var manager: SessionManager
        private set
    lateinit var controller: SessionSurfaceController
        private set

    /**
     * Dipasang [KzDeskSessionScreen]: return `true` kalau back sudah
     * ditangani (dialog ditutup, panel disembunyikan, konfirmasi, dll).
     */
    var backHandler: (() -> Boolean)? = null
    var appPrefs: AppPrefs? = null

    var onExternalPointerDelta: ((Float, Float) -> Unit)? = null
    var onExternalScrollUnits: ((Int) -> Unit)? = null
    var onExternalMouseClick: ((KzMouseButton, Boolean) -> Unit)? = null

    private val externalInputState = mutableStateOf(false)
    /** True while a physical keyboard, mouse, gamepad or D-pad is connected. */
    val externalInputDeviceConnected: Boolean get() = externalInputState.value

    /** Mouse fisik (OTG/Bluetooth) terpasang — kursor lokal overlay disembunyikan
     *  karena kursor remote sudah terlihat di stream (anti "mouse double"). */
    private val externalMouseState = mutableStateOf(false)
    val externalMouseConnectedState: androidx.compose.runtime.State<Boolean> get() = externalMouseState
    private var inputManager: InputManager? = null
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshExternalInputDeviceState()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshExternalInputDeviceState()
        override fun onInputDeviceChanged(deviceId: Int) = refreshExternalInputDeviceState()
    }

    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    private var gyroActive = false
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!gyroActive || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val pitch = event.values.getOrElse(0) { 0f }
            val yaw = event.values.getOrElse(1) { 0f }
            if (kotlin.math.abs(pitch) > 0.045f || kotlin.math.abs(yaw) > 0.045f) {
                onExternalPointerDelta?.invoke(-yaw * 16f, -pitch * 16f)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val bgHandler = Handler(Looper.getMainLooper())
    private val bgDisconnect = Runnable {
        if (manager.state.value is SessionState.Connected) {
            if (appPrefs?.autoLockRemoteOnLeave == true) {
                manager.lockRemoteSession()
            }
            Log.i(TAG, "policy background: auto-disconnect (lewat ${BACKGROUND_DISCONNECT_DELAY_MS}ms)")
            explicitDisconnect = true
            sessionId?.let { appPrefs?.clearAutoResumeSession(it) }
            manager.disconnect()
        }
    }
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val clipboardReader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "KzDeskClipboardReader").apply { isDaemon = true }
    }
    private var clipboardSyncEnabled = true
    @Volatile
    private var lastSyncedPhoneClip: String? = null
    @Volatile
    private var lastSyncedPhoneImageHash: Int = 0
    @Volatile
    private var lastRemoteClipboardAtMs: Long = 0L
    private var pendingPermissionProfile: ConnectionProfile? = null
    private var lastInputFailureNoticeAt = 0L

    fun noteRemoteClipboardText(text: String) {
        lastSyncedPhoneClip = text
        lastRemoteClipboardAtMs = SystemClock.elapsedRealtime()
    }

    fun noteRemoteClipboardImage(bytes: ByteArray) {
        lastSyncedPhoneImageHash = bytes.contentHashCode()
        lastRemoteClipboardAtMs = SystemClock.elapsedRealtime()
    }

    private fun registerExternalInputMonitor() {
        val manager = getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        inputManager = manager
        manager.registerInputDeviceListener(inputDeviceListener, bgHandler)
        refreshExternalInputDeviceState()
    }

    private fun refreshExternalInputDeviceState() {
        val mouseConnected = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            if (device.isVirtual || !device.isExternal) return@any false
            (device.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        }
        if (externalMouseState.value != mouseConnected) {
            externalMouseState.value = mouseConnected
            ConnectionLog.add("SES: external mouse connected=$mouseConnected")
        }
        val connected = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            if (device.isVirtual || !device.isExternal) return@any false
            val sources = device.sources
            fun supports(mask: Int) = (sources and mask) == mask
            supports(InputDevice.SOURCE_KEYBOARD) ||
                supports(InputDevice.SOURCE_MOUSE) ||
                supports(InputDevice.SOURCE_GAMEPAD) ||
                supports(InputDevice.SOURCE_JOYSTICK) ||
                supports(InputDevice.SOURCE_DPAD)
        }
        if (externalInputState.value != connected) {
            externalInputState.value = connected
            ConnectionLog.add("SES: external input connected=$connected")
        }
    }

    fun setGyroMouseActive(enabled: Boolean) {
        if (gyroActive == enabled) return
        val sm = sensorManager ?: (getSystemService(SENSOR_SERVICE) as? SensorManager)?.also {
            sensorManager = it
            gyroSensor = it.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        }
        val sensor = gyroSensor
        if (enabled && sm != null && sensor != null) {
            gyroActive = sm.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm?.unregisterListener(gyroListener)
            gyroActive = false
        }
    }

    fun applyWindowSecurityFlags() {
        val secure = (appPrefs ?: AppPrefs(this)).flagSecure
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun syncPhoneClipboardToRemote() {
        if (!clipboardSyncEnabled || manager.state.value !is SessionState.Connected) return
        if (SystemClock.elapsedRealtime() - lastRemoteClipboardAtMs < 2_000L) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = try {
            cm.getPrimaryClip()
        } catch (e: RuntimeException) {
            Log.w(TAG, "clipboard read failed; session left running")
            return
        } ?: return
        val label = clip.description?.label?.toString()
        if (label == "rdp" || label == "rdp-clipboard" || label == "rdp-image" || clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        if (item.uri != ClipboardImageProvider.TEXT_CONTENT_URI) {
            ClipboardImageProvider.clearTextData()
        }
        try {
            clipboardReader.execute {
                try {
                    val uri = item.uri
                    val mime = uri?.let { runCatching { contentResolver.getType(it) }.getOrNull() }
                    if (uri != null && mime != null && mime.startsWith("image/") &&
                        uri != ClipboardImageProvider.CONTENT_URI
                    ) {
                        // Hanya kirim gambar clipboard HP yang ukurannya ringkas (<= 512 KB)
                        // agar screenshot layar HP beresolusi penuh tidak membanjiri kanal
                        // virtual RDP (CF_DIB) dan memicu putus sesi.
                        val bytes = runCatching {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                val buf = ByteArray(512 * 1024 + 1)
                                var total = 0
                                while (total < buf.size) {
                                    val r = stream.read(buf, total, buf.size - total)
                                    if (r <= 0) break
                                    total += r
                                }
                                if (total in 1..(512 * 1024)) buf.copyOf(total) else null
                            }
                        }.getOrNull()
                        if (bytes != null) {
                            val hash = bytes.contentHashCode()
                            if (hash != lastSyncedPhoneImageHash &&
                                !isFinishing && !isDestroyed &&
                                manager.state.value is SessionState.Connected
                            ) {
                                if (manager.sendClipboardImageData(bytes, mime)) {
                                    lastSyncedPhoneImageHash = hash
                                }
                            }
                        }
                        return@execute
                    }
                    val text = runCatching {
                        val direct = item.text?.toString()
                        if (direct != null) {
                            if (direct.length > 120_000) direct.substring(0, 120_000) else direct
                        } else if (uri != null && uri != ClipboardImageProvider.TEXT_CONTENT_URI) {
                            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                                val sb = StringBuilder()
                                val chBuf = CharArray(4096)
                                while (sb.length < 120_000) {
                                    val n = reader.read(chBuf, 0, minOf(chBuf.size, 120_000 - sb.length))
                                    if (n <= 0) break
                                    sb.append(chBuf, 0, n)
                                }
                                sb.toString()
                            }.orEmpty()
                        } else {
                            ""
                        }
                    }.getOrDefault("")
                    val prevNorm = lastSyncedPhoneClip?.replace("\r\n", "\n")
                    val curNorm = text.replace("\r\n", "\n")
                    if (text.isNotEmpty() && curNorm != prevNorm &&
                        !isFinishing && !isDestroyed &&
                        manager.state.value is SessionState.Connected
                    ) {
                        if (manager.sendClipboardData(text)) {
                            lastSyncedPhoneClip = text
                        } else {
                            ConnectionLog.add("SES: Android-to-remote clipboard send rejected; contents omitted")
                        }
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "clipboard processing failed; contents omitted")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Activity is already tearing down.
        }
    }

    fun reportInputDispatchFailure() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInputFailureNoticeAt < 2_000L) return
        lastInputFailureNoticeAt = now
        val showNotice = {
            if (!isFinishing && !isDestroyed) {
                KzNoticeBus.post(kzNow(
                    "Aksi belum masuk antrean input FreeRDP. Periksa koneksi dan coba lagi.",
                    "Input was not queued by FreeRDP. Check the connection and try again.",
                ))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) showNotice()
        else runOnUiThread { showNotice() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSystemBarsWatch()
        registerExternalInputMonitor()
        volumeControlStream = AudioManager.STREAM_MUSIC
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as? AudioManager
            if (am != null && am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
            }
        }

        try {
            ConnectionLog.add("SES: activity created")
            manager = SessionManager(applicationContext)
            controller = SessionSurfaceController(this)
            controller.onGraphicsInvalidated = manager::recordCoalescedGraphicsInvalidation
            controller.onInputDispatchFailure = { reportInputDispatchFailure() }
            // sink WAJIB sebelum connect — event grafik pertama tidak boleh hilang
            manager.setGraphicsSink(controller)
            ConnectionLog.add("SES: manager+controller ok, sink set")
        } catch (t: Throwable) {
            ConnectionLog.addThrowable("SES: FAIL inisialisasi", t)
            Log.e(TAG, "gagal inisialisasi sesi", t)
            CrashLog.note(this, "inisialisasi sesi gagal: ${t.stackTraceToString().take(12_000)}")
            KzNoticeBus.post("Gagal menyiapkan sesi: ${t.message}")
            finish()
            return
        }

        val profile = profileFromIntent(getIntent())
        if (profile == null) {
            ConnectionLog.add("SES: intent tanpa profil valid — keluar")
            Log.w(TAG, "intent tanpa profil valid — keluar")
            finish()
            return
        }
        ConnectionLog.add("SES: profil ok -> ${profile.host}:${profile.port}")
        sessionId = profile.id
        sessionLabel = profile.label ?: "${profile.host}:${profile.port}"
        requestPreferredRefreshRate(profile)

        hideSystemBars()

        // Clipboard Android -> remote hanya jika opsi profil menyala. Klip
        // remote diberi label asal "rdp" agar listener tidak memantulkannya.
        clipboardSyncEnabled = runCatching { RdpOptions.of(this, profile.id).clipboard }
            .getOrDefault(false)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        ClipboardHistory.load(this)
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            // Catat dulu ke riwayat (app di depan, clipboard boleh dibaca),
            // lalu teruskan ke sinkronisasi remote seperti biasa.
            runCatching {
                cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            }.getOrNull()?.let { ClipboardHistory.record(this, it) }
            syncPhoneClipboardToRemote()
        }
        clipListener = listener
        cm.addPrimaryClipChangedListener(listener)

        val prefs = AppPrefs(this)
        this.appPrefs = prefs
        applyWindowSecurityFlags()
        // Daftarkan sesi ini supaya home bisa menampilkan & memindahkan sesi
        // yang sedang hidup (multi-sesi).
        KzSessionRegistry.add(
            KzSessionRegistry.Live(
                id = profile.id,
                label = profile.label ?: "${profile.host}:${profile.port}",
                address = "${profile.host}:${profile.port}",
                open = {
                    // Intent lengkap (extras profil) dipakai ulang: kalau
                    // activity-nya masih hidup ini cuma membawa ke depan,
                    // kalau sudah mati sesinya bisa dibuka lagi dari awal.
                    val again = Intent(this, KzDeskSessionActivity::class.java)
                        .putExtras(intent)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(again)
                },
                kill = {
                    runOnUiThread {
                        markExplicitDisconnect(profile.id)
                        if (prefs.autoLockRemoteOnLeave) manager.lockRemoteSession()
                        manager.disconnect()
                        finish()
                    }
                },
                bitmapProvider = {
                    if (::controller.isInitialized) controller.peekBitmap() else null
                },
            ),
        )
        val deferConnectForPermission = requestRuntimePermissions(profile)
        // Di-hoist ke sini: di dalam lambda Compose `this` bisa berarti BoxScope,
        // bukan Context, sehingga SessionPrefs(this) tidak bisa dipakai langsung.
        val sessionPrefs = SessionPrefs(this)
        // Mode stik dibaca di sini supaya sesi yang langsung memakai pad tanpa
        // membuka bar edit tetap memakai mode tersimpan, bukan default POINTER.
        padStickMode = sessionPrefs.virtualPadStickMode
        setContent {
            // Layar sesi ikut setelan tema app (dulu dipaksa gelap, jadi di
            // mode terang panel dan dialog di sini tidak nyambung dengan sisa
            // app). Tombol HUD yang menempel di gambar remote tetap punya
            // warna tetap supaya selalu terbaca.
            KzThemeState.init(appPrefs ?: AppPrefs(this))
            KzDeskTheme(dark = kzDark()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    KzDeskSessionScreen(
                        profile = profile,
                        manager = manager,
                        controller = controller,
                        onExit = { finish() },
                        onDisplayRefreshPreferenceChange = { applyPreferredDisplayRefreshRate(it) },
                        stickModeProvider = { padStickMode },
                        onStickKeys = { x, y -> applyHudStickAxis(x, y) },
                        onStickKeysMode = { mode, x, y -> applyHudStickAxis(x, y, mode) },
                        onStickModeChange = { next ->
                            padStickMode = next
                            sessionPrefs.virtualPadStickMode = next
                        },
                    )
                }
            }
        }

        // back = delegate ke layar Compose; default finish
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (backHandler?.invoke() != true) {
                        sessionId?.let { appPrefs?.clearAutoResumeSession(it) }
                        finish()
                    }
                }
            }
        )

        if (deferConnectForPermission) {
            ConnectionLog.add("SES: koneksi menunggu hasil izin kamera/mikrofon")
        } else {
            connectProfile(profile)
        }
    }

    override fun onStart() {
        super.onStart()
        // balik dari background sebelum timer jalan = cancel auto-disconnect
        bgHandler.removeCallbacks(bgDisconnect)
        observeConnectionLoss()
    }

    // ---- Picture-in-Picture --------------------------------------------------
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.state.value is SessionState.Connected &&
            appPrefs?.pip != false &&
            !isInPictureInPictureMode
        ) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        KzPipState.active = isInPictureInPictureMode
    }

    // ---- Notifikasi koneksi putus ------------------------------------------------
    private var lossJob: kotlinx.coroutines.Job? = null

    /** Beri tahu pengguna bila sesi putus sendiri saat app tidak sedang dilihat (layar mati/di latar). */
    private fun observeConnectionLoss() {
        if (lossJob != null) return
        var wasConnected = false
        lossJob = lifecycleScope.launch {
            manager.state.collect { st ->
                if (st is SessionState.Connected) {
                    wasConnected = true
                    return@collect
                }
                val dropped = st is SessionState.Disconnected || st is SessionState.Error
                if (wasConnected && dropped && !explicitDisconnect) {
                    wasConnected = false
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        PushManager.show(
                            applicationContext,
                            kzNow("Koneksi terputus", "Connection lost"),
                            kzNow("Sesi ke {0} terputus. Ketuk untuk menyambung lagi.", "The session to {0} was lost. Tap to reconnect.", sessionLabel ?: "PC"),
                            PushManager.CHANNEL_DEVICES,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyWindowSecurityFlags()
        hideSystemBars()
        syncPhoneClipboardToRemote()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Balik fokus (shade ditutup, balik dari recents) = paksa immersive
            // lagi; beberapa OEM membiarkan bar tetap tampil sesudah shade.
            hideSystemBars()
            scheduleForceRehide()
            syncPhoneClipboardToRemote()
        }
    }

    override fun onStop() {
        super.onStop()
        setGyroMouseActive(false)
        if (manager.state.value !is SessionState.Connected) return
        if (!userNavigatedHome && !explicitDisconnect) {
            sessionId?.let { appPrefs?.rememberAutoResumeSession(it) }
        }
        when {
            // Default: sesi dibiarkan hidup di latar lewat foreground service.
            appPrefs?.keepAlive != false -> {
                Log.i(TAG, "keep-alive: sesi jalan di latar (foreground service)")
                KzSessionService.start(this, sessionLabel, ping = true)
            }

            appPrefs?.autoDisconnect != false -> {
                bgHandler.postDelayed(bgDisconnect, BACKGROUND_DISCONNECT_DELAY_MS)
            }
        }
    }

    override fun onDestroy() {
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        inputManager = null
        super.onDestroy()
        setGyroMouseActive(false)
        sessionId?.let { KzSessionRegistry.remove(it) }
        bgHandler.removeCallbacks(bgDisconnect)
        if (KzSessionRegistry.activeCount() == 0) KzSessionService.stop(this)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipListener?.let { cm.removePrimaryClipChangedListener(it) }
        clipListener = null
        if (appPrefs?.clearClipboardOnDisconnect == true) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
        clipboardReader.shutdownNow()
        controller.release()
        manager.release()
    }

    /**
     * Ask Android for the handset's best supported display refresh while an
     * RDP session is foregrounded. This is a preference, not a promise about
     * the remote host's frame production rate.
     */
    private fun requestPreferredRefreshRate(profile: ConnectionProfile) {
        runCatching {
            val options = RdpOptions.of(this, profile.id)
            applyPreferredDisplayRefreshRate(
                requestedHz = if (options.pcConnectMode) options.pcTargetFps else null,
            )
        }.onFailure { error ->
            Log.w(TAG, "could not read display refresh preference; using system default", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyPreferredDisplayRefreshRate(requestedHz: Int?) {
        runCatching {
            val display = windowManager.defaultDisplay
            val currentMode = display.mode
            val sameResolution = display.supportedModes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            val modes = sameResolution.ifEmpty { display.supportedModes.toList() }
            val rates = modes.map { it.refreshRate }
            val preferred = RefreshRatePolicy.choose(requestedHz, rates)
                ?: display.refreshRate.takeIf { it.isFinite() && it > 0f }
                ?: return
            window.attributes = window.attributes.apply { preferredRefreshRate = preferred }
            ConnectionLog.add(
                "SES: local display refresh requested=${requestedHz ?: "max"}Hz " +
                    "selected=${preferred}Hz supported=${rates.sorted().joinToString()}Hz",
            )
        }.onFailure { error ->
            Log.w(TAG, "display refresh preference unavailable; using system default", error)
        }
    }

    /** Immersive: bar transparan, hide sistem bar (swipe untuk transient). */
    private fun hideSystemBars() {
        // Bar transparan tidak lagi di-set lewat window.statusBarColor /
        // navigationBarColor (no-op dan deprecated sejak API 35); cukup
        // edge-to-edge + hide() di bawah.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Reassert immersive mode after an IME transition on devices that reveal bars while typing. */
    fun onSessionImeVisibilityChanged(visible: Boolean) {
        sessionImeVisible = visible
        if (!visible || appPrefs?.keepSystemBarsHiddenWhenKeyboardOpens != true) return
        hideSystemBars()
        scheduleSystemBarsRehide()
    }

    private fun scheduleSystemBarsRehide() {
        if (appPrefs?.keepSystemBarsHiddenWhenKeyboardOpens != true) return
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed && sessionImeVisible &&
                appPrefs?.keepSystemBarsHiddenWhenKeyboardOpens == true
            ) {
                hideSystemBars()
            }
        }, 180L)
    }

    /**
     * Pengawas insets untuk OEM yang membandel: sesudah notification shade
     * ditutup (ataugesture notch), sebagian perangkat membiarkan status bar
     * tetap tampil walau behavior sudah transient. Di sini insets dibaca:
     * kalau bar terlihat padahal activity sedang memegang focus, sembunyikan
     * paksa sesudah jeda singkat. Saat shade benar-benar terbuka activity
     * kehilangan focus, jadi kita tidak melawan swipe pengguna.
     */
    private var barsRehidePending = false

    private fun installSystemBarsWatch() {
        val decor = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
                insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            if (visible && v.hasWindowFocus() && !isFinishing && !isDestroyed) {
                scheduleForceRehide()
            }
            ViewCompat.onApplyWindowInsets(v, insets)
        }
    }

    private fun scheduleForceRehide() {
        if (barsRehidePending) return
        barsRehidePending = true
        window.decorView.postDelayed({
            barsRehidePending = false
            if (!isFinishing && !isDestroyed && window.decorView.hasWindowFocus()) {
                hideSystemBars()
            }
        }, 700L)
    }

    /** State stick HUD terakhir, dipakai mapper untuk mendeteksi tepi tombol arah. */
    /** State terakhir per mode (WASD/Panah/Kursor) agar dua joystick tidak saling melepas tombol. */
    private val lastHudStickByMode = HashMap<KzStickMode, KzGamepadState>()

    /** Tujuan stik kiri gamepad virtual; disinkronkan dari state Compose. */
    private var padStickMode: KzStickMode = KzStickMode.POINTER

    /**
     * Jalur keluar gamepad virtual untuk sesi RDP. Sengaja memakai hook yang sama
     * dengan gamepad fisik (pointer delta, scroll units, klik, tombol virtual)
     * supaya satu pemetaan berlaku untuk kedua jenis pad.
     */
    private val virtualPadOutput = object : KzGamepadOutput {
        override fun pointerDelta(dx: Float, dy: Float) {
            onExternalPointerDelta?.invoke(dx, dy)
        }

        override fun scrollUnits(units: Int) {
            onExternalScrollUnits?.invoke(units)
        }

        override fun mouseClick(button: KzMouseButton, down: Boolean) {
            onExternalMouseClick?.invoke(button, down)
        }

        override fun key(key: KzPadKey, down: Boolean) {
            if (!::controller.isInitialized) return
            controller.sendVirtualKey(
                when (key) {
                    KzPadKey.ENTER -> KeyEvent.KEYCODE_ENTER
                    KzPadKey.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
                    KzPadKey.ARROW_UP -> KeyEvent.KEYCODE_DPAD_UP
                    KzPadKey.ARROW_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                    KzPadKey.ARROW_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                    KzPadKey.ARROW_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                    KzPadKey.W -> KeyEvent.KEYCODE_W
                    KzPadKey.A -> KeyEvent.KEYCODE_A
                    KzPadKey.S -> KeyEvent.KEYCODE_S
                    KzPadKey.D -> KeyEvent.KEYCODE_D
                },
                down,
            )
        }
    }

    /**
     * Jalankan sumbu joystick HUD lewat mapper inti untuk mode WASD/Panah.
     *
     * Sumbu -1..1 diubah ke skala int16 yang sama dengan gamepad fisik, jadi
     * keduanya melewati logika deadzone, threshold arah, dan deteksi tepi yang
     * sama. Sumbu (0,0) menghasilkan state kosong, yang melepas tombol arah.
     */
    private fun applyHudStickAxis(x: Float, y: Float, mode: KzStickMode = padStickMode) {
        val state = KzGamepadState(
            leftX = (x.coerceIn(-1f, 1f) * 32_767f).toInt(),
            leftY = (y.coerceIn(-1f, 1f) * 32_767f).toInt(),
        )
        KzGamepadActionMapper.apply(state, lastHudStickByMode[mode], virtualPadOutput, mode)
        lastHudStickByMode[mode] = state
    }

    /**
     * Tombol fisik / event kunci Android diteruskan ke mapper inti. Karakter
     * dari keyboard HP lewat jalur InputSink milik SessionView. Tombol
     * gamepad Bluetooth/USB dipetakan ke klik & aksi bila diaktifkan.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isGamepad && SessionPrefs(this).gamepadEnabled) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    onExternalMouseClick?.invoke(KzMouseButton.LEFT, down)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    onExternalMouseClick?.invoke(KzMouseButton.RIGHT, down)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    controller.sendVirtualKey(KeyEvent.KEYCODE_ENTER, down)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    controller.sendVirtualKey(KeyEvent.KEYCODE_ESCAPE, down)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    if (down) onExternalScrollUnits?.invoke(120)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    if (down) onExternalScrollUnits?.invoke(-120)
                    return true
                }
            }
        }
        return controller.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isJoystick && event.action == MotionEvent.ACTION_MOVE && SessionPrefs(this).gamepadEnabled) {
            val ax = event.getAxisValue(MotionEvent.AXIS_X)
            val ay = event.getAxisValue(MotionEvent.AXIS_Y)
            if (kotlin.math.abs(ax) > 0.14f || kotlin.math.abs(ay) > 0.14f) {
                onExternalPointerDelta?.invoke(ax * 18f, ay * 18f)
            }
            val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
            if (kotlin.math.abs(rz) > 0.22f) {
                onExternalScrollUnits?.invoke((-rz * 48f).toInt())
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * Izin runtime yang diminta mengikuti kanal yang benar-benar ada di
     * biner: KAMERA (rdpecam) dan MIKROFON (audin, capture lewat OpenSLES).
     * Keduanya terverifikasi ada di libfreerdp-client3.so; capture OpenSLES
     * di Android wajib punya izin RECORD_AUDIO yang diberikan user, jadi
     * izinnya diminta tepat sebelum menyambung saat opsi mikrofon menyala.
     */
    private fun requestRuntimePermissions(profile: ConnectionProfile): Boolean {
        val options = runCatching { RdpOptions.of(this, profile.id) }.getOrNull()
            ?: return false
        val wanted = buildList {
            if (options.camera) add(Manifest.permission.CAMERA)
            if (options.microphone) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isEmpty()) return false
        val deferConnect = wanted.any {
            it == Manifest.permission.CAMERA || it == Manifest.permission.RECORD_AUDIO
        }
        if (deferConnect) pendingPermissionProfile = profile
        ConnectionLog.add("SES: minta izin runtime ${wanted.joinToString()}")
        permissionLauncher.launch(wanted.toTypedArray())
        return deferConnect
    }

    private fun connectProfile(profile: ConnectionProfile) {
        ConnectionLog.add("SES: memanggil manager.connect (native createSession + parse args)")
        try {
            manager.connect(profile)
            ConnectionLog.add("SES: manager.connect kembali (worker jalan async)")
        } catch (t: Throwable) {
            ConnectionLog.addThrowable("SES: connect() JVM-exception", t)
            Log.e(TAG, "connect() gagal", t)
            CrashLog.note(this, "connect() gagal: ${t.stackTraceToString().take(12_000)}")
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            ConnectionLog.add("SES: hasil izin ${result.entries.joinToString { "${it.key}=${it.value}" }}")
            val profile = pendingPermissionProfile
            pendingPermissionProfile = null
            if (profile != null && !isFinishing && !isDestroyed) {
                val options = runCatching { RdpOptions.of(this, profile.id) }.getOrNull()
                if (options != null) {
                    val cameraGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    val microphoneGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    val adjusted = options.copy(
                        camera = options.camera && cameraGranted,
                        microphone = options.microphone && microphoneGranted,
                    )
                    if (adjusted != options) adjusted.write(this, profile.id)
                    if ((options.camera && !cameraGranted) || (options.microphone && !microphoneGranted)) {
                        KzNoticeBus.post("Izin kamera/mikrofon ditolak; kanal dinonaktifkan di opsi profil.")
                    }
                }
                connectProfile(profile)
            }
        }

    private fun profileFromIntent(intent: Intent): ConnectionProfile? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return null
        return try {
            ConnectionProfile(
                host = host,
                port = intent.getIntExtra(EXTRA_PORT, 3389),
                username = intent.getStringExtra(EXTRA_USER),
                password = intent.getStringExtra(EXTRA_PASS),
                domain = intent.getStringExtra(EXTRA_DOMAIN),
                label = intent.getStringExtra(EXTRA_LABEL),
                // Kunci tetap perangkat WAJIB ikut. Tanpa ini id profil jatuh
                // balik ke "host:port", padahal RdpOptions/DisplayPrefs/HUD
                // ditulis pakai kunci tetap — akibatnya semua setelan per
                // perangkat (audio, mic, gateway, UDP, H264, clipboard,
                // drive, resolusi, rotasi, dpi, layout tombol) tidak pernah
                // terbaca dan sesi jalan dengan default.
                key = intent.getStringExtra(EXTRA_KEY),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "profil dari intent tidak valid: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "KzDeskSessionAct"

        const val EXTRA_HOST = "kzdesk.host"
        const val EXTRA_PORT = "kzdesk.port"
        const val EXTRA_USER = "kzdesk.user"
        const val EXTRA_PASS = "kzdesk.pass"
        const val EXTRA_DOMAIN = "kzdesk.domain"
        const val EXTRA_LABEL = "kzdesk.label"
        const val EXTRA_KEY = "kzdesk.key"

        /** Auto-disconnect kalau app di-background dan keep-alive dimatikan. */
        const val BACKGROUND_DISCONNECT_DELAY_MS = 15_000L

        fun connectIntent(context: Context, profile: ConnectionProfile): Intent =
            Intent(context, KzDeskSessionActivity::class.java).apply {
                putExtra(EXTRA_HOST, profile.host)
                putExtra(EXTRA_PORT, profile.port)
                putExtra(EXTRA_USER, profile.username)
                putExtra(EXTRA_PASS, profile.password)
                putExtra(EXTRA_DOMAIN, profile.domain)
                putExtra(EXTRA_LABEL, profile.label)
                putExtra(EXTRA_KEY, profile.key)
            }
    }
}
