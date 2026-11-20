package com.musiclify.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.musiclify.data.Artist
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }

    if (step == 0) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Musiclify", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Accent)
            Spacer(Modifier.height(8.dp))
            Text("Your music, your taste. Everything stays on this phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Text("What should we call you?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                singleLine = true,
                placeholder = { Text("Your name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { step = 1 }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Continue")
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) {
                Text("Already use Musiclify on your PC? Import from it")
            }
        }
    } else if (step == 2) {
        var imported by remember { mutableStateOf(false) }
        LinkScreen(vm, receiveOnly = true, onImported = { imported = true }, onBack = {
            when {
                !imported -> step = 0
                vm.userName.isBlank() -> step = 0 // still need a name
                vm.favoriteArtists.value.isEmpty() -> { name = vm.userName; step = 1 }
                else -> vm.finishOnboardingAfterImport()
            }
        })
    } else {
        ArtistPicker(
            title = "Pick artists you like",
            subtitle = "Choose at least 3. We'll build your home from them.",
            initial = emptyList(),
            minimum = 3,
            confirmLabel = "Done",
            vm = vm,
            onBack = { step = 0 },
        ) { picked -> vm.completeOnboarding(name, picked) }
    }
}

/** Grid of artists to pick from, with search. Used in onboarding and settings. */
@Composable
fun ArtistPicker(
    title: String,
    subtitle: String,
    initial: List<Artist>,
    minimum: Int,
    confirmLabel: String,
    vm: AppViewModel,
    onBack: (() -> Unit)? = null,
    onDone: (List<Artist>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateListOf<Artist>().apply { addAll(initial) } }

    LaunchedEffect(query, reloadKey) {
        if (query.isNotBlank()) delay(350)
        loading = true
        error = null
        try {
            artists = if (query.isBlank()) vm.popularArtists() else vm.searchArtists(query.trim())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = "Couldn't load artists. Check your internet connection."
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Lucide.Search, contentDescription = null) },
                placeholder = { Text("Search artists") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.weight(1f)) {
            when {
                error != null -> CenteredMessage(error!!, "Retry") { reloadKey++ }
                loading && artists.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Keep already-picked artists visible even when they're not in the current results.
                    val shown = (selected.filter { s -> artists.none { it.id == s.id } } + artists)
                    items(shown, key = { it.id }) { artist ->
                        val isSelected = selected.any { it.id == artist.id }
                        ArtistAvatar(artist, size = 96, selected = isSelected) {
                            if (isSelected) selected.removeAll { it.id == artist.id } else selected.add(artist)
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { TextButton(onClick = it) { Text("Back") } }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onDone(selected.toList()) }, enabled = selected.size >= minimum) {
                    Text(if (selected.size < minimum) "Pick ${minimum - selected.size} more" else "$confirmLabel (${selected.size})")
                }
            }
        }
    }
}
