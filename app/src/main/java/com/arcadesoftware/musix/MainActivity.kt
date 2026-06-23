package com.arcadesoftware.musix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcadesoftware.musix.ui.theme.MusixTheme
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.arcadesoftware.musix.ui.screens.HomeScreen
import com.arcadesoftware.musix.ui.screens.PlaylistScreen
import com.arcadesoftware.musix.ui.screens.RecommendationsScreen
import com.arcadesoftware.musix.ui.screens.LibraryScreen

import androidx.compose.ui.text.font.FontWeight
import com.arcadesoftware.musix.ui.screens.PlaylistDetailScreen
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
import com.arcadesoftware.musix.updater.MusixUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import com.music.innertube.models.YTItem
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.music.innertube.models.*
import com.music.innertube.YouTube
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlayerManager {
    private const val ACTION_PLAY = "com.arcadesoftware.musix.ACTION_PLAY"
    private const val ACTION_PAUSE = "com.arcadesoftware.musix.ACTION_PAUSE"
    private const val ACTION_PREVIOUS = "com.arcadesoftware.musix.ACTION_PREVIOUS"
    private const val ACTION_NEXT = "com.arcadesoftware.musix.ACTION_NEXT"
    private const val ACTION_DISMISS = "com.arcadesoftware.musix.ACTION_DISMISS"
    private const val ACTION_REWIND_10 = "com.arcadesoftware.musix.ACTION_REWIND_10"
    private const val ACTION_FORWARD_10 = "com.arcadesoftware.musix.ACTION_FORWARD_10"
    private const val ACTION_LIKE = "com.arcadesoftware.musix.ACTION_LIKE"

    val currentSong = MutableStateFlow<YTItem?>(null)
    val isCurrentSongLiked = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val currentDuration = MutableStateFlow(0L)
    val queue = MutableStateFlow<List<YTItem>>(emptyList())
    val currentQueueIndex = MutableStateFlow(0)
    val autoPlayEnabled = MutableStateFlow(true)
    val activePlaylistDetail = MutableStateFlow<YTItem?>(null)
    val activeUserPlaylist = MutableStateFlow<com.arcadesoftware.musix.db.entities.PlaylistEntity?>(null)
    val currentPlayingPlaylist = MutableStateFlow<YTItem?>(null)
    private var appContext: Context? = null
    private var mediaSession: android.media.session.MediaSession? = null
    private var lastThumbnailUrl: String? = null
    private var currentMetadataSongId: String? = null
    private var currentMetadataDuration: Long = 0L
    private var currentMetadataBitmap: android.graphics.Bitmap? = null
    var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val downloadProgressMap = MutableStateFlow<Map<String, Float>>(emptyMap())
    private var restoredSongId: String? = null
    private var seekOnPreparePosition: Long? = null

    private var simpleCache: androidx.media3.datasource.cache.SimpleCache? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = appContext?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Musix:PlaybackWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout for safety
            android.util.Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                android.util.Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    @Synchronized
    private fun getCache(context: Context): androidx.media3.datasource.cache.SimpleCache {
        if (simpleCache == null) {
            val cacheDir = java.io.File(context.cacheDir, "media_cache")
            val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1024 * 1024 * 512) // 512 MB cache
            val databaseProvider = androidx.media3.database.StandaloneDatabaseProvider(context)
            simpleCache = androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return simpleCache!!
    }

    private val TAG = "PlayerManager"

    @Volatile
    var activeUserAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // Complete client order mirroring Echo-Music to check all playback possibilities
    private val CLIENTS = arrayOf(
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        YouTubeClient.TVHTML5,
        YouTubeClient.ANDROID_CREATOR,
        YouTubeClient.IPADOS,
        YouTubeClient.IOS,
        YouTubeClient.WEB,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.WEB_CREATOR,
        YouTubeClient.MOBILE,
        YouTubeClient.ANDROID_NO_SDK,
    )

    private fun validateUrl(urlStr: String, userAgent: String): Boolean {
        return try {
            val url = java.net.URL(urlStr)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val responseCode = connection.responseCode
            android.util.Log.d(TAG, "Validation response for URL: $responseCode")

            // Accept 2xx success codes or 3xx redirects
            responseCode in 200..399
        } catch (e: Exception) {
            android.util.Log.e(TAG, "URL validation failed with exception: ${e.message}")
            false
        }
    }

    @OptIn(UnstableApi::class)
    fun init(context: Context) {
        if (exoPlayer == null) {
            appContext = context.applicationContext

            // Restore last played song and progress
            val prefs = context.getSharedPreferences("musix_playback_state", Context.MODE_PRIVATE)
            val lastSongId = prefs.getString("last_song_id", null)
            if (lastSongId != null) {
                val lastSongTitle = prefs.getString("last_song_title", "Unknown Title") ?: "Unknown Title"
                val lastSongArtist = prefs.getString("last_song_artist", "Unknown Artist") ?: "Unknown Artist"
                val lastSongThumbnail = prefs.getString("last_song_thumbnail", "") ?: ""
                val lastSongDuration = prefs.getLong("last_song_duration", 0L)
                val lastSongPosition = prefs.getLong("last_song_position", 0L)

                val song = SongItem(
                    id = lastSongId,
                    title = lastSongTitle,
                    artists = listOf(Artist(lastSongArtist, null)),
                    thumbnail = lastSongThumbnail,
                    duration = (lastSongDuration / 1000).toInt()
                )
                currentSong.value = song
                currentPosition.value = lastSongPosition
                currentDuration.value = lastSongDuration
                restoredSongId = lastSongId
                if (lastSongPosition > 0L) {
                    seekOnPreparePosition = lastSongPosition
                }
            }

            // Initialize platform MediaSession
            val session = android.media.session.MediaSession(context, "MusixPlayer").apply {
                isActive = true
                setFlags(
                    android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                )
                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            exoPlayer?.play()
                        }
                    }
                    override fun onPause() {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            exoPlayer?.pause()
                        }
                    }
                    override fun onSkipToNext() {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            playNext()
                        }
                    }
                    override fun onSkipToPrevious() {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            playPrevious()
                        }
                    }
                    override fun onSeekTo(pos: Long) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            exoPlayer?.seekTo(pos)
                            currentPosition.value = pos
                            updatePlaybackState(pos)
                        }
                    }
                    override fun onFastForward() {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            seekBy(10_000L)
                        }
                    }
                    override fun onRewind() {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            seekBy(-10_000L)
                        }
                    }
                    override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            when (action) {
                                ACTION_LIKE -> {
                                    val context = appContext ?: return@post
                                    val song = currentSong.value ?: return@post
                                    val nowLiked = com.arcadesoftware.musix.db.LikedSongsManager.toggleLikeSong(context, song.id)
                                    isCurrentSongLiked.value = nowLiked
                                    updatePlaybackState()
                                    showOrUpdateNotification()
                                }
                                ACTION_REWIND_10 -> seekBy(-10_000L)
                                ACTION_FORWARD_10 -> seekBy(10_000L)
                            }
                        }
                    }
                })
            }
            mediaSession = session

            // Register receiver for notification actions
            val filter = android.content.IntentFilter().apply {
                addAction(ACTION_PLAY)
                addAction(ACTION_PAUSE)
                addAction(ACTION_PREVIOUS)
                addAction(ACTION_NEXT)
                addAction(ACTION_DISMISS)
                addAction(ACTION_REWIND_10)
                addAction(ACTION_FORWARD_10)
                addAction(ACTION_LIKE)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    when (intent.action) {
                        ACTION_PLAY -> playOrRecover(ctx)
                        ACTION_PAUSE -> exoPlayer?.pause()
                        ACTION_PREVIOUS -> playPrevious()
                        ACTION_NEXT -> playNext()
                        ACTION_DISMISS -> {
                            appContext?.let { PlaybackService.stop(it) }
                        }
                        ACTION_REWIND_10 -> seekBy(-10_000L)
                        ACTION_FORWARD_10 -> seekBy(10_000L)
                        ACTION_LIKE -> {
                            val context = appContext ?: return
                            val song = currentSong.value ?: return
                            val nowLiked = com.arcadesoftware.musix.db.LikedSongsManager.toggleLikeSong(context, song.id)
                            isCurrentSongLiked.value = nowLiked
                            updatePlaybackState()
                            showOrUpdateNotification()
                        }
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            startProgressUpdates()

            scope.launch {
                if (YouTube.visitorData == null) {
                    android.util.Log.d(TAG, "Initializing guest session visitorData...")
                    YouTube.refreshVisitorData().onSuccess { newData ->
                        android.util.Log.i(TAG, "Initialized guest session visitorData successfully: $newData")
                    }.onFailure { e ->
                        android.util.Log.e(TAG, "Failed to initialize guest session visitorData: ${e.message}", e)
                    }
                }
            }

            scope.launch {
                currentSong.collect { song ->
                    val context = appContext
                    isCurrentSongLiked.value = if (context != null && song != null) {
                        com.arcadesoftware.musix.db.LikedSongsManager.isSongLiked(context, song.id)
                    } else {
                        false
                    }
                }
            }
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()

            val resolvingDataSourceFactory = androidx.media3.datasource.ResolvingDataSource.Factory(
                httpDataSourceFactory
            ) { dataSpec ->
                dataSpec.withAdditionalHeaders(mapOf(
                    "User-Agent" to activeUserAgent,
                    "Referer" to "https://www.youtube.com/"
                ))
            }

            val cache = getCache(context)
            val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(resolvingDataSourceFactory)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, cacheDataSourceFactory)

            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            exoPlayer = ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, true)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                )
                .build()

            exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying.value = playing
                    updatePlaybackState()
                    showOrUpdateNotification()

                    appContext?.let { ctx ->
                        if (playing) {
                            acquireWakeLock()
                            PlaybackService.start(ctx)
                        } else {
                            acquireWakeLock() // keep wake lock during track transition gap
                            PlaybackService.start(ctx) // keep service alive between songs
                            val state = exoPlayer?.playbackState
                            if (state != androidx.media3.common.Player.STATE_ENDED &&
                                state != androidx.media3.common.Player.STATE_BUFFERING &&
                                state != androidx.media3.common.Player.STATE_IDLE) {
                                releaseWakeLock()
                            }
                            exoPlayer?.let { player ->
                                currentPosition.value = player.currentPosition
                                savePlaybackState()
                            }
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateStr = when (playbackState) {
                        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                        androidx.media3.common.Player.STATE_READY -> "READY"
                        androidx.media3.common.Player.STATE_ENDED -> {
                            acquireWakeLock()
                            val currentIndex = currentQueueIndex.value
                            val currentQueue = queue.value
                            if (currentIndex < currentQueue.size - 1) {
                                currentQueueIndex.value = currentIndex + 1
                                playInternal(currentQueue[currentIndex + 1])
                            } else if (autoPlayEnabled.value) {
                                currentSong.value?.let { song ->
                                    scope.launch {
                                        val endpoint = WatchEndpoint(videoId = song.id)
                                        YouTube.next(endpoint).onSuccess { nextResult ->
                                            val nextItems = nextResult.items.filter { it.id != song.id }
                                            if (nextItems.isNotEmpty()) {
                                                val newQueue = currentQueue + nextItems
                                                queue.value = newQueue
                                                currentQueueIndex.value = currentIndex + 1
                                                playInternal(newQueue[currentIndex + 1])
                                            } else {
                                                releaseWakeLock()
                                                exoPlayer?.playWhenReady = false
                                            }
                                        }.onFailure {
                                            releaseWakeLock()
                                            exoPlayer?.playWhenReady = false
                                        }
                                    }
                                } ?: run {
                                    releaseWakeLock()
                                    exoPlayer?.playWhenReady = false
                                }
                            } else {
                                releaseWakeLock()
                                exoPlayer?.playWhenReady = false
                            }
                            "ENDED"
                        }
                        else -> "UNKNOWN"
                    }
                    android.util.Log.d(TAG, "ExoPlayer state changed: $stateStr")
                    updatePlaybackState()
                    showOrUpdateNotification()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e(TAG, "ExoPlayer playback error: ${error.message}", error)
                    android.util.Log.e(TAG, "Error code: ${error.errorCodeName} (${error.errorCode})")
                }

                override fun onPositionDiscontinuity(
                    oldPosition: androidx.media3.common.Player.PositionInfo,
                    newPosition: androidx.media3.common.Player.PositionInfo,
                    reason: Int
                ) {
                    currentPosition.value = newPosition.positionMs
                    updatePlaybackState()
                }
            })
        }
    }

    fun play(item: YTItem) {
        currentPlayingPlaylist.value = null
        queue.value = listOf(item)
        currentQueueIndex.value = 0
        playInternal(item)
    }

    /** Play a song that is already stored locally on disk (no network needed). */
    fun playLocal(song: SongItem, localFilePath: String) {
        queue.value = listOf(song)
        currentQueueIndex.value = 0
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            currentSong.value = song
            updatePlaybackDetails()
            exoPlayer?.stop()
            exoPlayer?.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(localFilePath))))
            exoPlayer?.prepare()
            exoPlayer?.play()
            updatePlaybackDetails()
        }
    }

    /**
     * Resolves the best audio stream URL for [song], downloads it to [destFile],
     * then saves the local path in the DB and returns the path (or null on failure).
     */
    suspend fun downloadAudio(
        song: SongItem,
        destFile: java.io.File,
        onProgress: (Float) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val videoId = song.id
        var streamUrl: String? = null
        var usedUserAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        val signatureTimestamp = com.music.innertube.NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull()
        for (client in CLIENTS) {
            val response = YouTube.player(videoId = videoId, client = client, signatureTimestamp = signatureTimestamp)
            response.onSuccess { playerResponse ->
                if (playerResponse.playabilityStatus.status != "OK") return@onSuccess
                val format = playerResponse.streamingData?.adaptiveFormats
                    ?.filter { it.mimeType.startsWith("audio/") }
                    ?.maxByOrNull { it.bitrate }
                    ?: playerResponse.streamingData?.formats?.firstOrNull()
                if (format != null) {
                    val url = com.music.innertube.NewPipeExtractor.getStreamUrl(format, videoId)
                    if (url != null) {
                        val ua = client.userAgent ?: usedUserAgent
                        if (validateUrl(url, ua)) {
                            streamUrl = url
                            usedUserAgent = ua
                        }
                    }
                }
            }
            if (streamUrl != null) break
        }

        if (streamUrl == null) return@withContext null

        try {
            val connection = java.net.URL(streamUrl!!).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", usedUserAgent)
            connection.setRequestProperty("Referer", "https://www.youtube.com/")
            connection.setRequestProperty("Accept-Encoding", "identity") // no gzip so Content-Length is accurate
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.connect()
            val contentLength = connection.contentLengthLong
            destFile.parentFile?.mkdirs()
            var bytesCopied = 0L
            var lastProgressBytes = 0L
            val progressChunk = 256 * 1024L // emit progress every 256 KB to reduce main-thread pressure
            connection.inputStream.use { input ->
                java.io.BufferedOutputStream(destFile.outputStream(), 256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        if (coroutineContext[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException("Download cancelled")
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        if (contentLength > 0 && bytesCopied - lastProgressBytes >= progressChunk) {
                            lastProgressBytes = bytesCopied
                            onProgress(bytesCopied.toFloat() / contentLength)
                        }
                        bytes = input.read(buffer)
                    }
                    if (contentLength > 0) onProgress(1f) // ensure 100% is reported
                }
            }
            connection.disconnect()
            destFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Download failed: ${e.message}", e)
            null
        }
    }

    private val activeDownloadJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun cancelDownload(songId: String) {
        activeDownloadJobs.remove(songId)?.cancel()
    }

    fun startDownload(song: SongItem, context: Context) {
        val songId = song.id
        synchronized(downloadProgressMap) {
            if (downloadProgressMap.value.containsKey(songId)) return
            downloadProgressMap.value = downloadProgressMap.value + (songId to 0f)
        }
        val destFile = java.io.File(
            context.applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
            "$songId.m4a"
        )
        val job = scope.launch(Dispatchers.IO) {
            try {
                val localPath = downloadAudio(song, destFile) { progressVal ->
                    synchronized(downloadProgressMap) {
                        downloadProgressMap.value = downloadProgressMap.value + (songId to progressVal)
                    }
                }
                if (localPath != null) {
                    val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(context.applicationContext)
                    val artistName = song.artists.firstOrNull()?.name ?: ""
                    val artistId = song.artists.firstOrNull()?.id
                    db.musicDao().insertDownloadedSong(
                        com.arcadesoftware.musix.db.entities.DownloadedSongEntity(
                            id = song.id,
                            title = song.title,
                            artistName = artistName,
                            artistId = artistId,
                            thumbnailUrl = song.thumbnail,
                            localFilePath = localPath
                        )
                    )
                    // Also cache in play history so it shows in Recently Played
                    db.musicDao().insertPlayHistory(
                        com.arcadesoftware.musix.db.entities.PlayHistoryEntity(
                            id = song.id,
                            title = song.title,
                            artistName = artistName,
                            artistId = artistId,
                            thumbnailUrl = song.thumbnail
                        )
                    )
                    com.arcadesoftware.musix.db.FirebaseSyncManager.syncHistory(context.applicationContext)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Download job exception: ${e.message}", e)
            } finally {
                activeDownloadJobs.remove(songId)
                val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(context.applicationContext)
                val isDownloaded = db.musicDao().getDownloadedSong(songId) != null
                if (!isDownloaded && destFile.exists()) {
                    destFile.delete()
                }
                synchronized(downloadProgressMap) {
                    downloadProgressMap.value = downloadProgressMap.value - songId
                }
            }
        }
        activeDownloadJobs[songId] = job
    }

    fun playQueue(items: List<YTItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        queue.value = items
        currentQueueIndex.value = startIndex
        playInternal(items[startIndex])
    }

    fun playNext() {
        val currentIndex = currentQueueIndex.value
        val currentQueue = queue.value
        if (currentIndex < currentQueue.size - 1) {
            currentQueueIndex.value = currentIndex + 1
            playInternal(currentQueue[currentIndex + 1])
        } else if (autoPlayEnabled.value) {
            currentSong.value?.let { song ->
                acquireWakeLock()
                scope.launch {
                    val endpoint = WatchEndpoint(videoId = song.id)
                    YouTube.next(endpoint).onSuccess { nextResult ->
                        val nextItems = nextResult.items.filter { it.id != song.id }
                        if (nextItems.isNotEmpty()) {
                            val newQueue = currentQueue + nextItems
                            queue.value = newQueue
                            currentQueueIndex.value = currentIndex + 1
                            playInternal(newQueue[currentIndex + 1])
                        } else {
                            releaseWakeLock()
                        }
                    }.onFailure {
                        releaseWakeLock()
                    }
                }
            }
        }
    }

    fun playPrevious() {
        if ((exoPlayer?.currentPosition ?: 0L) > 3000L) {
            exoPlayer?.seekTo(0)
        } else {
            val currentIndex = currentQueueIndex.value
            val currentQueue = queue.value
            if (currentIndex > 0) {
                currentQueueIndex.value = currentIndex - 1
                playInternal(currentQueue[currentIndex - 1])
            } else {
                exoPlayer?.seekTo(0)
            }
        }
    }

    private fun playInternal(item: YTItem) {
        acquireWakeLock()
        scope.launch {
            android.util.Log.d(TAG, "Resolving item: class=${item::class.java.simpleName}, id=${item.id}")
            val resolvedSong = when (item) {
                is SongItem -> item
                is PlaylistItem -> {
                    android.util.Log.d(TAG, "Fetching playlist ${item.id} — loading all songs into queue")
                    currentPlayingPlaylist.value = item
                    val result = YouTube.playlist(item.id)
                    var firstSong: SongItem? = null
                    result.onSuccess { playlistPage ->
                        val songs = playlistPage.songs
                        if (songs.isNotEmpty()) {
                            // Set the full playlist as the queue
                            queue.value = songs
                            currentQueueIndex.value = 0
                            firstSong = songs.first()
                        }
                    }.onFailure { e ->
                        android.util.Log.e(TAG, "Failed to load playlist: ${e.message}", e)
                    }
                    firstSong
                }
                is AlbumItem -> {
                    android.util.Log.d(TAG, "Fetching album ${item.browseId} — loading all songs into queue")
                    currentPlayingPlaylist.value = item
                    val result = YouTube.album(item.browseId)
                    var firstSong: SongItem? = null
                    result.onSuccess { albumPage ->
                        val songs = albumPage.songs
                        if (songs.isNotEmpty()) {
                            queue.value = songs
                            currentQueueIndex.value = 0
                            firstSong = songs.first()
                        }
                    }.onFailure { e ->
                        android.util.Log.e(TAG, "Failed to load album: ${e.message}", e)
                    }
                    firstSong
                }
                is ArtistItem -> {
                    android.util.Log.d(TAG, "Resolving artist ${item.id} — loading radio into queue")
                    val endpoint = item.radioEndpoint ?: item.playEndpoint ?: item.shuffleEndpoint
                    if (endpoint != null) {
                        val result = YouTube.next(endpoint)
                        var firstSong: SongItem? = null
                        result.onSuccess { nextResult ->
                            val songs = nextResult.items
                            if (songs.isNotEmpty()) {
                                queue.value = songs
                                currentQueueIndex.value = 0
                                firstSong = songs.first()
                            }
                        }.onFailure { e ->
                            android.util.Log.e(TAG, "Failed to load artist nextResult: ${e.message}", e)
                        }
                        firstSong
                    } else {
                        android.util.Log.e(TAG, "Artist has no playable endpoints")
                        null
                    }
                }
                else -> null
            }

            if (resolvedSong == null) {
                android.util.Log.e(TAG, "Could not resolve a song for item: $item")
                releaseWakeLock()
                exoPlayer?.playWhenReady = false
                android.os.Handler(android.os.Looper.getMainLooper()).post { triggerNotificationUpdate() }
                return@launch
            }

            // Save to play history
            appContext?.let { ctx ->
                scope.launch {
                    val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(ctx)
                    db.musicDao().insertPlayHistory(
                        com.arcadesoftware.musix.db.entities.PlayHistoryEntity(
                            id = resolvedSong.id,
                            title = resolvedSong.title,
                            artistName = resolvedSong.artists.firstOrNull()?.name ?: "Unknown",
                            artistId = resolvedSong.artists.firstOrNull()?.id,
                            thumbnailUrl = resolvedSong.thumbnail
                        )
                    )
                    com.arcadesoftware.musix.db.FirebaseSyncManager.syncHistory(ctx)
                }
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                currentSong.value = resolvedSong
                updatePlaybackDetails()
            }

            // Check if song is downloaded locally to stream offline
            var downloadedSong: com.arcadesoftware.musix.db.entities.DownloadedSongEntity? = null
            appContext?.let { ctx ->
                val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(ctx)
                downloadedSong = db.musicDao().getDownloadedSong(resolvedSong.id)
            }
            if (downloadedSong != null && !downloadedSong!!.localFilePath.isNullOrEmpty()) {
                val localFile = java.io.File(downloadedSong!!.localFilePath)
                if (localFile.exists()) {
                    android.util.Log.d(TAG, "Playing local downloaded file: ${localFile.absolutePath}")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        exoPlayer?.stop()
                        exoPlayer?.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(localFile)))
                        val seekPos = if (resolvedSong.id == restoredSongId) seekOnPreparePosition else null
                        if (seekPos != null && seekPos > 0) {
                            exoPlayer?.seekTo(seekPos)
                            restoredSongId = null
                            seekOnPreparePosition = null
                        }
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                        updatePlaybackDetails()
                    }
                    return@launch
                }
            }

            val videoId = resolvedSong.id
            android.util.Log.d(TAG, "Playing videoId=$videoId")
            var streamUrl: String? = null
            var usedUserAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

            suspend fun resolveStream(): Boolean {
                val signatureTimestamp = com.music.innertube.NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull()
                android.util.Log.d(TAG, "Signature timestamp: $signatureTimestamp")

                for (client in CLIENTS) {
                    android.util.Log.d(TAG, "Trying client: ${client.clientName} v${client.clientVersion}")
                    val response = YouTube.player(videoId = videoId, client = client, signatureTimestamp = signatureTimestamp)
                    response.onSuccess { playerResponse ->
                        val status = playerResponse.playabilityStatus.status
                        android.util.Log.d(TAG, "Status: $status, reason: ${playerResponse.playabilityStatus.reason}")

                        if (status != "OK") return@onSuccess

                        // Prefer audio-only adaptive formats, then fall back to combined formats
                        val format = playerResponse.streamingData?.adaptiveFormats
                            ?.filter { it.mimeType.startsWith("audio/") }
                            ?.maxByOrNull { it.bitrate }
                            ?: playerResponse.streamingData?.formats?.firstOrNull()

                        if (format != null) {
                            val url = com.music.innertube.NewPipeExtractor.getStreamUrl(format, videoId)
                            android.util.Log.d(TAG, "Format: ${format.mimeType}, bitrate: ${format.bitrate}, resolved url null? ${url == null}")

                            if (url != null) {
                                val ua = client.userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
                                if (validateUrl(url, ua)) {
                                    streamUrl = url
                                    usedUserAgent = ua
                                } else {
                                    android.util.Log.w(TAG, "Skipping client ${client.clientName} due to validation failure (403 or network issue)")
                                }
                            }
                        }
                    }
                    response.onFailure { e ->
                        android.util.Log.e(TAG, "Client ${client.clientName} failed: ${e.message}")
                    }
                    if (streamUrl != null) return true
                }
                return false
            }

            var resolved = resolveStream()
            if (!resolved) {
                android.util.Log.w(TAG, "Playback resolution failed on first attempt. Rotating guest session and retrying...")
                YouTube.visitorData = null
                YouTube.refreshVisitorData().onSuccess { newData ->
                    android.util.Log.i(TAG, "Rotated visitorData successfully: $newData")
                }.onFailure { e ->
                    android.util.Log.e(TAG, "Failed to rotate visitorData: ${e.message}", e)
                }
                resolved = resolveStream()
            }

            if (streamUrl != null) {
                activeUserAgent = usedUserAgent
                android.util.Log.d(TAG, "Playing stream URL (length=${streamUrl!!.length}) with User-Agent: $activeUserAgent")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    exoPlayer?.stop()
                    exoPlayer?.setMediaItem(MediaItem.fromUri(streamUrl!!))
                    val seekPos = if (resolvedSong.id == restoredSongId) seekOnPreparePosition else null
                    if (seekPos != null && seekPos > 0) {
                        exoPlayer?.seekTo(seekPos)
                        restoredSongId = null
                        seekOnPreparePosition = null
                    }
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    updatePlaybackDetails()
                }
            } else {
                android.util.Log.e(TAG, "All clients failed for videoId=$videoId")
                releaseWakeLock()
                exoPlayer?.playWhenReady = false
                android.os.Handler(android.os.Looper.getMainLooper()).post { triggerNotificationUpdate() }
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                val state = player.playbackState
                if (state == androidx.media3.common.Player.STATE_IDLE || state == androidx.media3.common.Player.STATE_ENDED) {
                    currentSong.value?.let { song ->
                        playInternal(song)
                    } ?: player.play()
                } else {
                    player.play()
                }
            }
        }
    }

    fun playOrRecover(context: Context? = null) {
        val ctx = context ?: appContext
        if (exoPlayer == null && ctx != null) {
            init(ctx)
        }
        exoPlayer?.let { player ->
            if (!player.isPlaying) {
                val state = player.playbackState
                if (state == androidx.media3.common.Player.STATE_IDLE || state == androidx.media3.common.Player.STATE_ENDED) {
                    currentSong.value?.let { song ->
                        playInternal(song)
                    } ?: player.play()
                } else {
                    player.play()
                }
            }
        }
    }

    fun savePlaybackState() {
        val context = appContext ?: return
        val song = currentSong.value as? SongItem ?: return
        val pos = currentPosition.value
        val dur = currentDuration.value
        if (song.id.isNotEmpty()) {
            val prefs = context.getSharedPreferences("musix_playback_state", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("last_song_id", song.id)
                putString("last_song_title", song.title)
                putString("last_song_artist", song.artists.joinToString { it.name })
                putString("last_song_thumbnail", song.thumbnail)
                putLong("last_song_duration", dur)
                putLong("last_song_position", pos)
                apply()
            }
        }
    }

    private fun startProgressUpdates() {
        scope.launch(Dispatchers.Main) {
            while (true) {
                try {
                    val player = exoPlayer
                    if (player != null && player.isPlaying) {
                        currentPosition.value = player.currentPosition
                        val dur = player.duration
                        if (dur > 0) {
                            currentDuration.value = dur
                        }
                        savePlaybackState()
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error in progress updates", e)
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun seekTo(fraction: Float) {
        val durationVal = currentDuration.value
        if (durationVal > 0) {
            val targetPosition = (durationVal * fraction).toLong()
            currentPosition.value = targetPosition
            exoPlayer?.let { player ->
                if (player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                    player.seekTo(targetPosition)
                } else {
                    seekOnPreparePosition = targetPosition
                }
            } ?: run {
                seekOnPreparePosition = targetPosition
            }
            updatePlaybackState(targetPosition)
            savePlaybackState()
        }
    }

    fun seekBy(offsetMs: Long) {
        val durationVal = currentDuration.value
        if (durationVal > 0) {
            val current = currentPosition.value
            val target = (current + offsetMs).coerceIn(0L, durationVal)
            currentPosition.value = target
            exoPlayer?.let { player ->
                if (player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                    player.seekTo(target)
                } else {
                    seekOnPreparePosition = target
                }
            } ?: run {
                seekOnPreparePosition = target
            }
            updatePlaybackState(target)
            savePlaybackState()
        }
    }

    private fun updatePlaybackState(overridePosition: Long? = null) {
        val session = mediaSession ?: return
        val player = exoPlayer ?: return
        val song = currentSong.value as? SongItem ?: return

        val state = if (player.isPlaying) {
            android.media.session.PlaybackState.STATE_PLAYING
        } else {
            android.media.session.PlaybackState.STATE_PAUSED
        }

        // Check if duration has become available or has changed
        val playerDuration = player.duration
        val durationMs = if (playerDuration > 0) playerDuration else 0L
        if (durationMs > 0 && durationMs != currentMetadataDuration && song.id == currentMetadataSongId) {
            currentMetadataDuration = durationMs
            val metadataBuilder = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artists.joinToString { it.name })
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, durationMs)

            currentMetadataBitmap?.let { bitmap ->
                metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, bitmap)
                metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
            }
            session.setMetadata(metadataBuilder.build())
        }

        val position = overridePosition ?: player.currentPosition
        currentPosition.value = position
        if (playerDuration > 0) {
            currentDuration.value = playerDuration
        }

        val likeIconRes = if (isCurrentSongLiked.value) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        val likeTitle = if (isCurrentSongLiked.value) "Unlike" else "Like"

        val likeCustom = android.media.session.PlaybackState.CustomAction.Builder(
            ACTION_LIKE,
            likeTitle,
            likeIconRes
        ).build()

        val playbackSpeed = if (player.isPlaying) 1.0f else 0f
        val playbackState = android.media.session.PlaybackState.Builder()
            .setState(state, position, playbackSpeed)
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY or
                        android.media.session.PlaybackState.ACTION_PAUSE or
                        android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                        android.media.session.PlaybackState.ACTION_SEEK_TO or
                        android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                        android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        android.media.session.PlaybackState.ACTION_FAST_FORWARD or
                        android.media.session.PlaybackState.ACTION_REWIND
            )
            .addCustomAction(likeCustom)
            .build()

        session.setPlaybackState(playbackState)
    }

    private fun updatePlaybackDetails() {
        val song = currentSong.value as? SongItem ?: return

        val session = mediaSession ?: return
        val durationMs = exoPlayer?.duration ?: 0L
        val songDuration = song.duration
        val targetDuration = if (durationMs > 0) {
            durationMs
        } else if (songDuration != null) {
            songDuration * 1000L
        } else {
            0L
        }

        val isDifferentSong = song.id != currentMetadataSongId
        currentMetadataSongId = song.id
        currentMetadataDuration = targetDuration
        if (isDifferentSong) {
            currentMetadataBitmap = null // Reset bitmap only for a different song
            currentPosition.value = 0L
        }
        currentDuration.value = targetDuration

        val metadataBuilder = android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artists.joinToString { it.name })
        if (targetDuration > 0) {
            metadataBuilder.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, targetDuration)
        }
        currentMetadataBitmap?.let { bitmap ->
            metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
            metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, bitmap)
            metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
        }
        session.setMetadata(metadataBuilder.build())

        updatePlaybackState(overridePosition = if (isDifferentSong) 0L else null)
        showOrUpdateNotification()

        if (song.thumbnail.isNotEmpty() && (isDifferentSong || currentMetadataBitmap == null)) {
            lastThumbnailUrl = song.thumbnail
            scope.launch(Dispatchers.IO) {
                val context = appContext ?: return@launch
                var bitmap: android.graphics.Bitmap? = null

                // Build URL list: try high-res first, then original
                val thumbUrl = song.thumbnail
                val highResUrl = if (thumbUrl.contains("=w") || thumbUrl.contains("-w")) {
                    thumbUrl.replace(Regex("=w\\d+-h\\d+.*"), "=w1080-h1080")
                        .replace(Regex("w\\d+-h\\d+(-s\\d+)?(-c\\d+)?(-k.*)?$"), "w1080-h1080-l90-rj")
                } else thumbUrl

                for (url in listOf(highResUrl, thumbUrl)) {
                    if (bitmap != null) break
                    try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .addHeader("User-Agent", "Mozilla/5.0")
                            .addHeader("Referer", "https://www.youtube.com/")
                            .build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        }
                        response.close()
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to load art from $url: ${e.message}")
                    }
                }

                bitmap?.let { b ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val activeSong = currentSong.value as? SongItem
                        if (activeSong?.id == song.id) {
                            currentMetadataBitmap = b
                            val meta = android.media.MediaMetadata.Builder()
                                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
                                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artists.joinToString { it.name })
                                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, b)
                                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, b)
                                .putBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON, b)
                            if (currentMetadataDuration > 0) {
                                meta.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, currentMetadataDuration)
                            }
                            session.setMetadata(meta.build())
                            showOrUpdateNotification(b)
                        }
                    }
                }
            }
        }
    }

    private fun showOrUpdateNotification(largeIcon: android.graphics.Bitmap? = null) {
        val context = appContext ?: return
        val song = currentSong.value as? SongItem ?: return
        val isForegroundRequired = exoPlayer?.playWhenReady ?: isPlaying.value

        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "music_playback",
                "Music Playback",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for music playback"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val playIntent = android.app.PendingIntent.getService(
            context, 0, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_PLAY),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = android.app.PendingIntent.getService(
            context, 1, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_PAUSE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = android.app.PendingIntent.getService(
            context, 2, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_PREVIOUS),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = android.app.PendingIntent.getService(
            context, 3, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_NEXT),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = android.app.PendingIntent.getService(
            context, 5, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_DISMISS),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val likeIntent = android.app.PendingIntent.getService(
            context, 6, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_LIKE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val rewindIntent = android.app.PendingIntent.getService(
            context, 7, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_REWIND_10),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val forwardIntent = android.app.PendingIntent.getService(
            context, 8, android.content.Intent(context, com.arcadesoftware.musix.PlaybackService::class.java).setAction(ACTION_FORWARD_10),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val mainActivityIntent = android.content.Intent(context, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            context, 4, mainActivityIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, "music_playback")
        } else {
            android.app.Notification.Builder(context)
        }

        builder.setContentTitle(song.title)
            .setContentText(song.artists.joinToString { it.name })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setOngoing(isForegroundRequired)

        val bitmapToUse = largeIcon ?: currentMetadataBitmap
        if (bitmapToUse != null) {
            builder.setLargeIcon(bitmapToUse)
        }

        val likeIconRes = if (isCurrentSongLiked.value) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        val likeTitle = if (isCurrentSongLiked.value) "Unlike" else "Like"

        builder.addAction(
            android.app.Notification.Action.Builder(
                likeIconRes, likeTitle, likeIntent
            ).build()
        )
        builder.addAction(
            android.app.Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", prevIntent
            ).build()
        )
        if (isForegroundRequired) {
            builder.addAction(
                android.app.Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "Pause", pauseIntent
                ).build()
            )
        } else {
            builder.addAction(
                android.app.Notification.Action.Builder(
                    android.R.drawable.ic_media_play, "Play", playIntent
                ).build()
            )
        }
        builder.addAction(
            android.app.Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next", nextIntent
            ).build()
        )


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val style = android.app.Notification.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3) // Previous (1), Play/Pause (2), Next (3)
            mediaSession?.let {
                style.setMediaSession(it.sessionToken)
            }
            builder.setStyle(style)
        }

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
            ) {
                val notification = builder.build()
                com.arcadesoftware.musix.PlaybackService.instance?.let { service ->
                    service.updateForegroundNotification(notification, isForegroundRequired)
                } ?: run {
                    notificationManager.notify(1001, notification)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to show notification: ${e.message}")
        }
    }

    fun triggerNotificationUpdate() {
        updatePlaybackState()
        showOrUpdateNotification()
    }
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configure global Coil ImageLoader with disk and memory cache
        try {
            val imageLoader = coil.ImageLoader.Builder(applicationContext)
                .memoryCache {
                    coil.memory.MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(1024 * 1024 * 256) // 256 MB disk cache
                        .build()
                }
                .build()
            coil.Coil.setImageLoader(imageLoader)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to configure Coil ImageLoader cache", e)
        }

        setContent {
            MusixTheme {
                MainScreen()
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val initialTab = remember {
        if (isNetworkAvailable(context)) 0 else 1
    }
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    val mainBackdrop = rememberLayerBackdrop()
    val playlistBackdrop = rememberLayerBackdrop()
    val currentSong by PlayerManager.currentSong.collectAsState()
    val activePlaylistDetail by PlayerManager.activePlaylistDetail.collectAsState()
    val activeUserPlaylist by PlayerManager.activeUserPlaylist.collectAsState()
    val showBottomBar = activePlaylistDetail == null && activeUserPlaylist == null

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        android.util.Log.d("MainScreen", "POST_NOTIFICATIONS permission granted: $isGranted")
    }

    var showBlockAllAccessDialog by remember { mutableStateOf(false) }
    var showForceUpdateDialog by remember { mutableStateOf(false) }
    var showSoftUpdateDialog by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        PlayerManager.init(context.applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentAppVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }
            
            val vcRef = com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("com_arcadesoftware_musix")
                .child("version_control")
            vcRef.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (snapshot.exists()) {
                        val blockAllAccess = snapshot.child("block_all_access").value as? Boolean ?: false
                        val minSupported = (snapshot.child("min_supported_version_code").value as? Number)?.toInt() ?: 0
                        val currentVersion = (snapshot.child("current_version_code").value as? Number)?.toInt() ?: 0
                        val url = snapshot.child("update_path_url").value as? String ?: ""
                        val msg = snapshot.child("update_message").value as? String ?: "Please update to the latest version."
                        
                        updateUrl = url
                        updateMessage = msg
                        
                        if (blockAllAccess) {
                            showBlockAllAccessDialog = true
                        } else if (currentAppVersionCode < minSupported) {
                            showForceUpdateDialog = true
                        } else if (currentAppVersionCode < currentVersion) {
                            showSoftUpdateDialog = true
                        }
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    android.util.Log.e("VersionControl", "Failed to check version control", error.toException())
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("VersionControl", "Failed to resolve version code", e)
        }

        MusixUpdater.checkForUpdate(
            context = context,
            onUpdateFound = { version, description, apkUrl ->
//                Toast.makeText(context, "Update v$version available!", Toast.LENGTH_LONG).show()
            },
            onNoUpdate = {}
        )
    }

    var showAccountSheet by remember { mutableStateOf(false) }
    var currentUser by remember { mutableStateOf(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser) }
    var showWelcomePopup by remember { mutableStateOf(false) }
    var showCloudSyncPrompt by remember { mutableStateOf(false) }
    var hasPromptedSync by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentUser, showForceUpdateDialog, showSoftUpdateDialog, showBlockAllAccessDialog) {
        if (currentUser == null && !hasPromptedSync && !showForceUpdateDialog && !showSoftUpdateDialog && !showBlockAllAccessDialog) {
            hasPromptedSync = true
            kotlinx.coroutines.delay(2000)
            if (!showForceUpdateDialog && !showSoftUpdateDialog && !showBlockAllAccessDialog) {
                showCloudSyncPrompt = true
            }
        }
    }

    LaunchedEffect(Unit) {
        var lastUid: String? = null
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser
            currentUser = user
            if (user != null && user.uid != lastUid) {
                lastUid = user.uid
                com.arcadesoftware.musix.db.FirebaseSyncManager.fetchAndMergeFirebaseData(context) {
                    com.arcadesoftware.musix.db.FirebaseSyncManager.pushAllLocalDataToFirebase(context)
                }
            }
        }
    }

    if (showAccountSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAccountSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (currentUser == null) {
                    Button(
                        onClick = {
                            showAccountSheet = false
                            scope.launch {
                                try {
                                    val credentialManager = androidx.credentials.CredentialManager.create(context)
                                    val request = androidx.credentials.GetCredentialRequest.Builder()
                                        .addCredentialOption(
                                            com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
                                                .setFilterByAuthorizedAccounts(false)
                                                .setServerClientId("983178184530-c0grj95ua7kb862qnr0f9nnhr2g3t5qt.apps.googleusercontent.com")
                                                .setAutoSelectEnabled(false)
                                                .build()
                                        )
                                        .build()
                                    val result = credentialManager.getCredential(context, request)
                                    val credential = result.credential
                                    if (credential is androidx.credentials.CustomCredential &&
                                        credential.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                    ) {
                                        val googleIdTokenCredential = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(credential.data)
                                        val idToken = googleIdTokenCredential.idToken
                                        val authCredential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
                                        com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(authCredential)
                                            .addOnSuccessListener {
                                                showWelcomePopup = true
                                                scope.launch {
                                                    kotlinx.coroutines.delay(2500)
                                                    showWelcomePopup = false
                                                }
                                            }
                                            .addOnFailureListener {
                                                android.widget.Toast.makeText(context, "Sign in failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Google Sign-In failed", android.widget.Toast.LENGTH_SHORT).show()
                                    e.printStackTrace()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_google),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign in with Google")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                animation = androidx.compose.animation.core.tween(3000, easing = androidx.compose.animation.core.LinearEasing),
                                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                            )
                        )
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .graphicsLayer { rotationZ = rotation }
                                    .border(
                                        2.dp,
                                        androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Cyan, Color.Magenta, Color.Yellow, Color.Cyan)),
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                            AsyncImage(
                                model = currentUser?.photoUrl,
                                contentDescription = "Profile Picture",
                                modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(currentUser?.displayName ?: "User", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(currentUser?.email ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = {
                            showAccountSheet = false
                            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                            com.arcadesoftware.musix.components.ByeAnimManager.trigger()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Sign Out")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        showAccountSheet = false
                        context.startActivity(android.content.Intent(context, SettingsActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Settings")
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showBottomBar,
                    enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                    exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it })
                ) {
                    AppBottomBar(
                        selectedTab = selectedTab,
                        onTabSelected = {
                            focusManager.clearFocus()
                            selectedTab = it
                        },
                        onSearchClick = {
                            context.startActivity(android.content.Intent(context, SearchActivity::class.java))
                        },
                        backdrop = mainBackdrop
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(mainBackdrop)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        onOpenDrawer = {
                            showAccountSheet = true
                        }
                    )
                    1 -> PlaylistScreen(backdrop = playlistBackdrop)
                    2 -> LibraryScreen(backdrop = playlistBackdrop)
                    3 -> RecommendationsScreen()
                }
            }
        }

        // Playlist details page overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = activePlaylistDetail != null,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            activePlaylistDetail?.let { playlistItem ->
                LaunchedEffect(playlistItem) {
                    focusManager.clearFocus()
                }
                PlaylistDetailScreen(
                    playlistItem = playlistItem,
                    backdrop = playlistBackdrop,
                    onBack = { PlayerManager.activePlaylistDetail.value = null }
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = currentSong != null,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it })
        ) {
            val currentBackdrop = if (activePlaylistDetail != null) playlistBackdrop else mainBackdrop
            MiniPlayer(
                backdrop = currentBackdrop,
                currentSong = currentSong,
                collapsedBottomPadding = if (showBottomBar) 112.dp else 24.dp
            )
        }

        com.arcadesoftware.musix.components.FloatingHeartsContainer()
        com.arcadesoftware.musix.components.FloatingCelebrateContainer()
        com.arcadesoftware.musix.components.FloatingByeContainer()

        // Status bar protector to ensure scrolling content doesn't overlap system notifications
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
        )

        LaunchedEffect(showWelcomePopup) {
            if (showWelcomePopup) {
                com.arcadesoftware.musix.components.CelebrateAnimManager.trigger()
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showWelcomePopup,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val isLight = !androidx.compose.foundation.isSystemInDarkTheme()
            val popupAlpha = if (isLight) 0.5f else 0.4f
            val containerColor = if (isLight) Color.White.copy(alpha = popupAlpha) else Color.Black.copy(alpha = popupAlpha)
            val popupShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showWelcomePopup = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .drawBackdrop(
                            backdrop = mainBackdrop,
                            shape = { popupShape },
                            effects = {
                                vibrancy()
                                blur(16f.dp.toPx())
                                lens(12f.dp.toPx(), 24f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(containerColor)
                            }
                        )
                        .border(
                            width = 0.5.dp,
                            color = if (isLight) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                            shape = popupShape
                        )
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {},
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🎉 Welcome back 🎉",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = if (isLight) Color.Black else Color.White
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                animation = androidx.compose.animation.core.tween(3000, easing = androidx.compose.animation.core.LinearEasing),
                                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                            )
                        )
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .graphicsLayer { rotationZ = rotation }
                                    .border(
                                        2.dp,
                                        androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Cyan, Color.Magenta, Color.Yellow, Color.Cyan)),
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                            AsyncImage(
                                model = currentUser?.photoUrl,
                                contentDescription = "Profile Picture",
                                modifier = Modifier.size(60.dp).clip(androidx.compose.foundation.shape.CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = currentUser?.displayName ?: "",
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = if (isLight) Color.DarkGray else Color.LightGray,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), thickness = 0.5.dp)
                    androidx.compose.material3.TextButton(
                        onClick = { showWelcomePopup = false },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = androidx.compose.ui.graphics.RectangleShape
                    ) {
                        Text("OK", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showCloudSyncPrompt,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val isLight = !androidx.compose.foundation.isSystemInDarkTheme()
            val popupAlpha = if (isLight) 0.5f else 0.4f
            val containerColor = if (isLight) Color.White.copy(alpha = popupAlpha) else Color.Black.copy(alpha = popupAlpha)
            val popupShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showCloudSyncPrompt = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .drawBackdrop(
                            backdrop = mainBackdrop,
                            shape = { popupShape },
                            effects = {
                                vibrancy()
                                blur(16f.dp.toPx())
                                lens(12f.dp.toPx(), 24f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(containerColor)
                            }
                        )
                        .border(
                            width = 0.5.dp,
                            color = if (isLight) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                            shape = popupShape
                        )
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {},
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudQueue,
                            contentDescription = null,
                            tint = Color(0xFFFA243C),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Cloud Sync",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = if (isLight) Color.Black else Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sign in with your Google account to back up and sync your library, playlists, recommendations, and history across devices.",
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = if (isLight) Color.DarkGray else Color.LightGray
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { showCloudSyncPrompt = false },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = androidx.compose.ui.graphics.RectangleShape
                        ) {
                            Text("Remind Later", fontSize = 15.sp, color = Color(0xFF007AFF))
                        }
                        Box(modifier = Modifier.width(0.5.dp).fillMaxHeight().background(Color.Gray.copy(alpha = 0.3f)))
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showCloudSyncPrompt = false
                                showAccountSheet = true
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = androidx.compose.ui.graphics.RectangleShape
                        ) {
                            Text("Sign In Now", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                        }
                    }
                }
            }
        }

        // 0. Block All Access Dialog (Non-dismissible, no actions)
        if (showBlockAllAccessDialog) {
            androidx.activity.compose.BackHandler(enabled = true) {
                // Intercept back presses so user cannot close it
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showBlockAllAccessDialog,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val isLight = !androidx.compose.foundation.isSystemInDarkTheme()
            val popupAlpha = if (isLight) 0.5f else 0.4f
            val containerColor = if (isLight) Color.White.copy(alpha = popupAlpha) else Color.Black.copy(alpha = popupAlpha)
            val popupShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .drawBackdrop(
                            backdrop = mainBackdrop,
                            shape = { popupShape },
                            effects = {
                                vibrancy()
                                blur(16f.dp.toPx())
                                lens(12f.dp.toPx(), 24f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(containerColor)
                            }
                        )
                        .border(
                            width = 0.5.dp,
                            color = if (isLight) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                            shape = popupShape
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFA243C),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Service Unavailable",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = if (isLight) Color.Black else Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (updateMessage.isNotEmpty()) updateMessage else "This service is temporarily undergoing maintenance. Please check back later.",
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = if (isLight) Color.DarkGray else Color.LightGray
                        )
                    }
                }
            }
        }

        // 1. Force Update Dialog (Non-dismissible)
        if (showForceUpdateDialog) {
            androidx.activity.compose.BackHandler(enabled = true) {
                // Intercept back presses so user cannot close it
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showForceUpdateDialog,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val isLight = !androidx.compose.foundation.isSystemInDarkTheme()
            val popupAlpha = if (isLight) 0.5f else 0.4f
            val containerColor = if (isLight) Color.White.copy(alpha = popupAlpha) else Color.Black.copy(alpha = popupAlpha)
            val popupShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .drawBackdrop(
                            backdrop = mainBackdrop,
                            shape = { popupShape },
                            effects = {
                                vibrancy()
                                blur(16f.dp.toPx())
                                lens(12f.dp.toPx(), 24f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(containerColor)
                            }
                        )
                        .border(
                            width = 0.5.dp,
                            color = if (isLight) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                            shape = popupShape
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFA243C),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Update Required",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = if (isLight) Color.Black else Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateMessage,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = if (isLight) Color.DarkGray else Color.LightGray
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), thickness = 0.5.dp)
                    androidx.compose.material3.TextButton(
                        onClick = {
                            if (updateUrl.isNotEmpty()) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(updateUrl))
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = androidx.compose.ui.graphics.RectangleShape
                    ) {
                        Text("Update Now", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                    }
                }
            }
        }

        // 2. Soft Update Dialog (Dismissible)
        androidx.compose.animation.AnimatedVisibility(
            visible = showSoftUpdateDialog,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val isLight = !androidx.compose.foundation.isSystemInDarkTheme()
            val popupAlpha = if (isLight) 0.5f else 0.4f
            val containerColor = if (isLight) Color.White.copy(alpha = popupAlpha) else Color.Black.copy(alpha = popupAlpha)
            val popupShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showSoftUpdateDialog = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .drawBackdrop(
                            backdrop = mainBackdrop,
                            shape = { popupShape },
                            effects = {
                                vibrancy()
                                blur(16f.dp.toPx())
                                lens(12f.dp.toPx(), 24f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(containerColor)
                            }
                        )
                        .border(
                            width = 0.5.dp,
                            color = if (isLight) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                            shape = popupShape
                        )
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {},
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = Color(0xFF007AFF),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Update Available",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = if (isLight) Color.Black else Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateMessage,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = if (isLight) Color.DarkGray else Color.LightGray
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { showSoftUpdateDialog = false },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = androidx.compose.ui.graphics.RectangleShape
                        ) {
                            Text("Later", fontSize = 15.sp, color = Color(0xFF007AFF))
                        }
                        Box(modifier = Modifier.width(0.5.dp).fillMaxHeight().background(Color.Gray.copy(alpha = 0.3f)))
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showSoftUpdateDialog = false
                                if (updateUrl.isNotEmpty()) {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(updateUrl))
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = androidx.compose.ui.graphics.RectangleShape
                        ) {
                            Text("Update Now", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSearchClick: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop
) {
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    val activeColor = Color(0xFFFA243C) // Apple Music Red
    val inactiveColor = if (isLightTheme) Color.Black else Color.White
    val containerColor = if (isLightTheme) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.4f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        com.arcadesoftware.musix.components.LiquidBottomTabs(
            selectedTabIndex = selectedTab,
            onTabSelected = onTabSelected,
            backdrop = backdrop,
            tabsCount = 4,
            accentColor = activeColor,
            modifier = Modifier.weight(1f)
        ) {
            LiquidBottomTab(onClick = { onTabSelected(0) }) {
                Icon(
                    Icons.Rounded.Home,
                    contentDescription = "Home",
                    tint = if (selectedTab == 0) activeColor else inactiveColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Home",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selectedTab == 0) activeColor else inactiveColor
                )
            }
            LiquidBottomTab(onClick = { onTabSelected(1) }) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "Playlist",
                    tint = if (selectedTab == 1) activeColor else inactiveColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Playlist",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selectedTab == 1) activeColor else inactiveColor
                )
            }
            LiquidBottomTab(onClick = { onTabSelected(2) }) {
                Icon(
                    Icons.Rounded.LibraryMusic,
                    contentDescription = "Library",
                    tint = if (selectedTab == 2) activeColor else inactiveColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Library",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selectedTab == 2) activeColor else inactiveColor
                )
            }
            LiquidBottomTab(onClick = { onTabSelected(3) }) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = "Recommend",
                    tint = if (selectedTab == 3) activeColor else inactiveColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Recommend",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selectedTab == 3) activeColor else inactiveColor
                )
            }
        }

        com.arcadesoftware.musix.components.LiquidButton(
            onClick = onSearchClick,
            backdrop = backdrop,
            surfaceColor = containerColor,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = "Search",
                tint = inactiveColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
fun MiniPlayer(
    backdrop: com.kyant.backdrop.Backdrop,
    currentSong: YTItem?,
    modifier: Modifier = Modifier,
    collapsedBottomPadding: androidx.compose.ui.unit.Dp = 112.dp
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    var isFab by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyricsText by remember { mutableStateOf<String?>(null) }
    var lyricsLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var isLoadingLyrics by remember { mutableStateOf(false) }

    LaunchedEffect(currentSong?.id) {
        showLyrics = false
        lyricsText = null
        lyricsLines = emptyList()
        val song = currentSong ?: return@LaunchedEffect
        val songId = song.id
        isLoadingLyrics = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val artistNames = when (song) {
                    is SongItem -> song.artists?.joinToString { it.name } ?: "Unknown Artist"
                    is AlbumItem -> song.artists?.joinToString { it.name } ?: "Unknown Artist"
                    else -> "Unknown Artist"
                }
                val titleName = when (song) {
                    is SongItem -> song.title
                    is AlbumItem -> song.title
                    else -> "Unknown Title"
                }
                
                // 1. Try LrcLib first
                val lrcLyrics = fetchLrcLibLyrics(titleName, artistNames)
                if (lrcLyrics != null) {
                    if (lrcLyrics.startsWith("[")) {
                        val parsed = parseSyncedLyrics(lrcLyrics)
                        if (parsed.isNotEmpty()) {
                            lyricsLines = parsed
                            lyricsText = lrcLyrics
                            isLoadingLyrics = false
                            return@withContext
                        }
                    }
                    lyricsText = lrcLyrics
                    isLoadingLyrics = false
                    return@withContext
                }

                // 2. Fallback to YouTube Music lyrics
                val endpoint = WatchEndpoint(videoId = songId)
                YouTube.next(endpoint).onSuccess { nextResult ->
                    val lyricsEndpoint = nextResult.lyricsEndpoint
                    if (lyricsEndpoint != null) {
                        YouTube.lyrics(lyricsEndpoint).onSuccess { lyrics ->
                            lyricsText = lyrics ?: "No lyrics available."
                        }.onFailure {
                            lyricsText = "Failed to load lyrics."
                        }
                    } else {
                        lyricsText = "Lyrics not available for this song."
                    }
                }.onFailure {
                    lyricsText = "Failed to load lyrics endpoint."
                }
            } catch (e: Exception) {
                lyricsText = "Error loading lyrics: ${e.message}"
            } finally {
                isLoadingLyrics = false
            }
        }
    }
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    val isPlaying by PlayerManager.isPlaying.collectAsState()
    val currentPosition by PlayerManager.currentPosition.collectAsState()
    val currentDuration by PlayerManager.currentDuration.collectAsState()
    val playbackProgress = if (currentDuration > 0) currentPosition.toFloat() / currentDuration else 0f
    val currentAlpha by androidx.compose.animation.core.animateFloatAsState(if (expanded) 0.85f else (if (isLightTheme) 0.5f else 0.4f))
    val containerColor = if (isLightTheme) Color.White.copy(alpha = currentAlpha) else Color.Black.copy(alpha = currentAlpha)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val playPauseIcon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow

    val title = when (currentSong) {
        is SongItem -> currentSong.title
        is AlbumItem -> currentSong.title
        is PlaylistItem -> currentSong.title
        is ArtistItem -> currentSong.title
        else -> "Unknown Title"
    }
    val subtitle = when (currentSong) {
        is SongItem -> currentSong.artists?.joinToString { it.name } ?: "Unknown Artist"
        is AlbumItem -> currentSong.artists?.joinToString { it.name } ?: "Unknown Artist"
        is PlaylistItem -> currentSong.author?.name ?: "Playlist"
        is ArtistItem -> "Artist"
        else -> "Unknown Artist"
    }
    val thumbnail = when (currentSong) {
        is SongItem -> currentSong.thumbnail.replace(Regex("w\\d+-h\\d+.*"), "w1080-h1080-l90-rj")
        is AlbumItem -> currentSong.thumbnail.replace(Regex("w\\d+-h\\d+.*"), "w1080-h1080-l90-rj")
        is PlaylistItem -> currentSong.thumbnail.orEmpty().replace(Regex("w\\d+-h\\d+.*"), "w1080-h1080-l90-rj")
        is ArtistItem -> currentSong.thumbnail.orEmpty().replace(Regex("w\\d+-h\\d+.*"), "w1080-h1080-l90-rj")
        else -> ""
    }

    val bottomPadding by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else collapsedBottomPadding)
    val horizontalPadding by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else if (isFab) 45.dp else 45.dp)
    val cornerRadius by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else 100.dp)
    val currentBlur by androidx.compose.animation.core.animateDpAsState(if (expanded) 8.dp else 4.dp)

    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val targetWidth = if (expanded) screenWidth else if (isFab) 64.dp else screenWidth
    val animatedWidth by androidx.compose.animation.core.animateDpAsState(targetWidth)

    val targetStartPadding = if (expanded) 0.dp else if (isFab) 0.dp else horizontalPadding
    val targetEndPadding = if (expanded) 0.dp else if (isFab) 24.dp else horizontalPadding
    val startPadding by androidx.compose.animation.core.animateDpAsState(targetStartPadding)
    val endPadding by androidx.compose.animation.core.animateDpAsState(targetEndPadding)

    // Use fillMaxSize when expanded so the player covers the whole screen
    val playerModifier = if (expanded) {
        modifier
            .fillMaxSize()
            .pointerInput(expanded, isFab) {
                var isDragValid = false
                detectVerticalDragGestures(
                    onDragStart = { startPosition ->
                        focusManager.clearFocus()
                        isDragValid = startPosition.y < size.height * 0.65f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (isDragValid && dragAmount > 10f) expanded = false
                    }
                )
            }
    } else {
        modifier
            .navigationBarsPadding()
            .padding(bottom = bottomPadding, start = startPadding, end = endPadding)
            .width(animatedWidth)
            .height(64.dp)
            .pointerInput(expanded, isFab) {
                detectDragGestures(
                    onDragStart = {
                        focusManager.clearFocus()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                            if (isFab) {
                                if (dragAmount.x < -10f) isFab = false
                            } else {
                                if (dragAmount.x > 10f) isFab = true
                            }
                        } else {
                            if (dragAmount.y < -10f) expanded = true
                        }
                    }
                )
            }
    }

    val activePlaylistDetail by PlayerManager.activePlaylistDetail.collectAsState()

    com.arcadesoftware.musix.components.LiquidButton(
        onClick = {
            focusManager.clearFocus()
            if (isFab) {
                isFab = false
            } else if (!expanded) {
                val playlist = PlayerManager.currentPlayingPlaylist.value
                if (playlist != null && activePlaylistDetail == null) {
                    val id = (playlist as? PlaylistItem)?.id
                    if (id == "downloads" || id?.toLongOrNull() != null) {
                        // local playlist — don't open YT detail, just expand player
                        expanded = true
                    } else {
                        PlayerManager.activePlaylistDetail.value = playlist
                    }
                } else {
                    expanded = true
                }
            }
        },
        backdrop = backdrop,
        surfaceColor = containerColor,
        blurRadius = currentBlur,
        isInteractive = false,
        shape = { if (isFab) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(cornerRadius) },
        modifier = playerModifier
    ) {
        val consumeClicksModifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null
        ) {}

        if (!expanded) {
            // ---- Collapsed Mini Player (also used when isFab = true, showing full row) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = if (isFab) 6.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
                val rotationAngle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                    ),
                    label = "ringRotation"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp)
                ) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val strokeWidth = 2.5.dp.toPx()
                        val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val radius = (size.minDimension - strokeWidth) / 2f

                        if (isPlaying) {
                            rotate(rotationAngle) {
                                drawCircle(
                                    brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                        colors = listOf(
                                            Color(0xFFFA243C), // appleRed
                                            Color(0xFF8B5CF6), // Violet
                                            Color(0xFF06B6D4), // Cyan
                                            Color(0xFFEC4899), // Rose
                                            Color(0xFFFA243C)  // appleRed (to complete smoothly)
                                        ),
                                        center = centerOffset
                                    ),
                                    radius = radius,
                                    center = centerOffset,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                                )
                            }
                        } else {
                            drawCircle(
                                color = contentColor.copy(alpha = 0.15f),
                                radius = radius,
                                center = centerOffset,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.Gray.copy(0.5f))
                    ) {
                        AsyncImage(
                            model = thumbnail ?: "",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (!isFab) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(androidx.compose.ui.graphics.RectangleShape)
                    ) {
                        Text(
                            text = title,
                            color = contentColor,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Text(
                            subtitle,
                            color = contentColor.copy(0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = contentColor,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) {
                                focusManager.clearFocus()
                                PlayerManager.playPrevious()
                            }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .background(contentColor.copy(alpha = 0.05f), androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) {
                                focusManager.clearFocus()
                                PlayerManager.togglePlayPause()
                            }
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 2.5.dp.toPx()
                            val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                            val radius = (size.minDimension - strokeWidth) / 2f
                            
                            // Draw background track
                            drawCircle(
                                color = contentColor.copy(alpha = 0.15f),
                                radius = radius,
                                center = centerOffset,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                            )
                            
                            // Draw progress arc
                            drawArc(
                                color = contentColor,
                                startAngle = -90f,
                                sweepAngle = playbackProgress * 360f,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2f, strokeWidth / 2f),
                                size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = strokeWidth,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            )
                        }

                        Icon(
                            playPauseIcon,
                            contentDescription = "Play/Pause",
                            tint = contentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = contentColor,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) {
                                focusManager.clearFocus()
                                PlayerManager.playNext()
                            }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        } else {
            // ---- Expanded Full-Screen Player ----
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Drag handle at the top — tappable + swipeable to collapse
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { _, dragAmount ->
                                        if (dragAmount > 8f) expanded = false
                                    }
                                )
                            }
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { expanded = false }
                            .padding(horizontal = 40.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(contentColor.copy(alpha = 0.3f))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Album art / Lyrics Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Gray.copy(if (showLyrics) 0.1f else 0.3f))
                    ) {
                        // Always show album art underneath
                        AsyncImage(
                            model = thumbnail ?: "",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Lyrics overlay slides on top
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showLyrics,
                            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(400)) + androidx.compose.animation.slideInVertically(initialOffsetY = { it / 3 }, animationSpec = androidx.compose.animation.core.tween(400)),
                            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) + androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 3 }, animationSpec = androidx.compose.animation.core.tween(300))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = 0.85f),
                                                Color.Black.copy(alpha = 0.92f)
                                            )
                                        )
                                    )
                            ) {
                                when {
                                    isLoadingLyrics -> {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            io.github.robinpcrd.cupertino.CupertinoActivityIndicator(modifier = Modifier.size(32.dp))
                                        }
                                    }
                                    lyricsLines.isNotEmpty() -> {
                                        val currentPosition by PlayerManager.currentPosition.collectAsState()
                                        val activeIndex = remember(lyricsLines, currentPosition) {
                                            var index = -1
                                            for (i in lyricsLines.indices) {
                                                if (currentPosition >= lyricsLines[i].timestamp) index = i
                                                else break
                                            }
                                            index
                                        }
                                        val listState = rememberLazyListState()
                                        LaunchedEffect(activeIndex) {
                                            if (activeIndex >= 0) {
                                                listState.animateScrollToItem(
                                                    index = activeIndex,
                                                    scrollOffset = -120
                                                )
                                            }
                                        }
                                        // Top fade gradient
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            LazyColumn(
                                                state = listState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(vertical = 100.dp, horizontal = 20.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                                horizontalAlignment = Alignment.Start
                                            ) {
                                                itemsIndexed(lyricsLines) { index, line ->
                                                    if (line.text.isBlank()) {
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        return@itemsIndexed
                                                    }
                                                    val isActive = index == activeIndex
                                                    val isPast = index < activeIndex
                                                    val targetAlpha = when {
                                                        isActive -> 1f
                                                        isPast -> 0.3f
                                                        else -> 0.45f
                                                    }
                                                    val alpha by androidx.compose.animation.core.animateFloatAsState(
                                                        targetValue = targetAlpha,
                                                        animationSpec = androidx.compose.animation.core.tween(300),
                                                        label = "lyricAlpha"
                                                    )
                                                    val scale by androidx.compose.animation.core.animateFloatAsState(
                                                        targetValue = if (isActive) 1f else 0.95f,
                                                        animationSpec = androidx.compose.animation.core.spring(
                                                            dampingRatio = 0.7f,
                                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                                        ),
                                                        label = "lyricScale"
                                                    )
                                                    Text(
                                                        text = line.text,
                                                        color = Color.White.copy(alpha = alpha),
                                                        style = MaterialTheme.typography.headlineSmall.copy(
                                                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                                                            fontSize = if (isActive) 26.sp else 20.sp,
                                                            lineHeight = 34.sp
                                                        ),
                                                        modifier = Modifier
                                                            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                                                            .fillMaxWidth()
                                                            .clickable(
                                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                                indication = null
                                                            ) {
                                                                val duration = PlayerManager.currentDuration.value
                                                                if (duration > 0) {
                                                                    val progress = line.timestamp.toFloat() / duration
                                                                    PlayerManager.seekTo(progress.coerceIn(0f, 1f))
                                                                }
                                                            }
                                                    )
                                                }
                                            }
                                            // Top fade
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(80.dp)
                                                    .background(
                                                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                                            colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)
                                                        )
                                                    )
                                                    .align(Alignment.TopCenter)
                                            )
                                            // Bottom fade
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(80.dp)
                                                    .background(
                                                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                                                        )
                                                    )
                                                    .align(Alignment.BottomCenter)
                                            )
                                        }
                                    }
                                    else -> {
                                        val scrollState = rememberScrollState()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(scrollState)
                                                .padding(horizontal = 24.dp, vertical = 32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = lyricsText ?: "No lyrics available.",
                                                color = Color.White.copy(alpha = 0.8f),
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    lineHeight = 30.sp,
                                                    fontSize = 18.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Title + like button row
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp)
                        ) {
                            Text(
                                title,
                                color = contentColor,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                subtitle,
                                color = contentColor.copy(0.7f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        val context = LocalContext.current
                        val isSongLiked by PlayerManager.isCurrentSongLiked.collectAsState()
                        Icon(
                            imageVector = if (isSongLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (isSongLiked) Color(0xFFFA243C) else contentColor,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable {
                                    currentSong?.id?.let { songId ->
                                        val nowLiked = com.arcadesoftware.musix.db.LikedSongsManager.toggleLikeSong(context, songId)
                                        PlayerManager.isCurrentSongLiked.value = nowLiked
                                        if (nowLiked) {
                                            com.arcadesoftware.musix.components.HeartAnimManager.trigger()
                                        }
                                        PlayerManager.triggerNotificationUpdate()
                                    }
                                }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Seekbar with time labels
                    var sliderDragValue by remember { mutableStateOf<Float?>(null) }
                    val sliderValue = sliderDragValue
                        ?: (if (currentDuration > 0) currentPosition.toFloat() / currentDuration else 0f)

                    com.arcadesoftware.musix.components.LiquidSlider(
                        value = { sliderValue },
                        onValueChange = { newVal ->
                            // Live drag: update thumb display immediately without triggering position poll race
                            sliderDragValue = newVal
                        },
                        onValueChangeFinished = {
                            // Seek once when user lifts finger
                            sliderDragValue?.let { PlayerManager.seekTo(it) }
                            sliderDragValue = null
                        },
                        valueRange = 0f..1f,
                        visibilityThreshold = 0.001f,
                        backdrop = backdrop,
                        accentColor = contentColor,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Time labels
                    val displayPosition = if (sliderDragValue != null && currentDuration > 0)
                        (sliderDragValue!! * currentDuration).toLong()
                    else currentPosition
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(displayPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(0.6f)
                        )
                        Text(
                            text = formatDuration(currentDuration),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(0.6f)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Playback controls
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = contentColor,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { PlayerManager.playPrevious() }
                        )
                        // 10 sec backward
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    val pos = PlayerManager.currentPosition.value
                                    val dur = PlayerManager.currentDuration.value
                                    if (dur > 0) PlayerManager.seekTo(((pos - 10_000L).coerceAtLeast(0L).toFloat() / dur).coerceIn(0f, 1f))
                                }
                        ) {
                            Icon(Icons.Rounded.Replay10, contentDescription = "-10s", tint = contentColor, modifier = Modifier.fillMaxSize())
                        }
                        Icon(
                            playPauseIcon,
                            contentDescription = "Play/Pause",
                            tint = contentColor,
                            modifier = Modifier
                                .size(64.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { PlayerManager.togglePlayPause() }
                        )
                        // 10 sec forward
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    val pos = PlayerManager.currentPosition.value
                                    val dur = PlayerManager.currentDuration.value
                                    if (dur > 0) PlayerManager.seekTo(((pos + 10_000L).coerceAtMost(dur).toFloat() / dur).coerceIn(0f, 1f))
                                }
                        ) {
                            Icon(Icons.Rounded.Forward10, contentDescription = "+10s", tint = contentColor, modifier = Modifier.fillMaxSize())
                        }
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = contentColor,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { PlayerManager.playNext() }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Secondary controls
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var showAddToPlaylist by remember { mutableStateOf(false) }
                        val addSong = currentSong as? SongItem
                        Icon(
                            imageVector = Icons.Rounded.AddCircleOutline,
                            contentDescription = "Add to Playlist",
                            tint = contentColor.copy(0.8f),
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { if (addSong != null) showAddToPlaylist = true }
                        )
                        if (showAddToPlaylist && addSong != null) {
                            com.arcadesoftware.musix.components.AddToPlaylistSheet(
                                song = addSong,
                                onDismiss = { showAddToPlaylist = false }
                            )
                        }
                        val downloadContext = LocalContext.current
                        val db = remember(downloadContext) { com.arcadesoftware.musix.db.AppDatabase.getDatabase(downloadContext) }

                        val downloadedSongState = remember(currentSong) {
                            val song = currentSong as? SongItem
                            if (song != null) {
                                db.musicDao().getDownloadedSongFlow(song.id)
                            } else {
                                kotlinx.coroutines.flow.flowOf(null)
                            }
                        }.collectAsState(initial = null)

                        val isDownloaded = downloadedSongState.value?.let { downloadedSong ->
                            !downloadedSong.localFilePath.isNullOrEmpty() && java.io.File(downloadedSong.localFilePath).exists()
                        } ?: false

                        val downloadProgressMap by PlayerManager.downloadProgressMap.collectAsState()
                        val songId = (currentSong as? SongItem)?.id ?: ""
                        val isDownloading = downloadProgressMap.containsKey(songId)
                        val downloadProgress = downloadProgressMap[songId] ?: 0f

                        androidx.compose.animation.AnimatedContent(
                            targetState = when {
                                isDownloaded -> 2
                                isDownloading -> 1
                                else -> 0
                            },
                            label = "download"
                        ) { state ->
                            when (state) {
                                2 -> Icon(
                                    imageVector = Icons.Rounded.DownloadDone,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp).then(consumeClicksModifier)
                                )
                                1 -> {
                                    val primaryColor = MaterialTheme.colorScheme.primary
                                    androidx.compose.foundation.Canvas(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                if (songId.isNotEmpty()) {
                                                    PlayerManager.cancelDownload(songId)
                                                }
                                            }
                                    ) {
                                        val sizePx = size.minDimension
                                        val strokeWidthPx = 2.dp.toPx()
                                        val radius = (sizePx - strokeWidthPx) / 2f
                                        val centerOffset = androidx.compose.ui.geometry.Offset(sizePx / 2f, sizePx / 2f)

                                        // Draw track
                                        drawCircle(
                                            color = primaryColor.copy(alpha = 0.2f),
                                            radius = radius,
                                            center = centerOffset,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidthPx)
                                        )

                                        // Draw progress arc
                                        drawArc(
                                            color = primaryColor,
                                            startAngle = -90f,
                                            sweepAngle = downloadProgress * 360f,
                                            useCenter = false,
                                            topLeft = androidx.compose.ui.geometry.Offset(strokeWidthPx / 2f, strokeWidthPx / 2f),
                                            size = androidx.compose.ui.geometry.Size(sizePx - strokeWidthPx, sizePx - strokeWidthPx),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                width = strokeWidthPx,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        )

                                        // Draw stop/pause square in the center
                                        val squareSize = 8.dp.toPx()
                                        val squareLeft = (sizePx - squareSize) / 2f
                                        val squareTop = (sizePx - squareSize) / 2f
                                        val cornerRadiusPx = 1.5.dp.toPx()
                                        drawRoundRect(
                                            color = primaryColor,
                                            topLeft = androidx.compose.ui.geometry.Offset(squareLeft, squareTop),
                                            size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
                                        )
                                    }
                                }
                                else -> Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Download",
                                    tint = contentColor.copy(0.8f),
                                    modifier = Modifier.size(28.dp).clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        val song = currentSong as? SongItem
                                        if (song != null) {
                                            PlayerManager.startDownload(song, downloadContext)
                                        }
                                    }
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) MaterialTheme.colorScheme.primary else contentColor.copy(0.8f),
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    showLyrics = !showLyrics
                                }
                        )
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Up Next",
                            tint = contentColor.copy(0.8f),
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    showQueue = true
                                }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // System volume control row at the very bottom
                    val context = LocalContext.current
                    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
                    var volumeState by remember {
                        mutableStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                    }
                    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }
                    var lastVolume by remember { mutableStateOf(volumeState.takeIf { it > 0 } ?: (maxVolume / 2)) }
                    var volumeDragValue by remember { mutableStateOf<Float?>(null) }
                    val volumeSliderValue = volumeDragValue
                        ?: (if (maxVolume > 0) volumeState.toFloat() / maxVolume else 0f)

                    androidx.compose.runtime.DisposableEffect(context) {
                        val receiver = object : android.content.BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                                volumeState = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                            }
                        }
                        val filter = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
                        context.registerReceiver(receiver, filter)
                        onDispose {
                            try {
                                context.unregisterReceiver(receiver)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isMuted = volumeState == 0
                        Icon(
                            imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            contentDescription = "Mute Toggle",
                            tint = contentColor.copy(alpha = 0.8f),
                            modifier = Modifier
                                .size(26.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (isMuted) {
                                        val target = if (lastVolume > 0) lastVolume else (maxVolume / 2)
                                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                                        volumeState = target
                                    } else {
                                        lastVolume = volumeState
                                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                                        volumeState = 0
                                    }
                                }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        com.arcadesoftware.musix.components.LiquidSlider(
                            value = { volumeSliderValue },
                            onValueChange = { newVal ->
                                volumeDragValue = newVal
                                val targetVol = (newVal * maxVolume + 0.5f).toInt().coerceIn(0, maxVolume)
                                if (targetVol != volumeState) {
                                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
                                    volumeState = targetVol
                                }
                            },
                            onValueChangeFinished = {
                                volumeDragValue = null
                            },
                            valueRange = 0f..1f,
                            visibilityThreshold = 0.001f,
                            backdrop = backdrop,
                            accentColor = Color(0xFFFA243C), // appleRed accent to differentiate from progress slider
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Queue/Playlist Sheet Overlay (Spotify-style)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showQueue,
                    enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                    exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val queueItems by PlayerManager.queue.collectAsState()
                    val currentIndex by PlayerManager.currentQueueIndex.collectAsState()
                    val playingPlaylist by PlayerManager.currentPlayingPlaylist.collectAsState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .systemBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ -> change.consume() }
                            }
                    ) {
                        // Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(onClick = { showQueue = false }) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", tint = contentColor)
                            }
                            Text(
                                text = "Playing Queue",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = contentColor,
                                modifier = Modifier.weight(1f)
                            )
                            if (playingPlaylist != null) {
                                TextButton(onClick = {
                                    PlayerManager.activePlaylistDetail.value = playingPlaylist
                                    showQueue = false
                                    expanded = false
                                }) {
                                    Text("View Playlist", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Queue List
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            item {
                                Text(
                                    text = "Now Playing",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = contentColor.copy(0.6f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            currentSong?.let { song ->
                                item {
                                    QueueRow(
                                        song = song,
                                        isCurrent = true,
                                        contentColor = contentColor,
                                        onClick = {}
                                    )
                                }
                            }

                            item {
                                Text(
                                    text = "Next In Queue",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = contentColor.copy(0.6f),
                                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                                )
                            }

                            val nextItems = if (currentIndex < queueItems.size - 1) {
                                queueItems.subList(currentIndex + 1, queueItems.size)
                            } else {
                                emptyList()
                            }

                            if (nextItems.isEmpty()) {
                                item {
                                    Text(
                                        text = "Queue is empty. Auto-play is enabled.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(0.5f),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            } else {
                                itemsIndexed(nextItems) { idx, item ->
                                    val actualIndex = currentIndex + 1 + idx
                                    QueueRow(
                                        song = item,
                                        isCurrent = false,
                                        contentColor = contentColor,
                                        onClick = {
                                            PlayerManager.playQueue(queueItems, actualIndex)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun QueueRow(
    song: YTItem,
    isCurrent: Boolean,
    contentColor: Color,
    onClick: () -> Unit
) {
    val title = when (song) {
        is SongItem -> song.title
        is AlbumItem -> song.title
        is PlaylistItem -> song.title
        else -> ""
    }
    val artist = when (song) {
        is SongItem -> song.artists?.joinToString { it.name } ?: ""
        is AlbumItem -> song.artists?.joinToString { it.name } ?: ""
        is PlaylistItem -> song.author?.name ?: ""
        else -> ""
    }
    val thumbnail = song.thumbnail ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isCurrent, onClick = onClick)
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(0.7f) else contentColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isCurrent) {
            Icon(
                Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = "Playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return when {
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
        else -> false
    }
}

data class LyricLine(val timestamp: Long, val text: String)

fun parseSyncedLyrics(lyrics: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val regex = Regex("""\[(\d+):(\d+)\.(\d+)\](.*)""")
    lyrics.lines().forEach { line ->
        val match = regex.matchEntire(line.trim())
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msStr = match.groupValues[3]
            val ms = (msStr.padEnd(3, '0').substring(0, 3)).toLong()
            val time = min * 60 * 1000 + sec * 1000 + ms
            val text = match.groupValues[4].trim()
            lines.add(LyricLine(time, text))
        }
    }
    return lines.sortedBy { it.timestamp }
}

suspend fun fetchLrcLibLyrics(title: String, artist: String): String? {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val cleanTitle = title.replace(Regex("""\(.*?\)"""), "").trim()
            val cleanArtist = artist.split(",", "&", "feat").firstOrNull()?.trim() ?: artist
            val urlStr = "https://lrclib.net/api/get?artist_name=" + 
                java.net.URLEncoder.encode(cleanArtist, "UTF-8") + 
                "&track_name=" + java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "MusixLyricsClient/1.0")
            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(responseText)
                if (json.has("syncedLyrics") && !json.isNull("syncedLyrics")) {
                    return@withContext json.getString("syncedLyrics")
                }
                if (json.has("plainLyrics") && !json.isNull("plainLyrics")) {
                    return@withContext json.getString("plainLyrics")
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}