package com.kazamatech.kzdesk.sessions

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FavoriteEntity::class], version = 1, exportSchema = false)
abstract class KzDeskDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao

    companion object {
        private const val NAME = "kzdesk.db"

        @Volatile
        private var instance: KzDeskDatabase? = null

        fun get(context: Context): KzDeskDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KzDeskDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
