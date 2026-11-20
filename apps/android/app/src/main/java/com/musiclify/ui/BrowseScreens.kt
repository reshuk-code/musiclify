@file:OptIn(ExperimentalMaterial3Api::class)

package com.musiclify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musiclify.Routes
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import com.musiclify.data.AppUpdate
import com.musiclify.data.Artist
import com.musiclify.data.Song
import java.util.Calendar

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(vm: AppViewModel, actions: SongActions, openSettings: () -> Unit, open: (String) -> Unit) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedIds.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    val mix = vm.mix
    // Coming back to home (or to the app) rebuilds a stale feed, so the mix doesn't stay the same forever.
    LifecycleResumeEffect(Unit) { vm.refreshHomeIfStale(); onPauseOrDispose { } }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) {
                    Text(vm.userName.take(1).uppercase().ifEmpty { "?" }, color = Color(0xFFFFB39F), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(greeting(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (vm.pairedPc != null) {
                    IconButton(onClick = { vm.syncNow(handoff = true) }, enabled = !vm.syncing) {
                        if (vm.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Lucide.MonitorSmartphone, contentDescription = if (vm.player.isPlaying) "Sync and play on ${vm.pairedPc}" else "Sync with ${vm.pairedPc}")
                    }
                }
                IconButton(onClick = { vm.loadHome() }) { Icon(Lucide.RefreshCw, contentDescription = "Refresh") }
                IconButton(onClick = openSettings) { Icon(Lucide.Settings, contentDescription = "Settings") }
            }
        }
        vm.availableUpdate?.let { update ->
            item(key = "update") { UpdateBanner(update, onDismiss = vm::dismissUpdate) }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "offline" to "Offline", "playlists" to "Playlists").forEach { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = { filter = key },
                        label = { Text(label, fontWeight = FontWeight.SemiBold) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent, selectedLabelColor = Color(0xFF1A0D08),
                        ),
                    )
                }
            }
        }

        when (filter) {
            "offline" -> {
                if (downloads.isEmpty()) item { CenteredMessage("Songs you play are saved here and play without internet.") }
                itemsIndexed(downloads, key = { _, s -> "o${s.id}" }) { i, s -> SongRow(s, actions) { vm.play(downloads, i) } }
            }
            "playlists" -> {
                if (playlists.isEmpty()) item { CenteredMessage("No playlists yet. Add songs from any song's ⋮ menu.") }
                items(playlists, key = { "p${it.playlist.id}" }) { p ->
                    ListItem(
                        headlineContent = { Text(p.playlist.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("${p.songCount} songs") },
                        leadingContent = { Cover(p.cover, Modifier.size(56.dp), corner = 8) },
                        modifier = Modifier.clickable { open(Routes.playlist(p.playlist.id)) },
                    )
                }
            }
            else -> {
                item {
                    QuickTiles(
                        buildList {
                            add(QuickTile("Liked Songs", null, isLiked = true) { open(Routes.LIKED) })
                            if (mix.isNotEmpty()) add(QuickTile(MIX_TITLE, mix.first().cover) { open(Routes.MIX) })
                            playlists.take(2).forEach { p -> add(QuickTile(p.playlist.name, p.cover) { open(Routes.playlist(p.playlist.id)) }) }
                            recent.forEachIndexed { i, s -> if (size < 6) add(QuickTile(s.title, s.cover) { vm.play(recent, i) }) }
                        }
                    )
                }
                if (mix.isNotEmpty()) item {
                    MixCard(mix, offline = mix.count { it.id in downloaded }, playing = vm.isPlayingList(mix) && vm.player.isPlaying,
                        onOpen = { open(Routes.MIX) }, onPlay = { vm.playOrToggle(mix) })
                }
                when {
                    vm.homeLoading && vm.home.isEmpty() -> item {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    vm.homeError != null && vm.home.isEmpty() -> item { CenteredMessage(vm.homeError!!, "Retry") { vm.loadHome() } }
                }
                vm.home.filterNot { it.title == MIX_TITLE }.forEach { section ->
                    when (section) {
                        is HomeSection.Songs -> {
                            item(key = "h-${section.title}") {
                                SectionHeader(section.title, section.subtitle) {
                                    IconButton(onClick = { vm.play(section.songs) }) {
                                        Icon(Lucide.CirclePlay, contentDescription = "Play ${section.title}", tint = Accent,
                                            modifier = Modifier.size(36.dp))
                                    }
                                }
                            }
                            item(key = "r-${section.title}") { SongCardRow(section.songs, vm) }
                        }
                        is HomeSection.Artists -> {
                            item(key = "h-${section.title}") { SectionHeader(section.title) }
                            item(key = "r-${section.title}") { ArtistRow(section.artists) { actions.openArtist(it.id) } }
                        }
                    }
                }
            }
        }
    }
}

/** "A new version is available" card, linking straight to the APK on GitHub Releases. */
@Composable
private fun UpdateBanner(update: AppUpdate, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(AccentSoft).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Musiclify ${update.version} is available", fontWeight = FontWeight.SemiBold)
            Text("Install it over this version. Your library stays.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDismiss) { Text("Later") }
        Button(onClick = { uri.openUri(update.apkUrl ?: update.pageUrl) }) { Text("Update") }
    }
}

private class QuickTile(val label: String, val cover: String?, val isLiked: Boolean = false, val onClick: () -> Unit)

@Composable
private fun QuickTiles(tiles: List<QuickTile>) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { t ->
                    Row(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = t.onClick),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (t.isLiked) {
                            Box(Modifier.size(56.dp).background(Accent), contentAlignment = Alignment.Center) {
                                Icon(Lucide.HeartFilled, contentDescription = null, tint = Color(0xFF1A0D08))
                            }
                        } else if (t.cover.isNullOrEmpty()) {
                            Box(Modifier.size(56.dp).background(AccentSoft), contentAlignment = Alignment.Center) {
                                Icon(Lucide.ListMusic, contentDescription = null, tint = Accent)
                            }
                        } else {
                            Cover(t.cover, Modifier.size(56.dp), corner = 0)
                        }
                        Text(t.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MixCard(mix: List<Song>, offline: Int, playing: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1B1B20)).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.size(88.dp).clip(RoundedCornerShape(10.dp))) {
            mix.take(4).chunked(2).forEach { row ->
                Row(Modifier.weight(1f)) { row.forEach { s -> Cover(s.cover, Modifier.weight(1f).fillMaxHeight(), corner = 0) } }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text("MADE FOR YOU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Accent)
            Text("Your mix", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${mix.size} songs" + if (offline > 0) " · $offline offline" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
            if (playing) Icon(Lucide.PauseFilled, contentDescription = "Pause your mix")
            else Icon(Lucide.PlayFilled, contentDescription = "Play your mix")
        }
    }
}

@Composable
private fun SongCardRow(songs: List<Song>, vm: AppViewModel) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongCard(song, isCurrent = vm.player.currentId == song.id) { vm.play(songs, i) }
        }
    }
}

@Composable
fun ArtistRow(artists: List<Artist>, onClick: (Artist) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(artists, key = { it.id }) { a -> ArtistAvatar(a) { onClick(a) } }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

// ---------------------------------------------------------------- search

@Composable
fun SearchScreen(vm: AppViewModel, actions: SongActions) {
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (vm.query.isEmpty()) focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("Songs or artists") },
            leadingIcon = { Icon(Lucide.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onQueryChange("") }) {
                    Icon(Lucide.X, contentDescription = "Clear")
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focusRequester),
        )
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        val results = vm.results
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                vm.searchError != null -> item { CenteredMessage(vm.searchError!!) }
                vm.query.isBlank() -> item { CenteredMessage("Find any song or artist.") }
                !vm.searching && results.songs.isEmpty() && results.artists.isEmpty() ->
                    item { CenteredMessage("No results for \"${vm.query}\".") }
            }
            if (results.artists.isNotEmpty()) {
                item { SectionHeader("Artists") }
                item { ArtistRow(results.artists) { actions.openArtist(it.id) } }
            }
            if (results.songs.isNotEmpty()) {
                item { SectionHeader("Songs") }
                itemsIndexed(results.songs, key = { _, s -> s.id }) { i, song ->
                    SongRow(song, actions) { focus.clearFocus(); vm.play(results.songs, i) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- library

@Composable
fun LibraryScreen(vm: AppViewModel, open: (String) -> Unit) {
    val liked by vm.liked.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Your Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }) { Icon(Lucide.Plus, contentDescription = "New playlist") }
            }
        }
        item { LibraryEntry("Liked Songs", "${liked.size} songs", Lucide.HeartFilled) { open(Routes.LIKED) } }
        item { LibraryEntry("Downloads", "${downloads.size} songs · play offline", Lucide.Download) { open(Routes.DOWNLOADS) } }
        item { LibraryEntry("Recently played", "${recent.size} songs", Lucide.History) { open(Routes.HISTORY) } }
        item { SectionHeader("Playlists") }
        if (playlists.isEmpty()) item { CenteredMessage("No playlists yet.", "Create playlist") { creating = true } }
        items(playlists, key = { it.playlist.id }) { p ->
            ListItem(
                headlineContent = { Text(p.playlist.name, fontWeight = FontWeight.Medium) },
                supportingContent = { Text("${p.songCount} songs") },
                leadingContent = { Cover(p.cover, Modifier.size(56.dp), corner = 6) },
                modifier = Modifier.clickable { open(Routes.playlist(p.playlist.id)) },
            )
        }
    }
    if (creating) NameDialog("New playlist", "Create", onDismiss = { creating = false }) { vm.createPlaylist(it); creating = false }
}

@Composable
private fun LibraryEntry(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.matchParentSize()) {}
                Icon(icon, contentDescription = null, tint = Accent)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Generic list page: liked songs, downloads, history, playlists. */
@Composable
fun SongListScreen(
    title: String,
    songs: List<Song>,
    vm: AppViewModel,
    actions: SongActions,
    onBack: () -> Unit,
    emptyText: String,
    menu: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "Back") } },
            actions = { menu?.invoke() },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("${songs.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            item { PlayShuffleButtons(songs, vm) }
            if (songs.isEmpty()) item { CenteredMessage(emptyText) }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song -> SongRow(song, actions) { vm.play(songs, i) } }
        }
    }
}

/** The songs in "Your mix", with Play/Pause and Shuffle like any list. */
@Composable
fun MixScreen(vm: AppViewModel, actions: SongActions, onBack: () -> Unit) {
    SongListScreen(MIX_TITLE, vm.mix, vm, actions, onBack,
        emptyText = "Follow a few artists and your mix shows up here.") {
        IconButton(onClick = { vm.loadHome() }, enabled = !vm.homeLoading) {
            Icon(Lucide.RefreshCw, contentDescription = "New mix")
        }
    }
}

@Composable
fun PlaylistScreen(id: Long, vm: AppViewModel, openArtist: (Long) -> Unit, onBack: () -> Unit) {
    val playlist by vm.playlist(id).collectAsStateWithLifecycle(null)
    val songs by vm.playlistSongs(id).collectAsStateWithLifecycle(emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val actions = SongActions(vm, openArtist, removeFromPlaylist = { vm.removeFromPlaylist(id, it.id) })

    SongListScreen(playlist?.name ?: "Playlist", songs, vm, actions, onBack,
        emptyText = "Add songs with the ⋮ menu on any song.") {
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Lucide.EllipsisVertical, contentDescription = "Playlist options") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { renaming = true; menuOpen = false })
                DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { vm.deletePlaylist(id); menuOpen = false; onBack() })
            }
        }
    }
    if (renaming) NameDialog("Rename playlist", "Save", playlist?.name.orEmpty(), { renaming = false }) {
        vm.renamePlaylist(id, it); renaming = false
    }
}

// ---------------------------------------------------------------- artist

@Composable
fun ArtistScreen(id: Long, vm: AppViewModel, actions: SongActions, onBack: () -> Unit) {
    var page by remember(id) { mutableStateOf<Triple<Artist, List<Song>, List<Artist>>?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val favorites by vm.favoriteArtists.collectAsStateWithLifecycle()

    LaunchedEffect(id, reload) {
        error = null
        try {
            page = vm.artistPage(id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = "Couldn't load this artist."
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(page?.first?.name.orEmpty()) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "Back") } },
        )
        val p = page
        when {
            error != null -> CenteredMessage(error!!, "Retry") { reload++ }
            p == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val (artist, top, related) = p
                val following = favorites.any { it.id == artist.id }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            coil3.compose.AsyncImage(model = artist.picture, contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(180.dp).clip(CircleShape))
                            Spacer(Modifier.height(12.dp))
                            Text(artist.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            if (following) OutlinedButton(onClick = { vm.toggleFollow(artist) }) { Text("Following") }
                            else Button(onClick = { vm.toggleFollow(artist) }) { Text("Follow") }
                        }
                    }
                    item { PlayShuffleButtons(top, vm) }
                    item { SectionHeader("Popular") }
                    itemsIndexed(top, key = { _, s -> s.id }) { i, song -> SongRow(song, actions) { vm.play(top, i) } }
                    if (related.isNotEmpty()) {
                        item { SectionHeader("Fans also like") }
                        item { ArtistRow(related) { actions.openArtist(it.id) } }
                    }
                }
            }
        }
    }
}
