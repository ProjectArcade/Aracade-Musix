package com.arcadesoftware.musix.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistName: String,
    val artistId: String?,
    val thumbnailUrl: String,
    val timestamp: Long = System.currentTimeMillis()
) : java.io.Serializable {
    fun toSongItem(): SongItem {
        return SongItem(
            id = id,
            title = title,
            artists = listOf(Artist(name = artistName, id = artistId)),
            thumbnail = thumbnailUrl,
            explicit = false
        )
    }
}
