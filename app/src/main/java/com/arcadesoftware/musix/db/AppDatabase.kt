package com.arcadesoftware.musix.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.arcadesoftware.musix.db.entities.DownloadedSongEntity
import com.arcadesoftware.musix.db.entities.PlayHistoryEntity
import com.arcadesoftware.musix.db.entities.PlaylistEntity
import com.arcadesoftware.musix.db.entities.PlaylistSongEntity

@Database(
    entities = [
        PlayHistoryEntity::class,
        DownloadedSongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "musix_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
