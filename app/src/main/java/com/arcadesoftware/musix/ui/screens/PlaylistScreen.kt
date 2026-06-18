package com.arcadesoftware.musix.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.arcadesoftware.musix.components.LiquidButton
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
fun PlaylistScreen(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
    viewModel: PlaylistViewModel = viewModel()
) {
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

            // ── Quick Access Row: Liked Songs & Downloads ─────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PlaylistCard(
                        title = "Liked Songs",
                        subtitle = "${likedSongIds.size} songs",
                        thumbnail = null,
                        defaultIcon = Icons.Rounded.Favorite,
                        iconContainerColor = Color(0xFFFEE2E2),
                        iconColor = Color(0xFFEF4444),
                        onClick = { /* TODO open liked songs list */ }
                    )
                    PlaylistCard(
                        title = "Downloads",
                        subtitle = "${downloadedSongs.size} songs",
                        thumbnail = null,
                        defaultIcon = Icons.Rounded.DownloadDone,
                        iconContainerColor = Color(0xFFDCFCE7),
                        iconColor = Color(0xFF22C55E),
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
                                    val downloadsPlaylistItem = PlaylistItem(
                                        id = "downloads",
                                        title = "Downloads",
                                        author = Artist("", null),
                                        songCountText = "${downloadedSongs.size} songs",
                                        thumbnail = downloadedSongs.firstOrNull()?.thumbnailUrl ?: "",
                                        playEndpoint = null,
                                        shuffleEndpoint = null,
                                        radioEndpoint = null
                                    )
                                    PlayerManager.currentPlayingPlaylist.value = downloadsPlaylistItem
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
                        val downloadsPlaylistItem = PlaylistItem(
                            id = "downloads",
                            title = "Downloads",
                            author = Artist("", null),
                            songCountText = "${downloadedSongs.size} songs",
                            thumbnail = downloadedSongs.firstOrNull()?.thumbnailUrl ?: "",
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null
                        )
                        PlayerManager.currentPlayingPlaylist.value = downloadsPlaylistItem
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
                backdrop = backdrop,
                onBack = { selectedUserPlaylist = null }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserPlaylistDetailScreen(
    playlist: com.arcadesoftware.musix.db.entities.PlaylistEntity,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember(context) { AppDatabase.getDatabase(context) }
    val songs by db.musicDao().getSongsForPlaylist(playlist.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val appleRed = Color(0xFFFA243C)

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val showMiniTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 300)
        }
    }

    // Pick the best thumbnail for ambient background
    val currentSong by PlayerManager.currentSong.collectAsState()
    val ambientThumbnail = if (currentSong != null && songs.any { it.id == currentSong?.id }) {
        currentSong?.thumbnail
    } else {
        songs.firstOrNull()?.thumbnailUrl
    }

    fun buildYtPlaylistItem() = PlaylistItem(
        id = playlist.id.toString(),
        title = playlist.name,
        author = Artist("", null),
        songCountText = "${songs.size} songs",
        thumbnail = songs.firstOrNull()?.thumbnailUrl ?: "",
        playEndpoint = null,
        shuffleEndpoint = null,
        radioEndpoint = null
    )

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Backdrop layer (only if backdrop passed)
        val boxMod = if (backdrop != null) {
            Modifier.fillMaxSize().layerBackdrop(backdrop)
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = boxMod) {
            // Ambient blurred cover background
            if (!ambientThumbnail.isNullOrEmpty()) {
                AsyncImage(
                    model = ambientThumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.18f)
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
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

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 96.dp, bottom = 160.dp)
            ) {
                // Centered artwork + metadata (Apple Music style)
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        // Mosaic / single artwork
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .shadow(8.dp, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
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
                                AsyncImage(
                                    model = songs[0].thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.QueueMusic, contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 23.sp),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${songs.size} songs",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, color = appleRed, fontSize = 17.sp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                // Play & Shuffle buttons (Cupertino style pills)
                if (songs.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f).height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    .clickable {
                                        PlayerManager.currentPlayingPlaylist.value = buildYtPlaylistItem()
                                        PlayerManager.playQueue(songs.map { it.toSongItem() }, 0)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = appleRed, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "Play", color = appleRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f).height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    .clickable {
                                        PlayerManager.currentPlayingPlaylist.value = buildYtPlaylistItem()
                                        PlayerManager.playQueue(songs.shuffled().map { it.toSongItem() }, 0)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = appleRed, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "Shuffle", color = appleRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }

                if (songs.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
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
                    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = interactionSource, indication = null) {
                                PlayerManager.currentPlayingPlaylist.value = buildYtPlaylistItem()
                                PlayerManager.playQueue(songs.map { it.toSongItem() }, index)
                            }
                            .background(
                                if (isPlaying) MaterialTheme.colorScheme.primary.copy(0.07f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            if (isPlaying) {
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
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            AsyncImage(
                                model = songEntity.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (isPlaying) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Playing",
                                        tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                songEntity.title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                                color = if (isPlaying) appleRed else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                songEntity.artistName,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    db.musicDao().removeSongFromPlaylist(playlist.id, songEntity.id)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = "Options",
                                tint = MaterialTheme.colorScheme.onSurface.copy(0.35f),
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    if (index < songs.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 84.dp, end = 24.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }

        // Floating Liquid glass capsule bar (Back + title + heart)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(48.dp)
        ) {
            // Back button
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                if (backdrop != null) {
                    LiquidButton(
                        onClick = onBack,
                        backdrop = backdrop,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back",
                            tint = appleRed, modifier = Modifier.size(22.dp))
                    }
                } else {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(0.8f))
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back",
                            tint = appleRed, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // Mini title (appears when scrolled)
            Box(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showMiniTitle,
                    enter = fadeIn() + expandHorizontally() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + shrinkHorizontally() + scaleOut(targetScale = 0.8f)
                ) {
                    if (backdrop != null) {
                        LiquidButton(
                            onClick = {},
                            backdrop = backdrop,
                            isInteractive = false,
                            surfaceColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                            modifier = Modifier.wrapContentWidth()
                        ) {
                            Text(text = playlist.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }

            // Heart/like placeholder (no YT id, so show a playlist icon)
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                if (backdrop != null) {
                    LiquidButton(
                        onClick = { /* Could add playlist-level favourite */ },
                        backdrop = backdrop,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "Playlist",
                            tint = appleRed, modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(
                        onClick = {},
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(0.8f))
                    ) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "Playlist",
                            tint = appleRed, modifier = Modifier.size(20.dp))
                    }
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
private fun PlaylistCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    defaultIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.QueueMusic,
    iconContainerColor: Color = MaterialTheme.colorScheme.primary.copy(0.12f),
    iconColor: Color = MaterialTheme.colorScheme.primary
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
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        defaultIcon,
                        contentDescription = null,
                        tint = iconColor,
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
