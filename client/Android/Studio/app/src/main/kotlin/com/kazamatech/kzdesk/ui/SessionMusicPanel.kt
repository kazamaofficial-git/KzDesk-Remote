package com.kazamatech.kzdesk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.kzGlass
import com.kazamatech.kzdesk.ui.components.kzPurpleGlass
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch

/**
 * Tab Musik di panel sesi: sumber, kontrol penuh, antrian, dan pustaka.
 *
 * Pemutar mengambang hanya punya ruang untuk satu lagu, jadi daftar (antrian
 * dan pustaka) hidup di sini. Semua tetap memakai pola panel yang sama — grid
 * kotak yang isinya MELEBAR di bawah kotaknya, bukan halaman baru dengan
 * tombol kembali.
 */
@Composable
internal fun MusicTab(
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    val context = LocalContext.current
    val playback by SpotifyMediaBridge.playback.collectAsState()
    val positionMs by SpotifyMediaBridge.positionMs.collectAsState()
    val library by MediaLibraryBrowser.state.collectAsState()

    // Pustaka dibaca ulang setiap tab dibuka: aplikasi musik bisa dipasang
    // atau ditutup kapan saja, dan daftar yang basi hanya membingungkan.
    LaunchedEffect(Unit) { MediaLibraryBrowser.discover(context) }
    DisposableEffect(Unit) {
        onDispose { MediaLibraryBrowser.disconnect() }
    }

    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
        section("sumber-musik", kz("Sumber & izin", "Source & access")) {
            PanelHint(
                kz(
                    "KzDesk mengendalikan sesi media Android, jadi pemutar apa pun bisa dipakai — Spotify, SoundCloud, YouTube Music, atau pemutar lokal. Audio dan login tetap di aplikasi itu.",
                    "KzDesk drives Android's media session, so any player works — Spotify, SoundCloud, YouTube Music, or a local player. Audio and sign-in stay inside that app.",
                ),
            )
            if (!playback.notificationAccessGranted) {
                KzPillButton(
                    text = kz("Beri akses notifikasi", "Allow notification access"),
                    onClick = { openSpotifyNotificationAccess(context) },
                    icon = KzIcons.Music,
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    kz(
                        "Akses notifikasi aktif. {0} sesi terdeteksi.",
                        "{0} session(s) detected.",
                        playback.sources.size,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            if (playback.sources.isEmpty() && playback.notificationAccessGranted) {
                Text(
                    kz(
                        "Belum ada aplikasi musik yang membuka sesi. Putar satu lagu dulu, lalu ketuk Segarkan.",
                        "No music app has opened a session yet. Play a song first, then tap Refresh.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            playback.sources.forEach { source ->
                SourceRow(
                    source = source,
                    onSelect = {
                        val ok = SpotifyMediaBridge.selectSource(context, source.packageName)
                        if (!ok) {
                            SpotifyMediaBridge.refresh(context)
                        }
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    kz("Segarkan", "Refresh"),
                    { SpotifyMediaBridge.refresh(context) },
                    icon = KzIcons.Refresh,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                if (playback.sources.any { it.active }) {
                    KzPillButton(
                        kz("Pilih otomatis", "Auto pick"),
                        { SpotifyMediaBridge.clearSourcePreference(context) },
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        section("musik-online", kz("Musik online (SoundCloud)", "Online music (SoundCloud)")) {
            OnlineMusicSection()
        }

        section("kontrol-pemutar", kz("Kontrol penuh", "Full control")) {
            if (!playback.hasActiveSession) {
                PanelHint(
                    kz(
                        "Belum ada lagu aktif. Kontrol muncul begitu ada pemutar yang membuka sesi.",
                        "No active track yet. The controls appear as soon as a player opens a session.",
                    ),
                )
            } else {
                Text(
                    playback.title ?: kz("(tanpa judul)", "(untitled)"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(playback.artist, playback.album).joinToString(" · ")
                        .ifEmpty { playback.sourceLabel.orEmpty() },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MusicTransportControls(
                    playback = playback,
                    positionMs = positionMs,
                    onTogglePlayback = { SpotifyMediaBridge.playPause() },
                    onPrevious = { SpotifyMediaBridge.previous() },
                    onNext = { SpotifyMediaBridge.next() },
                    onSeek = { SpotifyMediaBridge.seekTo(it) },
                    onSeekBy = { SpotifyMediaBridge.seekBy(it) },
                    onStop = { SpotifyMediaBridge.stop() },
                    onToggleShuffle = { SpotifyMediaBridge.setShuffle(it) },
                    onCycleRepeat = { SpotifyMediaBridge.cycleRepeat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                val unsupported = buildList {
                    if (!playback.canSeek) add(kz("geser posisi", "seeking"))
                    if (!playback.canSkipNext || !playback.canSkipPrevious) add(kz("ganti lagu", "skipping tracks"))
                    if (!playback.canShuffle) add(kz("acak", "shuffle"))
                    if (!playback.canRepeat) add(kz("ulang", "repeat"))
                    if (!playback.canStop) add(kz("hentikan", "stop"))
                }
                if (unsupported.isNotEmpty()) {
                    Text(
                        kz(
                            "Pemutar ini tidak membuka: {0}. Tombolnya dimatikan, bukan disembunyikan.",
                            "This player does not expose: {0}. Those buttons are disabled rather than hidden.",
                            unsupported.joinToString(", "),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }

        section("antrian-musik", kz("Antrian / playlist", "Queue / playlist")) {
            PanelHint(
                kz(
                    "Antrian adalah daftar yang sudah dimuat pemutar — playlist, album, atau radio yang sedang jalan. Ketuk satu lagu untuk langsung memutarnya.",
                    "The queue is what the player already loaded — the running playlist, album, or radio. Tap a track to play it now.",
                ),
            )
            playback.queueTitle?.let { title ->
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playback.queue.isEmpty()) {
                Text(
                    kz(
                        "Pemutar ini tidak membagikan antriannya. Banyak pemutar hanya membukanya saat satu daftar sedang diputar.",
                        "This player does not share its queue. Many players only expose it while a list is playing.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    playback.queue.forEach { entry ->
                        QueueRow(
                            entry = entry,
                            onClick = { SpotifyMediaBridge.playQueueItem(entry.queueId) },
                        )
                    }
                }
                Text(
                    kz("{0} lagu di antrian", "{0} tracks in queue", playback.queue.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }

        section("pustaka-musik", kz("Pustaka", "Library")) {
            PanelHint(
                kz(
                    "Pustaka dibaca lewat MediaBrowserService: album, playlist, dan artis yang BELUM diputar. Tidak semua aplikasi membukanya — kalau ditolak, antrian tetap jalan.",
                    "The library is read through MediaBrowserService: albums, playlists, and artists that are NOT loaded yet. Not every app opens it — if refused, the queue still works.",
                ),
            )
            if (!library.discovered) {
                KzPillButton(
                    kz("Cari pemutar", "Find players"),
                    { MediaLibraryBrowser.discover(context) },
                    icon = KzIcons.Search,
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (library.sources.isEmpty()) {
                Text(
                    kz(
                        "Tidak ada aplikasi terpasang yang membuka pustaka lewat MediaBrowserService.",
                        "No installed app exposes a library through MediaBrowserService.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    library.sources.forEach { source ->
                        KzPillButton(
                            source.label,
                            { MediaLibraryBrowser.connect(context, source) },
                            icon = KzIcons.Library,
                            primary = library.connectedPackage == source.packageName,
                            compact = true,
                        )
                    }
                }
            }

            if (library.connecting) {
                Text(
                    kz("Menyambungkan pustaka…", "Connecting to the library…"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            if (library.crumbs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        library.crumbs.joinToString("  ›  ") { it.title },
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (library.crumbs.size > 1) {
                        KzPillButton(
                            kz("Naik", "Up"),
                            { MediaLibraryBrowser.up() },
                            icon = KzIcons.ChevronLeft,
                            primary = false,
                            compact = true,
                        )
                    }
                }
            }

            if (library.loading) {
                Text(
                    kz("Memuat folder…", "Loading folder…"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            library.message?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            if (library.entries.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    library.entries.forEach { entry ->
                        LibraryRow(
                            entry = entry,
                            onClick = {
                                if (entry.browsable) {
                                    MediaLibraryBrowser.open(entry, entry.title)
                                } else if (entry.playable) {
                                    MediaLibraryBrowser.play(context, entry)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: MediaSourceEntry, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (source.active) KzIcons.Check else KzIcons.Music,
            contentDescription = null,
            tint = if (source.active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                source.label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    source.active && source.playing -> kz("Dikendalikan · sedang diputar", "Controlled · playing")
                    source.active -> kz("Sedang dikendalikan", "Being controlled")
                    source.playing -> kz("Sedang diputar", "Playing")
                    else -> kz("Sesi tersedia", "Session available")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun QueueRow(entry: MediaQueueEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (entry.active) KzIcons.Play else KzIcons.Music,
            contentDescription = null,
            tint = if (entry.active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            modifier = Modifier.size(14.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.title ?: kz("(tanpa judul)", "(untitled)"),
                color = if (entry.active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 12.sp,
                fontWeight = if (entry.active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(entry: MediaLibraryEntry, onClick: () -> Unit) {
    val enabled = entry.browsable || entry.playable
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            when {
                entry.browsable -> KzIcons.Folder
                entry.playable -> KzIcons.Music
                else -> KzIcons.File
            },
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(15.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.browsable) {
            Spacer(Modifier.width(2.dp))
            Icon(
                KzIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        } else if (entry.playable) {
            Spacer(Modifier.width(2.dp))
            Icon(
                KzIcons.Play,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * Blok musik online (SoundCloud): pencarian, hasil, dan pemutar bawaan.
 * Dipakai panel musik (bila tab-nya aktif) dan pemutar mengambang sesi.
 */
@Composable
internal fun OnlineMusicSection() {
    val context = LocalContext.current
    val online by OnlineMusicPlayer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var scQuery by remember { mutableStateOf("") }
    var scResults by remember { mutableStateOf<List<ScTrack>>(emptyList()) }
    var scSearching by remember { mutableStateOf(false) }
    var scSearched by remember { mutableStateOf(false) }

    PanelHint(
        kz(
            "Cari dan putar trek publik SoundCloud langsung di dalam KzDesk — tanpa login dan tanpa aplikasi tambahan. Pemutarnya milik KzDesk sendiri, jadi tetap jalan saat panel ditutup.",
            "Search and play public SoundCloud tracks right inside KzDesk — no sign-in, no extra app. KzDesk's own player keeps going after the panel closes.",
        ),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .kzPurpleGlass(RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (scQuery.isEmpty()) {
                Text(
                    kz("Cari lagu, artis…", "Search songs, artists…"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            BasicTextField(
                value = scQuery,
                onValueChange = { scQuery = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                singleLine = true,
            )
        }
        KzPillButton(
            kz("Cari", "Search"),
            {
                val q = scQuery.trim()
                if (q.isNotEmpty() && !scSearching) {
                    scSearching = true
                    scope.launch {
                        scResults = SoundCloudClient.search(q)
                        scSearching = false
                        scSearched = true
                    }
                }
            },
            icon = KzIcons.Search,
            primary = false,
            compact = true,
            enabled = !scSearching,
        )
    }
    if (scSearching) {
        Text(
            kz("Mencari…", "Searching…"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    } else if (scSearched && scResults.isEmpty()) {
        Text(
            kz(
                "Tidak ada hasil atau jaringan sedang bermasalah. Coba kata kunci lain.",
                "No results or the network is having trouble. Try a different keyword.",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
    if (scResults.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            scResults.forEachIndexed { index, track ->
                ScResultRow(
                    track = track,
                    active = online.current?.id == track.id,
                    onClick = {
                        OnlineMusicPlayer.playQueue(context, scResults, index)
                    },
                )
            }
        }
        Text(
            kz("{0} hasil", "{0} results", scResults.size),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
    online.current?.let { track ->
        Text(
            kz("Sedang diputar", "Now playing").uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            track.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                track.artist.ifBlank { null },
                kzNow(
                    "{0} / {1}",
                    "{0} / {1}",
                    formatTrackMs(online.positionMs),
                    formatTrackMs(track.durationMs),
                ),
            ).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        when (online.status) {
            OnlineMusicPlayer.Status.LOADING -> Text(
                kz("Memuat stream…", "Loading stream…"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            OnlineMusicPlayer.Status.ERROR -> Text(
                online.message ?: kz("Pemutaran gagal.", "Playback failed."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KzPillButton(
                "\u25c0\u25c0",
                { OnlineMusicPlayer.previous() },
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            KzPillButton(
                if (online.status == OnlineMusicPlayer.Status.PLAYING) {
                    kz("Jeda", "Pause")
                } else {
                    kz("Putar", "Play")
                },
                { OnlineMusicPlayer.toggle() },
                icon = KzIcons.Music,
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            KzPillButton(
                "\u25b6\u25b6",
                { OnlineMusicPlayer.next() },
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Baris hasil pencarian SoundCloud; ketuk = putar dari trek itu. */
@Composable
private fun ScResultRow(track: ScTrack, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (active) KzIcons.Play else KzIcons.Music,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            modifier = Modifier.size(14.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(
                track.artist.takeIf { it.isNotBlank() },
                track.durationMs.takeIf { it > 0 }?.let { formatTrackMs(it) },
            ).filterNotNull().joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** m:dtk untuk durasi trek; "?:??" untuk nilai tak dikenal. */
private fun formatTrackMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
