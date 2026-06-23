package com.arcadesoftware.musix.db

import android.content.Context
import android.util.Log
import com.arcadesoftware.musix.HomeCacheManager
import com.arcadesoftware.musix.db.entities.PlayHistoryEntity
import com.arcadesoftware.musix.db.entities.PlaylistEntity
import com.arcadesoftware.musix.db.entities.PlaylistSongEntity
import com.arcadesoftware.musix.models.SimilarRecommendation
import com.arcadesoftware.musix.ui.screens.LibraryArtist
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.music.innertube.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"

    private fun getDbRef() = FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
        FirebaseDatabase.getInstance().getReference("com_arcadesoftware_musix").child("user").child(uid)
    }

    fun syncLikedSongs(context: Context) {
        val ref = getDbRef() ?: return
        val songIds = LikedSongsManager.getLikedSongIds(context).toList()
        ref.child("liked_songs").setValue(songIds)
            .addOnFailureListener { Log.e(TAG, "Failed to sync liked songs", it) }
    }

    fun syncLikedArtists(context: Context) {
        val ref = getDbRef() ?: return
        val artists = LikedArtistsManager.getLikedArtists(context).map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "thumbnailUrl" to it.thumbnailUrl
            )
        }
        ref.child("liked_artists").setValue(artists)
            .addOnFailureListener { Log.e(TAG, "Failed to sync liked artists", it) }
    }

    fun syncLikedPlaylists(context: Context) {
        val ref = getDbRef() ?: return
        val playlists = LikedPlaylistsManager.getLikedPlaylists(context).map {
            mapOf(
                "id" to it.id,
                "title" to it.title,
                "thumbnail" to it.thumbnail,
                "type" to it.type,
                "subtitle" to it.subtitle
            )
        }
        ref.child("liked_playlists").setValue(playlists)
            .addOnFailureListener { Log.e(TAG, "Failed to sync liked playlists", it) }
    }

    fun syncHistory(context: Context) {
        val ref = getDbRef() ?: return
        val database = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val history = database.musicDao().getPlayHistory().first().map {
                    mapOf(
                        "id" to it.id,
                        "title" to it.title,
                        "artistName" to it.artistName,
                        "artistId" to it.artistId,
                        "thumbnailUrl" to it.thumbnailUrl,
                        "timestamp" to it.timestamp
                    )
                }
                ref.child("history").setValue(history)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync history", e)
            }
        }
    }

    fun syncRecommendations(context: Context) {
        val ref = getDbRef() ?: return
        val recs = HomeCacheManager.load(context).second
        val serializedRecs = recs.map { rec ->
            mapOf(
                "seed" to mapOf(
                    "id" to rec.seed.id,
                    "title" to rec.seed.title,
                    "artistName" to rec.seed.artistName,
                    "artistId" to rec.seed.artistId,
                    "thumbnailUrl" to rec.seed.thumbnailUrl,
                    "timestamp" to rec.seed.timestamp
                ),
                "items" to rec.items.map { item -> item.toMap() }
            )
        }
        ref.child("recommendations").setValue(serializedRecs)
            .addOnFailureListener { Log.e(TAG, "Failed to sync recommendations", it) }
    }

    fun syncPlaylists(context: Context) {
        val ref = getDbRef() ?: return
        val database = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val playlistsList = database.musicDao().getPlaylists().first()
                val playlistData = playlistsList.map { playlist ->
                    val songs = database.musicDao().getSongsForPlaylist(playlist.id).first().map { song ->
                        mapOf(
                            "songId" to song.id,
                            "songMetadata" to mapOf(
                                "id" to song.id,
                                "title" to song.title,
                                "artistName" to song.artistName,
                                "artistId" to song.artistId,
                                "thumbnailUrl" to song.thumbnailUrl
                            )
                        )
                    }
                    mapOf(
                        "id" to playlist.id,
                        "name" to playlist.name,
                        "coverUri" to playlist.coverUri,
                        "createdAt" to playlist.createdAt,
                        "songs" to songs
                    )
                }
                ref.child("playlists").setValue(playlistData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync playlists", e)
            }
        }
    }

    fun pushAllLocalDataToFirebase(context: Context) {
        syncLikedSongs(context)
        syncLikedArtists(context)
        syncLikedPlaylists(context)
        syncHistory(context)
        syncRecommendations(context)
        syncPlaylists(context)
    }

    fun fetchAndMergeFirebaseData(context: Context, onComplete: (() -> Unit)? = null) {
        val ref = getDbRef() ?: return
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 1. Liked Songs
                        val likedSongsSnap = snapshot.child("liked_songs")
                        if (likedSongsSnap.exists()) {
                            val remoteIds = likedSongsSnap.children.mapNotNull { it.value as? String }.toSet()
                            val localIds = LikedSongsManager.getLikedSongIds(context)
                            LikedSongsManager.saveLikedSongIds(context, localIds + remoteIds)
                        }

                        // 2. Liked Artists
                        val likedArtistsSnap = snapshot.child("liked_artists")
                        if (likedArtistsSnap.exists()) {
                            val remoteArtists = likedArtistsSnap.children.mapNotNull { child ->
                                val id = child.child("id").value as? String ?: return@mapNotNull null
                                val name = child.child("name").value as? String ?: "Unknown Artist"
                                val thumbnailUrl = child.child("thumbnailUrl").value as? String
                                LibraryArtist(id, name, thumbnailUrl, emptyList())
                            }
                            val localArtists = LikedArtistsManager.getLikedArtists(context)
                            val mergedArtists = (localArtists + remoteArtists).distinctBy { it.id }
                            LikedArtistsManager.saveLikedArtists(context, mergedArtists)
                        }

                        // 3. Liked Playlists
                        val likedPlaylistsSnap = snapshot.child("liked_playlists")
                        if (likedPlaylistsSnap.exists()) {
                            val remotePlaylists = likedPlaylistsSnap.children.mapNotNull { child ->
                                val id = child.child("id").value as? String ?: return@mapNotNull null
                                val title = child.child("title").value as? String ?: "Untitled"
                                val thumbnail = child.child("thumbnail").value as? String
                                val type = child.child("type").value as? String ?: "PLAYLIST"
                                val subtitle = child.child("subtitle").value as? String ?: ""
                                LikedPlaylistsManager.LikedPlaylist(id, title, thumbnail, type, subtitle)
                            }
                            val localPlaylists = LikedPlaylistsManager.getLikedPlaylists(context)
                            val mergedPlaylists = (localPlaylists + remotePlaylists).distinctBy { it.id }
                            LikedPlaylistsManager.saveLikedPlaylists(context, mergedPlaylists)
                        }

                        // 4. Play History
                        val historySnap = snapshot.child("history")
                        val database = AppDatabase.getDatabase(context)
                        if (historySnap.exists()) {
                            historySnap.children.forEach { child ->
                                val id = child.child("id").value as? String ?: return@forEach
                                val title = child.child("title").value as? String ?: return@forEach
                                val artistName = child.child("artistName").value as? String ?: return@forEach
                                val artistId = child.child("artistId").value as? String
                                val thumbnailUrl = child.child("thumbnailUrl").value as? String ?: return@forEach
                                val timestamp = (child.child("timestamp").value as? Number)?.toLong() ?: System.currentTimeMillis()
                                database.musicDao().insertPlayHistory(PlayHistoryEntity(id, title, artistName, artistId, thumbnailUrl, timestamp))
                            }
                        }

                        // 5. Recommendations
                        val recsSnap = snapshot.child("recommendations")
                        if (recsSnap.exists()) {
                            val remoteRecs = recsSnap.children.mapNotNull { child ->
                                val seedSnap = child.child("seed")
                                val seedId = seedSnap.child("id").value as? String ?: return@mapNotNull null
                                val seedTitle = seedSnap.child("title").value as? String ?: return@mapNotNull null
                                val seedArtistName = seedSnap.child("artistName").value as? String ?: return@mapNotNull null
                                val seedArtistId = seedSnap.child("artistId").value as? String
                                val seedThumbnailUrl = seedSnap.child("thumbnailUrl").value as? String ?: return@mapNotNull null
                                val seedTimestamp = (seedSnap.child("timestamp").value as? Number)?.toLong() ?: System.currentTimeMillis()
                                val seed = PlayHistoryEntity(seedId, seedTitle, seedArtistName, seedArtistId, seedThumbnailUrl, seedTimestamp)

                                val items = child.child("items").children.mapNotNull { itemChild ->
                                    val itemMap = itemChild.value as? Map<String, Any?>
                                    itemMap?.toYTItem()
                                }
                                SimilarRecommendation(seed, items)
                            }
                            val localPair = HomeCacheManager.load(context)
                            val mergedRecs = (localPair.second + remoteRecs).distinctBy { it.seed.id }
                            HomeCacheManager.save(context, localPair.first, mergedRecs)
                        }

                        // 6. Playlists
                        val playlistsSnap = snapshot.child("playlists")
                        if (playlistsSnap.exists()) {
                            playlistsSnap.children.forEach { child ->
                                val name = child.child("name").value as? String ?: return@forEach
                                val coverUri = child.child("coverUri").value as? String
                                val createdAt = (child.child("createdAt").value as? Number)?.toLong() ?: System.currentTimeMillis()
                                
                                val playlistEntity = PlaylistEntity(name = name, coverUri = coverUri, createdAt = createdAt)
                                val playlistId = database.musicDao().insertPlaylist(playlistEntity)
                                
                                child.child("songs").children.forEachIndexed { index, songChild ->
                                    val songId = songChild.child("songId").value as? String ?: return@forEachIndexed
                                    
                                    val songMeta = songChild.child("songMetadata")
                                    if (songMeta.exists()) {
                                        val title = songMeta.child("title").value as? String ?: "Unknown"
                                        val artistName = songMeta.child("artistName").value as? String ?: "Unknown"
                                        val artistId = songMeta.child("artistId").value as? String
                                        val thumbnailUrl = songMeta.child("thumbnailUrl").value as? String ?: ""
                                        database.musicDao().insertPlayHistory(
                                            PlayHistoryEntity(songId, title, artistName, artistId, thumbnailUrl)
                                        )
                                    }
                                    
                                    val songEntity = PlaylistSongEntity(
                                        playlistId = playlistId,
                                        songId = songId,
                                        position = index,
                                        addedAt = (songChild.child("addedAt").value as? Number)?.toLong() ?: System.currentTimeMillis()
                                    )
                                    database.musicDao().insertPlaylistSong(songEntity)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error merging remote Firebase data", e)
                    } finally {
                        onComplete?.invoke()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase fetch cancelled: ${error.message}")
                onComplete?.invoke()
            }
        })
    }

    private fun YTItem.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "id" to id,
            "title" to title,
            "thumbnail" to thumbnail,
            "explicit" to explicit
        )
        when (this) {
            is SongItem -> {
                map["type"] = "song"
                map["artists"] = artists.map { mapOf("name" to it.name, "id" to it.id) }
                map["album"] = album?.let { mapOf("name" to it.name, "id" to it.id) }
                map["duration"] = duration
                map["musicVideoType"] = musicVideoType
            }
            is AlbumItem -> {
                map["type"] = "album"
                map["playlistId"] = playlistId
                map["artists"] = artists?.map { mapOf("name" to it.name, "id" to it.id) }
                map["year"] = year
                map["description"] = description
            }
            is PlaylistItem -> {
                map["type"] = "playlist"
                map["author"] = author?.let { mapOf("name" to it.name, "id" to it.id) }
                map["songCountText"] = songCountText
            }
            is ArtistItem -> {
                map["type"] = "artist"
                map["channelId"] = channelId
            }
        }
        return map
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toYTItem(): YTItem? {
        val id = this["id"] as? String ?: return null
        val title = this["title"] as? String ?: return null
        val thumbnail = this["thumbnail"] as? String
        val explicit = this["explicit"] as? Boolean ?: false
        val type = this["type"] as? String ?: return null
        
        return when (type) {
            "song" -> {
                val artists = (this["artists"] as? List<Map<String, Any?>>)?.map {
                    Artist(it["name"] as? String ?: "", it["id"] as? String)
                } ?: emptyList()
                val albumMap = this["album"] as? Map<String, Any?>
                val album = albumMap?.let {
                    Album(it["name"] as? String ?: "", it["id"] as? String ?: "")
                }
                val duration = (this["duration"] as? Number)?.toInt()
                val musicVideoType = this["musicVideoType"] as? String
                SongItem(id, title, artists, album, duration, musicVideoType, thumbnail = thumbnail ?: "", explicit = explicit)
            }
            "album" -> {
                val playlistId = this["playlistId"] as? String ?: id
                val artists = (this["artists"] as? List<Map<String, Any?>>)?.map {
                    Artist(it["name"] as? String ?: "", it["id"] as? String)
                }
                val year = (this["year"] as? Number)?.toInt()
                val description = this["description"] as? String
                AlbumItem(id, playlistId, id, title, artists, year, thumbnail ?: "", explicit, description)
            }
            "playlist" -> {
                val authorMap = this["author"] as? Map<String, Any?>
                val author = authorMap?.let {
                    Artist(it["name"] as? String ?: "", it["id"] as? String)
                }
                val songCountText = this["songCountText"] as? String
                PlaylistItem(id, title, author, songCountText, thumbnail, null, null, null, false)
            }
            "artist" -> {
                val channelId = this["channelId"] as? String
                ArtistItem(id, title, thumbnail, channelId, null, null, null)
            }
            else -> null
        }
    }
}
