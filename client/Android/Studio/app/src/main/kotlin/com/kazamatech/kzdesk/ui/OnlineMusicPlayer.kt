package com.kazamatech.kzdesk.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kazamatech.kzdesk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pemutar musik online bawaan (SoundCloud) — MediaPlayer sendiri.
 *
 * Sengaja bukan layanan latar penuh: musik online ini pendamping sesi remote,
 * jadi cukup hidup selama proses app hidup. Sesi media + notifikasi MediaStyle
 * tetap dipasang supaya kontrol kunci layar dan headset berfungsi, dan state
 * dipancarkan lewat [state] untuk UI Compose.
 */
object OnlineMusicPlayer {
    enum class Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    data class State(
        val status: Status = Status.IDLE,
        val queue: List<ScTrack> = emptyList(),
        val index: Int = -1,
        val positionMs: Long = 0L,
        val message: String? = null,
    ) {
        val current: ScTrack? get() = queue.getOrNull(index)
    }

    private const val CHANNEL_ID = "kz_online_music"
    private const val NOTIF_ID = 0x5C01
    private const val ACTION_TOGGLE = "com.kazamatech.kzdesk.ONLINE_MUSIC_TOGGLE"
    private const val ACTION_NEXT = "com.kazamatech.kzdesk.ONLINE_MUSIC_NEXT"
    private const val ACTION_PREV = "com.kazamatech.kzdesk.ONLINE_MUSIC_PREV"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null
    private var appContext: Context? = null
    private var ticker: Job? = null

    private val buttonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TOGGLE -> toggle()
                ACTION_NEXT -> next()
                ACTION_PREV -> previous()
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        ensureChannel(app)
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(
            app,
            buttonReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        session = MediaSessionCompat(app, "KzDeskOnlineMusic").apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() = play()
                    override fun onPause() = pause()
                    override fun onSkipToNext() = next()
                    override fun onSkipToPrevious() = previous()
                    override fun onStop() = stop()
                },
            )
            isActive = true
        }
    }

    /** Ambil URL stream lalu putar antrian mulai dari [startIndex]. */
    fun playQueue(context: Context, tracks: List<ScTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        init(context)
        _state.value = State(
            status = Status.LOADING,
            queue = tracks,
            index = startIndex.coerceIn(0, tracks.size - 1),
        )
        scope.launch { loadAndPlay(_state.value.index) }
    }

    private suspend fun loadAndPlay(index: Int) {
        val track = _state.value.queue.getOrNull(index) ?: return
        val url = runCatching { SoundCloudClient.streamUrl(track) }.getOrNull()
        if (url == null) {
            _state.value = _state.value.copy(
                status = Status.ERROR,
                message = "Gagal memuat stream: ${track.title}",
            )
            updateNotification()
            return
        }
        val ctx = appContext ?: return
        val result = withContext(Dispatchers.IO) {
            runCatching {
                releasePlayer()
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    setDataSource(url)
                    setOnPreparedListener { mp ->
                        mp.start()
                        _state.value = _state.value.copy(status = Status.PLAYING)
                        updateSession()
                        updateNotification()
                        startTicker()
                    }
                    setOnCompletionListener { next() }
                    setOnErrorListener { _, what, extra ->
                        _state.value = _state.value.copy(
                            status = Status.ERROR,
                            message = "Pemutaran gagal (kode $what/$extra)",
                        )
                        updateNotification()
                        true
                    }
                    prepare()
                }
            }
        }
        result.onSuccess { mp ->
            player = mp
            updateSession()
            updateNotification()
        }.onFailure { error ->
            _state.value = _state.value.copy(
                status = Status.ERROR,
                message = error.message ?: error.javaClass.simpleName,
            )
            updateNotification()
        }
    }

    fun toggle() {
        when (_state.value.status) {
            Status.PLAYING -> pause()
            Status.PAUSED -> play()
            Status.ERROR, Status.IDLE -> _state.value.current?.let {
                scope.launch { loadAndPlay(_state.value.index) }
            }
            Status.LOADING -> Unit
        }
    }

    fun play() {
        val mp = player ?: return
        runCatching { mp.start() }
        _state.value = _state.value.copy(status = Status.PLAYING)
        updateSession()
        updateNotification()
        startTicker()
    }

    fun pause() {
        val mp = player ?: return
        runCatching { mp.pause() }
        _state.value = _state.value.copy(
            status = Status.PAUSED,
            positionMs = currentPosition(),
        )
        stopTicker()
        updateSession()
        updateNotification()
    }

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val nextIndex = (s.index + 1) % s.queue.size
        _state.value = s.copy(index = nextIndex, status = Status.LOADING, positionMs = 0)
        scope.launch { loadAndPlay(nextIndex) }
    }

    fun previous() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        // Sudah lewat 3 detik? Ulangi lagu ini, perilaku pemutar pada umumnya.
        if (currentPosition() > 3_000L) {
            seekTo(0L)
            return
        }
        val prevIndex = (s.index - 1 + s.queue.size) % s.queue.size
        _state.value = s.copy(index = prevIndex, status = Status.LOADING, positionMs = 0)
        scope.launch { loadAndPlay(prevIndex) }
    }

    fun seekTo(positionMs: Long) {
        val mp = player ?: return
        runCatching { mp.seekTo(positionMs.toInt().coerceAtLeast(0)) }
        _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    fun stop() {
        stopTicker()
        releasePlayer()
        _state.value = State()
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1f)
                .build(),
        )
        NotificationManagerCompat.from(appContext ?: return).cancel(NOTIF_ID)
    }

    private fun currentPosition(): Long =
        runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _state.value.status == Status.PLAYING) {
                _state.value = _state.value.copy(positionMs = currentPosition())
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun releasePlayer() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        }
        player = null
    }

    private fun updateSession() {
        val s = _state.value
        val track = s.current ?: return
        session?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
                .build(),
        )
        val playbackState = when (s.status) {
            Status.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            Status.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            Status.LOADING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(playbackState, currentPosition(), 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .build(),
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Musik online",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun actionPending(action: String): PendingIntent {
        val intent = Intent(action).setPackage(appContext?.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification() {
        val ctx = appContext ?: return
        val s = _state.value
        val track = s.current
        if (track == null || s.status == Status.IDLE) {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
            return
        }
        val playing = s.status == Status.PLAYING
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(track.title)
            .setContentText(track.artist.ifBlank { "SoundCloud" })
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Prev", actionPending(ACTION_PREV))
            .addAction(
                0,
                if (playing) "Pause" else "Play",
                actionPending(ACTION_TOGGLE),
            )
            .addAction(0, "Next", actionPending(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        runCatching {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build())
        }
    }
}
