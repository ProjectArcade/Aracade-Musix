package com.arcadesoftware.musix

import android.content.Context
import com.arcadesoftware.musix.models.SimilarRecommendation
import com.music.innertube.pages.HomePage
import java.io.*

object HomeCacheManager {
    private const val HOME_PAGE_FILE = "homepage_cache.bin"
    private const val RECOMMENDATIONS_FILE = "recommendations_cache.bin"

    // In-memory cache for ultra-fast tab switching without file read
    var cachedHomePage: HomePage? = null
    var cachedRecommendations: List<SimilarRecommendation> = emptyList()

    fun save(context: Context, homePage: HomePage?, recs: List<SimilarRecommendation>) {
        cachedHomePage = homePage
        cachedRecommendations = recs
        
        try {
            val homeFile = File(context.cacheDir, HOME_PAGE_FILE)
            ObjectOutputStream(FileOutputStream(homeFile)).use { it.writeObject(homePage) }
        } catch (e: Exception) {
            android.util.Log.e("HomeCacheManager", "Failed to save homepage", e)
        }
        
        try {
            val recsFile = File(context.cacheDir, RECOMMENDATIONS_FILE)
            ObjectOutputStream(FileOutputStream(recsFile)).use { it.writeObject(recs) }
        } catch (e: Exception) {
            android.util.Log.e("HomeCacheManager", "Failed to save recommendations", e)
        }
        // Recommendations are YouTube-derived and not user-specific data;
        // no need to sync them to Firestore. They are cached locally only.
    }

    @Suppress("UNCHECKED_CAST")
    fun load(context: Context): Pair<HomePage?, List<SimilarRecommendation>> {
        if (cachedHomePage != null) {
            return Pair(cachedHomePage, cachedRecommendations)
        }
        
        var homePage: HomePage? = null
        var recs: List<SimilarRecommendation> = emptyList()
        
        try {
            val homeFile = File(context.cacheDir, HOME_PAGE_FILE)
            if (homeFile.exists()) {
                ObjectInputStream(FileInputStream(homeFile)).use { 
                    homePage = it.readObject() as? HomePage
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeCacheManager", "Failed to load homepage", e)
            try {
                File(context.cacheDir, HOME_PAGE_FILE).delete()
            } catch (delEx: Exception) {
                // ignore
            }
        }
        
        try {
            val recsFile = File(context.cacheDir, RECOMMENDATIONS_FILE)
            if (recsFile.exists()) {
                ObjectInputStream(FileInputStream(recsFile)).use { 
                    recs = it.readObject() as List<SimilarRecommendation>
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeCacheManager", "Failed to load recommendations", e)
            try {
                File(context.cacheDir, RECOMMENDATIONS_FILE).delete()
            } catch (delEx: Exception) {
                // ignore
            }
        }
        
        cachedHomePage = homePage
        cachedRecommendations = recs
        return Pair(homePage, recs)
    }
}
