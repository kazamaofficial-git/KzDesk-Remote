package com.kazamatech.kzdesk.sessions

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Baris favorites di Room. `id` = `host:port` (sama dengan
 * [com.kazamatech.kzdesk.core.ConnectionProfile.id]) sehingga password di
 * [com.kazamatech.kzdesk.security.CredentialVault] ter-mapa 1:1.
 *
 * Password TIDAK ada di tabel ini — hanya [rememberPassword] sebagai flag.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,
    val host: String,
    val port: Int,
    val username: String?,
    val domain: String?,
    val label: String?,
    val rememberPassword: Boolean,
    val createdAtMs: Long,
    val lastUsedAtMs: Long?,
)
