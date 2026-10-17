package com.kazamatech.kzdesk.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.core.ConnectionLog
import com.kazamatech.kzdesk.ui.components.KzCard
import com.kazamatech.kzdesk.ui.components.KzIconPill
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzSectionLabel
import com.kazamatech.kzdesk.ui.components.KzTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Satu layar status: versi aplikasi, status server rilis, izin mikrofon, dan
 * diagnostik yang bisa disalin. Tujuannya supaya pertanyaan "yang rusak di mana"
 * bisa dijawab tanpa buka log lewat adb.
 */
@Composable
fun StatusScreen(
    onBack: () -> Unit,
    onShowLog: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "-"
    }
    val versionCode = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
        }.getOrNull() ?: "-"
    }
    val abi = remember { Build.SUPPORTED_ABIS.firstOrNull() ?: "-" }
    var micGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    var portalSummary by remember { mutableStateOf<String?>(null) }
    var portalLoading by remember { mutableStateOf(false) }
    var portalError by remember { mutableStateOf<String?>(null) }

    suspend fun fetchPortal() {
        portalLoading = true
        portalError = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("$PORTAL_STATUS_URL?cb=${System.currentTimeMillis()}").openConnection()
                    as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "KzDesk-Remote-Android")
                }
                try {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    listOf(
                        "qaBuild" to body.stringField("qaBuild"),
                        "qaVersionCode" to body.stringField("qaVersionCode"),
                        "kzdeskHostBuild" to body.stringField("kzdeskHostBuild"),
                        "status" to body.stringField("status"),
                        "launchWib" to body.stringField("launchWib"),
                    ).filter { it.second != null }
                        .joinToString("\n") { "${it.first}: ${it.second}" }
                } finally {
                    conn.disconnect()
                }
            }
        }
        portalLoading = false
        result.onSuccess { portalSummary = it.ifBlank { "(kosong)" } }
            .onFailure { portalError = it.message ?: "gagal menghubungi portal" }
    }

    LaunchedEffect(Unit) { fetchPortal() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        KzTopBar(
            title = kz("Status", "Status"),
            onBack = onBack,
            actions = {
                KzIconPill(KzIcons.Info, onShowLog, flat = true, contentDescription = kz("Log sesi", "Session log"))
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KzSectionLabel(kz("Aplikasi ini", "This app"))
            KzCard {
                KeyValue("Versi", "$versionName ($versionCode)")
                KeyValue("ABI", abi)
                KeyValue("Paket", context.packageName)
                KeyValue(
                    kz("Mikrofon", "Microphone"),
                    if (micGranted) {
                        kz("izin diberikan", "permission granted")
                    } else {
                        kz("izin belum diberikan", "permission not granted")
                    },
                )
            }

            KzSectionLabel(kz("Portal rilis", "Release portal"))
            KzCard {
                if (portalLoading) {
                    Text(kz("Memuat…", "Loading…"), style = MaterialTheme.typography.bodyMedium)
                } else if (portalError != null) {
                    Text(
                        text = kz(
                            "Tidak bisa menghubungi portal: $portalError",
                            "Cannot reach the portal: $portalError",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = portalSummary ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KzPillButton(
                        text = kz("Muat ulang", "Reload"),
                        onClick = { scope.launch { fetchPortal() } },
                        icon = KzIcons.Rotate,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    KzPillButton(
                        text = kz("Salin diagnostik", "Copy diagnostics"),
                        onClick = {
                            val tail = ConnectionLog.last(25).joinToString("\n")
                            copyToClipboard(
                                context,
                                "KzDesk Remote $versionName ($versionCode) · $abi\n" +
                                    "portal: ${portalSummary ?: portalError ?: "-"}\n" +
                                    "mic: ${if (micGranted) "granted" else "denied"}\n" +
                                    tail,
                            )
                        },
                        primary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            KzSectionLabel(kz("Kalau audio/mic bermasalah", "If audio/mic misbehaves"))
            KzCard {
                Text(
                    text = kz(
                        "1. Mode audio \"Putar di perangkat ini\" = suara + mic lewat kanal RDP (tanpa host agent).\n" +
                            "2. Mode \"Putar di komputer remote\" = lewat KzDeskRemoteHost.exe di PC (UDP 4433).\n" +
                            "3. Mic tidak jalan: cek izin mikrofon di baris atas, lalu di PC pastikan " +
                            "fDisableAudioCapture=0 pada RDP-Tcp.",
                        "1. Audio mode \"Play on this device\" = sound + mic over the RDP channel (no host agent).\n" +
                            "2. \"Play on the remote computer\" = through KzDeskRemoteHost.exe on the PC (UDP 4433).\n" +
                            "3. Mic not working: check the permission row above, then make sure the PC has " +
                            "fDisableAudioCapture=0 on RDP-Tcp.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "KazamaDev",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ambil field JSON sederhana tanpa parser: cukup untuk ringkasan status. */
private fun String.stringField(key: String): String? =
    Regex("\"$key\"\\s*:\\s*\"?([^,\"}\\n]+)\"?").find(this)?.groupValues?.get(1)?.trim()

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("KzDesk Remote diagnostics", text))
}

private const val PORTAL_STATUS_URL = com.kazamatech.kzdesk.KzConfig.API_BASE + "/api/status"
