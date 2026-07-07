package com.arcadesoftware.musix.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Download
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
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.arcadesoftware.musix.PlayerManager
import com.arcadesoftware.musix.models.SimilarRecommendation
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.arcadesoftware.musix.db.entities.PlayHistoryEntity

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _homePage = MutableStateFlow<HomePage?>(null)
    val homePage = _homePage.asStateFlow()
    
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    
    private val _selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    val selectedChip = _selectedChip.asStateFlow()

    // Recently played from DB as a StateFlow
    val recentlyPlayed = com.arcadesoftware.musix.db.AppDatabase
        .getDatabase(application)
        .musicDao()
        .getRecentPlayHistory(20)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    init {
        loadHome()
    }

    private fun loadHome() {
        viewModelScope.launch(Dispatchers.IO) {
            val (cachedHome, cachedRecs) = com.arcadesoftware.musix.HomeCacheManager.load(getApplication())
            if (cachedHome != null) {
                _homePage.value = cachedHome
                similarRecommendations.value = cachedRecs
                _isLoading.value = false
                
                // Fetch fresh data in the background silently
                launch {
                    refreshContentSilently()
                }
            } else {
                _isLoading.value = true
                
                launch {
                    val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(getApplication())
                    val recentHistory = db.musicDao().getRecentPlayHistory(10).first()
                    val newRecommendations = mutableListOf<SimilarRecommendation>()
                    for (seed in recentHistory.take(5)) {
                        val endpoint = com.music.innertube.models.WatchEndpoint(videoId = seed.id)
                        val nextResult = YouTube.next(endpoint).getOrNull()
                        if (nextResult != null) {
                            val items = nextResult.items.filter { it.id != seed.id }.shuffled().take(10)
                            if (items.isNotEmpty()) {
                                newRecommendations.add(SimilarRecommendation(seed, items))
                            }
                        }
                    }
                    val shuffledRecs = newRecommendations.shuffled()
                    similarRecommendations.value = shuffledRecs
                    com.arcadesoftware.musix.HomeCacheManager.save(getApplication(), _homePage.value, shuffledRecs)
                }
                
                val result = YouTube.home()
                result.onSuccess {
                    _homePage.value = it
                    com.arcadesoftware.musix.HomeCacheManager.save(getApplication(), it, similarRecommendations.value)
                }
                _isLoading.value = false
            }
        }
    }

    private suspend fun refreshContentSilently() {
        try {
            val context = getApplication<Application>()
            val prefs = context.getSharedPreferences("recommendations_prefs", android.content.Context.MODE_PRIVATE)
            val lastUpdateTime = prefs.getLong("last_update_time", 0L)
            val currentTime = System.currentTimeMillis()
            val (cachedHome, cachedRecs) = com.arcadesoftware.musix.HomeCacheManager.load(context)
            
            val shouldUpdateRecs = cachedRecs.isEmpty() || (currentTime - lastUpdateTime > 24 * 60 * 60 * 1000L)
            
            val shuffledRecs = if (shouldUpdateRecs) {
                val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(context)
                val recentHistory = db.musicDao().getRecentPlayHistory(10).first()
                val newRecommendations = mutableListOf<SimilarRecommendation>()
                for (seed in recentHistory.take(5)) {
                    val endpoint = com.music.innertube.models.WatchEndpoint(videoId = seed.id)
                    val nextResult = YouTube.next(endpoint).getOrNull()
                    if (nextResult != null) {
                        val items = nextResult.items.filter { it.id != seed.id }.shuffled().take(10)
                        if (items.isNotEmpty()) {
                            newRecommendations.add(SimilarRecommendation(seed, items))
                        }
                    }
                }
                val result = newRecommendations.shuffled()
                prefs.edit().putLong("last_update_time", currentTime).apply()
                result
            } else {
                cachedRecs
            }

            val result = YouTube.home()
            result.onSuccess { freshHome ->
                _homePage.value = freshHome
                similarRecommendations.value = shuffledRecs
                com.arcadesoftware.musix.HomeCacheManager.save(context, freshHome, shuffledRecs)
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeViewModel", "Failed to refresh content silently", e)
        }
    }

    private fun forceLoadHome() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            
            launch {
                val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(getApplication())
                val recentHistory = db.musicDao().getRecentPlayHistory(10).first()
                val newRecommendations = mutableListOf<SimilarRecommendation>()
                for (seed in recentHistory.take(5)) {
                    val endpoint = com.music.innertube.models.WatchEndpoint(videoId = seed.id)
                    val nextResult = YouTube.next(endpoint).getOrNull()
                    if (nextResult != null) {
                        val items = nextResult.items.filter { it.id != seed.id }.shuffled().take(10)
                        if (items.isNotEmpty()) {
                            newRecommendations.add(SimilarRecommendation(seed, items))
                        }
                    }
                }
                val shuffledRecs = newRecommendations.shuffled()
                
                val context = getApplication<Application>()
                val prefs = context.getSharedPreferences("recommendations_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putLong("last_update_time", System.currentTimeMillis()).apply()

                similarRecommendations.value = shuffledRecs
                com.arcadesoftware.musix.HomeCacheManager.save(getApplication(), _homePage.value, shuffledRecs)
            }
            
            val result = YouTube.home()
            result.onSuccess {
                _homePage.value = it
                com.arcadesoftware.musix.HomeCacheManager.save(getApplication(), it, similarRecommendations.value)
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
            forceLoadHome()
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
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onOpenDrawer: () -> Unit = {},
    onOpenDownloads: () -> Unit = {}
) {
    val homePage by viewModel.homePage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedChip by viewModel.selectedChip.collectAsState()
    val recommendations by viewModel.similarRecommendations.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    
    var currentUser by remember { mutableStateOf(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser) }
    LaunchedEffect(Unit) {
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { auth ->
            currentUser = auth.currentUser
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isLoading,
        onRefresh = { viewModel.refresh() }
    )

    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPadding, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Musix",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val downloadProgressMap by PlayerManager.downloadProgressMap.collectAsState()
                        val activeDownloads = remember(downloadProgressMap) { downloadProgressMap.filter { it.value < 1.0f } }
                        val isDownloading = activeDownloads.isNotEmpty()

                        androidx.compose.animation.AnimatedVisibility(
                            visible = isDownloading,
                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
                        ) {
                            val averageProgress = remember(activeDownloads) {
                                if (activeDownloads.isEmpty()) 0f else activeDownloads.values.average().toFloat()
                            }
                            
                            Box(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(36.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .clickable { onOpenDownloads() },
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { averageProgress },
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Downloads Center",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        IconButton(onClick = onOpenDrawer) {
                            if (currentUser != null && currentUser?.photoUrl != null) {
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
                                            .size(40.dp)
                                            .graphicsLayer { rotationZ = rotation }
                                            .border(
                                                2.dp,
                                                androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Cyan, Color.Magenta, Color.Yellow, Color.Cyan)),
                                                androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                    AsyncImage(
                                        model = currentUser?.photoUrl,
                                        contentDescription = "Profile",
                                        modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = "Profile",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (homePage?.chips != null) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(homePage!!.chips!!) { chip ->
                            FilterChip(
                                selected = selectedChip == chip,
                                onClick = { viewModel.toggleChip(chip) },
                                label = { Text(chip.title) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }

            // Recently Played section (pinned at top)
            if (recentlyPlayed.isNotEmpty() && selectedChip == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Recently Played",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(recentlyPlayed) { entity ->
                                val songItem = entity.toSongItem()
                                Column(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .clickable { PlayerManager.play(songItem) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    ) {
                                        AsyncImage(
                                            model = entity.thumbnailUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = entity.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = entity.artistName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
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
                


                homePage?.sections?.forEachIndexed { index, section ->
                    item {
                        HomeSection(title = section.title) {
                            if (index == 0) {
                                SwipeableCardStack(items = section.items)
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
                    if (index == 0 && recommendations.isNotEmpty() && selectedChip == null) {
                        recommendations.forEach { recommendation ->
                            item {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = true,
                                    enter = androidx.compose.animation.fadeIn(
                                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600)
                                    )
                                ) {
                                    Column {
                                        Text(
                                            text = "Because you listened to ${recommendation.seed.title}",
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            items(recommendation.items) { item ->
                                                SquareCard(item)
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
fun SwipeableCardStack(
    items: List<YTItem>,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var currentIndex by remember { mutableStateOf(0) }
    // Safe modulo for positive and negative index values
    val activeIndex = ((currentIndex % items.size) + items.size) % items.size
    val coroutineScope = rememberCoroutineScope()
    
    val swipeX = remember { Animatable(0f) }
    val isSwipingRight = swipeX.value > 0f
    var isAnimating by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(380.dp),
        contentAlignment = Alignment.Center
    ) {
        // Show up to 3 cards in the stack
        for (i in 2 downTo 0) {
            val cardIndex = if (isSwipingRight) {
                // Swiping right: previous card comes in from the left on top
                when (i) {
                    0 -> ((activeIndex - 1) % items.size + items.size) % items.size // Previous card
                    1 -> activeIndex // Current card
                    else -> (activeIndex + 1) % items.size // Next card
                }
            } else {
                // Swiping left: current card slides out to the left
                when (i) {
                    0 -> activeIndex // Current card
                    1 -> (activeIndex + 1) % items.size // Next card
                    else -> (activeIndex + 2) % items.size // Second next card
                }
            }

            val cardItem = items[cardIndex]

            key(cardItem.id) {
                val swipeProgress = (kotlin.math.abs(swipeX.value) / 1020f).coerceIn(0f, 1f)

                // Background card styling for stack depth
                val scale: Float
                val offsetY: Float
                val rotation: Float
                val offsetX: Float

                if (isSwipingRight) {
                    // Previous card sliding in from left on top of the stack
                    when (i) {
                        0 -> { // Previous card coming in
                            scale = 0.94f + 0.06f * swipeProgress
                            offsetY = 0f
                            rotation = 0f
                            offsetX = -340f * (1f - swipeProgress)
                        }
                        1 -> { // Current card retreating to middle
                            scale = 1f - 0.06f * swipeProgress
                            offsetY = -16f * swipeProgress
                            rotation = 4f * swipeProgress
                            offsetX = 0f
                        }
                        else -> { // Next card retreating to bottom
                            scale = 0.94f - 0.06f * swipeProgress
                            offsetY = -16f - 16f * swipeProgress
                            rotation = 4f - 8f * swipeProgress
                            offsetX = 0f
                        }
                    }
                } else {
                    // Current card sliding out to the left
                    when (i) {
                        0 -> { // Current card swiping out
                            scale = 1f
                            offsetY = 0f
                            rotation = 0f
                            offsetX = swipeX.value / 3f
                        }
                        1 -> { // Next card scaling up to front
                            scale = 0.94f + 0.06f * swipeProgress
                            offsetY = -16f + 16f * swipeProgress
                            rotation = 4f * (1f - swipeProgress)
                            offsetX = 0f
                        }
                        else -> { // Second next card scaling up to middle
                            scale = 0.88f + 0.06f * swipeProgress
                            offsetY = -32f + 16f * swipeProgress
                            rotation = -4f + 8f * swipeProgress
                            offsetX = 0f
                        }
                    }
                }

                // Apply swipe gestures to the activeIndex card (which is in the center at start of swipe)
                val isTargetForGestures = cardIndex == activeIndex

                val dragModifier = if (isTargetForGestures) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (isAnimating) return@detectHorizontalDragGestures
                                coroutineScope.launch {
                                    val targetX = swipeX.value
                                    if (targetX < -300f) {
                                        isAnimating = true
                                        // Swipe current card off screen left
                                        swipeX.animateTo(
                                            targetValue = -1020f,
                                            animationSpec = tween(durationMillis = 300)
                                        )
                                        currentIndex++
                                        swipeX.snapTo(0f)
                                        isAnimating = false
                                    } else if (targetX > 300f) {
                                        isAnimating = true
                                        // Pull previous card fully to center
                                        swipeX.animateTo(
                                            targetValue = 1020f,
                                            animationSpec = tween(durationMillis = 300)
                                        )
                                        currentIndex--
                                        swipeX.snapTo(0f)
                                        isAnimating = false
                                    } else {
                                        // Snap back to center / snap previous card back off-screen
                                        swipeX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                    }
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (isAnimating) return@detectHorizontalDragGestures
                                change.consume()
                                coroutineScope.launch {
                                    swipeX.snapTo(swipeX.value + dragAmount)
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }

                FeaturedCard(
                    item = cardItem,
                    modifier = Modifier
                        .then(dragModifier)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            rotationZ = rotation
                        )
                        .offset(x = offsetX.dp, y = offsetY.dp)
                        .width(300.dp)
                        .height(340.dp)
                )
            }
        }
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
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable {
                if (item is PlaylistItem || item is AlbumItem) {
                    PlayerManager.activePlaylistDetail.value = item
                } else {
                    PlayerManager.play(item)
                }
            }
    ) {
        AsyncImage(
            model = thumbnail ?: "",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        val isPlaylist = item is PlaylistItem
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaylist) Icons.AutoMirrored.Rounded.PlaylistPlay else Icons.Rounded.MusicNote,
                contentDescription = if (isPlaylist) "Playlist" else "Music",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
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
            .clickable {
                if (item is PlaylistItem || item is AlbumItem) {
                    PlayerManager.activePlaylistDetail.value = item
                } else {
                    PlayerManager.play(item)
                }
            }
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
            val isPlaylist = item is PlaylistItem
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaylist) Icons.AutoMirrored.Rounded.PlaylistPlay else Icons.Rounded.MusicNote,
                    contentDescription = if (isPlaylist) "Playlist" else "Music",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
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
