package com.kazamatech.kzdesk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.core.ConnectionProfile
import com.kazamatech.kzdesk.core.KzSecurityProtocol
import com.kazamatech.kzdesk.core.KzStreamProfile
import com.kazamatech.kzdesk.security.CredentialVault
import com.kazamatech.kzdesk.core.coreBuildInfo
import com.kazamatech.kzdesk.ui.components.KzSlider
import com.kazamatech.kzdesk.ui.components.KzCard
import com.kazamatech.kzdesk.ui.components.KzDialog
import com.kazamatech.kzdesk.ui.components.KzIconPill
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzLogo
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzRow
import com.kazamatech.kzdesk.ui.components.KzSectionLabel
import com.kazamatech.kzdesk.ui.components.KzSegmented
import com.kazamatech.kzdesk.ui.components.KzToggleRow
import com.kazamatech.kzdesk.ui.components.KzTopBar
import com.kazamatech.kzdesk.ui.theme.KzPill

/** Router seksi drawer (selain "Perangkat" yang punya layarnya sendiri). */
@Composable
internal fun SectionScreen(
    section: KzSection,
    favorites: List<ConnectionProfile>,
    onMenu: () -> Unit,
    appPrefs: AppPrefs,
    onEditDevice: (ConnectionProfile) -> Unit,
    onClearAllCredentials: () -> Unit,
    onShowLog: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        KzTopBar(
            title = sectionTitle(section),
            onBack = null,
            actions = { KzIconPill(KzIcons.Menu, onMenu, flat = true, contentDescription = "Menu") },
        )
        when (section) {
            KzSection.TAMPILAN -> DisplaySection(favorites, onEditDevice)
            KzSection.KREDENSIAL -> CredentialsSection(favorites, onClearAllCredentials)
            KzSection.UMUM -> GeneralSection(appPrefs, onShowLog)
            KzSection.KEAMANAN -> SecuritySection(appPrefs)
            KzSection.TENTANG -> AboutSection()
            KzSection.PERANGKAT -> Unit
        }
    }
}

// =============================================================
// Tampilan — resolusi, orientasi, skala per perangkat
// =============================================================

@Composable
private fun DisplaySection(
    favorites: List<ConnectionProfile>,
    onEditDevice: (ConnectionProfile) -> Unit,
) {
    val context = LocalContext.current
    if (favorites.isEmpty()) {
        EmptyHint(
            title = kz("Belum ada perangkat", "No devices yet"),
            body = kz(
                "Tambahkan perangkat dulu, lalu atur tampilannya di sini.",
                "Add a device first, then set its display options here.",
            ),
        )
        return
    }
    var index by remember { mutableIntStateOf(0) }
    val profile = favorites[index.coerceIn(0, favorites.lastIndex)]
    var resolution by remember(profile.id) {
        mutableStateOf(DisplayPrefs.resolution(context, profile.id))
    }
    var rotation by remember(profile.id) {
        mutableStateOf(DisplayPrefs.rotation(context, profile.id))
    }
    var scale by remember(profile.id) {
        mutableIntStateOf(DisplayPrefs.dpi(context, profile.id))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KzSectionLabel(kz("Perangkat", "Devices"))
        KzSegmented(
            options = favorites.map { it.label ?: it.host },
            selectedIndex = index.coerceIn(0, favorites.lastIndex),
            onSelect = { index = it },
            modifier = Modifier.fillMaxWidth(),
        )
        KzCard {
            KzRow(
                title = profile.label ?: profile.host,
                subtitle = "${profile.host}:${profile.port}",
                trailing = {
                    KzIconPill(
                        KzIcons.Gear,
                        { onEditDevice(profile) },
                        size = 38.dp,
                        contentDescription = kz("Ubah perangkat", "Edit device"),
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            KzSectionLabel(kz("Resolusi desktop remote", "Remote desktop resolution"))
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DisplayPrefs.resolutionOptions.chunked(2).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { colIndex, option ->
                            val value = option.value
                            KzPillButton(
                                text = kz(option.id, option.en),
                                onClick = {
                                    resolution = value
                                    DisplayPrefs.setResolution(context, profile.id, value)
                                },
                                primary = value == resolution,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            KzSectionLabel(kz("Orientasi", "Orientation"))
            Spacer(Modifier.height(8.dp))
            KzSegmented(
                options = DisplayPrefs.rotations,
                selectedIndex = DisplayPrefs.rotations.indexOf(rotation).coerceAtLeast(0),
                onSelect = {
                    rotation = DisplayPrefs.rotations[it]
                    DisplayPrefs.setRotation(context, profile.id, rotation)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            KzSectionLabel(kz("Zoom lokal awal", "Initial local zoom"))
            Text(
                "$scale%",
                style = MaterialTheme.typography.titleMedium,
            )
            KzSlider(
                value = scale.toFloat(),
                onValueChange = {
                    scale = it.toInt()
                    DisplayPrefs.setDpi(context, profile.id, scale)
                },
                valueRange = 80f..200f,
                steps = 11,
            )
            Text(
                kz(
                    "Zoom lokal bisa diubah dari panel sesi tanpa memutus koneksi. DPI desktop " +
                        "Windows dan resolusi remote punya kontrol masing-masing.",
                    "Local zoom can be changed from the session panel without disconnecting. Windows " +
                        "desktop DPI and remote resolution each have their own controls.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

// =============================================================
// Kredensial
// =============================================================

@Composable
private fun CredentialsSection(
    favorites: List<ConnectionProfile>,
    onClearAll: () -> Unit,
) {
    val context = LocalContext.current
    val vault = remember { CredentialVault(context.applicationContext) }
    var tick by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmForgetProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    // Flag "password tersimpan?" dihitung ulang saat `tick` naik (tombol
    // Lupakan ditekan) atau daftar perangkat berubah. Dulu ini pakai trik
    // `if (tick < 0) Text("")` di ujung layar untuk memaksa recompose.
    val rememberedFlags = remember(tick, favorites) { favorites.map { vault.has(it.id) } }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KzSectionLabel(kz("Password tersimpan", "Saved passwords"))
        KzCard {
            Text(
                kz(
                    "Password dienkripsi AES-256-GCM; kuncinya tidak bisa keluar dari " +
                        "Android Keystore perangkat ini.",
                    "Passwords are encrypted with AES-256-GCM; the key cannot leave this " +
                        "device's Android Keystore.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (favorites.isEmpty()) {
                Text(kz("Belum ada perangkat tersimpan.", "No saved devices yet."), style = MaterialTheme.typography.bodyMedium)
            } else {
                favorites.forEachIndexed { idx, profile ->
                    val remembered = rememberedFlags[idx]
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.label ?: profile.host,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(profile.username ?: kz("tanpa username", "no username"))
                                    append(
                                        if (remembered) kz("  ·  password tersimpan", "  ·  password saved")
                                        else kz("  ·  password tidak disimpan", "  ·  password not saved"),
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (remembered) {
                            KzPillButton(
                                text = kz("Lupakan", "Forget"),
                                onClick = { confirmForgetProfile = profile },
                                primary = false,
                                compact = true,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        KzCard {
            KzRow(
                title = kz("Hapus semua kredensial & perangkat", "Delete all credentials and devices"),
                subtitle = kz("Vault dikosongkan dan daftar perangkat dibersihkan", "Vault cleared and device list wiped"),
                leading = KzIcons.Trash,
                onClick = { confirmClear = true },
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    confirmForgetProfile?.let { target ->
        KzDialog(
            title = kz("Lupakan password?", "Forget password?"),
            body = kz(
                "Hapus password tersimpan untuk \"{0}\"? Kamu akan diminta memasukkan password lagi saat menyambung.",
                "Remove the saved password for \"{0}\"? You will be asked to enter it again on the next connection.",
                target.label ?: "${target.host}:${target.port}",
            ),
            confirmLabel = kz("Lupakan", "Forget"),
            onConfirm = {
                confirmForgetProfile = null
                vault.remove(target.id)
                tick++
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmForgetProfile = null },
        )
    }

    if (confirmClear) {
        KzDialog(
            title = kz("Hapus semua?", "Delete everything?"),
            body = kz("Semua perangkat tersimpan dan password-nya akan dihapus.", "All saved devices and their passwords will be removed."),
            confirmLabel = kz("Hapus", "Delete"),
            onConfirm = {
                confirmClear = false
                onClearAll()
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmClear = false },
        )
    }
    }
}

// =============================================================
// Umum — tema, kontrol default, sesi
// =============================================================

@Composable
private fun GeneralSection(
    appPrefs: AppPrefs,
    onShowLog: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { SessionPrefs(context) }
    var hudSize by remember { mutableStateOf(prefs.hudButtonSize) }
    var autoFit by remember { mutableStateOf(prefs.autoFit) }
    var defaultUdp by remember { mutableStateOf(appPrefs.defaultUdp) }
    var defaultNetAuto by remember { mutableStateOf(appPrefs.defaultNetAuto) }
    var defaultH264 by remember { mutableStateOf(appPrefs.defaultH264) }
    var defaultDynRes by remember { mutableStateOf(appPrefs.defaultDynamicResolution) }
    var defaultClipboard by remember { mutableStateOf(appPrefs.defaultClipboard) }
    var defaultDrive by remember { mutableStateOf(appPrefs.defaultLocalDrive) }
    var defaultStreamProfile by remember {
        mutableStateOf(
            KzStreamProfile.entries.getOrElse(appPrefs.defaultStreamProfile) { KzStreamProfile.AUTO }
        )
    }
    var defaultAsyncUpdate by remember { mutableStateOf(appPrefs.defaultAsyncUpdate) }
    var defaultAsyncChannels by remember { mutableStateOf(appPrefs.defaultAsyncChannels) }
    var themeMode by remember { mutableIntStateOf(appPrefs.themeMode) }
    var autoDisconnect by remember { mutableStateOf(appPrefs.autoDisconnect) }
    var pip by remember { mutableStateOf(appPrefs.pip) }
    var autoResumeLastSession by remember { mutableStateOf(appPrefs.autoResumeLastSession) }
    var keepSystemBarsHiddenOnIme by remember {
        mutableStateOf(appPrefs.keepSystemBarsHiddenWhenKeyboardOpens)
    }
    var inputMode by remember { mutableIntStateOf(prefs.inputMode.ordinal) }
    var pointerStyle by remember { mutableIntStateOf(prefs.pointerStyle.ordinal) }
    var pointerSize by remember { mutableStateOf(prefs.pointerSize) }
    var scrollSpeed by remember { mutableStateOf(prefs.scrollSpeed) }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var lang by remember { mutableStateOf(LangPrefs.current()) }
    var showLeft by remember { mutableStateOf(prefs.showLeft) }
    var showRight by remember { mutableStateOf(prefs.showRight) }
    var showMiddle by remember { mutableStateOf(prefs.showMiddle) }
    var showScroll by remember { mutableStateOf(prefs.showScroll) }
    var showSwitch by remember { mutableStateOf(prefs.showSwitch) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KzSectionLabel(kz("Tampilan app", "App appearance"))
        KzCard {
            KzSegmented(
                options = listOf(kz("Ikut sistem", "Follow system"), kz("Gelap", "Dark"), kz("Terang", "Light")),
                selectedIndex = themeMode,
                onSelect = {
                    themeMode = it
                    // Tanpa recreate(): state tema reaktif, semua layar
                    // recompose sendiri (dulu seluruh activity di-recreate,
                    // layar kedip dan posisi scroll hilang).
                    com.kazamatech.kzdesk.ui.theme.KzThemeState.set(appPrefs, it)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            KzToggleRow(
                title = kz("Putuskan sesi saat app ke background", "Disconnect when app goes background"),
                subtitle = kz(
                    "Cegah sesi menggantung saat HP dipakai untuk hal lain",
                    "Avoid a dangling session while the phone does something else",
                ),
                checked = autoDisconnect,
                onCheckedChange = {
                    autoDisconnect = it
                    appPrefs.autoDisconnect = it
                },
            )
            KzToggleRow(
                title = kz("Picture-in-Picture", "Picture-in-Picture"),
                subtitle = kz(
                    "Layar PC jadi jendela kecil melayang saat kamu menekan Home",
                    "The PC screen becomes a small floating window when you press Home",
                ),
                checked = pip,
                onCheckedChange = {
                    pip = it
                    appPrefs.pip = it
                },
            )
        }

        KzSectionLabel(kz("Sesi terakhir", "Last session"))
        KzCard {
            KzToggleRow(
                title = kz("Lanjutkan sesi terakhir otomatis", "Automatically resume the last session"),
                subtitle = kz(
                    "Coba buka profil terakhir dalam 15 menit setelah sesi berakhir tidak sengaja. Password yang tidak tersimpan akan diminta. Putus manual tidak akan tersambung lagi.",
                    "Reopen the last profile within 15 minutes after an unexpected session close. If its password isn't saved, you'll be prompted. Manual Disconnect will not reconnect.",
                ),
                checked = autoResumeLastSession,
                onCheckedChange = {
                    autoResumeLastSession = it
                    appPrefs.autoResumeLastSession = it
                },
            )
        }

        KzSectionLabel(kz("Kontrol sesi", "Session controls"))
        KzCard {
            Text(kz("Mode input default", "Default input mode"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            KzSegmented(
                options = InputMode.entries.map { it.title },
                selectedIndex = inputMode,
                onSelect = {
                    inputMode = it
                    prefs.inputMode = InputMode.entries[it]
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                InputMode.entries[inputMode].detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(kz("Pointer", "Pointer"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            KzSegmented(
                options = PointerStyle.entries.map { kz(it.title, it.titleEn) },
                selectedIndex = pointerStyle,
                onSelect = {
                    pointerStyle = it
                    prefs.pointerStyle = PointerStyle.entries[it]
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                kz("Ukuran pointer: {0} dp", "Pointer size: {0} dp", pointerSize.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KzSlider(
                value = pointerSize,
                onValueChange = {
                    pointerSize = it
                    prefs.pointerSize = it
                },
                valueRange = 10f..52f,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                kz(
                    "Kecepatan scroll: {0}x",
                    "Scroll speed: {0}x",
                    "%.1f".format(scrollSpeed),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KzSlider(
                value = scrollSpeed,
                onValueChange = {
                    scrollSpeed = it
                    prefs.scrollSpeed = it
                },
                valueRange = 0.4f..2.5f,
            )
            KzToggleRow(
                title = kz("Getaran saat tombol ditekan", "Haptic feedback on button press"),
                subtitle = kz("Haptic halus di cluster mouse dan keyboard", "Subtle haptics on the mouse cluster and keyboard"),
                checked = haptics,
                onCheckedChange = {
                    haptics = it
                    prefs.haptics = it
                },
            )
        }

        KzSectionLabel(kz("Tombol HUD", "HUD buttons"))
        KzCard {
            Text(
                kz(
                    "Setiap tombol di layar sesi berdiri sendiri: bulat, ukurannya " +
                        "bisa diatur, dan aksinya dipilih per tombol " +
                        "(sekali klik / tahan / toggle). Geser tombol hanya saat " +
                        "mode \"Atur posisi\" menyala, supaya tidak kepencet waktu dipakai. " +
                        "Tombol bawaan di bawah ini menentukan set awal untuk perangkat " +
                        "yang belum pernah diubah.",
                    "Every on-screen button stands alone: round, resizable, and " +
                        "with its own action (single tap / " +
                        "hold / toggle). Dragging only works while \"Edit layout\" is on, " +
                        "so buttons cannot move by accident while you use them. The " +
                        "defaults below seed devices that were never customised.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KzToggleRow(
                title = kz("Tombol kiri", "Left button"),
                checked = showLeft,
                onCheckedChange = { showLeft = it; prefs.showLeft = it },
            )
            KzToggleRow(
                title = kz("Tombol kanan", "Right button"),
                checked = showRight,
                onCheckedChange = { showRight = it; prefs.showRight = it },
            )
            KzToggleRow(
                title = kz("Tombol tengah", "Middle button"),
                checked = showMiddle,
                onCheckedChange = { showMiddle = it; prefs.showMiddle = it },
            )
            KzToggleRow(
                title = kz("Scroll atas/bawah", "Scroll up/down"),
                checked = showScroll,
                onCheckedChange = { showScroll = it; prefs.showScroll = it },
            )
            KzToggleRow(
                title = kz("Tombol ganti mode input", "Input-mode switch button"),
                subtitle = kz(
                    "Pindah trackpad <-> sentuh langsung dari layar sesi",
                    "Switch trackpad <-> direct touch from the session screen",
                ),
                checked = showSwitch,
                onCheckedChange = { showSwitch = it; prefs.showSwitch = it },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                kz(
                    "Posisi cluster diatur dengan menyeretnya langsung di layar sesi.",
                    "Cluster position is set by dragging it on the session screen.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        KzSectionLabel(kz("Keyboard", "Keyboard"))
        KzCard {
            Text(
                kz(
                    "Mengetik memakai keyboard HP (IME). Tidak ada keyboard virtual " +
                        "bawaan app dan tidak ada toolbar di atas keyboard: semua " +
                        "kontrol dibuat dari tombol overlay satu-satu di layar sesi.",
                    "Typing uses the phone keyboard (IME). There is no built-in " +
                        "virtual keyboard and no toolbar above it: every control is a " +
                        "separate overlay button on the session screen.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            KzToggleRow(
                title = kz("Muat seluruh desktop (fit)", "Fit whole desktop"),
                subtitle = kz("Taskbar dan tepi desktop selalu ikut kelihatan", "Taskbar and desktop edges always stay visible"),
                checked = autoFit,
                onCheckedChange = { autoFit = it; prefs.autoFit = it },
            )
            Spacer(Modifier.height(8.dp))
            KzToggleRow(
                title = kz("Paksa bar sistem tetap tersembunyi saat mengetik", "Keep system bars hidden while typing"),
                subtitle = kz(
                    "Coba sembunyikan kembali status bar/ikon notch saat keyboard HP muncul di sesi. Berguna untuk Android 8+; sistem masih dapat menampilkannya sementara.",
                    "Re-hide the status bar and notch icons when the phone keyboard opens in a session. Helps on Android 8+; the system may still show them briefly.",
                ),
                checked = keepSystemBarsHiddenOnIme,
                onCheckedChange = {
                    keepSystemBarsHiddenOnIme = it
                    appPrefs.keepSystemBarsHiddenWhenKeyboardOpens = it
                },
            )
        }

        KzSectionLabel(kz("Keyboard & HUD", "Keyboard & HUD"))
        KzCard {
            Text(
                kz("Ukuran default tombol HUD: {0} dp", "Default HUD button size: {0} dp", hudSize.toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                kz(
                    "Ukuran ini dipakai untuk tombol baru dan tata letak bawaan. " +
                        "Tombol yang sudah diatur tetap memakai ukuran masing-masing.",
                    "New buttons and restored default layouts use this size. " +
                        "Existing custom buttons keep their individual sizes.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KzSlider(
                value = hudSize,
                onValueChange = { hudSize = it; prefs.hudButtonSize = it },
                valueRange = 56f..80f,
            )
        }

        KzSectionLabel(kz("Bahasa / Language", "Language / Bahasa"))
        KzCard {
            KzSegmented(
                options = KzLang.entries.map { it.label },
                selectedIndex = lang.ordinal,
                onSelect = {
                    lang = KzLang.entries[it]
                    LangPrefs.set(context, lang)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                t("home.language.sub"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        KzSectionLabel(kz("Streaming & Latensi (default perangkat baru)", "Streaming & Latency (new device defaults)"))
        KzCard {
            Text(
                kz(
                    "Nilai ini dipakai saat menambah perangkat baru. Perangkat yang " +
                        "sudah ada tetap diatur sendiri di layar Ubah perangkat.",
                    "These values are used when adding a new device. Existing devices " +
                        "keep their own settings on the Edit device screen.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(kz("Profil streaming default", "Default streaming profile"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val streamOptions = listOf(
                KzStreamProfile.AUTO,
                KzStreamProfile.BALANCED,
                KzStreamProfile.ULTRA_LOW_LATENCY,
                KzStreamProfile.HIGH_VISUAL,
                KzStreamProfile.DATA_SAVER,
            )
            streamOptions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { preset ->
                        KzPillButton(
                            text = kz(preset.title, preset.titleEn),
                            onClick = {
                                defaultStreamProfile = preset
                                appPrefs.defaultStreamProfile = preset.ordinal
                            },
                            primary = defaultStreamProfile == preset,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
            KzToggleRow(
                title = kz("Transport UDP (RDP-UDP + FEC)", "UDP transport (RDP-UDP + FEC)"),
                subtitle = kz("Jalur UDP multitransport untuk gerakan mulus", "Multitransport UDP channel for smoother motion"),
                checked = defaultUdp,
                onCheckedChange = { defaultUdp = it; appPrefs.defaultUdp = it },
            )
            KzToggleRow(
                title = kz("Deteksi bandwidth otomatis", "Automatic bandwidth detection"),
                subtitle = kz("Kualitas mengikuti kondisi jaringan secara dinamis", "Quality adapts dynamically to network conditions"),
                checked = defaultNetAuto,
                onCheckedChange = { defaultNetAuto = it; appPrefs.defaultNetAuto = it },
            )
            KzToggleRow(
                title = kz("H.264 / RemoteFX (GFX)", "H.264 / RemoteFX (GFX)"),
                subtitle = kz("Akselerasi hardware H.264 untuk video & animasi 60 FPS", "H.264 hardware acceleration for 60 FPS video & motion"),
                checked = defaultH264,
                onCheckedChange = { defaultH264 = it; appPrefs.defaultH264 = it },
            )
            KzToggleRow(
                title = kz("Async Frame Update (+async-update)", "Async Frame Update (+async-update)"),
                subtitle = kz("Render layar mandiri tanpa menunggu antrean input", "Decouple frame rendering from input queue"),
                checked = defaultAsyncUpdate,
                onCheckedChange = { defaultAsyncUpdate = it; appPrefs.defaultAsyncUpdate = it },
            )
            KzToggleRow(
                title = kz("Async Input & Channels (+async-channels)", "Async Input & Channels (+async-channels)"),
                subtitle = kz("Proses input & kanal di thread terpisah agar responsif", "Process input & channels on dedicated threads"),
                checked = defaultAsyncChannels,
                onCheckedChange = { defaultAsyncChannels = it; appPrefs.defaultAsyncChannels = it },
            )
            KzToggleRow(
                title = kz("Resolusi dinamis", "Dynamic resolution"),
                subtitle = kz("Desktop remote bisa diubah saat sesi hidup (kanal DISP)", "Change remote desktop size live (DISP channel)"),
                checked = defaultDynRes,
                onCheckedChange = { defaultDynRes = it; appPrefs.defaultDynamicResolution = it },
            )
            KzToggleRow(
                title = kz("Clipboard dua arah (Teks & Gambar)", "Two-way clipboard (Text & Image)"),
                checked = defaultClipboard,
                onCheckedChange = { defaultClipboard = it; appPrefs.defaultClipboard = it },
            )
            KzToggleRow(
                title = kz("Penyimpanan lokal (drive)", "Local storage (drive)"),
                subtitle = kz("Folder Download HP muncul sebagai drive di remote", "Phone storage shows up as a remote drive"),
                checked = defaultDrive,
                onCheckedChange = { defaultDrive = it; appPrefs.defaultLocalDrive = it },
            )
        }

        KzSectionLabel(kz("Masalah koneksi", "Connection issues"))
        KzCard {
            Text(
                kz(
                    "Audio, mikrofon, clipboard, drive, kamera, dan gateway diatur " +
                        "per perangkat di layar Tambah/Ubah perangkat.",
                    "Audio, microphone, clipboard, drive, camera and gateway are set " +
                        "per device on the Add/Edit device screen.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                kz("Inti: {0}", "Core: {0}", coreBuildInfo()),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            KzPillButton(
                kz("Lihat log sesi terakhir", "View last session log"),
                onShowLog,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

// =============================================================
// Keamanan
// =============================================================

@Composable
private fun SecuritySection(appPrefs: AppPrefs) {
    val context = LocalContext.current
    val store = remember { CertificateTrustStore(context.applicationContext) }
    var tick by remember { mutableIntStateOf(0) }
    var showTrustedCertificates by remember { mutableStateOf(false) }
    var confirmRemoveCertHost by remember { mutableStateOf<String?>(null) }
    var confirmClearAllCerts by remember { mutableStateOf(false) }
    var screenCaptureProtection by remember { mutableStateOf(appPrefs.flagSecure) }
    var requireDeviceLock by remember { mutableStateOf(appPrefs.requireDeviceLock) }
    var autoLockRemoteOnLeave by remember { mutableStateOf(appPrefs.autoLockRemoteOnLeave) }
    var clearClipboardOnDisconnect by remember { mutableStateOf(appPrefs.clearClipboardOnDisconnect) }
    var shareActiveSessionStats by remember { mutableStateOf(appPrefs.shareActiveSessionStats) }
    var defaultSecurityProtocol by remember {
        mutableStateOf(
            KzSecurityProtocol.entries.getOrElse(appPrefs.defaultSecurityProtocol) { KzSecurityProtocol.AUTO }
        )
    }
    var defaultTlsSecLevel by remember { mutableIntStateOf(appPrefs.defaultTlsSecLevel) }
    val entries = remember(tick) { store.entries() }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KzSectionLabel(kz("Perlindungan Layar & Sesi", "Screen & Session Protection"))
        KzCard {
            KzToggleRow(
                title = kz("Proteksi tangkapan layar (FLAG_SECURE)", "Screen capture protection (FLAG_SECURE)"),
                subtitle = kz(
                    "Sembunyikan pratinjau layar PC di recent apps Android & cegah perekaman layar pihak ketiga",
                    "Hide the PC preview in Android recents & block third-party screen recording",
                ),
                checked = screenCaptureProtection,
                onCheckedChange = {
                    screenCaptureProtection = it
                    appPrefs.screenCaptureProtection = it
                },
            )
            KzToggleRow(
                title = kz("Wajib autentikasi perangkat (Biometrik / PIN)", "Require device lock (Biometric / PIN)"),
                subtitle = kz(
                    "Minta sidik jari, wajah, atau PIN HP sebelum membuka koneksi remote",
                    "Prompt for phone fingerprint, face, or PIN before launching a remote session",
                ),
                checked = requireDeviceLock,
                onCheckedChange = {
                    requireDeviceLock = it
                    appPrefs.requireDeviceLock = it
                },
            )
            KzToggleRow(
                title = kz("Kunci PC otomatis saat sesi selesai (Win+L)", "Auto-lock PC on session exit (Win+L)"),
                subtitle = kz(
                    "Kirim perintah kunci layar Windows otomatis sesaat sebelum memutus koneksi",
                    "Automatically send Windows lock screen command right before disconnecting",
                ),
                checked = autoLockRemoteOnLeave,
                onCheckedChange = {
                    autoLockRemoteOnLeave = it
                    appPrefs.autoLockRemoteOnLeave = it
                },
            )
            KzToggleRow(
                title = kz("Bersihkan clipboard HP saat putus", "Clear phone clipboard on disconnect"),
                subtitle = kz(
                    "Hapus sisa teks/gambar dari sesi remote di clipboard HP setelah sesi ditutup",
                    "Wipe synced text/images from the phone clipboard after the session closes",
                ),
                checked = clearClipboardOnDisconnect,
                onCheckedChange = {
                    clearClipboardOnDisconnect = it
                    appPrefs.clearClipboardOnDisconnect = it
                },
            )
        }

        KzSectionLabel(kz("Status sesi aktif", "Active session status"))
        KzCard {
            KzToggleRow(
                title = kz("Hitung sesi aktif secara anonim", "Count active sessions anonymously"),
                subtitle = kz(
                    "Aktif otomatis. Matikan sakelar ini kapan saja untuk berhenti mengirim heartbeat sesi.",
                    "On automatically. Turn this switch off any time to stop sending session heartbeats.",
                ),
                checked = shareActiveSessionStats,
                onCheckedChange = {
                    shareActiveSessionStats = it
                    appPrefs.shareActiveSessionStats = it
                },
            )
            Text(
                kz(
                    "Yang dikirim hanya ID sesi acak sementara (berbeda untuk tiap koneksi)—bukan alamat PC, profil, username, password, clipboard, atau gambar desktop. Cloudflare tetap memproses metadata jaringan seperti IP. Angka publik adalah perkiraan sesi klien yang mengirim heartbeat, bukan jumlah orang; lease kedaluwarsa setelah 90 detik. Sakelar ini dapat dimatikan kapan saja.",
                    "Only a temporary random session ID (new for each connection) is sent—not the PC address, profile, username, password, clipboard, or desktop image. Cloudflare still processes network metadata such as IP. The public figure estimates client sessions sending heartbeats, not people; leases expire after 90 seconds. Turn this switch off any time.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        KzSectionLabel(kz("Protokol & Enkripsi Default", "Default Protocol & Encryption"))
        KzCard {
            Text(
                kz("Protokol keamanan default (perangkat baru)", "Default security protocol (new devices)"),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            val secProtocols = KzSecurityProtocol.entries
            secProtocols.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { proto ->
                        KzPillButton(
                            text = kz(proto.title, proto.titleEn),
                            onClick = {
                                defaultSecurityProtocol = proto
                                appPrefs.defaultSecurityProtocol = proto.ordinal
                            },
                            primary = defaultSecurityProtocol == proto,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                kz("Level kebijakan OpenSSL TLS default", "Default OpenSSL TLS security level"),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            KzSegmented(
                options = listOf(
                    kz("Kompatibel (0)", "Legacy (0)"),
                    kz("Standar (1)", "Standard (1)"),
                    kz("Ketat (2)", "Strict (2)"),
                ),
                selectedIndex = defaultTlsSecLevel.coerceIn(0, 2),
                onSelect = {
                    defaultTlsSecLevel = it
                    appPrefs.defaultTlsSecLevel = it
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                kz(
                    "Level 1 adalah standar modern. Gunakan Level 0 untuk Windows Server lama, atau Level 2 untuk enkripsi ketat.",
                    "Level 1 is the modern default. Use Level 0 for older Windows Servers, or Level 2 for strict ciphers.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        KzSectionLabel(kz("Penyimpanan kredensial", "Credential storage"))
        KzCard {
            Bullet(kz("Password: AES-256-GCM, kunci non-exportable di Android Keystore",
                "Passwords: AES-256-GCM, non-exportable key in the Android Keystore"))
            Bullet(kz("Password tidak pernah masuk log aplikasi atau URI yang ditulis ke log",
                "Passwords never end up in app logs or in logged URIs"))
            Bullet(kz("Backup & transfer data app dimatikan (allowBackup=false)",
                "App backup & transfer are disabled (allowBackup=false)"))
            Bullet(kz("Tidak ada kredensial yang dikirim ke layanan pihak ketiga",
                "No credentials are sent to any third-party service"))
        }
        KzSectionLabel(kz("Sertifikat server yang dipercaya", "Trusted server certificates"))
        KzCard {
            Row(Modifier.fillMaxWidth().clickable { showTrustedCertificates = !showTrustedCertificates },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${if (showTrustedCertificates) "▾" else "▸"} ${kz("Daftar tepercaya ({0})", "Trusted list ({0})", entries.size)}",
                    style = MaterialTheme.typography.titleSmall)
            }
            if (showTrustedCertificates && entries.isEmpty()) {
                Text(
                    kz(
                        "Belum ada sertifikat yang ditandai percaya. Sertifikat hanya " +
                            "diingat kalau kamu menekan \"Percaya & ingat\" saat connect.",
                        "No certificate has been trusted yet. A certificate is only " +
                            "remembered when you press \"Trust & remember\" while connecting.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (showTrustedCertificates) {
                entries.take(12).forEach { (host, fingerprint) ->
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(host, style = MaterialTheme.typography.titleSmall)
                            Text(
                                fingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Hapus per entri — dulu satu-satunya jalan adalah
                        // menghapus SEMUA kepercayaan sertifikat.
                        Box(
                            Modifier
                                .clip(KzPill)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, KzPill)
                                .clickable { confirmRemoveCertHost = host }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                kz("Hapus", "Delete"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                KzPillButton(
                    text = kz("Hapus semua kepercayaan sertifikat", "Clear all trusted certificates"),
                    onClick = { confirmClearAllCerts = true },
                    primary = false,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    confirmRemoveCertHost?.let { targetHost ->
        KzDialog(
            title = kz("Hapus sertifikat tepercaya?", "Remove trusted certificate?"),
            body = kz(
                "Hapus kepercayaan sertifikat untuk \"{0}\"? Server ini akan meminta verifikasi ulang saat menyambung.",
                "Remove certificate trust for \"{0}\"? This server will prompt for verification again on the next connection.",
                targetHost,
            ),
            confirmLabel = kz("Hapus", "Remove"),
            onConfirm = {
                confirmRemoveCertHost = null
                store.removeKey(targetHost)
                tick++
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmRemoveCertHost = null },
        )
    }

    if (confirmClearAllCerts) {
        KzDialog(
            title = kz("Hapus semua sertifikat?", "Clear all certificates?"),
            body = kz(
                "Hapus seluruh daftar sertifikat server yang dipercaya? Semua server akan meminta verifikasi ulang saat menyambung.",
                "Clear the entire trusted server certificate list? Every server will prompt for verification again on connect.",
            ),
            confirmLabel = kz("Hapus semua", "Clear all"),
            onConfirm = {
                confirmClearAllCerts = false
                store.clear()
                tick++
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmClearAllCerts = false },
        )
    }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        Modifier.padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .height(5.dp)
                .clip(KzPill)
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
                .fillMaxWidth(0.014f),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// =============================================================
// Tentang
// =============================================================

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(LangPrefs.current()) }
    val native = remember {
        runCatching { com.freerdp.freerdpcore.services.LibFreeRDP.getVersion() }.getOrNull()
    }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KzCard {
            KzLogo(modifier = Modifier.height(38.dp).fillMaxWidth(0.16f))
            Spacer(Modifier.height(12.dp))
            Text(kz("KzDesk Remote", "KzDesk Remote"), style = MaterialTheme.typography.headlineSmall)
            Text(
                t("app.tagline"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            KzRow(title = t("about.version"), subtitle = appVersionName(context))
            KzRow(
                title = t("about.engine"),
                subtitle = native ?: kz("FreeRDP (dimuat saat connect)", "FreeRDP (loaded on connect)"),
            )
            KzRow(
                title = t("about.developer"),
                subtitle = kz(
                    "Didukung oleh ${AppBrand.PUBLISHER}",
                    "Powered by ${AppBrand.PUBLISHER}",
                ),
            )
        }

        // ---- Bahasa: satu klik, langsung ganti seluruh teks app ----
        KzCard {
            KzSectionLabel(t("home.language").uppercase())
            Text(
                t("home.language.sub"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            KzSegmented(
                options = KzLang.entries.map { it.label },
                selectedIndex = lang.ordinal,
                onSelect = {
                    lang = KzLang.entries[it]
                    LangPrefs.set(context, lang)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SupportCard()

        KzCard {
            Text(
                kz(
                    "KzDesk Remote dibangun di atas FreeRDP (Apache License 2.0). " +
                        "Kode aplikasi milik KazamaDev. Daftar perubahan terhadap tree " +
                        "FreeRDP ada di KZDESK-REMOTE-NOTICE.md.",
                    "KzDesk Remote is built on FreeRDP (Apache License 2.0). The app " +
                        "code belongs to KazamaDev. The list of changes to the FreeRDP " +
                        "tree is in KZDESK-REMOTE-NOTICE.md.",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            KzPillButton(t("about.license"), { showLicense = true }, primary = false)
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showLicense) {
        KzDialog(
            title = t("about.license.title"),
            body = t("about.license.body"),
            confirmLabel = "OK",
            onConfirm = { showLicense = false },
        )
    }
    }
}

/**
 * kz("Dukung saya", "Support me") — donasi, situs resmi, dan bintang repo.
 *
 * Semua tautan dibuka lewat browser (Intent VIEW), tanpa SDK pihak ketiga
 * dan tanpa jaringan dari dalam app sendiri.
 */
@Composable
internal fun SupportCard() {
    val context = LocalContext.current
    KzCard {
        KzSectionLabel(t("about.support.title").uppercase())
        Text(
            t("about.support.body"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SupportLink(
                icon = KzIcons.Heart,
                title = t("about.support.saweria"),
                subtitle = "kazamasite.biz.id/donate",
                url = com.kazamatech.kzdesk.KzConfig.DONATE_URL,
            )
            SupportLink(
                icon = KzIcons.Sponsor,
                title = t("about.support.github"),
                subtitle = "kzdesk.kazamasite.biz.id",
                url = com.kazamatech.kzdesk.KzConfig.API_BASE,
            )
            SupportLink(
                icon = KzIcons.Star,
                title = t("about.support.star"),
                subtitle = "github.com/kazamadev/KzDesk-Remote",
                url = com.kazamatech.kzdesk.KzConfig.REPO_URL + "/stargazers",
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            t("about.thanks"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupportLink(icon: ImageVector, title: String, subtitle: String, url: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                runCatching { context.startActivity(intent) }
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            KzIcons.ExternalLink,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(16.dp).width(16.dp),
        )
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        KzCard(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    KzDialog(
        title = title,
        body = body,
        confirmLabel = kz("Tutup", "Close"),
        onConfirm = onDismiss,
        dismissLabel = null,
        onDismiss = onDismiss,
        mono = true,
    )
}

private fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"
