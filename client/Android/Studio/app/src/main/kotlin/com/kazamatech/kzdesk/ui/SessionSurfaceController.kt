package com.kazamatech.kzdesk.ui

import android.app.Activity
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kazamatech.kzdesk.core.ConnectionLog
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.compose.ui.geometry.Offset
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.ScrollView2D
import com.freerdp.freerdpcore.presentation.SessionInputManager
import com.freerdp.freerdpcore.presentation.SessionView
import com.freerdp.freerdpcore.presentation.TouchPointerView
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.kazamatech.kzdesk.core.GraphicsSink

/**
 * M2 — controller surface sesi KzDesk.
 *
 * Menjamu view render inti (SessionView + TouchPointerView + ScrollView2D —
 * susunan programatik dari `session.xml` milik core) dan mengimplementasikan [GraphicsSink]:
 *
 *  - OnGraphicsUpdate -> `LibFreeRDP.updateGraphics` (copy piksel ke
 *    bitmap permukaan) -> `SessionView.addInvalidRegion` + invalidate
 *    (main thread) — identik dengan jalur M0
 *    (`SessionActivity.OnGraphicsUpdate`).
 *  - OnGraphicsResize -> bitmap baru -> `SessionState.setSurface` ->
 *    `SessionView.onSurfaceChange` (main thread).
 *
 * Dipanggil dari thread RDP (sink) — semua modifikasi View di-post ke
 * main thread. View tree dibangun dari main thread (AndroidView factory).
 */
/**
 * Kursor yang dikirim server RDP.
 *
 * Server mengirim bitmap kursor SUNGGUHAN (panah, tangan, I-beam, resize,
 * dsb) lewat pointer update; kita cuma menggambarnya, jadi bentuknya sama
 * seperti di desktop remote. [visible] = false artinya server minta kursor
 * disembunyikan (mis. saat mengetik).
 */
data class RemoteCursor(
    val bitmap: android.graphics.Bitmap?,
    val hotX: Int,
    val hotY: Int,
    val visible: Boolean,
)

class SessionSurfaceController(private val activity: Activity) : GraphicsSink {

    private val uiHandler = Handler(Looper.getMainLooper())

    private var rootView: RelativeLayout? = null
    private var scrollView: ScrollView2D? = null
    private var sessionView: SessionView? = null
    private var touchPointerView: TouchPointerView? = null
    private var inputManager: SessionInputManager? = null

    @Volatile private var inst = 0L
    /** Tinggi IME terakhir (px). 0 = keyboard HP tidak tampil. */
    @Volatile var imeHeightPx: Int = 0
        private set
    private var lastImeBottom = -1
    /**
     * Dipanggil tiap tinggi IME berubah. UI memakai ini untuk menaruh
     * toolbar persis di atas keyboard HP dan menyembunyikannya saat
     * keyboard HP ditutup.
     */
    var onImeChanged: ((Int) -> Unit)? = null
    var onInputDispatchFailure: (() -> Unit)? = null
    var onCursorMoved: ((Int, Int) -> Unit)? = null
    var onInputKeyConsumed: (() -> Unit)? = null
    /** Called once per coalesced display refresh when a dirty region is invalidated. */
    var onGraphicsInvalidated: (() -> Unit)? = null
    var onImeSnippetCommitted: ((String) -> Unit)? = null
    @Volatile var hasActiveHudModifiers: Boolean = false
    @Volatile var overlayInputActive: Boolean = false
    @Volatile var batterySaverMode: Boolean = false
    private var lastInputDispatchFailureAt = 0L
    private val dirtyLock = Any()
    private val pendingDirtyRect = Rect()
    private var hasPendingDirty = false
    private var flushScheduled = false
    private val flushDirtyRunnable = Runnable {
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        synchronized(dirtyLock) {
            flushScheduled = false
            if (!hasPendingDirty) return@Runnable
            left = pendingDirtyRect.left
            top = pendingDirtyRect.top
            right = pendingDirtyRect.right
            bottom = pendingDirtyRect.bottom
            pendingDirtyRect.setEmpty()
            hasPendingDirty = false
        }
        val sv = sessionView ?: return@Runnable
        sv.addInvalidRegion(Rect(left, top, right, bottom))
        sv.invalidateRegion()
        onGraphicsInvalidated?.invoke()
    }

    private fun asciiToAndroidKeyCode(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> KeyEvent.KEYCODE_A + (codePoint - 'a'.code)
        in 'A'.code..'Z'.code -> KeyEvent.KEYCODE_A + (codePoint - 'A'.code)
        in '0'.code..'9'.code -> KeyEvent.KEYCODE_0 + (codePoint - '0'.code)
        '-'.code -> KeyEvent.KEYCODE_MINUS
        '='.code -> KeyEvent.KEYCODE_EQUALS
        '['.code -> KeyEvent.KEYCODE_LEFT_BRACKET
        ']'.code -> KeyEvent.KEYCODE_RIGHT_BRACKET
        '\\'.code -> KeyEvent.KEYCODE_BACKSLASH
        ';'.code -> KeyEvent.KEYCODE_SEMICOLON
        '\''.code -> KeyEvent.KEYCODE_APOSTROPHE
        '`'.code -> KeyEvent.KEYCODE_GRAVE
        ','.code -> KeyEvent.KEYCODE_COMMA
        '.'.code -> KeyEvent.KEYCODE_PERIOD
        '/'.code -> KeyEvent.KEYCODE_SLASH
        else -> 0
    }

    private fun reportInputDispatchFailure() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInputDispatchFailureAt < 2_000L) return
        lastInputDispatchFailureAt = now
        onInputDispatchFailure?.invoke()
    }

    /** Re-dispatch current IME insets after the Compose observer is installed. */
    fun refreshImeInsets() {
        rootView?.let { ViewCompat.requestApplyInsets(it) }
    }
    /** Bentuk kursor dari server untuk digambar Compose (null = panah bawaan). */
    var onRemoteCursor: ((RemoteCursor) -> Unit)? = null
    @Volatile private var bitmap: Bitmap? = null
    private var remoteCursorBitmap: Bitmap? = null
    private val remoteFramePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    @Volatile private var viewReady = false

    /** Perubahan zoom (pinch/programatik) — dipanggil di main thread. */
    var onZoomChanged: ((Float) -> Unit)? = null

    val isViewReady: Boolean
        get() = viewReady

    /** Dipanggil (idempotent) sesegera mungkin setelah view tree siap. */
    fun setInstance(inst: Long) {
        if (inst == 0L) return
        this.inst = inst
        if (viewReady) catchUpSurface()
    }

    /**
     * Build view tree (dipanggil dari Compose `AndroidView` factory).
     * Wiring input identik dengan `SessionActivity.onCreate`.
     */
    fun buildViewTree(context: Context): View {
        if (viewReady) return rootView!!
        val root = RelativeLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        val scroller = ScrollView2D(context)
        val rail = FrameLayout(context)
        val sv = SessionView(context)
        rail.addView(
            sv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scroller.addView(rail)
        val scrollerParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        root.addView(scroller, scrollerParams)

        val tpv = TouchPointerView(context)
        val tpvParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        tpv.visibility = View.INVISIBLE
        root.addView(tpv, tpvParams)

        // Wiring input — cermin SessionActivity.onCreate
        // Board keyboard bawaan FreeRDP tidak dipakai (ronde 5): semua
        // pengetikan lewat keyboard HP, jadi view-nya tidak pernah dibuat.
        val im = SessionInputManager(activity, scroller, sv, tpv, null)
        im.setInputDispatchFailureListener { reportInputDispatchFailure() }
        im.setCursorPositionListener { x, y -> onCursorMoved?.invoke(x, y) }
        fun sendImeKey(keyCode: Int) {
            im.onAndroidKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            im.onAndroidKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }

        // Keyboard HP -> mapper tombol inti. SessionView menanyakan koneksi
        // input ke sini; karakter diubah jadi KeyEvent supaya scancode,
        // modifier, dan kombinasi ditangani jalur yang sudah terbukti.
        sv.setInputSink(object : SessionView.InputSink {
            override fun onText(text: String) {
                if (text.isEmpty()) return
                // Jika user menempelkan teks panjang (>16 karakter, mis. dari bar clipboard Gboard
                // atau file .txt), jangan lempar ribuan event unicode beruntun ke antrean 512 slot
                // yang bisa memicu disconnect. Sinkronkan ke clipboard remote lalu kirim Ctrl+V.
                if (text.length > 16) {
                    onImeSnippetCommitted?.invoke(text)
                    uiHandler.postDelayed({
                        im.sendAndroidKeyCode(KeyEvent.KEYCODE_CTRL_LEFT, true)
                        im.sendAndroidKeyCode(KeyEvent.KEYCODE_V, true)
                        im.sendAndroidKeyCode(KeyEvent.KEYCODE_V, false)
                        im.sendAndroidKeyCode(KeyEvent.KEYCODE_CTRL_LEFT, false)
                        onInputKeyConsumed?.invoke()
                    }, 105L)
                    return
                }
                var i = 0
                while (i < text.length) {
                    val cp = Character.codePointAt(text, i)
                    val mappedKey = if (hasActiveHudModifiers) asciiToAndroidKeyCode(cp) else 0
                    when {
                        cp == '\n'.code -> sendImeKey(KeyEvent.KEYCODE_ENTER)
                        cp == '\t'.code -> sendImeKey(KeyEvent.KEYCODE_TAB)
                        cp == '\b'.code -> sendImeKey(KeyEvent.KEYCODE_DEL)
                        cp == ' '.code -> sendImeKey(KeyEvent.KEYCODE_SPACE)
                        mappedKey != 0 -> {
                            im.sendAndroidKeyCode(mappedKey, true)
                            im.sendAndroidKeyCode(mappedKey, false)
                        }
                        else -> im.processUnicodeKey(cp)
                    }
                    i += Character.charCount(cp)
                }
                if (text.length > 1) {
                    onImeSnippetCommitted?.invoke(text)
                }
                onInputKeyConsumed?.invoke()
            }

            override fun onKeyEvent(event: KeyEvent): Boolean {
                val handled = im.onAndroidKeyEvent(event)
                if (handled && event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(event.keyCode)) {
                    onInputKeyConsumed?.invoke()
                }
                return handled
            }
        })
        sv.setSessionViewListener(im)
        tpv.setTouchPointerListener(im)
        sv.setScaleGestureDetector(
            ScaleGestureDetector(activity, im.getPinchZoomListener())
        )
        sv.setOnZoomChangedListener { z -> onZoomChanged?.invoke(z) }

        rootView = root
        scrollView = scroller
        sessionView = sv
        touchPointerView = tpv
        inputManager = im
        viewReady = true

        installInsetsHandling(root, scroller)
        root.requestApplyInsets()

        // Catch-up: kalau resize sudah terjadi sebelum view tree siap
        // (jarang — connect native butuh >100ms, factory jalan <100ms),
        // ambil surface yang sudah ada sekarang.
        catchUpSurface()

        root.post { sv.requestFocus() }
        return root
    }

    /**
     * Insets: satu-satunya hal yang perlu dilaporkan adalah status keyboard
     * HP (IME) ke input manager inti. Tidak ada lagi view keyboard yang perlu
     * digeser, jadi scroll view tidak diberi padding — inilah yang dulu bikin
     * strip gelap di tepi bawah dan menutupi taskbar remote.
     */
    private fun installInsetsHandling(
        root: RelativeLayout,
        scroller: ScrollView2D,
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            val bottomInset = maxOf(nav.bottom, gestures)
            inputManager?.onImeVisibilityChanged(imeBottom > 0)
            if (imeBottom != lastImeBottom) {
                lastImeBottom = imeBottom
                imeHeightPx = imeBottom
                onImeChanged?.invoke(imeBottom)
            }
            scroller.setPadding(nav.left, 0, nav.right, 0)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Dipanggil saat state = Connected. */
    fun bind(inst: Long) {
        if (inst == 0L) return
        this.inst = inst
        uiHandler.post {
            val session = GlobalApp.getSession(inst)
            val surface = session?.getSurface()
            inputManager?.attachSession(inst, surface?.bitmap)
            val root = rootView
            if (root != null && root.width > 0 && root.height > 0) {
                inputManager?.setScreenSize(root.width, root.height)
            }
            if (session == null || surface == null) return@post
            bitmap = surface.bitmap
            sessionView?.onSurfaceChange(session)
            scrollView?.requestLayout()
        }
    }

    // ------------------------------------------------------------------
    // Controls (dipakai panel HUD — main thread)
    // ------------------------------------------------------------------

    fun currentZoom(): Float = sessionView?.getZoom() ?: 1f

    fun zoomIn(): Boolean = sessionView?.zoomIn(ZOOM_STEP) ?: false

    fun zoomOut(): Boolean = sessionView?.zoomOut(ZOOM_STEP) ?: false

    /** Scale the full remote desktop into the current device viewport. */
    fun fitToScreen() {
        val view = sessionView ?: return
        val frame = bitmap ?: return
        val width = (scrollView?.width ?: rootView?.width ?: 0).toFloat()
        val height = (scrollView?.height ?: rootView?.height ?: 0).toFloat()
        if (width <= 0f || height <= 0f || frame.width <= 0 || frame.height <= 0) return
        val factor = minOf(width / frame.width, height / frame.height)
        view.setZoom(factor.coerceIn(SessionView.MIN_SCALE_FACTOR, SessionView.MAX_SCALE_FACTOR))
    }

    fun toggleTouchPointer() {
        inputManager?.toggleTouchPointer()
    }

    /**
     * Kirim keycode Android (KeyEvent.KEYCODE_*) lewat input manager inti.
     * Pencocokan ke scancode RDP dilakukan KeyboardMapper, jadi overlay
     * keyboard KzDesk tidak perlu tahu tabel scancode.
     */
    fun sendVirtualKey(keyCode: Int, down: Boolean) {
        uiHandler.post {
            val im = inputManager
            if (im == null) {
                reportInputDispatchFailure()
            } else {
                im.sendAndroidKeyCode(keyCode, down)
            }
        }
    }

    /** Tekan beberapa tombol sekaligus (mis. Ctrl+Alt+Del) lalu lepas terbalik. */
    fun sendCombo(keyCodes: List<Int>) {
        if (keyCodes.isEmpty()) return
        keyCodes.forEach { sendVirtualKey(it, true) }
        keyCodes.reversed().forEach { sendVirtualKey(it, false) }
    }

    /** Kirim karakter unicode (dipakai kalau server tidak paham scancode-nya). */
    fun sendUnicode(ch: Int) {
        uiHandler.post { inputManager?.processUnicodeKey(ch) }
    }

    /**
     * Lepas fokus dari surface sebelum layar sesi ditinggalkan (mis. buka
     * home). Tanpa ini keyboard HP bisa tertinggal terbuka di layar lain
     * padahal sesinya tetap jalan di latar.
     */
    fun blurInput() {
        uiHandler.post {
            runCatching {
                inputManager?.setSoftKeyboard(false)
                sessionView?.let { sv ->
                    WindowCompat.getInsetsController(activity.window, sv)
                        .hide(WindowInsetsCompat.Type.ime())
                }
                rootView?.clearFocus()
            }
        }
    }

    fun setOverlayActive(active: Boolean) {
        if (overlayInputActive == active) return
        overlayInputActive = active
        if (active) {
            blurInput()
        } else {
            uiHandler.post { sessionView?.requestFocus() }
        }
    }

    /**
     * Nyala/matikan keyboard HP (IME). Board keyboard bawaan sudah dibuang,
     * jadi ini satu-satunya jalur keyboard: tombol HUD "Keyboard" dan chip
     * di panel hanya memanggil ini.
     */
    fun setImeVisible(shown: Boolean) {
        uiHandler.post {
            val im = inputManager ?: return@post
            im.setSoftKeyboard(shown)
            sessionView?.let { sv ->
                val insetsController = WindowCompat.getInsetsController(activity.window, sv)
                if (shown) {
                    sv.requestFocus()
                    insetsController.show(WindowInsetsCompat.Type.ime())
                } else {
                    insetsController.hide(WindowInsetsCompat.Type.ime())
                }
            }
            rootView?.requestApplyInsets()
        }
    }

    fun isImeVisible(): Boolean = imeHeightPx > 0 || (inputManager?.isSoftInputActive() ?: false)

    /**
     * Teruskan event tombol Android (dari keyboard HP / keyboard fisik) ke
     * mapper inti. Activity wajib memanggil ini dari dispatchKeyEvent —
     * tanpa jalur ini keyboard HP tidak bisa mengetik ke remote.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (overlayInputActive) return false
        val handled = inputManager?.onAndroidKeyEvent(event) ?: false
        if (handled && event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(event.keyCode)) {
            onInputKeyConsumed?.invoke()
        }
        return handled
    }

    fun onKeyLongPress(keyCode: Int): Boolean = inputManager?.onAndroidKeyLongPress(keyCode) ?: false

    /**
     * Konversi koordinat desktop remote -> koordinat layar (px), dipakai
     * lapisan Compose untuk menggambar pointer sendiri. Return null kalau
     * surface belum siap.
     */
    fun remoteToScreen(x: Float, y: Float): Offset? {
        val sv = sessionView ?: return null
        val zoom = sv.getZoom().takeIf { it > 0.01f } ?: return null
        val container = if (scrollView?.childCount ?: 0 > 0) scrollView!!.getChildAt(0) else sv
        val screenX = x * zoom - (scrollView?.scrollX ?: 0) + container.left
        val screenY = y * zoom - (scrollView?.scrollY ?: 0) + container.top
        return Offset(screenX, screenY)
    }

    /** Terapkan zoom tersimpan (dipanggil setelah bind, main thread). */
    fun applyZoom(zoom: Float) {
        sessionView?.setZoom(zoom)
    }

    /** Ambil referensi bitmap frame terakhir untuk pratinjau jendela mengambang (PiP overlay). */
    fun peekBitmap(): Bitmap? = bitmap

    /**
     * Gambar frame desktop terakhir langsung ke canvas milik pemanggil, tanpa
     * membuat salinan bitmap.
     *
     * Dipakai perekaman: jalur lama menyalin bitmap penuh setiap frame (30
     * alokasi besar per detik), yang menekan GC. Tekanan GC itu sendiri bikin
     * HP tersendat, dan jeda GC membuat thread perekaman kehilangan jadwal
     * frame-nya -- jadi rekaman ikut patah walaupun desktop PC lancar.
     */
    fun drawRemoteFrameInto(canvas: android.graphics.Canvas, dest: android.graphics.Rect): Boolean {
        val source = bitmap ?: return false
        return runCatching {
            synchronized(source) {
                if (source.isRecycled) return false
                canvas.drawBitmap(source, null, dest, remoteFramePaint)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Copy frame desktop remote tanpa menangkap layar HP atau overlay Compose.
     * Resolusi dibatasi untuk mencegah perekaman membebani memori perangkat.
     */
    fun copyRemoteBitmap(maxLongEdge: Int = 1280, mutable: Boolean = false): Bitmap? {
        val source = bitmap ?: return null
        return runCatching {
            synchronized(source) {
                if (source.isRecycled) return@synchronized null
                val limit = maxLongEdge.coerceIn(2, 4096)
                val scale = minOf(1f, limit.toFloat() / maxOf(source.width, source.height))
                val width = ((source.width * scale).toInt().coerceAtLeast(2) / 2) * 2
                val height = ((source.height * scale).toInt().coerceAtLeast(2) / 2) * 2
                val copied = if (width == source.width && height == source.height) {
                    source.copy(Bitmap.Config.ARGB_8888, mutable)
                } else {
                    Bitmap.createScaledBitmap(source, width, height, true)
                }
                // createScaledBitmap bisa mengembalikan bitmap yang tidak bisa
                // digambari. Perekaman butuh mutable untuk menimpa kursor ke
                // frame, jadi disalin ulang hanya kalau memang perlu.
                if (mutable && copied != null && !copied.isMutable && copied !== source) {
                    copied.copy(Bitmap.Config.ARGB_8888, true).also { copied.recycle() }
                } else {
                    copied
                }
            }
        }.getOrNull()
    }

    /**
     * M2.5 — screenshot surface: copy bitmap -> PNG di
     * `getExternalFilesDir/screenshots/` -> content URI (FileProvider)
     * untuk dibagikan. Return null kalau belum ada frame.
     */
    suspend fun captureScreenshot(activity: Activity): Uri? = withContext(Dispatchers.IO) {
        val src = bitmap ?: return@withContext null
        val shot = runCatching { synchronized(src) { src.copy(Bitmap.Config.ARGB_8888, false) } }
            .getOrNull() ?: return@withContext null
        val fileName = "KzDesk-PC-${System.currentTimeMillis()}.png"
        try {
            // Android 10+ (API 29): simpan ke Pictures/KzDesk lewat MediaStore
            // supaya screenshot muncul di galeri. Sebelumnya masuk ke
            // getExternalFilesDir()/screenshots — folder privat app yang tidak
            // terlihat di galeri dan ikut terhapus saat app di-uninstall.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = activity.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/KzDesk",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                var completed = false
                try {
                    val written = resolver.openOutputStream(uri)?.use { out ->
                        shot.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: false
                    if (!written) return@withContext null
                    val ready = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    check(resolver.update(uri, ready, null, null) > 0) {
                        "Unable to publish the PNG in MediaStore"
                    }
                    completed = true
                    uri
                } finally {
                    // Baris MediaStore yang gagal ditulis harus dihapus, kalau
                    // tidak galeri berisi entri kosong yang tidak bisa dibuka.
                    if (!completed) runCatching { resolver.delete(uri, null, null) }
                }
            } else {
                // Android 7-9: manifest tidak meminta WRITE_EXTERNAL_STORAGE,
                // jadi folder Pictures publik tidak bisa ditulis tanpa menambah
                // izin lama + permintaan runtime. Tetap di folder app.
                val externalFiles = activity.getExternalFilesDir(null)
                    ?: return@withContext null
                val dir = File(externalFiles, "screenshots")
                if (!dir.exists() && !dir.mkdirs()) return@withContext null
                val f = File(dir, fileName)
                val saved = FileOutputStream(f).use { out ->
                    shot.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!saved) {
                    f.delete()
                    return@withContext null
                }
                FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
            }
        } catch (t: Throwable) {
            ConnectionLog.addThrowable("SES: screenshot gagal", t)
            null
        } finally {
            shot.recycle()
        }
    }

    fun release() {
        uiHandler.removeCallbacksAndMessages(null)
        inputManager?.cancelPendingEvents()
        inputManager?.hideKeyboards()
        inputManager = null
        sessionView = null
        touchPointerView = null
        scrollView = null
        rootView = null
        bitmap = null
        remoteCursorBitmap?.let { if (!it.isRecycled) it.recycle() }
        remoteCursorBitmap = null
        onRemoteCursor?.invoke(RemoteCursor(null, 0, 0, true))
        onGraphicsInvalidated = null
        inst = 0L
        viewReady = false
    }

    // ------------------------------------------------------------------
    // GraphicsSink (dipanggil dari thread RDP)
    // ------------------------------------------------------------------

    override fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        val i = inst
        val bm = bitmap
        if (i == 0L || bm == null) return
        if (width <= 0 || height <= 0) return
        val left = x.coerceIn(0, bm.width)
        val top = y.coerceIn(0, bm.height)
        val right = (x.toLong() + width).coerceIn(0L, bm.width.toLong()).toInt()
        val bottom = (y.toLong() + height).coerceIn(0L, bm.height.toLong()).toInt()
        if (left >= right || top >= bottom) return
        synchronized(bm) {
            LibFreeRDP.updateGraphics(i, bm, left, top, right - left, bottom - top)
        }
        val shouldSchedule: Boolean
        synchronized(dirtyLock) {
            if (!hasPendingDirty) {
                pendingDirtyRect.set(left, top, right, bottom)
                hasPendingDirty = true
            } else {
                pendingDirtyRect.union(left, top, right, bottom)
            }
            shouldSchedule = !flushScheduled
            if (shouldSchedule) flushScheduled = true
        }
        if (shouldSchedule) {
            val sv = sessionView
            if (!batterySaverMode && sv != null) {
                sv.postOnAnimation(flushDirtyRunnable)
            } else {
                uiHandler.postDelayed(flushDirtyRunnable, if (batterySaverMode) 28L else 0L)
            }
        }
    }

    override fun onGraphicsResize(width: Int, height: Int, bpp: Int) {
        val i = inst
        if (i == 0L) return
        if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > MAX_SURFACE_PIXELS) {
            ConnectionLog.add("SES: tolak surface di luar batas ${width}x$height")
            return
        }
        val newBitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setHasAlpha(false)
                // Mipmap trilinear dimatikan: pada skala non-bulat mip level
                // yang dipilih GPU membuat stroke huruf tipis ikut turun
                // resolusi sehingga teks tampak pecah. Filter bilinear
                // (isFilterBitmap) sudah cukup untuk diperkecil/diperbesar.
                setHasMipMap(false)
            }
        }.getOrElse {
            ConnectionLog.addThrowable("SES: gagal alokasi surface ${width}x$height", it)
            return
        }
        bitmap = newBitmap
        val session = GlobalApp.getSession(i) ?: return
        val drawable = BitmapDrawable(activity.getResources(), newBitmap).apply {
            isFilterBitmap = true
            setDither(true)
            paint.isFilterBitmap = true
            paint.isAntiAlias = true
            paint.isDither = true
        }
        session.setSurface(drawable)
        uiHandler.post {
            if (!viewReady) return@post
            val sv = sessionView ?: return@post
            sv.onSurfaceChange(session)
            scrollView?.requestLayout()
            inputManager?.setBitmap(newBitmap)
            inputManager?.attachSession(i, newBitmap)
        }
    }

    override fun onPointerSet(
        pixels: IntArray?,
        width: Int,
        height: Int,
        hotX: Int,
        hotY: Int,
    ) {
        uiHandler.post {
            sessionView?.setRemoteCursor(pixels, width, height, hotX, hotY)
            touchPointerView?.setRemoteCursor(pixels, width, height, hotX, hotY)
            if (pixels != null && width > 0 && height > 0) {
                val bmp = runCatching {
                    android.graphics.Bitmap.createBitmap(
                        pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888,
                    )
                }.getOrNull()
                if (bmp != null) publishRemoteCursor(RemoteCursor(bmp, hotX, hotY, true))
            }
        }
    }

    override fun onPointerSetNull() {
        uiHandler.post {
            sessionView?.setRemoteCursor(null, 0, 0, 0, 0)
            touchPointerView?.setRemoteCursor(null, 0, 0, 0, 0)
            publishRemoteCursor(RemoteCursor(null, 0, 0, false))
        }
    }

    override fun onPointerSetDefault() {
        uiHandler.post {
            sessionView?.setDefaultCursor()
            publishRemoteCursor(RemoteCursor(null, 0, 0, true))
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun publishRemoteCursor(cursor: RemoteCursor) {
        val previous = remoteCursorBitmap
        remoteCursorBitmap = cursor.bitmap
        onRemoteCursor?.invoke(cursor)
        if (previous != null && previous !== cursor.bitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    private fun catchUpSurface() {
        val i = inst
        if (i == 0L) return
        val session = GlobalApp.getSession(i) ?: return
        val surface = session.getSurface() ?: return
        bitmap = surface.bitmap
        uiHandler.post {
            if (!viewReady) return@post
            sessionView?.onSurfaceChange(session)
            scrollView?.requestLayout()
            inputManager?.setBitmap(surface.bitmap)
            inputManager?.attachSession(i, surface.bitmap)
        }
    }

    companion object {
        private const val ZOOM_STEP = 0.1f
        private const val MAX_SURFACE_PIXELS = 20_000_000L
    }
}
