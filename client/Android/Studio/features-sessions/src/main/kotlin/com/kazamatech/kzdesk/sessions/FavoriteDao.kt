package com.kazamatech.kzdesk.sessions

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY COALESCE(label, host) COLLATE NOCASE")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun byId(id: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Delete
    suspend fun delete(entity: FavoriteEntity)

    @Query("UPDATE favorites SET lastUsedAtMs = :tsMs WHERE id = :id")
    suspend fun touch(id: String, tsMs: Long)

    @Query("DELETE FROM favorites")
    suspend fun clear()
}
