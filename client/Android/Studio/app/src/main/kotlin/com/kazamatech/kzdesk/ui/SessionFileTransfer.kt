package com.kazamatech.kzdesk.ui

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.kazamatech.kzdesk.core.ConnectionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Panel kirim/terima file antara HP dan PC.
 *
 * Jalurnya kanal RDPDR yang sudah ada: satu folder HP di-redirect jadi drive
 * `KzDesk` di Windows (lihat `RdpUri`), jadi "kirim" = salin ke folder itu dan
 * "terima" = ambil file yang PC taruh di sana. Tidak ada protokol baru, tidak
 * ada server tambahan — yang sebelumnya hilang hanya UI-nya: pengguna harus
 * membuka File Explorer di PC dan menebak letak foldernya.
 *
 * Nama file dibersihkan lewat [FileTransferPlan] karena kiriman dari aplikasi
 * lain sering memakai karakter yang ditolak Windows, dan file yang sudah ada
 * tidak pernah ditimpa.
 */
internal object SessionFileTransfer {

    private const val BUFFER_SIZE = 64 * 1024
    /** Jarak minimum antar pembaruan progres supaya UI tidak kebanjiran. */
    private const val PROGRESS_INTERVAL_MS = 120L

    private val mutableJobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = mutableJobs.asStateFlow()

    /** Nama drive di sisi Windows, harus sama dengan `RdpUri`. */
    private const val REMOTE_DRIVE_NAME = "KzDesk"

    /**
     * Folder HP yang tampak di PC.
     *
     * `LibFreeRDP.appDrivePath` mengembalikan SELURUH penyimpanan kalau app
     * punya izin "semua file", jadi di situ dipakai subfolder `KzDesk` supaya
     * daftar "dari PC" tidak berisi seluruh isi HP. Kalau folder utama tidak
     * bisa ditulis, turun ke folder app-scoped yang juga ikut di-redirect.
     */
    fun transferRoot(context: Context): TransferRoot? {
        val candidates = ArrayList<File>(2)
        runCatching {
            val raw = File(LibFreeRDP.appDrivePath(context))
            candidates.add(if (LibFreeRDP.hasAllFilesAccess()) File(raw, REMOTE_DRIVE_NAME) else raw)
        }
        context.getExternalFilesDir(REMOTE_DRIVE_NAME)?.let { candidates.add(it.parentFile ?: it) }
        candidates.forEach { dir ->
            if (!dir.exists()) runCatching { dir.mkdirs() }
            if (dir.isDirectory && dir.canWrite()) {
                val subfolder = dir.name == REMOTE_DRIVE_NAME &&
                    dir.parentFile?.absolutePath != context.getExternalFilesDir(null)?.absolutePath
                return TransferRoot(
                    dir = dir,
                    redirected = true,
                    remoteHint = if (subfolder) {
                        "\\\\tsclient\\$REMOTE_DRIVE_NAME\\$REMOTE_DRIVE_NAME"
                    } else {
                        "\\\\tsclient\\$REMOTE_DRIVE_NAME"
                    },
                )
            }
        }
        return null
    }

    /**
     * Kirim file HP ke PC. Mengembalikan jumlah file yang benar-benar selesai.
     *
     * [uris] berasal dari pemilih dokumen Android; namanya dibaca dari
     * `OpenableColumns.DISPLAY_NAME` karena URI `content://` tidak membawa nama
     * file di jalurnya.
     */
    suspend fun sendToPc(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val root = transferRoot(context)
        if (root == null) {
            fail(kzn("Folder bersama tidak bisa ditulis.", "The shared folder is not writable."))
            return@withContext 0
        }
        var done = 0
        uris.forEach { uri ->
            val info = queryFileInfo(context, uri)
            val wanted = FileTransferPlan.sanitizeFileName(info.first, System.currentTimeMillis())
            val existing = root.dir.listFiles()?.map { it.name }.orEmpty()
            val name = FileTransferPlan.uniqueName(existing, wanted)
            val job = addJob(name, info.second, TransferDirection.TO_PC)
            val ok = runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("no input stream")
                input.use { source ->
                    File(root.dir, name).outputStream().use { target ->
                        copyWithProgress(job.id, source, target)
                    }
                }
            }
            ok.onSuccess {
                update(job.id) { it.copy(phase = TransferPhase.DONE, doneBytes = it.bytes) }
                done++
            }.onFailure { error ->
                ConnectionLog.addThrowable("SES: transfer ke PC gagal ($name)", error)
                runCatching { File(root.dir, name).delete() }
                update(job.id) {
                    it.copy(
                        phase = TransferPhase.FAILED,
                        message = kzn(
                            "Gagal: ${error.message ?: error.javaClass.simpleName}",
                            "Failed: ${error.message ?: error.javaClass.simpleName}",
                        ),
                    )
                }
            }
        }
        done
    }

    /** File yang sudah ditaruh PC di folder bersama. */
    fun listIncoming(context: Context): List<SharedFileInfo> {
        val root = transferRoot(context) ?: return emptyList()
        return root.dir.listFiles()
            ?.filter { it.isFile }
            ?.map { SharedFileInfo(it.name, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.modifiedAt }
            .orEmpty()
    }

    /**
     * Simpan file kiriman PC ke tempat yang terlihat di HP: `Download/KzDesk`
     * lewat MediaStore (Android 10+). Di Android 7-9 manifest tidak meminta
     * izin tulis penyimpanan publik, jadi file dibagikan langsung dari folder
     * bersama dan jalurnya dilaporkan.
     */
    suspend fun saveToPhone(context: Context, fileName: String): ReceiveResult =
        withContext(Dispatchers.IO) {
            val root = transferRoot(context)
                ?: return@withContext ReceiveResult.Failed(
                    kzn("Folder bersama tidak ditemukan.", "The shared folder is missing."),
                )
            val source = File(root.dir, fileName)
            if (!source.isFile) {
                return@withContext ReceiveResult.Failed(
                    kzn("File sudah tidak ada di folder bersama.", "The file is no longer in the shared folder."),
                )
            }
            val job = addJob(fileName, source.length(), TransferDirection.FROM_PC)
            val mime = mimeFor(fileName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$REMOTE_DRIVE_NAME",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (target == null) {
                    update(job.id) {
                        it.copy(phase = TransferPhase.FAILED, message = kzn(
                            "MediaStore menolak membuat file.", "MediaStore refused to create the file.",
                        ))
                    }
                    return@withContext ReceiveResult.Failed(
                        kzn("Tidak bisa menulis ke Download.", "Could not write to Downloads."),
                    )
                }
                var completed = false
                try {
                    val written = resolver.openOutputStream(target)?.use { out ->
                        source.inputStream().use { input ->
                            copyWithProgress(job.id, input, out)
                        }
                        true
                    } ?: false
                    if (!written) {
                        return@withContext ReceiveResult.Failed(
                            kzn("Tidak bisa menulis file.", "Could not write the file."),
                        )
                    }
                    val ready = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    check(resolver.update(target, ready, null, null) > 0) {
                        "Unable to publish the downloaded file in MediaStore"
                    }
                    completed = true
                    update(job.id) { it.copy(phase = TransferPhase.DONE, doneBytes = it.bytes) }
                    ReceiveResult.Saved(
                        target,
                        "${Environment.DIRECTORY_DOWNLOADS}/$REMOTE_DRIVE_NAME/$fileName",
                    )
                } catch (t: Throwable) {
                    ConnectionLog.addThrowable("SES: terima dari PC gagal ($fileName)", t)
                    update(job.id) {
                        it.copy(
                            phase = TransferPhase.FAILED,
                            message = kzn("Gagal: ${t.message}", "Failed: ${t.message}"),
                        )
                    }
                    ReceiveResult.Failed(t.message ?: t.javaClass.simpleName)
                } finally {
                    // Baris MediaStore yang gagal ditulis harus dihapus supaya
                    // Downloads tidak berisi entri kosong.
                    if (!completed) runCatching { resolver.delete(target, null, null) }
                }
            } else {
                // Android 7-9: bagikan langsung dari folder bersama. Folder itu
                // dideklarasikan di file_paths.xml, jadi FileProvider melayaninya.
                val uri = runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", source)
                }.getOrNull()
                if (uri == null) {
                    update(job.id) {
                        it.copy(phase = TransferPhase.FAILED, message = kzn(
                            "FileProvider menolak file ini.", "FileProvider rejected this file.",
                        ))
                    }
                    return@withContext ReceiveResult.Failed(
                        kzn("Tidak bisa membuka file.", "Could not open the file."),
                    )
                }
                update(job.id) { it.copy(phase = TransferPhase.DONE, doneBytes = it.bytes) }
                ReceiveResult.Saved(uri, source.absolutePath)
            }
        }

    /** URI `content://` untuk membuka/membagikan file di folder bersama. */
    fun sharedUri(context: Context, fileName: String): Uri? {
        val root = transferRoot(context) ?: return null
        val file = File(root.dir, fileName)
        if (!file.isFile) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()
    }

    /** Hapus file dari folder bersama (mis. sesudah berhasil disimpan ke HP). */
    fun remove(context: Context, fileName: String): Boolean {
        val root = transferRoot(context) ?: return false
        val file = File(root.dir, fileName)
        if (!file.isFile) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun clearFinished() {
        mutableJobs.value = mutableJobs.value.filter { it.phase == TransferPhase.RUNNING }
    }

    // ------------------------------------------------------------------ internal

    private fun copyWithProgress(
        jobId: Long,
        source: InputStream,
        target: OutputStream,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var lastPublish = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            target.write(buffer, 0, read)
            copied += read
            val now = System.currentTimeMillis()
            if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
                lastPublish = now
                val snapshot = copied
                update(jobId) { it.copy(doneBytes = snapshot) }
            }
        }
        target.flush()
    }

    /** `(nama, ukuran)` dari penyedia konten; keduanya bisa null/0. */
    private fun queryFileInfo(context: Context, uri: Uri): Pair<String?, Long> {
        var cursor: Cursor? = null
        return runCatching {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                }
                name to size
            } else {
                uri.lastPathSegment?.substringAfterLast('/') to 0L
            }
        }.getOrDefault(uri.lastPathSegment?.substringAfterLast('/') to 0L)
            .also { runCatching { cursor?.close() } }
    }

    private fun mimeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == fileName) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private var nextJobId = 1L

    private fun addJob(name: String, bytes: Long, direction: TransferDirection): TransferJob {
        val job = TransferJob(
            id = nextJobId++,
            name = name,
            bytes = bytes,
            direction = direction,
            phase = TransferPhase.RUNNING,
        )
        mutableJobs.value = listOf(job) + mutableJobs.value
        return job
    }

    private fun update(jobId: Long, transform: (TransferJob) -> TransferJob) {
        mutableJobs.value = mutableJobs.value.map { if (it.id == jobId) transform(it) else it }
    }

    private fun fail(message: String) {
        val job = TransferJob(
            id = nextJobId++,
            name = "-",
            bytes = 0L,
            direction = TransferDirection.TO_PC,
            phase = TransferPhase.FAILED,
            message = message,
        )
        mutableJobs.value = listOf(job) + mutableJobs.value
    }

    private fun kzn(id: String, en: String): String = kzNow(id, en)
}

internal enum class TransferDirection { TO_PC, FROM_PC }

internal enum class TransferPhase { RUNNING, DONE, FAILED }

internal data class TransferJob(
    val id: Long,
    val name: String,
    val bytes: Long,
    val direction: TransferDirection,
    val phase: TransferPhase,
    val message: String? = null,
    val doneBytes: Long = 0L,
)

internal data class SharedFileInfo(
    val name: String,
    val bytes: Long,
    val modifiedAt: Long,
)

internal data class TransferRoot(
    val dir: File,
    val redirected: Boolean,
    val remoteHint: String,
)

internal sealed class ReceiveResult {
    data class Saved(val uri: Uri, val label: String) : ReceiveResult()
    data class Failed(val message: String) : ReceiveResult()
}
