package com.arcadesoftware.musix.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.arcadesoftware.musix.PlayerManager
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.HomePage
import io.github.robinpcrd.cupertino.CupertinoActivityIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val _homePage = MutableStateFlow<HomePage?>(null)
    val homePage = _homePage.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    
    private val _selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    val selectedChip = _selectedChip.asStateFlow()

    init {
        loadHome()
    }

    private fun loadHome() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val result = YouTube.home()
            result.onSuccess {
                _homePage.value = it
            }
            _isLoading.value = false
        }
    }
    
    fun toggleChip(chip: HomePage.Chip?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_selectedChip.value == chip) {
                _selectedChip.value = null
                loadHome()
                return@launch
            }
            
            _selectedChip.value = chip
            _isLoading.value = true
            _homePage.value = _homePage.value?.copy(sections = emptyList())
            val result = YouTube.home(params = chip?.endpoint?.params)
            result.onSuccess {
                _homePage.value = _homePage.value?.copy(
                    sections = it.sections
                )
            }
            _isLoading.value = false
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _selectedChip.value = null
            loadHome()
        }
    }
}

fun Modifier.shimmerEffect(): Modifier = composed {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val startOffsetX by transition.animateFloat(
        initialValue = -2 * size.width.toFloat(),
        targetValue = 2 * size.width.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000)
        ), label = "shimmer_offset"
    )

    background(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Gray.copy(alpha = 0.2f),
                Color.Gray.copy(alpha = 0.4f),
                Color.Gray.copy(alpha = 0.2f),
            ),
            start = Offset(startOffsetX, 0f),
            end = Offset(startOffsetX + size.width.toFloat(), size.height.toFloat())
        )
    ).onGloballyPositioned { size = it.size }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val homePage by viewModel.homePage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedChip by viewModel.selectedChip.collectAsState()

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isLoading,
        onRefresh = { viewModel.refresh() }
    )

    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 48.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            item {
                Text(
                    text = "Musix",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            val currentChips = homePage?.chips?.filter { !it.title.contains("Podcast", ignoreCase = true) }
            if (!currentChips.isNullOrEmpty()) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(currentChips) { chip ->
                            FilterChip(
                                selected = chip == selectedChip,
                                onClick = { viewModel.toggleChip(chip) },
                                label = { Text(chip.title) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }

            if (isLoading && homePage?.sections.isNullOrEmpty()) {
                item {
                    HomeSectionSkeleton(isFeatured = true)
                }
                items(2) {
                    HomeSectionSkeleton(isFeatured = false)
                }
            } else {
                if (isLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CupertinoActivityIndicator(modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                
                // Spotify Speed Dial (taking first 6 items from the first section)
                val speedDialItems = homePage?.sections?.firstOrNull()?.items?.take(6)
                if (!speedDialItems.isNullOrEmpty()) {
                    item {
                        SpeedDialGrid(speedDialItems)
                    }
                }

                homePage?.sections?.forEachIndexed { index, section ->
                    item {
                        HomeSection(title = section.title) {
                            if (index == 0) {
                                val pagerState = rememberPagerState(pageCount = { section.items.size })
                                HorizontalPager(
                                    state = pagerState,
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    pageSpacing = 16.dp
                                ) { page ->
                                    FeaturedCard(section.items[page], modifier = Modifier.fillMaxWidth())
                                }
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp)
                                ) {
                                    items(section.items) { item ->
                                        SquareCard(item)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        PullRefreshIndicator(
            refreshing = isLoading,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
fun HomeSectionSkeleton(isFeatured: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        Box(modifier = Modifier.width(150.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
        if (isFeatured) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(2) {
                    Box(modifier = Modifier.width(320.dp).height(360.dp).clip(RoundedCornerShape(24.dp)).shimmerEffect())
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(4) {
                    Column(modifier = Modifier.width(160.dp)) {
                        Box(modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    }
                }
            }
        }
    }
}

@Composable
fun HomeSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        content()
    }
}

@Composable
fun FeaturedCard(item: YTItem, modifier: Modifier = Modifier) {
    val title = when (item) {
        is SongItem -> item.title
        is AlbumItem -> item.title
        is PlaylistItem -> item.title
        is ArtistItem -> item.title
        else -> "Unknown"
    }
    val subtitle = when (item) {
        is SongItem -> item.artists?.joinToString { it.name } ?: "Unknown Artist"
        is AlbumItem -> item.artists?.joinToString { it.name } ?: "Unknown Artist"
        is PlaylistItem -> item.author?.name ?: "Playlist"
        is ArtistItem -> "Artist"
        else -> ""
    }
    val thumbnail = when (item) {
        is SongItem -> item.thumbnail
        is AlbumItem -> item.thumbnail
        is PlaylistItem -> item.thumbnail
        is ArtistItem -> item.thumbnail
        else -> null
    }

    Box(
        modifier = Modifier
            .width(320.dp)
            .height(360.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable { PlayerManager.play(item) }
    ) {
        AsyncImage(
            model = thumbnail ?: "",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        startY = 400f
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SquareCard(item: YTItem) {
    val title = when (item) {
        is SongItem -> item.title
        is AlbumItem -> item.title
        is PlaylistItem -> item.title
        is ArtistItem -> item.title
        else -> "Unknown"
    }
    val subtitle = when (item) {
        is SongItem -> item.artists?.joinToString { it.name } ?: "Unknown Artist"
        is AlbumItem -> item.artists?.joinToString { it.name } ?: "Unknown Artist"
        is PlaylistItem -> item.author?.name ?: "Playlist"
        is ArtistItem -> "Artist"
        else -> ""
    }
    val thumbnail = when (item) {
        is SongItem -> item.thumbnail
        is AlbumItem -> item.thumbnail
        is PlaylistItem -> item.thumbnail
        is ArtistItem -> item.thumbnail
        else -> null
    }

    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable { PlayerManager.play(item) }
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            AsyncImage(
                model = thumbnail ?: "",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SpeedDialGrid(items: List<YTItem>) {
    val displayItems = items.take(6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        for (row in displayItems.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in row) {
                    SpeedDialCard(item, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun SpeedDialCard(item: YTItem, modifier: Modifier = Modifier) {
    val title = when (item) {
        is SongItem -> item.title
        is AlbumItem -> item.title
        is PlaylistItem -> item.title
        is ArtistItem -> item.title
        else -> "Unknown"
    }
    val thumbnail = when (item) {
        is SongItem -> item.thumbnail
        is AlbumItem -> item.thumbnail
        is PlaylistItem -> item.thumbnail
        is ArtistItem -> item.thumbnail
        else -> null
    }

    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { PlayerManager.play(item) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = thumbnail ?: "",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}
