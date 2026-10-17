package com.kazamatech.kzdesk.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Jembatan ke sesi media Android yang sedang aktif.
 *
 * Setelah pengguna memberi akses listener notifikasi, KzDesk bisa membaca info
 * lagu dan mengirim kontrol transport ke pemutar APA PUN (Spotify, SoundCloud,
 * YouTube Music, Poweramp, pemutar lokal). Audio dan login tetap di aplikasi
 * musik itu; KzDesk hanya pengendali jarak jauh.
 *
 * Yang dibuka di sini bukan cuma putar/jeda: posisi + durasi (untuk scrub),
 * aksi yang benar-benar didukung pemutar (supaya tombol mati sendiri kalau
 * pemutarnya tidak bisa), antrian lagu, mode ulang & acak, dan daftar sesi
 * supaya pengguna bisa memilih sumbernya sendiri.
 */
internal object SpotifyMediaBridge {
    const val SPOTIFY_PACKAGE = "com.spotify.music"

    /** Interval pembaruan jam posisi. 500 ms cukup halus, murah untuk CPU. */
    private const val TICK_MS = 500L

    private val mutablePlayback = MutableStateFlow(SpotifyPlaybackState())
    val playback: StateFlow<SpotifyPlaybackState> = mutablePlayback.asStateFlow()

    /**
     * Posisi (ms) yang sudah diekstrapolasi. Dipisah dari [playback] supaya
     * pembaruan tiap 500 ms tidak membangun ulang state besar (termasuk
     * mengecilkan bitmap sampul) hanya untuk menggerakkan scrub bar.
     */
    private val mutablePositionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = mutablePositionMs.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeController: MediaController? = null
    private var activeCallback: MediaController.Callback? = null
    @Volatile private var notificationAccess = false
    private var refreshRunnable: Runnable? = null
    @Volatile private var appContext: Context? = null

    /** Paket yang dipilih pengguna sendiri; `null` = pilih otomatis. */
    @Volatile private var preferredPackage: String? = null

    /**
     * Jalur samping untuk acak & ulang.
     *
     * `MediaController.getShuffleMode/getRepeatMode` dan
     * `TransportControls.setShuffleMode/setRepeatMode` TIDAK ada di SDK publik —
     * semuanya @hide. Yang membukanya hanya `MediaControllerCompat` dari
     * androidx.media, jadi controller itu dibangun dari session token yang sama
     * dan dipakai khusus untuk dua kontrol tersebut.
     */
    @Volatile private var compatController: MediaControllerCompat? = null
    private var compatCallback: MediaControllerCompat.Callback? = null

    /** Jangkar posisi terakhir, untuk ekstrapolasi [mutablePositionMs]. */
    @Volatile private var positionAnchorMs = 0L
    @Volatile private var positionAnchorElapsed = 0L
    @Volatile private var tickerRunning = false

    fun hasNotificationAccess(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    /** Paket aplikasi sumber yang sedang terpasang, untuk listener notifikasi. */
    fun currentSourcePackage(): String? = activeController?.packageName

    // ------------------------------------------------------------ pemilihan sumber

    /**
     * Daftar semua sesi media yang terlihat, supaya pengguna bisa memilih
     * sumber sendiri. Pilihan otomatis kadang menempel ke pemutar yang salah
     * (mis. YouTube yang berhenti tapi sesinya masih hidup).
     */
    fun availableSources(context: Context): List<MediaSourceEntry> {
        val sessions = activeSessions(context) ?: return emptyList()
        val current = activeController?.packageName
        return sessions
            .groupBy { it.packageName }
            .map { (pkg, group) ->
                MediaSourceEntry(
                    packageName = pkg,
                    label = sourceLabelFor(pkg) ?: shortPackageName(pkg),
                    playing = group.any { it.isPlayingNow() },
                    hasMetadata = group.any {
                        it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null
                    },
                    active = pkg == current,
                )
            }
            .sortedWith(
                compareByDescending<MediaSourceEntry> { it.active }
                    .thenByDescending { it.playing }
                    .thenByDescending { it.hasMetadata }
                    .thenBy { it.label },
            )
    }

    /**
     * Pilih sumber secara manual. Mengembalikan false kalau sesi aplikasi itu
     * tidak ada (sudah ditutup), jadi UI bisa memberi tahu pengguna.
     */
    fun selectSource(context: Context, packageName: String): Boolean {
        val target = activeSessions(context)?.firstOrNull { it.packageName == packageName }
            ?: return false
        preferredPackage = packageName
        attach(target)
        publishSources(context)
        return true
    }

    /** Kembali ke pemilihan sumber otomatis. */
    fun clearSourcePreference(context: Context) {
        preferredPackage = null
        refresh(context)
    }

    // ------------------------------------------------------------ penyegaran

    /**
     * Versi refresh yang ditunda sebentar. Listener notifikasi kini menerima
     * notifikasi media dari aplikasi mana pun, jadi panggilan bisa beruntun;
     * tanpa jeda, getActiveSessions() dipanggil berkali-kali untuk satu kejadian.
     */
    fun refreshDebounced(context: Context) {
        val application = context.applicationContext
        refreshRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            refreshRunnable = null
            refresh(application)
        }
        refreshRunnable = task
        mainHandler.postDelayed(task, 200L)
    }

    private fun MediaController.isPlayingNow(): Boolean {
        val state = playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun activeSessions(context: Context): List<MediaController>? = runCatching {
        val application = context.applicationContext
        appContext = application
        val manager = application.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = ComponentName(application, SpotifyNotificationListenerService::class.java)
        manager.getActiveSessions(listener)
            .filter { it.packageName != application.packageName }
    }.getOrNull()

    private fun sourceLabelFor(packageName: String?): String? {
        val context = appContext ?: return null
        val pkg = packageName?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Nama pendek paket sebagai cadangan label. Mulai Android 11 aplikasi tidak
     * selalu boleh membaca info paket lain, jadi label resmi bisa gagal; tanpa
     * cadangan ini daftar sumber menampilkan baris kosong.
     */
    private fun shortPackageName(packageName: String): String =
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    fun refresh(context: Context) {
        val application = context.applicationContext
        appContext = application
        val access = hasNotificationAccess(application)
        notificationAccess = access
        if (!access) {
            preferredPackage = null
            attach(null)
            mutablePlayback.value = SpotifyPlaybackState()
            return
        }

        val sessions = activeSessions(application)
        if (sessions == null) {
            attach(null)
            mutablePlayback.value = SpotifyPlaybackState(notificationAccessGranted = true)
            return
        }
        val chosen = pickSession(sessions)
        attach(chosen)
        publishSources(application)
    }

    private fun pickSession(sessions: List<MediaController>): MediaController? {
        // Sumber mana pun boleh: pilihan manual menang, lalu yang sedang
        // diputar, lalu yang punya metadata, baru sesi pertama yang tersedia.
        preferredPackage?.let { wanted ->
            sessions.firstOrNull { it.packageName == wanted }?.let { return it }
        }
        return sessions.firstOrNull { it.isPlayingNow() }
            ?: sessions.firstOrNull {
                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null
            }
            ?: sessions.firstOrNull()
    }

    private fun publishSources(context: Context) {
        val current = mutablePlayback.value
        mutablePlayback.value = current.copy(sources = availableSources(context))
    }

    // ------------------------------------------------------------ transport

    fun playPause() {
        val controller = activeController ?: return
        runCatching {
            when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> controller.transportControls.pause()
                else -> controller.transportControls.play()
            }
        }
    }

    fun previous() {
        runCatching { activeController?.transportControls?.skipToPrevious() }
    }

    fun next() {
        runCatching { activeController?.transportControls?.skipToNext() }
    }

    fun stop() {
        runCatching { activeController?.transportControls?.stop() }
    }

    /** Lompat ke posisi mutlak (ms). Pemutar tanpa ACTION_SEEK_TO mengabaikan. */
    fun seekTo(positionMs: Long) {
        runCatching { activeController?.transportControls?.seekTo(positionMs.coerceAtLeast(0L)) }
        // Jangkar dipindah sekarang supaya scrub bar tidak melompat mundur ke
        // posisi lama menunggu callback pemutar datang.
        positionAnchorMs = positionMs.coerceAtLeast(0L)
        positionAnchorElapsed = SystemClock.elapsedRealtime()
        mutablePositionMs.value = positionAnchorMs
    }

    /** Maju/mundur relatif [deltaMs]; ujung lagu dijaga oleh [MediaTransportMath]. */
    fun seekBy(deltaMs: Long) {
        val state = mutablePlayback.value
        val target = MediaTransportMath.seekBy(currentPositionMs(), state.durationMs, deltaMs)
        seekTo(target)
    }

    /**
     * Mode ulang berikutnya: mati -> satu lagu -> semua -> mati.
     *
     * Tidak semua pemutar menerima permintaan ini; kalau diabaikan, tidak ada
     * callback yang datang dan tombol menampilkan nilai lama apa adanya.
     */
    fun cycleRepeat() {
        val controller = compatController ?: return
        val next = MediaTransportMath.nextRepeatMode(controller.repeatMode)
        runCatching { controller.transportControls.setRepeatMode(next) }
    }

    fun setShuffle(enabled: Boolean) {
        val controller = compatController ?: return
        val mode = if (enabled) {
            PlaybackStateCompat.SHUFFLE_MODE_ALL
        } else {
            PlaybackStateCompat.SHUFFLE_MODE_NONE
        }
        runCatching { controller.transportControls.setShuffleMode(mode) }
    }

    /** Putar satu lagu dari antrian. */
    fun playQueueItem(queueId: Long) {
        runCatching { activeController?.transportControls?.skipToQueueItem(queueId) }
    }

    /**
     * Putar dari pustaka yang di-browse. [packageName] memastikan sesi yang
     * dikendalikan adalah aplikasi pemilik media id itu — media id Spotify
     * tidak berarti apa-apa bagi pemutar lokal.
     */
    fun playFromMediaId(context: Context, packageName: String, mediaId: String) {
        if (activeController?.packageName != packageName) {
            if (!selectSource(context, packageName)) return
        }
        runCatching { activeController?.transportControls?.playFromMediaId(mediaId, null) }
    }

    /** Posisi sekarang, sudah diekstrapolasi selama lagu berjalan. */
    fun currentPositionMs(): Long {
        val state = mutablePlayback.value
        val elapsed = SystemClock.elapsedRealtime() - positionAnchorElapsed
        return MediaTransportMath.extrapolate(
            positionMs = state.positionMs,
            elapsedSinceUpdateMs = elapsed,
            speed = state.speed,
            playing = state.isPlaying,
            durationMs = state.durationMs,
        )
    }

    // ------------------------------------------------------------ pemasangan

    private fun attach(next: MediaController?) {
        val current = activeController
        if (current != null && next != null && current.sessionToken == next.sessionToken) {
            publish(next)
            return
        }
        if (current != null) {
            activeCallback?.let { callback -> runCatching { current.unregisterCallback(callback) } }
        }
        activeController = next
        activeCallback = null
        attachCompat(next)

        if (next == null) {
            stopTicker()
            mutablePositionMs.value = 0L
            val sources = mutablePlayback.value.sources
            mutablePlayback.value = SpotifyPlaybackState(
                notificationAccessGranted = notificationAccess,
                sources = sources,
            )
            return
        }

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish(next)
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish(next)
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = publish(next)
            override fun onQueueTitleChanged(title: CharSequence?) = publish(next)
            override fun onSessionDestroyed() {
                mainHandler.post {
                    if (activeController?.sessionToken == next.sessionToken) {
                        if (preferredPackage == next.packageName) preferredPackage = null
                        attach(null)
                        appContext?.let(::refresh)
                    }
                }
            }
        }
        activeCallback = callback
        runCatching { next.registerCallback(callback, mainHandler) }
        publish(next)
    }

    /**
     * Bangun (atau lepas) `MediaControllerCompat` untuk sesi yang sama.
     * Kegagalan di sini tidak fatal: putar/jeda/lewati/geser tetap jalan lewat
     * controller framework, hanya acak & ulang yang ikut mati.
     */
    private fun attachCompat(framework: MediaController?) {
        compatController?.let { previous ->
            compatCallback?.let { callback -> runCatching { previous.unregisterCallback(callback) } }
        }
        compatController = null
        compatCallback = null
        if (framework == null) return
        val context = appContext ?: return
        val token = runCatching {
            MediaSessionCompat.Token.fromToken(framework.sessionToken)
        }.getOrNull() ?: return
        val created = runCatching { MediaControllerCompat(context, token) }.getOrNull() ?: return
        val callback = object : MediaControllerCompat.Callback() {
            override fun onShuffleModeChanged(shuffleMode: Int) = publish(framework)
            override fun onRepeatModeChanged(repeatMode: Int) = publish(framework)
        }
        compatController = created
        compatCallback = callback
        runCatching { created.registerCallback(callback, mainHandler) }
    }

    private fun publish(controller: MediaController) {
        if (activeController?.sessionToken != controller.sessionToken) return
        val metadata = controller.metadata
        val state = controller.playbackState
        val artwork = runCatching {
            (metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))?.let(::smallArtwork)
        }.getOrNull()

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L
        val reportedPosition = state?.position?.takeIf { it >= 0L } ?: 0L
        positionAnchorMs = reportedPosition
        positionAnchorElapsed = state?.lastPositionUpdateTime?.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()

        val actions = state?.actions ?: 0L
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING

        val queueItems = runCatching { controller.queue }.getOrNull().orEmpty()
        // `MediaController.getQueueIndex()` juga @hide, jadi lagu yang sedang
        // diputar dikenali dari media id metadata yang cocok dengan salah satu
        // entri antrian. Ini cara yang sama yang dipakai kontrol media Android.
        val currentMediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val activeQueueId = currentMediaId?.let { id ->
            queueItems.firstOrNull { it.description?.mediaId == id }?.queueId
        }

        mutablePlayback.value = SpotifyPlaybackState(
            notificationAccessGranted = notificationAccess,
            hasActiveSession = true,
            isPlaying = isPlaying,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).cleanMetadata(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).cleanMetadata()
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).cleanMetadata(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).cleanMetadata(),
            artwork = artwork,
            sourceLabel = sourceLabelFor(controller.packageName)
                ?: shortPackageName(controller.packageName.orEmpty()),
            sourcePackage = controller.packageName,
            positionMs = reportedPosition,
            durationMs = duration,
            speed = MediaTransportMath.safeSpeed(state?.playbackSpeed ?: 1f),
            canPlayPause = actions.has(PlaybackState.ACTION_PLAY_PAUSE) ||
                actions.has(PlaybackState.ACTION_PLAY) ||
                actions.has(PlaybackState.ACTION_PAUSE),
            canSkipNext = actions.has(PlaybackState.ACTION_SKIP_TO_NEXT),
            canSkipPrevious = actions.has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canSeek = actions.has(PlaybackState.ACTION_SEEK_TO),
            canSkipForward = actions.has(PlaybackState.ACTION_FAST_FORWARD) ||
                actions.has(PlaybackState.ACTION_SEEK_TO),
            canSkipBackward = actions.has(PlaybackState.ACTION_REWIND) ||
                actions.has(PlaybackState.ACTION_SEEK_TO),
            canStop = actions.has(PlaybackState.ACTION_STOP),
            // Bit aksi untuk acak/ulang ikut @hide dan banyak pemutar tidak
            // mengumumkannya walau menerima perintahnya. Jadi dua tombol ini
            // disediakan selama ada sesi — sama seperti kontrol media Android
            // sendiri; pemutar yang tidak mendukung cukup mengabaikannya.
            canShuffle = compatController != null,
            canRepeat = compatController != null,
            shuffleEnabled = compatController?.let { compat ->
                runCatching {
                    compat.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL ||
                        compat.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_GROUP
                }.getOrDefault(false)
            } ?: false,
            repeatMode = compatController?.let { compat ->
                runCatching { compat.repeatMode }.getOrDefault(MediaTransportMath.REPEAT_OFF)
            } ?: MediaTransportMath.REPEAT_OFF,
            queue = queueItems.map { it.toEntry(activeQueueId) },
            queueTitle = runCatching { controller.queueTitle?.toString()?.trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() },
            activeQueueId = activeQueueId,
            sources = mutablePlayback.value.sources,
        )
        mutablePositionMs.value = currentPositionMs()
        if (isPlaying) startTicker() else stopTicker()
    }

    private fun MediaSession.QueueItem.toEntry(activeQueueId: Long?) = MediaQueueEntry(
        queueId = queueId,
        mediaId = description?.mediaId,
        title = description?.title?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        subtitle = description?.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        active = activeQueueId != null && activeQueueId == queueId,
    )

    private fun Long.has(action: Long): Boolean = this and action == action

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.postDelayed(positionTicker, TICK_MS)
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(positionTicker)
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            val state = mutablePlayback.value
            mutablePositionMs.value = currentPositionMs()
            if (state.isPlaying && state.hasActiveSession) {
                mainHandler.postDelayed(this, TICK_MS)
            } else {
                tickerRunning = false
            }
        }
    }

    private fun smallArtwork(source: Bitmap): Bitmap {
        val maxSide = 192
        if (source.width <= maxSide && source.height <= maxSide) return source
        val scale = maxSide.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun String?.cleanMetadata(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

internal data class SpotifyPlaybackState(
    val notificationAccessGranted: Boolean = false,
    val hasActiveSession: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    /** Nama aplikasi sumber, misalnya "Spotify" atau "Poweramp". */
    val sourceLabel: String? = null,
    val sourcePackage: String? = null,

    // Kontrol penuh.
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val canPlayPause: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val canSkipForward: Boolean = false,
    val canSkipBackward: Boolean = false,
    val canStop: Boolean = false,
    val canShuffle: Boolean = false,
    val canRepeat: Boolean = false,
    val shuffleEnabled: Boolean = false,
    /** `PlaybackState.REPEAT_MODE_*`; lihat [MediaTransportMath]. */
    val repeatMode: Int = MediaTransportMath.REPEAT_OFF,

    // Antrian (playlist yang sedang aktif di pemutar).
    val queue: List<MediaQueueEntry> = emptyList(),
    val queueTitle: String? = null,
    val activeQueueId: Long? = null,

    // Semua sesi media yang bisa dipilih pengguna.
    val sources: List<MediaSourceEntry> = emptyList(),
)

/** Satu baris antrian lagu dari `MediaController.queue`. */
internal data class MediaQueueEntry(
    val queueId: Long,
    val mediaId: String?,
    val title: String?,
    val subtitle: String?,
    val active: Boolean,
)

/** Satu aplikasi yang punya sesi media aktif. */
internal data class MediaSourceEntry(
    val packageName: String,
    val label: String,
    val playing: Boolean,
    val hasMetadata: Boolean,
    val active: Boolean,
)

/** System service used only as the trusted NotificationListener component token. */
class SpotifyNotificationListenerService : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        SpotifyMediaBridge.refresh(this)
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        if (isMediaRelated(sbn)) SpotifyMediaBridge.refreshDebounced(this)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        if (isMediaRelated(sbn)) SpotifyMediaBridge.refreshDebounced(this)
    }

    /**
     * Notifikasi media dari aplikasi mana pun, atau notifikasi apa pun dari
     * aplikasi yang sedang jadi sumber. Sebelumnya hanya paket Spotify yang
     * diterima, jadi pemutar lain tidak pernah terdeteksi.
     */
    private fun isMediaRelated(sbn: android.service.notification.StatusBarNotification): Boolean {
        if (sbn.packageName == SpotifyMediaBridge.currentSourcePackage()) return true
        return runCatching {
            sbn.notification?.extras?.getString(android.app.Notification.EXTRA_TEMPLATE) ==
                "android.app.Notification\$MediaStyle"
        }.getOrDefault(false)
    }

    override fun onListenerDisconnected() {
        SpotifyMediaBridge.refresh(this)
        super.onListenerDisconnected()
    }
}
