package com.arcadesoftware.musix.components

import androidx.compose.animation.AnimatedContent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arcadesoftware.musix.db.AppDatabase
import com.arcadesoftware.musix.db.entities.PlaylistEntity
import com.arcadesoftware.musix.db.entities.PlaylistSongEntity
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bottom sheet that lets the user add [song] to an existing playlist or create a new one.
 * Dismiss with [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    song: SongItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val db = remember(context) { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val playlists by db.musicDao().getPlaylists().collectAsState(initial = emptyList())

    var showCreateField by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // When create field appears, request focus
    LaunchedEffect(showCreateField) {
        if (showCreateField) focusRequester.requestFocus()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = song.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add to Playlist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))

            // Create new playlist row / inline field
            if (showCreateField) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text("Playlist name") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            if (newPlaylistName.isNotBlank()) {
                                scope.launch(Dispatchers.IO) {
                                    val playlistId = db.musicDao().insertPlaylist(
                                        PlaylistEntity(name = newPlaylistName.trim())
                                    )
                                    db.musicDao().insertPlayHistory(
                                        com.arcadesoftware.musix.db.entities.PlayHistoryEntity(
                                            id = song.id,
                                            title = song.title,
                                            artistName = song.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                            artistId = song.artists?.firstOrNull()?.id,
                                            thumbnailUrl = song.thumbnail ?: ""
                                        )
                                    )
                                    db.musicDao().insertPlaylistSong(
                                        PlaylistSongEntity(
                                            playlistId = playlistId,
                                            songId = song.id,
                                            position = 0
                                        )
                                    )
                                    // suspend: runs in same coroutine, sees committed DB data
                                    com.arcadesoftware.musix.db.FirestoreSyncManager.syncPlaylistsSuspend(context)
                                    onDismiss()
                                }
                            }
                        })
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    FilledIconButton(
                        onClick = {
                            keyboardController?.hide()
                            if (newPlaylistName.isNotBlank()) {
                                scope.launch(Dispatchers.IO) {
                                    val playlistId = db.musicDao().insertPlaylist(
                                        PlaylistEntity(name = newPlaylistName.trim())
                                    )
                                    db.musicDao().insertPlayHistory(
                                        com.arcadesoftware.musix.db.entities.PlayHistoryEntity(
                                            id = song.id,
                                            title = song.title,
                                            artistName = song.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                            artistId = song.artists?.firstOrNull()?.id,
                                            thumbnailUrl = song.thumbnail ?: ""
                                        )
                                    )
                                    db.musicDao().insertPlaylistSong(
                                        PlaylistSongEntity(
                                            playlistId = playlistId,
                                            songId = song.id,
                                            position = 0
                                        )
                                    )
                                    // suspend: runs in same coroutine, sees committed DB data
                                    com.arcadesoftware.musix.db.FirestoreSyncManager.syncPlaylistsSuspend(context)
                                    onDismiss()
                                }
                            }
                        },
                        enabled = newPlaylistName.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = "Create")
                    }
                }
            } else {
                // "New Playlist" button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCreateField = true }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        "New Playlist",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Existing playlists
            if (playlists.isEmpty() && !showCreateField) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No playlists yet. Create one above!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    var added by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !added) {
                                scope.launch(Dispatchers.IO) {
                                    // Guard against duplicate song entries in the same playlist
                                    val alreadyAdded = db.musicDao().isSongInPlaylist(playlist.id, song.id) > 0
                                    if (!alreadyAdded) {
                                        db.musicDao().insertPlayHistory(
                                            com.arcadesoftware.musix.db.entities.PlayHistoryEntity(
                                                id = song.id,
                                                title = song.title,
                                                artistName = song.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                                artistId = song.artists?.firstOrNull()?.id,
                                                thumbnailUrl = song.thumbnail ?: ""
                                            )
                                        )
                                        db.musicDao().insertPlaylistSong(
                                            PlaylistSongEntity(
                                                playlistId = playlist.id,
                                                songId = song.id,
                                                position = Int.MAX_VALUE
                                            )
                                        )
                                        // Use suspend sync — schedulePushAllLocalDataToFirestore
                                        // had a 5-minute delay and never ran in practice.
                                        com.arcadesoftware.musix.db.FirestoreSyncManager.syncPlaylistsSuspend(context)
                                    }
                                    added = true
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Playlist icon placeholder
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        AnimatedContent(targetState = added, label = "addedTick") { isAdded ->
                            if (isAdded) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = "Added",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = "Add",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 84.dp, end = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f),
                        thickness = 0.5.dp
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
