@file:OptIn(ExperimentalMaterial3Api::class)

package com.musiclify.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musiclify.BuildConfig
import com.musiclify.data.Artist
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, editArtists: () -> Unit, linkDevice: () -> Unit) {
    val context = LocalContext.current
    val favorites by vm.favoriteArtists.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var storage by remember { mutableLongStateOf(vm.storageBytes()) }
    var cookieMessage by remember { mutableStateOf<String?>(null) }

    val pickCookies = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        cookieMessage = if (text == null) "Couldn't read that file." else vm.importCookies(text) ?: "Cookies imported."
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "Back") } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Group("Profile")
            Setting("Name", vm.userName) { renaming = true }
            Setting("Favourite artists", favorites.joinToString { it.name }.ifEmpty { "None" }, onClick = editArtists)

            Group("Storage")
            Setting("Downloaded songs", "${Formatter.formatShortFileSize(context, storage)} used · encrypted, only playable in Musiclify")
            Setting("Remove all downloads", "Songs will download again when you play them") { confirmClear = true }
            Setting("Clear listening history", "Removes Recently played") { vm.clearHistory() }

            Group("Devices")
            if (vm.pairedPc != null) {
                ListItem(
                colors = ClearListItem,
                    headlineContent = { Text("Synced with ${vm.pairedPc}") },
                    supportingContent = {
                        Text(
                            (if (vm.lastSync > 0) "Last synced ${android.text.format.DateUtils.getRelativeTimeSpanString(vm.lastSync)}" else "Not synced yet") +
                                ". Syncs automatically when you open Musiclify on the same Wi-Fi as your PC."
                        )
                    },
                    leadingContent = { Icon(Lucide.MonitorSmartphone, contentDescription = null, tint = Accent) },
                )
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.syncNow() }, enabled = !vm.syncing) {
                        if (vm.syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Lucide.RefreshCcw, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (vm.syncing) "Syncing…" else "Sync now")
                    }
                    TextButton(onClick = { vm.unpair() }) { Text("Unpair") }
                }
            }
            Setting(if (vm.pairedPc == null) "Link a device" else "Link again",
                if (vm.pairedPc == null) "Scan your PC's QR code once. After that, sync with one tap, no more scanning."
                else "Scan a QR code to pair with a different PC", onClick = linkDevice)

            Group("Speed")
            ListItem(
                colors = ClearListItem,
                headlineContent = { Text("Shared song matching") },
                supportingContent = {
                    Text("Songs start faster by reusing which YouTube video matches each song, found by other " +
                        "Musiclify users. Only song and video IDs are shared, never audio or anything about you.")
                },
                trailingContent = { Switch(checked = vm.shareMatches, onCheckedChange = vm::updateShareMatches) },
                modifier = Modifier.clickable { vm.updateShareMatches(!vm.shareMatches) },
            )

            Group("Look")
            ListItem(
                colors = ClearListItem,
                headlineContent = { Text("Ambient light") },
                supportingContent = { Text("A soft glow in the colours of the song's cover that pulses with the beat.") },
                trailingContent = { Switch(checked = vm.ambientLight, onCheckedChange = vm::updateAmbientLight) },
                modifier = Modifier.clickable { vm.updateAmbientLight(!vm.ambientLight) },
            )

            Group("Lyrics")
            ListItem(
                colors = ClearListItem,
                headlineContent = { Text("Lyrics on lock screen") },
                supportingContent = {
                    Text("Show the line being sung on the lock screen and in the notification, in place of the " +
                        "artist name. Car and Bluetooth displays may show it too.")
                },
                trailingContent = { Switch(checked = vm.lockScreenLyrics, onCheckedChange = vm::updateLockScreenLyrics) },
                modifier = Modifier.clickable { vm.updateLockScreenLyrics(!vm.lockScreenLyrics) },
            )

            Group("Updates")
            ListItem(
                colors = ClearListItem,
                headlineContent = { Text("Check for updates") },
                supportingContent = {
                    Text("Once a day, look for a newer version on GitHub Releases. Only the public release list " +
                        "is read; nothing about you or this phone is sent.")
                },
                trailingContent = { Switch(checked = vm.checkUpdates, onCheckedChange = vm::updateCheckUpdates) },
                modifier = Modifier.clickable { vm.updateCheckUpdates(!vm.checkUpdates) },
            )

            Group("YouTube")
            Setting(
                "YouTube cookies",
                if (vm.hasCookies) "Imported. Remove them if you no longer want to use them."
                else "Optional. Only needed if YouTube blocks this phone. Import a cookies.txt exported from youtube.com.",
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickCookies.launch(arrayOf("text/*", "application/octet-stream")) }) {
                    Text(if (vm.hasCookies) "Replace" else "Import cookies.txt")
                }
                if (vm.hasCookies) TextButton(onClick = { vm.removeCookies(); cookieMessage = "Cookies removed." }) { Text("Remove") }
            }
            cookieMessage?.let {
                Text(it, color = Accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 4.dp))
            }
            Text(
                "Cookies are encrypted on this phone and only sent to youtube.com. Use a spare Google account: " +
                    "YouTube may restrict accounts used for downloading.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 8.dp),
            )

            Group("About")
            Setting("Musiclify ${BuildConfig.VERSION_NAME}", "Open source (GPL-3.0). Song info from Deezer, lyrics from LRCLIB, audio via NewPipeExtractor.")
            Setting("Personal use only", "Music is streamed from YouTube for your own listening. Respect artists and copyright.")
        }
    }

    if (renaming) NameDialog("Your name", "Save", vm.userName, { renaming = false }) { vm.rename(it); renaming = false }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Remove all downloads?") },
        text = { Text("This frees ${Formatter.formatShortFileSize(context, storage)}.") },
        confirmButton = {
            TextButton(onClick = { vm.clearDownloads { storage = vm.storageBytes() }; confirmClear = false }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
fun EditArtistsScreen(vm: AppViewModel, onDone: () -> Unit) {
    val favorites by vm.favoriteArtists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ArtistPicker(
        title = "Favourite artists",
        subtitle = "Your home screen is built from these.",
        initial = favorites.map { Artist(it.id, it.name, it.picture) },
        minimum = 1,
        confirmLabel = "Save",
        vm = vm,
        onBack = onDone,
    ) { picked ->
        scope.launch {
            vm.setFavoriteArtists(picked)
            vm.loadHome()
            onDone()
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(title, color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Setting(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    ListItem(
                colors = ClearListItem,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
