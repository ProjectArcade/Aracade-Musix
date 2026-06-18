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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.arcadesoftware.musix.ui.screens.HomeScreen
import com.arcadesoftware.musix.ui.screens.PlaylistScreen
import com.arcadesoftware.musix.ui.screens.RecommendationsScreen

import androidx.compose.ui.text.font.FontWeight
import com.arcadesoftware.musix.ui.screens.PlaylistDetailScreen
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    val currentSong = MutableStateFlow<YTItem?>(null)
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val currentDuration = MutableStateFlow(0L)
    val queue = MutableStateFlow<List<YTItem>>(emptyList())
    val currentQueueIndex = MutableStateFlow(0)
    val autoPlayEnabled = MutableStateFlow(true)
    val activePlaylistDetail = MutableStateFlow<YTItem?>(null)
    val currentPlayingPlaylist = MutableStateFlow<YTItem?>(null)
    private var appContext: android.content.Context? = null
    private var mediaSession: android.media.session.MediaSession? = null
    private var lastThumbnailUrl: String? = null
    private var currentMetadataSongId: String? = null
    private var currentMetadataDuration: Long = 0L
    private var currentMetadataBitmap: android.graphics.Bitmap? = null
    var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val downloadProgressMap = MutableStateFlow<Map<String, Float>>(emptyMap())

    private var simpleCache: androidx.media3.datasource.cache.SimpleCache? = null

    @Synchronized
    private fun getCache(context: android.content.Context): androidx.media3.datasource.cache.SimpleCache {
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
        com.music.innertube.models.YouTubeClient.ANDROID_VR_NO_AUTH,
        com.music.innertube.models.YouTubeClient.ANDROID_VR_1_43_32,
        com.music.innertube.models.YouTubeClient.ANDROID_VR_1_61_48,
        com.music.innertube.models.YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        com.music.innertube.models.YouTubeClient.TVHTML5,
        com.music.innertube.models.YouTubeClient.ANDROID_CREATOR,
        com.music.innertube.models.YouTubeClient.IPADOS,
        com.music.innertube.models.YouTubeClient.IOS,
        com.music.innertube.models.YouTubeClient.WEB,
        com.music.innertube.models.YouTubeClient.WEB_REMIX,
        com.music.innertube.models.YouTubeClient.WEB_CREATOR,
        com.music.innertube.models.YouTubeClient.MOBILE,
        com.music.innertube.models.YouTubeClient.ANDROID_NO_SDK,
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

    fun init(context: android.content.Context) {
        if (exoPlayer == null) {
            appContext = context.applicationContext

            // Initialize platform MediaSession
            val session = android.media.session.MediaSession(context, "MusixPlayer").apply {
                isActive = true
                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() {
                        exoPlayer?.play()
                    }
                    override fun onPause() {
                        exoPlayer?.pause()
                    }
                    override fun onSkipToNext() {
                        // Skip to next if playlist is active
                    }
                    override fun onSkipToPrevious() {
                        exoPlayer?.seekTo(0)
                    }
                    override fun onSeekTo(pos: Long) {
                        exoPlayer?.seekTo(pos)
                        currentPosition.value = pos
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
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                    when (intent.action) {
                        ACTION_PLAY -> exoPlayer?.play()
                        ACTION_PAUSE -> exoPlayer?.pause()
                        ACTION_PREVIOUS -> playPrevious()
                        ACTION_NEXT -> playNext()
                        ACTION_DISMISS -> {
                            appContext?.let { com.arcadesoftware.musix.PlaybackService.stop(it) }
                        }
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
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
                            com.arcadesoftware.musix.PlaybackService.start(ctx)
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateStr = when (playbackState) {
                        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                        androidx.media3.common.Player.STATE_READY -> "READY"
                        androidx.media3.common.Player.STATE_ENDED -> {
                            val currentIndex = currentQueueIndex.value
                            val currentQueue = queue.value
                            if (currentIndex < currentQueue.size - 1) {
                                currentQueueIndex.value = currentIndex + 1
                                playInternal(currentQueue[currentIndex + 1])
                            } else if (autoPlayEnabled.value) {
                                currentSong.value?.let { song ->
                                    scope.launch {
                                        val endpoint = com.music.innertube.models.WatchEndpoint(videoId = song.id)
                                        YouTube.next(endpoint).onSuccess { nextResult ->
                                            val nextItems = nextResult.items.filter { it.id != song.id }
                                            if (nextItems.isNotEmpty()) {
                                                val newQueue = currentQueue + nextItems
                                                queue.value = newQueue
                                                currentQueueIndex.value = currentIndex + 1
                                                playInternal(newQueue[currentIndex + 1])
                                            }
                                        }
                                    }
                                }
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
        scope.launch {
            withContext(Dispatchers.Main) {
                currentSong.value = song
                updatePlaybackDetails()
            }
            withContext(Dispatchers.Main) {
                exoPlayer?.stop()
                exoPlayer?.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(localFilePath))))
                exoPlayer?.prepare()
                exoPlayer?.play()
                updatePlaybackDetails()
            }
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
    ): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
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

    fun startDownload(song: SongItem, context: android.content.Context) {
        val songId = song.id
        synchronized(downloadProgressMap) {
            if (downloadProgressMap.value.containsKey(songId)) return
            downloadProgressMap.value = downloadProgressMap.value + (songId to 0f)
        }
        scope.launch(Dispatchers.IO) {
            val destFile = java.io.File(
                context.applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
                "$songId.m4a"
            )
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
            }
            synchronized(downloadProgressMap) {
                downloadProgressMap.value = downloadProgressMap.value - songId
            }
        }
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
                scope.launch {
                    val endpoint = com.music.innertube.models.WatchEndpoint(videoId = song.id)
                    YouTube.next(endpoint).onSuccess { nextResult ->
                        val nextItems = nextResult.items.filter { it.id != song.id }
                        if (nextItems.isNotEmpty()) {
                            val newQueue = currentQueue + nextItems
                            queue.value = newQueue
                            currentQueueIndex.value = currentIndex + 1
                            playInternal(newQueue[currentIndex + 1])
                        }
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
                }
            }

            withContext(Dispatchers.Main) {
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
                    withContext(Dispatchers.Main) {
                        exoPlayer?.stop()
                        exoPlayer?.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(localFile)))
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
                withContext(Dispatchers.Main) {
                    exoPlayer?.stop()
                    exoPlayer?.setMediaItem(MediaItem.fromUri(streamUrl!!))
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    updatePlaybackDetails()
                }
            } else {
                android.util.Log.e(TAG, "All clients failed for videoId=$videoId")
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    private fun startProgressUpdates() {
        scope.launch {
            while (true) {
                withContext(Dispatchers.Main) {
                    exoPlayer?.let { player ->
                        if (player.isPlaying || player.playbackState == androidx.media3.common.Player.STATE_READY) {
                            currentPosition.value = player.currentPosition
                            currentDuration.value = player.duration.coerceAtLeast(0L)
                            updatePlaybackState()
                        }
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    fun seekTo(fraction: Float) {
        exoPlayer?.let { player ->
            val duration = player.duration
            if (duration > 0) {
                val position = (duration * fraction).toLong()
                player.seekTo(position)
                currentPosition.value = position
                updatePlaybackState()
            }
        }
    }

    private fun updatePlaybackState() {
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
            }
            session.setMetadata(metadataBuilder.build())
        }

        val playbackState = android.media.session.PlaybackState.Builder()
            .setState(state, player.currentPosition, 1.0f)
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY or
                android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_SEEK_TO or
                android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
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

        currentMetadataSongId = song.id
        currentMetadataDuration = targetDuration
        currentMetadataBitmap = null // Reset bitmap for new song

        val metadataBuilder = android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artists.joinToString { it.name })
        if (targetDuration > 0) {
            metadataBuilder.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, targetDuration)
        }
        session.setMetadata(metadataBuilder.build())

        updatePlaybackState()
        showOrUpdateNotification()

        if (song.thumbnail.isNotEmpty() && song.thumbnail != lastThumbnailUrl) {
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
                    withContext(Dispatchers.Main) {
                        val activeSong = currentSong.value as? SongItem
                        if (activeSong?.id == song.id) {
                            currentMetadataBitmap = b
                            val meta = android.media.MediaMetadata.Builder()
                                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
                                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artists.joinToString { it.name })
                                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, b)
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
        val isPlayingVal = isPlaying.value

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

        val playIntent = android.app.PendingIntent.getBroadcast(
            context, 0, android.content.Intent(ACTION_PLAY),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = android.app.PendingIntent.getBroadcast(
            context, 1, android.content.Intent(ACTION_PAUSE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = android.app.PendingIntent.getBroadcast(
            context, 2, android.content.Intent(ACTION_PREVIOUS),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = android.app.PendingIntent.getBroadcast(
            context, 3, android.content.Intent(ACTION_NEXT),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = android.app.PendingIntent.getBroadcast(
            context, 5, android.content.Intent(ACTION_DISMISS),
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
            .setOngoing(isPlayingVal)

        val bitmapToUse = largeIcon ?: currentMetadataBitmap
        if (bitmapToUse != null) {
            builder.setLargeIcon(bitmapToUse)
        }

        builder.addAction(
            android.app.Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", prevIntent
            ).build()
        )
        if (isPlayingVal) {
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
                .setShowActionsInCompactView(0, 1, 2)
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
                    service.updateForegroundNotification(notification, isPlayingVal)
                } ?: run {
                    notificationManager.notify(1001, notification)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to show notification: ${e.message}")
        }
    }

    fun triggerNotificationUpdate() {
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

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        android.util.Log.d("MainScreen", "POST_NOTIFICATIONS permission granted: $isGranted")
    }
    
    LaunchedEffect(Unit) {
        PlayerManager.init(context.applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        MusixUpdater.checkForUpdate(
            context = context,
            onUpdateFound = { version, description, apkUrl ->
//                Toast.makeText(context, "Update v$version available!", Toast.LENGTH_LONG).show()
            },
            onNoUpdate = {}
        )
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                AppBottomBar(
                    selectedTab = selectedTab,
                    onTabSelected = { 
                        selectedTab = it
                    },
                    onSearchClick = { 
                        context.startActivity(android.content.Intent(context, SearchActivity::class.java))
                    },
                    backdrop = mainBackdrop
                )
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
                    0 -> HomeScreen()
                    1 -> PlaylistScreen(backdrop = playlistBackdrop)
                    2 -> PlaylistScreen(backdrop = playlistBackdrop)
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
            MiniPlayer(backdrop = currentBackdrop, currentSong = currentSong)
        }

        com.arcadesoftware.musix.components.FloatingHeartsContainer()
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
    var expanded by remember { mutableStateOf(false) }
    var isFab by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    val isPlaying by PlayerManager.isPlaying.collectAsState()
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

    val bottomPadding by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else if (isFab) collapsedBottomPadding + 80.dp else collapsedBottomPadding)
    val horizontalPadding by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else if (isFab) 16.dp else 45.dp)
    val cornerRadius by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else if (isFab) 32.dp else 100.dp)
    val currentBlur by androidx.compose.animation.core.animateDpAsState(if (expanded) 8.dp else 4.dp)

    // Use fillMaxSize when expanded so the player covers the whole screen
    val playerModifier = if (expanded) {
        modifier
            .fillMaxSize()
            .pointerInput(expanded, isFab) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 10f) expanded = false
                    }
                )
            }
    } else if (isFab) {
        modifier
            .padding(bottom = bottomPadding, end = horizontalPadding)
            .size(64.dp)
            .pointerInput(expanded, isFab) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        if (dragAmount < -10f) isFab = false
                    }
                )
            }
    } else {
        modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding, start = horizontalPadding, end = horizontalPadding)
            .pointerInput(expanded, isFab) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                            if (dragAmount.x > 10f) isFab = true
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
            if (isFab) {
                isFab = false 
            } else if (!expanded) {
                val playlist = PlayerManager.currentPlayingPlaylist.value
                if (playlist != null && activePlaylistDetail == null) {
                    PlayerManager.activePlaylistDetail.value = playlist
                } else {
                    expanded = true
                }
            }
        },
        backdrop = backdrop,
        surfaceColor = containerColor,
        blurRadius = currentBlur,
        isInteractive = false,
        shape = { RoundedCornerShape(cornerRadius) },
        modifier = playerModifier
    ) {
        val consumeClicksModifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null
        ) {}

        if (isFab) {
            Icon(
                Icons.Rounded.LibraryMusic, 
                contentDescription = "Player", 
                tint = contentColor,
                modifier = Modifier.size(26.dp)
            )
            return@LiquidButton
        }

        if (!expanded) {
            // ---- Collapsed Mini Player ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(3000, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                    )
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp)
                ) {
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = rotation }
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                        listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)
                                    ),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
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
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(androidx.compose.ui.graphics.RectangleShape) // Clip marquee
                ) {
                    Text(
                        text = title,
                        color = contentColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                    Text(
                        subtitle,
                        color = contentColor.copy(0.7f),
                        style = MaterialTheme.typography.labelSmall,
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
                        ) { PlayerManager.playPrevious() }
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    playPauseIcon,
                    contentDescription = "Play/Pause",
                    tint = contentColor,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { PlayerManager.togglePlayPause() }
                )
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
                        ) { PlayerManager.playNext() }
                )
                Spacer(modifier = Modifier.width(4.dp))
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
                // Drag handle at the top
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(contentColor.copy(alpha = 0.3f))
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Album art — square, max width but respecting available height
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Gray.copy(0.3f))
                ) {
                    AsyncImage(
                        model = thumbnail ?: "",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
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
                    var isSongLiked by remember(currentSong?.id) {
                        mutableStateOf(currentSong?.id?.let { com.arcadesoftware.musix.db.LikedSongsManager.isSongLiked(context, it) } ?: false)
                    }
                    Icon(
                        imageVector = if (isSongLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isSongLiked) Color(0xFFFA243C) else contentColor,
                        modifier = Modifier
                            .size(28.dp)
                            .clickable {
                                currentSong?.id?.let { songId ->
                                    val nowLiked = com.arcadesoftware.musix.db.LikedSongsManager.toggleLikeSong(context, songId)
                                    isSongLiked = nowLiked
                                    if (nowLiked) {
                                        com.arcadesoftware.musix.components.HeartAnimManager.trigger()
                                    }
                                }
                            }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Seekbar with time labels
                val currentPosition by PlayerManager.currentPosition.collectAsState()
                val currentDuration by PlayerManager.currentDuration.collectAsState()
                var sliderDragValue by remember { mutableStateOf<Float?>(null) }
                val sliderValue = sliderDragValue
                    ?: (if (currentDuration > 0) currentPosition.toFloat() / currentDuration else 0f)

                val sliderConsumeGesture = Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures { _, _ -> }
                }

                com.arcadesoftware.musix.components.LiquidSlider(
                    value = { sliderValue },
                    onValueChange = { sliderDragValue = it },
                    onValueChangeFinished = {
                        sliderDragValue?.let { PlayerManager.seekTo(it) }
                        sliderDragValue = null
                    },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.001f,
                    backdrop = backdrop,
                    accentColor = contentColor,
                    modifier = Modifier.fillMaxWidth().then(sliderConsumeGesture)
                )

                Spacer(modifier = Modifier.height(4.dp))

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
                            .size(48.dp)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { PlayerManager.playPrevious() }
                    )
                    Icon(
                        playPauseIcon,
                        contentDescription = "Play/Pause",
                        tint = contentColor,
                        modifier = Modifier
                            .size(72.dp)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { PlayerManager.togglePlayPause() }
                    )
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = contentColor,
                        modifier = Modifier
                            .size(48.dp)
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
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(28.dp).then(consumeClicksModifier)
                                ) {
                                    CircularProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxSize(),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(1.5.dp))
                                            .background(MaterialTheme.colorScheme.primary)
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
                    Icon(Icons.Rounded.Lyrics, contentDescription = "Lyrics", tint = contentColor.copy(0.8f), modifier = Modifier.size(28.dp).then(consumeClicksModifier))
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
                Spacer(modifier = Modifier.height(8.dp))
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
