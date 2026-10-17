package com.kazamatech.kzdesk.sessions

import android.content.Context
import com.kazamatech.kzdesk.core.ConnectionProfile
import com.kazamatech.kzdesk.security.CredentialVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * M1 — data layer favorites: metadata di Room, password di
 * [CredentialVault] (AES-GCM, key di Android Keystore).
 *
 * UI (M1.2) cukup consume [favorites] → dapat daftar [ConnectionProfile]
 * siap-connect (password sudah di-dekripsi di memori, tidak pernah di UI).
 */
class SessionsRepository(context: Context) {

    private val dao = KzDeskDatabase.get(context).favoriteDao()
    private val vault = CredentialVault(context)

    /** Daftar favorites, password sudah ter-load (null jika tidak diingat). */
    fun favorites(): Flow<List<ConnectionProfile>> =
        dao.observeAll().map { list ->
            list.map { e ->
                ConnectionProfile(
                    host = e.host,
                    port = e.port,
                    username = e.username,
                    password = if (e.rememberPassword) {
                        vault.get(e.id)
                    } else {
                        null
                    } ?: vault.get(
                        CredentialVault.hostCredKey(e.host, e.port, e.username.orEmpty()),
                    ),
                    domain = e.domain,
                    label = e.label,
                    // id baris = kunci tetap perangkat (data lama tetap host:port)
                    key = e.id,
                )
            }
        }

    /**
     * Simpan/update favorite. Return false jika user meminta password diingat
     * tetapi enkripsi/persistensi vault gagal; metadata tetap tersimpan tanpa
     * mengklaim password aman tersimpan.
     */
    suspend fun save(profile: ConnectionProfile, rememberPassword: Boolean): Boolean {
        val existing = dao.byId(profile.id)
        val pass = profile.password // local val: smart cast lintas modul tidak diizinkan
        val shouldRemember = rememberPassword && !pass.isNullOrEmpty()
        val oldPassword = if (shouldRemember) vault.get(profile.id) else null
        val vaultSaved = shouldRemember && vault.put(profile.id, pass!!)
        val persistedRememberPassword = shouldRemember && vaultSaved
        // Ingatan per host: koneksi BARU ke host+user yang sama langsung
        // terisi tanpa input ulang (permintaan pemilik 2026-10-10).
        if (!pass.isNullOrEmpty()) {
            vault.put(
                CredentialVault.hostCredKey(profile.host, profile.port, profile.username.orEmpty()),
                pass,
            )
        }
        try {
            dao.upsert(
                FavoriteEntity(
                    id = profile.id,
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    domain = profile.domain,
                    label = profile.label,
                    rememberPassword = persistedRememberPassword,
                    createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                    lastUsedAtMs = System.currentTimeMillis(),
                )
            )
        } catch (t: Throwable) {
            // Roll back vault update if Room failed so the old favorite never
            // points at a different/new password than the row it describes.
            if (shouldRemember && vaultSaved) {
                if (oldPassword != null) vault.put(profile.id, oldPassword)
                else vault.remove(profile.id)
            }
            throw t
        }
        if (!persistedRememberPassword) vault.remove(profile.id)
        return !shouldRemember || vaultSaved
    }

    /** Tandai barusan dipakai (urutan "terakhir dipakai" di UI). */
    suspend fun touch(profile: ConnectionProfile) {
        dao.touch(profile.id, System.currentTimeMillis())
    }

    /** Hapus favorite + password-nya dari vault. */
    suspend fun remove(id: String) {
        dao.byId(id)?.let { dao.delete(it) }
        vault.remove(id)
    }

    /** Hapus semua favorites + semua password tersimpan. */
    suspend fun clear() {
        dao.clear()
        vault.clearAll()
    }
}
