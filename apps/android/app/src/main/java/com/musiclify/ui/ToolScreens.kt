@file:OptIn(ExperimentalMaterial3Api::class)

package com.musiclify.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
private fun ToolTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "Back") } },
        actions = actions,
    )
}

// ---------------------------------------------------------------- listening stats

@Composable
fun StatsScreen(vm: AppViewModel, actions: SongActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(StatsPeriod.MONTH) }
    var stats by remember { mutableStateOf<ListeningStats?>(null) }
    LaunchedEffect(period) {
        stats = null
        stats = vm.stats(period)
    }

    Column(Modifier.fillMaxSize()) {
        ToolTopBar("Your stats", onBack) {
            val s = stats
            if (s != null && s.plays > 0) IconButton(onClick = { scope.launch { ShareCard.shareStats(context, period.label.lowercase(), s) } }) {
                Icon(Lucide.Share2, contentDescription = "Share stats")
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsPeriod.entries.forEach { p ->
                FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.label) })
            }
        }
        val s = stats
        when {
            s == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.plays == 0 -> CenteredMessage("Nothing played ${period.label.lowercase()} yet.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("%,d".format(s.minutes), "minutes", Modifier.weight(1f))
                        StatTile("%,d".format(s.plays), "plays", Modifier.weight(1f))
                        StatTile("%,d".format(s.songCount), "songs", Modifier.weight(1f))
                    }
                }
                if (s.topArtists.isNotEmpty()) {
                    item { SectionHeader("Top artists") }
                    itemsIndexed(s.topArtists, key = { _, a -> "a${a.artistId}" }) { i, a ->
                        ListItem(
                            colors = ClearListItem,
                            headlineContent = { Text("${i + 1}. ${a.artist}", fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("${a.plays} plays") },
                            leadingContent = { Cover(a.cover, Modifier.size(48.dp), corner = 24) },
                            modifier = Modifier.clickable(enabled = a.artistId != 0L) { actions.openArtist(a.artistId) },
                        )
                    }
                }
                item { SectionHeader("Top songs", "Counted each time a song starts") }
                val songs = s.topSongs.map { it.song }
                itemsIndexed(s.topSongs, key = { _, t -> "s${t.song.id}" }) { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", modifier = Modifier.padding(start = 16.dp).width(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f)) { SongRow(t.song, actions) { vm.play(songs, i) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Accent, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- playlist import

@Composable
fun ImportScreen(vm: AppViewModel, openPlaylist: (Long) -> Unit, onBack: () -> Unit) {
    var link by remember { mutableStateOf("") }
    val state = vm.importState
    DisposableEffect(Unit) { onDispose { if (vm.importState !is ImportState.Matching && vm.importState !is ImportState.Reading) vm.resetImport() } }

    Column(Modifier.fillMaxSize()) {
        ToolTopBar("Import playlist", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Paste a link to a public Spotify playlist or album, or a YouTube / YouTube Music playlist. " +
                "Musiclify finds each song and saves them as a new playlist.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = link, onValueChange = { link = it }, singleLine = true,
                placeholder = { Text("https://open.spotify.com/playlist/…") },
                leadingIcon = { Icon(Lucide.Link, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImportState.Reading && state !is ImportState.Matching,
            )
            when (state) {
                ImportState.Idle, is ImportState.Failed -> {
                    if (state is ImportState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.importPlaylist(link) }, enabled = link.isNotBlank()) { Text("Import") }
                    Text("Spotify shares up to about 100 songs of a playlist this way.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ImportState.Reading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading the playlist…")
                }
                is ImportState.Matching -> {
                    LinearProgressIndicator(progress = { state.done.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("Finding songs from \"${state.name}\": ${state.done} of ${state.total}")
                    TextButton(onClick = vm::resetImport) { Text("Cancel") }
                }
                is ImportState.Done -> {
                    val r = state.result
                    Text("Saved \"${r.name}\" with ${r.matched.size} songs.", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.resetImport(); openPlaylist(state.playlistId) }) { Text("Open playlist") }
                        OutlinedButton(onClick = { link = ""; vm.resetImport() }) { Text("Import another") }
                    }
                    if (r.missing.isNotEmpty()) {
                        Text("Couldn't find ${r.missing.size}:", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        (state as? ImportState.Done)?.result?.missing?.takeIf { it.isNotEmpty() }?.let { missing ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                items(missing) { t ->
                    Text("${t.title} · ${t.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- free up space

/** Shown when the phone is low on storage; the user decides, nothing is removed without "Review". */
@Composable
fun LowStorageDialog(vm: AppViewModel, onReview: () -> Unit) {
    val context = LocalContext.current
    val free = remember { vm.deviceSpace().first }
    AlertDialog(
        onDismissRequest = vm::dismissStoragePrompt,
        icon = { Icon(Lucide.HardDrive, contentDescription = null) },
        title = { Text("Your phone is running low on storage") },
        text = {
            Text("Only ${Formatter.formatShortFileSize(context, free)} is free. Your downloaded songs use " +
                "${Formatter.formatShortFileSize(context, vm.storageBytes())}. Removing a few you rarely play would help. " +
                "You choose which ones.")
        },
        confirmButton = { TextButton(onClick = { vm.dismissStoragePrompt(); onReview() }) { Text("Review songs") } },
        dismissButton = { TextButton(onClick = vm::snoozeStoragePrompt) { Text("Not now") } },
    )
}

/**
 * Pick downloads to remove. When space is short, the least recently played songs are ticked up
 * front until enough would be freed; every box can be changed before anything is removed.
 */
@Composable
fun FreeSpaceScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var space by remember { mutableStateOf(vm.deviceSpace()) }
    var removing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val needed = remember(reload) { vm.bytesToFree() }

    LaunchedEffect(reload) {
        val list = vm.downloadItems()
        items = list
        // Suggest: least recently played first, until the phone would be back to a comfortable amount free.
        var sum = 0L
        selected = if (needed <= 0) emptySet() else list.takeWhile { sum < needed }.onEach { sum += it.bytes }.map { it.song.id }.toSet()
        space = vm.deviceSpace()
    }

    val fmt = { b: Long -> Formatter.formatShortFileSize(context, b) }
    val list = items
    val total = list?.sumOf { it.bytes } ?: 0L
    val chosen = list?.filter { it.song.id in selected }.orEmpty()
    val chosenBytes = chosen.sumOf { it.bytes }

    Column(Modifier.fillMaxSize()) {
        ToolTopBar("Free up space", onBack) {
            if (!list.isNullOrEmpty()) TextButton(onClick = {
                selected = if (selected.size == list.size) emptySet() else list.map { it.song.id }.toSet()
            }) { Text(if (selected.size == list.size) "Select none" else "Select all") }
        }
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> CenteredMessage("No downloaded songs.")
            else -> {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (needed > 0) Text(
                        "Your phone is running low on storage. We've ticked the songs you play least. Untick any you want " +
                            "to keep, or tick others.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${list.size} downloads use ${fmt(total)} · ${fmt(space.first)} free on this phone",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(list, key = { it.song.id }) { item ->
                        val checked = item.song.id in selected
                        val toggle = { selected = if (checked) selected - item.song.id else selected + item.song.id }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Cover(item.song.cover, Modifier.size(44.dp), corner = 6)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text("${item.song.artist} · ${lastPlayedText(item)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(fmt(item.bytes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        Text("Selected: ${chosen.size} songs · ${fmt(chosenBytes)}", fontWeight = FontWeight.SemiBold)
                        Text("Free space after: ${fmt(space.first + chosenBytes)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                removing = true
                                vm.removeDownloads(selected) { removing = false; reload++ }
                            },
                            enabled = chosen.isNotEmpty() && !removing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (removing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Remove ${chosen.size} songs (${fmt(chosenBytes)})")
                        }
                        Text("Removed songs stay in your library and download again when you play them.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

private fun lastPlayedText(item: DownloadItem): String = item.lastPlayed?.let {
    "played " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
} ?: "never played"
