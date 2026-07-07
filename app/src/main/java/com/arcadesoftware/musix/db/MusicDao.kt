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
    suspend fun insertPlayHistory(playHistoryEntity: PlayHistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY timestamp DESC")
    fun getPlayHistory(): Flow<List<PlayHistoryEntity>>
    
    @Query("SELECT * FROM play_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentPlayHistory(limit: Int): Flow<List<PlayHistoryEntity>>

    // --- Downloaded Songs ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedSong(downloadedSongEntity: DownloadedSongEntity)

    @Query("SELECT * FROM downloaded_songs ORDER BY downloadTimestamp DESC")
    fun getDownloadedSongs(): Flow<List<DownloadedSongEntity>>

    @Query("SELECT * FROM downloaded_songs WHERE id = :songId")
    fun getDownloadedSong(songId: String): DownloadedSongEntity?

    @Query("SELECT * FROM downloaded_songs WHERE id = :songId")
    fun getDownloadedSongFlow(songId: String): Flow<DownloadedSongEntity?>

    @Query("DELETE FROM downloaded_songs WHERE id = :songId")
    suspend fun removeDownloadedSong(songId: String)

    // --- Playlists ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(playlistEntity: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(playlistSongEntity: PlaylistSongEntity)

    // Joins to get songs for a playlist (from play_history)
    @Query("""
        SELECT play_history.* FROM play_history 
        INNER JOIN playlist_songs ON play_history.id = playlist_songs.songId 
        WHERE playlist_songs.playlistId = :playlistId 
        ORDER BY playlist_songs.position ASC
    """)
    fun getSongsForPlaylist(playlistId: Long): Flow<List<PlayHistoryEntity>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    fun getSongCountForPlaylist(playlistId: Long): Flow<Int>

    @Query("UPDATE playlists SET name = :name, coverUri = :coverUri WHERE id = :id")
    suspend fun updatePlaylist(id: Long, name: String, coverUri: String?)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    // --- Play History Maintenance ---
    /**
     * Keep only the most recent [limit] play history entries to prevent unbounded table growth.
     * Call periodically (e.g., once per app launch) to cap DB size.
     */
    @Query("DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun trimPlayHistory(limit: Int = 500)

    /** Check if a song already exists in a playlist (for duplicate prevention). */
    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun isSongInPlaylist(playlistId: Long, songId: String): Int
}
