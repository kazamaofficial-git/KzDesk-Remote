package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.ui.components.kzPurpleGlass

import androidx.compose.ui.graphics.Color
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzPillButton

/**
 * Pemutar musik mengambang di atas sesi remote.
 *
 * Kontrol penuh, bukan cuma putar/jeda: scrub bar (posisi + durai), acak,
 * mode ulang, maju/mundur 10 detik, dan berhenti. Tombol yang tidak didukung
 * pemutar dimatikan lewat flag `can*` dari `PlaybackState.actions` — lebih
 * jujur daripada tombol yang terlihat aktif tapi tidak berbuat apa-apa.
 *
 * Efek kaca dibangun dari lapisan transparan + kilau, bukan blur sungguhan:
 * `Modifier.blur` memburamkan kontennya sendiri dan backdrop blur tidak
 * tersedia di atas SurfaceView tempat layar RDP digambar (lagi pula baru ada
 * di API 31 sedangkan minSdk proyek 24).
 */
@Composable
internal fun SpotifyFloatingPlayer(
    playback: SpotifyPlaybackState,
    positionMs: Long,
    expanded: Boolean,
    onExpand: () -> Unit,
    onMinimize: () -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onStop: () -> Unit,
    onToggleShuffle: (Boolean) -> Unit,
    onCycleRepeat: () -> Unit,
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (!expanded) {
        // Compact drag-handle style: only a small line is visible until tapped.
        Box(
            modifier = modifier
                .size(width = 72.dp, height = 36.dp)
                .clickable(
                    onClickLabel = kz("Buka pemutar musik", "Open music player"),
                    onClick = onExpand,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)),
            )
        }
    } else {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
            modifier = modifier,
        ) {
            Column(
                Modifier
                    .widthIn(min = 252.dp, max = 304.dp)
                    .kzPurpleGlass(RoundedCornerShape(18.dp))
                    .padding(12.dp)
                    // Musik online menambah isi; popup tidak boleh lebih tinggi
                    // dari layar — sisanya digulir.
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(playback, Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            // Nama aplikasi sumber, bukan tulisan "SPOTIFY" tetap:
                            // bridge sekarang menerima pemutar apa pun.
                            (playback.sourceLabel ?: kz("Pemutar", "Player")).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.1.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            playback.title
                                ?: if (playback.hasActiveSession) {
                                    playback.sourceLabel ?: kz("Sedang diputar", "Now playing")
                                } else kz("Pemutar musik", "Music player"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            playback.artist ?: playback.album ?: when {
                                playback.hasActiveSession -> kz("Menunggu info lagu…", "Waiting for track info…")
                                playback.notificationAccessGranted -> kz("Putar lagu dari aplikasi musik mana pun di HP", "Play a song from any music app on your phone")
                                else -> kz("Kontrol pemutar di HP dari sesi ini", "Control the phone player from this session")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PlayerIconButton(
                        icon = KzIcons.ChevronUp,
                        description = kz("Kecilkan pemutar musik", "Minimize music player"),
                        onClick = onMinimize,
                        modifier = Modifier.size(32.dp),
                    )
                }

                if (!playback.notificationAccessGranted) {
                    Text(
                        kz(
                            "Izinkan akses notifikasi Android agar KzDesk bisa membaca info lagu dan mengirim kontrol penuh. Pemutar apa pun didukung; login dan audio tetap di aplikasi musik itu.",
                            "Allow Android notification access so KzDesk can read track details and send full playback controls. Any music player works; sign-in and audio stay inside that app.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    KzPillButton(
                        text = kz("Beri akses notifikasi", "Allow notification access"),
                        onClick = onRequestAccess,
                        modifier = Modifier.fillMaxWidth(),
                        icon = KzIcons.Music,
                        compact = true,
                    )
                } else if (!playback.hasActiveSession) {
                    Text(
                        kz(
                            "Belum ada musik yang diputar. Putar lagu dari aplikasi musik mana pun di HP, lalu kontrol dari sini.",
                            "Nothing is playing yet. Start a song in any music app on your phone, then control it from here.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Tombol hanya ditawarkan kalau Spotify memang terpasang.
                    // Jalur "Pasang Spotify" dibuang: sumbernya tidak lagi
                    // harus Spotify, jadi KzDesk tidak mendorong satu aplikasi.
                    if (isSpotifyInstalled(context)) {
                        KzPillButton(
                            text = kz("Buka Spotify", "Open Spotify"),
                            onClick = { openSpotify(context) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = KzIcons.Music,
                            compact = true,
                        )
                    }
                } else {
                    MusicTransportControls(
                        playback = playback,
                        positionMs = positionMs,
                        onTogglePlayback = onTogglePlayback,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSeek = onSeek,
                        onSeekBy = onSeekBy,
                        onStop = onStop,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                    )
                    Text(
                        kz("Audio tetap di HP · kontrol dari KzDesk", "Audio stays on your phone · controls from KzDesk"),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Musik online bawaan (SoundCloud): selalu tersedia, tidak
                // bergantung aplikasi musik lain yang terpasang di HP.
                Text(
                    kz("Musik online (SoundCloud)", "Online music (SoundCloud)").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )
                OnlineMusicSection()
            }
        }
    }
}

/**
 * Baris kontrol transport lengkap. Dipakai dua kali: di pemutar mengambang dan
 * di tab Musik panel sesi, supaya keduanya tidak pernah berbeda perilakunya.
 */
@Composable
internal fun MusicTransportControls(
    playback: SpotifyPlaybackState,
    positionMs: Long,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onStop: () -> Unit,
    onToggleShuffle: (Boolean) -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaScrubber(
            positionMs = positionMs,
            durationMs = playback.durationMs,
            enabled = playback.canSeek,
            onSeek = onSeek,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                MediaTransportMath.formatClock(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (playback.durationMs > 0L) {
                    MediaTransportMath.formatClock(playback.durationMs)
                } else {
                    "--:--"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                description = if (playback.shuffleEnabled) {
                    kz("Acak: aktif", "Shuffle: on")
                } else {
                    kz("Acak: mati", "Shuffle: off")
                },
                icon = KzIcons.Shuffle,
                onClick = { onToggleShuffle(!playback.shuffleEnabled) },
                enabled = playback.canShuffle,
                highlighted = playback.shuffleEnabled,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(8.dp))
            PlayerIconButton(
                description = kz("Sebelumnya", "Previous"),
                icon = KzIcons.SkipPrevious,
                onClick = onPrevious,
                enabled = playback.canSkipPrevious,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(8.dp))
            PlayerIconButton(
                description = if (playback.isPlaying) kz("Jeda", "Pause") else kz("Putar", "Play"),
                icon = if (playback.isPlaying) KzIcons.Pause else KzIcons.Play,
                onClick = onTogglePlayback,
                enabled = playback.canPlayPause,
                emphasized = true,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.width(8.dp))
            PlayerIconButton(
                description = kz("Berikutnya", "Next"),
                icon = KzIcons.SkipNext,
                onClick = onNext,
                enabled = playback.canSkipNext,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(8.dp))
            PlayerIconButton(
                description = repeatDescription(playback.repeatMode),
                icon = if (playback.repeatMode == MediaTransportMath.REPEAT_ONE) {
                    KzIcons.RepeatOne
                } else {
                    KzIcons.Repeat
                },
                onClick = onCycleRepeat,
                enabled = playback.canRepeat,
                highlighted = playback.repeatMode != MediaTransportMath.REPEAT_OFF,
                modifier = Modifier.size(34.dp),
            )
        }

        // Baris kedua hanya muncul kalau pemutarnya benar-benar mendukung
        // salah satunya; kalau tidak, yang ada cuma tiga tombol mati.
        if (playback.canSkipBackward || playback.canSkipForward || playback.canStop) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIconButton(
                    description = kz("Mundur 10 detik", "Back 10 seconds"),
                    icon = KzIcons.Rewind,
                    onClick = { onSeekBy(-MediaTransportMath.SKIP_STEP_MS) },
                    enabled = playback.canSkipBackward,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(10.dp))
                PlayerIconButton(
                    description = kz("Hentikan", "Stop"),
                    icon = KzIcons.Stop,
                    onClick = onStop,
                    enabled = playback.canStop,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(10.dp))
                PlayerIconButton(
                    description = kz("Maju 10 detik", "Forward 10 seconds"),
                    icon = KzIcons.Forward,
                    onClick = { onSeekBy(MediaTransportMath.SKIP_STEP_MS) },
                    enabled = playback.canSkipForward,
                    modifier = Modifier.size(32.dp),
                )
                if (playback.speed != 1f) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "%.2fx".format(playback.speed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun repeatDescription(mode: Int): String = when (mode) {
    MediaTransportMath.REPEAT_ONE -> kz("Ulang: satu lagu", "Repeat: one track")
    MediaTransportMath.REPEAT_ALL -> kz("Ulang: semua", "Repeat: all")
    else -> kz("Ulang: mati", "Repeat: off")
}

/**
 * Scrub bar yang bisa digeser.
 *
 * `seekTo` dikirim HANYA saat jari lepas. Mengirimnya tiap gerakan akan
 * membanjiri pemutar dengan perintah seek dan membuat audio tersendat.
 */
@Composable
private fun MediaScrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(0L) }
    val latestDuration by rememberUpdatedState(durationMs)
    val latestSeek by rememberUpdatedState(onSeek)
    val shown = if (dragging) dragMs else positionMs
    val fraction = MediaTransportMath.progressFraction(shown, durationMs)

    Box(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun apply(x: Float) {
                        val usable = if (size.width > 0) x / size.width.toFloat() else 0f
                        dragMs = MediaTransportMath.positionAtFraction(usable, latestDuration)
                        dragging = true
                    }
                    apply(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        apply(change.position.x)
                        change.consume()
                    }
                    dragging = false
                    latestSeek(dragMs)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackColor = MaterialTheme.colorScheme.outline
        val fillColor = MaterialTheme.colorScheme.primary
        val knobColor = MaterialTheme.colorScheme.surface
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val centerY = size.height / 2f
            val pad = 7.dp.toPx()
            val usable = (size.width - pad * 2f).coerceAtLeast(0f)
            val stroke = 4.dp.toPx()
            drawLine(
                color = trackColor,
                start = Offset(pad, centerY),
                end = Offset(pad + usable, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            val filled = usable * fraction
            if (filled > 0f) {
                drawLine(
                    color = fillColor,
                    start = Offset(pad, centerY),
                    end = Offset(pad + filled, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = knobColor,
                radius = 6.dp.toPx(),
                center = Offset(pad + filled, centerY),
            )
        }
    }
}

@Composable
private fun Artwork(playback: SpotifyPlaybackState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        val art = playback.artwork
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = kz("Sampul album", "Album artwork"),
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Icon(
                KzIcons.Music,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(42.dp),
    enabled: Boolean = true,
    emphasized: Boolean = false,
    highlighted: Boolean = false,
) {
    val shape = CircleShape
    val background = when {
        emphasized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tint = when {
        emphasized -> MaterialTheme.colorScheme.onPrimary
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(
                if (highlighted) 1.4.dp else 1.dp,
                if (highlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (emphasized) 0.25f else 0.7f)
                },
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(if (emphasized) 20.dp else 17.dp),
        )
    }
}

private fun isSpotifyInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(SpotifyMediaBridge.SPOTIFY_PACKAGE, 0)
}.isSuccess

private fun openSpotify(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(SpotifyMediaBridge.SPOTIFY_PACKAGE)
    val intent = launch ?: Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=${SpotifyMediaBridge.SPOTIFY_PACKAGE}"),
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

internal fun openSpotifyNotificationAccess(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
