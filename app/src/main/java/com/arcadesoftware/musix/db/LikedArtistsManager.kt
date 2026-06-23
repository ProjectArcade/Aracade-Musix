package com.arcadesoftware.musix.db

import android.content.Context
import com.arcadesoftware.musix.ui.screens.LibraryArtist

object LikedArtistsManager {
    private const val PREFS_NAME = "liked_artists_prefs"
    private const val KEY_LIKED_IDS = "liked_artist_ids"
    private const val PREFIX_NAME = "artist_name_"
    private const val PREFIX_THUMBNAIL = "artist_thumbnail_"

    fun isArtistLiked(context: Context, artistId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_LIKED_IDS, null) ?: return false
        return set.contains(artistId)
    }

    fun getLikedArtistIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LIKED_IDS, null)?.toSet() ?: emptySet()
    }

    fun toggleLikeArtist(context: Context, artistId: String, name: String, thumbnailUrl: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_LIKED_IDS, null)?.toMutableSet() ?: mutableSetOf()
        val isNowLiked = if (set.contains(artistId)) {
            set.remove(artistId)
            prefs.edit().remove(PREFIX_NAME + artistId).remove(PREFIX_THUMBNAIL + artistId).apply()
            false
        } else {
            set.add(artistId)
            val editor = prefs.edit()
            editor.putString(PREFIX_NAME + artistId, name)
            if (thumbnailUrl != null) {
                editor.putString(PREFIX_THUMBNAIL + artistId, thumbnailUrl)
            }
            editor.apply()
            true
        }
        prefs.edit().putStringSet(KEY_LIKED_IDS, set).apply()
        FirebaseSyncManager.syncLikedArtists(context)
        return isNowLiked
    }

    fun getLikedArtists(context: Context): List<LibraryArtist> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_LIKED_IDS, null) ?: return emptyList()
        return ids.map { id ->
            val name = prefs.getString(PREFIX_NAME + id, "Unknown Artist") ?: "Unknown Artist"
            val thumbnailUrl = prefs.getString(PREFIX_THUMBNAIL + id, null)
            LibraryArtist(
                id = id,
                name = name,
                thumbnailUrl = thumbnailUrl,
                songs = emptyList()
            )
        }.sortedBy { it.name }
    }

    fun saveLikedArtists(context: Context, list: List<LibraryArtist>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = list.map { it.id }.toSet()
        val editor = prefs.edit()
        editor.putStringSet(KEY_LIKED_IDS, ids)
        for (artist in list) {
            editor.putString(PREFIX_NAME + artist.id, artist.name)
            if (artist.thumbnailUrl != null) {
                editor.putString(PREFIX_THUMBNAIL + artist.id, artist.thumbnailUrl)
            } else {
                editor.remove(PREFIX_THUMBNAIL + artist.id)
            }
        }
        editor.apply()
    }
}
