package com.arcadesoftware.musix.db

import android.content.Context

object LikedSongsManager {
    private const val PREFS_NAME = "liked_songs_prefs"
    private const val KEY_LIKED_IDS = "liked_ids"

    fun isSongLiked(context: Context, songId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_LIKED_IDS, null) ?: return false
        return set.contains(songId)
    }

    fun getLikedSongIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LIKED_IDS, null)?.toSet() ?: emptySet()
    }

    fun saveLikedSongIds(context: Context, ids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_LIKED_IDS, ids).apply()
    }

    fun toggleLikeSong(context: Context, songId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_LIKED_IDS, null)?.toMutableSet() ?: mutableSetOf()
        val isNowLiked = if (set.contains(songId)) {
            set.remove(songId)
            false
        } else {
            set.add(songId)
            true
        }
        prefs.edit().putStringSet(KEY_LIKED_IDS, set).apply()
        FirebaseSyncManager.syncLikedSongs(context)
        return isNowLiked
    }
}
