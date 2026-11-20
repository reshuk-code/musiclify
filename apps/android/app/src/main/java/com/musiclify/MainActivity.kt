package com.musiclify

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.musiclify.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusiclifyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: AppViewModel = viewModel()
                    if (vm.onboarded) MainScaffold(vm) else OnboardingScreen(vm)
                }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Lucide.House),
    Tab(Routes.SEARCH, "Search", Lucide.Search),
    Tab(Routes.LIBRARY, "Library", Lucide.Library),
)

@Composable
private fun MainScaffold(vm: AppViewModel) {
    val nav = rememberNavController()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = playerOpen) { playerOpen = false }

    // Android 13+: notification permission for the playback controls notification.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Sync results / errors as a short toast.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vm.syncMessage) {
        vm.syncMessage?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.syncMessage = null }
    }

    val openArtist: (Long) -> Unit = { nav.navigate(Routes.artist(it)) }
    val actions = SongActions(vm, openArtist)
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Box(Modifier.fillMaxSize()) {
        AmbientBackground(vm, strength = 0.45f, modifier = Modifier.fillMaxSize())
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent, // let the ambient light through
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Column {
                    MiniPlayer(vm) { playerOpen = true }
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                composable(Routes.HOME) {
                    HomeScreen(vm, actions, openSettings = { nav.navigate(Routes.SETTINGS) }, open = { nav.navigate(it) })
                }
                composable(Routes.SEARCH) { SearchScreen(vm, actions) }
                composable(Routes.LIBRARY) { LibraryScreen(vm) { nav.navigate(it) } }
                composable(Routes.LIKED) {
                    val songs by vm.liked.collectAsStateWithLifecycle()
                    SongListScreen("Liked Songs", songs, vm, actions, nav::popBackStack, "Tap ♡ on any song to save it here.")
                }
                composable(Routes.DOWNLOADS) {
                    val songs by vm.downloads.collectAsStateWithLifecycle()
                    SongListScreen("Downloads", songs, vm, actions, nav::popBackStack,
                        "Songs you play or download are kept here, encrypted, and play offline.")
                }
                composable(Routes.MIX) { MixScreen(vm, actions, nav::popBackStack) }
                composable(Routes.HISTORY) {
                    val songs by vm.recent.collectAsStateWithLifecycle()
                    SongListScreen("Recently played", songs, vm, actions, nav::popBackStack, "Nothing played yet.")
                }
                composable(Routes.PLAYLIST, listOf(navArgument("id") { type = NavType.LongType })) {
                    PlaylistScreen(it.arguments!!.getLong("id"), vm, openArtist, nav::popBackStack)
                }
                composable(Routes.ARTIST, listOf(navArgument("id") { type = NavType.LongType })) {
                    ArtistScreen(it.arguments!!.getLong("id"), vm, actions, nav::popBackStack)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm, nav::popBackStack, editArtists = { nav.navigate(Routes.EDIT_ARTISTS) },
                        linkDevice = { nav.navigate(Routes.LINK) })
                }
                composable(Routes.LINK) { LinkScreen(vm, receiveOnly = false, onBack = { nav.popBackStack() }) }
                composable(Routes.EDIT_ARTISTS) { EditArtistsScreen(vm) { nav.popBackStack() } }
            }
        }

        AnimatedVisibility(
            visible = playerOpen && vm.player.currentId != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            PlayerScreen(vm, onClose = { playerOpen = false }, openArtist = openArtist)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val LIKED = "liked"
    const val DOWNLOADS = "downloads"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val EDIT_ARTISTS = "edit-artists"
    const val LINK = "link"
    const val MIX = "mix"
    const val PLAYLIST = "playlist/{id}"
    const val ARTIST = "artist/{id}"

    fun playlist(id: Long) = "playlist/$id"
    fun artist(id: Long) = "artist/$id"
}
