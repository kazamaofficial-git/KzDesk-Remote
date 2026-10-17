package com.kazamatech.kzdesk.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent connection diagnostics. Writes to Android/media/<package>/log
 * and mirrors a private copy in filesDir so logs survive unavailable media storage.
 */
object ConnectionLog {
    private const val MAX_ENTRIES = 300
    private const val MAX_FILE_BYTES = 128 * 1024L
    private const val FILE_NAME = "kzdesk-boot.log"
    private val buf = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var files: List<File> = emptyList()
    @Volatile private var visiblePath: String = "Belum diinisialisasi"

    /** Call before GlobalApp.onCreate: that superclass initializes FreeRDP JNI. */
    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        val internal = File(app.filesDir, FILE_NAME)
        val external = app.externalMediaDirs
            .firstOrNull()
            ?.let { File(File(it, "log"), FILE_NAME) }
            ?.takeIf { target ->
                runCatching {
                    target.parentFile?.let { it.isDirectory || it.mkdirs() } == true
                    FileOutputStream(target, true).use { }
                    target.parentFile?.canWrite() == true
                }.getOrDefault(false)
            }
        files = if (external != null) listOf(external, internal) else listOf(internal)
        visiblePath = external?.absolutePath ?: internal.absolutePath
        add("APP: diagnostics initialized; external=${external != null}; file=$visiblePath")
    }

    /** Path log native FreeRDP (di-set app saat boot); null kalau tidak aktif. */
    @Volatile private var nativeLogPath: String? = null

    fun setNativeLogFile(path: String) {
        nativeLogPath = path
    }

    fun nativeLogPath(): String? = nativeLogPath

    /**
     * Ekor log native FreeRDP (kanal audio, clipboard, drive, DISP, dan
     * error jaringan). Dipakai layar diagnosa: user bisa kirim teks ini
     * tanpa harus pasang ADB.
     */
    fun nativeTail(lines: Int = 120): List<String> {
        val path = nativeLogPath ?: return emptyList()
        return runCatching {
            val file = File(path)
            if (!file.exists()) return emptyList()
            file.readLines().takeLast(lines)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        buf.add(line)
        while (buf.size > MAX_ENTRIES) buf.removeAt(0)
        files.forEach { appendBounded(it, line) }
    }

    /** Record an exception with stack trace; redact credentials before calling. */
    fun addThrowable(tag: String, error: Throwable) {
        val stack = error.stackTraceToString().take(12_000)
        add("$tag: ${error.javaClass.name}: ${error.message ?: "(tanpa pesan)"}\n$stack")
    }

    private fun appendBounded(file: File, line: String) {
        try {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val tail = file.readText().takeLast((MAX_FILE_BYTES / 2).toInt())
                file.writeText(tail)
            }
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use {
                it.append(line).append('\n')
            }
        } catch (_: Throwable) {
            // Best effort; the other mirror remains available if one volume fails.
        }
    }

    fun path(): String = visiblePath

    /** Complete persistent diagnostic text for the in-app viewer / share action. */
    fun readAll(context: Context): String {
        val candidates = files.ifEmpty { listOf(File(context.applicationContext.filesDir, FILE_NAME)) }
        val content = candidates.firstNotNullOfOrNull { file ->
            runCatching { file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()
        }.orEmpty()
        return content.ifBlank { last(MAX_ENTRIES).joinToString("\n") }
    }

    fun last(n: Int = 60): List<String> =
        if (buf.size <= n) buf.toList() else buf.subList(buf.size - n, buf.size)

    /** Tail from prior process launch; native SIGSEGV logs survive restart. */
    fun tailFromFile(context: Context, n: Int = 12): List<String> {
        val candidates = files.ifEmpty {
            listOf(File(context.applicationContext.filesDir, FILE_NAME))
        }
        return candidates.firstNotNullOfOrNull { file ->
            runCatching {
                file.takeIf { it.exists() }?.readText()?.trim()?.lineSequence()?.toList()?.takeLast(n)
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        } ?: emptyList()
    }

    @Synchronized
    fun clear(context: Context? = null) {
        buf.clear()
        val candidates = files.ifEmpty {
            context?.let { listOf(File(it.applicationContext.filesDir, FILE_NAME)) }.orEmpty()
        }
        candidates.forEach { runCatching { it.delete() } }
    }
}
