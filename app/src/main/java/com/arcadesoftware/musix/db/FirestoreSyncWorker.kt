package com.arcadesoftware.musix.db

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.arcadesoftware.musix.HomeCacheManager
import com.arcadesoftware.musix.db.entities.PlaylistEntity
import com.arcadesoftware.musix.db.entities.PlaylistSongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * WorkManager worker that pushes all local user data to Firestore.
 *
 * Scheduled on [onStop] (app goes background / swipe-away) and also used
 * as the reliable sync mechanism on sign-out. WorkManager guarantees the
 * work runs even if the process was killed before it could complete.
 */
class FirestoreSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "FirestoreSyncWorker"
        private const val WORK_NAME = "firestore_sync_all"

        /**
         * Enqueue a one-time, expedited sync work request.
         * Uses [ExistingWorkPolicy.REPLACE] so rapid successive calls
         * (e.g., multiple data changes before app closes) only run once.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FirestoreSyncWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            Log.d(TAG, "FirestoreSyncWorker enqueued")
        }
    }

    override suspend fun doWork(): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.d(TAG, "No logged-in user — skipping sync")
            return Result.success()
        }

        val ctx = applicationContext
        val fs = FirebaseFirestore.getInstance()
        val ref = fs.collection("users").document(uid)
        val db = AppDatabase.getDatabase(ctx)
        val p = ctx.getSharedPreferences("musix_profile_settings", Context.MODE_PRIVATE)

        return try {
            // ── 1. Liked Song IDs ───────────────────────────────────────────
            val ids = LikedSongsManager.getLikedSongIds(ctx).toList()
            ref.collection("liked_songs").document("ids")
                .set(mapOf("ids" to ids)).await()

            // ── 2. Liked Songs Metadata ─────────────────────────────────────
            val historyList = db.musicDao().getPlayHistory().first()
            if (ids.isNotEmpty() && historyList.isNotEmpty()) {
                var batch = fs.batch(); var count = 0
                for (id in ids) {
                    val song = historyList.find { it.id == id } ?: continue
                    batch.set(ref.collection("liked_songs_metadata").document(id), mapOf(
                        "id" to song.id, "title" to song.title,
                        "artistName" to song.artistName, "artistId" to song.artistId,
                        "thumbnailUrl" to song.thumbnailUrl
                    ))
                    count++
                    if (count % 400 == 0) { batch.commit().await(); batch = fs.batch() }
                }
                if (count % 400 != 0) batch.commit().await()
            }

            // ── 3. Liked Artists ────────────────────────────────────────────
            val artists = LikedArtistsManager.getLikedArtists(ctx)
            if (artists.isNotEmpty()) {
                var batch = fs.batch(); var count = 0
                artists.forEach { a ->
                    a.id?.let { id ->
                        batch.set(ref.collection("liked_artists").document(id),
                            mapOf("id" to id, "name" to a.name, "thumbnailUrl" to a.thumbnailUrl))
                        count++
                        if (count % 400 == 0) { batch.commit().await(); batch = fs.batch() }
                    }
                }
                if (count % 400 != 0) batch.commit().await()
            }

            // ── 4. Liked Playlists ──────────────────────────────────────────
            val likedPlaylists = LikedPlaylistsManager.getLikedPlaylists(ctx)
            if (likedPlaylists.isNotEmpty()) {
                var batch = fs.batch(); var count = 0
                likedPlaylists.forEach { pl ->
                    batch.set(ref.collection("liked_playlists").document(pl.id), mapOf(
                        "id" to pl.id, "title" to pl.title, "thumbnail" to pl.thumbnail,
                        "type" to pl.type, "subtitle" to pl.subtitle
                    ))
                    count++
                    if (count % 400 == 0) { batch.commit().await(); batch = fs.batch() }
                }
                if (count % 400 != 0) batch.commit().await()
            }

            // ── 5. Play History ─────────────────────────────────────────────
            if (p.getBoolean("sync_history", true) && historyList.isNotEmpty()) {
                var batch = fs.batch(); var count = 0
                historyList.take(200).forEach { song ->
                    batch.set(ref.collection("history").document(song.id), mapOf(
                        "id" to song.id, "title" to song.title,
                        "artistName" to song.artistName, "artistId" to song.artistId,
                        "thumbnailUrl" to song.thumbnailUrl, "timestamp" to song.timestamp
                    ))
                    count++
                    if (count % 400 == 0) { batch.commit().await(); batch = fs.batch() }
                }
                if (count % 400 != 0) batch.commit().await()
            }

            // ── 6. User Playlists ───────────────────────────────────────────
            if (p.getBoolean("sync_playlists", true)) {
                val playlists = db.musicDao().getPlaylists().first()
                playlists.forEach { playlist ->
                    val plRef = ref.collection("playlists").document(playlist.id.toString())
                    plRef.set(mapOf("id" to playlist.id.toString())).await()
                    plRef.collection("info").document("meta").set(mapOf(
                        "id" to playlist.id, "name" to playlist.name,
                        "coverUri" to playlist.coverUri, "createdAt" to playlist.createdAt
                    )).await()
                    val songs = db.musicDao().getSongsForPlaylist(playlist.id).first()
                    if (songs.isNotEmpty()) {
                        var batch = fs.batch(); var count = 0
                        songs.forEachIndexed { index, song ->
                            batch.set(plRef.collection("songs").document(song.id), mapOf(
                                "songId" to song.id, "position" to index,
                                "title" to song.title, "artistName" to song.artistName,
                                "artistId" to song.artistId, "thumbnailUrl" to song.thumbnailUrl,
                                "addedAt" to System.currentTimeMillis()
                            ))
                            count++
                            if (count % 400 == 0) { batch.commit().await(); batch = fs.batch() }
                        }
                        if (count % 400 != 0) batch.commit().await()
                    }
                }
            }

            // ── 7. Settings ─────────────────────────────────────────────────
            ref.collection("settings").document("prefs").set(mapOf(
                "sync_playlists" to p.getBoolean("sync_playlists", true),
                "sync_library" to p.getBoolean("sync_library", true),
                "sync_history" to p.getBoolean("sync_history", true),
                "resume_playback" to p.getBoolean("resume_playback", true),
                "always_shuffle" to p.getBoolean("always_shuffle", false),
                "auto_download_playlists" to p.getBoolean("auto_download_playlists", false),
                "wifi_only_download" to p.getBoolean("wifi_only_download", false)
            )).await()

            Log.d(TAG, "FirestoreSyncWorker completed successfully for uid=$uid")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "FirestoreSyncWorker failed", e)
            // Retry up to WorkManager's default retry policy
            Result.retry()
        }
    }
}
