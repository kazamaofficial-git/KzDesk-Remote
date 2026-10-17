package com.kazamatech.kzdesk.security

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persist JVM crashes and handled fatal errors to an easy-to-find media folder. */
object CrashLog {
    private const val FILE_NAME = "kzdesk-crash.log"
    @Volatile private var targets: List<File> = emptyList()

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val internal = File(appCtx.filesDir, FILE_NAME)
        val external = appCtx.externalMediaDirs.firstOrNull()?.let { File(File(it, "log"), FILE_NAME) }
            ?.takeIf { target -> runCatching {
                target.parentFile?.let { it.isDirectory || it.mkdirs() } == true
                target.parentFile?.canWrite() == true
            }.getOrDefault(false) }
        targets = if (external != null) listOf(external, internal) else listOf(internal)
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(appCtx, t, e) } catch (_: Throwable) { }
            default?.uncaughtException(t, e)
        }
    }

    private fun write(ctx: Context, t: Thread, e: Throwable) {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        append("=== $ts  thread=${t.name} ===\n${sw}\n")
        if (targets.isEmpty()) appendTo(File(ctx.filesDir, FILE_NAME), "=== $ts ===\n${sw}\n")
    }

    /** Record a recoverable JNI/startup error, including its stack trace. */
    fun note(context: Context, msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val body = "=== $ts  insiden: $msg ===\n"
        if (targets.isEmpty()) targets = listOf(File(context.applicationContext.filesDir, FILE_NAME))
        append(body)
    }

    fun last(context: Context): String? {
        val candidates = targets.ifEmpty {
            listOf(File(context.applicationContext.filesDir, FILE_NAME),
                context.externalMediaDirs.firstOrNull()?.let { File(File(it, "log"), FILE_NAME) }
                    ?: File(context.filesDir, FILE_NAME))
        }
        return candidates.firstNotNullOfOrNull { file ->
            runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        }
    }

    fun path(context: Context): String = targets.firstOrNull()?.absolutePath
        ?: File(context.applicationContext.filesDir, FILE_NAME).absolutePath

    fun clear(context: Context) {
        val candidates = targets.ifEmpty {
            listOf(File(context.applicationContext.filesDir, FILE_NAME),
                context.externalMediaDirs.firstOrNull()?.let { File(File(it, "log"), FILE_NAME) }
                    ?: File(context.filesDir, FILE_NAME))
        }
        candidates.distinct().forEach { runCatching { it.delete() } }
    }

    private fun append(text: String) {
        targets.forEach { appendTo(it, text) }
    }

    private fun appendTo(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > 64 * 1024) {
                val tail = file.readText().takeLast(32 * 1024)
                file.writeText(tail)
            }
            file.appendText(text)
        }
    }
}
