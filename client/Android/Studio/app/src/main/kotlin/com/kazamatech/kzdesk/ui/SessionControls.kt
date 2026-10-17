package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.ui.input.KzStickMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import com.kazamatech.kzdesk.core.RdpOptions
import com.kazamatech.kzdesk.core.SmartResolution
import com.kazamatech.kzdesk.ui.components.KzGlassTile
import com.kazamatech.kzdesk.ui.components.KzDialog
import com.kazamatech.kzdesk.ui.components.KzField
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzNoticeState
import com.kazamatech.kzdesk.ui.components.KzOverlay
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzSegmented
import com.kazamatech.kzdesk.ui.components.KzSlider
import com.kazamatech.kzdesk.ui.components.KzToggleRow
import com.kazamatech.kzdesk.ui.components.kzGlass
import com.kazamatech.kzdesk.ui.theme.KzPill
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Warna tombol HUD tetap gelap-transparan karena duduk di atas desktop remote;
 * panel, banner editor, dan dialog mengikuti tema app.
 */
internal fun updateHudEditorDraft(current: HudKey?, updated: HudKey): HudKey? =
    if (current?.id == updated.id) updated else current

/** Tab di panel sesi — SATU panel, empat seksi jelas. Dulu dua panel kiri/kanan tanpa label: nobody tahu isinya apa. */
private enum class PanelTab(val id: String, val en: String) {
    SCREEN("Layar", "Screen"),
    INPUT("Input", "Input"),
    BUTTONS("Tombol", "Buttons"),
    SESSION("Sesi", "Session"),
}

/**
 * Kontrol sesi.
 *
 * Model (ronde 8): satu handle di tepi kanan-atas membuka SATU panel bertab —
 * Layar (zoom/resolusi/orientasi), Input (mode/keyboard/clipboard/pointer),
 * Tombol (editor tombol HUD), Sesi (screenshot/putus/info teknis).
 * Rail kanan-bawah tetap terlihat untuk keyboard, monitor, putus, dan toggle
 * visibilitas HUD; auto-hide hanya memengaruhi tombol overlay yang dapat dipindah.
 */
@Composable
fun SessionControls(
    deviceId: String,
    hostLabel: String,
    statusText: String,
    remoteSize: String,
    zoomPercent: Int,
    remoteDpi: Int,
    telemetry: com.kazamatech.kzdesk.core.TelemetrySample = com.kazamatech.kzdesk.core.TelemetrySample.EMPTY,
    pointerScreen: Offset,
    pointerVisible: Boolean,
    remoteCursor: RemoteCursor? = null,
    zoom: Float = 1f,
    inputMode: InputMode,
    externalInputDeviceConnected: Boolean = false,
    onInputModeChange: (InputMode) -> Unit,
    keys: List<HudKey>,
    onKeysChange: (List<HudKey>) -> Unit,
    latchedKeyIds: Set<String>,
    onKeyEdited: (HudKey) -> Unit,
    onDeleteKey: (HudKey) -> Unit,
    mappingMode: Boolean,
    onMappingModeChange: (Boolean) -> Unit,
    onPhase: (HudKey, HudPhase) -> Unit,
    onScrollUnits: (Int) -> Unit,
    /** Sumbu joystick HUD (HudKind.PAD_STICK), -1..1 per sumbu. */
    onStickAxis: (Float, Float) -> Unit = { _, _ -> },
    /** Joystick dengan mode sendiri WASD/Panah: (mode, x, y). Dipanggil juga saat kembali ke (0,0). */
    onStickKeysMode: (com.kazamatech.kzdesk.ui.input.KzStickMode, Float, Float) -> Unit = { _, _, _ -> },
    /** Stik KANAN preset gamepad selalu jadi pointer, apa pun mode stik. */
    onStickPointer: (Float, Float) -> Unit = { _, _ -> },
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomActual: () -> Unit,
    onZoomScale: (Float) -> Unit,
    onRemoteDpiChange: (Int) -> Unit,
    onDisplayRefreshPreferenceChange: (Int) -> Unit = {},
    recordingLabel: String = "Record PC",
    recordingStatus: String? = null,
    recordingActionEnabled: Boolean = true,
    recordingActive: Boolean = false,
    stickModeProvider: () -> KzStickMode = { KzStickMode.POINTER },
    onStickModeChange: (KzStickMode) -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onScreenshot: () -> Unit,
    onDisconnect: () -> Unit,
    onGoHome: () -> Unit = {},
    onPointerVisibilityChange: (Boolean) -> Unit,
    onResetCluster: () -> Unit,
    onRotationChange: (String) -> Unit,
    onResolutionChange: (String) -> Unit,
    onOpenKeyboard: () -> Unit = {},
    onOpenHome: () -> Unit = {},
    lastClipboard: String? = null,
    clipboardSyncEnabled: Boolean = false,
    onSendText: (String) -> Unit,
    onSendRemoteClipboardText: (String) -> Boolean = { false },
    activeUsername: String? = null,
    consoleAdminMode: Boolean = false,
    onSwitchConsoleMode: (Boolean) -> Unit = {},
    onSwitchUserSession: (String, String?, String?) -> Unit = { _, _, _ -> },
    swapMouseButtons: Boolean = false,
    onSwapMouseButtonsChange: (Boolean) -> Unit = {},
    onLockRemotePc: () -> Unit = {},
    onActivatePrivacyCurtain: () -> Unit = {},
    onGyroMouseChanged: (Boolean) -> Unit = {},
    onOverlayActiveChange: (Boolean) -> Unit = {},
    onRegisterBackHandler: ((() -> Boolean)?) -> Unit = {},
    coreInfo: List<String> = emptyList(),
    notice: KzNoticeState,
) {
    // Picture-in-Picture: jendela terlalu kecil untuk HUD, tampilkan layar PC saja.
    if (KzPipState.active) return

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val view = androidx.compose.ui.platform.LocalView.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val prefs = remember { SessionPrefs(context) }
    val spotifyPlayback by SpotifyMediaBridge.playback.collectAsState()
    val spotifyPosition by SpotifyMediaBridge.positionMs.collectAsState()
    var spotifyExpanded by remember { mutableStateOf(false) }
    var scrollPillOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var monitorGridOpen by remember { mutableStateOf(false) }
    var railMenuOpen by remember { mutableStateOf(false) }
    var drawerMode by remember { mutableStateOf(prefs.menuDrawer) }
    var tab by remember { mutableStateOf(PanelTab.SCREEN) }
    var pickerOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HudKey?>(null) }
    var pointerSize by remember { mutableFloatStateOf(prefs.pointerSize) }
    var pointerStyle by remember { mutableStateOf(prefs.pointerStyle) }
    var pointerSensitivity by remember { mutableFloatStateOf(prefs.pointerSensitivity) }
    var pointerAcceleration by remember { mutableStateOf(prefs.pointerAcceleration) }
    var inertialScroll by remember { mutableStateOf(prefs.inertialScroll) }
    var edgeScrollZone by remember { mutableStateOf(prefs.edgeScrollZone) }
    var gamepadEnabled by remember { mutableStateOf(prefs.gamepadEnabled) }
    // Mode stick dibaca lewat provider karena nilai sesungguhnya hidup di
    // activity (dipakai mapper dari luar komposisi), bukan di prefs saja.
    var stickModeUi by remember { mutableStateOf(stickModeProvider()) }
    var gyroMouseEnabled by remember { mutableStateOf(prefs.gyroMouseEnabled) }
    var showTelemetryPill by remember { mutableStateOf(prefs.showTelemetryPill) }
    var hudProfile by remember(deviceId) { mutableStateOf(prefs.hudProfile(deviceId)) }
    val pcConnectMode = remember(deviceId) { RdpOptions.of(context, deviceId).pcConnectMode }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var autoFit by remember { mutableStateOf(prefs.autoFit) }
    var plate by remember { mutableStateOf(prefs.hudPlate) }
    var hudOpacity by remember { mutableFloatStateOf(prefs.hudOpacity) }
    var showHudButtons by remember(deviceId) { mutableStateOf(prefs.hudButtonsVisible(deviceId)) }
    var autoHideHudOnExternalInput by remember {
        mutableStateOf(prefs.autoHideHudOnExternalInput)
    }
    var forceShowHudForExternalInput by remember(deviceId) { mutableStateOf(false) }
    val hudAutomaticallyHidden = autoHideHudOnExternalInput && externalInputDeviceConnected &&
        !forceShowHudForExternalInput
    val hudButtonsVisibleNow = showHudButtons && !hudAutomaticallyHidden
    var resolution by remember { mutableStateOf(DisplayPrefs.resolution(context, deviceId)) }
    var rotation by remember { mutableStateOf(DisplayPrefs.rotation(context, deviceId)) }
    var custom by remember { mutableStateOf("") }
    var textOpen by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf("") }
    var layoutJsonOpen by remember { mutableStateOf(false) }
    var templateOpen by remember { mutableStateOf(false) }
    var layoutJsonValue by remember { mutableStateOf("") }
    var confirmDeleteKey by remember { mutableStateOf<HudKey?>(null) }
    var confirmResetHud by remember { mutableStateOf(false) }
    var confirmImportLayout by remember { mutableStateOf<List<HudKey>?>(null) }
    var confirmApplyPreset by remember { mutableStateOf<HudProfilePreset?>(null) }
    var confirmLockPc by remember { mutableStateOf(false) }
    var confirmResolution by remember { mutableStateOf<String?>(null) }
    var validationAlert by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(context) { SpotifyMediaBridge.refresh(context) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                SpotifyMediaBridge.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(deviceId, externalInputDeviceConnected, autoHideHudOnExternalInput) {
        if (!externalInputDeviceConnected || !autoHideHudOnExternalInput) {
            forceShowHudForExternalInput = false
        }
    }

    val anyOverlayOpen = panelOpen || monitorGridOpen || pickerOpen || editing != null || textOpen || layoutJsonOpen || templateOpen ||
        confirmDeleteKey != null || confirmResetHud || confirmImportLayout != null ||
        confirmApplyPreset != null || confirmLockPc ||
        confirmResolution != null || validationAlert != null
    LaunchedEffect(anyOverlayOpen) {
        onOverlayActiveChange(anyOverlayOpen)
    }

    LaunchedEffect(
        panelOpen, monitorGridOpen, pickerOpen, editing, textOpen, layoutJsonOpen, templateOpen, mappingMode,
        scrollPillOpen, spotifyExpanded,
        confirmDeleteKey, confirmResetHud, confirmImportLayout, confirmApplyPreset,
        confirmLockPc, confirmResolution, validationAlert,
    ) {
        onRegisterBackHandler {
            when {
                validationAlert != null -> {
                    validationAlert = null
                    true
                }
                confirmDeleteKey != null -> {
                    confirmDeleteKey = null
                    true
                }
                confirmResetHud -> {
                    confirmResetHud = false
                    true
                }
                confirmImportLayout != null -> {
                    confirmImportLayout = null
                    true
                }
                confirmApplyPreset != null -> {
                    confirmApplyPreset = null
                    true
                }
                confirmLockPc -> {
                    confirmLockPc = false
                    true
                }
                confirmResolution != null -> {
                    confirmResolution = null
                    true
                }
                editing != null -> {
                    editing = null
                    true
                }
                pickerOpen -> {
                    pickerOpen = false
                    true
                }
                templateOpen -> {
                    templateOpen = false
                    true
                }
                layoutJsonOpen -> {
                    layoutJsonOpen = false
                    true
                }
                textOpen -> {
                    textOpen = false
                    true
                }
                spotifyExpanded -> {
                    spotifyExpanded = false
                    true
                }
                scrollPillOpen -> {
                    scrollPillOpen = false
                    true
                }
                monitorGridOpen -> {
                    monitorGridOpen = false
                    true
                }
                panelOpen -> {
                    panelOpen = false
                    true
                }
                mappingMode -> {
                    onMappingModeChange(false)
                    true
                }
                else -> false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onOverlayActiveChange(false)
            onRegisterBackHandler(null)
        }
    }

    fun copyCoreInfo() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(
            ClipData.newPlainText("KzDesk Remote", coreInfo.joinToString("\n"))
        )
        notice.show(kzNow("Info teknis tersalin", "Technical info copied"))
    }

    fun clipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(context).toString()
    }

    fun exportHudLayoutToClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val json = HudKey.exportLayoutJson(keys)
        cm?.setPrimaryClip(ClipData.newPlainText("KzDesk HUD Layout", json))
        notice.show(kzNow("Layout tombol JSON disalin ke clipboard", "Button layout JSON copied to clipboard"))
    }

    fun importHudLayoutFromRaw(raw: String): Boolean {
        val imported = HudKey.importLayoutJson(raw, prefs.hudButtonSize)
        if (imported == null) {
            notice.show(kzNow("JSON layout tidak valid atau kosong", "Layout JSON is invalid or empty"))
            return false
        }
        onKeysChange(imported)
        notice.show(
            kzNow(
                "Layout diimpor ({0} tombol)",
                "Layout imported ({0} buttons)",
                imported.size,
            ),
        )
        return true
    }

    fun haptic() {
        if (haptics) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun toggleHudButtonVisibility() {
        if (autoHideHudOnExternalInput && externalInputDeviceConnected) {
            if (hudButtonsVisibleNow) {
                forceShowHudForExternalInput = false
            } else {
                showHudButtons = true
                prefs.setHudButtonsVisible(deviceId, true)
                forceShowHudForExternalInput = true
            }
        } else {
            showHudButtons = !showHudButtons
            prefs.setHudButtonsVisible(deviceId, showHudButtons)
        }
    }

    fun replace(updated: HudKey) {
        // Keep the editor's live draft in sync with the persisted per-device layout.
        editing = updateHudEditorDraft(editing, updated)
        val updatedKeys = keys.map { if (it.id == updated.id) updated else it }
        prefs.setCustomHudKeys(deviceId, updatedKeys)
        onKeyEdited(updated)
    }

    fun add(option: HudKeyOption) {
        if (keys.size >= 36) {
            notice.show(kzNow("Maksimal 36 tombol HUD", "Maximum 36 HUD buttons"))
            return
        }
        val width = configuration.screenWidthDp.toFloat().coerceAtLeast(1f)
        val height = configuration.screenHeightDp.toFloat().coerceAtLeast(1f)
        val size = prefs.hudButtonSize
        val maxX = (width - size).coerceAtLeast(1f)
        val maxY = (height - size).coerceAtLeast(1f)
        val railCenters = listOf(
            androidx.compose.ui.geometry.Offset(width - 32f, height - 210f),
            androidx.compose.ui.geometry.Offset(width - 32f, height - 156f),
            androidx.compose.ui.geometry.Offset(width - 32f, height - 102f),
        )
        val candidates = buildList {
            var y = 72f
            while (y <= maxY) {
                var x = 12f
                while (x <= maxX) {
                    this.add(androidx.compose.ui.geometry.Offset(x, y))
                    x += 88f
                }
                y += 64f
            }
        }
        val position = candidates.firstOrNull { candidate ->
            val cx = candidate.x + size / 2f
            val cy = candidate.y + size / 2f
            val hitsExisting = keys.any { oldKey ->
                val oldCx = oldKey.x * (width - oldKey.size).coerceAtLeast(1f) + oldKey.size / 2f
                val oldCy = oldKey.y * (height - oldKey.size).coerceAtLeast(1f) + oldKey.size / 2f
                val dx = cx - oldCx
                val dy = cy - oldCy
                val minDistance = (size + oldKey.size) / 2f + 8f
                dx * dx + dy * dy < minDistance * minDistance
            }
            val hitsRail = railCenters.any { rail ->
                val dx = cx - rail.x
                val dy = cy - rail.y
                val minDistance = size / 2f + 22f + 8f
                dx * dx + dy * dy < minDistance * minDistance
            }
            val hitsPanelHandle = kotlin.math.abs(cx - (width - 30f)) < size / 2f + 38f &&
                kotlin.math.abs(cy - 70f) < size / 2f + 60f
            !hitsExisting && !hitsRail && !hitsPanelHandle
        }
        if (position == null) {
            notice.show(kzNow("Ruang kontrol penuh — pindahkan tombol dulu", "No free control space — move a button first"))
            return
        }
        val key = HudKey(
            id = "k${java.util.UUID.randomUUID()}",
            kind = option.kind,
            label = option.label,
            keyCode = option.keyCode,
            shift = option.shift,
            combo = option.combo,
            action = option.defaultAction.takeIf { it in HudKey.allowedActions(option.kind) } ?: HudAction.TAP,
            x = (position.x / maxX).coerceIn(0f, 1f),
            y = (position.y / maxY).coerceIn(0f, 1f),
            size = size,
            macroText = option.macroText,
            macroSendEnter = option.macroSendEnter,
        )
        val updatedKeys = keys + key
        onKeysChange(updatedKeys)
        prefs.setCustomHudKeys(deviceId, updatedKeys)
        hudProfile = HudProfilePreset.CUSTOM
        prefs.setHudProfile(deviceId, HudProfilePreset.CUSTOM)
        panelOpen = false
        onMappingModeChange(true)
        notice.show(
            kzNow(
                "Tombol \"{0}\" ditambahkan — geser ke posisi yang kamu mau atau tekan Selesai.",
                "Button \"{0}\" added — drag it into position or press Done.",
                key.label,
            ),
        )
    }

    Box(Modifier.fillMaxSize().zIndex(10f)) {
        if (pointerVisible && !panelOpen && !monitorGridOpen) {
            KzPointer(
                position = pointerScreen,
                sizeDp = pointerSize,
                style = pointerStyle,
                remote = remoteCursor,
                zoom = zoom,
            )
        }

        if (showTelemetryPill && !panelOpen && !monitorGridOpen && !mappingMode) {
            LiveTelemetryPill(
                telemetry = telemetry,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp)
                    .zIndex(19f),
            )
        }

        // Hanya tombol HUD yang ikut auto-hide/transparansi; pointer, menu, dan rail
        // (keyboard, monitor, putus, serta toggle HUD) tetap terjangkau.
        if (hudButtonsVisibleNow) {
            Box(Modifier.fillMaxSize().alpha(hudOpacity)) {
                HudKeyLayer(
                    keys = keys,
                    mappingMode = mappingMode,
                    plate = plate,
                    latchedKeys = latchedKeyIds,
                    onMove = { id, x, y ->
                        val moved = keys.map { if (it.id == id) it.copy(x = x, y = y) else it }
                        onKeysChange(moved)
                        prefs.setCustomHudKeys(deviceId, moved)
                    },
                    onPhase = { key, phase ->
                        if (phase != HudPhase.UP) haptic()
                        onPhase(key, phase)
                    },
                    scrollSpeed = prefs.scrollSpeed,
                    onScrollUnits = onScrollUnits,
                    onEdit = { editing = it },
                    onStickAxis = { stickKey, x, y ->
                        val own = stickKey.stickMode
                        if (own != null) {
                            // Joystick dengan mode sendiri (Template Controller / editor).
                            if (own == HudStickMode.POINTER) {
                                val (ax, ay) = HudStickMath.shapeAnalog(x, y, stickKey.stickDead, stickKey.stickSens)
                                if (ax != 0f || ay != 0f) onStickPointer(ax, ay)
                            } else {
                                val (ax, ay) = HudStickMath.shapeDigital(x, y, stickKey.stickDead)
                                onStickKeysMode(own.toKzMode(), ax, ay)
                            }
                        } else if (stickKey.pad && stickKey.label == "R") {
                            // Stik kanan gamepad = mouse; stik kiri ikut mode global.
                            if (x != 0f || y != 0f) onStickPointer(x, y)
                        } else {
                            onStickAxis(x, y)
                        }
                    },
                    stickMode = stickModeUi,
                )
            }
        }

        // Indikator rekaman sengaja ditaruh DI LUAR syarat !panelOpen: status
        // "sedang merekam" tidak boleh hilang hanya karena pengguna membuka
        // menu. Tidak ada clickable, jadi tidak menyerap sentuhan layar remote.
        if (recordingActive) {
            RecordingIndicator(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 10.dp)
                    .zIndex(24f),
            )
        }

        if (!panelOpen && !monitorGridOpen && !mappingMode) {
            SpotifyFloatingPlayer(
                playback = spotifyPlayback,
                expanded = spotifyExpanded,
                onExpand = { spotifyExpanded = true },
                onMinimize = { spotifyExpanded = false },
                positionMs = spotifyPosition,
                onTogglePlayback = { SpotifyMediaBridge.playPause() },
                onPrevious = { SpotifyMediaBridge.previous() },
                onNext = { SpotifyMediaBridge.next() },
                onSeek = { SpotifyMediaBridge.seekTo(it) },
                onSeekBy = { SpotifyMediaBridge.seekBy(it) },
                onStop = { SpotifyMediaBridge.stop() },
                onToggleShuffle = { SpotifyMediaBridge.setShuffle(it) },
                onCycleRepeat = { SpotifyMediaBridge.cycleRepeat() },
                onRequestAccess = { openSpotifyNotificationAccess(context) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .zIndex(22f),
            )
        }

        if (scrollPillOpen && !panelOpen && !monitorGridOpen && !mappingMode) {
            SessionScrollPill(
                plate = plate,
                scrollSpeed = prefs.scrollSpeed,
                onScrollUnits = onScrollUnits,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 68.dp)
                    .zIndex(23f),
            )
        }

        // Rail diminimalkan atas permintaan pemilik (2026-10-10): hanya dua
        // tombol tetap — pembuka menu bubble (panah atas) dan keyboard di
        // paling bawah. Sisanya pindah ke dalam bubble popup.
        if (!panelOpen && !monitorGridOpen && !mappingMode) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 24.dp)
                    .zIndex(24f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (railMenuOpen) {
                    Column(
                        Modifier
                            .kzGlass(shape = RoundedCornerShape(18.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        RailMenuItem(
                            icon = KzIcons.ScrollSlide,
                            label = kz("Kontrol scroll", "Scroll control"),
                            active = scrollPillOpen,
                        ) {
                            scrollPillOpen = !scrollPillOpen
                        }
                        RailMenuItem(
                            icon = if (hudButtonsVisibleNow) KzIcons.EyeOff else KzIcons.Eye,
                            label = if (hudButtonsVisibleNow) {
                                kz("Sembunyikan overlay", "Hide overlay")
                            } else {
                                kz("Tampilkan overlay", "Show overlay")
                            },
                        ) {
                            toggleHudButtonVisibility()
                        }
                        RailMenuItem(
                            icon = KzIcons.Monitor,
                            label = kz("Monitor & sesi", "Monitors & sessions"),
                        ) {
                            railMenuOpen = false
                            monitorGridOpen = true
                        }
                        RailMenuItem(
                            icon = KzIcons.Home,
                            label = kz("Ke beranda (sesi tetap jalan)", "Go home (session stays alive)"),
                        ) {
                            railMenuOpen = false
                            onGoHome()
                        }
                        RailMenuItem(
                            icon = KzIcons.Power,
                            label = kz("Putuskan sesi", "Disconnect"),
                            danger = true,
                        ) {
                            railMenuOpen = false
                            onDisconnect()
                        }
                    }
                }
                RailButton(
                    icon = if (railMenuOpen) KzIcons.ChevronDown else KzIcons.ChevronUp,
                    active = railMenuOpen,
                    description = kz("Menu sesi", "Session menu"),
                    plate = plate,
                    onClick = { railMenuOpen = !railMenuOpen },
                )
                RailButton(
                    icon = KzIcons.Keyboard,
                    active = false,
                    description = kz("Buka keyboard HP", "Open phone keyboard"),
                    plate = plate,
                ) { onOpenKeyboard() }
            }
        }

        if (mappingMode) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).zIndex(26f)) {
                HudMappingBanner(
                    keyCount = keys.size,
                    onAdd = { pickerOpen = true },
                    onReset = { confirmResetHud = true },
                    onDone = { onMappingModeChange(false) },
                )
            }
        }

        if (textOpen) {
            KzOverlay(title = kz("Kirim teks", "Send text"), onDismiss = { textOpen = false }) {
                Text(
                    kz(
                        "Dikirim sebagai ketikan. Pakai 'Kirim sebagai clipboard' untuk Paste di Windows.",
                        "Sent as keystrokes. Use 'Send as clipboard' for Windows Paste.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                KzField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = kz("Teks", "Text"),
                    hint = kz("mis. password, alamat URL", "e.g. password, a URL"),
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KzPillButton(
                        kz("Tempel", "Paste"),
                        {
                            val paste = clipboardText()
                            textValue = paste
                            if (paste.isEmpty()) notice.show(kzNow("Clipboard HP kosong", "Phone clipboard is empty"))
                        },
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    KzPillButton(
                        kz("Ketik ke remote", "Type to remote"),
                        {
                            val t = textValue
                            if (t.isEmpty()) {
                                validationAlert = kzNow("Teks kosong", "Empty text") to
                                    kzNow("Masukkan teks terlebih dahulu sebelum mengirim ke remote.", "Enter some text before sending to the remote.")
                            } else {
                                onSendText(t)
                                notice.show(kzNow("Teks diketik ke jendela remote", "Text typed into the remote window"))
                                textOpen = false
                            }
                        },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                KzPillButton(
                    kz("Kirim sebagai clipboard Windows", "Send as Windows clipboard"),
                    {
                        val text = textValue
                        when {
                            text.isEmpty() -> validationAlert = kzNow("Teks kosong", "Empty text") to
                                kzNow("Masukkan teks terlebih dahulu sebelum mengirim ke clipboard Windows.", "Enter some text before sending to Windows clipboard.")
                            !clipboardSyncEnabled -> validationAlert = kzNow("Kanal clipboard nonaktif", "Clipboard channel off") to
                                kzNow("Aktifkan kanal clipboard di pengaturan perangkat lalu sambungkan ulang.", "Enable the clipboard channel in device settings and reconnect.")
                            onSendRemoteClipboardText(text) -> {
                                notice.show(kzNow("Permintaan clipboard dikirim; tunggu sebentar sebelum Paste", "Clipboard request sent; wait briefly before Paste"))
                                textOpen = false
                            }
                            else -> notice.show(kzNow("Gagal mengirim permintaan clipboard", "Failed to queue clipboard request"))
                        }
                    },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = clipboardSyncEnabled && textValue.isNotEmpty(),
                )
                KzPillButton(
                    kz("Selesai", "Done"),
                    { textOpen = false },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (layoutJsonOpen) {
            KzOverlay(
                title = kz("Tata letak tombol (JSON)", "Button layout (JSON)"),
                onDismiss = { layoutJsonOpen = false },
            ) {
                Text(
                    kz(
                        "Salin JSON untuk memindahkan tata letak tombol ke perangkat lain, atau tempel JSON lalu tekan Terapkan.",
                        "Copy JSON to reuse this button layout on another device, or paste JSON and press Apply.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                KzField(
                    value = layoutJsonValue,
                    onValueChange = { layoutJsonValue = it },
                    label = kz("JSON layout", "Layout JSON"),
                    hint = "{\"version\":1,\"keys\":[...]}",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KzPillButton(
                        kz("Tempel dari clipboard", "Paste clipboard"),
                        {
                            val clip = clipboardText()
                            if (clip.isEmpty()) {
                                notice.show(kzNow("Clipboard HP kosong", "Phone clipboard is empty"))
                            } else {
                                layoutJsonValue = clip
                            }
                        },
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    KzPillButton(
                        kz("Terapkan JSON", "Apply JSON"),
                        {
                            val imported = HudKey.importLayoutJson(layoutJsonValue, prefs.hudButtonSize)
                            if (imported == null) {
                                validationAlert = kzNow("JSON tidak valid", "Invalid JSON") to
                                    kzNow(
                                        "Format JSON tata letak tombol tidak valid atau tidak memuat tombol kontrol.",
                                        "The button layout JSON format is invalid or contains no control buttons.",
                                    )
                            } else {
                                confirmImportLayout = imported
                            }
                        },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                KzPillButton(
                    kz("Salin layout saat ini", "Copy current layout"),
                    {
                        exportHudLayoutToClipboard()
                        layoutJsonValue = HudKey.exportLayoutJson(keys)
                    },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                KzPillButton(
                    kz("Tutup", "Close"),
                    { layoutJsonOpen = false },
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (templateOpen) {
            TemplateControllerDialog(
                currentKeys = keys,
                buttonSize = prefs.hudButtonSize,
                onApply = { onKeysChange(it) },
                onNotice = { notice.show(it) },
                onClose = { templateOpen = false },
            )
        }

        // ---- handle panel di kanan atas, terpisah dari rail kontrol ----
        if (!panelOpen && !monitorGridOpen && !mappingMode) {
            PanelHandle(
                onClick = {
                    scrollPillOpen = false
                    spotifyExpanded = false
                    panelOpen = true
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 6.dp),
            )
        }

        if (monitorGridOpen) {
            MonitorAndUserGridModal(
                deviceId = deviceId,
                hostLabel = hostLabel,
                remoteSize = remoteSize,
                codecLabel = telemetry.codecLabel,
                resolution = resolution,
                pcConnectMode = pcConnectMode,
                activeUsername = activeUsername,
                consoleAdminMode = consoleAdminMode,
                onSelectMonitorResolution = { selectedRes ->
                    monitorGridOpen = false
                    resolution = selectedRes
                    DisplayPrefs.setResolution(context, deviceId, selectedRes)
                    onResolutionChange(selectedRes)
                },
                onSwitchConsoleMode = { useConsole ->
                    monitorGridOpen = false
                    onSwitchConsoleMode(useConsole)
                },
                onSwitchUserSession = { user, dom, pass ->
                    monitorGridOpen = false
                    onSwitchUserSession(user, dom, pass)
                },
                onActivatePrivacyCurtain = {
                    monitorGridOpen = false
                    onActivatePrivacyCurtain()
                },
                onLockRemotePc = {
                    monitorGridOpen = false
                    onLockRemotePc()
                },
                onOpenHome = {
                    monitorGridOpen = false
                    onOpenHome()
                },
                notice = notice,
                onDismiss = { monitorGridOpen = false },
            )
        }

        if (panelOpen) {
            SessionPanel(
                drawerMode = drawerMode,
                onDrawerModeChange = { v ->
                    drawerMode = v
                    prefs.menuDrawer = v
                },
                deviceId = deviceId,
                hostLabel = hostLabel,
                statusText = statusText,
                remoteSize = remoteSize,
                zoomPercent = zoomPercent,
                remoteDpi = remoteDpi,
                tab = tab,
                onTab = { tab = it },
                notice = notice,
                // Layar
                resolution = resolution,
                rotation = rotation,
                custom = custom,
                autoFit = autoFit,
                onAutoFitChange = { autoFit = it; prefs.autoFit = it },
                onCustomChange = { custom = it },
                onCustomApply = {
                    val parsed = DisplayPrefs.parseCustom(custom)
                    if (parsed == null) {
                        validationAlert = kzNow("Resolusi kustom tidak valid", "Invalid custom resolution") to
                            kzNow(
                                "Masukkan format WxH yang valid (640..8192 x 480..8192, lebar harus genap), contoh: 1920x1080.",
                                "Enter a valid WxH format (640..8192 x 480..8192, even width), e.g. 1920x1080.",
                            )
                    } else {
                        confirmResolution = parsed
                    }
                },
                onResolution = { selected ->
                    if (selected != resolution) {
                        confirmResolution = selected
                    }
                },
                onRotation = {
                    rotation = it
                    DisplayPrefs.setRotation(context, deviceId, it)
                    onRotationChange(it)
                },
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onFit = onFit,
                onZoomActual = onZoomActual,
                onZoomScale = onZoomScale,
                onRemoteDpiChange = onRemoteDpiChange,
                onDisplayRefreshPreferenceChange = onDisplayRefreshPreferenceChange,
                showTelemetryPill = showTelemetryPill,
                onShowTelemetryPillChange = { showTelemetryPill = it; prefs.showTelemetryPill = it },
                onOpenMonitorGrid = {
                    panelOpen = false
                    monitorGridOpen = true
                },
                pcConnectMode = pcConnectMode,
                // Input
                inputMode = inputMode,
                onInputModeChange = onInputModeChange,
                swapMouseButtons = swapMouseButtons,
                onSwapMouseButtonsChange = onSwapMouseButtonsChange,
                onSendTextClick = { textValue = ""; textOpen = true },
                lastClipboard = lastClipboard,
                clipboardSyncEnabled = clipboardSyncEnabled,
                pointerVisible = pointerVisible,
                onPointerVisibilityChange = onPointerVisibilityChange,
                pointerStyle = pointerStyle,
                onPointerStyle = { pointerStyle = it; prefs.pointerStyle = it },
                pointerSize = pointerSize,
                onPointerSize = { pointerSize = it; prefs.pointerSize = it },
                pointerSensitivity = pointerSensitivity,
                onPointerSensitivity = { pointerSensitivity = it; prefs.pointerSensitivity = it },
                pointerAcceleration = pointerAcceleration,
                onPointerAcceleration = { pointerAcceleration = it; prefs.pointerAcceleration = it },
                inertialScroll = inertialScroll,
                onInertialScroll = { inertialScroll = it; prefs.inertialScroll = it },
                edgeScrollZone = edgeScrollZone,
                onEdgeScrollZone = { edgeScrollZone = it; prefs.edgeScrollZone = it },
                gamepadEnabled = gamepadEnabled,
                onGamepadEnabled = { gamepadEnabled = it; prefs.gamepadEnabled = it },
                stickMode = stickModeUi,
                onStickModeChange = { next ->
                    stickModeUi = next
                    prefs.virtualPadStickMode = next
                    // Activity ikut diberi tahu karena field padStickMode dibaca
                    // mapper dari luar komposisi.
                    onStickModeChange(next)
                },
                gyroMouseEnabled = gyroMouseEnabled,
                onGyroMouseEnabled = {
                    gyroMouseEnabled = it
                    prefs.gyroMouseEnabled = it
                    onGyroMouseChanged(it)
                },
                // Tombol
                keys = keys,
                hudProfile = hudProfile,
                onSelectHudProfile = { selectedPreset ->
                    if (selectedPreset == HudProfilePreset.CUSTOM) {
                        hudProfile = HudProfilePreset.CUSTOM
                        prefs.setHudProfile(deviceId, HudProfilePreset.CUSTOM)
                        val savedCustom = prefs.customHudKeys(deviceId)
                        if (savedCustom != null && savedCustom.isNotEmpty()) {
                            onKeysChange(savedCustom)
                        } else {
                            prefs.setCustomHudKeys(deviceId, keys)
                        }
                        panelOpen = false
                        onMappingModeChange(true)
                        notice.show(
                            kzNow(
                                "Mode Kustom aktif — geser, tambah, atau ubah tombol sesuai keinginanmu",
                                "Custom mode active — drag, add, or edit buttons to build your layout",
                            ),
                        )
                    } else if (selectedPreset != hudProfile) {
                        confirmApplyPreset = selectedPreset
                    }
                },
                onAddKey = { pickerOpen = true },
                onEditKey = { editing = it },
                onDeleteKey = { confirmDeleteKey = it },
                mappingMode = mappingMode,
                onMappingModeChange = { enabled ->
                    onMappingModeChange(enabled)
                    if (enabled) panelOpen = false
                },
                plate = plate,
                onPlate = { plate = it; prefs.hudPlate = it },
                haptics = haptics,
                onHaptics = { haptics = it; prefs.haptics = it },
                onResetCluster = { confirmResetHud = true },
                onExportLayoutJson = { exportHudLayoutToClipboard() },
                onOpenLayoutJson = { templateOpen = true },
                showHudButtons = showHudButtons,
                onShowHudButtonsChange = { visible ->
                    showHudButtons = visible
                    prefs.setHudButtonsVisible(deviceId, visible)
                    if (!visible) forceShowHudForExternalInput = false
                },
                autoHideHudOnExternalInput = autoHideHudOnExternalInput,
                onAutoHideHudOnExternalInputChange = { enabled ->
                    autoHideHudOnExternalInput = enabled
                    prefs.autoHideHudOnExternalInput = enabled
                    if (!enabled) forceShowHudForExternalInput = false
                },
                hudOpacity = hudOpacity,
                onHudOpacityChange = { opacity ->
                    hudOpacity = opacity.coerceIn(0f, 1f)
                    prefs.hudOpacity = hudOpacity
                },
                // Sesi
                recordingLabel = recordingLabel,
                recordingStatus = recordingStatus,
                recordingActionEnabled = recordingActionEnabled,
                recordingActive = recordingActive,
                onToggleRecording = onToggleRecording,
                onScreenshot = onScreenshot,
                onLockRemotePc = { confirmLockPc = true },
                onActivatePrivacyCurtain = {
                    panelOpen = false
                    onActivatePrivacyCurtain()
                },
                onDisconnect = {
                    panelOpen = false
                    onDisconnect()
                },
                coreInfo = coreInfo,
                onCopyCoreInfo = { copyCoreInfo() },
                onClose = { panelOpen = false },
            )
        }
    }

    if (pickerOpen) {
        HudKeyPicker(
            onPick = { add(it); pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
    editing?.let { key ->
        HudKeyEditor(
            key = key,
            onChange = { updated ->
                val persisted = if (updated.size != key.size) {
                    resizeHudKeyPreservingCenter(
                        key, updated.size,
                        configuration.screenWidthDp.toFloat(),
                        configuration.screenHeightDp.toFloat(),
                    )
                } else updated
                replace(persisted)
            },
            onDelete = {
                confirmDeleteKey = key
            },
            onDismiss = { editing = null },
        )
    }

    confirmDeleteKey?.let { key ->
        KzDialog(
            title = kz("Hapus tombol kontrol?", "Delete control button?"),
            body = kz(
                "Hapus tombol \"{0}\" dari tata letak sesi ini?",
                "Delete button \"{0}\" from this session layout?",
                key.label,
            ),
            confirmLabel = kz("Hapus", "Delete"),
            onConfirm = {
                confirmDeleteKey = null
                if (editing?.id == key.id) editing = null
                onDeleteKey(key)
                notice.show(kzNow("Tombol dihapus", "Button deleted"))
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmDeleteKey = null },
        )
    }

    if (confirmResetHud) {
        KzDialog(
            title = kz("Reset tata letak tombol?", "Reset button layout?"),
            body = kz(
                "Kembalikan semua tombol kontrol ke setelan bawaan? Susunan tombol kustom saat ini akan diganti.",
                "Restore all control buttons to the default set? Your current custom button layout will be replaced.",
            ),
            confirmLabel = kz("Reset", "Reset"),
            onConfirm = {
                confirmResetHud = false
                editing = null
                onResetCluster()
                notice.show(kzNow("Tata letak tombol dikembalikan ke bawaan", "Button layout restored to default"))
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmResetHud = false },
        )
    }

    confirmImportLayout?.let { imported ->
        KzDialog(
            title = kz("Terapkan tata letak JSON?", "Apply JSON layout?"),
            body = kz(
                "Ganti tata letak tombol saat ini dengan {0} tombol dari JSON?",
                "Replace the current button layout with {0} buttons from JSON?",
                imported.size,
            ),
            confirmLabel = kz("Terapkan", "Apply"),
            onConfirm = {
                confirmImportLayout = null
                layoutJsonOpen = false
                panelOpen = false
                onKeysChange(imported)
                notice.show(
                    kzNow(
                        "Layout diimpor ({0} tombol)",
                        "Layout imported ({0} buttons)",
                        imported.size,
                    ),
                )
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmImportLayout = null },
        )
    }

    confirmApplyPreset?.let { preset ->
        KzDialog(
            title = kz("Ganti profil tombol HUD?", "Switch HUD button profile?"),
            body = kz(
                "Terapkan profil \"{0}\"? Susunan tombol kontrol di layar sesi akan disesuaikan dengan profil ini.",
                "Apply profile \"{0}\"? The on-screen control buttons will be updated to match this preset.",
                kz(preset.title, preset.titleEn),
            ),
            confirmLabel = kz("Terapkan", "Apply"),
            onConfirm = {
                confirmApplyPreset = null
                hudProfile = preset
                prefs.setHudProfile(deviceId, preset)
                onKeysChange(HudKey.presetLayout(preset, prefs.hudButtonSize))
                notice.show(
                    kzNow(
                        "Profil tombol diubah ke {0}",
                        "Button profile switched to {0}",
                        kzNow(preset.title, preset.titleEn),
                    ),
                )
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmApplyPreset = null },
        )
    }

    if (confirmLockPc) {
        KzDialog(
            title = kz("Kunci layar PC (Win+L)?", "Lock PC screen (Win+L)?"),
            body = kz(
                "Kirim perintah Win+L untuk mengunci sesi Windows di komputer remote sekarang?",
                "Send Win+L to lock the Windows session on the remote computer now?",
            ),
            confirmLabel = kz("Kunci PC", "Lock PC"),
            onConfirm = {
                confirmLockPc = false
                panelOpen = false
                onLockRemotePc()
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmLockPc = false },
        )
    }

    confirmResolution?.let { targetRes ->
        KzDialog(
            title = kz("Ubah resolusi remote?", "Change remote resolution?"),
            body = kz(
                "Terapkan resolusi \"{0}\"? Jika server belum menerapkan perubahan langsung, sesi akan menyambung ulang otomatis.",
                "Apply resolution \"{0}\"? If the server does not apply live resize, the session will reconnect automatically.",
                targetRes,
            ),
            confirmLabel = kz("Terapkan", "Apply"),
            onConfirm = {
                confirmResolution = null
                resolution = targetRes
                DisplayPrefs.setResolution(context, deviceId, targetRes)
                onResolutionChange(targetRes)
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmResolution = null },
        )
    }

    validationAlert?.let { (alertTitle, alertBody) ->
        KzDialog(
            title = alertTitle,
            body = alertBody,
            confirmLabel = kz("Mengerti", "Got it"),
            onConfirm = { validationAlert = null },
            onDismiss = { validationAlert = null },
        )
    }
}

@Composable
private fun LiveTelemetryPill(
    telemetry: com.kazamatech.kzdesk.core.TelemetrySample,
    modifier: Modifier = Modifier,
) {
    val rtt = telemetry.rttMs
    val rttColor = when {
        rtt <= 0 -> Color(0xFFF2F2F4)
        rtt < 45 -> Color(0xFFE4E4E8)
        rtt < 120 -> Color(0xFFA9A9B0)
        else -> Color(0xFF7C7C84)
    }
    val textShadow = Shadow(
        color = Color(0xF2000000),
        offset = Offset(1.2f, 1.2f),
        blurRadius = 3f,
    )
    val labelStyle = TextStyle(
        color = Color(0xB8D9D9DE),
        fontSize = 7.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.4.sp,
        lineHeight = 8.sp,
        shadow = textShadow,
    )
    val valueStyle = TextStyle(
        color = Color(0xFFF2F2F4),
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        lineHeight = 10.sp,
        shadow = textShadow,
    )
    val rttLabel = if (rtt > 0) "${rtt} ms" else "-- ms"
    val resLabel = if (telemetry.width > 0) "${telemetry.width}x${telemetry.height}" else "--"
    val items = listOf(
        Triple("FPS", "${telemetry.fps}", Color(0xFFF2F2F4)),
        Triple("LATENCY", rttLabel, rttColor),
        Triple("RESOLUSI", resLabel, Color(0xFFF2F2F4)),
        Triple("ENCODE", telemetry.codecLabel, Color(0xFFF2F2F4)),
        Triple("RELAY", telemetry.relayLabel, Color(0xFFF2F2F4)),
        Triple("NETWORK", telemetry.networkLabel, Color(0xFFF2F2F4)),
    )

    Column(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEach { (label, value, color) ->
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(text = label, style = labelStyle)
                Text(text = value, style = valueStyle.copy(color = color))
            }
        }
    }
}

/** Tombol rail sesi: latar mengikuti rasa tombol HUD, ikon selalu terbaca. */
@Composable
private fun RailButton(
    icon: ImageVector,
    active: Boolean,
    description: String,
    plate: HudPlate,
    onClick: () -> Unit,
) {
    val pal = hudPalette(plate)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xE9F2F2F4) else pal.plate)
            .border(
                if (active) 2.dp else 1.2.dp,
                if (active) Color(0xFFFFFFFF) else pal.border,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) Color(0xFF0B0B0E) else pal.ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RailMenuItem(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else ink,
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                danger -> MaterialTheme.colorScheme.error
                active -> MaterialTheme.colorScheme.primary
                else -> ink
            },
        )
    }
}

@Composable
private fun PanelHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 24.dp, horizontal = 5.dp)
            .zIndex(20f),
    ) {
        Box(
            Modifier
                .clip(KzPill)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, KzPill)
                .padding(horizontal = 10.dp, vertical = 16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    KzIcons.Sliders,
                    contentDescription = kz("Buka panel sesi", "Open session panel"),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    kz("Menu", "Menu"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}

/**
 * Pointer tunggal.
 *
 * Urutan prioritas:
 *  1. Bentuk kursor yang DIKIRIM SERVER (panah, tangan, I-beam, resize, ...)
 *     — digambar apa adanya, jadi bentuknya berubah selayaknya pointer desktop.
 *  2. Kalau server tidak mengirim apa-apa: panah/titik bawaan app.
 */
@Composable
private fun KzPointer(
    position: Offset,
    sizeDp: Float,
    style: PointerStyle,
    remote: RemoteCursor?,
    zoom: Float,
) {
    val remoteBitmap = remote?.bitmap
    if (remote != null && remote.visible && remoteBitmap != null) {
        val image = remember(remoteBitmap) { remoteBitmap.asImageBitmap() }
        val scale = zoom.coerceIn(0.35f, 3f)
        Canvas(Modifier.fillMaxSize().zIndex(11f)) {
            val w = (remoteBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val h = (remoteBitmap.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(remoteBitmap.width, remoteBitmap.height),
                dstOffset = IntOffset(
                    (position.x - remote.hotX * scale).roundToInt(),
                    (position.y - remote.hotY * scale).roundToInt(),
                ),
                dstSize = IntSize(w, h),
            )
        }
        return
    }

    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.toPx() }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (position.x - sizePx / 2f).roundToInt(),
                    (position.y - sizePx / 2f).roundToInt(),
                )
            }
            .size(sizeDp.dp)
            .zIndex(11f),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            when (style) {
                PointerStyle.DOT -> {
                    val radius = w / 2f - 1.5f
                    drawCircle(Color.White, radius = radius, center = Offset(w / 2f, h / 2f))
                    drawCircle(
                        Color.Black.copy(alpha = 0.55f),
                        radius = radius,
                        center = Offset(w / 2f, h / 2f),
                        style = Stroke(width = 3f),
                    )
                }

                PointerStyle.ARROW -> {
                    val path = Path().apply {
                        val sx = w / 24f
                        val sy = h / 24f
                        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
                        val a = p(6f, 3f)
                        val b = p(6f, 18.5f)
                        val c = p(10.2f, 14.6f)
                        val d = p(13.1f, 20.8f)
                        val e = p(15.6f, 19.6f)
                        val f = p(12.8f, 13.6f)
                        val g = p(18.4f, 13.1f)
                        moveTo(a.x, a.y)
                        lineTo(b.x, b.y)
                        lineTo(c.x, c.y)
                        lineTo(d.x, d.y)
                        lineTo(e.x, e.y)
                        lineTo(f.x, f.y)
                        lineTo(g.x, g.y)
                        close()
                    }
                    drawPath(path, Color.White)
                    drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 2.4f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ panel

@Composable
private fun SessionPanel(
    drawerMode: Boolean = false,
    onDrawerModeChange: (Boolean) -> Unit = {},
    deviceId: String,
    hostLabel: String,
    statusText: String,
    remoteSize: String,
    zoomPercent: Int,
    remoteDpi: Int,
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    notice: KzNoticeState,
    onClose: () -> Unit,
    // Layar
    resolution: String,
    rotation: String,
    custom: String,
    autoFit: Boolean,
    onAutoFitChange: (Boolean) -> Unit,
    onCustomChange: (String) -> Unit,
    onCustomApply: () -> Unit,
    onResolution: (String) -> Unit,
    onRotation: (String) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomActual: () -> Unit,
    onZoomScale: (Float) -> Unit,
    onRemoteDpiChange: (Int) -> Unit,
    onDisplayRefreshPreferenceChange: (Int) -> Unit,
    showTelemetryPill: Boolean,
    onShowTelemetryPillChange: (Boolean) -> Unit,
    onOpenMonitorGrid: () -> Unit,
    pcConnectMode: Boolean = false,
    // Input
    inputMode: InputMode,
    onInputModeChange: (InputMode) -> Unit,
    swapMouseButtons: Boolean,
    onSwapMouseButtonsChange: (Boolean) -> Unit,
    onSendTextClick: () -> Unit,
    lastClipboard: String?,
    clipboardSyncEnabled: Boolean,
    pointerVisible: Boolean,
    onPointerVisibilityChange: (Boolean) -> Unit,
    pointerStyle: PointerStyle,
    onPointerStyle: (PointerStyle) -> Unit,
    pointerSize: Float,
    onPointerSize: (Float) -> Unit,
    pointerSensitivity: Float,
    onPointerSensitivity: (Float) -> Unit,
    pointerAcceleration: Boolean,
    onPointerAcceleration: (Boolean) -> Unit,
    inertialScroll: Boolean,
    onInertialScroll: (Boolean) -> Unit,
    edgeScrollZone: Boolean,
    onEdgeScrollZone: (Boolean) -> Unit,
    gamepadEnabled: Boolean,
    stickMode: KzStickMode,
    onStickModeChange: (KzStickMode) -> Unit,
    onGamepadEnabled: (Boolean) -> Unit,
    gyroMouseEnabled: Boolean,
    onGyroMouseEnabled: (Boolean) -> Unit,
    // Tombol
    keys: List<HudKey>,
    hudProfile: HudProfilePreset,
    onSelectHudProfile: (HudProfilePreset) -> Unit,
    onAddKey: () -> Unit,
    onEditKey: (HudKey) -> Unit,
    onDeleteKey: (HudKey) -> Unit,
    mappingMode: Boolean,
    onMappingModeChange: (Boolean) -> Unit,
    plate: HudPlate,
    onPlate: (HudPlate) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    onResetCluster: () -> Unit,
    onExportLayoutJson: () -> Unit,
    onOpenLayoutJson: () -> Unit,
    // Tombol overlay HUD
    showHudButtons: Boolean,
    onShowHudButtonsChange: (Boolean) -> Unit,
    autoHideHudOnExternalInput: Boolean,
    onAutoHideHudOnExternalInputChange: (Boolean) -> Unit,
    hudOpacity: Float,
    onHudOpacityChange: (Float) -> Unit,
    // Sesi
    recordingLabel: String,
    recordingStatus: String?,
    recordingActionEnabled: Boolean,
    recordingActive: Boolean,
    onToggleRecording: () -> Unit,
    onScreenshot: () -> Unit,
    onLockRemotePc: () -> Unit,
    onActivatePrivacyCurtain: () -> Unit,
    onDisconnect: () -> Unit,
    coreInfo: List<String>,
    onCopyCoreInfo: () -> Unit,
) {
    // Kunci seksi yang sedang dibuka di grid drill-in. Di-key pada `tab` supaya
    // pindah tab otomatis kembali ke daftar kotak, bukan membuka seksi lama.
    var openSection by remember(tab) { mutableStateOf<String?>(null) }
    // Panel muncul halus: membesar dari titik handle (kanan-atas) seperti
    // keluar dari tombolnya, bukan sekadar tampil seketika.
    var panelShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { panelShown = true }
    val panelScale by animateFloatAsState(
        targetValue = if (panelShown) 1f else 0.9f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (panelShown) 1f else 0f,
        animationSpec = tween(180),
    )
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(30f),
        contentAlignment = if (drawerMode) Alignment.CenterEnd else Alignment.Center,
    ) {
        // Scrim dipertahankan (lebih tipis) karena pemisahan utama sekarang
        // datang dari blur permukaan remote; di Android < 12 blur tidak jalan
        // sehingga scrim inilah yang menjaga keterbacaan.
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.34f))
                .consumeBackgroundPointer(onClose),
        )
        // Dua varian menu (permintaan pemilik 2026-10-10): popup tengah gaya
        // jendela, atau drawer menempel sisi kanan setinggi layar.
        Column(
            if (drawerMode) {
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 380.dp)
            } else {
                Modifier
                    .fillMaxHeight(0.9f)
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 760.dp)
            }
                .graphicsLayer {
                    scaleX = panelScale
                    scaleY = panelScale
                    alpha = panelAlpha
                    transformOrigin = TransformOrigin(1f, 0f)
                }
                // Gaya kaca yang sama dengan pemutar musik. Opacity dinaikkan
                // (0.55 * 1.6 = 0.88) karena panel berisi banyak teks; kaca
                // setipis pemutar akan membuat tulisan sulit dibaca.
                .kzGlass(
                    shape = if (drawerMode) {
                        RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
                    } else {
                        RoundedCornerShape(28.dp)
                    },
                    opacity = 1.6f,
                    strength = 1f,
                )
                .pointerInput(Unit) {}
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        hostLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$statusText  ·  $zoomPercent%  ·  $remoteSize",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tab == PanelTab.BUTTONS) {
                    PanelChip(kz("+ Tombol", "+ Add"), onAddKey)
                }
                PanelChip(
                    if (drawerMode) kz("Popup", "Popup") else kz("Drawer", "Drawer"),
                    { onDrawerModeChange(!drawerMode) },
                )
                PanelChip(kz("Tutup", "Close"), onClose)
            }

            PanelTabTiles(tab = tab, onTab = onTab, modifier = Modifier.fillMaxWidth())

            val tabScroll = androidx.compose.runtime.key(tab) { rememberScrollState() }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(tabScroll),
                // Jarak antar kartu dirapatkan; kartu kini punya padding sendiri.
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when (tab) {
                    PanelTab.SCREEN -> ScreenTab(
                        openKey = openSection,
                        onOpenKey = { openSection = it },
                        deviceId = deviceId,
                        remoteSize = remoteSize,
                        zoomPercent = zoomPercent,
                        remoteDpi = remoteDpi,
                        resolution = resolution,
                        rotation = rotation,
                        custom = custom,
                        autoFit = autoFit,
                        onAutoFitChange = onAutoFitChange,
                        onCustomChange = onCustomChange,
                        onCustomApply = onCustomApply,
                        onResolution = onResolution,
                        onRotation = onRotation,
                        onZoomIn = onZoomIn,
                        onZoomOut = onZoomOut,
                        onFit = onFit,
                        onZoomActual = onZoomActual,
                        onZoomScale = onZoomScale,
                        onRemoteDpiChange = onRemoteDpiChange,
                        onDisplayRefreshPreferenceChange = onDisplayRefreshPreferenceChange,
                        showTelemetryPill = showTelemetryPill,
                        onShowTelemetryPillChange = onShowTelemetryPillChange,
                        onOpenMonitorGrid = onOpenMonitorGrid,
                        pcConnectMode = pcConnectMode,
                        notice = notice,
                    )

                    PanelTab.INPUT -> InputTab(
                        openKey = openSection,
                        onOpenKey = { openSection = it },
                        inputMode = inputMode,
                        onInputModeChange = onInputModeChange,
                        swapMouseButtons = swapMouseButtons,
                        onSwapMouseButtonsChange = onSwapMouseButtonsChange,
                        onSendTextClick = onSendTextClick,
                        lastClipboard = lastClipboard,
                        clipboardSyncEnabled = clipboardSyncEnabled,
                        pointerVisible = pointerVisible,
                        onPointerVisibilityChange = onPointerVisibilityChange,
                        pointerStyle = pointerStyle,
                        onPointerStyle = onPointerStyle,
                        pointerSize = pointerSize,
                        onPointerSize = onPointerSize,
                        pointerSensitivity = pointerSensitivity,
                        onPointerSensitivity = onPointerSensitivity,
                        pointerAcceleration = pointerAcceleration,
                        onPointerAcceleration = onPointerAcceleration,
                        inertialScroll = inertialScroll,
                        onInertialScroll = onInertialScroll,
                        edgeScrollZone = edgeScrollZone,
                        onEdgeScrollZone = onEdgeScrollZone,
                        gamepadEnabled = gamepadEnabled,
                        stickMode = stickMode,
                        onStickModeChange = onStickModeChange,
                        onGamepadEnabled = onGamepadEnabled,
                        gyroMouseEnabled = gyroMouseEnabled,
                        onGyroMouseEnabled = onGyroMouseEnabled,
                    )

                    PanelTab.BUTTONS -> ButtonsTab(
                        openKey = openSection,
                        onOpenKey = { openSection = it },
                        keys = keys,
                        hudProfile = hudProfile,
                        onSelectHudProfile = onSelectHudProfile,
                        onAddKey = onAddKey,
                        onEditKey = onEditKey,
                        onDeleteKey = onDeleteKey,
                        mappingMode = mappingMode,
                        onMappingModeChange = onMappingModeChange,
                        plate = plate,
                        onPlate = onPlate,
                        haptics = haptics,
                        onHaptics = onHaptics,
                        onResetCluster = onResetCluster,
                        onExportLayoutJson = onExportLayoutJson,
                        onOpenLayoutJson = onOpenLayoutJson,
                        showHudButtons = showHudButtons,
                        onShowHudButtonsChange = onShowHudButtonsChange,
                        autoHideHudOnExternalInput = autoHideHudOnExternalInput,
                        onAutoHideHudOnExternalInputChange = onAutoHideHudOnExternalInputChange,
                        hudOpacity = hudOpacity,
                        onHudOpacityChange = onHudOpacityChange,
                    )

                    PanelTab.SESSION -> SessionTab(
                        deviceId = deviceId,
                        notice = notice,
                        openKey = openSection,
                        onOpenKey = { openSection = it },
                        onScreenshot = onScreenshot,
                        onLockRemotePc = onLockRemotePc,
                        onActivatePrivacyCurtain = onActivatePrivacyCurtain,
                        onDisconnect = onDisconnect,
                        recordingLabel = recordingLabel,
                        recordingStatus = recordingStatus,
                        recordingActionEnabled = recordingActionEnabled,
                        recordingActive = recordingActive,
                        onToggleRecording = onToggleRecording,
                        coreInfo = coreInfo,
                        onCopyCoreInfo = onCopyCoreInfo,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ tab: layar

@Composable
private fun ScreenTab(
    deviceId: String,
    remoteSize: String,
    zoomPercent: Int,
    remoteDpi: Int,
    resolution: String,
    rotation: String,
    custom: String,
    autoFit: Boolean,
    onAutoFitChange: (Boolean) -> Unit,
    onCustomChange: (String) -> Unit,
    onCustomApply: () -> Unit,
    onResolution: (String) -> Unit,
    onRotation: (String) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomActual: () -> Unit,
    onZoomScale: (Float) -> Unit,
    onRemoteDpiChange: (Int) -> Unit,
    onDisplayRefreshPreferenceChange: (Int) -> Unit,
    showTelemetryPill: Boolean,
    onShowTelemetryPillChange: (Boolean) -> Unit,
    onOpenMonitorGrid: () -> Unit,
    pcConnectMode: Boolean = false,
    notice: KzNoticeState,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    val context = LocalContext.current
    var pcOptions by remember(deviceId) { mutableStateOf(RdpOptions.of(context, deviceId)) }

    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section(
        "telemetri-kontrol-monitor-fisik-pc",
        if (pcConnectMode) kz("Telemetri & Kontrol Monitor Fisik PC", "Live Telemetry & Physical PC Monitor")
        else kz("Telemetri & Multi-Monitor / Sesi RDP", "Live Telemetry & Multi-Monitor / RDP Sessions"),
        advanced = true,
    ) {
            KzToggleRow(
                title = kz("Status telemetri live (Update UI, Latency, Network)", "Live telemetry status (UI updates, latency, network)"),
                subtitle = kz("FPS adalah laju penyegaran tampilan app (frame per detik yang digambar di HP). Tampilkan bersama latency, resolusi, codec, relay, dan jaringan.", "FPS is the app display refresh rate (frames per second drawn on the phone). Show it with latency, resolution, codec, relay, and network."),
                checked = showTelemetryPill,
                onCheckedChange = onShowTelemetryPillChange,
            )
            KzPillButton(
                text = if (pcConnectMode) {
                    kz("Monitor Fisik 1:1 & Mesin Direct Stream", "1:1 Physical Monitor & Direct Stream Engine")
                } else {
                    kz("Grid Monitor & Sesi Multi-User / Konsol", "Active Monitors & Multi-User / Console Grid")
                },
                onClick = onOpenMonitorGrid,
                icon = KzIcons.DualMonitor,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

    section("orientasi",kz("Orientasi", "Orientation")) {
            // Dulu label mentah "Auto/Portrait/Landscape" — satu-satunya baris
            // Inggris di panel Indonesia.
            val labels = listOf(
                kz("Otomatis", "Auto"),
                kz("Potret", "Portrait"),
                kz("Lanskap", "Landscape"),
            )
            KzSegmented(
                options = labels,
                selectedIndex = DisplayPrefs.rotations.indexOf(rotation).coerceAtLeast(0),
                onSelect = { onRotation(DisplayPrefs.rotations[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ tab: input

@Composable
private fun InputTab(
    inputMode: InputMode,
    onInputModeChange: (InputMode) -> Unit,
    swapMouseButtons: Boolean,
    onSwapMouseButtonsChange: (Boolean) -> Unit,
    onSendTextClick: () -> Unit,
    lastClipboard: String?,
    clipboardSyncEnabled: Boolean,
    pointerVisible: Boolean,
    onPointerVisibilityChange: (Boolean) -> Unit,
    pointerStyle: PointerStyle,
    onPointerStyle: (PointerStyle) -> Unit,
    pointerSize: Float,
    onPointerSize: (Float) -> Unit,
    pointerSensitivity: Float,
    onPointerSensitivity: (Float) -> Unit,
    pointerAcceleration: Boolean,
    onPointerAcceleration: (Boolean) -> Unit,
    inertialScroll: Boolean,
    onInertialScroll: (Boolean) -> Unit,
    edgeScrollZone: Boolean,
    onEdgeScrollZone: (Boolean) -> Unit,
    gamepadEnabled: Boolean,
    stickMode: KzStickMode,
    onStickModeChange: (KzStickMode) -> Unit,
    onGamepadEnabled: (Boolean) -> Unit,
    gyroMouseEnabled: Boolean,
    onGyroMouseEnabled: (Boolean) -> Unit,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section("mode-input-klik-mouse",kz("Mode input & Klik Mouse", "Input mode & Mouse Click")) {
            KzSegmented(
                options = InputMode.entries.map { kz(it.title, it.titleEn) },
                selectedIndex = inputMode.ordinal,
                onSelect = { onInputModeChange(InputMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            PanelHint(kz(InputMode.entries[inputMode.ordinal].detail, InputMode.entries[inputMode.ordinal].detailEn))
            // Switch Ikon Mouse Kiri <-> Kanan (tanpa bergantung pada teks)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (swapMouseButtons) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    )
                    .border(
                        1.dp,
                        if (swapMouseButtons) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSwapMouseButtonsChange(!swapMouseButtons) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (swapMouseButtons) KzIcons.ClickRight else KzIcons.ClickLeft,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Icon(
                        KzIcons.MouseSwap,
                        contentDescription = kz("Tukar Klik Kiri & Kanan", "Swap Left & Right Click"),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (swapMouseButtons) KzIcons.ClickLeft else KzIcons.ClickRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                com.kazamatech.kzdesk.ui.components.KzSwitch(
                    checked = swapMouseButtons,
                    onCheckedChange = onSwapMouseButtonsChange,
                )
            }
            KzPillButton(
                kz("Kirim teks ke remote", "Send text to remote"),
                onSendTextClick,
                icon = KzIcons.Keyboard,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

    section("fisika-trackpad-sensor",kz("Fisika Trackpad & Sensor", "Trackpad Physics & Sensors"), advanced = true) {
            PanelHint(
                kz(
                    "Sensitivitas pointer: {0}x",
                    "Pointer sensitivity: {0}x",
                    String.format(java.util.Locale.US, "%.1f", pointerSensitivity),
                ),
            )
            KzSlider(
                value = pointerSensitivity,
                onValueChange = onPointerSensitivity,
                valueRange = 0.4f..3.0f,
            )
            KzToggleRow(
                title = kz("Akselerasi pointer dinamis", "Dynamic pointer acceleration"),
                subtitle = kz("Gerak cepat menjangkau layar jauh, gerak pelan tetap presisi", "Fast swipes cross the screen, slow moves stay pixel-accurate"),
                checked = pointerAcceleration,
                onCheckedChange = onPointerAcceleration,
            )
            KzToggleRow(
                title = kz("Inertial scroll (momentum)", "Inertial scroll (momentum)"),
                subtitle = kz("Gulir dua jari tetap meluncur halus saat dilepas", "Two-finger scroll glides smoothly after release"),
                checked = inertialScroll,
                onCheckedChange = onInertialScroll,
            )
            KzToggleRow(
                title = kz("Zona scroll tepi kanan", "Right-edge scroll strip"),
                subtitle = kz("Usap 1 jari di tepi paling kanan layar untuk roda scroll", "Swipe 1 finger along the right screen edge for mouse wheel"),
                checked = edgeScrollZone,
                onCheckedChange = onEdgeScrollZone,
            )
            KzToggleRow(
                title = kz("Dukungan Gamepad & Joystick fisik", "Physical Gamepad & Joystick"),
                subtitle = kz("Stik kiri WASD, stik kanan mouse, A/B klik", "Left stick WASD, right stick mouse, A/B click"),
                checked = gamepadEnabled,
                onCheckedChange = onGamepadEnabled,
            )
            PanelHint(
                kz(
                    "Mode stik kiri: Pointer = kursor, WASD/Panah = tombol arah. Stik kanan gamepad selalu kursor.",
                    "Left stick mode: Pointer = cursor, WASD/Arrows = keys. The gamepad's right stick is always the cursor.",
                ),
            )
            KzSegmented(
                options = listOf(kz("Pointer", "Pointer"), kz("WASD", "WASD"), kz("Panah", "Arrows")),
                selectedIndex = KzStickMode.entries.indexOf(stickMode).coerceAtLeast(0),
                onSelect = { onStickModeChange(KzStickMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            KzToggleRow(
                title = kz("Gyro Air-Mouse (sensor gerak HP)", "Gyro Air-Mouse (phone motion)"),
                subtitle = kz("Arahkan kursor dengan memiringkan HP seperti pointer laser", "Aim the cursor by tilting your phone like a laser pointer"),
                checked = gyroMouseEnabled,
                onCheckedChange = onGyroMouseEnabled,
            )
        }

    section("clipboard-otomatis-teks-gambar",kz("Clipboard otomatis (Teks & Gambar)", "Automatic clipboard (Text & Image)"), advanced = true) {
            PanelHint(
                if (clipboardSyncEnabled) {
                    kz(
                        "Sinkronisasi dua arah aktif otomatis: teks maupun gambar/screenshot yang disalin di PC langsung masuk ke clipboard HP, dan sebaliknya.",
                        "Two-way sync is active automatically: text and images/screenshots copied on PC go straight to the phone clipboard, and vice versa.",
                    )
                } else {
                    kz(
                        "Kanal clipboard mati di pengaturan perangkat. Aktifkan lalu sambungkan ulang untuk sinkronisasi otomatis PC dan HP.",
                        "Clipboard channel is off in device settings. Enable it and reconnect for automatic PC and phone clipboard sync.",
                    )
                },
            )
            if (clipboardSyncEnabled && !lastClipboard.isNullOrEmpty()) {
                PanelHint(
                    kz(
                        "Status: teks terakhir dari remote sudah tersalin otomatis ke clipboard HP.",
                        "Status: latest remote text has been automatically copied to the phone clipboard.",
                    ),
                )
            }
            ClipboardHistoryBlock(clipboardSyncEnabled = clipboardSyncEnabled)
        }

    section("pointer",kz("Pointer", "Pointer")) {
            KzToggleRow(
                title = kz("Tampilkan pointer", "Show pointer"),
                checked = pointerVisible,
                onCheckedChange = onPointerVisibilityChange,
            )
            PanelHint(
                kz(
                    "Bentuk pointer mengikuti kursor yang dikirim server (panah, tangan, I-beam).",
                    "Pointer shape follows the cursor sent by the server (arrow, hand, I-beam).",
                ),
            )
            KzSegmented(
                options = PointerStyle.entries.map { kz(it.title, it.titleEn) },
                selectedIndex = pointerStyle.ordinal,
                onSelect = { onPointerStyle(PointerStyle.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            PanelHint(kz("Ukuran pointer: {0} dp", "Pointer size: {0} dp", pointerSize.toInt()))
            KzSlider(value = pointerSize, onValueChange = onPointerSize, valueRange = 10f..52f)
        }
    }
}

// ------------------------------------------------------------- tab: tombol

@Composable
private fun ButtonsTab(
    keys: List<HudKey>,
    hudProfile: HudProfilePreset,
    onSelectHudProfile: (HudProfilePreset) -> Unit,
    onAddKey: () -> Unit,
    onEditKey: (HudKey) -> Unit,
    onDeleteKey: (HudKey) -> Unit,
    mappingMode: Boolean,
    onMappingModeChange: (Boolean) -> Unit,
    plate: HudPlate,
    onPlate: (HudPlate) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    onResetCluster: () -> Unit,
    onExportLayoutJson: () -> Unit,
    onOpenLayoutJson: () -> Unit,
    showHudButtons: Boolean,
    onShowHudButtonsChange: (Boolean) -> Unit,
    autoHideHudOnExternalInput: Boolean,
    onAutoHideHudOnExternalInputChange: (Boolean) -> Unit,
    hudOpacity: Float,
    onHudOpacityChange: (Float) -> Unit,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section("tampilan-tombol-overlay",kz("Tampilan tombol overlay", "Overlay button display"), advanced = true) {
            KzToggleRow(
                title = kz("Aktifkan tombol HUD", "Show HUD buttons"),
                subtitle = kz(
                    "Bisa disembunyikan kapan saja lewat ikon mata di rail sesi.",
                    "You can hide these any time with the eye button on the session rail.",
                ),
                checked = showHudButtons,
                onCheckedChange = onShowHudButtonsChange,
            )
            KzToggleRow(
                title = kz("Sembunyikan otomatis saat input fisik tersambung", "Auto-hide when physical input is connected"),
                subtitle = kz(
                    "Mendeteksi keyboard, mouse, gamepad, dan D-pad eksternal. Menu, keyboard HP, monitor, dan disconnect tetap terlihat.",
                    "Detects external keyboards, mice, gamepads, and D-pads. Menu, phone keyboard, monitors, and disconnect stay available.",
                ),
                checked = autoHideHudOnExternalInput,
                onCheckedChange = onAutoHideHudOnExternalInputChange,
            )
            PanelHint(
                kz(
                    "Opasitas tombol HUD: {0}% (0% transparan, 100% solid). Hanya tombol overlay berubah; layar remote dan rail tidak terpengaruh.",
                    "HUD button opacity: {0}% (0% transparent, 100% opaque). Only overlay buttons change; the remote screen and rail are unaffected.",
                    (hudOpacity * 100).roundToInt(),
                ),
            )
            KzSlider(
                value = hudOpacity,
                onValueChange = onHudOpacityChange,
                valueRange = 0f..1f,
            )
        }

    section("preset-profil-hud",kz("Preset Profil HUD", "HUD Profile Presets")) {
            PanelHint(
                kz(
                    "Ganti susunan tombol instan sesuai aktivitas: Standar, Coding/Terminal, Gaming/WASD, Office, atau Desain/Video.",
                    "Switch button layouts instantly by activity: Standard, Coding/Terminal, Gaming/WASD, Office, or Design/Video.",
                ),
            )
            HudProfilePreset.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { preset ->
                        KzPillButton(
                            text = kz(preset.title, preset.titleEn),
                            onClick = { onSelectHudProfile(preset) },
                            primary = preset == hudProfile,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

    section("tombol-kontrol-0",kz("Tombol kontrol ({0})", "Control buttons ({0})", keys.size)) {
            PanelHint(
                if (mappingMode) {
                    kz(
                        "Mode atur posisi MENYALA: geser tombol ke tempat yang kamu mau, " +
                            "ketuk tombol untuk ubah aksi/ukuran, atau tekan + Tambah di atas layar.",
                        "Layout mode is ON: drag buttons where you want them, tap a " +
                            "button to change its action/size, or press + Add at the top bar.",
                    )
                } else {
                    kz(
                        "Tekan \"Atur posisi & ukuran\" untuk menggeser tombol atau menambah kontrol dari bar atas (Kombinasi, F1-F12, Single Key, Numpad, Modifier, Mouse).",
                        "Press \"Edit layout & size\" to drag buttons or add controls from the top bar (Combos, F1-F12, Single Key, Numpad, Modifiers, Mouse).",
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    kz("+ Tambah tombol", "+ Add button"),
                    onAddKey,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    if (mappingMode) kz("Selesai atur", "Done layout") else kz("Atur posisi", "Edit layout"),
                    { onMappingModeChange(!mappingMode) },
                    primary = mappingMode,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            HudLayoutPreview(keys)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    kz("Salin JSON", "Copy JSON"),
                    onExportLayoutJson,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    kz("Template Controller", "Controller Templates"),
                    onOpenLayoutJson,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            KzPillButton(
                kz("Kembalikan bawaan", "Restore default"),
                onResetCluster,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PanelHint(
                kz(
                    "Pratinjau di bawah memakai warna tombol sungguhan di atas contoh gambar desktop, jadi hasilnya sama dengan yang tampil saat sesi berjalan.",
                    "The preview below uses the real button colors over a sample desktop, so it matches what you get during a session.",
                ),
            )
            HudPlatePicker(
                selected = plate,
                onSelect = onPlate,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (keys.isEmpty()) {
                    PanelHint(
                        kz(
                            "Belum ada tombol. Tekan \"+ Tambah tombol\".",
                            "No buttons yet. Press \"+ Add button\".",
                        ),
                    )
                }
                keys.forEach { key -> KeyRow(key, onEditKey, onDeleteKey) }
            }
            KzToggleRow(
                title = kz("Getaran saat tombol ditekan", "Haptic feedback on button press"),
                checked = haptics,
                onCheckedChange = onHaptics,
            )
        }
    }
}

// -------------------------------------------------------------- tab: sesi

@Composable
private fun SessionTab(
    deviceId: String,
    notice: com.kazamatech.kzdesk.ui.components.KzNoticeState,
    onScreenshot: () -> Unit,
    onLockRemotePc: () -> Unit,
    onActivatePrivacyCurtain: () -> Unit,
    onDisconnect: () -> Unit,
    recordingLabel: String,
    recordingStatus: String?,
    recordingActionEnabled: Boolean,
    recordingActive: Boolean,
    onToggleRecording: () -> Unit,
    coreInfo: List<String>,
    onCopyCoreInfo: () -> Unit,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
) {
    PanelSectionGrid(openKey = openKey, onOpenKey = onOpenKey) {
    section("keamanan-privasi-cepat",kz("Keamanan & Privasi Cepat", "Quick Security & Privacy")) {
            PanelHint(
                kz(
                    "Kunci sesi Windows dari jarak jauh (Win+L) atau aktifkan Tirai Privasi agar layar HP tertutup gelap sementara.",
                    "Remotely lock the Windows session (Win+L) or activate the Privacy Curtain to black out the phone screen temporarily.",
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    kz("Kunci PC", "Lock PC"),
                    onLockRemotePc,
                    icon = KzIcons.Lock,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    kz("Tirai Privasi", "Privacy Curtain"),
                    onActivatePrivacyCurtain,
                    icon = KzIcons.EyeOff,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

    section("perekaman-lokal-pc-ke-hp",kz("Perekaman lokal · PC ke HP", "Local recording · PC to phone"), advanced = true) {
            PanelHint(
                kz(
                    "Desktop PC beserta audionya direkam ke satu MP4 di Movies/KzDesk. Layar HP dan kontrol tidak pernah ikut terekam. Kalau penggabungan gagal, video dan audio disimpan sebagai dua file. Perekaman diblokir saat FLAG_SECURE aktif.",
                    "The PC desktop and its audio are recorded into a single MP4 in Movies/KzDesk. The phone screen and controls are never captured. If merging fails, video and audio are saved as two files. Recording is blocked while FLAG_SECURE is on.",
                ),
            )
            KzPillButton(
                text = recordingLabel,
                onClick = onToggleRecording,
                enabled = recordingActionEnabled,
                primary = recordingActive,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            recordingStatus?.let { status ->
                Text(
                    status,
                    color = if (recordingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }

    section("transfer-file",kz("Transfer file HP ↔ PC", "File transfer phone ↔ PC")) {
            TransferFileSection(deviceId = deviceId, notice = notice)
        }

    section("sesi",kz("Sesi", "Session")) {
            PanelHint(
                kz(
                    "Tombol bulat kanan bawah (ikon power) juga memutus — langsung kembali ke beranda setelah konfirmasi.",
                    "The bottom-right round button (power icon) also disconnects — returns straight to home after confirmation.",
                ),
            )
            KzPillButton(
                kz("Ambil screenshot", "Take screenshot"),
                onScreenshot,
                icon = KzIcons.Shot,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            KzPillButton(
                kz("Putuskan sesi", "Disconnect"),
                onDisconnect,
                icon = KzIcons.Power,
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (coreInfo.isNotEmpty()) {
            PanelSection(kz("Info teknis", "Technical info")) {
                coreInfo.forEach { line ->
                    Text(
                        line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                KzPillButton(
                    kz("Salin info teknis", "Copy technical info"),
                    onCopyCoreInfo,
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PanelChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(KzPill)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, KzPill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    }
}

@Composable
internal fun PanelHint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

/**
 * Tab panel sebagai deretan kotak kaca, bukan strip tersegmentasi.
 *
 * Tinggi dan jejak kakinya sama dengan [KzSegmented] yang digantikannya, jadi
 * isi panel tidak bertambah panjang dan tidak menambah scroll — yang berubah
 * hanya bentuknya jadi kotak, sesuai permintaan pengguna.
 */
@Composable
private fun PanelTabTiles(
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PanelTab.entries.forEach { entry ->
            val active = entry == tab
            val bg by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.Transparent
                },
                animationSpec = tween(durationMillis = 150),
                label = "panelTabBg",
            )
            val fg by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(durationMillis = 150),
                label = "panelTabFg",
            )
            val label = kz(entry.id, entry.en)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .kzGlass(shape = RoundedCornerShape(13.dp), strength = 0.9f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(bg)
                    .clickable { onTab(entry) }
                    .semantics { contentDescription = label }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    panelTabIcon(entry),
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    label,
                    color = fg,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun panelTabIcon(entry: PanelTab): ImageVector = when (entry) {
    PanelTab.SCREEN -> KzIcons.Fit
    PanelTab.INPUT -> KzIcons.Keyboard
    PanelTab.BUTTONS -> KzIcons.Grid
    PanelTab.SESSION -> KzIcons.Gear
}

/**
 * Ikon kotak untuk satu seksi panel. Dicocokkan dari kunci seksi, bukan judul,
 * supaya perubahan terjemahan judul tidak mengubah ikonnya.
 */
private fun panelSectionIcon(key: String): ImageVector = when {
    key.startsWith("telemetri") -> KzIcons.Monitor
    key.startsWith("ukuran-tampilan") -> KzIcons.Fit
    key.startsWith("mesin-direct-pc-stream") -> KzIcons.Terminal
    key.startsWith("skala-tampilan-windows") -> KzIcons.Windows
    key.startsWith("resolusi-desktop") -> KzIcons.DualMonitor
    key.startsWith("orientasi") -> KzIcons.Rotate
    key.startsWith("mode-input") -> KzIcons.Mouse
    key.startsWith("fisika-trackpad") -> KzIcons.Sliders
    key.startsWith("clipboard") -> KzIcons.Copy
    key.startsWith("pointer") -> KzIcons.Cursor
    key.startsWith("tampilan-tombol") -> KzIcons.Eye
    key.startsWith("preset-profil-hud") -> KzIcons.Star
    key.startsWith("tombol-kontrol") -> KzIcons.Grid
    key.startsWith("sumber-musik") -> KzIcons.Users
    key.startsWith("kontrol-pemutar") -> KzIcons.Sliders
    key.startsWith("antrian-musik") -> KzIcons.Queue
    key.startsWith("pustaka-musik") -> KzIcons.Library
    key.startsWith("transfer-file") -> KzIcons.Swap
    key.startsWith("keamanan") -> KzIcons.Shield
    key.startsWith("perekaman") -> KzIcons.Shot
    key.startsWith("sesi") -> KzIcons.Power
    key.startsWith("info-teknis") -> KzIcons.Info
    else -> KzIcons.ChevronRight
}

/**
 * Cakupan pembangun [PanelSectionGrid].
 *
 * Seksi direkam sebagai data dulu, tidak langsung digambar. Inilah yang membuat
 * panel bisa tampil sebagai kotak-kotak: selama belum ada seksi yang dibuka, yang
 * digambar hanya ikon + judul, jadi satu tab muat tanpa digulir. Isi seksi baru
 * disusun untuk satu seksi yang sedang terbuka.
 */
internal class PanelSectionScope {
    internal class Spec(
        val key: String,
        val title: String,
        val advanced: Boolean = false,
        val content: @Composable () -> Unit,
    )

    internal val specs: MutableList<Spec> = mutableListOf()

    /**
     * Rekam satu seksi. [content] belum dijalankan di sini.
     * [advanced] = seksi jarang dipakai; disembunyikan di balik ubin
     * "Lanjutan" supaya menu utama tetap pendek dan bersih.
     */
    fun section(
        key: String,
        title: String,
        advanced: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        specs.add(Spec(key, title, advanced, content))
    }
}

/**
 * Daftar seksi panel sebagai grid kotak dengan drill-in.
 *
 * Menggantikan accordion. Satu tab berisi 3-6 seksi dan tiap seksi memuat banyak
 * blok penjelasan, jadi accordion pun tetap menuntut scroll. Di sini tab digambar
 * sebagai dua kolom kotak; mengetuk satu kotak membuka seksi itu penuh lebar
 * dengan tombol kembali. Seksi bersarang di dalam badan seksi tetap memakai
 * [PanelSection] biasa.
 *
 * [builder] dijalankan tiap komposisi karena judul seksi memakai `kz()` yang
 * composable dan beberapa seksi bersyarat (`if (pcConnectMode)`). Scope dibuat
 * baru tiap kali supaya seksi dari komposisi sebelumnya tidak tertinggal.
 */
@Composable
internal fun PanelSectionGrid(
    openKey: String?,
    onOpenKey: (String?) -> Unit,
    builder: @Composable PanelSectionScope.() -> Unit,
) {
    val scope = PanelSectionScope()
    scope.builder()
    val allSpecs = scope.specs
    if (allSpecs.isEmpty()) return
    // Menu utama hanya seksi inti; seksi lanjutan muncul setelah ubin
    // "Lanjutan" diketuk supaya tiap tab singkat dilihat sekilas.
    val advancedCount = allSpecs.count { it.advanced }
    var showAdvanced by remember { mutableStateOf(false) }
    val specs = if (showAdvanced) allSpecs else allSpecs.filter { !it.advanced }
    if (specs.isEmpty()) return
    // Grid dua kolom, dan seksi yang terbuka MELEBAR TEPAT DI BAWAH barisnya.
    //
    // Sebelumnya versi ini memakai drill-in: mengetuk kotak mengganti seluruh
    // tab dengan satu seksi plus tombol kembali. Pengguna menolaknya —
    // "lebih gampang malah makin susah" — karena menambah satu lapis ketukan
    // dan menyembunyikan seksi lain. Di sini satu ketukan langsung membuka isi
    // di tempat, kotak lain tetap terlihat, dan ketukan kedua menutupnya lagi.
    Column(
        Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        specs.chunked(2).forEach { rowSpecs ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowSpecs.forEach { spec ->
                    val selected = spec.key == openKey
                    Box(Modifier.weight(1f)) {
                        KzGlassTile(
                            icon = panelSectionIcon(spec.key),
                            title = spec.title,
                            onClick = { onOpenKey(if (selected) null else spec.key) },
                            selected = selected,
                            titleMaxLines = 2,
                            trailing = {
                                Icon(
                                    imageVector = if (selected) KzIcons.ChevronUp else KzIcons.ChevronDown,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
                // Pengisi baris ganjil: tanpa ini ubin sendirian melebar
                // sepenuh baris dan grid terlihat berantakan.
                repeat(2 - rowSpecs.size) { Spacer(Modifier.weight(1f)) }
            }
            val open = rowSpecs.firstOrNull { it.key == openKey }
            if (open != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .kzGlass(shape = RoundedCornerShape(14.dp), opacity = 1.25f, strength = 0.9f)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        open.title.uppercase(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    open.content()
                }
            }
        }
        if (advancedCount > 0) {
            KzGlassTile(
                icon = KzIcons.Sliders,
                title = if (showAdvanced) {
                    kz("Sembunyikan seksi lanjutan", "Hide advanced sections")
                } else {
                    kz(
                        "Lanjutan ({0} seksi lagi)",
                        "Advanced ({0} more sections)",
                        advancedCount,
                    )
                },
                onClick = {
                    showAdvanced = !showAdvanced
                    // Seksi lanjutan yang sedang terbuka ikut tertutup supaya
                    // isi yang disembunyikan tidak menggantung tanpa ubinnya.
                    if (!showAdvanced) onOpenKey(null)
                },
                selected = false,
                titleMaxLines = 1,
                trailing = {
                    Icon(
                        imageVector = if (showAdvanced) KzIcons.ChevronUp else KzIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
internal fun PanelSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Setiap seksi digambar sebagai kartu yang bisa dilipat. Satu fungsi ini
    // dipakai 17 seksi di empat tab, jadi seluruh panel ikut berubah tanpa
    // menyentuh masing-masing tab.
    //
    // Alasan accordion: satu tab berisi 3-6 seksi dengan total 24 blok teks
    // penjelasan, sehingga tidak mungkin semua muat sekaligus di layar HP.
    // Dengan seksi terlipat jadi judul saja, satu tab muat tanpa scroll;
    // seksi pertama tiap tab tetap terbuka supaya isinya langsung kelihatan.
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier
            .fillMaxWidth()
            // Kartu kaca: satu perubahan di sini mengubah 18 seksi di empat tab,
            // karena semua seksi digambar lewat fungsi ini.
            .kzGlass(shape = RoundedCornerShape(14.dp), opacity = 1.25f, strength = 0.9f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title.uppercase(),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (expanded) KzIcons.ChevronUp else KzIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
        }
    }
}

@Composable
private fun KeyRow(
    key: HudKey,
    onEdit: (HudKey) -> Unit,
    onDelete: (HudKey) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
            .clickable { onEdit(key) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HudKeyGlyph(key = key, tint = MaterialTheme.colorScheme.onSurface, boxDp = 30f)
        }
        Column(Modifier.weight(1f)) {
            Text(
                key.label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kz(
                    "{0} · {1} · {2} dp · {3}%,{4}%",
                    "{0} · {1} · {2} dp · {3}%,{4}%",
                    kz(key.kind.title, key.kind.titleEn),
                    kz(key.action.title, key.action.titleEn),
                    key.size.toInt(),
                    (key.x * 100).toInt(),
                    (key.y * 100).toInt(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable { onDelete(key) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                KzIcons.Trash,
                contentDescription = kz("Hapus", "Delete"),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private data class MonitorGridItem(
    val badge: String,
    val title: String,
    val subtitle: String,
    val value: String,
    val icon: ImageVector,
    val active: Boolean,
)

/**
 * Modal Grid Monitor & Sesi User Aktif (dibuka dari tombol Monitor di atas Disconnect).
 * Mendeteksi otomatis monitor aktif (DISP / resolusi sesi) serta sesi konsol (/admin)
 * maupun akun user tersimpan untuk berpindah secara langsung saat sesi berlangsung.
 */
@Composable
private fun MonitorAndUserGridModal(
    deviceId: String,
    hostLabel: String,
    remoteSize: String,
    codecLabel: String,
    resolution: String,
    pcConnectMode: Boolean,
    activeUsername: String?,
    consoleAdminMode: Boolean,
    onSelectMonitorResolution: (String) -> Unit,
    onSwitchConsoleMode: (Boolean) -> Unit,
    onSwitchUserSession: (String, String?, String?) -> Unit,
    onActivatePrivacyCurtain: () -> Unit,
    onLockRemotePc: () -> Unit,
    onOpenHome: () -> Unit,
    notice: KzNoticeState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pcOptions by remember(deviceId) { mutableStateOf(RdpOptions.of(context, deviceId)) }
    val repo = remember { com.kazamatech.kzdesk.sessions.SessionsRepository(context) }
    val allFavorites by repo.favorites().collectAsState(initial = emptyList())
    val savedAccounts = remember(allFavorites) {
        allFavorites
            .filter { !it.username.isNullOrBlank() && !it.username.equals("KzDesk", ignoreCase = true) }
            .distinctBy { "${it.domain.orEmpty().lowercase()}\\${it.username.orEmpty().lowercase()}" }
    }
    var customUserOpen by remember { mutableStateOf(false) }
    var customUsername by remember { mutableStateOf(activeUsername.orEmpty()) }
    var customDomain by remember { mutableStateOf("") }
    var customPassword by remember { mutableStateOf("") }

    val cleanRemoteSize = remoteSize.replace(" ", "")
    val monitorItems = listOf(
        MonitorGridItem(
            badge = "MON 1",
            title = kz("Monitor 1 · Utama", "Monitor 1 · Primary"),
            subtitle = if (cleanRemoteSize.isNotBlank() && cleanRemoteSize != "--") "$cleanRemoteSize · 16:9" else "1280x720 · 16:9",
            value = DisplayPrefs.AUTOMATIC,
            icon = KzIcons.Monitor,
            active = resolution == DisplayPrefs.AUTOMATIC || resolution == "1280x720",
        ),
        MonitorGridItem(
            badge = "MON 2",
            title = kz("Monitor 2 · QHD", "Monitor 2 · QHD"),
            subtitle = "2560x1440 · Studio",
            value = "2560x1440",
            icon = KzIcons.DualMonitor,
            active = resolution == "2560x1440",
        ),
        MonitorGridItem(
            badge = "SPAN",
            title = kz("Dual-Monitor Span", "Dual-Monitor Span"),
            subtitle = "2560x1080 · 21:9",
            value = "2560x1080",
            icon = KzIcons.DualMonitor,
            active = resolution == "2560x1080",
        ),
        MonitorGridItem(
            badge = "VIEW",
            title = kz("Layar Penuh HP", "Phone Native View"),
            subtitle = kz("Ikuti Viewport", "Follow Viewport"),
            value = DisplayPrefs.FOLLOW,
            icon = KzIcons.Fit,
            active = resolution == DisplayPrefs.FOLLOW,
        ),
    )

    KzOverlay(
        title = if (pcConnectMode) {
            kz("Monitor Fisik 1:1 & Mesin Direct Stream", "1:1 Physical Monitor & Direct Stream Engine")
        } else {
            kz("Monitor & Sesi Multi-User / Konsol RDP", "Active Monitors & Multi-User / Console Grid")
        },
        maxWidth = 440.dp,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Baris status deteksi otomatis
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    KzIcons.DualMonitor,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "$hostLabel · $remoteSize",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            if (pcConnectMode) {
                                append(kz("Koneksi PC · Direct Stream 1:1", "PC Connect · 1:1 Direct Stream"))
                                append(" · ${pcOptions.pcStreamEngine.badge}")
                            } else {
                                append(
                                    if (consoleAdminMode) kz("Konsol Fisik / Runner (/admin)", "Physical / Runner Console (/admin)")
                                    else kz("Sesi Virtual Multi-User RDP", "Multi-User Virtual RDP Session"),
                                )
                                if (!activeUsername.isNullOrBlank()) append(" · $activeUsername")
                            }
                            append(" · $codecLabel")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (pcConnectMode) {
                // Mode Koneksi PC: Eksklusif Direct Stream Engine (Tidak ada Multi-User RDP)
                Text(
                    text = kz("MESIN DIRECT PC STREAM (EKSKLUSIF KONEKSI PC)", "DIRECT PC STREAM ENGINE (PC CONNECT EXCLUSIVE)"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
                com.kazamatech.kzdesk.core.KzPcStreamEngine.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { engine ->
                            val isActive = pcOptions.pcStreamEngine == engine
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    )
                                    .border(
                                        if (isActive) 1.4.dp else 1.dp,
                                        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(11.dp),
                                    )
                                    .clickable {
                                        val next = pcOptions.withPcStreamEngine(engine)
                                        pcOptions = next
                                        next.write(context, deviceId)
                                        notice.show(kzNow("Mesin Direct Stream: {0} aktif", "Direct Stream Engine: {0} active", engine.badge))
                                    }
                                    .padding(10.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            KzIcons.Monitor,
                                            contentDescription = null,
                                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = if (isActive) kz("AKTIF", "ACTIVE") else engine.badge,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = kz(engine.title, engine.titleEn),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = kz(engine.detail, engine.detailEn),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KzPillButton(
                        text = kz("Layar Stealth (Privacy)", "Stealth Screen (Privacy)"),
                        icon = KzIcons.EyeOff,
                        onClick = onActivatePrivacyCurtain,
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    KzPillButton(
                        text = kz("Kunci Fisik PC (Win+L)", "Lock Physical PC (Win+L)"),
                        icon = KzIcons.Windows,
                        onClick = onLockRemotePc,
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                // Mode Koneksi RDP: Grid Monitor Virtual + Multi-User & Konsol Runner
                Text(
                    text = kz("GRID MONITOR & LAYAR AKTIF", "ACTIVE MONITORS & DISPLAYS GRID"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
                monitorItems.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (item.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    )
                                    .border(
                                        if (item.active) 1.4.dp else 1.dp,
                                        if (item.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(11.dp),
                                    )
                                    .clickable {
                                        onSelectMonitorResolution(item.value)
                                    }
                                    .padding(10.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            item.icon,
                                            contentDescription = null,
                                            tint = if (item.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Text(
                                            text = if (item.active) kz("AKTIF", "ACTIVE") else item.badge,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = item.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = item.subtitle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // Grid Sesi User & Konsol Runner Windows
                Text(
                    text = kz("GRID SESI WINDOWS: KONSOL RUNNER (/ADMIN) VS MULTI-USER", "WINDOWS SESSIONS: RUNNER CONSOLE (/ADMIN) VS MULTI-USER"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
                val isConsole = consoleAdminMode && !activeUsername.equals("KzDesk", ignoreCase = true)
                val isVirtualUser = !isConsole
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Card 1: Sesi Konsol Runner (/admin)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (isConsole) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            )
                            .border(
                                if (isConsole) 1.4.dp else 1.dp,
                                if (isConsole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(11.dp),
                            )
                            .clickable { onSwitchConsoleMode(true) }
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    KzIcons.Monitor,
                                    contentDescription = null,
                                    tint = if (isConsole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    text = if (isConsole) kz("AKTIF", "ACTIVE") else "/ADMIN",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isConsole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = kz("Konsol Runner / Admin", "Runner / Admin Console"),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = kz("Sesi 1 Konsol (runneradmin)", "Session 1 Console (runneradmin)"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                                maxLines = 1,
                            )
                        }
                    }

                    // Card 2: Sesi User RDP Virtual
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (isVirtualUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            )
                            .border(
                                if (isVirtualUser) 1.4.dp else 1.dp,
                                if (isVirtualUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(11.dp),
                            )
                            .clickable { onSwitchConsoleMode(false) }
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    KzIcons.Users,
                                    contentDescription = null,
                                    tint = if (isVirtualUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    text = if (isVirtualUser) kz("AKTIF", "ACTIVE") else "MULTI",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isVirtualUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = kz("Sesi Virtual Multi-User", "Multi-User Virtual Session"),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = kz("Sesi 2 Terpisah (KzDesk)", "Separate Session 2 (KzDesk)"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Target Akun Cepat (Runneradmin vs KzDesk + Akun Tersimpan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isRunnerActive = activeUsername.equals("runneradmin", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            .border(
                                1.dp,
                                if (isRunnerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                RdpOptions.of(context, deviceId).copy(consoleAdmin = true).write(context, deviceId)
                                onSwitchUserSession("runneradmin", null, null)
                            }
                            .padding(9.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "runneradmin (/admin)",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = kz("Pindah ke Konsol Runner", "Switch to Runner Console"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                            )
                        }
                    }
                    val isKzDeskActive = activeUsername.equals("KzDesk", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            .border(
                                1.dp,
                                if (isKzDeskActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                RdpOptions.of(context, deviceId).copy(consoleAdmin = false).write(context, deviceId)
                                onSwitchUserSession("KzDesk", null, null)
                            }
                            .padding(9.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "KzDesk (Multi-User)",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = kz("Pindah ke Sesi Virtual 2", "Switch to Virtual Session 2"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.5.sp,
                            )
                        }
                    }
                }

                if (savedAccounts.isNotEmpty()) {
                    savedAccounts.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { acc ->
                                val accUser = acc.username.orEmpty()
                                val isCurrentAcc = !activeUsername.isNullOrBlank() &&
                                    activeUsername.equals(accUser, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                        .border(
                                            1.dp,
                                            if (isCurrentAcc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable {
                                            onSwitchUserSession(accUser, acc.domain, acc.password)
                                        }
                                        .padding(9.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Icon(
                                            KzIcons.Users,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = acc.label?.takeIf { it.isNotBlank() } ?: accUser,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = accUser,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 9.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                if (customUserOpen) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KzField(
                            value = customUsername,
                            onValueChange = { customUsername = it },
                            label = kz("Username Windows", "Windows Username"),
                            hint = "runneradmin / Administrator / KzDesk",
                        )
                        KzField(
                            value = customPassword,
                            onValueChange = { customPassword = it },
                            label = kz("Password (opsional)", "Password (optional)"),
                            isPassword = true,
                        )
                        KzPillButton(
                            text = kz("Pindah ke User Ini Sekarang", "Switch to This User Now"),
                            icon = KzIcons.Users,
                            onClick = {
                                val u = customUsername.trim()
                                if (u.isNotEmpty()) {
                                    onSwitchUserSession(
                                        u,
                                        customDomain.trim().takeIf { it.isNotEmpty() },
                                        customPassword.takeIf { it.isNotEmpty() },
                                    )
                                }
                            },
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KzPillButton(
                        text = kz("Pindah User Lain", "Switch User"),
                        icon = KzIcons.Users,
                        onClick = { customUserOpen = !customUserOpen },
                        primary = customUserOpen,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    KzPillButton(
                        text = kz("Layar Kunci Windows", "Windows Lock Screen"),
                        icon = KzIcons.Windows,
                        onClick = onLockRemotePc,
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    text = kz("Perangkat Lain (Beranda)", "Other Devices (Home)"),
                    icon = KzIcons.Grid,
                    onClick = onOpenHome,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    text = kz("Tutup", "Close"),
                    icon = KzIcons.Close,
                    onClick = onDismiss,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Badge "REC" + waktu berjalan selama perekaman aktif.
 *
 * Selalu tampil selama [recordingActive] benar, termasuk saat panel atau mode
 * pemetaan tombol terbuka, supaya pengguna tidak lupa rekaman masih jalan.
 * Komponen ini hanya digambar saat merekam, jadi `remember` di dalamnya mulai
 * dari nol tiap sesi rekaman baru.
 */
@Composable
private fun RecordingIndicator(modifier: Modifier = Modifier) {
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsedMs by remember { mutableStateOf(0L) }
    var dotOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            dotOn = !dotOn
            delay(500L)
        }
    }
    val totalSeconds = elapsedMs / 1000L
    val clock = "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.68f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    MaterialTheme.colorScheme.error.copy(alpha = if (dotOn) 1f else 0.25f),
                    CircleShape,
                ),
        )
        Text(
            text = "REC",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = clock,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
        )
    }
}

/**
 * Riwayat clipboard: 10 teks terakhir yang disalin di HP. Ketuk satu entri
 * untuk menyalinnya ulang; bila kanal clipboard sesi aktif, sinkronisasi
 * otomatis yang sudah ada langsung meneruskannya ke PC.
 */
@Composable
private fun ClipboardHistoryBlock(clipboardSyncEnabled: Boolean) {
    val context = LocalContext.current
    val history by ClipboardHistory.entries.collectAsState()
    LaunchedEffect(Unit) { ClipboardHistory.load(context) }
    var feedback by remember { mutableStateOf<String?>(null) }

    Text(
        kz("Riwayat clipboard", "Clipboard history").uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (history.isEmpty()) {
        Text(
            kz(
                "Belum ada yang tersalin. Salin teks di HP atau di PC, lalu daftar ini terisi sendiri (maksimal 10 entri terakhir).",
                "Nothing copied yet. Copy text on the phone or the PC and this list fills itself (last 10 entries).",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        history.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable {
                        val ok = ClipboardHistory.copyToPhone(context, entry)
                        feedback = when {
                            !ok -> kzNow(
                                "Tidak bisa menyalin ke clipboard HP.",
                                "Could not copy to the phone clipboard.",
                            )
                            clipboardSyncEnabled -> kzNow(
                                "Disalin — otomatis diteruskan ke PC.",
                                "Copied — forwarded to the PC automatically.",
                            )
                            else -> kzNow(
                                "Disalin ke clipboard HP.",
                                "Copied to the phone clipboard.",
                            )
                        }
                    }
                    .padding(vertical = 7.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        kz(
                            "{0} karakter",
                            "{0} characters",
                            entry.text.length,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                    )
                }
                KzPillButton(
                    "\u2715",
                    {
                        ClipboardHistory.remove(context, entry.id)
                        feedback = null
                    },
                    primary = false,
                    compact = true,
                )
            }
        }
    }
    feedback?.let { message ->
        Text(
            message,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
        )
    }
    KzPillButton(
        kz("Hapus semua riwayat", "Clear all history"),
        {
            ClipboardHistory.clear(context)
            feedback = null
        },
        primary = false,
        compact = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
