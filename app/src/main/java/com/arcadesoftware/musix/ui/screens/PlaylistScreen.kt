package com.arcadesoftware.musix.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.flow.first

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val downloadedSongs: StateFlow<List<DownloadedSongEntity>> = db.musicDao().getDownloadedSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playHistory: StateFlow<List<com.arcadesoftware.musix.db.entities.PlayHistoryEntity>> = db.musicDao().getPlayHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val userPlaylists: StateFlow<List<com.arcadesoftware.musix.db.entities.PlaylistEntity>> =
        db.musicDao().getPlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteSong(songId: String, localFilePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.musicDao().removeDownloadedSong(songId)
            val file = java.io.File(localFilePath)
            if (file.exists()) file.delete()
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val generatedId = db.musicDao().insertPlaylist(
                com.arcadesoftware.musix.db.entities.PlaylistEntity(name = name)
            )
            // Use the suspend version so we're guaranteed the insert is committed
            // before Firestore reads from the DB — no race condition.
            com.arcadesoftware.musix.db.FirestoreSyncManager.syncPlaylistsSuspend(getApplication())
            
            // Fetch the newly inserted playlist from the database to open it automatically in the UI
            val playlists = db.musicDao().getPlaylists().first()
            val newPlaylist = playlists.find { it.id == generatedId || it.name == name }
            if (newPlaylist != null) {
                withContext(Dispatchers.Main) {
                    PlayerManager.activeUserPlaylist.value = newPlaylist
                }
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.musicDao().clearPlaylistSongs(playlistId)
            db.musicDao().deletePlaylist(playlistId)
            com.arcadesoftware.musix.db.FirestoreSyncManager.syncPlaylistsSuspend(getApplication())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    backdrop: LayerBackdrop,
    viewModel: PlaylistViewModel = viewModel()
) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val userPlaylists by viewModel.userPlaylists.collectAsState()
    val context = LocalContext.current
    val downloadProgressMap by PlayerManager.downloadProgressMap.collectAsState()

    var likedPlaylists by remember { mutableStateOf<List<LikedPlaylistsManager.LikedPlaylist>>(emptyList()) }
    var downloadedPlaylists by remember { mutableStateOf<List<com.arcadesoftware.musix.db.DownloadedPlaylistsManager.DownloadedPlaylist>>(emptyList()) }
    var likedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val activePlaylistDetail by PlayerManager.activePlaylistDetail.collectAsState()

    var optionsSong by remember { mutableStateOf<DownloadedSongEntity?>(null) }
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddToPlaylistForSong by remember { mutableStateOf<DownloadedSongEntity?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistNameInput by remember { mutableStateOf("") }
    val selectedUserPlaylist by PlayerManager.activeUserPlaylist.collectAsState()
    var activeBuiltInPlaylist by remember { mutableStateOf<String?>(null) } // "liked" or "downloads"
    
    androidx.activity.compose.BackHandler(enabled = activeBuiltInPlaylist != null) {
        activeBuiltInPlaylist = null
    }

    LaunchedEffect(activePlaylistDetail, userPlaylists) {
        if (activePlaylistDetail == null) {
            likedPlaylists = withContext(Dispatchers.IO) { LikedPlaylistsManager.getLikedPlaylists(context) }
            likedSongIds = withContext(Dispatchers.IO) { LikedSongsManager.getLikedSongIds(context) }
            downloadedPlaylists = withContext(Dispatchers.IO) { com.arcadesoftware.musix.db.DownloadedPlaylistsManager.getDownloadedPlaylists(context) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 38.sp,
                            letterSpacing = (-1).sp
                        ),
                        color = Color.White
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Custom Liked Songs Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(110.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(Color(0xFFFA243C).copy(alpha = 0.2f), Color(0xFFFA243C).copy(alpha = 0.05f))
                                )
                            )
                            .border(1.dp, Color(0x33FA243C), RoundedCornerShape(24.dp))
                            .clickable { activeBuiltInPlaylist = "liked" }
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = Color(0xFFFA243C),
                                modifier = Modifier.size(32.dp)
                            )
                            Column {
                                Text(
                                    "Liked Songs",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Text(
                                    "${likedSongIds.size} songs",
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    // Custom Downloads Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(110.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(Color(0xFF22C55E).copy(alpha = 0.2f), Color(0xFF22C55E).copy(alpha = 0.05f))
                                )
                            )
                            .border(1.dp, Color(0x3322C55E), RoundedCornerShape(24.dp))
                            .clickable { activeBuiltInPlaylist = "downloads" }
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Rounded.DownloadDone,
                                contentDescription = null,
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(32.dp)
                            )
                            Column {
                                Text(
                                    "Downloads",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Text(
                                    "${downloadedSongs.size} songs",
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            if (downloadedPlaylists.isNotEmpty()) {
                item {
                    LibrarySectionHeader(title = "Downloaded Playlists")
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                    ) {
                        items(downloadedPlaylists) { item ->
                            PlaylistCard(
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnail = item.thumbnail,
                                isDownloaded = true,
                                onDeleteClick = {
                                    com.arcadesoftware.musix.db.DownloadedPlaylistsManager.removeDownloadedPlaylist(context, item.id)
                                    downloadedPlaylists = downloadedPlaylists.filter { it.id != item.id }
                                },
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
                            val isDownloaded = com.arcadesoftware.musix.db.DownloadedPlaylistsManager.isPlaylistDownloaded(context, item.id)
                            PlaylistCard(
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnail = item.thumbnail,
                                isDownloaded = isDownloaded,
                                onDeleteClick = if (isDownloaded) {
                                    {
                                        com.arcadesoftware.musix.db.DownloadedPlaylistsManager.removeDownloadedPlaylist(context, item.id)
                                        downloadedPlaylists = com.arcadesoftware.musix.db.DownloadedPlaylistsManager.getDownloadedPlaylists(context)
                                    }
                                } else null,
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

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "My Playlists",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color.White
                    )
                    IconButton(
                        onClick = { showNewPlaylistDialog = true },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFA243C))
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
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
                                onClick = { PlayerManager.activeUserPlaylist.value = playlist },
                                onDeleteClick = { viewModel.deletePlaylist(playlist.id) }
                            )
                        }
                    }
                }
            }

        }
    }

    optionsSong?.let { song ->
        ModalBottomSheet(
            onDismissRequest = { optionsSong = null },
            sheetState = optionsSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
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
            BottomSheetOption(
                icon = Icons.Rounded.PlaylistAdd,
                label = "Add to Playlist",
                onClick = {
                    showAddToPlaylistForSong = song
                    optionsSong = null
                }
            )
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

    if (showNewPlaylistDialog) {
        var dialogName by remember { mutableStateOf("") }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showNewPlaylistDialog = false; dialogName = "" }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF1C1C1E))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(28.dp))
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "New Playlist",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter a name for this playlist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    TextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        placeholder = { Text("Playlist Title", color = Color.Gray) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2C2C2E),
                            unfocusedContainerColor = Color(0xFF2C2C2E),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { showNewPlaylistDialog = false; dialogName = "" },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color.White.copy(alpha = 0.6f)
                            )
                        ) {
                            Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Button(
                            onClick = {
                                if (dialogName.isNotBlank()) {
                                    viewModel.createPlaylist(dialogName.trim())
                                    showNewPlaylistDialog = false
                                    dialogName = ""
                                }
                            },
                            enabled = dialogName.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFA243C),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFFFA243C).copy(alpha = 0.3f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f)
                            )
                        ) {
                            Text("Create", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }

    showAddToPlaylistForSong?.let { song ->
        com.arcadesoftware.musix.components.AddToPlaylistSheet(
            song = song.toSongItem(),
            onDismiss = { showAddToPlaylistForSong = null }
        )
    }

    AnimatedVisibility(
        visible = selectedUserPlaylist != null,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize()
    ) {
        selectedUserPlaylist?.let { playlist ->
            UserPlaylistDetailScreen(
                playlist = playlist,
                backdrop = backdrop,
                onBack = { PlayerManager.activeUserPlaylist.value = null }
            )
        }
    }

    AnimatedVisibility(
        visible = activeBuiltInPlaylist != null,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize()
    ) {
        activeBuiltInPlaylist?.let { type ->
            BuiltInPlaylistDetailScreen(
                type = type,
                backdrop = backdrop,
                onBack = { activeBuiltInPlaylist = null },
                onOptionsClick = { optionsSong = it }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserPlaylistDetailScreen(
    playlist: com.arcadesoftware.musix.db.entities.PlaylistEntity,
    backdrop: LayerBackdrop,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember(context) { AppDatabase.getDatabase(context) }
    val songs by db.musicDao().getSongsForPlaylist(playlist.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val appleRed = Color(0xFFFA243C)
    var showEditSheet by remember { mutableStateOf(false) }
    var showSongOptionsSheet by remember { mutableStateOf<com.arcadesoftware.musix.db.entities.PlayHistoryEntity?>(null) }
    var editName by remember(playlist.name) { mutableStateOf(playlist.name) }
    var editCoverUri by remember(playlist.coverUri) { mutableStateOf(playlist.coverUri) }
    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> 
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val file = java.io.File(context.filesDir, "playlist_cover_${playlist.id}_${System.currentTimeMillis()}.jpg")
                    inputStream?.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val localUri = android.net.Uri.fromFile(file).toString()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        editCoverUri = localUri
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val showMiniTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 300)
        }
    }

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
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (!ambientThumbnail.isNullOrEmpty()) {
                AsyncImage(
                    model = ambientThumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.18f)
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
                )
            }
            
            val currentSong by PlayerManager.currentSong.collectAsState()

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 96.dp, bottom = 160.dp)
            ) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .shadow(8.dp, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            if (playlist.coverUri != null) {
                                AsyncImage(
                                    model = playlist.coverUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (songs.size >= 4) {
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
                            } else if (songs.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.QueueMusic,
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f)
                                    )
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
                                Icon(
                                    Icons.Rounded.PlaylistAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(0.3f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("No songs yet", color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                                Text(
                                    "Add songs using the + button in the player",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(0.35f)
                                )
                            }
                        }
                    }
                }

                itemsIndexed(songs) { index, songEntity ->
                    val isPlaying = currentSong?.id == songEntity.id
                    val interactionSource = remember { MutableInteractionSource() }
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
                                showSongOptionsSheet = songEntity
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.MoreHoriz,
                                contentDescription = "Options",
                                tint = MaterialTheme.colorScheme.onSurface.copy(0.35f),
                                modifier = Modifier.size(20.dp)
                            )
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

        // ── Edit Playlist Sheet ──────────────────────────────────────────────
        if (showEditSheet) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showEditSheet = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Edit Playlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Thumbnail picker
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { imagePickerLauncher.launch("image/*") }
                            .align(Alignment.CenterHorizontally),
                        contentAlignment = Alignment.Center
                    ) {
                        if (editCoverUri != null) {
                            AsyncImage(
                                model = editCoverUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (songs.isNotEmpty()) {
                            AsyncImage(
                                model = songs.first().thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().alpha(0.5f)
                            )
                        }
                        Icon(
                            Icons.Rounded.CameraAlt,
                            contentDescription = "Pick image",
                            tint = MaterialTheme.colorScheme.onSurface.copy(0.7f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Name field
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            onClick = { showEditSheet = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("Cancel") }
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    db.musicDao().updatePlaylist(playlist.id, editName.trim(), editCoverUri)
                                }
                                showEditSheet = false
                            },
                            enabled = editName.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Save") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (showSongOptionsSheet != null) {
            val selectedSong = showSongOptionsSheet!!
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showSongOptionsSheet = null },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                model = selectedSong.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedSong.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(selectedSong.artistName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch(Dispatchers.IO) {
                                    db.musicDao().removeSongFromPlaylist(playlist.id, selectedSong.id)
                                    com.arcadesoftware.musix.db.FirestoreSyncManager.syncPlaylists(context)
                                }
                                showSongOptionsSheet = null
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Remove from playlist", style = MaterialTheme.typography.bodyLarge)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                PlayerManager.cancelDownload(selectedSong.id)
                                scope.launch(Dispatchers.IO) {
                                    db.musicDao().removeDownloadedSong(selectedSong.id)
                                    val file = java.io.File(context.filesDir, "downloads/${selectedSong.id}.m4a")
                                    if (file.exists()) file.delete()
                                    db.musicDao().removeSongFromPlaylist(playlist.id, selectedSong.id)
                                    com.arcadesoftware.musix.db.FirestoreSyncManager.syncPlaylists(context)
                                }
                                showSongOptionsSheet = null
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Delete this music", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        // ── Top bar: all three buttons always use LiquidButton ──────────────
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
                LiquidButton(
                    onClick = onBack,
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = appleRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Mini title pill (appears when scrolled)
            Box(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = showMiniTitle,
                    enter = fadeIn() + expandHorizontally() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + shrinkHorizontally() + scaleOut(targetScale = 0.8f)
                ) {
                    LiquidButton(
                        onClick = {},
                        backdrop = backdrop,
                        isInteractive = false,
                        surfaceColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                        modifier = Modifier.height(48.dp).widthIn(min = 120.dp, max = 220.dp)
                    ) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Playlist icon button
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                LiquidButton(
                    onClick = { showEditSheet = true },
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Edit Playlist",
                        tint = appleRed,
                        modifier = Modifier.size(20.dp)
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
private fun PlaylistCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    defaultIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.QueueMusic,
    iconContainerColor: Color = Color(0xFF1C1C1E),
    iconColor: Color = Color(0xFFFA243C)
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF141416))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp))
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
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(iconContainerColor, iconContainerColor.copy(alpha = 0.5f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        defaultIcon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
            if (isDownloaded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC000000))
                        .padding(2.dp)
                        .background(Color(0xFF22C55E), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(0.6f))
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Normal,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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

    val thumbnail = playlist.coverUri
        ?: if (songs.isNotEmpty()) songs.first().thumbnailUrl else null

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltInPlaylistDetailScreen(
    type: String,
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
    onOptionsClick: (DownloadedSongEntity) -> Unit,
    viewModel: PlaylistViewModel = viewModel()
) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val playHistory by viewModel.playHistory.collectAsState()
    val context = LocalContext.current
    val downloadProgressMap by PlayerManager.downloadProgressMap.collectAsState()
    
    var likedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        likedSongIds = withContext(Dispatchers.IO) { LikedSongsManager.getLikedSongIds(context) }
    }
    
    val title = if (type == "liked") "Liked Songs" else "Downloads"
    val songs: List<SongItem> = if (type == "liked") {
        playHistory.filter { likedSongIds.contains(it.id) }.map { it.toSongItem() }.distinctBy { it.id }
    } else {
        downloadedSongs.map { it.toSongItem() }
    }
    
    val ambientThumbnail = songs.firstOrNull()?.thumbnail
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val showMiniTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 300)
        }
    }
    
    val downloadsPlaylistItem = PlaylistItem(
        id = type,
        title = title,
        author = Artist("", null),
        songCountText = "${songs.size} songs",
        thumbnail = ambientThumbnail ?: "",
        playEndpoint = null,
        shuffleEndpoint = null,
        radioEndpoint = null
    )
    
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (!ambientThumbnail.isNullOrEmpty()) {
                AsyncImage(
                    model = ambientThumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.18f)
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                                MaterialTheme.colorScheme.background
                            ),
                            startY = 0f,
                            endY = 1000f
                        )
                    )
                )
            }
        }
        
        val appleRed = Color(0xFFFA243C)
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 96.dp, bottom = 120.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${songs.size} songs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LiquidButton(
                                onClick = {
                                    if (songs.isNotEmpty()) {
                                        PlayerManager.currentPlayingPlaylist.value = downloadsPlaylistItem
                                        PlayerManager.playQueue(songs, 0)
                                    }
                                },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Play", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            LiquidButton(
                                onClick = {
                                    if (songs.isNotEmpty()) {
                                        PlayerManager.currentPlayingPlaylist.value = downloadsPlaylistItem
                                        val shuffled = songs.shuffled()
                                        PlayerManager.playQueue(shuffled, 0)
                                    }
                                },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                surfaceColor = MaterialTheme.colorScheme.surfaceVariant,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Shuffle", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                
                if (songs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No songs found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        }
                    }
                }
                
                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                    val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == song.id
                    val isDownloading = downloadProgressMap.containsKey(song.id)
                    val downloadProgress = downloadProgressMap[song.id] ?: 0f
                    val downloadedEntity = downloadedSongs.find { it.id == song.id }
                    
                    if (type == "downloads" && downloadedEntity != null) {
                        DownloadedSongRow(
                            songEntity = downloadedEntity,
                            index = index,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isDownloading = isDownloading,
                            downloadProgress = downloadProgress,
                            onClick = {
                                PlayerManager.currentPlayingPlaylist.value = downloadsPlaylistItem
                                if (downloadedEntity.localFilePath.isNotEmpty()) {
                                    PlayerManager.playLocal(downloadedEntity.toSongItem(), downloadedEntity.localFilePath)
                                } else {
                                    PlayerManager.playQueue(songs, index)
                                }
                            },
                            onMoreClick = { onOptionsClick(downloadedEntity) }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    PlayerManager.currentPlayingPlaylist.value = downloadsPlaylistItem
                                    PlayerManager.playQueue(songs, index)
                                }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = song.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp))
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
                                    song.artists.joinToString { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Top Bar with Liquid Back Button and Centered Title Pill
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
                LiquidButton(
                    onClick = onBack,
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = appleRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Mini title pill (appears when scrolled)
            Box(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = showMiniTitle,
                    enter = fadeIn() + expandHorizontally() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + shrinkHorizontally() + scaleOut(targetScale = 0.8f)
                ) {
                    LiquidButton(
                        onClick = {},
                        backdrop = backdrop,
                        modifier = Modifier.height(36.dp).wrapContentWidth(),
                        isInteractive = false
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
