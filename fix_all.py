import re
import os

# --- PlaylistDetailScreen.kt ---
with open("app/src/main/java/com/arcadesoftware/musix/ui/screens/PlaylistDetailScreen.kt", "r") as f:
    pl_content = f.read()

if "import kotlinx.coroutines.flow.first" not in pl_content:
    pl_content = pl_content.replace("import kotlinx.coroutines.Dispatchers", "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.flow.first")

# Update isLiked to include alwaysShuffle
if "val alwaysShuffle =" not in pl_content:
    pl_content = pl_content.replace("""    var isLiked by remember(playlistItem.id) {
        mutableStateOf(LikedPlaylistsManager.isPlaylistLiked(context, playlistItem.id))
    }""", """    var isLiked by remember(playlistItem.id) {
        mutableStateOf(LikedPlaylistsManager.isPlaylistLiked(context, playlistItem.id))
    }

    val alwaysShuffle = remember(context) {
        context.getSharedPreferences("musix_profile_settings", android.content.Context.MODE_PRIVATE).getBoolean("always_shuffle", false)
    }""")

# Update fetching logic
fetch_target = """                if (playlistItem is PlaylistItem) {
                    YouTube.playlist(playlistItem.id).getOrNull()?.let { Pair(it.songs, null) }"""
fetch_replacement = """                if (playlistItem is PlaylistItem) {
                    val id = playlistItem.id
                    val db = com.arcadesoftware.musix.db.AppDatabase.getDatabase(context)
                    if (id == "downloads" || id == "local_downloads") {
                        val songs = db.musicDao().getDownloadedSongs().first().map { it.toSongItem() }
                        Pair(songs, "Your downloaded offline music")
                    } else if (id == "liked") {
                        val likedSongIds = com.arcadesoftware.musix.LikedSongsManager.getLikedSongIds(context)
                        val songs = db.musicDao().getPlayHistory().first().filter { likedSongIds.contains(it.id) }.map { it.toSongItem() }
                        Pair(songs, "Songs you liked")
                    } else if (id.toLongOrNull() != null) {
                        val playlistId = id.toLong()
                        val songs = db.musicDao().getSongsForPlaylist(playlistId).first().map { it.toSongItem() }
                        val playlistEntity = db.musicDao().getPlaylists().first().find { it.id == playlistId }
                        Pair(songs, playlistEntity?.name ?: "Custom Playlist")
                    } else if (id.startsWith("artist_lib_") || id.startsWith("artist_section_") || id.startsWith("local_")) {
                        Pair(com.arcadesoftware.musix.PlayerManager.queue.value, "Playing from Queue")
                    } else {
                        com.music.innertube.YouTube.playlist(id).getOrNull()?.let { Pair(it.songs, null) }
                    }"""
if "val id = playlistItem.id" not in pl_content:
    pl_content = pl_content.replace(fetch_target, fetch_replacement)

# Update Play Button Shuffle logic
play_btn_target = """                                                PlayerManager.playQueue(songList, 0)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = appleRed,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Play","""
play_btn_replacement = """                                                PlayerManager.playQueue(if (alwaysShuffle) songList.shuffled() else songList, 0)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (alwaysShuffle) {
                                        Icon(
                                            imageVector = Icons.Rounded.Shuffle,
                                            contentDescription = null,
                                            tint = appleRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = appleRed,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (alwaysShuffle) "Shuffle Play" else "Play","""
if "Shuffle Play" not in pl_content:
    pl_content = pl_content.replace(play_btn_target, play_btn_replacement)

with open("app/src/main/java/com/arcadesoftware/musix/ui/screens/PlaylistDetailScreen.kt", "w") as f:
    f.write(pl_content)

# --- MainActivity.kt ---
with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "r") as f:
    content = f.read()

# Replace the top Sign In button logic
pattern_sign_in = r'if \(currentUser == null\) \{\s*OutlinedButton.*?Text\("Sign In with Google"\)\s*\}\s*\} else \{'
new_sign_in = 'if (currentUser != null) {'
content = re.sub(pattern_sign_in, new_sign_in, content, flags=re.DOTALL)

# Inject accountBackdrop and Box wrap inside ModalBottomSheet
pattern_bs = r'(ModalBottomSheet\([\s\S]*?\) \{)(\n\s*var isSigningIn)'
new_bs = r'\1\n            val accountBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()\n            androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.layerBackdrop(accountBackdrop)) {\2'
content = re.sub(pattern_bs, new_bs, content, count=1)

# Safely close the Box right after the animated content
# The AnimatedContent block ends at the end of AccountSheet.
pattern_end_bs = r'(\s*Button\(\s*onClick = \{ showDeleteConfirmDialog = true \}.*?Text\("Delete Account".*?\)\s*\}\s*\n\s*\}.*?\}.*?\}\n\s*\})'
# Add closing brace for the Box
content = re.sub(pattern_end_bs, r'\1\n            }', content, flags=re.DOTALL, count=1)

# Replace mainBackdrop with accountBackdrop for Liquid components inside AccountSheet
pattern_sheet = r'(androidx\.compose\.animation\.AnimatedContent\([\s\S]*?\} screen ->[\s\S]*?)(val currentRoute)'
def replace_backdrop(m):
    return m.group(1).replace('backdrop = mainBackdrop', 'backdrop = accountBackdrop') + m.group(2)
content = re.sub(pattern_sheet, replace_backdrop, content, count=1)

# Update padding
content = content.replace(
    'padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp)',
    'padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)'
)

with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "w") as f:
    f.write(content)
