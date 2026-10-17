package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.security.CrashReporter
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.KzConfig
import com.kazamatech.kzdesk.ui.components.KzPillButton
import androidx.core.content.ContextCompat
import com.kazamatech.kzdesk.push.PushManager
import com.kazamatech.kzdesk.update.UpdateManager
import com.kazamatech.kzdesk.update.UpdatePhase
import com.kazamatech.kzdesk.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Isi dialog "Pembaruan": status, catatan rilis, unduh/pasang, dan saklar auto-update.
 * Seluruh logika ada di [UpdateManager]; di sini hanya menampilkan [UpdateState].
 */
@Composable
fun UpdatePanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = UpdateState.info
    var auto by remember { mutableStateOf(UpdateManager.isAutoEnabled(context)) }
    var push by remember { mutableStateOf(PushManager.isEnabled(context)) }
    var crash by remember { mutableStateOf(CrashReporter.isOptedIn(context)) }
    val pushAvailable = remember { PushManager.isAvailable(context) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { push = PushManager.setEnabled(context, true) } else push = false
    }
    val installed = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Text(
        text = "${KzConfig.APP_NAME} v$installed  ·  ${KzConfig.DEVELOPER}",
        style = MaterialTheme.typography.titleSmall,
    )

    val body = when (UpdateState.phase) {
        UpdatePhase.CHECKING -> kz("Memeriksa pembaruan...", "Checking for updates...")
        UpdatePhase.AVAILABLE, UpdatePhase.READY -> kz(
            "Versi v{0} tersedia.", "Version v{0} is available.", info?.version ?: "",
        )
        UpdatePhase.DOWNLOADING -> kz("Mengunduh v{0}...", "Downloading v{0}...", info?.version ?: "")
        UpdatePhase.INSTALLING -> kz("Menunggu konfirmasi pemasangan...", "Waiting for install confirmation...")
        UpdatePhase.ERROR -> kz("Gagal: {0}", "Failed: {0}", UpdateState.error ?: "")
        UpdatePhase.UP_TO_DATE -> kz("Kamu sudah memakai versi terbaru.", "You are on the latest version.")
        UpdatePhase.IDLE -> kz("Ketuk \"Periksa sekarang\".", "Tap \"Check now\".")
    }
    Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (info != null && info.mandatory && info.available) {
        Text(
            text = kz("Pembaruan ini wajib.", "This update is required."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (info != null && info.available && info.notes.isNotBlank() &&
        UpdateState.phase in listOf(UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.DOWNLOADING)
    ) {
        Text(
            text = info.notes,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
    when (UpdateState.phase) {
        UpdatePhase.CHECKING, UpdatePhase.INSTALLING -> LinearProgressIndicator(Modifier.fillMaxWidth())
        UpdatePhase.DOWNLOADING -> LinearProgressIndicator(progress = { UpdateState.progress }, modifier = Modifier.fillMaxWidth())
        else -> Unit
    }

    fun installNow() {
        val file = UpdateState.readyFile ?: return
        if (!UpdateManager.canInstall(context)) {
            // Izin "pasang aplikasi tidak dikenal" untuk KzDesk; pengguna kembali lalu ketuk lagi.
            runCatching { context.startActivity(UpdateManager.installPermissionIntent(context)) }
        } else {
            UpdateManager.install(context, file)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (UpdateState.phase) {
            UpdatePhase.AVAILABLE -> KzPillButton(
                text = kz("Unduh & pasang", "Download & install"),
                onClick = { scope.launch { if (UpdateManager.download(context) != null) installNow() } },
                modifier = Modifier.fillMaxWidth(),
            )
            UpdatePhase.READY -> KzPillButton(
                text = kz("Pasang sekarang", "Install now"),
                onClick = { installNow() },
                modifier = Modifier.fillMaxWidth(),
            )
            UpdatePhase.ERROR, UpdatePhase.UP_TO_DATE, UpdatePhase.IDLE -> KzPillButton(
                text = kz("Periksa sekarang", "Check now"),
                onClick = { scope.launch { UpdateManager.check(context, force = true) } },
                primary = UpdateState.phase != UpdatePhase.UP_TO_DATE,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> Unit
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = kz("Unduh otomatis di Wi-Fi", "Auto-download on Wi-Fi"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = auto, onCheckedChange = { auto = it; UpdateManager.setAutoEnabled(context, it) })
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = kz("Notifikasi push", "Push notifications"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (pushAvailable) {
                    kz("Kabar rilis baru, pengumuman, dan PC online/offline.", "New releases, announcements, and PC online/offline.")
                } else {
                    kz("Belum tersedia di build ini (butuh google-services.json).", "Not available in this build (needs google-services.json).")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = push,
            enabled = pushAvailable,
            onCheckedChange = { want ->
                if (!want) {
                    push = false
                    scope.launch { PushManager.setEnabled(context, false) }
                } else if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    scope.launch { push = PushManager.setEnabled(context, true) }
                }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = kz("Kirim laporan crash", "Send crash reports"), style = MaterialTheme.typography.bodySmall)
            Text(
                text = kz(
                    "Opsional. Hanya stack trace yang disamarkan (tanpa IP/e-mail/ID). Tidak ada yang dikirim bila mati.",
                    "Optional. Only a redacted stack trace (no IP/e-mail/ID). Nothing is sent when off.",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = crash, onCheckedChange = { crash = it; CrashReporter.setOptedIn(context, it) })
    }

    Spacer(Modifier.height(2.dp))
    KzPillButton(
        text = kz("Tutup", "Close"),
        onClick = onClose,
        primary = false,
        compact = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
