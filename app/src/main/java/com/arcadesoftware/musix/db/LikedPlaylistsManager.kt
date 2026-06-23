package com.arcadesoftware.musix.db

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object LikedPlaylistsManager {
    private const val PREFS_NAME = "liked_playlists_prefs"
    private const val KEY_LIKED_LIST = "liked_list"

    data class LikedPlaylist(
        val id: String,
        val title: String,
        val thumbnail: String?,
        val type: String, // "PLAYLIST" or "ALBUM"
        val subtitle: String
    )

    fun getLikedPlaylists(context: Context): List<LikedPlaylist> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_LIKED_LIST, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<LikedPlaylist>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    LikedPlaylist(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        thumbnail = obj.optString("thumbnail").takeIf { it.isNotEmpty() },
                        type = obj.getString("type"),
                        subtitle = obj.getString("subtitle")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isPlaylistLiked(context: Context, id: String): Boolean {
        return getLikedPlaylists(context).any { it.id == id }
    }

    fun toggleLikePlaylist(context: Context, playlist: LikedPlaylist) {
        val currentList = getLikedPlaylists(context).toMutableList()
        val index = currentList.indexOfFirst { it.id == playlist.id }
        if (index != -1) {
            currentList.removeAt(index)
        } else {
            currentList.add(playlist)
        }

        val jsonArray = JSONArray()
        for (item in currentList) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("thumbnail", item.thumbnail ?: "")
            obj.put("type", item.type)
            obj.put("subtitle", item.subtitle)
            jsonArray.put(obj)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIKED_LIST, jsonArray.toString())
            .apply()
        FirebaseSyncManager.syncLikedPlaylists(context)
    }

    fun saveLikedPlaylists(context: Context, list: List<LikedPlaylist>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("thumbnail", item.thumbnail ?: "")
            obj.put("type", item.type)
            obj.put("subtitle", item.subtitle)
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIKED_LIST, jsonArray.toString())
            .apply()
    }
}
