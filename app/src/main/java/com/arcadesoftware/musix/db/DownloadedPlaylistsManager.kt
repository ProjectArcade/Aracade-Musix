package com.arcadesoftware.musix.db

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DownloadedPlaylistsManager {
    private const val PREFS_NAME = "downloaded_playlists_prefs"
    private const val KEY_DOWNLOADED_LIST = "downloaded_list"

    data class DownloadedPlaylist(
        val id: String,
        val title: String,
        val thumbnail: String?,
        val type: String, // "PLAYLIST" or "ALBUM"
        val subtitle: String
    )

    fun getDownloadedPlaylists(context: Context): List<DownloadedPlaylist> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DOWNLOADED_LIST, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<DownloadedPlaylist>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    DownloadedPlaylist(
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

    fun isPlaylistDownloaded(context: Context, id: String): Boolean {
        return getDownloadedPlaylists(context).any { it.id == id }
    }

    fun addDownloadedPlaylist(context: Context, playlist: DownloadedPlaylist) {
        val currentList = getDownloadedPlaylists(context).toMutableList()
        if (currentList.none { it.id == playlist.id }) {
            currentList.add(playlist)
            saveList(context, currentList)
        }
    }

    fun removeDownloadedPlaylist(context: Context, id: String) {
        val currentList = getDownloadedPlaylists(context).toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList.removeAt(index)
            saveList(context, currentList)
        }
    }

    private fun saveList(context: Context, list: List<DownloadedPlaylist>) {
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
            .putString(KEY_DOWNLOADED_LIST, jsonArray.toString())
            .apply()
    }
}
