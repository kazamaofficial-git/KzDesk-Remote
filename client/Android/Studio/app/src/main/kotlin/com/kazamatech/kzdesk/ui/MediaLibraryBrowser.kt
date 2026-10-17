package com.kazamatech.kzdesk.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.MediaDescription
import android.os.Bundle
import android.service.media.MediaBrowserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Penjelajah pustaka musik lewat `MediaBrowserService`.
 *
 * Antrian (`MediaController.queue`) hanya berisi lagu yang sudah dimuat pemutar.
 * Untuk membuka album/playlist/artis yang belum diputar, Android menyediakan
 * satu pintu resmi: `MediaBrowserService`. Setiap aplikasi musik mengekspor
 * pohon foldernya sendiri, jadi KzDesk tidak meng-hardcode aplikasi apa pun —
 * layanan ditemukan dari PackageManager, lalu pohonnya dijelajahi apa adanya.
 *
 * Batasannya jujur: aplikasi boleh menolak koneksi atau membatasi isinya
 * (Spotify, misalnya, tidak membuka browser publik). Karena itu UI selalu
 * menampilkan alasan ketika pustaka kosong, bukan layar kosong tanpa penjelasan.
 */
internal object MediaLibraryBrowser {

    private val mutableState = MutableStateFlow(MediaLibraryState())
    val state: StateFlow<MediaLibraryState> = mutableState.asStateFlow()

    private var browser: MediaBrowser? = null
    private var subscribedParent: String? = null
    @Volatile private var appContext: Context? = null

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val active = browser ?: return
            val root = runCatching { active.root }.getOrNull()
            val current = mutableState.value
            if (root == null) {
                mutableState.value = current.copy(
                    connecting = false,
                    loading = false,
                    message = kzNow(
                        "Aplikasi ini terhubung tapi tidak memberi folder akar pustaka.",
                        "This app connected but did not expose a library root.",
                    ),
                )
                return
            }
            val crumb = MediaLibraryCrumb(root, current.connectedLabel ?: root)
            mutableState.value = current.copy(
                connecting = false,
                rootId = root,
                crumbs = listOf(crumb),
                entries = emptyList(),
                message = null,
            )
            subscribe(root)
        }

        override fun onConnectionSuspended() {
            val current = mutableState.value
            mutableState.value = current.copy(
                connecting = false,
                loading = false,
                connectedPackage = null,
                crumbs = emptyList(),
                entries = emptyList(),
                message = kzNow(
                    "Koneksi pustaka terputus oleh aplikasinya. Coba sambungkan lagi.",
                    "The library connection was dropped by the app. Try connecting again.",
                ),
            )
        }

        override fun onConnectionFailed() {
            val current = mutableState.value
            mutableState.value = current.copy(
                connecting = false,
                loading = false,
                connectedPackage = null,
                crumbs = emptyList(),
                entries = emptyList(),
                message = kzNow(
                    "Aplikasi menolak koneksi pustaka. Tidak semua pemutar membuka MediaBrowserService; antrian lagu tetap bisa dipakai.",
                    "The app refused the library connection. Not every player opens a MediaBrowserService; the play queue still works.",
                ),
            )
        }
    }

    private val subscriptionCallback = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
            // Hasil folder lama bisa datang sesudah pengguna pindah folder;
            // abaikan kalau parentnya sudah bukan yang sedang dibuka.
            if (parentId != subscribedParent) return
            val current = mutableState.value
            mutableState.value = current.copy(
                loading = false,
                entries = children.map { it.toEntry() },
                message = if (children.isEmpty()) {
                    kzNow("Folder ini kosong.", "This folder is empty.")
                } else {
                    null
                },
            )
        }

        override fun onError(parentId: String) {
            if (parentId != subscribedParent) return
            val current = mutableState.value
            mutableState.value = current.copy(
                loading = false,
                entries = emptyList(),
                message = kzNow(
                    "Aplikasi menolak membaca folder ini.",
                    "The app refused to read this folder.",
                ),
            )
        }
    }

    /**
     * Cari aplikasi yang mengekspor `MediaBrowserService`.
     *
     * Android 11+ menyaring hasil kueri paket, jadi manifest mendeklarasikan
     * `<queries><intent>` untuk action ini; tanpa itu daftarnya selalu kosong.
     */
    fun discover(context: Context) {
        val application = context.applicationContext
        appContext = application
        val found = runCatching {
            val probe = Intent(MediaBrowserService.SERVICE_INTERFACE)
            application.packageManager.queryIntentServices(probe, 0)
                .filter { it.serviceInfo?.packageName != application.packageName }
                .mapNotNull { info ->
                    val service = info.serviceInfo ?: return@mapNotNull null
                    MediaLibrarySource(
                        packageName = service.packageName,
                        serviceName = service.name,
                        label = runCatching {
                            application.packageManager
                                .getApplicationLabel(service.applicationInfo)
                                .toString()
                        }.getOrNull()?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: service.packageName.substringAfterLast('.')
                                .replaceFirstChar { it.uppercase() },
                    )
                }
                .distinctBy { "${it.packageName}/${it.serviceName}" }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())

        val current = mutableState.value
        mutableState.value = current.copy(sources = found, discovered = true)
    }

    fun connect(context: Context, source: MediaLibrarySource) {
        val application = context.applicationContext
        appContext = application
        disconnectBrowser()
        mutableState.value = mutableState.value.copy(
            connecting = true,
            loading = false,
            connectedPackage = source.packageName,
            connectedLabel = source.label,
            crumbs = emptyList(),
            entries = emptyList(),
            rootId = null,
            message = null,
        )
        val created = runCatching {
            MediaBrowser(
                application,
                ComponentName(source.packageName, source.serviceName),
                connectionCallback,
                Bundle.EMPTY,
            )
        }.getOrNull()
        if (created == null) {
            mutableState.value = mutableState.value.copy(
                connecting = false,
                connectedPackage = null,
                message = kzNow(
                    "Tidak bisa membuat koneksi pustaka ke aplikasi ini.",
                    "Could not open a library connection to this app.",
                ),
            )
            return
        }
        browser = created
        runCatching { created.connect() }.onFailure {
            browser = null
            mutableState.value = mutableState.value.copy(
                connecting = false,
                connectedPackage = null,
                message = kzNow(
                    "Koneksi pustaka gagal dimulai.",
                    "The library connection failed to start.",
                ),
            )
        }
    }

    /** Buka folder di dalam pohon pustaka. */
    fun open(entry: MediaLibraryEntry, title: String) {
        if (!entry.browsable || entry.mediaId == null) return
        val current = mutableState.value
        mutableState.value = current.copy(
            crumbs = current.crumbs + MediaLibraryCrumb(entry.mediaId, title),
            entries = emptyList(),
            loading = true,
            message = null,
        )
        subscribe(entry.mediaId)
    }

    /** Naik satu tingkat; dari akar, tidak melakukan apa-apa. */
    fun up() {
        val current = mutableState.value
        if (current.crumbs.size <= 1) return
        val trimmed = current.crumbs.dropLast(1)
        val parent = trimmed.last()
        mutableState.value = current.copy(
            crumbs = trimmed,
            entries = emptyList(),
            loading = true,
            message = null,
        )
        subscribe(parent.id)
    }

    /** Putar satu lagu dari pustaka lewat sesi aplikasi pemiliknya. */
    fun play(context: Context, entry: MediaLibraryEntry) {
        val mediaId = entry.mediaId ?: return
        val pkg = mutableState.value.connectedPackage ?: return
        SpotifyMediaBridge.playFromMediaId(context, pkg, mediaId)
    }

    fun disconnect() {
        disconnectBrowser()
        mutableState.value = mutableState.value.copy(
            connecting = false,
            loading = false,
            connectedPackage = null,
            connectedLabel = null,
            rootId = null,
            crumbs = emptyList(),
            entries = emptyList(),
            message = null,
        )
    }

    private fun subscribe(parentId: String) {
        val active = browser
        if (active == null || !runCatching { active.isConnected }.getOrDefault(false)) {
            mutableState.value = mutableState.value.copy(
                loading = false,
                message = kzNow(
                    "Pustaka belum tersambung. Pilih aplikasi di atas dulu.",
                    "The library is not connected yet. Pick an app above first.",
                ),
            )
            return
        }
        runCatching {
            subscribedParent?.let { active.unsubscribe(it, subscriptionCallback) }
        }
        subscribedParent = parentId
        mutableState.value = mutableState.value.copy(loading = true)
        runCatching { active.subscribe(parentId, subscriptionCallback) }
    }

    private fun disconnectBrowser() {
        val active = browser ?: return
        runCatching {
            subscribedParent?.let { active.unsubscribe(it, subscriptionCallback) }
        }
        runCatching { active.disconnect() }
        browser = null
        subscribedParent = null
    }

    private fun MediaBrowser.MediaItem.toEntry(): MediaLibraryEntry {
        val description: MediaDescription? = this.description
        return MediaLibraryEntry(
            mediaId = this.mediaId,
            title = description?.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: kzNow("(tanpa judul)", "(untitled)"),
            subtitle = description?.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: description?.description?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            playable = this.isPlayable,
            browsable = this.isBrowsable,
        )
    }
}

internal data class MediaLibraryState(
    val sources: List<MediaLibrarySource> = emptyList(),
    val discovered: Boolean = false,
    val connecting: Boolean = false,
    val connectedPackage: String? = null,
    val connectedLabel: String? = null,
    val rootId: String? = null,
    val crumbs: List<MediaLibraryCrumb> = emptyList(),
    val entries: List<MediaLibraryEntry> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

internal data class MediaLibrarySource(
    val packageName: String,
    val serviceName: String,
    val label: String,
)

internal data class MediaLibraryCrumb(val id: String, val title: String)

internal data class MediaLibraryEntry(
    val mediaId: String?,
    val title: String,
    val subtitle: String?,
    val playable: Boolean,
    val browsable: Boolean,
)
