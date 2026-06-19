package com.arcadesoftware.musix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem

@Composable
fun RecommendationsScreen(viewModel: HomeViewModel = viewModel()) {
    val recommendations by viewModel.similarRecommendations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPadding, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Made For You",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 36.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discover new music based on what you love.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (recommendations.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            io.github.robinpcrd.cupertino.CupertinoActivityIndicator(
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(horizontal = 40.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "No Recommendations Yet",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Listen to some tracks or search for your favorite artists to help us build your personalized profile.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                items(recommendations) { recommendation ->
                    val seedThumb = when (val seed = recommendation.seed) {
                        is SongItem -> seed.thumbnail.replace(Regex("w\\d+-h\\d+.*"), "w200-h200-l90-rj")
                        is AlbumItem -> seed.thumbnail.replace(Regex("w\\d+-h\\d+.*"), "w200-h200-l90-rj")
                        is PlaylistItem -> seed.thumbnail?.replace(Regex("w\\d+-h\\d+.*"), "w200-h200-l90-rj")
                        is ArtistItem -> seed.thumbnail?.replace(Regex("w\\d+-h\\d+.*"), "w200-h200-l90-rj")
                        else -> ""
                    }
                    val seedArtist = when (val seed = recommendation.seed) {
                        is SongItem -> seed.artists?.firstOrNull()?.name
                        is AlbumItem -> seed.artists?.firstOrNull()?.name
                        is PlaylistItem -> seed.author?.name
                        else -> null
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Rich Context Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!seedThumb.isNullOrEmpty()) {
                                AsyncImage(
                                    model = seedThumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Because you listened to",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        letterSpacing = 1.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                Text(
                                    text = recommendation.seed.title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 22.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!seedArtist.isNullOrEmpty()) {
                                    Text(
                                        text = seedArtist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Recommendation Carousel
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
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
