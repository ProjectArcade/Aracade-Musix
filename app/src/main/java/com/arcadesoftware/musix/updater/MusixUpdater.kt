package com.arcadesoftware.musix.updater

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

object MusixUpdater {
    const val PREFS_NAME = "musix_settings"
    const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
    const val KEY_UPDATE_AVAILABLE = "update_available"

    fun getAutoUpdateCheckSetting(context: Context): Boolean {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true)
    }

    fun setAutoUpdateCheckSetting(context: Context, enabled: Boolean) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, enabled).apply()
    }

    fun saveUpdateAvailableState(context: Context, available: Boolean) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean(KEY_UPDATE_AVAILABLE, available).apply()
    }

    fun getUpdateAvailableState(context: Context): Boolean {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean(KEY_UPDATE_AVAILABLE, false)
    }

    suspend fun checkForUpdate(
        context: Context,
        onUpdateFound: (version: String, description: String, apkUrl: String) -> Unit,
        onNoUpdate: () -> Unit
    ) {
        if (!getAutoUpdateCheckSetting(context)) return

        withContext(Dispatchers.IO) {
            try {
                // Using Echo-Music repository as placeholder for Musix auto update test
                val url = URL("https://api.github.com/repos/EchoMusicApp/Echo-Music/releases/latest")
                val json = url.openStream().bufferedReader().use { it.readText() }
                val targetRelease = JSONObject(json)
                
                val currentVersion = "1.0" // App version
                val targetTagName = targetRelease.getString("tag_name")
                val targetClean = targetTagName.removePrefix("b").removePrefix("v").trim()
                
                if (currentVersion != targetClean) {
                    val description = targetRelease.optString("body", "New update available!")
                    val assets = targetRelease.getJSONArray("assets")
                    var apkDownloadUrl = ""
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val assetName = asset.getString("name")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                    if (apkDownloadUrl.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            saveUpdateAvailableState(context, true)
                            onUpdateFound(targetTagName, description, apkDownloadUrl)
                        }
                        return@withContext
                    }
                }
                withContext(Dispatchers.Main) {
                    saveUpdateAvailableState(context, false)
                    onNoUpdate()
                }
            } catch (e: Exception) {
                Log.e("MusixUpdater", "Update check failed", e)
            }
        }
    }
}
