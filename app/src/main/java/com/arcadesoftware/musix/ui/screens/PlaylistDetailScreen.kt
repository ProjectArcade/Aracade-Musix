package com.arcadesoftware.musix.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arcadesoftware.musix.PlayerManager
import com.arcadesoftware.musix.db.LikedPlaylistsManager
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.music.innertube.YouTube
import com.music.innertube.models.*
import io.github.robinpcrd.cupertino.CupertinoActivityIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistItem: YTItem,
    backdrop: LayerBackdrop, // Used for background layer and glassmorphism mapping
    onBack: () -> Unit
) {
    var songs by remember { mutableStateOf<List<SongItem>?>(null) }
    var description by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val appleRed = Color(0xFFFA243C)

    var isLiked by remember(playlistItem.id) {
        mutableStateOf(LikedPlaylistsManager.isPlaylistLiked(context, playlistItem.id))
    }

    val downloadProgressMap by PlayerManager.downloadProgressMap.collectAsState()
    val downloadedSongsState = remember(context) {
        com.arcadesoftware.musix.db.AppDatabase.getDatabase(context).musicDao().getDownloadedSongs()
    }.collectAsState(initial = emptyList())

    val title = when (playlistItem) {
        is PlaylistItem -> playlistItem.title
        is AlbumItem -> playlistItem.title
        else -> "Playlist"
    }

    val subtitle = when (playlistItem) {
        is PlaylistItem -> playlistItem.author?.name ?: "Playlist"
        is AlbumItem -> playlistItem.artists?.joinToString { it.name } ?: "Album"
        else -> ""
    }

    val thumbnail = when (playlistItem) {
        is PlaylistItem -> playlistItem.thumbnail
        is AlbumItem -> playlistItem.thumbnail
        else -> null
    }

    val totalDurationSeconds = remember(songs) {
        songs?.mapNotNull { it.duration }?.sum() ?: 0
    }

    val formattedPlaytime = remember(totalDurationSeconds) {
        if (totalDurationSeconds <= 0) ""
        else {
            val hours = totalDurationSeconds / 3600
            val minutes = (totalDurationSeconds % 3600) / 60
            if (hours > 0) {
                if (minutes > 0) "$hours hr $minutes min" else "$hours hr"
            } else {
                "$minutes min"
            }
        }
    }

    val metaText = remember(playlistItem, songs, formattedPlaytime) {
        val typeStr = when (playlistItem) {
            is PlaylistItem -> "PLAYLIST"
            is AlbumItem -> "ALBUM"
            else -> "MUSIC"
        }
        
        val songCountStr = when (playlistItem) {
            is PlaylistItem -> playlistItem.songCountText?.substringBefore(" ")?.toIntOrNull()?.let { "$it Songs" } ?: playlistItem.songCountText
            is AlbumItem -> songs?.size?.let { "$it Songs" } ?: "Album"
            else -> null
        }

        val yearStr = (playlistItem as? AlbumItem)?.year?.toString()

        val detailsList = mutableListOf<String>()
        if (yearStr != null) {
            detailsList.add(yearStr)
        }
        if (songCountStr != null) {
            detailsList.add(songCountStr)
        }
        if (formattedPlaytime.isNotEmpty()) {
            detailsList.add(formattedPlaytime)
        }

        val detailsJoined = detailsList.joinToString(" • ").uppercase()
        if (detailsJoined.isNotEmpty()) "$typeStr • $detailsJoined" else typeStr
    }

    LaunchedEffect(playlistItem.id) {
        isLoading = true
        errorMsg = null
        try {
            val fetchedData = withContext(Dispatchers.IO) {
                if (playlistItem is PlaylistItem) {
                    YouTube.playlist(playlistItem.id).getOrNull()?.let { Pair(it.songs, null) }
                } else if (playlistItem is AlbumItem) {
                    YouTube.album(playlistItem.browseId).getOrNull()?.let { Pair(it.songs, it.description) }
                } else {
                    null
                }
            }
            if (fetchedData != null) {
                songs = fetchedData.first
                description = fetchedData.second
            } else {
                errorMsg = "Failed to load tracks. Please try again."
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "An unexpected error occurred."
        } finally {
            isLoading = false
        }
    }

    val listState = rememberLazyListState()
    val showMiniTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 380)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CupertinoActivityIndicator(modifier = Modifier.size(48.dp))
            }
        } else if (errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = "Error",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = errorMsg!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMsg = null
                                val fetchedData = withContext(Dispatchers.IO) {
                                    if (playlistItem is PlaylistItem) {
                                        YouTube.playlist(playlistItem.id).getOrNull()?.let { Pair(it.songs, null) }
                                    } else if (playlistItem is AlbumItem) {
                                        YouTube.album(playlistItem.browseId).getOrNull()?.let { Pair(it.songs, it.description) }
                                    } else {
                                        null
                                    }
                                }
                                if (fetchedData != null) {
                                    songs = fetchedData.first
                                    description = fetchedData.second
                                } else {
                                    errorMsg = "Failed to load tracks. Please try again."
                                }
                                isLoading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = appleRed)
                    ) {
                        Text("Retry")
                    }
                }
            }
        } else {
            // Main backdrop layer containing the background image and the list.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                // Ambient Blurred Cover Artwork Background (Local to details page, no homescreen leakage)
                if (!thumbnail.isNullOrEmpty()) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.18f)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )
                }

                // Main content LazyColumn
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 96.dp, bottom = 120.dp)
                ) {
                    // Centered Album Art & Metadata (Apple Music Style)
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .shadow(8.dp, RoundedCornerShape(12.dp))
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                AsyncImage(
                                    model = thumbnail ?: "",
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 23.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = appleRed,
                                    fontSize = 17.sp
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = metaText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f),
                                    fontSize = 11.sp
                                ),
                                textAlign = TextAlign.Center
                            )
                            if (!description.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = description!!,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    ),
                                    textAlign = TextAlign.Center,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }

                    // Play & Shuffle Buttons (Cupertino style transparent pills)
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Play button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    .clickable {
                                        songs?.let { songList ->
                                            if (songList.isNotEmpty()) {
                                                PlayerManager.currentPlayingPlaylist.value = playlistItem
                                                PlayerManager.playQueue(songList, 0)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = appleRed,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Play",
                                        color = appleRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }

                            // Shuffle button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    .clickable {
                                        songs?.let { songList ->
                                            if (songList.isNotEmpty()) {
                                                PlayerManager.currentPlayingPlaylist.value = playlistItem
                                                PlayerManager.playQueue(songList.shuffled(), 0)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shuffle,
                                        contentDescription = null,
                                        tint = appleRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Shuffle",
                                        color = appleRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    // Tracks List (Apple Music style)
                    songs?.let { songList ->
                        itemsIndexed(songList) { index, songItem ->
                            val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == songItem.id
                            val isAlbum = playlistItem is AlbumItem
                            val startPadding = if (isAlbum) 72.dp else 80.dp

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        PlayerManager.currentPlayingPlaylist.value = playlistItem
                                        PlayerManager.playQueue(songList, index)
                                    }
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isAlbum) {
                                    // Track index number for album view
                                    Box(
                                        modifier = Modifier.width(36.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (isCurrentlyPlaying) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.VolumeUp,
                                                contentDescription = "Playing",
                                                tint = appleRed,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Text(
                                                text = (index + 1).toString(),
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 15.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                } else {
                                    // Thumbnail for playlist view
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        AsyncImage(
                                            model = songItem.thumbnail,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        if (isCurrentlyPlaying) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color.Black.copy(alpha = 0.4f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Rounded.VolumeUp,
                                                    contentDescription = "Playing",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                }

                                // Title and artist
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = songItem.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp
                                        ),
                                        color = if (isCurrentlyPlaying) appleRed else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = songItem.artists.joinToString { it.name },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 13.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // iOS style three horizontal dots menu button
                                IconButton(onClick = { /* Option click */ }) {
                                    Icon(
                                        Icons.Rounded.MoreHoriz,
                                        contentDescription = "Options",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (index < songList.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = startPadding, end = 24.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Three Separate Floating Liquid Glass Capsule Tiles (Positioned OUTSIDE the backdrop layer to prevent recursion)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(48.dp)
        ) {
            // Capsule 1: Back Button Pill (using LiquidButton) - Perfect circle
            Box(
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                LiquidButton(
                    onClick = onBack,
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = appleRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Capsule 2: Centered dynamic scrolling Title Pill (Dynamic Island styled, perfectly centered horizontally)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = 72.dp, end = 120.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showMiniTitle,
                    enter = fadeIn() + expandHorizontally() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + shrinkHorizontally() + scaleOut(targetScale = 0.8f)
                ) {
                    LiquidButton(
                        onClick = {},
                        backdrop = backdrop,
                        isInteractive = false, // Static information display
                        surfaceColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Capsule 3: Actions Row - Heart & Download Buttons
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download button
                if (songs != null && songs!!.isNotEmpty()) {
                    val downloadedSongs = downloadedSongsState.value
                    val totalCount = songs!!.size
                    val downloadedCount = remember(songs, downloadedSongs) {
                        songs!!.count { song -> downloadedSongs.any { it.id == song.id } }
                    }
                    val isAnyDownloading = remember(songs, downloadProgressMap) {
                        songs!!.any { downloadProgressMap.containsKey(it.id) }
                    }
                    val playlistProgress = remember(songs, downloadedSongs, downloadProgressMap) {
                        if (songs.isNullOrEmpty()) 0f
                        else {
                            val sum = songs!!.sumOf { song ->
                                if (downloadedSongs.any { it.id == song.id }) 1.0
                                else (downloadProgressMap[song.id] ?: 0f).toDouble()
                            }
                            (sum / songs!!.size).toFloat()
                        }
                    }
                    
                    LiquidButton(
                        onClick = {
                            if (isAnyDownloading) {
                                songs!!.forEach { song ->
                                    PlayerManager.cancelDownload(song.id)
                                }
                                com.arcadesoftware.musix.db.DownloadedPlaylistsManager.removeDownloadedPlaylist(context, playlistItem.id)
                            } else if (downloadedCount < totalCount) {
                                songs!!.forEach { song ->
                                    val isDownloaded = downloadedSongs.any { it.id == song.id }
                                    if (!isDownloaded) {
                                        PlayerManager.startDownload(song, context)
                                    }
                                }
                                com.arcadesoftware.musix.db.DownloadedPlaylistsManager.addDownloadedPlaylist(
                                    context,
                                    com.arcadesoftware.musix.db.DownloadedPlaylistsManager.DownloadedPlaylist(
                                        id = playlistItem.id,
                                        title = title,
                                        thumbnail = thumbnail,
                                        type = if (playlistItem is com.music.innertube.models.AlbumItem) "ALBUM" else "PLAYLIST",
                                        subtitle = subtitle
                                    )
                                )
                            }
                        },
                        backdrop = backdrop,
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (isAnyDownloading) {
                            androidx.compose.foundation.Canvas(modifier = Modifier.size(24.dp)) {
                                val sizePx = size.minDimension
                                val strokeWidthPx = 2.dp.toPx()
                                val radius = (sizePx - strokeWidthPx) / 2f
                                val centerOffset = androidx.compose.ui.geometry.Offset(sizePx / 2f, sizePx / 2f)

                                // Draw track
                                drawCircle(
                                    color = appleRed.copy(alpha = 0.15f),
                                    radius = radius,
                                    center = centerOffset,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidthPx)
                                )

                                // Draw progress arc
                                drawArc(
                                    color = appleRed,
                                    startAngle = -90f,
                                    sweepAngle = playlistProgress * 360f,
                                    useCenter = false,
                                    topLeft = androidx.compose.ui.geometry.Offset(strokeWidthPx / 2f, strokeWidthPx / 2f),
                                    size = androidx.compose.ui.geometry.Size(sizePx - strokeWidthPx, sizePx - strokeWidthPx),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = strokeWidthPx,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                )

                                // Draw stop/pause square in the center
                                val squareSize = 7.dp.toPx()
                                val squareLeft = (sizePx - squareSize) / 2f
                                val squareTop = (sizePx - squareSize) / 2f
                                val cornerRadiusPx = 1.2.dp.toPx()
                                drawRoundRect(
                                    color = appleRed,
                                    topLeft = androidx.compose.ui.geometry.Offset(squareLeft, squareTop),
                                    size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
                                )
                            }
                        } else if (downloadedCount == totalCount) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = "All songs downloaded",
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Download Playlist",
                                tint = appleRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Heart button
                LiquidButton(
                    onClick = {
                        val pType = if (playlistItem is AlbumItem) "ALBUM" else "PLAYLIST"
                        val willBeLiked = !isLiked
                        LikedPlaylistsManager.toggleLikePlaylist(
                            context,
                            LikedPlaylistsManager.LikedPlaylist(
                                id = playlistItem.id,
                                title = title,
                                thumbnail = thumbnail,
                                type = pType,
                                subtitle = subtitle
                            )
                        )
                        isLiked = willBeLiked
                        if (willBeLiked) {
                            com.arcadesoftware.musix.components.HeartAnimManager.trigger()
                        }
                    },
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Like Playlist",
                        tint = appleRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
