@file:OptIn(ExperimentalMaterial3Api::class)

package com.musiclify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.musiclify.MusiclifyApp
import com.musiclify.data.ImportSummary
import com.musiclify.engine.LinkTarget
import kotlinx.coroutines.launch

private sealed interface LinkState {
    data object Idle : LinkState
    data class Scanned(val target: LinkTarget) : LinkState
    data class Working(val message: String) : LinkState
    data class Done(val message: String, val offlineIds: List<Long> = emptyList()) : LinkState
    data class Failed(val message: String) : LinkState
}

/**
 * Scan the QR code shown by Musiclify on a PC, then send this phone's library to it or copy
 * the PC's library here. [receiveOnly] is used during onboarding (nothing to send yet).
 */
@Composable
fun LinkScreen(vm: AppViewModel, receiveOnly: Boolean, onBack: () -> Unit, onImported: (ImportSummary) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LinkState>(LinkState.Idle) }
    val link = MusiclifyApp.graph.link

    // ZXing asks for the camera permission itself the first time it opens.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult // cancelled
        state = LinkTarget.parse(raw)?.let { LinkState.Scanned(it) }
            ?: LinkState.Failed("That isn't a Musiclify link code. Open Settings → Link a device on your PC and scan the code shown there.")
    }

    fun scan() {
        try {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR code shown on your PC")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        } catch (e: Exception) {
            state = LinkState.Failed("Couldn't open the scanner: ${e.message}")
        }
    }

    fun send(target: LinkTarget) = scope.launch {
        state = LinkState.Working("Sending your library…")
        state = try {
            runCatching { link.pair(target) }.onSuccess { vm.refreshPairing() } // enables the Sync button
            link.send(target)
            LinkState.Done("Your whole library is on the PC now: favourite artists, likes, playlists, history and lyrics. " +
                "The PC can download your offline songs too.")
        } catch (e: Exception) {
            LinkState.Failed(friendly(e))
        }
    }

    fun receive(target: LinkTarget) = scope.launch {
        state = LinkState.Working("Getting the library…")
        state = try {
            runCatching { link.pair(target) }.onSuccess { vm.refreshPairing() } // enables the Sync button
            val s = link.receive(target)
            vm.afterImport()
            onImported(s)
            LinkState.Done(
                "Imported${if (s.name.isNotBlank()) " ${s.name}'s library" else ""}: ${s.artists} favourite artists, " +
                    "${s.liked} liked songs, ${s.playlists} playlists, ${s.history} plays of history and lyrics for ${s.lyrics} songs.",
                s.offlineIds,
            )
        } catch (e: Exception) {
            LinkState.Failed(friendly(e))
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopAppBar(
            title = { Text("Link a device") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "Back") } },
        )
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                LinkState.Idle, is LinkState.Failed -> {
                    Icon(Lucide.ScanQrCode, contentDescription = null, tint = Accent, modifier = Modifier.size(72.dp))
                    Text("Move your library between this phone and Musiclify on your PC.",
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text("On the PC, open Settings → Link a device (or choose \"Import from my phone\" when setting it up), " +
                        "then scan the code it shows. Both devices must be on the same Wi-Fi.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (s is LinkState.Failed) Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = { scan() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Lucide.ScanQrCode, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR code")
                    }
                    Text("Data goes straight between your devices, encrypted with a one-time key. Nothing is uploaded anywhere. " +
                        "Downloaded songs aren't copied; they download again on the other device.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }

                is LinkState.Scanned -> {
                    val liked by vm.liked.collectAsStateWithLifecycle()
                    val playlists by vm.playlists.collectAsStateWithLifecycle()
                    val artists by vm.favoriteArtists.collectAsStateWithLifecycle()
                    var sending by remember { mutableStateOf(!receiveOnly) }

                    // Phone ··· PC
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF1B1B20)).padding(vertical = 22.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DeviceBadge(Lucide.Smartphone)
                            Text(" ·  ·  ·  · ", color = Accent, fontWeight = FontWeight.Bold)
                            DeviceBadge(Lucide.Monitor)
                        }
                        Text("Connected to your PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${s.target.host} · same Wi-Fi · one-time key", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Text("WHAT DO YOU WANT TO DO?", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    if (!receiveOnly) {
                        LinkChoice(
                            selected = sending, icon = Lucide.ArrowUp,
                            title = "Send my library to the PC",
                            subtitle = "${liked.size} liked · ${playlists.size} playlists · ${artists.size} artists",
                        ) { sending = true }
                    }
                    LinkChoice(
                        selected = !sending, icon = Lucide.ArrowDown,
                        title = "Get the library from the PC",
                        subtitle = "Merges with this phone, deletes nothing",
                    ) { sending = false }

                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF151518)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row { Icon(Lucide.Check, null, tint = Accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(10.dp))
                            Text("Moves: name, favourite artists, likes, playlists, history", style = MaterialTheme.typography.bodySmall) }
                        Row { Icon(Lucide.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(10.dp))
                            Text("Songs stay locked to each device and download again there", style = MaterialTheme.typography.bodySmall) }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (sending) send(s.target) else receive(s.target) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(if (sending) "Send library" else "Get library", fontWeight = FontWeight.SemiBold)
                    }
                }

                is LinkState.Working -> {
                    CircularProgressIndicator()
                    Text(s.message)
                }

                is LinkState.Done -> {
                    Icon(Lucide.CircleCheck, contentDescription = null, tint = Accent, modifier = Modifier.size(72.dp))
                    Text(s.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (s.offlineIds.isNotEmpty()) {
                        var queued by remember { mutableStateOf(false) }
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1B20)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("${s.offlineIds.size} songs were saved offline on your PC.", fontWeight = FontWeight.SemiBold)
                            if (queued) {
                                Text("Downloading them one by one in the background. Keep using Musiclify.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                OutlinedButton(onClick = { vm.downloadMany(s.offlineIds); queued = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Download them on this phone")
                                }
                            }
                        }
                    }
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun DeviceBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun LinkChoice(
    selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else Color(0xFF2A2A31), RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF1F1A18) else Color(0xFF1B1B20))
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Accent else Color(0xFF24242B)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = if (selected) Color(0xFF1A0D08) else Accent) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun friendly(e: Exception): String = when {
    e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
        "Couldn't reach the PC. Make sure both devices are on the same Wi-Fi, the code is still open on the PC, " +
            "and Windows Firewall allowed Musiclify on private networks."
    e is javax.crypto.AEADBadTagException -> "The code didn't match. Scan the code again on the PC."
    else -> e.message ?: "Something went wrong."
}
