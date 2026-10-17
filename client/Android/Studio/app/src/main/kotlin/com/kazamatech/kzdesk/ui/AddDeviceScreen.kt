package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.directory.PairingQr
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanContract
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.kazamatech.kzdesk.core.ConnectionProfile
import com.kazamatech.kzdesk.core.DiscoveredPcHost
import com.kazamatech.kzdesk.core.LanScanner
import com.kazamatech.kzdesk.core.RdpOptions
import com.kazamatech.kzdesk.core.WakeOnLan
import com.kazamatech.kzdesk.core.KzGpuProfile
import com.kazamatech.kzdesk.core.KzPcStreamEngine
import com.kazamatech.kzdesk.core.KzSecurityProtocol
import com.kazamatech.kzdesk.core.KzStreamProfile
import com.kazamatech.kzdesk.core.encodeIpv4ToPcId
import com.kazamatech.kzdesk.core.formatRdpEndpoint
import com.kazamatech.kzdesk.core.parsePcIdOrEndpoint
import com.kazamatech.kzdesk.core.parseRdpEndpoint
import com.kazamatech.kzdesk.core.KzAudioMode
import com.kazamatech.kzdesk.core.KzGateway
import com.kazamatech.kzdesk.security.CredentialVault
import com.kazamatech.kzdesk.ui.components.KzNoticeBus
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kazamatech.kzdesk.ui.components.KzSlider
import com.kazamatech.kzdesk.ui.components.KzCard
import com.kazamatech.kzdesk.ui.components.KzDialog
import com.kazamatech.kzdesk.ui.components.KzField
import com.kazamatech.kzdesk.ui.components.KzIconPill
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzOverlay
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzSectionLabel
import com.kazamatech.kzdesk.ui.components.KzSegmented
import com.kazamatech.kzdesk.KzConfig
import com.kazamatech.kzdesk.directory.DeviceDirectoryParser
import com.kazamatech.kzdesk.ui.components.KzToggleRow
import com.kazamatech.kzdesk.ui.components.KzTopBar
import com.kazamatech.kzdesk.ui.theme.KzPill

/**
 * Layar kz("Tambah perangkat", "Add device") — bukan dialog. Semua yang dibutuhkan satu
 * koneksi RDP ada di sini: alamat, kredensial, tampilan, kanal yang
 * di-redirect, dan gateway.
 *
 * Preferensi (tampilan + opsi RDP) ditulis langsung ke SharedPreferences
 * dengan id = `host:port`, jadi begitu profil disimpan, sesi berikutnya
 * memakai setelan yang sama.
 */
private data class SavedAccountOption(
    val username: String,
    val domain: String,
    val password: String,
)

@Composable
fun AddDeviceScreen(
    existing: ConnectionProfile?,
    savedUsers: List<String>,
    savedProfiles: List<ConnectionProfile> = emptyList(),
    initialPcQuickMode: Boolean = false,
    onCancel: () -> Unit,
    onSubmit: (profile: ConnectionProfile, rememberPassword: Boolean, connect: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val vault = remember { CredentialVault(context.applicationContext) }
    // Kunci tetap: dipakai ulang saat mengubah perangkat supaya simpan tidak
    // menghasilkan baris kedua. Data lama (belum punya key) memakai id-nya.
    val deviceKey = remember(existing) {
        existing?.key ?: existing?.id ?: java.util.UUID.randomUUID().toString()
    }
    val deviceId = deviceKey
    val existingStored = remember(deviceId, existing) {
        if (existing != null) RdpOptions.of(context, deviceId) else null
    }
    var isPcQuickMode by remember(existing, initialPcQuickMode) {
        mutableStateOf(
            if (existing != null) existingStored?.pcConnectMode == true
            else initialPcQuickMode,
        )
    }

    // "Setelan cepat" menyembunyikan bagian lanjutan supaya layar tidak panjang
    // dan tidak perlu scroll jauh saat cuma mau konek.
    var showAdvanced by remember { mutableStateOf(false) }

    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var host by remember(existing) {
        mutableStateOf(existing?.let { formatRdpEndpoint(it.host, it.port) }.orEmpty())
    }
    var user by remember {
        mutableStateOf(existing?.username.orEmpty())
    }
    var pass by remember {
        mutableStateOf(
            existing?.password
                ?: existing?.let {
                    vault.get(it.id)
                        ?: vault.get(CredentialVault.hostCredKey(it.host, it.port, it.username.orEmpty()))
                }.orEmpty(),
        )
    }
    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var rememberPass by remember { mutableStateOf(existing?.password?.isNotEmpty() ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Isi otomatis password bila host+user pernah dipakai (kredensial
    // tersimpan per host — tidak perlu input ulang tiap add koneksi).
    LaunchedEffect(host, user) {
        if (pass.isNotEmpty()) return@LaunchedEffect
        val ep = parseRdpEndpoint(host) ?: return@LaunchedEffect
        val stored = vault.get(CredentialVault.hostCredKey(ep.host, ep.port, user))
        if (!stored.isNullOrEmpty()) {
            pass = stored
            rememberPass = true
        }
    }

    val baseAccounts = remember(savedProfiles, savedUsers, existing) {
        val list = mutableListOf<SavedAccountOption>()
        savedProfiles.forEach { prof ->
            val u = prof.username?.trim().orEmpty()
            if (u.isNotEmpty() && !u.equals("KzDesk", ignoreCase = true)) {
                val p = prof.password ?: vault.get(prof.id).orEmpty()
                val d = prof.domain?.trim().orEmpty()
                if (list.none { it.username.equals(u, ignoreCase = true) && it.domain.equals(d, ignoreCase = true) }) {
                    list.add(SavedAccountOption(u, d, p))
                }
            }
        }
        savedUsers.forEach { u ->
            val clean = u.trim()
            if (clean.isNotEmpty() && !clean.equals("KzDesk", ignoreCase = true) &&
                list.none { it.username.equals(clean, ignoreCase = true) }
            ) {
                list.add(SavedAccountOption(clean, "", ""))
            }
        }
        list
    }
    var extraAccounts by remember { mutableStateOf<List<SavedAccountOption>>(emptyList()) }
    val allAccounts = remember(baseAccounts, extraAccounts) {
        (extraAccounts + baseAccounts).distinctBy { "${it.domain.lowercase()}\\${it.username.lowercase()}" }
    }
    // Jika sudah ada akun yang terisi (mis. saat edit perangkat atau setelah
    // user input akun), sembunyikan form dan tampilkan sebagai dropdown.
    var showCredentialForm by remember(existing) {
        mutableStateOf(existing?.username.isNullOrBlank() && baseAccounts.isEmpty())
    }
    var accountDropdownOpen by remember { mutableStateOf(false) }
    var accountSearch by remember { mutableStateOf("") }
    val filteredAccounts = remember(allAccounts, accountSearch) {
        val query = accountSearch.trim()
        if (query.isEmpty()) allAccounts else allAccounts.filter { account ->
            "${account.domain} ${account.username}".contains(query, ignoreCase = true)
        }
    }

    val scope = rememberCoroutineScope()
    val stored = remember(deviceId, existing, initialPcQuickMode) {
        if (existingStored != null) {
            existingStored
        } else {
            val app = AppPrefs(context)
            val baseProfile = if (initialPcQuickMode) {
                KzStreamProfile.ULTRA_LOW_LATENCY
            } else {
                KzStreamProfile.entries.getOrElse(app.defaultStreamProfile) {
                    KzStreamProfile.AUTO
                }
            }
            val baseSecProto = if (initialPcQuickMode) {
                KzSecurityProtocol.NLA
            } else {
                KzSecurityProtocol.entries.getOrElse(app.defaultSecurityProtocol) {
                    KzSecurityProtocol.AUTO
                }
            }
            RdpOptions(
                pcConnectMode = initialPcQuickMode,
                clipboard = app.defaultClipboard,
                localDrive = if (initialPcQuickMode) false else app.defaultLocalDrive,
                udpTransport = if (initialPcQuickMode) true else app.defaultUdp,
                networkAutoDetect = app.defaultNetAuto,
                h264 = true,
                dynamicResolution = if (initialPcQuickMode) false else app.defaultDynamicResolution,
                asyncUpdate = if (initialPcQuickMode) true else app.defaultAsyncUpdate,
                asyncChannels = if (initialPcQuickMode) true else app.defaultAsyncChannels,
                securityProtocol = baseSecProto,
                tlsSecLevel = if (initialPcQuickMode) 2 else app.defaultTlsSecLevel,
            ).withStreamProfile(baseProfile).copy(
                // Perangkat baru: mikrofon HP langsung aktif sebagai input suara PC
                // (izin RECORD_AUDIO tetap diminta saat tersambung). Mode PC memakai bridge sendiri.
                microphone = !initialPcQuickMode,
                pcConnectMode = initialPcQuickMode,
                udpTransport = if (initialPcQuickMode) true else app.defaultUdp,
                networkAutoDetect = app.defaultNetAuto,
                h264 = true,
                dynamicResolution = if (initialPcQuickMode) false else app.defaultDynamicResolution,
                asyncUpdate = if (initialPcQuickMode) true else app.defaultAsyncUpdate,
                asyncChannels = if (initialPcQuickMode) true else app.defaultAsyncChannels,
                securityProtocol = baseSecProto,
                tlsSecLevel = if (initialPcQuickMode) 2 else app.defaultTlsSecLevel,
            )
        }
    }
    var options by remember { mutableStateOf(stored) }
    var wolStatusText by remember { mutableStateOf<String?>(null) }
    var gatewayOn by remember { mutableStateOf(stored.gateway != null) }
    var gwHost by remember { mutableStateOf(stored.gateway?.host.orEmpty()) }
    var gwPort by remember { mutableStateOf((stored.gateway?.port ?: 443).toString()) }
    var gwUser by remember { mutableStateOf(stored.gateway?.username.orEmpty()) }
    var gwPass by remember { mutableStateOf(stored.gateway?.password.orEmpty()) }
    var gwDomain by remember { mutableStateOf(stored.gateway?.domain.orEmpty()) }

    var resolutionIndex by remember(deviceId) {
        mutableStateOf(
            if (deviceId == null) 0
            else DisplayPrefs.resolutionOptions.indexOfFirst {
                it.value == DisplayPrefs.resolution(context, deviceId)
            }.coerceAtLeast(0)
        )
    }
    var rotationIndex by remember(deviceId) {
        mutableStateOf(
            if (deviceId == null) 0
            else DisplayPrefs.rotations.indexOf(DisplayPrefs.rotation(context, deviceId))
                .coerceAtLeast(0)
        )
    }
    var dpi by remember(deviceId) {
        mutableStateOf(if (deviceId == null) 100 else DisplayPrefs.dpi(context, deviceId))
    }
    var audioIndex by remember { mutableStateOf(options.audioMode.ordinal) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var validationPopup by remember { mutableStateOf<String?>(null) }
    var lanScanModalOpen by remember { mutableStateOf(false) }
    var lanScanning by remember { mutableStateOf(false) }
    var lanFoundHosts by remember { mutableStateOf<List<DiscoveredPcHost>>(emptyList()) }
    var lanSelfIp by remember { mutableStateOf<String?>(null) }

    fun triggerLanScan() {
        lanScanModalOpen = true
        if (lanScanning) return
        lanScanning = true
        scope.launch {
            val localIp = withContext(Dispatchers.IO) { LanScanner.detectLocalIpv4Address() }
            lanSelfIp = localIp
            val hosts = withContext(Dispatchers.IO) { LanScanner.scanLocalSubnet() }
            lanFoundHosts = hosts
            lanScanning = false
        }
    }

    val initialHost = remember(existing) {
        existing?.let { formatRdpEndpoint(it.host, it.port) }.orEmpty()
    }
    val hasChanges = if (existing == null) {
        host.isNotBlank() || label.isNotBlank() || pass.isNotBlank()
    } else {
        host != initialHost ||
            label != existing.label.orEmpty() ||
            user != existing.username.orEmpty() ||
            pass != existing.password.orEmpty() ||
            domain != existing.domain.orEmpty() ||
            options != stored ||
            gatewayOn != (stored.gateway != null)
    }

    fun requestCancel() {
        if (confirmDiscard || !hasChanges) {
            confirmDiscard = false
            onCancel()
        } else {
            confirmDiscard = true
        }
    }

    BackHandler {
        when {
            accountDropdownOpen -> {
                accountDropdownOpen = false
                accountSearch = ""
            }
            validationPopup != null -> validationPopup = null
            confirmDiscard -> confirmDiscard = false
            else -> requestCancel()
        }
    }

    // Scan QR dari KzDesk Host: mengisi ID Device + password sekaligus.
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        val parsed = PairingQr.parse(contents)
        if (parsed != null) {
            host = DeviceDirectoryParser.formatId(parsed.id)
            pass = parsed.password
            error = null
        } else if (contents != null) {
            error = kzNow("QR tidak dikenali. Gunakan QR dari KzDesk Host.", "Unrecognized QR. Use the QR from KzDesk Host.")
        }
    }

    fun submit(connect: Boolean) {
        // Mode ID Device: host berisi ID 9 digit; alamat asli dicari ke server saat menyambung.
        val directoryId = if (isPcQuickMode) DeviceDirectoryParser.normalizeId(host) else null
        val endpoint = if (isPcQuickMode) {
            directoryId?.let { parsePcIdOrEndpoint("id-$it.kzdesk.invalid") }
        } else {
            parsePcIdOrEndpoint(host)
        }
        if (host.isBlank()) {
            val msg = if (isPcQuickMode) {
                kzNow("ID Device wajib diisi.", "Device ID is required.")
            } else {
                kzNow("Alamat host wajib diisi.", "Host address is required.")
            }
            error = msg
            validationPopup = msg
            return
        }
        if (endpoint == null) {
            val msg = kzNow(
                if (isPcQuickMode) "ID Device tidak valid. Masukkan 9 digit (mis. 123-456-789)."
                else "Alamat/port tidak valid. Gunakan host atau host:port.",
                if (isPcQuickMode) "Invalid Device ID. Enter 9 digits (e.g. 123-456-789)."
                else "Invalid address/port. Use host or host:port.",
            )
            error = msg
            validationPopup = msg
            return
        }
        if (gatewayOn && gwHost.isBlank()) {
            val msg = kzNow(
                "Gateway diaktifkan tetapi alamat Gateway host masih kosong.",
                "RDP Gateway is enabled, but the Gateway host address is empty.",
            )
            error = msg
            validationPopup = msg
            return
        }
        if (!options.macAddress.isNullOrBlank() && WakeOnLan.parseMacBytes(options.macAddress) == null) {
            val msg = kzNow(
                "Format MAC Address Wake-on-LAN tidak valid. Gunakan 6 pasang heksadesimal seperti AA:BB:CC:DD:EE:FF.",
                "Invalid Wake-on-LAN MAC address format. Use 6 hex pairs like AA:BB:CC:DD:EE:FF.",
            )
            error = msg
            validationPopup = msg
            return
        }
        if (isPcQuickMode && pass.length < 4) {
            val msg = kzNow("Password Device wajib diisi (min. 4 karakter).", "Device password is required (min. 4 characters).")
            error = msg
            validationPopup = msg
            return
        }
        val effectiveUser = if (isPcQuickMode) {
            KzConfig.DEVICE_USERNAME
        } else {
            user.trim().ifEmpty { null }
        }
        val effectiveDomain = if (isPcQuickMode) null else domain.trim().ifEmpty { null }
        val profile = try {
            ConnectionProfile(
                host = endpoint.host,
                port = endpoint.port,
                username = effectiveUser,
                password = pass.ifEmpty { null },
                domain = effectiveDomain,
                label = label.trim().ifEmpty { null },
                key = deviceKey,
            )
        } catch (e: IllegalArgumentException) {
            error = e.message
            validationPopup = e.message
            return
        }
        error = null
        val finalOptions = if (isPcQuickMode) {
            // ID Device = RDP biasa ke akun KzDesk di PC; alamatnya dicari saat menyambung.
            options.copy(
                pcConnectMode = false,
                directoryId = directoryId.orEmpty(),
                securityProtocol = KzSecurityProtocol.AUTO,
                audioMode = KzAudioMode.entries[audioIndex],
            )
        } else {
            options.copy(
                pcConnectMode = false,
                gpuProfile = KzGpuProfile.AUTO,
                audioMode = KzAudioMode.entries[audioIndex],
                gateway = if (gatewayOn && gwHost.isNotBlank()) {
                    KzGateway(
                        host = gwHost.trim(),
                        port = gwPort.toIntOrNull() ?: 443,
                        username = gwUser.trim().ifEmpty { null },
                        password = gwPass.ifEmpty { null },
                        domain = gwDomain.trim().ifEmpty { null },
                    )
                } else {
                    null
                },
            )
        }
        finalOptions.write(context, profile.id)
        if (isPcQuickMode) {
            DisplayPrefs.setResolution(context, profile.id, DisplayPrefs.AUTOMATIC)
            DisplayPrefs.setRotation(context, profile.id, DisplayPrefs.rotations[rotationIndex])
            DisplayPrefs.setDpi(context, profile.id, 100)
        } else {
            DisplayPrefs.setResolution(
                context,
                profile.id,
                DisplayPrefs.resolutionOptions[resolutionIndex].value,
            )
            DisplayPrefs.setRotation(context, profile.id, DisplayPrefs.rotations[rotationIndex])
            DisplayPrefs.setDpi(context, profile.id, dpi)
        }
        onSubmit(profile, rememberPass, connect)
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        KzTopBar(
            title = when {
                existing != null && isPcQuickMode -> kz("Ubah Perangkat (ID Device)", "Edit Device (Device ID)")
                existing != null -> kz("Ubah Perangkat RDP", "Edit RDP Device")
                isPcQuickMode -> kz("Perangkat Baru · ID Device", "New Device · Device ID")
                else -> kz("Koneksi RDP Baru", "New RDP Connection")
            },
            onBack = { requestCancel() },
            actions = {
                KzIconPill(
                    KzIcons.Gear,
                    { showAdvanced = true },
                    active = showAdvanced,
                    flat = true,
                    contentDescription = kz("Pengaturan lanjutan", "Advanced settings"),
                )
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KzSegmented(
                options = listOf(
                    kz("Setelan cepat", "Quick setup"),
                    kz("Lanjutan", "Advanced"),
                ),
                selectedIndex = if (showAdvanced) 1 else 0,
                onSelect = { showAdvanced = it == 1 },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!showAdvanced) {
                Text(
                    kz(
                        "Mode cepat hanya menampilkan yang perlu untuk konek: akun, tipe koneksi, audio, " +
                            "clipboard, drive, dan kamera.",
                        "Quick mode shows only what a connection needs: account, connection type, audio, " +
                            "clipboard, drive, and camera.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (existing == null) {
                KzSegmented(
                    options = listOf(
                        kz("RDP via IP", "RDP via IP"),
                        kz("ID Device + Password", "Device ID + Password"),
                    ),
                    selectedIndex = if (isPcQuickMode) 1 else 0,
                    onSelect = { idx ->
                        val wantPc = idx == 1
                        isPcQuickMode = wantPc
                        if (wantPc) {
                            options = options.copy(pcConnectMode = false, microphone = true)
                        } else {
                            options = options.withStreamProfile(KzStreamProfile.AUTO).copy(
                                pcConnectMode = false,
                                gpuProfile = KzGpuProfile.AUTO,
                                dynamicResolution = true,
                                consoleAdmin = false,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            KzSectionLabel(
                if (isPcQuickMode) kz("Perangkat · ID Device + Password", "Device · Device ID + Password")
                else kz("Alamat Host / Server RDP", "RDP Host / Server Address")
            )
            KzCard {
                KzField(
                    value = label,
                    onValueChange = { label = it },
                    label = if (isPcQuickMode) kz("Label PC (opsional)", "PC Label (optional)")
                    else kz("Nama perangkat", "Device name"),
                    hint = if (isPcQuickMode) kz("mis. PC Gaming / Rig Utama", "e.g. Gaming PC / Main Rig")
                    else kz("mis. PC kantor", "e.g. Office PC"),
                )
                Spacer(Modifier.height(14.dp))
                KzField(
                    value = host,
                    onValueChange = { host = it },
                    label = if (isPcQuickMode) {
                        kz("ID Device (9 digit)", "Device ID (9 digits)")
                    } else {
                        kz("Host / IP (port opsional)", "Host / IP (optional port)")
                    },
                    hint = if (isPcQuickMode) {
                        kz("123-456-789 (tampil di KzDesk Host)", "123-456-789 (shown in KzDesk Host)")
                    } else {
                        kz("pc.tailnet.ts.net atau [2001:db8::1]:3390", "pc.example.com or [2001:db8::1]:3390")
                    },
                    keyboardType = KeyboardType.Uri,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                )
                if (!isPcQuickMode) {
                Spacer(Modifier.height(10.dp))
                KzPillButton(
                    text = if (isPcQuickMode) {
                        kz("Pindai PC Otomatis di Wi-Fi (LAN)", "Auto-Scan PCs on Wi-Fi (LAN)")
                    } else {
                        kz("Pindai Host RDP di Jaringan Lokal (LAN)", "Scan RDP Hosts on Local Network (LAN)")
                    },
                    onClick = { triggerLanScan() },
                    icon = KzIcons.Wifi,
                    primary = false,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
                if (isPcQuickMode) {
                    Spacer(Modifier.height(14.dp))
                    KzField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = kz("Password Device", "Device password"),
                        hint = kz("Tampil di akhir instalasi KzDesk Host (bukan password Windows)", "Shown after installing KzDesk Host (not your Windows password)"),
                        isPassword = true,
                        keyboardType = KeyboardType.Password,
                    )
                }
                val resolvedPreview = remember(host) { parsePcIdOrEndpoint(host) }
                val encodedPcId = remember(resolvedPreview) {
                    resolvedPreview?.let { encodeIpv4ToPcId(it.host) }
                }
                if (!isPcQuickMode && resolvedPreview != null && host.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append(kz("Target: ", "Target: "))
                            append(resolvedPreview.host).append(':').append(resolvedPreview.port)
                            if (encodedPcId != null) {
                                append("  ·  ID PC: ").append(encodedPcId)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isPcQuickMode) {
                KzPillButton(
                    text = kz("Scan QR dari KzDesk Host", "Scan QR from KzDesk Host"),
                    onClick = {
                        qrScanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt(kzNow("Arahkan ke QR di layar PC", "Point at the QR on the PC screen"))
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                KzSectionLabel(kz("KzDesk Host (Windows)", "KzDesk Host (Windows)"))
                KzCard {
                    Text(kz("Pasang sekali di PC", "Install once on the PC"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        kz(
                            "Satu installer memasang agen host, aturan firewall, akun KzDesk, dan updater otomatis. ID Device + password tampil di akhir instalasi.",
                            "One installer sets up the host agent, firewall rules, the KzDesk account and the auto-updater. Your Device ID + password appear at the end.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KzPillButton(
                            text = kz("Salin perintah setup", "Copy setup command"),
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("kzdesk-host-cmd", KzConfig.HOST_INSTALL_ONE_LINER))
                                KzNoticeBus.post(
                                    kzNow(
                                        "Perintah disalin. Jalankan di PowerShell (Administrator) pada PC.",
                                        "Command copied. Run it in PowerShell (Administrator) on the PC.",
                                    ),
                                )
                            },
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                        KzPillButton(
                            text = kz("Halaman Host", "Host page"),
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KzConfig.HOST_PAGE_URL)))
                                }
                            },
                            primary = false,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                KzSectionLabel(kz("Cara kerja", "How it works"))
                KzCard {
                    Text(
                        kz(
                            "Aplikasi mencari alamat PC lewat ID + password, mencoba LAN/VPN lebih dulu lalu IP publik, kemudian menyambung RDP ke akun KzDesk di PC. Dari internet, PC memerlukan port 3389 yang terbuka atau jaringan VPN (mis. Tailscale).",
                            "The app looks up the PC by ID + password, tries LAN/VPN first and then the public IP, and then connects over RDP to the KzDesk account on the PC. From the internet the PC needs port 3389 reachable or a VPN (e.g. Tailscale).",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
 else {

            KzSectionLabel(kz("Kredensial", "Credentials"))
            KzCard {
                if (!showCredentialForm) {
                    val activeAccountLabel = if (user.isNotBlank()) {
                        if (domain.isNotBlank()) "$domain\\$user" else user
                    } else {
                        kz("Tanpa Akun (Tanya saat Connect)", "No Account (Ask on Connect)")
                    }
                    val activeAccountSub = if (user.isNotBlank()) {
                        if (pass.isNotEmpty() && rememberPass) {
                            kz("Password tersimpan (AES-256-GCM) · Ketuk untuk ganti akun", "Password saved (AES-256-GCM) · Tap to switch account")
                        } else {
                            kz("Password ditanya saat connect · Ketuk untuk ganti akun", "Password asked on connect · Tap to switch account")
                        }
                    } else {
                        kz("Ketuk untuk memilih akun tersimpan atau tambah akun baru", "Tap to select a saved account or add a new account")
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                            .clickable {
                                accountDropdownOpen = !accountDropdownOpen
                                accountSearch = ""
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    kz("Akun Kredensial Aktif", "Active Credential Account"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    activeAccountLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    activeAccountSub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (accountDropdownOpen) "▴" else "▾",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }

                    if (accountDropdownOpen) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    kz("Akun tersimpan", "Saved accounts"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    kz("Tutup", "Close"),
                                    modifier = Modifier.clickable {
                                        accountDropdownOpen = false
                                        accountSearch = ""
                                    }.padding(horizontal = 8.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (allAccounts.size >= 4) {
                                KzField(
                                    value = accountSearch,
                                    onValueChange = { accountSearch = it },
                                    label = kz("Cari akun", "Search accounts"),
                                    hint = kz("Username atau domain", "Username or domain"),
                                )
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(vertical = 4.dp),
                            ) {
                                filteredAccounts.forEach { acc ->
                                    val itemTitle = if (acc.domain.isNotBlank()) "${acc.domain}\\${acc.username}" else acc.username
                                    val isSelected = acc.username.equals(user, ignoreCase = true) &&
                                        acc.domain.equals(domain, ignoreCase = true)
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .clickable {
                                                user = acc.username
                                                domain = acc.domain
                                                if (acc.password.isNotEmpty()) {
                                                    pass = acc.password
                                                    rememberPass = true
                                                }
                                                accountDropdownOpen = false
                                                accountSearch = ""
                                            }
                                            .padding(horizontal = 14.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                itemTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                if (acc.password.isNotEmpty()) {
                                                    kz("Password tersimpan · siap dipakai", "Password saved · ready to use")
                                                } else {
                                                    kz("Akun tersimpan", "Saved account")
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (isSelected) {
                                            Text(
                                                kz("✓ Dipilih", "✓ Selected"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                }
                                if (allAccounts.isNotEmpty() && filteredAccounts.isEmpty()) {
                                    Text(
                                        kz("Tidak ada akun yang cocok.", "No matching accounts."),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (user.isNotBlank()) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 52.dp)
                                            .clickable {
                                                accountDropdownOpen = false
                                                accountSearch = ""
                                                showCredentialForm = true
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            kz("✎ Ubah detail / password akun ini", "✎ Edit this account"),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                    }
                                }

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .clickable {
                                            user = ""
                                            pass = ""
                                            domain = ""
                                            accountDropdownOpen = false
                                            accountSearch = ""
                                            showCredentialForm = true
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        kz("+ Tambah akun baru", "+ Add new account"),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .clickable {
                                            user = ""
                                            pass = ""
                                            domain = ""
                                            accountDropdownOpen = false
                                            accountSearch = ""
                                            showCredentialForm = false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        kz("Tanpa akun (tanya saat Connect)", "No account (ask on connect)"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (isPcQuickMode && pass.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        KzField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = kz("Password Akses PC", "PC Access Password"),
                            hint = kz("Masukkan password PC dari KzDeskHost", "Enter PC password from KzDeskHost"),
                            isPassword = true,
                            keyboardType = KeyboardType.Password,
                        )
                    }
                } else {
                    KzField(
                        value = user,
                        onValueChange = { user = it },
                        label = kz("Username", "Username"),
                        hint = kz(
                            "kosongkan kalau mau ditanya saat connect",
                            "leave empty to be asked on connect",
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    KzField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = kz("Password", "Password"),
                        isPassword = true,
                        keyboardType = KeyboardType.Password,
                    )
                    Spacer(Modifier.height(14.dp))
                    KzField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = kz("Domain (opsional)", "Domain (optional)"),
                        hint = "CORP",
                    )
                    Spacer(Modifier.height(6.dp))
                    KzToggleRow(
                        title = kz("Ingat password", "Remember password"),
                        subtitle = kz("Disimpan terenkripsi AES-GCM, kunci di Android Keystore", "Stored AES-GCM encrypted, key in Android Keystore"),
                        checked = rememberPass,
                        onCheckedChange = { rememberPass = it },
                        leading = KzIcons.Lock,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (allAccounts.isNotEmpty()) {
                            KzPillButton(
                                text = kz("Pilih dari Dropdown ({0})", "Pick from Dropdown ({0})", allAccounts.size),
                                onClick = {
                                    if (user.isBlank()) {
                                        val first = allAccounts.first()
                                        user = first.username
                                        domain = first.domain
                                        if (first.password.isNotEmpty()) pass = first.password
                                    }
                                    showCredentialForm = false
                                    accountSearch = ""
                                    accountDropdownOpen = true
                                },
                                primary = false,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        KzPillButton(
                            text = kz("Simpan Akun ke Pilihan", "Save Account to Dropdown"),
                            onClick = {
                                val u = user.trim()
                                if (u.isNotEmpty()) {
                                    extraAccounts = listOf(SavedAccountOption(u, domain.trim(), pass)) + extraAccounts
                                }
                                showCredentialForm = false
                                accountDropdownOpen = false
                                accountSearch = ""
                            },
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Mode cepat menyembunyikan bagian ini; ada di tab Lanjutan.
            if (showAdvanced) {
            KzSectionLabel(kz("Tampilan", "Display"))
            KzCard {
                Text(
                    kz("Resolusi desktop remote", "Remote desktop resolution"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    kz(
                        "Default \"Otomatis\" selalu 16:9 (pas untuk desktop Windows). " +
                            "\"Ikuti layar HP\" memakai rasio HP — untuk video/game.",
                        "The \"Automatic\" default is always 16:9 (right for Windows " +
                            "desktops). \"Follow phone screen\" uses the phone ratio — for video/games.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisplayPrefs.resolutionOptions.chunked(2).forEachIndexed { rowIndex, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEachIndexed { colIndex, option ->
                                val index = rowIndex * 2 + colIndex
                                KzPillButton(
                                    text = kz(option.id, option.en),
                                    onClick = { resolutionIndex = index },
                                    primary = index == resolutionIndex,
                                    compact = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    kz("Orientasi perangkat", "Device orientation"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                KzSegmented(
                    options = DisplayPrefs.rotations,
                    selectedIndex = rotationIndex,
                    onSelect = { rotationIndex = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    kz("Skala lokal awal: {0}%", "Initial local zoom: {0}%", dpi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                KzSlider(
                    value = dpi.toFloat(),
                    onValueChange = { dpi = it.toInt() },
                    valueRange = 80f..200f,
                    steps = 11,
                )
                Text(
                    kz(
                        "Zoom lokal ini menjadi nilai awal saat sesi dibuka. DPI desktop Windows " +
                            "diatur terpisah dari panel sesi; resolusi remote juga kontrol terpisah.",
                        "This local zoom is the starting value when a session opens. Windows desktop DPI " +
                            "is controlled separately in the session panel; remote resolution is separate too.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            }

            KzSectionLabel(kz("Audio & kanal", "Audio & channels"))
            KzCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KzAudioMode.entries.forEachIndexed { index, mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (index == audioIndex) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface
                                )
                                .border(
                                    1.dp,
                                    if (index == audioIndex) MaterialTheme.colorScheme.outlineVariant
                                    else MaterialTheme.colorScheme.outline,
                                    MaterialTheme.shapes.medium,
                                )
                                .clickable { audioIndex = index }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(kz(mode.title, mode.titleEn), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    kz(mode.detail, mode.detailEn),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val audioNote = when (KzAudioMode.entries[audioIndex]) {
                        KzAudioMode.DEVICE ->
                            kz(
                                "Mode Perangkat memutar suara PC lewat kanal audio RDP (tanpa host agent). " +
                                    "Kalau di PC 'Remote Audio' dimatikan demi VB-CABLE, pilih mode Remote.",
                                "Device mode plays PC sound over the RDP audio channel (no host agent). " +
                                    "If PC 'Remote Audio' is disabled for VB-CABLE, switch to Remote mode.",
                            )
                        KzAudioMode.REMOTE ->
                            kz(
                                "Mode Remote = suara PC lewat jembatan UDP :4433, jadi KzDeskRemoteHost.exe " +
                                    "harus jalan di PC. Pakai ini kalau kanal audio RDP diblokir/kosong.",
                                "Remote mode routes PC sound through the UDP :4433 bridge, so KzDeskRemoteHost.exe " +
                                    "must run on the PC. Use it when the RDP audio channel is blocked/empty.",
                            )
                        KzAudioMode.OFF ->
                            kz("Audio dimatikan total — jembatan UDP tidak dijalankan.", "Audio fully off — the UDP bridge stays down.")
                    }
                    Text(
                        audioNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val bridgeRelevant = KzAudioMode.entries[audioIndex] == KzAudioMode.REMOTE
                KzToggleRow(
                    title = kz("Audio PC via UDP (bridge)", "PC audio over UDP (bridge)"),
                    subtitle = if (bridgeRelevant) {
                        kz(
                            "Perlu KzDeskRemoteHost.exe di PC (port UDP 4433). Tanpa itu suara PC tidak sampai.",
                            "Needs KzDeskRemoteHost.exe on the PC (UDP 4433). Without it, PC sound never arrives.",
                        )
                    } else {
                        kz(
                            "Tidak dipakai: mode Perangkat memutar suara lewat kanal RDP.",
                            "Not used: Device mode plays sound through the RDP channel.",
                        )
                    },
                    checked = options.quicAudio && bridgeRelevant,
                    onCheckedChange = { v -> options = options.copy(quicAudio = v) },
                    leading = KzIcons.Volume,
                    enabled = bridgeRelevant,
                )
                KzToggleRow(
                    title = kz("Mikrofon HP → PC", "Phone mic → PC"),
                    subtitle = kz(
                        "Suara dari mikrofon HP menjadi input suara di PC (perangkat rekam \"Remote Audio\"). Butuh izin mikrofon.",
                        "Your phone's microphone becomes the PC's voice input (the \"Remote Audio\" recording device). Needs mic permission.",
                    ),
                    checked = options.microphone,
                    onCheckedChange = { options = options.copy(microphone = it) },
                    leading = KzIcons.Mic,
                )
                if (options.microphone) {
                    Spacer(Modifier.height(8.dp))
                    KzSectionLabel(kz("Mic lanjutan (DSP di HP)", "Microphone DSP (on phone)"))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        KzSliderRow(
                            label = kz("Gain mic", "Mic gain"),
                            value = options.micGainDb.toFloat(),
                            suffix = " dB",
                            range = -12f..24f,
                            steps = 6,
                        ) { options = options.copy(micGainDb = it.toInt()) }
                        KzSliderRow(
                            label = kz("Noise gate", "Noise gate"),
                            value = options.micGateDb.toFloat(),
                            suffix = " dBFS",
                            range = -70f..0f,
                            steps = 6,
                        ) { options = options.copy(micGateDb = it.toInt()) }
                        KzToggleRow(
                            title = kz("Noise suppression", "Noise suppression"),
                            subtitle = kz("High-pass + expander buang desis/AC/angin", "High-pass + expander kills hiss, AC hum, wind"),
                            checked = options.micNoiseSuppression,
                            onCheckedChange = { options = options.copy(micNoiseSuppression = it) },
                            leading = KzIcons.Mic,
                        )
                        KzToggleRow(
                            title = kz("Auto gain (AGC)", "Auto gain (AGC)"),
                            subtitle = kz("Level mic HP disamakan otomatis saat bicara", "Phone mic level auto-levelled while talking"),
                            checked = options.micAgc,
                            onCheckedChange = { options = options.copy(micAgc = it) },
                            leading = KzIcons.Mic,
                        )
                        Text(
                            kz(
                                "Mic dirender ke endpoint 'CABLE Input / KzDesk Virtual Microphone' di PC; " +
                                    "kalau tidak ada, Host Agent pakai perangkat default.",
                                "The mic renders into the PC's 'CABLE Input / KzDesk Virtual Microphone' endpoint; " +
                                    "if missing, the Host Agent falls back to the default device.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                KzToggleRow(
                    title = kz("Clipboard", "Clipboard"),
                    subtitle = kz("Copy-paste dua arah", "Two-way copy-paste"),
                    checked = options.clipboard,
                    onCheckedChange = { options = options.copy(clipboard = it) },
                    leading = KzIcons.Clip,
                )
                KzToggleRow(
                    title = kz("Penyimpanan lokal", "Local storage"),
                    subtitle = kz("Folder HP muncul sebagai drive 'KzDesk' di PC remote", "Phone folder appears as the 'KzDesk' drive on the remote PC"),
                    checked = options.localDrive,
                    onCheckedChange = { options = options.copy(localDrive = it) },
                    leading = KzIcons.Folder,
                )
                if (options.localDrive) {
                    StorageAccessRow()
                }
                KzToggleRow(
                    title = kz("Kamera", "Camera"),
                    subtitle = kz("Kamera HP sebagai webcam remote (kalau didukung server)", "Phone camera as remote webcam (if the server supports it)"),
                    checked = options.camera,
                    onCheckedChange = { options = options.copy(camera = it) },
                    leading = KzIcons.Shot,
                )
            }

            if (showAdvanced) {
            KzSectionLabel(kz("Streaming & Latensi", "Streaming & Latency"))
            KzCard {
                Text(
                    kz("Profil Streaming & Latensi", "Streaming & Latency Profile"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    kz(
                        "Pilih profil instan atau atur codec, warna, dan optimasi latensi di bawah.",
                        "Pick an instant preset or customize codec, color depth, and latency flags below.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val streamPresets = listOf(
                    KzStreamProfile.AUTO,
                    KzStreamProfile.BALANCED,
                    KzStreamProfile.ULTRA_LOW_LATENCY,
                    KzStreamProfile.HIGH_VISUAL,
                    KzStreamProfile.DATA_SAVER,
                )
                streamPresets.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { preset ->
                            KzPillButton(
                                text = kz(preset.title, preset.titleEn),
                                onClick = { options = options.withStreamProfile(preset) },
                                primary = options.streamProfile == preset,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (options.streamProfile == KzStreamProfile.CUSTOM) {
                    Text(
                        kz("Profil aktif: Kustom Manual", "Active profile: Custom Manual"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                KzToggleRow(
                    title = kz("Mode Hemat Baterai & Suhu Dingin (VSYNC Pacing)", "Battery Saver & Cool Thermal Mode (VSYNC Pacing)"),
                    subtitle = kz(
                        "Gabungkan burst update piksel per siklus layar agar HP tidak cepat panas dan hemat baterai",
                        "Coalesce pixel burst updates per screen cycle to keep the phone cool and save battery",
                    ),
                    checked = options.batterySaver,
                    onCheckedChange = { options = options.copy(batterySaver = it) },
                    leading = KzIcons.Sliders,
                )
                KzToggleRow(
                    title = kz("Transport UDP (RDP-UDP + FEC)", "UDP transport (RDP-UDP + FEC)"),
                    subtitle = kz(
                        "RDP-UDP + FEC; lebih halus untuk gerakan cepat, butuh dukungan server",
                        "RDP-UDP + FEC; smoother fast motion, needs server support",
                    ),
                    checked = options.udpTransport,
                    onCheckedChange = { options = options.copy(udpTransport = it) },
                    leading = KzIcons.Wifi,
                )
                KzToggleRow(
                    title = kz("Deteksi bandwidth otomatis", "Automatic bandwidth detection"),
                    subtitle = kz(
                        "Profil otomatis FreeRDP mengikuti kondisi jaringan",
                        "FreeRDP auto profile adapts to network conditions",
                    ),
                    checked = options.networkAutoDetect,
                    onCheckedChange = {
                        options = options.copy(networkAutoDetect = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                    leading = KzIcons.Sliders,
                    enabled = !options.lowBandwidth,
                )
                KzToggleRow(
                    title = kz("Jaringan terbatas (broadband-low / AVC420)", "Low-bandwidth mode (broadband-low / AVC420)"),
                    subtitle = kz(
                        "Pakai profil broadband-low + AVC420 untuk koneksi seluler/hemat",
                        "Use broadband-low + AVC420 hints for cellular/metered connections",
                    ),
                    checked = options.lowBandwidth,
                    onCheckedChange = {
                        options = options.copy(lowBandwidth = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                    leading = KzIcons.Sliders,
                )
                KzToggleRow(
                    title = kz("H.264 / RemoteFX (GFX)", "H.264 / RemoteFX (GFX)"),
                    subtitle = kz(
                        "Akselerasi hardware H.264 (AVC444 di jaringan normal, AVC420 di mode hemat)",
                        "H.264 hardware acceleration (AVC444 on normal links, AVC420 in low-bandwidth)",
                    ),
                    checked = options.h264,
                    onCheckedChange = {
                        options = options.copy(h264 = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                    leading = KzIcons.Grid,
                )
                KzToggleRow(
                    title = kz("Async Frame Update (+async-update)", "Async Frame Update (+async-update)"),
                    subtitle = kz(
                        "Render layar tidak menahan antrean input untuk respons maksimal",
                        "Decouple frame rendering from input queue for maximum responsiveness",
                    ),
                    checked = options.asyncUpdate,
                    onCheckedChange = {
                        options = options.copy(asyncUpdate = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
                KzToggleRow(
                    title = kz("Async Input & Channels (+async-channels)", "Async Input & Channels (+async-channels)"),
                    subtitle = kz(
                        "Proses kanal input, audio, dan clipboard di thread mandiri",
                        "Process input, audio, and clipboard channels on dedicated threads",
                    ),
                    checked = options.asyncChannels,
                    onCheckedChange = {
                        options = options.copy(asyncChannels = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    kz("Level kompresi paket RDP", "RDP packet compression level"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                KzSegmented(
                    options = listOf(
                        kz("Mati (0)", "Off (0)"),
                        kz("Standar (1)", "Standard (1)"),
                        kz("Maksimum (2)", "Maximum (2)"),
                    ),
                    selectedIndex = options.compressionLevel.coerceIn(0, 2),
                    onSelect = {
                        options = options.copy(
                            compressionLevel = it,
                            streamProfile = KzStreamProfile.CUSTOM,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    kz("Kedalaman warna (Color Depth)", "Color depth (BPP)"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                val depthValues = listOf(32, 24, 16)
                KzSegmented(
                    options = listOf("32-bit", "24-bit", "16-bit"),
                    selectedIndex = depthValues.indexOf(options.colorDepth).coerceAtLeast(0),
                    onSelect = {
                        options = options.copy(
                            colorDepth = depthValues[it],
                            streamProfile = KzStreamProfile.CUSTOM,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    kz("Pengalaman Visual Desktop Windows", "Windows Desktop Visual Experience"),
                    style = MaterialTheme.typography.titleSmall,
                )
                KzToggleRow(
                    title = kz("Font Smoothing (ClearType)", "Font Smoothing (ClearType)"),
                    checked = options.fontSmoothing,
                    onCheckedChange = {
                        options = options.copy(fontSmoothing = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
                KzToggleRow(
                    title = kz("Desktop Composition (Aero DWM)", "Desktop Composition (Aero DWM)"),
                    checked = options.desktopComposition,
                    onCheckedChange = {
                        options = options.copy(desktopComposition = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
                KzToggleRow(
                    title = kz("Wallpaper Desktop", "Desktop Wallpaper"),
                    checked = options.desktopWallpaper,
                    onCheckedChange = {
                        options = options.copy(desktopWallpaper = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
                KzToggleRow(
                    title = kz("Tema Visual Windows", "Windows Visual Themes"),
                    checked = options.visualThemes,
                    onCheckedChange = {
                        options = options.copy(visualThemes = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
                KzToggleRow(
                    title = kz("Tarik Jendela Penuh (Full Window Drag)", "Full Window Drag"),
                    checked = options.windowDrag,
                    onCheckedChange = {
                        options = options.copy(windowDrag = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
                KzToggleRow(
                    title = kz("Animasi Menu Windows", "Windows Menu Animations"),
                    checked = options.menuAnimations,
                    onCheckedChange = {
                        options = options.copy(menuAnimations = it, streamProfile = KzStreamProfile.CUSTOM)
                    },
                )
            }

            KzSectionLabel(kz("Keamanan & Sesi Admin", "Security & Admin Session"))
            KzCard {
                Text(
                    kz("Protokol keamanan autentikasi", "Authentication security protocol"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                KzSecurityProtocol.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { proto ->
                            KzPillButton(
                                text = kz(proto.title, proto.titleEn),
                                onClick = { options = options.copy(securityProtocol = proto) },
                                primary = options.securityProtocol == proto,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    kz("Level Kebijakan OpenSSL TLS", "OpenSSL TLS Security Level"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                val tlsValues = listOf(-1, 0, 1, 2)
                KzSegmented(
                    options = listOf(
                        kz("Auto", "Auto"),
                        kz("Legacy (0)", "Legacy (0)"),
                        kz("Standar (1)", "Standard (1)"),
                        kz("Ketat (2)", "Strict (2)"),
                    ),
                    selectedIndex = tlsValues.indexOf(options.tlsSecLevel).coerceAtLeast(0),
                    onSelect = { options = options.copy(tlsSecLevel = tlsValues[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                KzToggleRow(
                    title = kz("Sesi Konsol / Admin (/admin)", "Console / Admin Session (/admin)"),
                    subtitle = kz(
                        "Masuk ke sesi konsol fisik atau sesi administrator Windows Server",
                        "Connect to the physical console or Windows Server administrator session",
                    ),
                    checked = options.consoleAdmin,
                    onCheckedChange = { options = options.copy(consoleAdmin = it) },
                    leading = KzIcons.Lock,
                )
                KzToggleRow(
                    title = kz("Mode Restricted Admin (/restricted-admin)", "Restricted Admin Mode (/restricted-admin)"),
                    subtitle = kz(
                        "Login NLA tanpa menyerahkan kredensial plaintext ke host remote (anti pass-the-hash)",
                        "Authenticate via NLA without sending plaintext credentials to the remote host",
                    ),
                    checked = options.restrictedAdmin,
                    onCheckedChange = { options = options.copy(restrictedAdmin = it) },
                    leading = KzIcons.Lock,
                )
            }

            KzSectionLabel(kz("Wake-on-LAN & SSH Tunnel", "Wake-on-LAN & SSH Tunnel"))
            KzCard {
                Text(
                    kz("Wake-on-LAN (Magic Packet)", "Wake-on-LAN (Magic Packet)"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    kz(
                        "Nyalakan PC dari kondisi Sleep/Shutdown lewat jaringan lokal atau VPN sebelum konek RDP.",
                        "Wake the PC from Sleep/Shutdown over LAN or VPN before connecting via RDP.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                KzField(
                    value = options.macAddress.orEmpty(),
                    onValueChange = { options = options.copy(macAddress = it.trim().ifEmpty { null }) },
                    label = kz("MAC Address kartu jaringan PC (opsional)", "PC network card MAC Address (optional)"),
                    hint = "AA:BB:CC:DD:EE:FF",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KzField(
                        value = options.wolBroadcast,
                        onValueChange = { options = options.copy(wolBroadcast = it.trim()) },
                        label = kz("Broadcast / IP tujuan", "Broadcast / target IP"),
                        hint = "255.255.255.255",
                        modifier = Modifier.weight(1.4f),
                    )
                    KzField(
                        value = options.wolPort.toString(),
                        onValueChange = {
                            val p = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 9
                            options = options.copy(wolPort = p.coerceIn(1, 65535))
                        },
                        label = kz("Port UDP", "UDP Port"),
                        hint = "9",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(0.7f),
                    )
                }
                if (!options.macAddress.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    KzPillButton(
                        text = kz("Kirim Tes Magic Packet Sekarang", "Send Test Magic Packet Now"),
                        onClick = {
                            val mac = options.macAddress.orEmpty()
                            if (WakeOnLan.parseMacBytes(mac) == null) {
                                wolStatusText = kzNow(
                                    "MAC Address belum valid (contoh: AA:BB:CC:DD:EE:FF)",
                                    "Invalid MAC address (e.g. AA:BB:CC:DD:EE:FF)",
                                )
                            } else {
                                wolStatusText = kzNow("Mengirim Magic Packet...", "Sending Magic Packet...")
                                scope.launch {
                                    val endpointHost = parseRdpEndpoint(host)?.host
                                    val targets = buildList {
                                        add(options.wolBroadcast.ifBlank { "255.255.255.255" })
                                        if (!endpointHost.isNullOrBlank()) add(endpointHost)
                                    }.distinct()
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            targets.forEach { target ->
                                                WakeOnLan.sendMagicPacket(mac, target, options.wolPort)
                                            }
                                        }
                                    }
                                    wolStatusText = if (result.isSuccess) {
                                        kzNow(
                                            "Magic Packet terkirim ke ${targets.joinToString()} (UDP ${options.wolPort})",
                                            "Magic Packet sent to ${targets.joinToString()} (UDP ${options.wolPort})",
                                        )
                                    } else {
                                        kzNow(
                                            "Gagal mengirim Magic Packet: ${result.exceptionOrNull()?.message ?: "error"}",
                                            "Failed to send Magic Packet: ${result.exceptionOrNull()?.message ?: "error"}",
                                        )
                                    }
                                }
                            }
                        },
                        primary = false,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    wolStatusText?.let { msg ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    kz("SSH Jump-Host / Port Forwarding Helper", "SSH Jump-Host / Port Forwarding Helper"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    kz(
                        "Jika PC berada di belakang firewall/NAT dan diakses via tunnel SSH lokal (mis. Termux), simpan detail jump-host di sini untuk membuat perintah port-forward otomatis.",
                        "If the PC sits behind a firewall/NAT and is reached via a local SSH tunnel (e.g. Termux), save jump-host details here to generate the port-forwarding command.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                KzField(
                    value = options.sshHost.orEmpty(),
                    onValueChange = { options = options.copy(sshHost = it.trim().ifEmpty { null }) },
                    label = kz("SSH Jump Host", "SSH Jump Host"),
                    hint = "ssh.kantor.com",
                )
                if (!options.sshHost.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KzField(
                            value = options.sshUser.orEmpty(),
                            onValueChange = { options = options.copy(sshUser = it.trim().ifEmpty { null }) },
                            label = kz("SSH User", "SSH User"),
                            hint = "root",
                            modifier = Modifier.weight(1.3f),
                        )
                        KzField(
                            value = options.sshPort.toString(),
                            onValueChange = {
                                val p = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 22
                                options = options.copy(sshPort = p.coerceIn(1, 65535))
                            },
                            label = kz("SSH Port", "SSH Port"),
                            hint = "22",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                    val parsedEndpoint = parseRdpEndpoint(host)
                    val sshCmd = WakeOnLan.buildSshTunnelCommand(
                        targetHost = parsedEndpoint?.host ?: "127.0.0.1",
                        targetPort = parsedEndpoint?.port ?: 3389,
                        options = options,
                    )
                    if (!sshCmd.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            sshCmd,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        KzPillButton(
                            text = kz("Salin Perintah SSH", "Copy SSH Command"),
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("SSH Tunnel", sshCmd))
                                wolStatusText = kzNow("Perintah SSH disalin ke clipboard.", "SSH command copied to clipboard.")
                            },
                            primary = false,
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            KzSectionLabel(kz("Gateway", "Gateway"))
            KzCard {
                KzToggleRow(
                    title = kz("Lewat RDP Gateway", "Use RDP Gateway"),
                    subtitle = kz("RD Gateway untuk jaringan kantor / tanpa port langsung", "RD Gateway for office networks / no direct port"),
                    checked = gatewayOn,
                    onCheckedChange = { gatewayOn = it },
                    leading = KzIcons.Lock,
                )
                if (gatewayOn) {
                    Spacer(Modifier.height(6.dp))
                    KzField(
                        value = gwHost,
                        onValueChange = { gwHost = it.trim() },
                        label = kz("Gateway host", "Gateway host"),
                        hint = "gw.kantor.com",
                    )
                    Spacer(Modifier.height(12.dp))
                    KzField(
                        value = gwPort,
                        onValueChange = { gwPort = it.filter(Char::isDigit).take(5) },
                        label = kz("Gateway port", "Gateway port"),
                        hint = "443",
                        keyboardType = KeyboardType.Number,
                    )
                    Spacer(Modifier.height(12.dp))
                    KzField(
                        value = gwUser,
                        onValueChange = { gwUser = it },
                        label = kz("Gateway username (opsional)", "Gateway username (optional)"),
                    )
                    Spacer(Modifier.height(12.dp))
                    KzField(
                        value = gwPass,
                        onValueChange = { gwPass = it },
                        label = kz("Gateway password (opsional)", "Gateway password (optional)"),
                        isPassword = true,
                        keyboardType = KeyboardType.Password,
                    )
                    Spacer(Modifier.height(12.dp))
                    KzField(
                        value = gwDomain,
                        onValueChange = { gwDomain = it },
                        label = kz("Gateway domain (opsional)", "Gateway domain (optional)"),
                    )
                }
            }
            }

            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KzPillButton(
                text = kz("Simpan", "Save"),
                onClick = { submit(false) },
                primary = false,
                modifier = Modifier.weight(1f),
            )
            KzPillButton(
                text = kz("Simpan & connect", "Save & connect"),
                onClick = { submit(true) },
                modifier = Modifier.weight(1.4f),
            )
        }
    }

    if (confirmDiscard) {
        KzDialog(
            title = kz("Buang perubahan?", "Discard changes?"),
            body = kz(
                "Perubahan pada pengaturan perangkat ini belum disimpan. Kembali tanpa menyimpan?",
                "Changes to this device configuration have not been saved. Leave without saving?",
            ),
            confirmLabel = kz("Buang", "Discard"),
            onConfirm = {
                confirmDiscard = false
                onCancel()
            },
            dismissLabel = kz("Lanjut ubah", "Keep editing"),
            onDismiss = { confirmDiscard = false },
        )
    }

    validationPopup?.let { msg ->
        KzDialog(
            title = kz("Data belum valid", "Validation error"),
            body = msg,
            confirmLabel = kz("Mengerti", "Got it"),
            onConfirm = { validationPopup = null },
            onDismiss = { validationPopup = null },
        )
    }

    if (lanScanModalOpen) {
        KzOverlay(
            title = if (isPcQuickMode) {
                kz("Pindai PC di Jaringan Wi-Fi (LAN)", "Scan PCs on Wi-Fi Network (LAN)")
            } else {
                kz("Pindai Host RDP di Jaringan Lokal", "Scan RDP Hosts on Local Network")
            },
            onDismiss = { lanScanModalOpen = false },
        ) {
            Text(
                text = if (!lanSelfIp.isNullOrBlank()) {
                    kz("IP HP Anda: {0} · Memindai port 3389 di subnet lokal", "Your Phone IP: {0} · Scanning port 3389 on local subnet", lanSelfIp!!)
                } else {
                    kz("Pastikan HP dan PC berada di jaringan Wi-Fi / Hotspot yang sama.", "Make sure your phone and PC are on the same Wi-Fi / Hotspot network.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lanScanning) {
                Text(
                    text = kz("Memindai 254 alamat lokal secara paralel...", "Scanning 254 local addresses in parallel..."),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (lanFoundHosts.isEmpty()) {
                Text(
                    text = kz(
                        "Belum ditemukan PC aktif di subnet ini. Pastikan PC menyala dan KzDeskHost / Remote Desktop (Port 3389) sudah aktif.",
                        "No active PC found on this subnet yet. Ensure the PC is powered on and KzDeskHost / Remote Desktop (Port 3389) is enabled.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lanFoundHosts.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .clickable {
                                    host = if (isPcQuickMode) item.pcId else "${item.ip}:${item.port}"
                                    if (label.isBlank()) {
                                        label = if (isPcQuickMode) "PC ${item.pcId.takeLast(4)}" else "RDP ${item.ip}"
                                    }
                                    lanScanModalOpen = false
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (isPcQuickMode) "ID PC: ${item.pcId}" else "${item.ip}:${item.port}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = if (isPcQuickMode) {
                                        kz("Endpoint: {0}:{1} · Ketuk untuk pakai ID ini", "Endpoint: {0}:{1} · Tap to use this ID", item.ip, item.port)
                                    } else {
                                        kz("ID PC: {0} · Ketuk untuk pakai IP ini", "PC ID: {0} · Tap to use this IP", item.pcId)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "${item.rttMs} ms",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    text = kz("Pindai Ulang", "Rescan"),
                    onClick = { triggerLanScan() },
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    text = kz("Tutup", "Close"),
                    onClick = { lanScanModalOpen = false },
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    }
}

/**
 * Status akses storage untuk redirect drive.
 *
 * Android 11+ hanya mengizinkan app membaca folder miliknya sendiri; kalau
 * user mau seluruh isi /storage/emulated/0, izin "semua file" harus diminta
 * lewat halaman Setelan (tidak bisa lewat dialog runtime biasa).
 */
@Composable
private fun StorageAccessRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LibFreeRDP.hasAllFilesAccess()) }
    val path = remember(granted) { LibFreeRDP.appDrivePath(context) }
    // Cek ulang izin saat layar ini kembali ke depan. Dulu `granted` hanya
    // dibaca sekali tepat setelah startActivity — padahal activity asal
    // tidak pernah pause-hilang di momen itu, jadi status selalu "terbatas"
    // walaupun user baru saja memberi izin di Setelan.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = LibFreeRDP.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Text(
            if (granted) {
                kz("Akses penuh: seluruh isi HP terbaca remote", "Full access: the remote reads all phone storage")
            } else {
                kz("Akses terbatas: hanya folder app yang terbaca remote", "Limited access: only the app folder is visible to the remote")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Path HP: $path",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            kz(
                "Cara transfer file di PC: Buka File Explorer Windows -> This PC -> klik drive 'KzDesk on Android' (atau ketik \\\\tsclient\\KzDesk di address bar) untuk salin/tempel file dua arah.",
                "How to transfer files on PC: Open Windows File Explorer -> This PC -> open 'KzDesk on Android' drive (or type \\\\tsclient\\KzDesk in the address bar) for two-way file copy/paste.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!granted) {
            Spacer(Modifier.height(8.dp))
            KzPillButton(
                text = kz("Beri akses semua file", "Grant all-files access"),
                onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    } else {
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    }
                    runCatching { context.startActivity(intent) }
                    granted = LibFreeRDP.hasAllFilesAccess()
                },
                primary = false,
                compact = true,
            )
        }
    }
}

/** Baris slider berlabel untuk pengaturan DSP mic. */
@Composable
private fun KzSliderRow(
    label: String,
    value: Float,
    suffix: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${value.toInt()}$suffix",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KzSlider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}
