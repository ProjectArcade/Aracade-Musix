package com.arcadesoftware.musix.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.arcadesoftware.musix.db.entities.DownloadedSongEntity
import com.arcadesoftware.musix.db.entities.PlayHistoryEntity
import com.arcadesoftware.musix.db.entities.PlaylistEntity
import com.arcadesoftware.musix.db.entities.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // --- Play History ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlayHistory(playHistoryEntity: PlayHistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY timestamp DESC")
    fun getPlayHistory(): Flow<List<PlayHistoryEntity>>
    
    @Query("SELECT * FROM play_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentPlayHistory(limit: Int): Flow<List<PlayHistoryEntity>>

    // --- Downloaded Songs ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDownloadedSong(downloadedSongEntity: DownloadedSongEntity)

    @Query("SELECT * FROM downloaded_songs ORDER BY downloadTimestamp DESC")
    fun getDownloadedSongs(): Flow<List<DownloadedSongEntity>>

    @Query("SELECT * FROM downloaded_songs WHERE id = :songId")
    fun getDownloadedSong(songId: String): DownloadedSongEntity?

    @Query("SELECT * FROM downloaded_songs WHERE id = :songId")
    fun getDownloadedSongFlow(songId: String): Flow<DownloadedSongEntity?>

    @Query("DELETE FROM downloaded_songs WHERE id = :songId")
    fun removeDownloadedSong(songId: String)

    // --- Playlists ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPlaylist(playlistEntity: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistSong(playlistSongEntity: PlaylistSongEntity)

    // Joins to get songs for a playlist
    @Query("""
        SELECT play_history.* FROM play_history 
        INNER JOIN playlist_songs ON play_history.id = playlist_songs.songId 
        WHERE playlist_songs.playlistId = :playlistId 
        ORDER BY playlist_songs.position ASC
    """)
    fun getSongsForPlaylist(playlistId: Long): Flow<List<PlayHistoryEntity>>
}
