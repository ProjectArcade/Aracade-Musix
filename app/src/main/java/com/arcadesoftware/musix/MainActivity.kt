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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusixTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var isSearchActive by remember { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AppBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { 
                    selectedTab = it
                    isSearchActive = false
                },
                onSearchClick = { isSearchActive = true },
                backdrop = backdrop
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            if (isSearchActive) {
                Text("Search Fragment")
            } else {
                when (selectedTab) {
                    0 -> Text("Home Fragment")
                    1 -> Text("Playlist Fragment")
                    2 -> Text("Library Fragment")
                    3 -> Text("Podcast Fragment")
                }
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        MiniPlayer(
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
                    Icons.Rounded.QueueMusic, 
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
                    Icons.Rounded.Podcasts, 
                    contentDescription = "Podcast", 
                    tint = if (selectedTab == 3) activeColor else inactiveColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Podcast", 
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
fun MiniPlayer(backdrop: com.kyant.backdrop.Backdrop, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    val containerColor = if (isLightTheme) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.4f)
    val contentColor = if (isLightTheme) Color.Black else Color.White

    val bottomPadding by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else 112.dp)
    val horizontalPadding by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else 45.dp)
    val cornerRadius by androidx.compose.animation.core.animateDpAsState(if (expanded) 0.dp else 100.dp)

    com.arcadesoftware.musix.components.LiquidButton(
        onClick = { expanded = !expanded },
        backdrop = backdrop,
        surfaceColor = containerColor,
        isInteractive = false,
        shape = { RoundedCornerShape(cornerRadius) },
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding, start = horizontalPadding, end = horizontalPadding)
            .animateContentSize(animationSpec = spring())
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount < -10) expanded = true
                        else if (dragAmount > 10) expanded = false
                    }
                )
            }
    ) {
        Box(modifier = Modifier.weight(1f)) {
            val consumeClicksModifier = Modifier.clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {}
            if (!expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Gray.copy(0.5f)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Not Playing", color = contentColor, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                        Text("Unknown Artist", color = contentColor.copy(0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = contentColor, modifier = Modifier.size(20.dp).then(consumeClicksModifier))
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = contentColor, modifier = Modifier.size(24.dp).then(consumeClicksModifier))
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = contentColor, modifier = Modifier.size(20.dp).then(consumeClicksModifier))
                    Spacer(modifier = Modifier.width(4.dp))
                }
            } else {
                val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                Column(
                    modifier = Modifier.fillMaxWidth().height(screenHeight).padding(top = 48.dp, start = 24.dp, end = 24.dp, bottom = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.Gray.copy(0.5f)))
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Not Playing", color = contentColor, style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("Unknown Artist", color = contentColor.copy(0.7f), style = MaterialTheme.typography.bodyLarge)
                        }
                        Icon(Icons.Rounded.FavoriteBorder, contentDescription = "Like", tint = contentColor, modifier = Modifier.size(32.dp).then(consumeClicksModifier))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    var sliderValue by remember { mutableStateOf(0.3f) }
                    val sliderConsumeGesture = Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { _, _ -> }
                    }
                    com.arcadesoftware.musix.components.LiquidSlider(
                        value = { sliderValue },
                        onValueChange = { sliderValue = it },
                        valueRange = 0f..1f,
                        visibilityThreshold = 0.001f,
                        backdrop = backdrop,
                        accentColor = contentColor,
                        modifier = Modifier.padding(horizontal = 16.dp).then(consumeClicksModifier).then(sliderConsumeGesture)
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = contentColor, modifier = Modifier.size(48.dp).then(consumeClicksModifier))
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = contentColor, modifier = Modifier.size(72.dp).then(consumeClicksModifier))
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = contentColor, modifier = Modifier.size(48.dp).then(consumeClicksModifier))
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.AddCircleOutline, contentDescription = "Add to Playlist", tint = contentColor.copy(0.8f), modifier = Modifier.size(28.dp).then(consumeClicksModifier))
                        Icon(Icons.Rounded.Download, contentDescription = "Download", tint = contentColor.copy(0.8f), modifier = Modifier.size(28.dp).then(consumeClicksModifier))
                        Icon(Icons.Rounded.Lyrics, contentDescription = "Lyrics", tint = contentColor.copy(0.8f), modifier = Modifier.size(28.dp).then(consumeClicksModifier))
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "Up Next", tint = contentColor.copy(0.8f), modifier = Modifier.size(28.dp).then(consumeClicksModifier))
                    }
                }
            }
        }
    }
}
