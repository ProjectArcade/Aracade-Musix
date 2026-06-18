package com.arcadesoftware.musix.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.arcadesoftware.musix.PlayerManager
import com.arcadesoftware.musix.db.AppDatabase
import com.arcadesoftware.musix.db.LikedPlaylistsManager
import com.arcadesoftware.musix.db.LikedSongsManager
import com.arcadesoftware.musix.db.entities.DownloadedSongEntity
import com.music.innertube.models.Artist
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val downloadedSongs: StateFlow<List<DownloadedSongEntity>> = db.musicDao().getDownloadedSongs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val userPlaylists: StateFlow<List<com.arcadesoftware.musix.db.entities.PlaylistEntity>> =
        db.musicDao().getPlaylists().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun deleteSong(songId: String, localFilePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.musicDao().removeDownloadedSong(songId)
            val file = java.io.File(localFilePath)
            if (file.exists()) file.delete()
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.musicDao().insertPlaylist(
                com.arcadesoftware.musix.db.entities.PlaylistEntity(name = name)
            )
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.musicDao().clearPlaylistSongs(playlistId)
            db.musicDao().deletePlaylist(playlistId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel = viewModel()) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val userPlaylists by viewModel.userPlaylists.collectAsState()
    val context = LocalContext.current
    val downloadProgressMap by PlayerManager.downloadProgressMap.collectAsState()

    var likedPlaylists by remember { mutableStateOf<List<LikedPlaylistsManager.LikedPlaylist>>(emptyList()) }
    var likedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val activePlaylistDetail by PlayerManager.activePlaylistDetail.collectAsState()

    // Sheet states
    var optionsSong by remember { mutableStateOf<DownloadedSongEntity?>(null) }
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddToPlaylistForSong by remember { mutableStateOf<DownloadedSongEntity?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistNameInput by remember { mutableStateOf("") }
    var selectedUserPlaylist by remember { mutableStateOf<com.arcadesoftware.musix.db.entities.PlaylistEntity?>(null) }

    // Refresh liked data when playlist detail closes
    LaunchedEffect(activePlaylistDetail) {
        if (activePlaylistDetail == null) {
            likedPlaylists = withContext(Dispatchers.IO) { LikedPlaylistsManager.getLikedPlaylists(context) }
            likedSongIds = withContext(Dispatchers.IO) { LikedSongsManager.getLikedSongIds(context) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Library",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 34.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // ── Pinned cards row: Liked Songs + Liked Playlists ─────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Liked Songs card
                    LikedSongsCard(
                        likedCount = likedSongIds.size,
                        modifier = Modifier.weight(1f),
                        onClick = { /* TODO open liked songs list */ }
                    )
                    // Downloaded Songs card
                    DownloadedCountCard(
                        count = downloadedSongs.size,
                        modifier = Modifier.weight(1f),
                        onClick = { /* scroll handled by the list below */ }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Favorite Playlists & Albums ──────────────────────────────
            if (likedPlaylists.isNotEmpty()) {
                item {
                    LibrarySectionHeader(title = "Favorite Playlists")
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                    ) {
                        items(likedPlaylists) { item ->
                            PlaylistCard(
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnail = item.thumbnail,
                                onClick = {
                                    val ytItem = if (item.type == "ALBUM") {
                                        AlbumItem(
                                            browseId = item.id,
                                            playlistId = "",
                                            title = item.title,
                                            artists = listOf(Artist(item.subtitle, null)),
                                            thumbnail = item.thumbnail ?: ""
                                        )
                                    } else {
                                        PlaylistItem(
                                            id = item.id,
                                            title = item.title,
                                            author = Artist(item.subtitle, null),
                                            songCountText = null,
                                            thumbnail = item.thumbnail,
                                            playEndpoint = null,
                                            shuffleEndpoint = null,
                                            radioEndpoint = null
                                        )
                                    }
                                    PlayerManager.activePlaylistDetail.value = ytItem
                                }
                            )
                        }
                    }
                }
            }

            // ── My Playlists section ─────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LibrarySectionHeader(title = "My Playlists")
                    TextButton(
                        onClick = { showNewPlaylistDialog = true }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (userPlaylists.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.5f))
                            .clickable { showNewPlaylistDialog = true }
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.PlaylistAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Create your first playlist",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Tap to add a new playlist",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                    ) {
                        items(userPlaylists, key = { it.id }) { playlist ->
                            UserPlaylistCard(
                                playlist = playlist,
                                onClick = { selectedUserPlaylist = playlist },
                                onDeleteClick = { viewModel.deletePlaylist(playlist.id) }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // ── Downloaded Songs section ─────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LibrarySectionHeader(title = "Downloads")
                    if (downloadedSongs.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                if (downloadedSongs.isNotEmpty()) {
                                    PlayerManager.playQueue(downloadedSongs.map { it.toSongItem() }, 0)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.PlayCircle,
                                contentDescription = "Play All",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Play All",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (downloadedSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)
                                )
                            }
                            Text(
                                "No downloads yet",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                            )
                            Text(
                                "Tap ⬇ in the player to save offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(0.35f)
                            )
                        }
                    }
                }
            }

            itemsIndexed(downloadedSongs, key = { _, s -> s.id }) { index, songEntity ->
                val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == songEntity.id
                val isDownloading = downloadProgressMap.containsKey(songEntity.id)
                val downloadProgress = downloadProgressMap[songEntity.id] ?: 0f

                DownloadedSongRow(
                    songEntity = songEntity,
                    index = index,
                    isCurrentlyPlaying = isCurrentlyPlaying,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    onClick = {
                        if (songEntity.localFilePath.isNotEmpty()) {
                            PlayerManager.playLocal(songEntity.toSongItem(), songEntity.localFilePath)
                        } else {
                            PlayerManager.playQueue(downloadedSongs.map { it.toSongItem() }, downloadedSongs.indexOf(songEntity))
                        }
                    },
                    onMoreClick = { optionsSong = songEntity }
                )
            }
        }
    }

    // ── Song Options Bottom Sheet ────────────────────────────────────────
    optionsSong?.let { song ->
        ModalBottomSheet(
            onDismissRequest = { optionsSong = null },
            sheetState = optionsSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            // Song header preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(modifier = Modifier.height(8.dp))

            // Play option
            BottomSheetOption(
                icon = Icons.Rounded.PlayArrow,
                label = "Play Now",
                onClick = {
                    optionsSong = null
                    if (song.localFilePath.isNotEmpty()) {
                        PlayerManager.playLocal(song.toSongItem(), song.localFilePath)
                    } else {
                        PlayerManager.play(song.toSongItem())
                    }
                }
            )
            // Add to queue
            BottomSheetOption(
                icon = Icons.Rounded.AddToQueue,
                label = "Add to Queue",
                onClick = {
                    optionsSong = null
                    val current = PlayerManager.queue.value.toMutableList()
                    current.add(song.toSongItem())
                    PlayerManager.queue.value = current
                }
            )
            // Add to playlist
            BottomSheetOption(
                icon = Icons.Rounded.PlaylistAdd,
                label = "Add to Playlist",
                onClick = {
                    showAddToPlaylistForSong = song
                    optionsSong = null
                }
            )
            // Delete
            BottomSheetOption(
                icon = Icons.Rounded.Delete,
                label = "Remove Download",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    optionsSong = null
                    viewModel.deleteSong(song.id, song.localFilePath)
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── New Playlist Dialog ───────────────────────────────────────────────────
    if (showNewPlaylistDialog) {
        var dialogName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false; dialogName = "" },
            title = { Text("New Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = dialogName,
                    onValueChange = { dialogName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogName.isNotBlank()) {
                            viewModel.createPlaylist(dialogName.trim())
                            showNewPlaylistDialog = false
                            dialogName = ""
                        }
                    },
                    enabled = dialogName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistDialog = false; dialogName = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Add to Playlist Sheet (from MoreVert) ─────────────────────────────────
    showAddToPlaylistForSong?.let { song ->
        com.arcadesoftware.musix.components.AddToPlaylistSheet(
            song = song.toSongItem(),
            onDismiss = { showAddToPlaylistForSong = null }
        )
    }

    // ── User Playlist Detail Overlay ──────────────────────────────────────────
    androidx.compose.animation.AnimatedVisibility(
        visible = selectedUserPlaylist != null,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize()
    ) {
        selectedUserPlaylist?.let { playlist ->
            UserPlaylistDetailScreen(
                playlist = playlist,
                onBack = { selectedUserPlaylist = null }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserPlaylistDetailScreen(
    playlist: com.arcadesoftware.musix.db.entities.PlaylistEntity,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember(context) { AppDatabase.getDatabase(context) }
    val songs by db.musicDao().getSongsForPlaylist(playlist.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            // Hero header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(0.6f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                ) {
                    // Mosaic of thumbnails
                    if (songs.size >= 4) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.weight(1f)) {
                                listOf(songs[0], songs[1]).forEach { s ->
                                    AsyncImage(
                                        model = s.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                }
                            }
                            Row(modifier = Modifier.weight(1f)) {
                                listOf(songs[2], songs[3]).forEach { s ->
                                    AsyncImage(
                                        model = s.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                }
                            }
                        }
                    } else if (songs.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f))
                        }
                    }

                    // Gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
                    )

                    // Title at bottom
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp)
                    ) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "${songs.size} songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Back button
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(8.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(0.7f))
                    ) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Play All / Shuffle row
            if (songs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { PlayerManager.playQueue(songs.map { it.toSongItem() }, 0) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play All", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { PlayerManager.playQueue(songs.shuffled().map { it.toSongItem() }, 0) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Shuffle", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (songs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.PlaylistAdd, contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(0.3f))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No songs yet", color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                            Text("Add songs using the + button in the player",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(0.35f))
                        }
                    }
                }
            }

            itemsIndexed(songs) { index, songEntity ->
                val isPlaying = PlayerManager.currentSong.collectAsState().value?.id == songEntity.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PlayerManager.playQueue(songs.map { it.toSongItem() }, index) }
                        .background(if (isPlaying) MaterialTheme.colorScheme.primary.copy(0.07f) else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        if (isPlaying) MusicBarsAnimation()
                        else Text("${index + 1}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.4f))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    AsyncImage(
                        model = songEntity.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            songEntity.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            songEntity.artistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    // Remove from playlist
                    IconButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                db.musicDao().removeSongFromPlaylist(playlist.id, songEntity.id)
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Rounded.RemoveCircleOutline,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurface.copy(0.35f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (index < songs.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 84.dp, end = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}

// ─── Reusable composables ────────────────────────────────────────────────────

@Composable
private fun LibrarySectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        ),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun LikedSongsCard(likedCount: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(110.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFBB5CD4), Color(0xFF6B3FA0))
                )
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    "Liked Songs",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "$likedCount songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(0.75f)
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = Color.White.copy(0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
        )
    }
}

@Composable
private fun DownloadedCountCard(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(110.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1A7E4C), Color(0xFF0F5A35))
                )
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(
                Icons.Rounded.DownloadDone,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "$count songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(0.75f)
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = Color.White.copy(0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
        )
    }
}

@Composable
private fun PlaylistCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .background(Color.Black.copy(0.5f), CircleShape)
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UserPlaylistCard(
    playlist: com.arcadesoftware.musix.db.entities.PlaylistEntity,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    val db = remember(context) { AppDatabase.getDatabase(context) }
    val songs by db.musicDao().getSongsForPlaylist(playlist.id).collectAsState(initial = emptyList())
    val currentSong by PlayerManager.currentSong.collectAsState()

    val isPlayingAnySongInPlaylist = currentSong != null && songs.any { it.id == currentSong?.id }

    val thumbnail = if (isPlayingAnySongInPlaylist && currentSong?.thumbnail != null) {
        currentSong?.thumbnail
    } else if (songs.isNotEmpty()) {
        songs.first().thumbnailUrl
    } else {
        null
    }

    PlaylistCard(
        title = playlist.name,
        subtitle = "${songs.size} songs",
        thumbnail = thumbnail,
        onClick = onClick,
        onDeleteClick = onDeleteClick
    )
}

@Composable
private fun DownloadedSongRow(
    songEntity: DownloadedSongEntity,
    index: Int,
    isCurrentlyPlaying: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyPlaying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Playing indicator / index number
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isCurrentlyPlaying) {
                    MusicBarsAnimation()
                } else {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))

            // Thumbnail with green downloaded badge
            Box(modifier = Modifier.size(52.dp)) {
                AsyncImage(
                    model = songEntity.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .shadow(2.dp, RoundedCornerShape(10.dp))
                )
                // Green circle tick badge (bottom-right)
                if (!isDownloading) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Downloaded",
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                } else {
                    // Download in progress ring
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.BottomEnd),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 2.dp,
                            color = Color(0xFF22C55E),
                            trackColor = Color(0xFF22C55E).copy(alpha = 0.2f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))

            // Title + artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    songEntity.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    songEntity.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // More options
            IconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun MusicBarsAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "bars")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "b1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "b2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "b3"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp)
    ) {
        listOf(bar1, bar2, bar3).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(h)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun BottomSheetOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}
