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

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"
    var lastSyncedUid: String? = null
    private var syncJob: Job? = null

    // Module-level scope — no lifecycle leak, survives across calls
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Debounce: track last time history was synced to avoid writing on every song play
    private var lastHistorySyncMs: Long = 0L
    private const val HISTORY_SYNC_DEBOUNCE_MS = 5 * 60 * 1000L // 5 minutes

    fun schedulePushAllLocalDataToFirebase(context: Context) {
        val appContext = context.applicationContext
        syncJob?.cancel()
        syncJob = syncScope.launch {
            kotlinx.coroutines.delay(5 * 60 * 1000) // 5 minutes delay
            pushAllLocalDataToFirebase(appContext)
        }
    }

    private fun getDbRef() = FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
        FirebaseDatabase.getInstance().getReference("com_arcadesoftware_musix").child("user").child(uid)
    }

    fun syncLikedSongs(context: Context) {
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!sharedPrefs.getBoolean("sync_library", true)) return
        val ref = getDbRef() ?: return
        val songIds = LikedSongsManager.getLikedSongIds(context).toList()
        ref.child("liked_songs").setValue(songIds)
            .addOnFailureListener { Log.e(TAG, "Failed to sync liked songs", it) }

        // Compile and sync metadata for each liked song from database
        val database = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val historyList = database.musicDao().getPlayHistory().first()
                val metadata = mutableMapOf<String, Map<String, Any?>>()
                for (id in songIds) {
                    val matchingSong = historyList.find { it.id == id }
                    if (matchingSong != null) {
                        metadata[id] = mapOf(
                            "id" to matchingSong.id,
                            "title" to matchingSong.title,
                            "artistName" to matchingSong.artistName,
                            "artistId" to matchingSong.artistId,
                            "thumbnailUrl" to matchingSong.thumbnailUrl
                        )
                    }
                }
                if (metadata.isNotEmpty()) {
                    ref.child("liked_songs_metadata").setValue(metadata)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync liked songs metadata", e)
            }
        }
    }

    fun syncLikedArtists(context: Context) {
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!sharedPrefs.getBoolean("sync_library", true)) return
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
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!sharedPrefs.getBoolean("sync_library", true)) return
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
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!sharedPrefs.getBoolean("sync_history", true)) return
        val ref = getDbRef() ?: return

        // Debounce: don't sync more than once every 5 minutes
        val now = System.currentTimeMillis()
        if (now - lastHistorySyncMs < HISTORY_SYNC_DEBOUNCE_MS) {
            Log.d(TAG, "syncHistory skipped — debounce active")
            return
        }
        lastHistorySyncMs = now

        val database = AppDatabase.getDatabase(context)
        syncScope.launch {
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
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!sharedPrefs.getBoolean("sync_history", true)) return
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
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!sharedPrefs.getBoolean("sync_playlists", true)) return
        val ref = getDbRef() ?: return
        val database = AppDatabase.getDatabase(context)
        syncScope.launch {
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
        syncUserDetails(context)
        syncSettings(context)
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
                        // 0. Settings Sync
                        val settingsSnap = snapshot.child("settings")
                        if (settingsSnap.exists()) {
                            val syncPlaylists = settingsSnap.child("sync_playlists").value as? Boolean ?: true
                            val syncLibrary = settingsSnap.child("sync_library").value as? Boolean ?: true
                            val syncHistory = settingsSnap.child("sync_history").value as? Boolean ?: true
                            val resumePlayback = settingsSnap.child("resume_playback").value as? Boolean ?: true
                            val alwaysShuffle = settingsSnap.child("always_shuffle").value as? Boolean ?: false
                            val autoDownloadPlaylists = settingsSnap.child("auto_download_playlists").value as? Boolean ?: false
                            val wifiOnlyDownload = settingsSnap.child("wifi_only_download").value as? Boolean ?: false
                            context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("sync_playlists", syncPlaylists)
                                .putBoolean("sync_library", syncLibrary)
                                .putBoolean("sync_history", syncHistory)
                                .putBoolean("resume_playback", resumePlayback)
                                .putBoolean("always_shuffle", alwaysShuffle)
                                .putBoolean("auto_download_playlists", autoDownloadPlaylists)
                                .putBoolean("wifi_only_download", wifiOnlyDownload)
                                .apply()
                        }

                        // 1. Liked Songs
                        val likedSongsSnap = snapshot.child("liked_songs")
                        if (likedSongsSnap.exists()) {
                            val remoteIds = likedSongsSnap.children.mapNotNull { it.value as? String }.toSet()
                            val localIds = LikedSongsManager.getLikedSongIds(context)
                            LikedSongsManager.saveLikedSongIds(context, localIds + remoteIds)
                        }

                        val likedSongsMetadataSnap = snapshot.child("liked_songs_metadata")
                        if (likedSongsMetadataSnap.exists()) {
                            val database = AppDatabase.getDatabase(context)
                            likedSongsMetadataSnap.children.forEach { child ->
                                val id = child.child("id").value as? String ?: return@forEach
                                val title = child.child("title").value as? String ?: return@forEach
                                val artistName = child.child("artistName").value as? String ?: return@forEach
                                val artistId = child.child("artistId").value as? String
                                val thumbnailUrl = child.child("thumbnailUrl").value as? String ?: return@forEach
                                database.musicDao().insertPlayHistory(
                                    PlayHistoryEntity(id, title, artistName, artistId, thumbnailUrl, System.currentTimeMillis())
                                )
                            }
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

                        // 6. Playlists — use stored id to prevent duplication on re-sync
                        val playlistsSnap = snapshot.child("playlists")
                        if (playlistsSnap.exists()) {
                            playlistsSnap.children.forEach { child ->
                                val storedId = (child.child("id").value as? Number)?.toLong() ?: 0L
                                val name = child.child("name").value as? String ?: return@forEach
                                val coverUri = child.child("coverUri").value as? String
                                val createdAt = (child.child("createdAt").value as? Number)?.toLong() ?: System.currentTimeMillis()

                                // Use the stored remote id so Room's IGNORE strategy prevents duplicate inserts
                                val playlistEntity = PlaylistEntity(id = storedId, name = name, coverUri = coverUri, createdAt = createdAt)
                                val playlistId = database.musicDao().insertPlaylist(playlistEntity)
                                // insertPlaylist returns -1 when IGNORE fires (playlist already exists)
                                val effectiveId = if (playlistId == -1L) storedId else playlistId

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

                                    // Only add the song if it's not already in the playlist
                                    val alreadyInPlaylist = database.musicDao().isSongInPlaylist(effectiveId, songId) > 0
                                    if (!alreadyInPlaylist) {
                                        val songEntity = PlaylistSongEntity(
                                            playlistId = effectiveId,
                                            songId = songId,
                                            position = index,
                                            addedAt = (songChild.child("addedAt").value as? Number)?.toLong() ?: System.currentTimeMillis()
                                        )
                                        database.musicDao().insertPlaylistSong(songEntity)
                                    }
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

    fun syncUserDetails(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val ref = getDbRef() ?: return
        val details = mapOf(
            "displayName" to (user.displayName ?: ""),
            "email" to (user.email ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "uid" to user.uid
        )
        ref.child("details").setValue(details)
            .addOnFailureListener { Log.e(TAG, "Failed to sync user details", it) }
    }

    fun syncSettings(context: Context) {
        val ref = getDbRef() ?: return
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        val settings = mapOf(
            "sync_playlists" to sharedPrefs.getBoolean("sync_playlists", true),
            "sync_library" to sharedPrefs.getBoolean("sync_library", true),
            "sync_history" to sharedPrefs.getBoolean("sync_history", true),
            "resume_playback" to sharedPrefs.getBoolean("resume_playback", true),
            "always_shuffle" to sharedPrefs.getBoolean("always_shuffle", false),
            "auto_download_playlists" to sharedPrefs.getBoolean("auto_download_playlists", false),
            "wifi_only_download" to sharedPrefs.getBoolean("wifi_only_download", false)
        )
        ref.child("settings").setValue(settings)
            .addOnFailureListener { Log.e(TAG, "Failed to sync settings", it) }
    }

    fun pushAllLocalDataToFirebaseImmediately(context: Context, onComplete: () -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onComplete()
            return
        }
        val ref = getDbRef()
        if (ref == null) {
            onComplete()
            return
        }

        // Cancel any pending debounced sync
        syncJob?.cancel()

        var remainingTasks = 9
        var finished = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!finished) {
                finished = true
                Log.d(TAG, "Sync immediately timed out, proceeding with callback")
                onComplete()
            }
        }
        handler.postDelayed(timeoutRunnable, 5000)

        fun checkComplete() {
            remainingTasks--
            if (remainingTasks <= 0 && !finished) {
                finished = true
                handler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "Sync immediately finished successfully, proceeding with callback")
                onComplete()
            }
        }

        // 1. Details
        val details = mapOf(
            "displayName" to (user.displayName ?: ""),
            "email" to (user.email ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "uid" to user.uid
        )
        ref.child("details").setValue(details).addOnCompleteListener { checkComplete() }

        // 2. Settings
        val sharedPrefs = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        val settings = mapOf(
            "sync_playlists" to sharedPrefs.getBoolean("sync_playlists", true),
            "sync_library" to sharedPrefs.getBoolean("sync_library", true),
            "sync_history" to sharedPrefs.getBoolean("sync_history", true),
            "resume_playback" to sharedPrefs.getBoolean("resume_playback", true),
            "always_shuffle" to sharedPrefs.getBoolean("always_shuffle", false),
            "auto_download_playlists" to sharedPrefs.getBoolean("auto_download_playlists", false),
            "wifi_only_download" to sharedPrefs.getBoolean("wifi_only_download", false)
        )
        ref.child("settings").setValue(settings).addOnCompleteListener { checkComplete() }

        // 3. Liked Songs
        val songIds = LikedSongsManager.getLikedSongIds(context).toList()
        ref.child("liked_songs").setValue(songIds).addOnCompleteListener { checkComplete() }

        // 4. Liked Artists
        val artists = LikedArtistsManager.getLikedArtists(context).map {
            mapOf("id" to it.id, "name" to it.name, "thumbnailUrl" to it.thumbnailUrl)
        }
        ref.child("liked_artists").setValue(artists).addOnCompleteListener { checkComplete() }

        // 5. Liked Playlists
        val playlists = LikedPlaylistsManager.getLikedPlaylists(context).map {
            mapOf("id" to it.id, "title" to it.title, "thumbnail" to it.thumbnail, "type" to it.type, "subtitle" to it.subtitle)
        }
        ref.child("liked_playlists").setValue(playlists).addOnCompleteListener { checkComplete() }

        // 6. History
        val database = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val history = database.musicDao().getPlayHistory().first().map {
                    mapOf("id" to it.id, "title" to it.title, "artistName" to it.artistName, "artistId" to it.artistId, "thumbnailUrl" to it.thumbnailUrl, "timestamp" to it.timestamp)
                }
                ref.child("history").setValue(history).addOnCompleteListener { checkComplete() }
            } catch (e: Exception) {
                checkComplete()
            }
        }

        // 7. Recommendations
        val recs = HomeCacheManager.load(context).second
        val serializedRecs = recs.map { rec ->
            mapOf(
                "seed" to mapOf("id" to rec.seed.id, "title" to rec.seed.title, "artistName" to rec.seed.artistName, "artistId" to rec.seed.artistId, "thumbnailUrl" to rec.seed.thumbnailUrl, "timestamp" to rec.seed.timestamp),
                "items" to rec.items.map { item -> item.toMap() }
            )
        }
        ref.child("recommendations").setValue(serializedRecs).addOnCompleteListener { checkComplete() }

        // 8. Playlists
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val playlistsList = database.musicDao().getPlaylists().first()
                val playlistData = playlistsList.map { pl ->
                    val songs = database.musicDao().getSongsForPlaylist(pl.id).first().map { song ->
                        mapOf("songId" to song.id, "songMetadata" to mapOf("id" to song.id, "title" to song.title, "artistName" to song.artistName, "artistId" to song.artistId, "thumbnailUrl" to song.thumbnailUrl))
                    }
                    mapOf("id" to pl.id, "name" to pl.name, "coverUri" to pl.coverUri, "createdAt" to pl.createdAt, "songs" to songs)
                }
                ref.child("playlists").setValue(playlistData).addOnCompleteListener { checkComplete() }
            } catch (e: Exception) {
                checkComplete()
            }
        }

        // 9. Liked Songs Metadata
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val historyList = database.musicDao().getPlayHistory().first()
                val metadata = mutableMapOf<String, Map<String, Any?>>()
                for (id in songIds) {
                    val matchingSong = historyList.find { it.id == id }
                    if (matchingSong != null) {
                        metadata[id] = mapOf(
                            "id" to matchingSong.id,
                            "title" to matchingSong.title,
                            "artistName" to matchingSong.artistName,
                            "artistId" to matchingSong.artistId,
                            "thumbnailUrl" to matchingSong.thumbnailUrl
                        )
                    }
                }
                if (metadata.isNotEmpty()) {
                    ref.child("liked_songs_metadata").setValue(metadata).addOnCompleteListener { checkComplete() }
                } else {
                    checkComplete()
                }
            } catch (e: Exception) {
                checkComplete()
            }
        }
    }

    fun clearAllLocalData(context: Context) {
        // 1. Clear SharedPreferences
        val prefsToClear = listOf(
            "liked_songs_prefs",
            "liked_artists_prefs",
            "liked_playlists_prefs",
            "downloaded_playlists_prefs",
            "musix_profile_settings"
        )
        for (pref in prefsToClear) {
            context.getSharedPreferences(pref, Context.MODE_PRIVATE).edit().clear().apply()
        }

        // 2. Clear Database (Room)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.getDatabase(context).clearAllTables()
                Log.d(TAG, "Local Room database cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear local Room database", e)
            }
        }
    }
}
