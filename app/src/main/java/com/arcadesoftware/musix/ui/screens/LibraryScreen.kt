package com.arcadesoftware.musix.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import com.arcadesoftware.musix.db.LikedArtistsManager

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
    val focusManager = LocalFocusManager.current

    var likedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedArtist by remember { mutableStateOf<LibraryArtist?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isGridView by remember { mutableStateOf(true) }

    // YT Music search results & suggestions
    var onlineSearchResult by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var querySuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var artistSuggestions by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var isSearchFocused by remember { mutableStateOf(false) }

    var likedArtists by remember { mutableStateOf<List<LibraryArtist>>(emptyList()) }
    var likedArtistsTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(downloadedSongs, playHistory) {
        likedSongIds = withContext(Dispatchers.IO) { LikedSongsManager.getLikedSongIds(context) }
    }

    LaunchedEffect(likedArtistsTrigger, downloadedSongs, playHistory) {
        likedArtists = withContext(Dispatchers.IO) { LikedArtistsManager.getLikedArtists(context) }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            isSearchingOnline = true
            
            // Launch suggestions fetch in parallel
            launch(Dispatchers.IO) {
                YouTube.searchSuggestions(searchQuery).onSuccess { suggestResult ->
                    withContext(Dispatchers.Main) {
                        querySuggestions = suggestResult.queries
                        artistSuggestions = suggestResult.recommendedItems.filterIsInstance<ArtistItem>()
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        querySuggestions = emptyList()
                        artistSuggestions = emptyList()
                    }
                }
            }
            
            // Launch search fetch in parallel
            launch(Dispatchers.IO) {
                try {
                    val result = YouTube.search(searchQuery, com.music.innertube.YouTube.SearchFilter.FILTER_ARTIST)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { searchPage ->
                            onlineSearchResult = searchPage.items.filterIsInstance<ArtistItem>()
                        }.onFailure {
                            onlineSearchResult = emptyList()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        onlineSearchResult = emptyList()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isSearchingOnline = false
                    }
                }
            }
        } else {
            onlineSearchResult = emptyList()
            querySuggestions = emptyList()
            artistSuggestions = emptyList()
        }
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
                        imageVector = if (isGridView) Icons.Rounded.GridView else Icons.Rounded.GridView,
                        contentDescription = "Toggle View Mode",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Liquid bar search bar
            com.arcadesoftware.musix.components.LiquidButton(
                onClick = {},
                backdrop = backdrop,
                surfaceColor = if (!androidx.compose.foundation.isSystemInDarkTheme()) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.4f),
                blurRadius = 4.dp,
                isInteractive = false,
                shape = { RoundedCornerShape(28.dp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(56.dp)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(28.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 16.dp)
                )
                
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onFocusChanged { isSearchFocused = it.isFocused },
                    placeholder = { Text("Search library and online artists...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isSearchFocused && searchQuery.isNotEmpty() && (querySuggestions.isNotEmpty() || artistSuggestions.isNotEmpty())) {
                // Suggestions overlay
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Artist recommendations
                    if (artistSuggestions.isNotEmpty()) {
                        item {
                            Text(
                                text = "Artist Suggestions",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(artistSuggestions) { artistItem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        focusManager.clearFocus()
                                        val artist = LibraryArtist(
                                            id = artistItem.id,
                                            name = artistItem.title,
                                            thumbnailUrl = artistItem.thumbnail,
                                            songs = emptyList()
                                        )
                                        selectedArtist = artist
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    if (!artistItem.thumbnail.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = artistItem.thumbnail,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.Person,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp).align(Alignment.Center),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = artistItem.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    
                    // Query suggestions
                    if (querySuggestions.isNotEmpty()) {
                        item {
                            Text(
                                text = "Queries",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(querySuggestions) { suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        searchQuery = suggestion
                                        focusManager.clearFocus()
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
            } else if (searchQuery.isEmpty() && artistsList.isEmpty()) {
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
                            text = "No artists found in library",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                if (isGridView) {
                    val lazyGridState = rememberLazyGridState()
                    LaunchedEffect(lazyGridState.isScrollInProgress) {
                        if (lazyGridState.isScrollInProgress) {
                            focusManager.clearFocus()
                        }
                    }
                    LazyVerticalGrid(
                        state = lazyGridState,
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Section: Favorite Artists
                        if (likedArtists.isNotEmpty() && searchQuery.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = "Favorite Artists",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp, start = 4.dp)
                                )
                            }
                            
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(likedArtists) { artist ->
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(80.dp)
                                                .clickable { 
                                                    focusManager.clearFocus()
                                                    selectedArtist = artist 
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(70.dp)
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
                                                        modifier = Modifier.size(36.dp).align(Alignment.Center),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = artist.name,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Section: In Library
                        if (searchQuery.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = "In Library",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
                                )
                            }
                        }

                        if (filteredArtists.isEmpty() && searchQuery.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = "No local matching artists",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                                )
                            }
                        } else {
                            items(filteredArtists) { artist ->
                                LibraryArtistGridItem(artist = artist, onClick = { 
                                    focusManager.clearFocus()
                                    selectedArtist = artist 
                                })
                            }
                        }

                        // Section: YouTube Music Online Results
                        if (searchQuery.isNotEmpty() && (isSearchingOnline || onlineSearchResult.isNotEmpty())) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp)
                                ) {
                                    Text(
                                        text = "From YouTube Music",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    if (isSearchingOnline) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CupertinoActivityIndicator(modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            if (onlineSearchResult.isNotEmpty()) {
                                items(onlineSearchResult) { artistItem ->
                                    val artist = LibraryArtist(
                                        id = artistItem.id,
                                        name = artistItem.title,
                                        thumbnailUrl = artistItem.thumbnail,
                                        songs = emptyList()
                                    )
                                    LibraryArtistGridItem(artist = artist, onClick = { 
                                        focusManager.clearFocus()
                                        selectedArtist = artist 
                                    })
                                }
                            } else if (!isSearchingOnline) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = "No artists found on YouTube Music",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val lazyListState = rememberLazyListState()
                    LaunchedEffect(lazyListState.isScrollInProgress) {
                        if (lazyListState.isScrollInProgress) {
                            focusManager.clearFocus()
                        }
                    }
                    LazyColumn(
                        state = lazyListState,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Section: Favorite Artists
                        if (likedArtists.isNotEmpty() && searchQuery.isEmpty()) {
                            item {
                                Text(
                                    text = "Favorite Artists",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                                )
                            }
                            
                            item {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(likedArtists) { artist ->
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(80.dp)
                                                .clickable { 
                                                    focusManager.clearFocus()
                                                    selectedArtist = artist 
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(70.dp)
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
                                                        modifier = Modifier.size(36.dp).align(Alignment.Center),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = artist.name,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Section: In Library
                        if (searchQuery.isNotEmpty()) {
                            item {
                                Text(
                                    text = "In Library",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                        }

                        if (filteredArtists.isEmpty() && searchQuery.isNotEmpty()) {
                            item {
                                Text(
                                    text = "No local matching artists",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        } else {
                            items(filteredArtists) { artist ->
                                LibraryArtistListItem(artist = artist, onClick = { 
                                    focusManager.clearFocus()
                                    selectedArtist = artist 
                                })
                            }
                        }

                        // Section: YouTube Music Online Results
                        if (searchQuery.isNotEmpty() && (isSearchingOnline || onlineSearchResult.isNotEmpty())) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                                ) {
                                    Text(
                                        text = "From YouTube Music",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    if (isSearchingOnline) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CupertinoActivityIndicator(modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            if (onlineSearchResult.isNotEmpty()) {
                                items(onlineSearchResult) { artistItem ->
                                    val artist = LibraryArtist(
                                        id = artistItem.id,
                                        name = artistItem.title,
                                        thumbnailUrl = artistItem.thumbnail,
                                        songs = emptyList()
                                    )
                                    LibraryArtistListItem(artist = artist, onClick = { 
                                        focusManager.clearFocus()
                                        selectedArtist = artist 
                                    })
                                }
                            } else if (!isSearchingOnline) {
                                item {
                                    Text(
                                        text = "No artists found on YouTube Music",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                }
                            }
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
                    onBack = { selectedArtist = null },
                    onLikedArtistsChanged = { likedArtistsTrigger++ }
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
            text = if (artist.songs.isEmpty()) "Online profile" else ("${artist.songs.size} " + if (artist.songs.size == 1) "track" else "tracks"),
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
                text = if (artist.songs.isEmpty()) "Explore Online" else ("${artist.songs.size} " + if (artist.songs.size == 1) "song in library" else "songs in library"),
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
    onBack: () -> Unit,
    onLikedArtistsChanged: () -> Unit
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val appleRed = Color(0xFFFA243C)
    // Automatically switch to Explore Online tab (1) if the library songs list is empty and artist has an online profile ID
    var selectedTab by remember(artist) { mutableStateOf(if (artist.songs.isEmpty() && artist.id != null) 1 else 0) }
    val hasOnlineProfile = artist.id != null

    val libListState = rememberLazyListState()
    val onlineListState = rememberLazyListState()
    val showMiniTitle by remember(selectedTab) {
        derivedStateOf {
            val state = if (selectedTab == 0) libListState else onlineListState
            state.firstVisibleItemIndex > 0 || (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset > 200)
        }
    }

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
            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(64.dp))

            // Big profile card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                    )
                )

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(142.dp)
                            .graphicsLayer { rotationZ = rotation }
                            .border(
                                2.5.dp,
                                androidx.compose.ui.graphics.Brush.sweepGradient(
                                    listOf(
                                        Color(0xFFFA243C),
                                        Color(0xFFFF5E3A),
                                        Color(0xFFFF2A68),
                                        Color(0xFFFA243C)
                                    )
                                ),
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Box(
                        modifier = Modifier
                            .size(134.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF141416))
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
                                tint = Color.Gray
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (artist.songs.isEmpty()) "Online YT Music Profile" else "${artist.songs.size} library songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }

            // Tabs for Library vs Online (if available)
            if (hasOnlineProfile && artist.songs.isNotEmpty()) {
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
            if (selectedTab == 0 && artist.songs.isNotEmpty()) {
                // In Library Screen
                LazyColumn(
                    state = libListState,
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
                                    focusManager.clearFocus()
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
                                    focusManager.clearFocus()
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
                                focusManager.clearFocus()
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
            } else if (hasOnlineProfile) {
                // Explore Online Screen
                ArtistOnlineDetailView(
                    artistId = artist.id!!,
                    appleRed = appleRed,
                    backdrop = backdrop,
                    listState = onlineListState,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Floating Top Bar with Liquid Back Button, Centered Title Pill, and Heart Button
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
                com.arcadesoftware.musix.components.LiquidButton(
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
                    com.arcadesoftware.musix.components.LiquidButton(
                        onClick = {},
                        backdrop = backdrop,
                        modifier = Modifier.height(36.dp).wrapContentWidth(),
                        isInteractive = false
                    ) {
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            // Liked/Heart button
            if (artist.id != null) {
                val context = LocalContext.current
                var isLiked by remember(artist.id) { 
                    mutableStateOf(LikedArtistsManager.isArtistLiked(context, artist.id)) 
                }
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    com.arcadesoftware.musix.components.LiquidButton(
                        onClick = {
                            val willBeLiked = LikedArtistsManager.toggleLikeArtist(context, artist.id, artist.name, artist.thumbnailUrl)
                            isLiked = willBeLiked
                            if (willBeLiked) {
                                com.arcadesoftware.musix.components.HeartAnimManager.trigger()
                            }
                            onLikedArtistsChanged()
                        },
                        backdrop = backdrop,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Heart Artist",
                            tint = if (isLiked) appleRed else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
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
    backdrop: LayerBackdrop,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var artistPage by remember { mutableStateOf<ArtistPage?>(null) }
    var isOnlineLoading by remember { mutableStateOf(true) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var activeSectionEndpoint by remember { mutableStateOf<Pair<String, BrowseEndpoint>?>(null) }

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
                    state = listState,
                    contentPadding = PaddingValues(bottom = 150.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // subscriber info / monthly listeners & Play Radio Buttons
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

                            // Online Play/Shuffle Buttons
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Play Radio
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        PlayerManager.play(page.artist)
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
                                            text = "Play Radio",
                                            color = appleRed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }
                                }

                                // Shuffle
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        val songsSection = page.sections.firstOrNull { 
                                            it.title.contains("songs", ignoreCase = true) || 
                                            it.title.contains("popular", ignoreCase = true) 
                                        }
                                        val songs = songsSection?.items?.filterIsInstance<SongItem>().orEmpty()
                                        if (songs.isNotEmpty()) {
                                            val playlistItem = PlaylistItem(
                                                id = "artist_online_shuffle_${page.artist.id}",
                                                title = page.artist.title,
                                                author = Artist(page.artist.title, null),
                                                songCountText = "${songs.size} songs",
                                                thumbnail = page.artist.thumbnail,
                                                playEndpoint = null,
                                                shuffleEndpoint = null,
                                                radioEndpoint = null
                                            )
                                            PlayerManager.currentPlayingPlaylist.value = playlistItem
                                            PlayerManager.playQueue(songs.shuffled(), 0)
                                        } else {
                                            PlayerManager.play(page.artist)
                                        }
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
                    }

                    // Render sections dynamically
                    page.sections.forEach { section ->
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (section.moreEndpoint != null) {
                                    Text(
                                        text = "See all",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = appleRed,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.clickable {
                                            focusManager.clearFocus()
                                            activeSectionEndpoint = Pair(section.title, section.moreEndpoint!!)
                                        }
                                    )
                                }
                            }
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
                                        focusManager.clearFocus()
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
                                                        focusManager.clearFocus()
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
                                                        focusManager.clearFocus()
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

        // Section details overlay screen
        AnimatedVisibility(
            visible = activeSectionEndpoint != null,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            activeSectionEndpoint?.let { (title, endpoint) ->
                ArtistSectionDetailView(
                    title = title,
                    endpoint = endpoint,
                    appleRed = appleRed,
                    backdrop = backdrop,
                    onBack = { activeSectionEndpoint = null }
                )
            }
        }
    }
}

@Composable
fun ArtistSectionDetailView(
    title: String,
    endpoint: BrowseEndpoint,
    appleRed: Color,
    backdrop: LayerBackdrop,
    onBack: () -> Unit
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var items by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var continuationToken by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isMoreLoading by remember { mutableStateOf(false) }

    LaunchedEffect(endpoint) {
        isLoading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) { YouTube.artistItems(endpoint) }
            result.onSuccess { page ->
                items = page.items
                continuationToken = page.continuation
            }.onFailure { e ->
                error = e.message ?: "Failed to load items"
            }
        } catch (e: Exception) {
            error = e.message ?: "An error occurred"
        } finally {
            isLoading = false
        }
    }

    val lazyListState = rememberLazyListState()
    val showMiniTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || (lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset > 200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(64.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CupertinoActivityIndicator(modifier = Modifier.size(48.dp))
                }
            } else if (error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Text(text = error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // Detect when user scrolls near the bottom to trigger loading more
                val shouldLoadMore = remember {
                    derivedStateOf {
                        val lastVisibleItem = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()
                        val totalItems = lazyListState.layoutInfo.totalItemsCount
                        lastVisibleItem != null && lastVisibleItem.index >= totalItems - 3 && continuationToken != null && !isMoreLoading
                    }
                }

                LaunchedEffect(shouldLoadMore.value) {
                    if (shouldLoadMore.value && continuationToken != null) {
                        isMoreLoading = true
                        try {
                            val result = withContext(Dispatchers.IO) {
                                YouTube.artistItemsContinuation(continuationToken!!)
                            }
                            result.onSuccess { page ->
                                items = items + page.items
                                continuationToken = page.continuation
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ArtistSectionDetail", "Error loading continuation", e)
                        } finally {
                            isMoreLoading = false
                        }
                    }
                }

                val songs = remember(items) { items.filterIsInstance<SongItem>() }

                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = 150.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (songs.isNotEmpty()) {
                        // All items in the list are songs (or we treat them as songs)
                        itemsIndexed(songs) { index, songItem ->
                            val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == songItem.id
                            LibrarySongRow(
                                songItem = songItem,
                                index = index,
                                isCurrentlyPlaying = isCurrentlyPlaying,
                                appleRed = appleRed,
                                onClick = {
                                    focusManager.clearFocus()
                                    val playlistItem = PlaylistItem(
                                        id = "artist_section_${endpoint.browseId}",
                                        title = title,
                                        author = Artist(songItem.artists.firstOrNull()?.name ?: "", null),
                                        songCountText = "${songs.size} songs",
                                        thumbnail = songItem.thumbnail,
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
                        // Items are albums, playlists, etc.
                        items(items) { item ->
                            when (item) {
                                is AlbumItem -> {
                                    LibraryAlbumListItem(
                                        title = item.title,
                                        subtitle = item.year?.toString() ?: "Album",
                                        thumbnail = item.thumbnail,
                                        onClick = {
                                            focusManager.clearFocus()
                                            PlayerManager.activePlaylistDetail.value = item
                                        }
                                    )
                                }
                                is PlaylistItem -> {
                                    LibraryAlbumListItem(
                                        title = item.title,
                                        subtitle = item.songCountText ?: "Playlist",
                                        thumbnail = item.thumbnail,
                                        onClick = {
                                            focusManager.clearFocus()
                                            PlayerManager.activePlaylistDetail.value = item
                                        }
                                    )
                                }
                                is SongItem -> {
                                    val isCurrentlyPlaying = PlayerManager.currentSong.collectAsState().value?.id == item.id
                                    LibrarySongRow(
                                        songItem = item,
                                        index = 0,
                                        isCurrentlyPlaying = isCurrentlyPlaying,
                                        appleRed = appleRed,
                                        onClick = {
                                            focusManager.clearFocus()
                                            PlayerManager.play(item)
                                        }
                                    )
                                }
                                else -> {}
                            }
                        }
                    }

                    if (isMoreLoading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CupertinoActivityIndicator(modifier = Modifier.size(24.dp))
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
                com.arcadesoftware.musix.components.LiquidButton(
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
                    com.arcadesoftware.musix.components.LiquidButton(
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

@Composable
fun LibraryAlbumListItem(
    title: String,
    subtitle: String,
    thumbnail: String?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
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
                    modifier = Modifier.size(28.dp).align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
