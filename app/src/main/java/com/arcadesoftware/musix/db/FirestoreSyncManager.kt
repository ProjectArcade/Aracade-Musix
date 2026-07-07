package com.arcadesoftware.musix.db

import android.content.Context
import android.util.Log
import com.arcadesoftware.musix.db.entities.PlayHistoryEntity
import com.arcadesoftware.musix.db.entities.PlaylistEntity
import com.arcadesoftware.musix.db.entities.PlaylistSongEntity
import com.arcadesoftware.musix.ui.screens.LibraryArtist
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * FirestoreSyncManager — authoritative sync layer, replaces RTDB.
 *
 * Firestore data layout:
 * ```
 * users/{uid}/
 *   details/profile        (document)
 *   settings/prefs         (document)
 *   liked_songs/ids        (document)  → field: ids=[String]
 *   liked_songs_metadata/{songId}  (collection)
 *   liked_artists/{artistId}       (collection)
 *   liked_playlists/{id}           (collection)
 *   history/{songId}               (collection)
 *   playlists/{playlistId}/
 *     info/meta            (document)
 *     songs/{songId}       (collection)
 * ```
 */
object FirestoreSyncManager {

    private const val TAG = "FirestoreSyncManager"

    /** Track the last UID we synced for — prevents double-sync on listener re-fires. */
    var lastSyncedUid: String? = null

    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    private var lastHistorySyncMs = 0L
    private const val HISTORY_SYNC_DEBOUNCE_MS = 5 * 60 * 1000L

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fs() = FirebaseFirestore.getInstance()
    private fun uid() = FirebaseAuth.getInstance().currentUser?.uid
    private fun userRef() = uid()?.let { fs().collection("users").document(it) }

    // ── RTDB → Firestore Migration ───────────────────────────────────────────

    /**
     * Called once on login. If user doc doesn't yet exist in Firestore,
     * reads the full RTDB record, writes it to Firestore in batches,
     * then removes the RTDB node. Idempotent.
     */
    fun migrateFromRtdbIfNeeded(context: Context, onComplete: (() -> Unit)? = null) {
        val user = FirebaseAuth.getInstance().currentUser ?: run { onComplete?.invoke(); return }
        val uid = user.uid
        val userDocRef = fs().collection("users").document(uid)

        syncScope.launch {
            try {
                val firestoreSnap = userDocRef.get().await()
                // Use a sentinel field to mark the doc as initialised
                val alreadyMigrated = firestoreSnap.exists() &&
                        firestoreSnap.getBoolean("_migrated") == true

                if (alreadyMigrated) {
                    Log.d(TAG, "User $uid already in Firestore — skipping RTDB migration")
                    onComplete?.invoke()
                    return@launch
                }

                Log.d(TAG, "Starting RTDB → Firestore migration for $uid")

                val rtdbRef = FirebaseDatabase.getInstance()
                    .getReference("com_arcadesoftware_musix/user/$uid")

                val rtdbSnap = rtdbRef.get().await()

                // Write a sentinel so we never migrate twice even if the process is killed mid-way
                userDocRef.set(mapOf("_migrated" to true, "uid" to uid), SetOptions.merge()).await()

                if (!rtdbSnap.exists()) {
                    Log.d(TAG, "No RTDB data for $uid — writing fresh profile")
                    userDocRef.collection("details").document("profile").set(
                        mapOf(
                            "displayName" to (user.displayName ?: ""),
                            "email" to (user.email ?: ""),
                            "photoUrl" to (user.photoUrl?.toString() ?: ""),
                            "uid" to uid,
                            "createdAt" to Timestamp.now()
                        )
                    ).await()
                    onComplete?.invoke()
                    return@launch
                }

                // ── Migrate: details ──
                val detSnap = rtdbSnap.child("details")
                if (detSnap.exists()) {
                    val map = (detSnap.value as? Map<*, *>)
                        ?.entries?.associate { (k, v) -> k.toString() to v }
                        ?.toMutableMap<String, Any?>() ?: mutableMapOf()
                    map["migratedFromRtdb"] = true
                    map["createdAt"] = Timestamp.now()
                    userDocRef.collection("details").document("profile").set(map).await()
                }

                // ── Migrate: settings ──
                val setSnap = rtdbSnap.child("settings")
                if (setSnap.exists()) {
                    val map = (setSnap.value as? Map<*, *>)
                        ?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
                    userDocRef.collection("settings").document("prefs").set(map).await()
                }

                // ── Migrate: liked_songs ids ──
                val likedIdsSnap = rtdbSnap.child("liked_songs")
                if (likedIdsSnap.exists()) {
                    val ids = likedIdsSnap.children.mapNotNull { it.value as? String }
                    if (ids.isNotEmpty()) {
                        userDocRef.collection("liked_songs").document("ids")
                            .set(mapOf("ids" to ids)).await()
                    }
                }

                // ── Migrate: liked_songs_metadata ──
                migrateBatchCollection(
                    snap = rtdbSnap.child("liked_songs_metadata"),
                    targetCollection = userDocRef.collection("liked_songs_metadata"),
                    keyExtractor = { child -> child.child("id").value as? String }
                ) { child ->
                    mapOf(
                        "id" to (child.child("id").value as? String ?: ""),
                        "title" to (child.child("title").value as? String ?: ""),
                        "artistName" to (child.child("artistName").value as? String ?: ""),
                        "artistId" to (child.child("artistId").value as? String),
                        "thumbnailUrl" to (child.child("thumbnailUrl").value as? String ?: "")
                    )
                }

                // ── Migrate: liked_artists ──
                migrateBatchCollection(
                    snap = rtdbSnap.child("liked_artists"),
                    targetCollection = userDocRef.collection("liked_artists"),
                    keyExtractor = { child -> child.child("id").value as? String }
                ) { child ->
                    mapOf(
                        "id" to (child.child("id").value as? String ?: ""),
                        "name" to (child.child("name").value as? String ?: "Unknown Artist"),
                        "thumbnailUrl" to (child.child("thumbnailUrl").value as? String)
                    )
                }

                // ── Migrate: liked_playlists ──
                migrateBatchCollection(
                    snap = rtdbSnap.child("liked_playlists"),
                    targetCollection = userDocRef.collection("liked_playlists"),
                    keyExtractor = { child -> child.child("id").value as? String }
                ) { child ->
                    mapOf(
                        "id" to (child.child("id").value as? String ?: ""),
                        "title" to (child.child("title").value as? String ?: ""),
                        "thumbnail" to (child.child("thumbnail").value as? String),
                        "type" to (child.child("type").value as? String ?: "PLAYLIST"),
                        "subtitle" to (child.child("subtitle").value as? String ?: "")
                    )
                }

                // ── Migrate: history ──
                migrateBatchCollection(
                    snap = rtdbSnap.child("history"),
                    targetCollection = userDocRef.collection("history"),
                    keyExtractor = { child -> child.child("id").value as? String }
                ) { child ->
                    mapOf(
                        "id" to (child.child("id").value as? String ?: ""),
                        "title" to (child.child("title").value as? String ?: ""),
                        "artistName" to (child.child("artistName").value as? String ?: ""),
                        "artistId" to (child.child("artistId").value as? String),
                        "thumbnailUrl" to (child.child("thumbnailUrl").value as? String ?: ""),
                        "timestamp" to ((child.child("timestamp").value as? Number)?.toLong() ?: System.currentTimeMillis())
                    )
                }

                // ── Migrate: playlists ──
                val playlistsSnap = rtdbSnap.child("playlists")
                if (playlistsSnap.exists()) {
                    playlistsSnap.children.forEach { plChild ->
                        val plId = (plChild.child("id").value as? Number)?.toLong()?.toString()
                            ?: plChild.key ?: return@forEach
                        val plDocRef = userDocRef.collection("playlists").document(plId)
                        plDocRef.set(mapOf("id" to plId)).await()
                        plDocRef.collection("info").document("meta").set(
                            mapOf(
                                "id" to plId,
                                "name" to (plChild.child("name").value as? String ?: "Untitled"),
                                "coverUri" to (plChild.child("coverUri").value as? String),
                                "createdAt" to ((plChild.child("createdAt").value as? Number)?.toLong() ?: System.currentTimeMillis())
                            )
                        ).await()

                        migrateBatchCollection(
                            snap = plChild.child("songs"),
                            targetCollection = plDocRef.collection("songs"),
                            keyExtractor = { child -> child.child("songId").value as? String }
                        ) { child ->
                            val meta = child.child("songMetadata")
                            mapOf(
                                "songId" to (child.child("songId").value as? String ?: ""),
                                "position" to ((child.child("position").value as? Number)?.toInt() ?: 0),
                                "title" to (meta.child("title").value as? String ?: ""),
                                "artistName" to (meta.child("artistName").value as? String ?: ""),
                                "artistId" to (meta.child("artistId").value as? String),
                                "thumbnailUrl" to (meta.child("thumbnailUrl").value as? String ?: ""),
                                "addedAt" to System.currentTimeMillis()
                            )
                        }
                    }
                }

                Log.d(TAG, "RTDB → Firestore migration complete for $uid")

                // ── Delete RTDB record ──
                rtdbRef.removeValue().await()
                Log.d(TAG, "RTDB record deleted for $uid")

                onComplete?.invoke()

            } catch (e: Exception) {
                Log.e(TAG, "Migration failed", e)
                onComplete?.invoke()
            }
        }
    }

    /** Batches writes from an RTDB DataSnapshot into a Firestore collection. */
    private suspend fun migrateBatchCollection(
        snap: com.google.firebase.database.DataSnapshot,
        targetCollection: com.google.firebase.firestore.CollectionReference,
        keyExtractor: (com.google.firebase.database.DataSnapshot) -> String?,
        dataBuilder: (com.google.firebase.database.DataSnapshot) -> Map<String, Any?>
    ) {
        if (!snap.exists()) return
        var batch = fs().batch()
        var count = 0
        snap.children.forEach { child ->
            val docId = keyExtractor(child) ?: return@forEach
            batch.set(targetCollection.document(docId), dataBuilder(child))
            count++
            if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
        }
        if (count % 400 != 0) batch.commit().await()
    }

    // ── Scheduled sync ───────────────────────────────────────────────────────

    fun schedulePushAllLocalDataToFirestore(context: Context) {
        val appCtx = context.applicationContext
        syncJob?.cancel()
        syncJob = syncScope.launch {
            kotlinx.coroutines.delay(5 * 60 * 1000L)
            pushAllLocalDataToFirestore(appCtx)
        }
    }

    // ── Individual sync methods ───────────────────────────────────────────────

    fun syncUserDetails(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val ref = userRef() ?: return
        syncScope.launch {
            try {
                ref.collection("details").document("profile").set(
                    mapOf(
                        "displayName" to (user.displayName ?: ""),
                        "email" to (user.email ?: ""),
                        "photoUrl" to (user.photoUrl?.toString() ?: ""),
                        "uid" to user.uid,
                        "lastSeen" to Timestamp.now()
                    ), SetOptions.merge()
                ).await()
            } catch (e: Exception) { Log.e(TAG, "syncUserDetails failed", e) }
        }
    }

    private var syncSettingsJob: Job? = null

    fun syncSettings(context: Context) {
        val ref = userRef() ?: return
        val p = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        
        // Cancel any pending syncSettings job to debounce rapid toggling by the user
        syncSettingsJob?.cancel()
        syncSettingsJob = syncScope.launch {
            try {
                // Coalesce updates by waiting 2 seconds before executing the write
                kotlinx.coroutines.delay(2000L)
                
                ref.collection("settings").document("prefs").set(mapOf(
                    "sync_playlists" to p.getBoolean("sync_playlists", true),
                    "sync_library" to p.getBoolean("sync_library", true),
                    "sync_history" to p.getBoolean("sync_history", true),
                    "resume_playback" to p.getBoolean("resume_playback", true),
                    "always_shuffle" to p.getBoolean("always_shuffle", false),
                    "auto_download_playlists" to p.getBoolean("auto_download_playlists", false),
                    "wifi_only_download" to p.getBoolean("wifi_only_download", false)
                )).await()
                Log.d(TAG, "syncSettings: settings successfully synced to Firestore")
            } catch (e: Exception) { Log.e(TAG, "syncSettings failed", e) }
        }
    }

    suspend fun syncLikedSongsSuspend(context: Context) {
        val p = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!p.getBoolean("sync_library", true)) return
        val ref = userRef() ?: return
        val ids = LikedSongsManager.getLikedSongIds(context).toList()
        try {
            ref.collection("liked_songs").document("ids").set(mapOf("ids" to ids)).await()
            val historyList = AppDatabase.getDatabase(context).musicDao().getPlayHistory().first()
            if (historyList.isNotEmpty()) {
                var batch = fs().batch(); var count = 0
                for (id in ids) {
                    val song = historyList.find { it.id == id } ?: continue
                    batch.set(ref.collection("liked_songs_metadata").document(id), mapOf(
                        "id" to song.id, "title" to song.title,
                        "artistName" to song.artistName, "artistId" to song.artistId,
                        "thumbnailUrl" to song.thumbnailUrl
                    ))
                    count++
                    if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
                }
                if (count % 400 != 0) batch.commit().await()
            }
        } catch (e: Exception) { Log.e(TAG, "syncLikedSongsSuspend failed", e) }
    }

    fun syncLikedSongs(context: Context) {
        syncScope.launch { syncLikedSongsSuspend(context) }
    }

    suspend fun syncLikedArtistsSuspend(context: Context) {
        val p = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!p.getBoolean("sync_library", true)) return
        val ref = userRef() ?: return
        val artists = LikedArtistsManager.getLikedArtists(context)
        try {
            var batch = fs().batch(); var count = 0
            artists.forEach { a ->
                a.id?.let { id ->
                    batch.set(ref.collection("liked_artists").document(id),
                        mapOf("id" to id, "name" to a.name, "thumbnailUrl" to a.thumbnailUrl))
                }
                count++
                if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
            }
            if (count % 400 != 0) batch.commit().await()
        } catch (e: Exception) { Log.e(TAG, "syncLikedArtistsSuspend failed", e) }
    }

    fun syncLikedArtists(context: Context) {
        syncScope.launch { syncLikedArtistsSuspend(context) }
    }

    suspend fun syncLikedPlaylistsSuspend(context: Context) {
        val p = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!p.getBoolean("sync_library", true)) return
        val ref = userRef() ?: return
        val playlists = LikedPlaylistsManager.getLikedPlaylists(context)
        try {
            var batch = fs().batch(); var count = 0
            playlists.forEach { pl ->
                batch.set(ref.collection("liked_playlists").document(pl.id), mapOf(
                    "id" to pl.id, "title" to pl.title, "thumbnail" to pl.thumbnail,
                    "type" to pl.type, "subtitle" to pl.subtitle
                ))
                count++
                if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
            }
            if (count % 400 != 0) batch.commit().await()
        } catch (e: Exception) { Log.e(TAG, "syncLikedPlaylistsSuspend failed", e) }
    }

    fun syncLikedPlaylists(context: Context) {
        syncScope.launch { syncLikedPlaylistsSuspend(context) }
    }

    suspend fun syncHistorySuspend(context: Context) {
        val p = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!p.getBoolean("sync_history", true)) return
        val ref = userRef() ?: return
        try {
            val history = AppDatabase.getDatabase(context).musicDao().getPlayHistory().first()
            var batch = fs().batch(); var count = 0
            history.take(200).forEach { song ->
                batch.set(ref.collection("history").document(song.id), mapOf(
                    "id" to song.id, "title" to song.title,
                    "artistName" to song.artistName, "artistId" to song.artistId,
                    "thumbnailUrl" to song.thumbnailUrl, "timestamp" to song.timestamp
                ))
                count++
                if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
            }
            if (count % 400 != 0) batch.commit().await()
        } catch (e: Exception) { Log.e(TAG, "syncHistorySuspend failed", e) }
    }

    fun syncHistory(context: Context) {
        val p = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!p.getBoolean("sync_history", true)) return
        val now = System.currentTimeMillis()
        if (now - lastHistorySyncMs < HISTORY_SYNC_DEBOUNCE_MS) {
            Log.d(TAG, "syncHistory skipped — debounce active"); return
        }
        lastHistorySyncMs = now
        syncScope.launch { syncHistorySuspend(context) }
    }

    /**
     * Suspend version — call this directly inside the same coroutine as your DB write
     * so the sync always sees the committed data. Never races against the insert.
     */
    suspend fun syncPlaylistsSuspend(context: Context) {
        val p = context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)
        if (!p.getBoolean("sync_playlists", true)) return
        val ref = userRef() ?: return
        val db = AppDatabase.getDatabase(context)
        try {
            val playlists = db.musicDao().getPlaylists().first()
            val localIds = playlists.map { it.id.toString() }.toSet()

            // Fetch current remote playlists to check for deleted ones
            try {
                val remoteDocs = ref.collection("playlists").get().await().documents
                remoteDocs.forEach { doc ->
                    if (!localIds.contains(doc.id)) {
                        // This playlist was deleted locally, delete from Firestore
                        // We must delete the subcollections first or delete them as well
                        val plRef = ref.collection("playlists").document(doc.id)
                        
                        // Delete meta info
                        try {
                            plRef.collection("info").document("meta").delete().await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete remote info/meta for deleted playlist ${doc.id}", e)
                        }

                        // Delete songs collection
                        try {
                            val songDocs = plRef.collection("songs").get().await().documents
                            if (songDocs.isNotEmpty()) {
                                var batch = fs().batch()
                                songDocs.forEachIndexed { index, songDoc ->
                                    batch.delete(plRef.collection("songs").document(songDoc.id))
                                    if ((index + 1) % 400 == 0) {
                                        batch.commit().await()
                                        batch = fs().batch()
                                    }
                                }
                                batch.commit().await()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete remote songs for deleted playlist ${doc.id}", e)
                        }

                        // Delete the parent document
                        plRef.delete().await()
                        Log.d(TAG, "syncPlaylistsSuspend: Deleted remote playlist ${doc.id}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch remote playlists for deletion sync", e)
            }

            playlists.forEach { playlist ->
                val plRef = ref.collection("playlists").document(playlist.id.toString())
                plRef.set(mapOf("id" to playlist.id.toString())).await()
                plRef.collection("info").document("meta").set(mapOf(
                    "id" to playlist.id, "name" to playlist.name,
                    "coverUri" to playlist.coverUri, "createdAt" to playlist.createdAt
                )).await()
                val songs = db.musicDao().getSongsForPlaylist(playlist.id).first()
                if (songs.isNotEmpty()) {
                    var batch = fs().batch(); var count = 0
                    songs.forEachIndexed { index, song ->
                        batch.set(plRef.collection("songs").document(song.id), mapOf(
                            "songId" to song.id, "position" to index,
                            "title" to song.title, "artistName" to song.artistName,
                            "artistId" to song.artistId, "thumbnailUrl" to song.thumbnailUrl,
                            "addedAt" to System.currentTimeMillis()
                        ))
                        count++
                        if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
                    }
                    if (count % 400 != 0) batch.commit().await()
                }
            }
            Log.d(TAG, "syncPlaylistsSuspend: synced ${playlists.size} playlist(s)")
        } catch (e: Exception) { Log.e(TAG, "syncPlaylistsSuspend failed", e) }
    }

    /** Fire-and-forget wrapper for call sites that don't have a coroutine context. */
    fun syncPlaylists(context: Context) {
        syncScope.launch { syncPlaylistsSuspend(context) }
    }

    // ── Fetch & merge from Firestore ──────────────────────────────────────────

    fun fetchAndMergeFirestoreData(context: Context, onComplete: (() -> Unit)? = null) {
        val ref = userRef() ?: run { onComplete?.invoke(); return }
        syncScope.launch {
            try {
                val db = AppDatabase.getDatabase(context)

                // Settings
                val settingsDoc = ref.collection("settings").document("prefs").get().await()
                if (settingsDoc.exists()) {
                    val d = settingsDoc.data ?: emptyMap()
                    context.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE).edit().apply {
                        (d["sync_playlists"] as? Boolean)?.let { putBoolean("sync_playlists", it) }
                        (d["sync_library"] as? Boolean)?.let { putBoolean("sync_library", it) }
                        (d["sync_history"] as? Boolean)?.let { putBoolean("sync_history", it) }
                        (d["resume_playback"] as? Boolean)?.let { putBoolean("resume_playback", it) }
                        (d["always_shuffle"] as? Boolean)?.let { putBoolean("always_shuffle", it) }
                        (d["auto_download_playlists"] as? Boolean)?.let { putBoolean("auto_download_playlists", it) }
                        (d["wifi_only_download"] as? Boolean)?.let { putBoolean("wifi_only_download", it) }
                    }.apply()
                }

                // Liked Song IDs
                val likedIdsDoc = ref.collection("liked_songs").document("ids").get().await()
                if (likedIdsDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val remoteIds = (likedIdsDoc.get("ids") as? List<String>)?.toSet() ?: emptySet()
                    LikedSongsManager.saveLikedSongIds(context, LikedSongsManager.getLikedSongIds(context) + remoteIds)
                }

                // Liked Songs Metadata → Room
                ref.collection("liked_songs_metadata").get().await().documents.forEach { doc ->
                    val id = doc.getString("id") ?: return@forEach
                    val title = doc.getString("title") ?: return@forEach
                    val artistName = doc.getString("artistName") ?: return@forEach
                    db.musicDao().insertPlayHistory(PlayHistoryEntity(
                        id, title, artistName, doc.getString("artistId"), doc.getString("thumbnailUrl") ?: ""
                    ))
                }

                // Liked Artists
                val remoteArtists = ref.collection("liked_artists").get().await().documents.mapNotNull { doc ->
                    val id = doc.getString("id") ?: return@mapNotNull null
                    LibraryArtist(id, doc.getString("name") ?: "Unknown Artist", doc.getString("thumbnailUrl"), emptyList())
                }
                LikedArtistsManager.saveLikedArtists(
                    context,
                    (LikedArtistsManager.getLikedArtists(context) + remoteArtists).distinctBy { it.id }
                )

                // Liked Playlists
                val remoteLikedPlaylists = ref.collection("liked_playlists").get().await().documents.mapNotNull { doc ->
                    val id = doc.getString("id") ?: return@mapNotNull null
                    LikedPlaylistsManager.LikedPlaylist(
                        id, doc.getString("title") ?: "Untitled",
                        doc.getString("thumbnail"), doc.getString("type") ?: "PLAYLIST",
                        doc.getString("subtitle") ?: ""
                    )
                }
                LikedPlaylistsManager.saveLikedPlaylists(
                    context,
                    (LikedPlaylistsManager.getLikedPlaylists(context) + remoteLikedPlaylists).distinctBy { it.id }
                )

                // History → Room
                ref.collection("history")
                    .orderBy("timestamp", Query.Direction.DESCENDING).limit(200)
                    .get().await().documents.forEach { doc ->
                        val id = doc.getString("id") ?: return@forEach
                        val title = doc.getString("title") ?: return@forEach
                        val artistName = doc.getString("artistName") ?: return@forEach
                        db.musicDao().insertPlayHistory(PlayHistoryEntity(
                            id, title, artistName, doc.getString("artistId"),
                            doc.getString("thumbnailUrl") ?: "",
                            doc.getLong("timestamp") ?: System.currentTimeMillis()
                        ))
                    }

                // User Playlists
                ref.collection("playlists").get().await().documents.forEach { plDoc ->
                    // Parse playlist ID — use 0 only as a last resort (Room will auto-assign)
                    val plId = plDoc.id.toLongOrNull() ?: 0L

                    // Fetch info/meta sub-doc; fall back to parent doc fields so we never
                    // silently skip a playlist due to a missing sub-doc.
                    val infoDoc = try {
                        ref.collection("playlists").document(plDoc.id)
                            .collection("info").document("meta").get().await()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not fetch info/meta for playlist ${plDoc.id}", e)
                        null
                    }

                    // Resolve name: info/meta → parent doc → "Untitled" (never skip)
                    val name = infoDoc?.getString("name")
                        ?: (plDoc.get("name") as? String)
                        ?: "Untitled"
                    val coverUri = infoDoc?.getString("coverUri")
                        ?: (plDoc.get("coverUri") as? String)
                    val createdAt = infoDoc?.getLong("createdAt")
                        ?: (plDoc.getLong("createdAt"))
                        ?: System.currentTimeMillis()

                    // Insert playlist; IGNORE strategy returns -1 if already exists
                    val insertedId = db.musicDao().insertPlaylist(
                        PlaylistEntity(id = plId, name = name, coverUri = coverUri, createdAt = createdAt)
                    )
                    // If Room returned -1 (duplicate), use the stored plId as the effective id
                    val effectiveId = if (insertedId == -1L) plId else insertedId

                    // Fetch and insert all songs for this playlist
                    try {
                        ref.collection("playlists").document(plDoc.id)
                            .collection("songs").orderBy("position").get().await()
                            .documents.forEachIndexed { index, songDoc ->
                                val songId = songDoc.getString("songId") ?: return@forEachIndexed
                                // Ensure song metadata exists in play_history for the JOIN query
                                db.musicDao().insertPlayHistory(PlayHistoryEntity(
                                    songId,
                                    songDoc.getString("title") ?: "Unknown",
                                    songDoc.getString("artistName") ?: "Unknown",
                                    songDoc.getString("artistId"),
                                    songDoc.getString("thumbnailUrl") ?: ""
                                ))
                                if (db.musicDao().isSongInPlaylist(effectiveId, songId) == 0) {
                                    db.musicDao().insertPlaylistSong(PlaylistSongEntity(
                                        playlistId = effectiveId, songId = songId, position = index,
                                        addedAt = songDoc.getLong("addedAt") ?: System.currentTimeMillis()
                                    ))
                                }
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore songs for playlist ${plDoc.id}", e)
                    }
                }

                Log.d(TAG, "Firestore merge complete")
            } catch (e: Exception) {
                Log.e(TAG, "fetchAndMergeFirestoreData failed", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun pushAllLocalDataToFirestore(context: Context) {
        syncUserDetails(context)
        syncSettings(context)
        syncLikedSongs(context)
        syncLikedArtists(context)
        syncLikedPlaylists(context)
        syncHistory(context)
        syncPlaylists(context)
    }

    /**
     * Pushes all local data to Firestore and calls [onComplete] once every task finishes
     * (or after a 5-second timeout guard). Use this when you need to ensure data is safely
     * uploaded before wiping local storage (e.g., on sign-out).
     */
    fun pushAllLocalDataToFirestoreImmediately(context: Context, onComplete: () -> Unit) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (user == null) { onComplete(); return }
        val ref = userRef()
        if (ref == null) { onComplete(); return }

        syncJob?.cancel()

        // 7 tasks: details, settings, liked_songs, liked_artists, liked_playlists, history, playlists
        var remainingTasks = 7
        var finished = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!finished) {
                finished = true
                Log.d(TAG, "pushAllLocalDataToFirestoreImmediately timed out — proceeding")
                onComplete()
            }
        }
        handler.postDelayed(timeoutRunnable, 5000)

        fun checkComplete() {
            remainingTasks--
            if (remainingTasks <= 0 && !finished) {
                finished = true
                handler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "pushAllLocalDataToFirestoreImmediately finished successfully")
                onComplete()
            }
        }

        val p = context.getSharedPreferences("musix_profile_settings", android.content.Context.MODE_PRIVATE)

        // 1. Details
        syncScope.launch {
            try {
                ref.collection("details").document("profile").set(mapOf(
                    "displayName" to (user.displayName ?: ""),
                    "email" to (user.email ?: ""),
                    "photoUrl" to (user.photoUrl?.toString() ?: ""),
                    "uid" to user.uid,
                    "lastSeen" to com.google.firebase.Timestamp.now()
                ), com.google.firebase.firestore.SetOptions.merge()).await()
            } catch (e: Exception) { Log.e(TAG, "immediate push details failed", e) }
            finally { android.os.Handler(android.os.Looper.getMainLooper()).post { checkComplete() } }
        }

        // 2. Settings
        syncScope.launch {
            try {
                ref.collection("settings").document("prefs").set(mapOf(
                    "sync_playlists" to p.getBoolean("sync_playlists", true),
                    "sync_library" to p.getBoolean("sync_library", true),
                    "sync_history" to p.getBoolean("sync_history", true),
                    "resume_playback" to p.getBoolean("resume_playback", true),
                    "always_shuffle" to p.getBoolean("always_shuffle", false),
                    "auto_download_playlists" to p.getBoolean("auto_download_playlists", false),
                    "wifi_only_download" to p.getBoolean("wifi_only_download", false)
                )).await()
            } catch (e: Exception) { Log.e(TAG, "immediate push settings failed", e) }
            finally { android.os.Handler(android.os.Looper.getMainLooper()).post { checkComplete() } }
        }

        // 3. Liked Songs IDs + metadata
        syncScope.launch {
            try {
                val ids = LikedSongsManager.getLikedSongIds(context).toList()
                ref.collection("liked_songs").document("ids").set(mapOf("ids" to ids)).await()
                val historyList = AppDatabase.getDatabase(context).musicDao().getPlayHistory().first()
                if (historyList.isNotEmpty()) {
                    var batch = fs().batch(); var count = 0
                    for (id in ids) {
                        val song = historyList.find { it.id == id } ?: continue
                        batch.set(ref.collection("liked_songs_metadata").document(id), mapOf(
                            "id" to song.id, "title" to song.title,
                            "artistName" to song.artistName, "artistId" to song.artistId,
                            "thumbnailUrl" to song.thumbnailUrl
                        ))
                        count++
                        if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
                    }
                    if (count % 400 != 0) batch.commit().await()
                }
            } catch (e: Exception) { Log.e(TAG, "immediate push liked_songs failed", e) }
            finally { android.os.Handler(android.os.Looper.getMainLooper()).post { checkComplete() } }
        }

        // 4. Liked Artists
        syncScope.launch {
            try {
                val artists = LikedArtistsManager.getLikedArtists(context)
                var batch = fs().batch(); var count = 0
                artists.forEach { a ->
                    a.id?.let { id ->
                        batch.set(ref.collection("liked_artists").document(id),
                            mapOf("id" to id, "name" to a.name, "thumbnailUrl" to a.thumbnailUrl))
                    }
                    count++
                    if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
                }
                if (count % 400 != 0) batch.commit().await()
            } catch (e: Exception) { Log.e(TAG, "immediate push liked_artists failed", e) }
            finally { android.os.Handler(android.os.Looper.getMainLooper()).post { checkComplete() } }
        }

        // 5. Liked Playlists
        syncScope.launch {
            try {
                val playlists = LikedPlaylistsManager.getLikedPlaylists(context)
                var batch = fs().batch(); var count = 0
                playlists.forEach { pl ->
                    batch.set(ref.collection("liked_playlists").document(pl.id), mapOf(
                        "id" to pl.id, "title" to pl.title, "thumbnail" to pl.thumbnail,
                        "type" to pl.type, "subtitle" to pl.subtitle
                    ))
                    count++
                    if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
                }
                if (count % 400 != 0) batch.commit().await()
            } catch (e: Exception) { Log.e(TAG, "immediate push liked_playlists failed", e) }
            finally { android.os.Handler(android.os.Looper.getMainLooper()).post { checkComplete() } }
        }

        // 6. History
        syncScope.launch {
            try {
                val history = AppDatabase.getDatabase(context).musicDao().getPlayHistory().first()
                var batch = fs().batch(); var count = 0
                history.take(200).forEach { song ->
                    batch.set(ref.collection("history").document(song.id), mapOf(
                        "id" to song.id, "title" to song.title,
                        "artistName" to song.artistName, "artistId" to song.artistId,
                        "thumbnailUrl" to song.thumbnailUrl, "timestamp" to song.timestamp
                    ))
                    count++
                    if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
                }
                if (count % 400 != 0) batch.commit().await()
            } catch (e: Exception) { Log.e(TAG, "immediate push history failed", e) }
            finally { android.os.Handler(android.os.Looper.getMainLooper()).post { checkComplete() } }
        }

        // 7. User Playlists
        syncScope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val playlists = db.musicDao().getPlaylists().first()
                playlists.forEach { playlist ->
                    val plRef = ref.collection("playlists").document(playlist.id.toString())
                    plRef.set(mapOf("id" to playlist.id.toString())).await()
                    plRef.collection("info").document("meta").set(mapOf(
                        "id" to playlist.id, "name" to playlist.name,
                        "coverUri" to playlist.coverUri, "createdAt" to playlist.createdAt
                    )).await()
                    val songs = db.musicDao().getSongsForPlaylist(playlist.id).first()
                    var batch = fs().batch(); var count = 0
                    songs.forEachIndexed { index, song ->
                        batch.set(plRef.collection("songs").document(song.id), mapOf(
                            "songId" to song.id, "position" to index,
                            "title" to song.title, "artistName" to song.artistName,
                            "artistId" to song.artistId, "thumbnailUrl" to song.thumbnailUrl,
                            "addedAt" to System.currentTimeMillis()
                        ))
                        count++
                        if (count % 400 == 0) { batch.commit().await(); batch = fs().batch() }
                    }
                    if (count % 400 != 0) batch.commit().await()
                }
            } catch (e: Exception) { Log.e(TAG, "immediate push playlists failed", e) }
            finally { android.os.Handler(android.os.Looper.getMainLooper()).post { checkComplete() } }
        }
    }

    fun clearAllLocalData(context: Context) {
        listOf(
            "liked_songs_prefs", "liked_artists_prefs",
            "liked_playlists_prefs", "downloaded_playlists_prefs", "musix_profile_settings"
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply() }
        syncScope.launch {
            try { AppDatabase.getDatabase(context).clearAllTables() }
            catch (e: Exception) { Log.e(TAG, "clearAllTables failed", e) }
        }
    }
}
