package com.kazamatech.kzdesk.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazamatech.kzdesk.core.RdpOptions
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzNoticeState
import kotlinx.coroutines.launch

/**
 * Seksi "Transfer file" di tab Sesi: kirim dari HP ke PC dan ambil dari PC.
 *
 * Memakai kanal RDPDR yang sudah ada, jadi tidak ada protokol atau server baru.
 * Yang ditambahkan hanya yang sebelumnya tidak ada: cara memilih file dari HP
 * tanpa keluar sesi, daftar yang jelas tentang apa yang sudah sampai, dan
 * tombol untuk menyimpan kiriman PC ke folder Download HP.
 */
@Composable
internal fun TransferFileSection(deviceId: String, notice: KzNoticeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by SessionFileTransfer.jobs.collectAsState()
    var incoming by remember { mutableStateOf(emptyList<SharedFileInfo>()) }
    var root by remember { mutableStateOf<TransferRoot?>(null) }

    // Drive redirection adalah opsi per-perangkat dan tidak aktif di PC Connect,
    // jadi statusnya dibaca ulang tiap seksi dibuka, bukan ditebak.
    val options = remember(deviceId) { RdpOptions.of(context, deviceId) }
    val driveAvailable = options.localDrive && !options.pcConnectMode

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // Semua langkah dibungkus: provider nakal atau izin yang dicabut di
            // tengah jalan tidak boleh mematikan aplikasi (keluhan "apk terhenti").
            val result = runCatching {
                val sent = SessionFileTransfer.sendToPc(context, uris)
                val listed = runCatching { SessionFileTransfer.listIncoming(context) }
                    .getOrDefault(incoming)
                sent to listed
            }
            result.onSuccess { (sent, listed) ->
                incoming = listed
                notice.show(
                    if (sent > 0) {
                        kzNow(
                            "{0} file siap di folder bersama. Di PC buka {1}.",
                            "{0} file(s) are in the shared folder. On the PC open {1}.",
                            sent,
                            root?.remoteHint ?: "\\\\tsclient\\KzDesk",
                        )
                    } else {
                        kzNow(
                            "Tidak ada file yang berhasil disalin. Lihat catatan di bawah.",
                            "No file could be copied. See the log below.",
                        )
                    },
                )
            }
            result.onFailure { t ->
                notice.show(
                    kzNow(
                        "Transfer terhenti: {0}",
                        "Transfer stopped: {0}",
                        t.message ?: t.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    val refreshIncoming = {
        runCatching {
            root = SessionFileTransfer.transferRoot(context)
            incoming = SessionFileTransfer.listIncoming(context)
        }.onFailure { t ->
            notice.show(
                kzNow(
                    "Tidak bisa membaca folder bersama: {0}",
                    "Could not read the shared folder: {0}",
                    t.message ?: t.javaClass.simpleName,
                ),
            )
        }
        Unit
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        PanelHint(
            kz(
                "Kirim file dari HP ke PC dan sebaliknya lewat folder bersama. Di Windows foldernya muncul sebagai drive KzDesk (\\\\tsclient\\KzDesk) — salin/tempel biasa di File Explorer juga jalan.",
                "Move files between phone and PC through the shared folder. On Windows it appears as the KzDesk drive (\\\\tsclient\\KzDesk) — plain copy/paste in File Explorer works too.",
            ),
        )

        if (!driveAvailable) {
            Text(
                if (options.pcConnectMode) {
                    kz(
                        "Drive lokal dimatikan di mode PC Connect, jadi transfer file tidak tersedia di sesi ini. Sambungkan dengan mode RDP biasa untuk memakainya.",
                        "Local drive is off in PC Connect mode, so file transfer is unavailable in this session. Connect with plain RDP mode to use it.",
                    )
                } else {
                    kz(
                        "Drive lokal belum diaktifkan untuk perangkat ini. Aktifkan di pengaturan perangkat (opsi Drive lokal), lalu sambungkan ulang.",
                        "Local drive is not enabled for this device. Turn on the Local drive option in the device settings, then reconnect.",
                    )
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KzPillButton(
                kz("Kirim file ke PC", "Send files to PC"),
                { picker.launch(arrayOf("*/*")) },
                icon = KzIcons.Upload,
                primary = false,
                compact = true,
                enabled = driveAvailable,
                modifier = Modifier.weight(1f),
            )
            KzPillButton(
                kz("Segarkan", "Refresh"),
                refreshIncoming,
                icon = KzIcons.Refresh,
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }

        root?.let { active ->
            Text(
                kz("Folder HP: {0}", "Phone folder: {0}", active.dir.absolutePath),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kz("Di PC: {0}", "On the PC: {0}", active.remoteHint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }

        if (jobs.isNotEmpty()) {
            Text(
                kz("Catatan transfer", "Transfer log").uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                jobs.take(12).forEach { job -> JobRow(job) }
            }
            KzPillButton(
                kz("Bersihkan catatan selesai", "Clear finished entries"),
                { SessionFileTransfer.clearFinished() },
                primary = false,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            kz("Dari PC", "From the PC").uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (incoming.isEmpty()) {
            Text(
                kz(
                    "Belum ada file dari PC. Di Windows, salin file ke drive KzDesk, lalu ketuk Segarkan.",
                    "Nothing from the PC yet. On Windows, copy a file into the KzDesk drive, then tap Refresh.",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                incoming.forEach { file ->
                    IncomingRow(
                        file = file,
                        onSave = {
                            scope.launch {
                                when (val result = SessionFileTransfer.saveToPhone(context, file.name)) {
                                    is ReceiveResult.Saved -> notice.show(
                                        kzNow(
                                            "Disimpan ke {0}",
                                            "Saved to {0}",
                                            result.label,
                                        ),
                                    )

                                    is ReceiveResult.Failed -> notice.show(
                                        kzNow(
                                            "Gagal menyimpan {0}: {1}",
                                            "Could not save {0}: {1}",
                                            file.name,
                                            result.message,
                                        ),
                                    )
                                }
                            }
                        },
                        onShare = {
                            val uri = SessionFileTransfer.sharedUri(context, file.name)
                            if (uri == null) {
                                notice.show(
                                    kzNow(
                                        "File tidak bisa dibuka untuk dibagikan.",
                                        "The file could not be opened for sharing.",
                                    ),
                                )
                                return@IncomingRow
                            }
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = context.contentResolver.getType(uri) ?: "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        send,
                                        kzNow("Bagikan file", "Share file"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                notice.show(
                                    kzNow(
                                        "Tidak ada aplikasi yang bisa menerima file ini.",
                                        "No app can receive this file.",
                                    ),
                                )
                            }
                        },
                        onDelete = {
                            val removed = SessionFileTransfer.remove(context, file.name)
                            incoming = SessionFileTransfer.listIncoming(context)
                            notice.show(
                                if (removed) {
                                    kzNow("{0} dihapus dari folder bersama.", "{0} removed from the shared folder.", file.name)
                                } else {
                                    kzNow("Gagal menghapus {0}.", "Could not delete {0}.", file.name)
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: TransferJob) {
    val percent = FileTransferPlan.percentOf(job.doneBytes, job.bytes)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp, horizontal = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (job.phase) {
                    TransferPhase.RUNNING ->
                        if (job.direction == TransferDirection.TO_PC) KzIcons.Upload else KzIcons.Download
                    TransferPhase.DONE -> KzIcons.Check
                    TransferPhase.FAILED -> KzIcons.Close
                },
                contentDescription = null,
                tint = when (job.phase) {
                    TransferPhase.DONE -> MaterialTheme.colorScheme.primary
                    TransferPhase.FAILED -> MaterialTheme.colorScheme.error
                    TransferPhase.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                job.name,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when (job.phase) {
                    TransferPhase.RUNNING -> "$percent%"
                    TransferPhase.DONE -> FileTransferPlan.formatBytes(job.bytes)
                    TransferPhase.FAILED -> kz("gagal", "failed")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
        if (job.phase == TransferPhase.RUNNING && job.bytes > 0L) {
            Spacer(Modifier.height(3.dp))
            ProgressBar(fraction = percent / 100f)
        }
        job.message?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(track),
    ) {
        // weight 0f valid: bar kosong saat progres masih nol.
        Box(
            Modifier
                .fillMaxHeight()
                .weight(fraction.coerceIn(0f, 1f))
                .background(fill),
        )
    }
}

@Composable
private fun IncomingRow(
    file: SharedFileInfo,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .padding(vertical = 5.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                KzIcons.File,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                file.name,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                FileTransferPlan.formatBytes(file.bytes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KzPillButton(
                kz("Simpan ke HP", "Save to phone"),
                onSave,
                icon = KzIcons.Download,
                primary = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            KzPillButton(
                kz("Bagikan", "Share"),
                onShare,
                icon = KzIcons.ExternalLink,
                primary = false,
                compact = true,
            )
            KzPillButton(
                kz("Hapus", "Delete"),
                onDelete,
                icon = KzIcons.Trash,
                primary = false,
                compact = true,
            )
        }
    }
}
