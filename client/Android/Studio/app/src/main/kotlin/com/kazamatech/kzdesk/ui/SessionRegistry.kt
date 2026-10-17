package com.kazamatech.kzdesk.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Daftar sesi yang sedang hidup di proses ini.
 *
 * Satu sesi = satu KzDeskSessionActivity (masing-masing punya SessionManager
 * sendiri), dan default app memang membiarkan sesi jalan di latar. Registry
 * ini yang bikin sesi-sesi itu kelihatan dan bisa dipindah-pindah dari home —
 * tanpa ini, sesi kedua cuma bisa dijangkau lewat daftar app terakhir.
 */
object KzSessionRegistry {

    class Live(
        val id: String,
        val label: String,
        val address: String,
        val open: () -> Unit,
        val kill: () -> Unit,
        val bitmapProvider: (() -> android.graphics.Bitmap?)? = null,
    )

    private val items = CopyOnWriteArrayList<Live>()
    private val activeIds = ConcurrentHashMap.newKeySet<String>()

    fun add(item: Live) {
        remove(item.id)
        items.add(item)
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        activeIds.remove(id)
    }

    fun setActive(id: String, active: Boolean) {
        if (active) activeIds.add(id) else activeIds.remove(id)
    }

    fun activeCount(): Int = activeIds.size

    fun list(): List<Live> = items.toList()

    /** Putuskan seluruh sesi yang aktif dari aksi global notifikasi. */
    fun disconnectAll() {
        list().forEach { live -> runCatching { live.kill() } }
    }

    fun count(): Int = items.size
}
