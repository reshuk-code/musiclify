@file:OptIn(ExperimentalMaterial3Api::class)

package com.musiclify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.musiclify.data.Lyrics

// ---------------------------------------------------------------- mini player

@Composable
fun MiniPlayer(vm: AppViewModel, onOpen: () -> Unit) {
    val p = vm.player
    val id = p.currentId ?: return
    val liked by vm.likedIds.collectAsStateWithLifecycle()
    val step = saveStep(vm, id)
    // Floating card above the navigation bar.
    Surface(
        color = PlayerCard,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(p.currentArtwork, Modifier.size(44.dp), corner = 8)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        p.error ?: when (step) {
                            is SaveStep.Saving -> "${p.currentArtist} · saving for offline"
                            SaveStep.Finding -> "Finding song…"
                            else -> p.currentArtist
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = if (p.error != null) MaterialTheme.colorScheme.error else Color(0xFFC9C0BC),
                    )
                }
                val isLiked = id in liked
                IconButton(onClick = { vm.toggleLikeById(id) }) {
                    Icon(if (isLiked) Lucide.HeartFilled else Lucide.Heart,
                        contentDescription = if (isLiked) "Unlike" else "Like", tint = if (isLiked) Accent else Color.White)
                }
                if (step == SaveStep.Finding) CircularProgressIndicator(Modifier.size(28.dp).padding(4.dp), strokeWidth = 2.dp)
                else IconButton(onClick = p::togglePlay) {
                    Icon(if (p.isPlaying) Lucide.PauseFilled else Lucide.PlayFilled,
                        contentDescription = if (p.isPlaying) "Pause" else "Play")
                }
            }
            LinearProgressIndicator(
                progress = { if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.14f), drawStopIndicator = {},
            )
        }
    }
}

// ---------------------------------------------------------------- first-play steps

sealed interface SaveStep {
    data object Finding : SaveStep
    data class Saving(val fraction: Float) : SaveStep
    data object Ready : SaveStep
}

/** Where the current song is: being found, being saved (buffered = written to the encrypted cache), or offline. */
@Composable
fun saveStep(vm: AppViewModel, id: Long): SaveStep {
    val p = vm.player
    val downloaded by vm.downloadedIds.collectAsStateWithLifecycle()
    val progress by vm.downloadProgress.collectAsStateWithLifecycle()
    return when {
        id in downloaded -> SaveStep.Ready
        p.isBuffering && p.positionMs == 0L && p.bufferedMs == 0L -> SaveStep.Finding
        progress[id] != null -> SaveStep.Saving(progress[id] ?: 0f)
        p.durationMs > 0 && p.bufferedMs >= p.durationMs -> SaveStep.Ready
        p.durationMs > 0 -> SaveStep.Saving((p.bufferedMs.toFloat() / p.durationMs).coerceIn(0f, 1f))
        else -> SaveStep.Finding
    }
}

@Composable
private fun SaveSteps(step: SaveStep) {
    val dim = Color(0xFF8F949C)
    @Composable
    fun stepLabel(text: String, state: Int) { // 0 = upcoming, 1 = current, 2 = done
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state == 2) Icon(Lucide.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
            if (state == 2) Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall,
                fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal,
                color = when (state) { 1 -> Color(0xFFFFB39F); 2 -> Color(0xFFB8BCC4); else -> dim })
        }
    }
    @Composable
    fun RowScope.line() = Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.18f)))

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (step) {
            SaveStep.Finding -> { stepLabel("Finding song…", 1); line(); stepLabel("Saving", 0); line(); stepLabel("Offline", 0) }
            is SaveStep.Saving -> { stepLabel("Found", 2); line(); stepLabel("Saving · ${(step.fraction * 100).toInt()}%", 1); line(); stepLabel("Offline", 0) }
            SaveStep.Ready -> { stepLabel("Found", 2); line(); stepLabel("Saved", 2); line(); stepLabel("Ready offline", 1) }
        }
    }
}

// ---------------------------------------------------------------- full player

private val PlayerBg = Color(0xFF14171B)
private val SoftText = Color(0xFFB8BCC4)

@Composable
fun PlayerScreen(vm: AppViewModel, onClose: () -> Unit, openArtist: (Long) -> Unit) {
    val p = vm.player
    val id = p.currentId ?: return
    val liked by vm.likedIds.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedIds.collectAsStateWithLifecycle()
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var artistId by remember(id) { mutableStateOf(0L) }
    var lyrics by remember(id) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(id) {
        artistId = vm.songById(id)?.artistId ?: 0L
        lyrics = vm.lyrics(id)
    }
    val isLiked = id in liked

    Column(
        Modifier.fillMaxSize().background(PlayerBg).systemBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header: close · "Playing from" · queue + more
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (lyricsOpen) lyricsOpen = false else onClose() }) {
                Icon(Lucide.ChevronDown, contentDescription = if (lyricsOpen) "Close lyrics" else "Close player")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SoftText)
                Text(if (lyricsOpen) p.currentTitle else "Your queue", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (vm.pairedPc != null) {
                IconButton(onClick = { vm.playOnPc() }, enabled = !vm.syncing) {
                    if (vm.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Lucide.MonitorSmartphone, contentDescription = "Play on ${vm.pairedPc}", tint = Accent)
                }
            }
            IconButton(onClick = { showQueue = true }) { Icon(Lucide.ListMusic, contentDescription = "Queue") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Lucide.EllipsisVertical, contentDescription = "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (p.sleepTimerEndsAt != null) "Sleep timer (on)" else "Sleep timer") },
                        leadingIcon = { Icon(Lucide.Moon, null, tint = if (p.sleepTimerEndsAt != null) Accent else LocalContentColor.current) },
                        onClick = { menuOpen = false; showSleep = true },
                    )
                    if (id !in downloaded) DropdownMenuItem(
                        text = { Text("Download for offline") }, leadingIcon = { Icon(Lucide.Download, null) },
                        onClick = { menuOpen = false; vm.downloadById(id) },
                    )
                    if (artistId != 0L) DropdownMenuItem(
                        text = { Text("Go to artist") }, leadingIcon = { Icon(Lucide.User, null) },
                        onClick = { menuOpen = false; onClose(); openArtist(artistId) },
                    )
                }
            }
        }

        if (lyricsOpen) {
            Box(Modifier.weight(1f).fillMaxWidth()) { LyricsPanel(vm, lyrics) }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Cover(p.currentArtwork, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true), corner = 16)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.currentTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.currentArtist, color = Color(0xFFC4C8CF), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = artistId != 0L) { onClose(); openArtist(artistId) })
                }
                IconButton(
                    onClick = { vm.toggleLikeById(id) },
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
                ) {
                    Icon(if (isLiked) Lucide.HeartFilled else Lucide.Heart, contentDescription = if (isLiked) "Unlike" else "Like",
                        tint = if (isLiked) Accent else Color.White)
                }
            }
            SaveSteps(saveStep(vm, id))
        }

        p.error?.let { err ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = p::retry) { Text("Retry") }
            }
        }

        SeekBar(vm)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = p::toggleShuffle, modifier = Modifier.size(48.dp)) {
                Icon(Lucide.Shuffle, contentDescription = if (p.shuffle) "Shuffle on" else "Shuffle off",
                    tint = if (p.shuffle) Accent else Color(0xFFC4C8CF))
            }
            IconButton(onClick = { p.previous() }, modifier = Modifier.size(56.dp)) {
                Icon(Lucide.SkipBackFilled, contentDescription = "Previous", Modifier.size(30.dp))
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFF2F2F4)).clickable(onClick = p::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                if (p.isBuffering && !p.isPlaying) CircularProgressIndicator(Modifier.size(30.dp), color = PlayerBg, strokeWidth = 3.dp)
                else Icon(if (p.isPlaying) Lucide.PauseFilled else Lucide.PlayFilled,
                    contentDescription = if (p.isPlaying) "Pause" else "Play", tint = PlayerBg, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { p.next() }, modifier = Modifier.size(56.dp)) {
                Icon(Lucide.SkipForwardFilled, contentDescription = "Next", Modifier.size(30.dp))
            }
            IconButton(onClick = p::cycleRepeat, modifier = Modifier.size(48.dp)) {
                Icon(if (p.repeatMode == Player.REPEAT_MODE_ONE) Lucide.Repeat1 else Lucide.Repeat,
                    contentDescription = when (p.repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                    tint = if (p.repeatMode != Player.REPEAT_MODE_OFF) Accent else Color(0xFFC4C8CF))
            }
        }

        if (!lyricsOpen) LyricsPreview(lyrics, p.positionMs) { lyricsOpen = true }
    }

    if (showQueue) QueueSheet(vm) { showQueue = false }
    if (showSleep) SleepTimerDialog(vm) { showSleep = false }
}

/** Current + next line, tap to open the full synced lyrics. */
@Composable
private fun LyricsPreview(lyrics: Lyrics?, positionMs: Long, onOpen: () -> Unit) {
    val lines = lyrics?.lines.orEmpty()
    val active = if (lyrics?.synced == true) lines.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0) else 0
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PlayerCard).clickable(enabled = lines.isNotEmpty(), onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.MicVocal, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(" Lyrics", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (lines.isNotEmpty()) Text("Tap to expand", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C0BC))
        }
        when {
            lyrics == null -> Text("Loading…", color = Color(0xFFC9C0BC))
            lines.isEmpty() -> Text("No lyrics for this song", color = Color(0xFFC9C0BC))
            else -> {
                Text(lines[active].text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF9C80), maxLines = 1, overflow = TextOverflow.Ellipsis)
                lines.getOrNull(active + 1)?.let {
                    Text(it.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = Color(0xFFA99F9B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SeekBar(vm: AppViewModel) {
    val p = vm.player
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = p.durationMs.coerceAtLeast(1L).toFloat()
    val value = (dragging ?: p.positionMs.toFloat()).coerceIn(0f, duration)
    Column {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { p.seekTo(it.toLong()) }; dragging = null },
            valueRange = 0f..duration,
            enabled = p.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.14f),
            ),
        )
        Row {
            Text(formatDuration(value.toLong()), style = MaterialTheme.typography.bodySmall, color = SoftText)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(p.durationMs), style = MaterialTheme.typography.bodySmall, color = SoftText)
        }
    }
}

@Composable
private fun LyricsPanel(vm: AppViewModel, l: Lyrics?) {
    when {
        l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        l.lines.isEmpty() -> Text("No lyrics found for this song.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val position = vm.player.positionMs
            val active = if (l.synced) l.lines.indexOfLast { it.startMs <= position } else -1
            val listState = rememberLazyListState()
            LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (!l.synced) item {
                    Text("Timestamps not available for this song", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                }
                itemsIndexed(l.lines) { i, line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            i == active -> Accent
                            !l.synced -> Color.White.copy(alpha = 0.85f)
                            i < active -> Color.White.copy(alpha = 0.45f)
                            else -> Color.White.copy(alpha = 0.32f)
                        },
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = l.synced) { vm.player.seekTo(line.startMs) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val p = vm.player
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            itemsIndexed(p.queue) { i, item ->
                val isCurrent = i == p.queueIndex
                ListItem(
                    headlineContent = {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                    },
                    supportingContent = { Text(item.artist, maxLines = 1) },
                    leadingContent = { Cover(item.artwork, Modifier.size(44.dp), corner = 4) },
                    trailingContent = {
                        if (!isCurrent) IconButton(onClick = { p.removeFromQueue(i) }) {
                            Icon(Lucide.X, contentDescription = "Remove from queue")
                        }
                    },
                    modifier = Modifier.clickable { p.skipToQueueIndex(i) },
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

@Composable
private fun SleepTimerDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val p = vm.player
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                p.sleepTimerEndsAt?.let {
                    val mins = ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                    Text("Pausing in about $mins min", color = Accent, modifier = Modifier.padding(bottom = 8.dp))
                }
                listOf(5, 15, 30, 45, 60).forEach { m ->
                    Text("$m minutes", Modifier.fillMaxWidth().clickable { p.setSleepTimer(m); onDismiss() }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {
            if (p.sleepTimerEndsAt != null) TextButton(onClick = { p.setSleepTimer(null); onDismiss() }) { Text("Turn off") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
