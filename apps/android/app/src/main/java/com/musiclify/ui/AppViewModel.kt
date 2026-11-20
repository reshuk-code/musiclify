package com.musiclify.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musiclify.BuildConfig
import com.musiclify.MusiclifyApp
import com.musiclify.data.AppUpdate
import com.musiclify.data.Artist
import com.musiclify.data.FavoriteArtist
import com.musiclify.data.Lyrics
import com.musiclify.data.Playlist
import com.musiclify.data.PlaylistSong
import com.musiclify.data.Prefs
import com.musiclify.data.Song
import com.musiclify.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeSection {
    val title: String

    data class Songs(override val title: String, val subtitle: String? = null, val songs: List<Song>) : HomeSection
    data class Artists(override val title: String, val artists: List<Artist>) : HomeSection
}

const val MIX_TITLE = "Your mix"
/** How long a home feed (and its mix) is kept before it is rebuilt when home is shown again. */
private const val HOME_TTL_MS = 30 * 60 * 1000L

data class SearchResults(val songs: List<Song> = emptyList(), val artists: List<Artist> = emptyList())

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = MusiclifyApp.graph
    private val dao = graph.dao
    private val deezer = graph.deezer
    private val engine = graph.engine

    val player = PlayerConnection(app, viewModelScope)

    // ---------------- profile ----------------
    var onboarded by mutableStateOf(graph.prefs.onboarded)
        private set
    var userName by mutableStateOf(graph.prefs.userName)
        private set

    val favoriteArtists = dao.favoriteArtists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun completeOnboarding(name: String, artists: List<Artist>) = viewModelScope.launch {
        setFavoriteArtists(artists)
        graph.prefs.userName = name.trim()
        graph.prefs.onboarded = true
        userName = name.trim()
        onboarded = true
        loadHome()
    }

    fun rename(name: String) {
        graph.prefs.userName = name.trim()
        userName = name.trim()
    }

    suspend fun setFavoriteArtists(artists: List<Artist>) {
        val now = System.currentTimeMillis()
        dao.favoriteArtistsList().filter { old -> artists.none { it.id == old.id } }
            .forEach { dao.addTombstone("artist", it.id.toString(), now) } // unfollows sync to linked devices
        dao.clearFavoriteArtists()
        dao.addFavoriteArtists(artists.mapIndexed { i, a -> FavoriteArtist(a.id, a.name, a.picture, now + i) })
    }

    fun toggleFollow(artist: Artist) = viewModelScope.launch {
        if (favoriteArtists.value.any { it.id == artist.id }) {
            dao.removeFavoriteArtist(artist.id)
            dao.addTombstone("artist", artist.id.toString(), System.currentTimeMillis())
        } else dao.addFavoriteArtists(listOf(FavoriteArtist(artist.id, artist.name, artist.picture, System.currentTimeMillis())))
        loadHome() // follow/unfollow should show on home right away
    }

    /** Hand-picked well-known artists (shared/popular-artists.json); Deezer's genre charts are unreliable. */
    suspend fun popularArtists(): List<Artist> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val json = getApplication<Application>().assets.open("popular-artists.json").bufferedReader().use { it.readText() }
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it).let { a -> Artist(a.getLong("id"), a.getString("name"), a.getString("picture")) } }
    }

    // ---------------- sync with the paired PC ----------------
    var pairedPc by mutableStateOf(graph.prefs.pairing?.pcName)
        private set
    var lastSync by mutableStateOf(graph.prefs.lastSync)
        private set
    var syncing by mutableStateOf(false)
        private set
    /** One-shot message for the UI (shown as a toast), then cleared. */
    var syncMessage by mutableStateOf<String?>(null)

    fun refreshPairing() {
        pairedPc = graph.prefs.pairing?.pcName
        lastSync = graph.prefs.lastSync
    }

    /**
     * Two-way sync with the paired PC. [silent] = automatic sync on app start (no messages unless it worked).
     * [handoff] = also move playback to the PC if something is playing here (the Sync button does this).
     */
    fun syncNow(silent: Boolean = false, handoff: Boolean = false) {
        if (syncing || graph.prefs.pairing == null) return
        val wasPlaying = player.isPlaying
        viewModelScope.launch {
            syncing = true
            try {
                val s = graph.link.syncNow()
                afterImport()
                if (handoff && wasPlaying) {
                    handOffToPc()
                    return@launch
                }
                syncMessage = "Synced with $pairedPc" + if (s.liked + s.playlists + s.history > 0) ": ${s.liked} likes, ${s.playlists} playlists, ${s.history} plays" else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent || e is com.musiclify.engine.DeviceLink.NotPairedException) syncMessage = e.message ?: "Sync failed"
            } finally {
                syncing = false
                refreshPairing()
            }
        }
    }

    /** "Play on PC": the PC continues this queue from the current second, and this phone pauses. */
    fun playOnPc() {
        if (graph.prefs.pairing == null || syncing) return
        viewModelScope.launch {
            syncing = true
            try {
                handOffToPc()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                syncMessage = e.message ?: "Couldn't play on your PC"
            } finally {
                syncing = false
                refreshPairing()
            }
        }
    }

    private suspend fun handOffToPc() {
        val queue = player.queue
        if (queue.isEmpty()) return
        val songs = org.json.JSONArray()
        var index = 0
        queue.forEachIndexed { i, item ->
            val s = dao.song(item.songId) ?: return@forEachIndexed
            if (i == player.queueIndex) index = songs.length()
            songs.put(
                org.json.JSONObject()
                    .put("id", s.id).put("title", s.title).put("artist", s.artist).put("artistId", s.artistId)
                    .put("album", s.album).put("cover", s.cover).put("coverBig", s.coverBig).put("durationSec", s.durationSec)
                    .put("youtubeId", s.youtubeId ?: org.json.JSONObject.NULL)
            )
        }
        val payload = org.json.JSONObject()
            .put("songs", songs)
            .put("index", index)
            .put("positionMs", player.positionMs)
            .put("shuffle", player.shuffle)
            .put("repeat", when (player.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "one"
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            })
        graph.link.handoff(payload)
        player.pause()
        syncMessage = "Now playing on $pairedPc"
    }

    fun unpair() {
        graph.prefs.pairing = null
        refreshPairing()
    }

    /** Called after a device-link import: pick up the imported name and rebuild home. */
    fun afterImport() {
        userName = graph.prefs.userName
        if (onboarded) loadHome()
    }

    fun finishOnboardingAfterImport() {
        graph.prefs.onboarded = true
        onboarded = true
        userName = graph.prefs.userName
        loadHome()
    }
    suspend fun searchArtists(q: String) = deezer.searchArtists(q, 30)

    // ---------------- home ----------------
    var home by mutableStateOf<List<HomeSection>>(emptyList())
        private set
    var homeLoading by mutableStateOf(false)
        private set
    var homeError by mutableStateOf<String?>(null)
        private set

    val recent = dao.recentSongs(20).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (onboarded) {
            loadHome()
            syncNow(silent = true) // auto-sync with the paired PC whenever the app opens
            checkForUpdate()
        }
    }

    /** The "Your mix" songs from the current home feed (empty until home loads). */
    val mix: List<Song>
        get() = (home.firstOrNull { it is HomeSection.Songs && it.title == MIX_TITLE } as? HomeSection.Songs)?.songs.orEmpty()

    private var homeLoadedAt = 0L

    /** Rebuild home (and a fresh mix) when it's older than [HOME_TTL_MS]: called whenever home is shown. */
    fun refreshHomeIfStale() {
        if (!onboarded || homeLoading) return
        if (System.currentTimeMillis() - homeLoadedAt > HOME_TTL_MS) loadHome()
    }

    /** True when the player's queue is this list (current song is in it and every song of it is queued). */
    fun isPlayingList(songs: List<Song>): Boolean {
        val cur = player.currentId ?: return false
        if (songs.isEmpty() || songs.none { it.id == cur }) return false
        val queued = player.queue.mapTo(HashSet()) { it.songId }
        return songs.all { it.id in queued }
    }

    /** Play button for a list: pause/resume if that list is already the queue, otherwise start it. */
    fun playOrToggle(songs: List<Song>) {
        if (isPlayingList(songs)) player.togglePlay() else play(songs)
    }

    fun loadHome() = viewModelScope.launch {
        homeLoadedAt = System.currentTimeMillis()
        homeLoading = true
        homeError = null
        try {
            val favs = dao.favoriteArtists().first()
            val picked = pickFeedArtists(favs)
            home = coroutineScope {
                val chart = async { runCatching { deezer.chartTracks(30) }.getOrDefault(emptyList()) }
                // Pull a deeper pool than we show and shuffle it, so the same 15 songs don't come back every time.
                val tops = picked.map { a ->
                    async { runCatching { a to deezer.artistTopTracks(a.id, 50).shuffled().take(15) }.getOrNull() }
                }.awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
                val related = picked.take(4).map { a ->
                    async { runCatching { deezer.relatedArtists(a.id, 8) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten().distinctBy { it.id }.filter { r -> favs.none { it.id == r.id } }.shuffled().take(16)

                buildList {
                    if (tops.isNotEmpty()) add(HomeSection.Songs(MIX_TITLE, "Songs from artists you love", interleave(tops.map { it.second }).take(30).shuffled()))
                    tops.forEach { (artist, songs) -> add(HomeSection.Songs("More from ${artist.name}", songs = songs)) }
                    if (related.isNotEmpty()) add(HomeSection.Artists("Artists you might like", related))
                    chart.await().takeIf { it.isNotEmpty() }?.let { add(HomeSection.Songs("Top charts", "Popular right now", it)) }
                }
            }
            if (home.isEmpty()) { homeLoadedAt = 0L; homeError = "Couldn't load music. Check your internet connection." }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            homeLoadedAt = 0L
            homeError = "Couldn't load music: ${e.message}"
        } finally {
            homeLoading = false
        }
    }

    /**
     * Which favourites feed home this time: the 2 most recently added (so a new artist shows up right away)
     * plus a random 4 of the rest, so every artist gets a turn instead of only the first 6 ever added.
     */
    private fun pickFeedArtists(favs: List<FavoriteArtist>): List<FavoriteArtist> {
        val newest = favs.sortedByDescending { it.addedAt }.take(2)
        return newest + favs.filterNot { it in newest }.shuffled().take(6 - newest.size)
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = mutableListOf<T>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }

    // ---------------- search ----------------
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf(SearchResults())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    fun onQueryChange(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) {
            results = SearchResults()
            searching = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            searching = true
            searchError = null
            try {
                results = coroutineScope {
                    val songs = async { deezer.searchTracks(q.trim()) }
                    val artists = async { deezer.searchArtists(q.trim(), 10) }
                    SearchResults(songs.await(), artists.await())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchError = "Search failed: ${e.message}"
            } finally {
                searching = false
            }
        }
    }

    // ---------------- playback ----------------
    fun play(songs: List<Song>, index: Int = 0, shuffled: Boolean = false) = viewModelScope.launch {
        dao.insertSongs(songs) // the player's resolver reads song metadata from the database
        player.playSongs(songs, index, shuffled)
    }

    fun playNext(song: Song) = viewModelScope.launch { dao.insertSongs(listOf(song)); player.playNext(song) }
    fun addToQueue(song: Song) = viewModelScope.launch { dao.insertSongs(listOf(song)); player.addToQueue(song) }

    suspend fun lyrics(songId: Long): Lyrics = graph.lyricsFor(songId)

    suspend fun songById(id: Long) = dao.song(id)

    // ---------------- library ----------------
    val liked = dao.likedSongs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloads = dao.downloadedSongs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val likedIds = dao.likedSongs().map { l -> l.map { it.id }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val downloadedIds = dao.downloadedSongs().map { l -> l.map { it.id }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val downloadProgress = engine.downloadProgress

    fun toggleLike(song: Song) = viewModelScope.launch {
        dao.insertSongs(listOf(song))
        val nowLiked = song.id !in likedIds.value
        dao.setLiked(song.id, nowLiked, if (nowLiked) System.currentTimeMillis() else null)
        if (!nowLiked) dao.addTombstone("like", song.id.toString(), System.currentTimeMillis())
    }

    fun toggleLikeById(id: Long) = viewModelScope.launch {
        val song = dao.song(id) ?: return@launch
        toggleLike(song)
    }

    fun download(song: Song) = viewModelScope.launch {
        dao.insertSongs(listOf(song))
        engine.download(song.id)
    }

    /** For a song that's already in the database (e.g. the one playing). */
    fun downloadById(id: Long) = engine.download(id)

    /** Songs imported from a linked device that were offline there: fetch them here, one by one. */
    fun downloadMany(ids: List<Long>) = engine.downloadMany(ids)

    fun removeDownload(song: Song) = engine.removeDownload(song.id)

    fun playlistSongs(id: Long) = dao.playlistSongs(id)
    fun playlist(id: Long) = dao.playlist(id)

    fun createPlaylist(name: String, firstSong: Song? = null) = viewModelScope.launch {
        val id = dao.createPlaylist(Playlist(name = name.trim(), createdAt = System.currentTimeMillis()))
        firstSong?.let { addToPlaylist(id, it) }
    }

    fun addToPlaylist(playlistId: Long, song: Song) = viewModelScope.launch {
        dao.insertSongs(listOf(song))
        dao.addToPlaylist(PlaylistSong(playlistId, song.id, System.currentTimeMillis()))
    }

    fun removeFromPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch {
        dao.removeFromPlaylist(playlistId, songId)
        dao.playlistName(playlistId)?.let { dao.addTombstone("playlist_song", "$it${com.musiclify.data.TOMB_SEP}$songId", System.currentTimeMillis()) }
    }

    /** Linked devices know playlists by name: the old name counts as deleted, the new one as created now. */
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch {
        val old = dao.playlistName(id) ?: return@launch
        if (old == name.trim()) return@launch
        val now = System.currentTimeMillis()
        dao.addTombstone("playlist", old, now)
        dao.renamePlaylist(id, name.trim())
        dao.touchPlaylist(id, now)
        dao.touchPlaylistSongs(id, now)
    }

    fun deletePlaylist(id: Long) = viewModelScope.launch {
        dao.playlistName(id)?.let { dao.addTombstone("playlist", it, System.currentTimeMillis()) }
        dao.clearPlaylist(id)
        dao.deletePlaylistRow(id)
    }

    // ---------------- artist page ----------------
    suspend fun artistPage(id: Long): Triple<Artist, List<Song>, List<Artist>> = coroutineScope {
        val artist = async { deezer.artist(id) }
        val top = async { deezer.artistTopTracks(id, 20) }
        val related = async { runCatching { deezer.relatedArtists(id, 12) }.getOrDefault(emptyList()) }
        Triple(artist.await(), top.await(), related.await())
    }

    // ---------------- settings ----------------
    var hasCookies by mutableStateOf(graph.prefs.youtubeCookies != null)
        private set

    var shareMatches by mutableStateOf(graph.prefs.shareMatches)
        private set

    fun updateShareMatches(enabled: Boolean) {
        graph.prefs.shareMatches = enabled
        shareMatches = enabled
    }

    // ---------------- updates ----------------
    /** A newer release to offer in a banner, or null. */
    var availableUpdate by mutableStateOf<AppUpdate?>(null)
        private set

    var checkUpdates by mutableStateOf(graph.prefs.checkUpdates)
        private set

    var ambientLight by mutableStateOf(graph.prefs.ambientLight)
        private set

    fun updateAmbientLight(enabled: Boolean) {
        graph.prefs.ambientLight = enabled
        ambientLight = enabled
    }

    var lockScreenLyrics by mutableStateOf(graph.prefs.lockScreenLyrics)
        private set

    fun updateLockScreenLyrics(enabled: Boolean) {
        graph.prefs.lockScreenLyrics = enabled // the playback service reads it on every tick
        lockScreenLyrics = enabled
    }

    fun updateCheckUpdates(enabled: Boolean) {
        graph.prefs.checkUpdates = enabled
        checkUpdates = enabled
        if (!enabled) availableUpdate = null else checkForUpdate(force = true)
    }

    /** At most once a day (or now, when [force]d from Settings), look for a newer release on GitHub. */
    fun checkForUpdate(force: Boolean = false) = viewModelScope.launch {
        val prefs = graph.prefs
        if (!prefs.checkUpdates) return@launch
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastUpdateCheck < 24 * 60 * 60 * 1000L) return@launch
        val update = runCatching { graph.updates.check(BuildConfig.VERSION_NAME) }.getOrNull()
        prefs.lastUpdateCheck = now
        availableUpdate = update?.takeIf { force || it.version != prefs.dismissedUpdate }
    }

    fun dismissUpdate() {
        availableUpdate?.let { graph.prefs.dismissedUpdate = it.version }
        availableUpdate = null
    }

    /** Returns null on success, or an error message. */
    fun importCookies(text: String): String? {
        val header = Prefs.parseCookiesTxt(text) ?: return "No youtube.com cookies found in that file."
        graph.prefs.youtubeCookies = header
        hasCookies = true
        return null
    }

    fun removeCookies() {
        graph.prefs.youtubeCookies = null
        hasCookies = false
    }

    fun storageBytes() = engine.storageBytes()
    fun clearDownloads(onDone: () -> Unit) = engine.clearAll(onDone)
    fun clearHistory() = viewModelScope.launch {
        dao.clearHistory()
        dao.addTombstone("history", "all", System.currentTimeMillis())
    }

    override fun onCleared() {
        player.release()
    }
}
