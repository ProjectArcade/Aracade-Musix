package com.arcadesoftware.musix.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.arcadesoftware.musix.PlayerManager
import com.arcadesoftware.musix.db.LikedSongsManager
import com.arcadesoftware.musix.db.entities.DownloadedSongEntity
import com.music.innertube.models.*
import com.music.innertube.YouTube
import com.music.innertube.pages.ArtistPage
import com.music.innertube.pages.ArtistSection
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import io.github.robinpcrd.cupertino.CupertinoActivityIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryArtist(
    val id: String?,
    val name: String,
    val thumbnailUrl: String?,
    val songs: List<SongItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    backdrop: LayerBackdrop,
    viewModel: PlaylistViewModel = viewModel()
) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val playHistory by viewModel.playHistory.collectAsState()
    val context = LocalContext.current

    var likedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedArtist by remember { mutableStateOf<LibraryArtist?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isGridView by remember { mutableStateOf(true) }

    LaunchedEffect(downloadedSongs, playHistory) {
        likedSongIds = withContext(Dispatchers.IO) { LikedSongsManager.getLikedSongIds(context) }
    }

    val artistsList = remember(downloadedSongs, playHistory, likedSongIds) {
        val songsList = mutableListOf<SongItem>()
        // Add downloaded songs
        songsList.addAll(downloadedSongs.map { it.toSongItem() })
        // Add liked songs from history
        songsList.addAll(playHistory.filter { likedSongIds.contains(it.id) }.map { it.toSongItem() })
        // Add play history songs
        songsList.addAll(playHistory.map { it.toSongItem() })
        
        val uniqueSongs = songsList.distinctBy { it.id }
        
        val grouped = uniqueSongs.groupBy { song ->
            val artist = song.artists.firstOrNull()
            Pair(artist?.id, artist?.name ?: "Unknown Artist")
        }
        
        grouped.map { (artistKey, songs) ->
            LibraryArtist(
                id = artistKey.first,
                name = artistKey.second,
                thumbnailUrl = songs.firstOrNull { !it.thumbnail.isNullOrEmpty() }?.thumbnail,
                songs = songs
            )
        }.sortedBy { it.name }
    }

    val filteredArtists = remember(artistsList, searchQuery) {
        if (searchQuery.isEmpty()) artistsList
        else artistsList.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Library Artists",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Grid/List toggle
                IconButton(onClick = { isGridView = !isGridView }) {
                    Icon(
                        imageVector = if (isGridView) Icons.Rounded.List else Icons.Rounded.GridView,
                        contentDescription = "Toggle View Mode",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text("Search library artists...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear Search")
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredArtists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = if (searchQuery.isEmpty()) "No artists found in library" else "No matching artists",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filteredArtists) { artist ->
                            LibraryArtistGridItem(artist = artist, onClick = { selectedArtist = artist })
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filteredArtists) { artist ->
                            LibraryArtistListItem(artist = artist, onClick = { selectedArtist = artist })
                        }
                    }
                }
            }
        }

        // Artist detail overlay screen
        AnimatedVisibility(
            visible = selectedArtist != null,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            selectedArtist?.let { artist ->
                ArtistLibraryDetailScreen(
                    artist = artist,
                    backdrop = backdrop,
                    onBack = { selectedArtist = null }
                )
            }
        }
    }
}

@Composable
fun LibraryArtistGridItem(
    artist: LibraryArtist,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!artist.thumbnailUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = artist.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${artist.songs.size} " + if (artist.songs.size == 1) "track" else "tracks",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun LibraryArtistListItem(
    artist: LibraryArtist,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!artist.thumbnailUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = artist.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${artist.songs.size} " + if (artist.songs.size == 1) "song in library" else "songs in library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistLibraryDetailScreen(
    artist: LibraryArtist,
    backdrop: LayerBackdrop,
    onBack: () -> Unit
) {
    val appleRed = Color(0xFFFA243C)
    var selectedTab by remember { mutableStateOf(0) } // 0 = In Library, 1 = Explore Online
    val hasOnlineProfile = artist.id != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Blurred ambient background
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (!artist.thumbnailUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = artist.thumbnailUrl,
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

        Column(modifier = Modifier.fillMaxSize()) {
            // Header Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Big profile card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (!artist.thumbnailUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = artist.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "${artist.songs.size} library songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Tabs for Library vs Online (if available)
            if (hasOnlineProfile) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = appleRed,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = appleRed
                        )
                    },
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("In Library", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Explore Online", fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab contents
            if (selectedTab == 0 || !hasOnlineProfile) {
                // In Library Screen
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 150.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Quick Action Buttons (Play & Shuffle)
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Play Button
                            Button(
                                onClick = {
                                    val playlistItem = PlaylistItem(
                                        id = "artist_lib_${artist.name}",
                                        title = artist.name,
                                        author = Artist(artist.name, null),
                                        songCountText = "${artist.songs.size} songs",
                                        thumbnail = artist.thumbnailUrl,
                                        playEndpoint = null,
                                        shuffleEndpoint = null,
                                        radioEndpoint = null
                                    )
                                    PlayerManager.currentPlayingPlaylist.value = playlistItem
                                    PlayerManager.playQueue(artist.songs, 0)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
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

                            // Shuffle Button
                            Button(
                                onClick = {
                                    val playlistItem = PlaylistItem(
                                        id = "artist_lib_${artist.name}",
                                        title = artist.name,
                                        author = Artist(artist.name, null),
                                        songCountText = "${artist.songs.size} songs",
                                        thumbnail = artist.thumbnailUrl,
                                        playEndpoint = null,
                                        shuffleEndpoint = null,
                                        radioEndpoint = null
                                    )
                                    PlayerManager.currentPlayingPlaylist.value = playlistItem
                                    PlayerManager.playQueue(artist.songs.shuffled(), 0)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Shuffle,
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

                    itemsIndexed(artist.songs) { index, songItem ->
                        val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == songItem.id
                        LibrarySongRow(
                            songItem = songItem,
                            index = index,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            appleRed = appleRed,
                            onClick = {
                                val playlistItem = PlaylistItem(
                                    id = "artist_lib_${artist.name}",
                                    title = artist.name,
                                    author = Artist(artist.name, null),
                                    songCountText = "${artist.songs.size} songs",
                                    thumbnail = artist.thumbnailUrl,
                                    playEndpoint = null,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null
                                )
                                PlayerManager.currentPlayingPlaylist.value = playlistItem
                                PlayerManager.playQueue(artist.songs, index)
                            }
                        )
                    }
                }
            } else {
                // Explore Online Screen
                ArtistOnlineDetailView(artistId = artist.id!!, appleRed = appleRed, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun LibrarySongRow(
    songItem: SongItem,
    index: Int,
    isCurrentlyPlaying: Boolean,
    appleRed: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
    }
}

@Composable
fun ArtistOnlineDetailView(
    artistId: String,
    appleRed: Color,
    modifier: Modifier = Modifier
) {
    var artistPage by remember { mutableStateOf<ArtistPage?>(null) }
    var isOnlineLoading by remember { mutableStateOf(true) }
    var onlineError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistId) {
        isOnlineLoading = true
        onlineError = null
        try {
            val result = withContext(Dispatchers.IO) { YouTube.artist(artistId) }
            result.onSuccess { page ->
                artistPage = page
            }.onFailure { e ->
                onlineError = e.message ?: "Failed to load online profile."
            }
        } catch (e: Exception) {
            onlineError = e.message ?: "An error occurred."
        } finally {
            isOnlineLoading = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        if (isOnlineLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CupertinoActivityIndicator(modifier = Modifier.size(48.dp))
            }
        } else if (onlineError != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = onlineError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            artistPage?.let { page ->
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 150.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // subscriber info / monthly listeners
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                        ) {
                            var metaList = mutableListOf<String>()
                            page.subscriberCountText?.let { metaList.add(it) }
                            page.monthlyListenerCount?.let { metaList.add("$it monthly listeners") }

                            if (metaList.isNotEmpty()) {
                                Text(
                                    text = metaList.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            page.description?.let { desc ->
                                var expandedDesc by remember { mutableStateOf(false) }
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                    maxLines = if (expandedDesc) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clickable { expandedDesc = !expandedDesc }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Render sections dynamically
                    page.sections.forEach { section ->
                        item {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }

                        // Check if items are songs vs albums/playlists
                        val songs = section.items.filterIsInstance<SongItem>()
                        if (songs.isNotEmpty()) {
                            itemsIndexed(songs) { index, songItem ->
                                val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == songItem.id
                                LibrarySongRow(
                                    songItem = songItem,
                                    index = index,
                                    isCurrentlyPlaying = isCurrentlyPlaying,
                                    appleRed = appleRed,
                                    onClick = {
                                        val playlistItem = PlaylistItem(
                                            id = "artist_online_${page.artist.id}",
                                            title = page.artist.title,
                                            author = Artist(page.artist.title, null),
                                            songCountText = "${songs.size} songs",
                                            thumbnail = page.artist.thumbnail,
                                            playEndpoint = null,
                                            shuffleEndpoint = null,
                                            radioEndpoint = null
                                        )
                                        PlayerManager.currentPlayingPlaylist.value = playlistItem
                                        PlayerManager.playQueue(songs, index)
                                    }
                                )
                            }
                        } else {
                            // Section has playlists or albums
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                ) {
                                    items(section.items) { item ->
                                        when (item) {
                                            is AlbumItem -> {
                                                OnlineCard(
                                                    title = item.title,
                                                    subtitle = item.year?.toString() ?: "Album",
                                                    thumbnail = item.thumbnail,
                                                    onClick = {
                                                        PlayerManager.activePlaylistDetail.value = item
                                                    }
                                                )
                                            }
                                            is PlaylistItem -> {
                                                OnlineCard(
                                                    title = item.title,
                                                    subtitle = item.songCountText ?: "Playlist",
                                                    thumbnail = item.thumbnail,
                                                    onClick = {
                                                        PlayerManager.activePlaylistDetail.value = item
                                                    }
                                                )
                                            }
                                            else -> {
                                                // Do nothing for other item types (e.g. ArtistItem, SongItem)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!thumbnail.isNullOrEmpty()) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Rounded.Album,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
