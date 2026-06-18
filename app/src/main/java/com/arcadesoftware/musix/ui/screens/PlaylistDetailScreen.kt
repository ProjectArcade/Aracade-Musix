package com.arcadesoftware.musix.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arcadesoftware.musix.PlayerManager
import com.arcadesoftware.musix.components.LiquidButton
import com.kyant.backdrop.Backdrop
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
    backdrop: Backdrop,
    onBack: () -> Unit
) {
    var songs by remember { mutableStateOf<List<SongItem>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

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

    val songCountText = when (playlistItem) {
        is PlaylistItem -> playlistItem.songCountText
        is AlbumItem -> playlistItem.year?.toString()
        else -> null
    }

    LaunchedEffect(playlistItem.id) {
        isLoading = true
        errorMsg = null
        try {
            val fetchedSongs = withContext(Dispatchers.IO) {
                if (playlistItem is PlaylistItem) {
                    val result = YouTube.playlist(playlistItem.id)
                    result.getOrNull()?.songs
                } else if (playlistItem is AlbumItem) {
                    val result = YouTube.album(playlistItem.browseId)
                    result.getOrNull()?.songs
                } else {
                    null
                }
            }
            if (fetchedSongs != null) {
                songs = fetchedSongs
            } else {
                errorMsg = "Failed to load tracks. Please try again."
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "An unexpected error occurred."
        } finally {
            isLoading = false
        }
    }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
                .padding(paddingValues)
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
                        Button(onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMsg = null
                                val fetchedSongs = withContext(Dispatchers.IO) {
                                    if (playlistItem is PlaylistItem) {
                                        YouTube.playlist(playlistItem.id).getOrNull()?.songs
                                    } else if (playlistItem is AlbumItem) {
                                        YouTube.album(playlistItem.browseId).getOrNull()?.songs
                                    } else {
                                        null
                                    }
                                }
                                if (fetchedSongs != null) {
                                    songs = fetchedSongs
                                } else {
                                    errorMsg = "Failed to load tracks. Please try again."
                                }
                                isLoading = false
                            }
                        }) {
                            Text("Retry")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    // Header Block
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .shadow(8.dp, RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                AsyncImage(
                                    model = thumbnail ?: "",
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                            songCountText?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (playlistItem is PlaylistItem) it else "Year: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                                )
                            }
                        }
                    }

                    // Action Buttons Row: Play All, Shuffle
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LiquidButton(
                                onClick = {
                                    songs?.let { songList ->
                                        if (songList.isNotEmpty()) {
                                            PlayerManager.currentPlayingPlaylist.value = playlistItem
                                            PlayerManager.playQueue(songList, 0)
                                        }
                                    }
                                },
                                backdrop = backdrop,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                tint = MaterialTheme.colorScheme.primary,
                                isInteractive = true,
                                shape = { RoundedCornerShape(16.dp) }
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Play All", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
                            }

                            LiquidButton(
                                onClick = {
                                    songs?.let { songList ->
                                        if (songList.isNotEmpty()) {
                                            PlayerManager.currentPlayingPlaylist.value = playlistItem
                                            PlayerManager.playQueue(songList.shuffled(), 0)
                                        }
                                    }
                                },
                                backdrop = backdrop,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                tint = MaterialTheme.colorScheme.surfaceVariant,
                                isInteractive = true,
                                shape = { RoundedCornerShape(16.dp) }
                            ) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Shuffle", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Song list header
                    item {
                        Text(
                            text = "Tracks",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }

                    // List items
                    songs?.let { songList ->
                        itemsIndexed(songList) { index, songItem ->
                            val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == songItem.id

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        PlayerManager.currentPlayingPlaylist.value = playlistItem
                                        PlayerManager.playQueue(songList, index)
                                    }
                                    .background(
                                        if (isCurrentlyPlaying)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Index / Play bar
                                Box(
                                    modifier = Modifier.width(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCurrentlyPlaying) {
                                        val infiniteTransition = rememberInfiniteTransition(label = "bars")
                                        val bar1 by infiniteTransition.animateFloat(
                                            initialValue = 0.3f, targetValue = 1f,
                                            animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
                                            label = "bar1"
                                        )
                                        val bar2 by infiniteTransition.animateFloat(
                                            initialValue = 1f, targetValue = 0.3f,
                                            animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
                                            label = "bar2"
                                        )
                                        val bar3 by infiniteTransition.animateFloat(
                                            initialValue = 0.6f, targetValue = 1f,
                                            animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
                                            label = "bar3"
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.Bottom,
                                            modifier = Modifier.height(18.dp)
                                        ) {
                                            listOf(bar1, bar2, bar3).forEach { height ->
                                                Box(
                                                    modifier = Modifier
                                                        .width(3.dp)
                                                        .fillMaxHeight(height)
                                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Thumbnail
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                    ) {
                                    AsyncImage(
                                        model = songItem.thumbnail,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                // Title and artist
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = songItem.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = songItem.artists.joinToString { it.name },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // More options icon
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "Options",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            if (index < songList.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 64.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
