@file:OptIn(ExperimentalMaterial3Api::class)

package com.musiclify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiclify.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.musiclify.data.Artist
import com.musiclify.data.Song
import kotlinx.coroutines.launch

val Accent = Color(0xFFFF7A59)
val AccentSoft = Color(0xFF3A2620)
val PlayerCard = Color(0xFF2B2523)

// Bundled variable fonts (OFL): Inter for text, Space Grotesk for titles. Same as desktop and website.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun grotesk(w: Int) = Font(R.font.space_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))
val SpaceGrotesk = FontFamily(grotesk(500), grotesk(600), grotesk(700))

private val MusiclifyType: Typography = Typography().let { t ->
    fun TextStyle.text() = copy(fontFamily = Inter)
    fun TextStyle.display() = copy(fontFamily = SpaceGrotesk, letterSpacing = (-0.3).sp)
    Typography(
        displayLarge = t.displayLarge.display(), displayMedium = t.displayMedium.display(), displaySmall = t.displaySmall.display(),
        headlineLarge = t.headlineLarge.display(), headlineMedium = t.headlineMedium.display(), headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.display(), titleMedium = t.titleMedium.text(), titleSmall = t.titleSmall.text(),
        bodyLarge = t.bodyLarge.text(), bodyMedium = t.bodyMedium.text(), bodySmall = t.bodySmall.text(),
        labelLarge = t.labelLarge.text(), labelMedium = t.labelMedium.text(), labelSmall = t.labelSmall.text(),
    )
}

@Composable
fun MusiclifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = MusiclifyType,
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            secondaryContainer = Color(0xFF3A2620),
            onSecondaryContainer = Color(0xFFFFDBD1),
            background = Color(0xFF0F0F11),
            surface = Color(0xFF0F0F11),
            surfaceVariant = Color(0xFF1C1C21),
            surfaceContainer = Color(0xFF17171B),
            surfaceContainerHigh = Color(0xFF202026),
        ),
        content = content,
    )
}

/** Things a song row/card can do; implemented by the screen hosting it. */
class SongActions(
    val vm: AppViewModel,
    val openArtist: (Long) -> Unit,
    val removeFromPlaylist: ((Song) -> Unit)? = null,
)

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, corner: Int = 8) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun SongCard(song: Song, isCurrent: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Cover(song.cover, Modifier.size(150.dp), corner = 10)
        Spacer(Modifier.height(8.dp))
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface)
        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ArtistAvatar(artist: Artist, size: Int = 110, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(size.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AsyncImage(
                model = artist.picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (selected) {
                Box(Modifier.matchParentSize().clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Lucide.CircleCheck, contentDescription = "Selected", tint = Accent, modifier = Modifier.size(40.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun SongRow(song: Song, actions: SongActions, onClick: () -> Unit) {
    val vm = actions.vm
    val isCurrent = vm.player.currentId == song.id
    val downloaded by vm.downloadedIds.collectAsStateWithLifecycle()
    val progress by vm.downloadProgress.collectAsStateWithLifecycle()

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.cover, Modifier.size(52.dp), corner = 6)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    song.id in progress -> CircularProgressIndicator(
                        progress = { progress[song.id] ?: 0f }, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    song.id in downloaded -> Icon(Lucide.Download, contentDescription = "Downloaded",
                        tint = Accent, modifier = Modifier.size(14.dp))
                }
                if (song.id in progress || song.id in downloaded) Spacer(Modifier.width(4.dp))
                Text("${song.artist} · ${formatDuration(song.durationSec * 1000L)}", maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SongMenuButton(song, actions)
    }
}

@Composable
fun SongMenuButton(song: Song, actions: SongActions) {
    val vm = actions.vm
    var open by remember { mutableStateOf(false) }
    var pickPlaylist by remember { mutableStateOf(false) }
    val liked by vm.likedIds.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedIds.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    Box {
        IconButton(onClick = { open = true }) { Icon(Lucide.EllipsisVertical, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Play next", Lucide.ListEnd) { vm.playNext(song); open = false }
            if (song.artistId != 0L) MenuItem("Start radio", Lucide.Radio) { vm.startRadio(song); open = false }
            MenuItem("Add to queue", Lucide.ListMusic) { vm.addToQueue(song); open = false }
            val isLiked = song.id in liked
            MenuItem(if (isLiked) "Remove from Liked" else "Like", if (isLiked) Lucide.HeartFilled else Lucide.Heart) {
                vm.toggleLike(song); open = false
            }
            MenuItem("Add to playlist", Lucide.ListPlus) { pickPlaylist = true; open = false }
            if (song.id in downloaded) {
                MenuItem("Remove download", Lucide.Trash2) { vm.removeDownload(song); open = false }
            } else {
                MenuItem("Download", Lucide.Download) { vm.download(song); open = false }
            }
            actions.removeFromPlaylist?.let { remove ->
                MenuItem("Remove from this playlist", Lucide.CircleMinus) { remove(song); open = false }
            }
            if (song.artistId != 0L) {
                MenuItem("Go to artist", Lucide.User) { actions.openArtist(song.artistId); open = false }
            }
            MenuItem("Share", Lucide.Share2) {
                open = false
                scope.launch { ShareCard.shareSong(context, song.title, song.artist, song.coverBig.ifEmpty { song.cover }) }
            }
        }
    }
    if (pickPlaylist) AddToPlaylistDialog(song, vm) { pickPlaylist = false }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

@Composable
fun AddToPlaylistDialog(song: Song, vm: AppViewModel, onDismiss: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("New playlist", "Create", onDismiss = onDismiss) { name ->
            vm.createPlaylist(name, song)
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    ListItem(
                colors = ClearListItem,
                        headlineContent = { Text("New playlist") },
                        leadingContent = { Icon(Lucide.Plus, contentDescription = null) },
                        modifier = Modifier.clickable { creating = true },
                    )
                }
                items(playlists, key = { it.playlist.id }) { p ->
                    ListItem(
                colors = ClearListItem,
                        headlineContent = { Text(p.playlist.name) },
                        supportingContent = { Text("${p.songCount} songs") },
                        leadingContent = { Cover(p.cover, Modifier.size(40.dp), corner = 4) },
                        modifier = Modifier.clickable { vm.addToPlaylist(p.playlist.id, song); onDismiss() },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(title: String, confirm: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PlayShuffleButtons(songs: List<Song>, vm: AppViewModel) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val pausable = vm.isPlayingList(songs) && vm.player.isPlaying
        Button(onClick = { vm.playOrToggle(songs) }, enabled = songs.isNotEmpty()) {
            Icon(if (pausable) Lucide.PauseFilled else Lucide.PlayFilled, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (pausable) "Pause" else "Play")
        }
        FilledTonalButton(onClick = { vm.play(songs, shuffled = true) }, enabled = songs.isNotEmpty()) {
            Icon(Lucide.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

@Composable
fun CenteredMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** List rows without their own background, so the ambient light shows through behind them. */
val ClearListItem: ListItemColors
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)
