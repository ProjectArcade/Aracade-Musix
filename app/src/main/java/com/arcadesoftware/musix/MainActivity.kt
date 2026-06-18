package com.arcadesoftware.musix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.robinpcrd.cupertino.icons.CupertinoIcons
import io.github.robinpcrd.cupertino.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
                    CupertinoIcons.Default.House, 
                    contentDescription = "Home", 
                    tint = if (selectedTab == 0) activeColor else inactiveColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Home", 
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selectedTab == 0) activeColor else inactiveColor
                )
            }
            LiquidBottomTab(onClick = { onTabSelected(1) }) {
                Icon(
                    CupertinoIcons.Default.MusicNoteList, 
                    contentDescription = "Playlist", 
                    tint = if (selectedTab == 1) activeColor else inactiveColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Playlist", 
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selectedTab == 1) activeColor else inactiveColor
                )
            }
            LiquidBottomTab(onClick = { onTabSelected(2) }) {
                Icon(
                    CupertinoIcons.Default.SquareStack, 
                    contentDescription = "Library", 
                    tint = if (selectedTab == 2) activeColor else inactiveColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Library", 
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selectedTab == 2) activeColor else inactiveColor
                )
            }
            LiquidBottomTab(onClick = { onTabSelected(3) }) {
                Icon(
                    CupertinoIcons.Default.DotRadiowavesLeftAndRight, 
                    contentDescription = "Podcast", 
                    tint = if (selectedTab == 3) activeColor else inactiveColor,
                    modifier = Modifier.size(20.dp)
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
                CupertinoIcons.Default.MagnifyingGlass, 
                contentDescription = "Search", 
                tint = inactiveColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}