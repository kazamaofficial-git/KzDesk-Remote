@file:OptIn(ExperimentalMaterial3Api::class)

package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.security.CrashReporter
import com.kazamatech.kzdesk.push.PushManager
import com.kazamatech.kzdesk.widget.KzFavoritesWidget
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.width
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import com.kazamatech.kzdesk.core.ConnectionLog
import com.kazamatech.kzdesk.directory.DeviceDirectoryClient
import com.kazamatech.kzdesk.directory.DeviceDirectoryParser
import com.kazamatech.kzdesk.directory.DirectoryError
import com.kazamatech.kzdesk.update.UpdateManager
import com.kazamatech.kzdesk.update.UpdatePhase
import com.kazamatech.kzdesk.update.UpdateState
import com.kazamatech.kzdesk.core.ConnectionProfile
import com.kazamatech.kzdesk.core.RdpOptions
import com.kazamatech.kzdesk.core.WakeOnLan
import com.kazamatech.kzdesk.core.KzStreamProfile
import com.kazamatech.kzdesk.security.CrashLog
import com.kazamatech.kzdesk.sessions.SessionsRepository
import com.kazamatech.kzdesk.ui.components.KzCard
import com.kazamatech.kzdesk.ui.components.KzDialog
import com.kazamatech.kzdesk.ui.components.KzField
import com.kazamatech.kzdesk.ui.components.KzIconPill
import com.kazamatech.kzdesk.ui.components.KzDivider
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzNoticeHost
import com.kazamatech.kzdesk.ui.components.KzOverlay
import com.kazamatech.kzdesk.ui.components.rememberXyNotice
import com.kazamatech.kzdesk.ui.components.KzLogo
import com.kazamatech.kzdesk.ui.components.KzGlassCard
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzRow
import com.kazamatech.kzdesk.ui.components.KzSectionLabel
import com.kazamatech.kzdesk.ui.components.KzSegmented
import com.kazamatech.kzdesk.ui.components.KzTopBar
import com.kazamatech.kzdesk.ui.components.KzWordmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Seksi di drawer samping. Judul dua bahasa dibaca di [sectionTitle]. */
internal enum class KzSection(val title: String, val titleEn: String) {
    PERANGKAT("Perangkat", "Devices"),
    TAMPILAN("Tampilan", "Display"),
    KREDENSIAL("Kredensial", "Credentials"),
    UMUM("Umum", "General"),
    KEAMANAN("Keamanan", "Security"),
    TENTANG("Tentang", "About"),
}

/** Judul seksi yang ikut bahasa aktif. */
@Composable
internal fun sectionTitle(section: KzSection): String = kz(section.title, section.titleEn)

/** Layar yang sedang tampil. */
private sealed interface KzRoute {
    data object Devices : KzRoute
    data class EditDevice(val profile: ConnectionProfile?, val pcQuickMode: Boolean = false) : KzRoute
    data object Section : KzRoute

    /** Feed komunitas — layar sendiri, dipetakan ke tab nav [KzTab.FEED]. */
    data object Feed : KzRoute

    /** Dashboard pengaturan — dipetakan ke tab nav [KzTab.PROFILE]. */
    data object Profile : KzRoute
}

/**
 * Home KzDesk: daftar perangkat + drawer seksi.
 *
 * Semua info yang ditampilkan sengaja tipis: nama perangkat, alamat, akun.
 * Preview pakai wallpaper motif OS perangkat (digambar prosedural, tanpa aset).
 */
@Composable
fun KzDeskHome(
    onExit: () -> Unit,
    onReady: () -> Unit = {},
    sharedFileNotice: String? = null,
    autoResumeSignal: Int = 0,
    skipAutoResume: Boolean = false,
    onDismissSharedNotice: () -> Unit = {},
    onAuthenticateConnect: (() -> Unit) -> Unit = { it() },
    /** Permintaan sambung dari widget layar utama (id perangkat). */
    connectProfileId: String? = null,
    onConnectProfileHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { SessionsRepository(context.applicationContext) }
    val favoritesFlow = remember { repo.favorites() }
    val favorites by favoritesFlow.collectAsState(initial = emptyList())
    // Data siap = daftar perangkat sudah keluar sekali dari database; dipakai
    // splash supaya dia berhenti tepat waktu, bukan menebak-nebak.
    var dataReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        favoritesFlow.first()
        dataReady = true
    }
    LaunchedEffect(dataReady) { if (dataReady) onReady() }
    val scope = rememberCoroutineScope()
    val appPrefs = remember { AppPrefs(context) }
    var drawerOpen by remember { mutableStateOf(false) }
    var helpToolsOpen by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(KzSection.PERANGKAT) }
    // Tab nav bawah (3 tujuan). `section` tetap ada karena seksi dibuka dari
    // drawer maupun dari layar Profil, dan keduanya menggambar SectionScreen.
    var tab by remember { mutableStateOf(KzTab.HOME) }
    var route by remember { mutableStateOf<KzRoute>(KzRoute.Devices) }
    var crashLog by remember { mutableStateOf(CrashLog.last(context.applicationContext)) }
    var showCrash by remember { mutableStateOf(false) }
    val bootTail = remember { ConnectionLog.tailFromFile(context.applicationContext, 20) }
    var showBoot by remember { mutableStateOf(false) }
    var feedbackOpen by remember { mutableStateOf(false) }
    var backupModalOpen by remember { mutableStateOf(false) }
    var backupJsonInput by remember { mutableStateOf("") }
    var confirmRestoreProfiles by remember { mutableStateOf<List<Pair<ConnectionProfile, Boolean>>?>(null) }
    var updateModalOpen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    var confirmDeleteDevice by remember { mutableStateOf<ConnectionProfile?>(null) }
    var confirmExitApp by remember { mutableStateOf(false) }
    var wolWaitingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    // Pesan app sendiri (bukan Toast bawaan Android).
    val notice = rememberXyNotice()

    fun connect(profile: ConnectionProfile) {
        onAuthenticateConnect {
            scope.launch { repo.touch(profile) }
            val directoryId = runCatching { RdpOptions.of(context, profile.id).directoryId }.getOrDefault("")
            if (directoryId.isBlank()) {
                context.startActivity(KzDeskSessionActivity.connectIntent(context, profile))
            } else {
                // Mode ID Device: cari alamat PC (LAN/VPN/publik) lewat server lalu sambung RDP.
                scope.launch {
                    notice.show(kzNow("Mencari PC…", "Looking up PC…"))
                    val password = profile.password.orEmpty()
                    val (route, failure) = DeviceDirectoryClient.resolveRoute(directoryId, password)
                    if (route != null) {
                        runCatching { com.kazamatech.kzdesk.push.PushManager.subscribeDevice(context, directoryId, password) }
                        context.startActivity(
                            KzDeskSessionActivity.connectIntent(
                                context,
                                profile.copy(host = route.host, port = route.port, username = route.username),
                            ),
                        )
                    } else {
                        val (id, en) = DeviceDirectoryParser.message(
                            failure?.error ?: DirectoryError.NETWORK,
                            failure?.retryAfterMs ?: 0L,
                        )
                        notice.show(kzNow(id, en))
                    }
                }
            }
        }
    }

    // Widget layar utama: sambung ke perangkat yang diketuk (lewat alur sambung biasa, termasuk kunci sidik jari).
    LaunchedEffect(connectProfileId, favorites, dataReady) {
        val wanted = connectProfileId ?: return@LaunchedEffect
        val target = favorites.firstOrNull { it.id == wanted }
        if (target == null) {
            if (dataReady) onConnectProfileHandled()   // perangkat sudah dihapus
            return@LaunchedEffect
        }
        onConnectProfileHandled()
        connect(target)
    }
    LaunchedEffect(favorites) { KzFavoritesWidget.requestUpdate(context) }

    LaunchedEffect(autoResumeSignal, skipAutoResume, dataReady) {
        if (autoResumeSignal <= 0) return@LaunchedEffect
        if (skipAutoResume) {
            // Home navigation and Share are deliberate; consume the marker so a
            // later lifecycle callback cannot unexpectedly reopen the session.
            appPrefs.clearAutoResumeSession()
            return@LaunchedEffect
        }
        if (!dataReady) return@LaunchedEffect
        if (!appPrefs.autoResumeLastSession) {
            appPrefs.clearAutoResumeSession()
            return@LaunchedEffect
        }

        val profileId = appPrefs.autoResumeProfileId
        val savedAt = appPrefs.autoResumeSavedAtMillis
        val now = System.currentTimeMillis()
        if (profileId.isNullOrBlank() || savedAt <= 0L || now < savedAt ||
            now - savedAt > AppPrefs.AUTO_RESUME_WINDOW_MS
        ) {
            appPrefs.clearAutoResumeSession(profileId)
            return@LaunchedEffect
        }

        val savedProfiles = favoritesFlow.first()
        val profile = savedProfiles.firstOrNull { it.id == profileId }
        if (profile == null) {
            appPrefs.clearAutoResumeSession(profileId)
            return@LaunchedEffect
        }

        // Consume before launching so a failed auth or Activity recreation cannot
        // create an auto-launch loop. Credentials are loaded only from the vault.
        appPrefs.clearAutoResumeSession(profileId)
        val existing = KzSessionRegistry.list().firstOrNull { it.id == profileId }
        if (existing != null) existing.open() else connect(profile)
    }

    fun wakeAndConnect(profile: ConnectionProfile) {
        val opts = RdpOptions.of(context, profile.id)
        val mac = opts.macAddress?.trim().orEmpty()
        if (WakeOnLan.parseMacBytes(mac) == null) {
            notice.show(kzNow("MAC Address Wake-on-LAN belum diatur atau tidak valid", "Wake-on-LAN MAC address is missing or invalid"))
            return
        }
        wolWaitingProfile = profile
        scope.launch {
            val targets = listOf(opts.wolBroadcast.ifBlank { "255.255.255.255" }, profile.host).distinct()
            val sendRes = withContext(Dispatchers.IO) {
                runCatching {
                    targets.forEach { target ->
                        WakeOnLan.sendMagicPacket(mac, target, opts.wolPort)
                    }
                }
            }
            if (sendRes.isFailure) {
                wolWaitingProfile = null
                notice.show(
                    kzNow(
                        "Gagal mengirim paket Wake-on-LAN: ${sendRes.exceptionOrNull()?.message ?: "error"}",
                        "Failed to send Wake-on-LAN packet: ${sendRes.exceptionOrNull()?.message ?: "error"}",
                    ),
                )
                return@launch
            }
            notice.show(
                kzNow(
                    "Magic Packet terkirim ke ${profile.label ?: profile.host}. Menunggu port RDP aktif...",
                    "Magic Packet sent to ${profile.label ?: profile.host}. Waiting for RDP port...",
                ),
            )
            val ready = withContext(Dispatchers.IO) {
                WakeOnLan.pollHostReady(
                    host = profile.host,
                    port = profile.port,
                    timeoutMs = 35_000L,
                    intervalMs = 1_200L,
                )
            }
            if (wolWaitingProfile?.id == profile.id) {
                wolWaitingProfile = null
                if (ready) {
                    notice.show(kzNow("PC menyala! Membuka sesi RDP...", "PC is awake! Launching RDP session..."))
                    connect(profile)
                } else {
                    notice.show(
                        kzNow(
                            "Paket WoL sudah dikirim, tetapi port ${profile.port} belum merespons dalam 35 detik.",
                            "WoL packet sent, but port ${profile.port} did not respond within 35 seconds.",
                        ),
                    )
                }
            }
        }
    }

    fun submit(profile: ConnectionProfile, rememberPassword: Boolean, startNow: Boolean) {
        scope.launch {
            val saved = runCatching { repo.save(profile, rememberPassword) }
            if (saved.isFailure) {
                notice.show(
                    kzNow("Profil tidak tersimpan: ", "Profile not saved: ") +
                        (saved.exceptionOrNull()?.message ?: kzNow("kesalahan penyimpanan", "storage error")),
                )
            } else if (saved.getOrDefault(true) == false) {
                notice.show(
                    kzNow(
                        "Profil tersimpan, tapi password gagal disimpan aman. Isi ulang nanti; password tidak diingat.",
                        "Profile saved, but the password could not be stored securely. Re-enter it later; it was not remembered.",
                    ),
                )
            }
            route = KzRoute.Devices
            if (startNow) connect(profile)
        }
    }

    /**
     * Kirim masukan ke tujuan yang dipilih pengguna.
     *
     * Tidak ada tujuan yang di-hardcode ke alamat tertentu: repo ini tidak
     * menyimpan alamat dukungan, jadi pilihannya adalah pemilih aplikasi
     * Android (bebas ke aplikasi mana pun) atau salin draf ke clipboard
     * supaya pengguna menempelkannya sendiri.
     */
    fun sendFeedback(destination: FeedbackDestination, category: String, message: String) {
        feedbackOpen = false
        val draft = feedbackShareDraft(category, message)
        when (destination) {
            FeedbackDestination.COPY -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard == null) {
                    notice.show(kzNow("Clipboard tidak tersedia", "Clipboard is unavailable"))
                    return
                }
                runCatching {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("KzDesk Remote feedback", draft),
                    )
                }.onSuccess {
                    notice.show(
                        kzNow(
                            "Draf disalin. Tempel ke aplikasi mana pun.",
                            "Draft copied. Paste it into any app.",
                        ),
                    )
                }.onFailure {
                    notice.show(kzNow("Gagal menyalin draf", "Could not copy the draft"))
                }
            }

            FeedbackDestination.SHARE -> {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "KzDesk Remote feedback")
                    putExtra(Intent.EXTRA_TEXT, draft)
                }
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            sendIntent,
                            kzNow("Pilih aplikasi untuk berbagi", "Choose an app to share"),
                        ),
                    )
                }.onFailure {
                    notice.show(kzNow("Menu berbagi tidak tersedia", "Share menu is unavailable"))
                }
            }
        }
    }

    BackHandler {
        when {
            confirmRestoreProfiles != null -> confirmRestoreProfiles = null
            backupModalOpen -> backupModalOpen = false
            updateModalOpen -> updateModalOpen = false
            confirmDeleteDevice != null -> confirmDeleteDevice = null
            confirmExitApp -> confirmExitApp = false
            feedbackOpen -> feedbackOpen = false
            drawerOpen -> drawerOpen = false
            route != KzRoute.Devices || section != KzSection.PERANGKAT -> {
                route = KzRoute.Devices
                section = KzSection.PERANGKAT
                tab = KzTab.HOME
            }
            else -> confirmExitApp = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Kolom: konten mendapat sisa tinggi, nav bawah punya tinggi tetap
            // sehingga tidak menutupi isi layar.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().weight(1f)) {

            when (val current = route) {

                is KzRoute.EditDevice -> AddDeviceScreen(
                    existing = current.profile,
                    savedUsers = favorites.mapNotNull { it.username }.distinct(),
                    savedProfiles = favorites,
                    initialPcQuickMode = current.pcQuickMode,
                    onCancel = { route = KzRoute.Devices },
                    onSubmit = { profile, remember, startNow -> submit(profile, remember, startNow) },
                )

                KzRoute.Devices -> if (section == KzSection.PERANGKAT) {
                    DevicesScreen(
                        favorites = favorites,
                        crashLog = crashLog,
                        bootTail = bootTail,
                        sharedFileNotice = sharedFileNotice,
                        onDismissSharedNotice = onDismissSharedNotice,
                        onMenu = { drawerOpen = true },
                        onAddRdp = { route = KzRoute.EditDevice(null, pcQuickMode = false) },
                        onAddPcQuick = { route = KzRoute.EditDevice(null, pcQuickMode = true) },
                        onEdit = {
                            route = KzRoute.EditDevice(
                                it,
                                pcQuickMode = RdpOptions.of(context, it.id).pcConnectMode,
                            )
                        },
                        onConnect = { connect(it) },
                        onWakeConnect = { wakeAndConnect(it) },
                        onDelete = { profile -> confirmDeleteDevice = profile },
                        onShowCrash = { showCrash = true },
                        onShowBoot = { showBoot = true },
                    )
                } else {
                    SectionScreen(
                        section = section,
                        favorites = favorites,
                        onMenu = { drawerOpen = true },
                        appPrefs = appPrefs,
                        onEditDevice = {
                            route = KzRoute.EditDevice(
                                it,
                                pcQuickMode = RdpOptions.of(context, it.id).pcConnectMode,
                            )
                        },
                        onClearAllCredentials = {
                            scope.launch {
                                repo.clear()
                                notice.show(
                                    kzNow(
                                        "Kredensial & perangkat dihapus",
                                        "Credentials and devices cleared",
                                    ),
                                )
                            }
                        },
                        onShowLog = { showBoot = true },
                    )
                }

                KzRoute.Feed -> KzFeedScreen(onMenu = { drawerOpen = true })

                KzRoute.Profile -> KzProfileScreen(
                    onMenu = { drawerOpen = true },
                    onOpenSection = {
                        section = it
                        route = KzRoute.Devices
                    },
                    appPrefs = appPrefs,
                    deviceCount = favorites.size,
                    onShowLog = { showBoot = true },
                    onOpenFeedback = { feedbackOpen = true },
                )

                KzRoute.Section -> Unit
            }
                }

                // Nav bawah disembunyikan saat form tambah/ubah perangkat terbuka
                // supaya layar itu tetap penuh dan tombol simpan tidak tertutup.
                if (route !is KzRoute.EditDevice) {
                    KzBottomNavHairline()
                    KzBottomNav(
                        selected = tab,
                        onSelect = { next ->
                            tab = next
                            when (next) {
                                KzTab.HOME -> {
                                    section = KzSection.PERANGKAT
                                    route = KzRoute.Devices
                                }
                                KzTab.FEED -> route = KzRoute.Feed
                                KzTab.PROFILE -> route = KzRoute.Profile
                            }
                        },
                    )
                }
            }
        }

    // ---- drawer kiri (custom, bukan ModalNavigationDrawer bawaan) ----
    if (drawerOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable { drawerOpen = false }
                .zIndex(40f),
        )
    }
    AnimatedVisibility(
        visible = drawerOpen,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it },
        modifier = Modifier.zIndex(41f),
    ) {
        Column(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {}
                .padding(vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KzLogo(modifier = Modifier.size(30.dp))
                Column {
                    KzWordmark(fontSize = 21.sp)
                    Text(
                        "KazamaDev \u2022 Remote Desktop",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            KzDivider()
            Spacer(Modifier.height(8.dp))
            KzSection.entries.filter { it != KzSection.TAMPILAN }.forEach { item ->
                KzRow(
                    // Dulu title mentah (Indonesia saja) — drawer satu-satunya
                    // tempat yang tidak ikut bahasa. Sekarang dua bahasa.
                    title = kz(item.title, item.titleEn),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    leading = when (item) {
                        KzSection.PERANGKAT -> KzIcons.Monitor
                        KzSection.TAMPILAN -> KzIcons.Fit
                        KzSection.KREDENSIAL -> KzIcons.Lock
                        KzSection.UMUM -> KzIcons.Sliders
                        // Dulu Info — sama dengan Tentang, jadi dua seksi
                        // bersebelahan punya ikon identik.
                        KzSection.KEAMANAN -> KzIcons.Shield
                        KzSection.TENTANG -> KzIcons.Info
                    },
                    leadingTint = if (item == section) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        section = item
                        route = KzRoute.Devices
                        // Tab ikut menyorot: seksi adalah bagian Profil, kecuali
                        // Perangkat yang memang isi Beranda.
                        tab = if (item == KzSection.PERANGKAT) KzTab.HOME else KzTab.PROFILE
                        drawerOpen = false
                    },
                )
            }
            KzRow(
                title = kz("Alat & bantuan", "Tools & help"),
                subtitle = kz("Cadangan, pembaruan, dan masukan", "Backup, updates, and feedback"),
                modifier = Modifier.padding(horizontal = 8.dp),
                leading = KzIcons.Folder,
                trailing = {
                    Text(
                        if (helpToolsOpen) "−" else "+",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { helpToolsOpen = !helpToolsOpen },
            )
            if (helpToolsOpen) {
                KzPillButton(
                    text = kz("Cadangkan / Pulihkan Profil", "Backup / Restore Profiles"),
                    onClick = {
                        drawerOpen = false
                        backupJsonInput = exportProfilesJson(context, favorites)
                        backupModalOpen = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    primary = false,
                    icon = KzIcons.Folder,
                    compact = true,
                )
                Spacer(Modifier.height(6.dp))
                KzPillButton(
                    text = kz("Cek Pembaruan", "Check for Updates"),
                    onClick = {
                        drawerOpen = false
                        updateModalOpen = true
                        scope.launch { UpdateManager.check(context, force = true) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    primary = false,
                    icon = KzIcons.Shield,
                    compact = true,
                )
                KzPillButton(
                    text = kz("Panduan", "Guide"),
                    onClick = { drawerOpen = false; guideOpen = true },
                    primary = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(6.dp))
                KzPillButton(
                    text = kz("Masukan & saran", "Feedback & suggestions"),
                    onClick = {
                        drawerOpen = false
                        feedbackOpen = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    primary = false,
                    icon = KzIcons.Info,
                    compact = true,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "v${appVersion(context)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
    }

    if (showCrash) {
        InfoDialog(
            title = kz("Log error terakhir", "Last error log"),
            body = crashLog.orEmpty(),
            onDismiss = {
                showCrash = false
                CrashLog.clear(context.applicationContext)
                crashLog = null
            },
        )
    }
    if (showBoot) {
        // Dua bagian: log app + log native FreeRDP (kanal audio/mikrofon,
        // clipboard, drive, DISP). Yang native muncul kalau appender file
        // aktif — dipasang KzApp sebelum library dimuat.
        val nativeTail = remember(showBoot) { ConnectionLog.nativeTail(160) }
        val body = buildString {
            if (nativeTail.isNotEmpty()) {
                appendLine("=== LOG NATIVE (FreeRDP) ===")
                appendLine(nativeTail.joinToString("\n"))
                appendLine()
            }
            appendLine("=== LOG APP (kzdesk-boot.log) ===")
            append(bootTail.joinToString("\n"))
        }
        InfoDialog(
            title = kz("Log sesi terakhir", "Last session log"),
            body = body,
            onDismiss = { showBoot = false },
        )
    }
    if (feedbackOpen) {
        FeedbackDialog(
            onDismiss = { feedbackOpen = false },
            onSend = ::sendFeedback,
        )
    }
    confirmDeleteDevice?.let { target ->
        KzDialog(
            title = kz("Hapus perangkat?", "Delete device?"),
            body = kz(
                "Hapus \"{0}\" beserta password dan pengaturannya?",
                "Delete \"{0}\" along with its saved password and settings?",
                target.label ?: "${target.host}:${target.port}",
            ),
            confirmLabel = kz("Hapus", "Delete"),
            onConfirm = {
                confirmDeleteDevice = null
                scope.launch {
                    repo.remove(target.id)
                    com.kazamatech.kzdesk.core.RdpOptions.clear(context, target.id)
                    DisplayPrefs.clear(context, target.id)
                    notice.show(kzNow("Perangkat dihapus", "Device deleted"))
                }
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmDeleteDevice = null },
        )
    }
    wolWaitingProfile?.let { target ->
        KzDialog(
            title = kz("Membangunkan PC (Wake-on-LAN)", "Waking PC (Wake-on-LAN)"),
            body = kz(
                "Magic Packet sudah dikirim ke \"{0}\" ({1}:{2}). KzDesk sedang memantau kesiapan port RDP otomatis hingga 35 detik...",
                "Magic Packet sent to \"{0}\" ({1}:{2}). KzDesk is automatically polling the RDP port for up to 35 seconds...",
                target.label ?: target.host,
                target.host,
                target.port,
            ),
            confirmLabel = kz("Konek Sekarang", "Connect Now"),
            onConfirm = {
                wolWaitingProfile = null
                connect(target)
            },
            dismissLabel = kz("Batal Tunggu", "Cancel Wait"),
            onDismiss = { wolWaitingProfile = null },
        )
    }

    if (confirmExitApp) {
        KzDialog(
            title = kz("Keluar dari KzDesk Remote?", "Exit KzDesk Remote?"),
            body = kz(
                "Tutup aplikasi sekarang? Sesi yang sedang aktif di latar akan tetap berjalan jika keep-alive menyala.",
                "Close the application now? Active background sessions will stay running while keep-alive is enabled.",
            ),
            confirmLabel = kz("Keluar", "Exit"),
            onConfirm = {
                confirmExitApp = false
                onExit()
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmExitApp = false },
        )
    }

    if (backupModalOpen) {
        KzOverlay(
            title = kz("Cadangkan & Pulihkan Profil (JSON)", "Backup & Restore Profiles (JSON)"),
            onDismiss = { backupModalOpen = false },
        ) {
            Text(
                kz(
                    "Salin JSON di bawah untuk mencadangkan seluruh daftar Koneksi PC & Koneksi RDP Anda, atau tempel JSON cadangan lalu ketuk Pulihkan.",
                    "Copy the JSON below to back up all your PC Connect & RDP profiles, or paste a backup JSON and tap Restore.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KzField(
                value = backupJsonInput,
                onValueChange = { backupJsonInput = it },
                label = kz("Data JSON Profil Perangkat", "Device Profiles JSON Data"),
                hint = "{\"version\":1,\"profiles\":[...]}",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    text = kz("Salin Cadangan", "Copy Backup"),
                    onClick = {
                        val json = exportProfilesJson(context, favorites)
                        backupJsonInput = json
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("kzdesk-profiles-backup", json))
                        notice.show(kzNow("Cadangan JSON disalin ke clipboard ({0} perangkat)", "Backup JSON copied to clipboard ({0} devices)", favorites.size))
                    },
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    text = kz("Pulihkan JSON", "Restore JSON"),
                    onClick = {
                        val parsed = importProfilesJson(backupJsonInput)
                        if (parsed.isEmpty()) {
                            notice.show(kzNow("Format JSON tidak valid atau kosong", "Invalid or empty JSON format"))
                        } else {
                            confirmRestoreProfiles = parsed
                        }
                    },
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            KzPillButton(
                text = kz("Tutup", "Close"),
                onClick = { backupModalOpen = false },
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    confirmRestoreProfiles?.let { listToRestore ->
        KzDialog(
            title = kz("Pulihkan {0} profil perangkat?", "Restore {0} device profiles?", listToRestore.size),
            body = kz(
                "Tambahkan / perbarui {0} profil perangkat dari data cadangan JSON sekarang?",
                "Add / update {0} device profiles from the JSON backup now?",
                listToRestore.size,
            ),
            confirmLabel = kz("Pulihkan", "Restore"),
            onConfirm = {
                val items = listToRestore
                confirmRestoreProfiles = null
                backupModalOpen = false
                scope.launch {
                    items.forEach { (prof, isPcMode) ->
                        runCatching {
                            repo.save(prof, rememberPassword = false)
                            val opts = RdpOptions.of(context, prof.id)
                            if (isPcMode) {
                                opts.withPcStreamEngine(opts.pcStreamEngine).write(context, prof.id)
                            } else {
                                opts.copy(pcConnectMode = false).write(context, prof.id)
                            }
                        }
                    }
                    notice.show(kzNow("{0} profil perangkat berhasil dipulihkan", "{0} device profiles restored", items.size))
                }
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmRestoreProfiles = null },
        )
    }

    if (updateModalOpen) {
        KzOverlay(
            title = kz("Pembaruan & Notifikasi", "Updates & Notifications"),
            onDismiss = { updateModalOpen = false },
        ) {
            UpdatePanel(onClose = { updateModalOpen = false })
        }
    }

    // Auto-update: cek otomatis saat Home dibuka (dibatasi 6 jam), buka dialog sekali
    // per proses bila ada versi baru atau APK sudah siap dipasang.
    LaunchedEffect(Unit) { UpdateManager.check(context, force = false) }
    LaunchedEffect(Unit) { com.kazamatech.kzdesk.push.PushManager.refreshIfEnabled(context) }
    // Laporan crash: hanya bila pengguna memilih ikut (opt-in); isi sudah disamarkan.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { CrashReporter.sendPending(context, com.kazamatech.kzdesk.KzConfig.API_BASE, appVersion(context)) }
        }
    }
    LaunchedEffect(dataReady) { if (dataReady && !AppPrefs(context).guideSeen) guideOpen = true }
    if (guideOpen) {
        BeginnerGuideDialog(onClose = {
            guideOpen = false
            AppPrefs(context).guideSeen = true
            if (android.os.Build.VERSION.SDK_INT >= 33 && !PushManager.canShowNotifications(context)) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        })
    }
    LaunchedEffect(UpdateState.phase) {
        val phase = UpdateState.phase
        if ((phase == UpdatePhase.AVAILABLE || phase == UpdatePhase.READY) && !UpdateState.promptShown) {
            UpdateState.promptShown = true
            updateModalOpen = true
        }
    }

    // Pesan app sendiri — paling atas supaya tidak ketutup drawer/panel.
    KzNoticeHost(state = notice, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/**
 * Sesi yang sedang hidup di proses ini.
 *
 * Daftarnya dibaca dari [KzSessionRegistry] dan disegarkan tiap 1,5 detik
 * selama home tampil — cukup untuk kasus "buka server kedua sambil yang
 * pertama tetap jalan".
 */
@Composable
private fun LiveSessionsCard() {
    var live by remember { mutableStateOf(KzSessionRegistry.list()) }
    var confirmKill by remember { mutableStateOf<KzSessionRegistry.Live?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            live = KzSessionRegistry.list()
            kotlinx.coroutines.delay(1_500)
        }
    }
    if (live.isEmpty()) return
    KzCard {
        KzSectionLabel(kz("Sesi aktif ({0})", "Active sessions ({0})", live.size))
        Spacer(Modifier.height(6.dp))
        live.forEach { item ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                KzPillButton(
                    text = kz("Buka", "Open"),
                    onClick = { item.open() },
                    compact = true,
                )
                KzPillButton(
                    text = kz("Putus", "Disconnect"),
                    onClick = { confirmKill = item },
                    primary = false,
                    compact = true,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    confirmKill?.let { target ->
        KzDialog(
            title = kz("Putuskan sesi aktif?", "Disconnect active session?"),
            body = kz(
                "Putuskan koneksi ke \"{0}\" ({1}) sekarang?",
                "Disconnect from \"{0}\" ({1}) now?",
                target.label,
                target.address,
            ),
            confirmLabel = kz("Putuskan", "Disconnect"),
            onConfirm = {
                confirmKill = null
                target.kill()
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmKill = null },
        )
    }
}

@Composable
private fun DevicesScreen(
    favorites: List<ConnectionProfile>,
    crashLog: String?,
    bootTail: List<String>,
    sharedFileNotice: String?,
    onDismissSharedNotice: () -> Unit,
    onMenu: () -> Unit,
    onAddRdp: () -> Unit,
    onAddPcQuick: () -> Unit,
    onEdit: (ConnectionProfile) -> Unit,
    onConnect: (ConnectionProfile) -> Unit,
    onWakeConnect: (ConnectionProfile) -> Unit,
    onDelete: (ConnectionProfile) -> Unit,
    onShowCrash: () -> Unit,
    onShowBoot: () -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var modeFilterIndex by remember { mutableStateOf(0) } // 0 = Semua, 1 = Koneksi PC, 2 = Koneksi RDP
    var showAddBubble by remember { mutableStateOf(false) }
    var showPcConnectionNotice by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (sharedFileNotice != null) {
                Banner(
                    text = sharedFileNotice,
                    onClick = onDismissSharedNotice,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (showPcConnectionNotice) {
                Banner(
                    text = kz(
                        "Koneksi PC langsung masih dalam tahap pengembangan dan terpisah dari RDP. Fitur RDP tetap tersedia.",
                        "Direct PC connection is still in development and separate from RDP. RDP remains available.",
                    ),
                    onClick = { showPcConnectionNotice = false },
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (crashLog != null) {
                Banner(
                    text = kz(
                        "Terjadi error sebelumnya — ketuk untuk lihat log",
                        "An error happened before — tap to see the log",
                    ),
                    onClick = onShowCrash,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            val bootSuspect = bootTail.isNotEmpty() && bootTail.last().let { last ->
                !last.contains("session.connect kembali") &&
                    !last.contains("-> Connected") &&
                    !last.contains("-> Disconnected") &&
                    !last.contains("intent tanpa profil")
            }
            if (bootSuspect) {
                Banner(
                    text = kz(
                        "Sesi terakhir terhenti di tengah jalan — ketuk untuk lihat log",
                        "The last session stopped midway — tap to see the log",
                    ),
                    onClick = onShowBoot,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            KzTopBar(
                title = kz("Perangkat", "Devices"),
                onBack = null,
                actions = {
                    KzIconPill(KzIcons.Menu, onMenu, flat = true, contentDescription = kz("Menu", "Menu"))
                    KzIconPill(
                        KzIcons.Plus,
                        { showAddBubble = !showAddBubble },
                        active = true,
                        contentDescription = kz("Tambah perangkat", "Add device"),
                    )
                },
            )

            if (favorites.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        KzCard(modifier = Modifier.fillMaxWidth()) {
                            Text(kz("Belum ada perangkat", "No devices yet"), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                kz(
                                    "Pilih Koneksi PC (ID & Password via KzDeskHost.exe dengan dukungan GPU Gaming) " +
                                        "atau Koneksi RDP standar lewat IP lokal, domain, maupun Tailscale.",
                                    "Choose PC Connection (ID & Password via KzDeskHost.exe with Gaming GPU support) " +
                                        "or standard RDP Connection over a local IP, domain, or Tailscale.",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                KzPillButton(
                                    kz("Koneksi PC (ID)", "PC (ID & Pass)"),
                                    onAddPcQuick,
                                    icon = KzIcons.Plus,
                                    modifier = Modifier.weight(1f),
                                )
                                KzPillButton(
                                    kz("Koneksi RDP", "Standard RDP"),
                                    onAddRdp,
                                    primary = false,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            } else {
                val filteredFavorites = remember(favorites, searchQuery, modeFilterIndex) {
                    val q = searchQuery.trim().lowercase()
                    favorites.filter { p ->
                        val isPc = RdpOptions.of(context, p.id).pcConnectMode
                        val modeOk = when (modeFilterIndex) {
                            1 -> isPc
                            2 -> !isPc
                            else -> true
                        }
                        val queryOk = q.isEmpty() ||
                            p.label.orEmpty().lowercase().contains(q) ||
                            p.host.lowercase().contains(q) ||
                            p.username.orEmpty().lowercase().contains(q)
                        modeOk && queryOk
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { LiveSessionsCard() }
                    item {
                        KzSegmented(
                            options = listOf(
                                kz("Semua ({0})", "All ({0})", favorites.size),
                                kz("Koneksi PC", "PC Connect"),
                                kz("Koneksi RDP", "RDP Session"),
                            ),
                            selectedIndex = modeFilterIndex,
                            onSelect = { modeFilterIndex = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (favorites.size >= 2) {
                        item {
                            KzField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = kz("Cari perangkat", "Search devices"),
                                hint = kz("Nama PC, alamat IP, atau username...", "PC name, IP address, or username..."),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(filteredFavorites, key = { it.id }) { profile ->
                        DeviceCard(
                            profile = profile,
                            onConnect = { onConnect(profile) },
                            onWakeConnect = { onWakeConnect(profile) },
                            onEdit = { onEdit(profile) },
                            onDelete = { onDelete(profile) },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        // Bubble Popover Menu saat tombol + ditekan
        if (showAddBubble) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { showAddBubble = false }
                    .zIndex(25f),
            )
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 16.dp)
                    .width(296.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(Unit) {}
                    .padding(10.dp)
                    .zIndex(26f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    kz("Pilih Jenis Koneksi", "Choose Connection Type"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                KzRow(
                    title = kz("Koneksi PC (ID & Password)", "PC Connection (ID & Password)"),
                    subtitle = kz(
                        "Jalur E2EE + Ultra-Low Latency FPS Gaming (NVIDIA / AMD / Intel GPU)",
                        "E2EE + Ultra-Low Latency FPS Gaming path (NVIDIA / AMD / Intel GPU)",
                    ),
                    modifier = Modifier.padding(horizontal = 2.dp),
                    trailing = {
                        Text(
                            kz("Eksperimental", "Experimental"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    },
                    onClick = {
                        showAddBubble = false
                        onAddPcQuick()
                    },
                )
                KzRow(
                    title = kz("Koneksi RDP (Host / IP)", "RDP Connection (Host / IP)"),
                    subtitle = kz(
                        "Koneksi langsung lewat IP lokal, domain publik, atau Tailscale",
                        "Direct connection via local IP, public domain, or Tailscale",
                    ),
                    modifier = Modifier.padding(horizontal = 2.dp),
                    onClick = {
                        showAddBubble = false
                        onAddRdp()
                    },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    profile: ConnectionProfile,
    onConnect: () -> Unit,
    onWakeConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val rdpOptions = remember(profile.id) { RdpOptions.of(context, profile.id) }
    val hasWol = !rdpOptions.macAddress.isNullOrBlank()
    val streamBadge = when (rdpOptions.streamProfile) {
        KzStreamProfile.ULTRA_LOW_LATENCY -> kz("Latensi Rendah", "Low Latency")
        KzStreamProfile.HIGH_VISUAL -> "AVC444"
        KzStreamProfile.DATA_SAVER -> kz("Hemat Kuota", "Data Saver")
        KzStreamProfile.CUSTOM -> kz("Kustom", "Custom")
        KzStreamProfile.BALANCED, KzStreamProfile.AUTO -> if (rdpOptions.udpTransport) "UDP+H264" else "H264"
    }
    // Art preview milik app (bukan wallpaper RDP/OS): geometris, diturunkan
    // dari nama perangkat + jenis OS sebagai penanda kecil di pojok.
    val wall = remember(profile.id, profile.label, profile.host) {
        KzWall.WIN11.forDevice(profile.label ?: profile.host)
    }
    val previewSeed = remember(profile.id, profile.label, profile.host) {
        (profile.label ?: profile.host) + "|" + profile.host
    }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onConnect),
    ) {
        // Preview desktop penuh yang lebih tinggi (212.dp) supaya proporsi
        // monitor desktop terlihat lega dan jelas di layar Home.
        Box(
            Modifier
                .fillMaxWidth()
                .height(212.dp)
                .clip(shape),
        ) {
            DevicePreviewArt(seed = previewSeed, os = wall)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.62f),
                        ),
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, end = 14.dp, bottom = 40.dp),
            ) {
                Text(
                    profile.label ?: profile.host,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (rdpOptions.pcConnectMode) {
                        buildString {
                            append(kz("Koneksi PC", "Connect PC"))
                            append("  ·  ")
                            append(profile.host)
                            append("  ·  ")
                            append(rdpOptions.activeCodecLabel())
                        }
                    } else {
                        buildString {
                            append(profile.host).append(':').append(profile.port)
                            if (!profile.username.isNullOrBlank()) append("  ·  ").append(profile.username)
                            append("  ·  ").append(streamBadge)
                            if (rdpOptions.consoleAdmin) append("  ·  Admin")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DesktopTaskbar(Modifier.align(Alignment.BottomCenter))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KzPillButton(kz("Connect", "Connect"), onConnect, compact = true)
            if (hasWol) {
                KzPillButton(
                    kz("Bangunkan (WoL)", "Wake (WoL)"),
                    onWakeConnect,
                    primary = false,
                    compact = true,
                )
            }
            Spacer(Modifier.weight(1f))
            KzIconPill(
                KzIcons.Sliders,
                onEdit,
                size = 40.dp,
                flat = true,
                contentDescription = kz("Pengaturan RDP", "RDP settings"),
            )
            KzIconPill(
                KzIcons.Trash,
                onDelete,
                size = 40.dp,
                flat = true,
                contentDescription = kz("Hapus", "Delete"),
            )
        }
    }
}

/** Strip taskbar tipis di dasar preview supaya terbaca sebagai desktop. */
@Composable
private fun DesktopTaskbar(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(Color(0xFF090B0E))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.85f)))
        repeat(3) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.45f)),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 18.dp, height = 5.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun Banner(
    text: String,
    onClick: () -> Unit,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"

private fun exportProfilesJson(
    context: Context,
    profiles: List<ConnectionProfile>,
): String {
    val arr = org.json.JSONArray()
    profiles.forEach { p ->
        val opts = RdpOptions.of(context, p.id)
        val obj = org.json.JSONObject()
            .put("host", p.host)
            .put("port", p.port)
            .put("username", p.username.orEmpty())
            .put("domain", p.domain.orEmpty())
            .put("label", p.label.orEmpty())
            .put("pcConnectMode", opts.pcConnectMode)
        arr.put(obj)
    }
    return org.json.JSONObject()
        .put("version", 1)
        .put("profiles", arr)
        .toString()
}

private fun importProfilesJson(raw: String): List<Pair<ConnectionProfile, Boolean>> = runCatching {
    val root = org.json.JSONObject(raw.trim())
    val arr = root.optJSONArray("profiles") ?: return@runCatching emptyList()
    buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val host = obj.optString("host", "").trim()
            if (host.isEmpty()) continue
            val port = obj.optInt("port", 3389).coerceIn(1, 65535)
            val username = obj.optString("username", "").trim().ifEmpty { null }
            val domain = obj.optString("domain", "").trim().ifEmpty { null }
            val label = obj.optString("label", "").trim().ifEmpty { null }
            val isPc = obj.optBoolean("pcConnectMode", false)
            add(
                ConnectionProfile(
                    host = host,
                    port = port,
                    username = username,
                    domain = domain,
                    label = label,
                ) to isPc,
            )
        }
    }
}.getOrDefault(emptyList())



