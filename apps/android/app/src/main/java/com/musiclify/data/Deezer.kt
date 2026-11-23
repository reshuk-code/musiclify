package com.musiclify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class Artist(val id: Long, val name: String, val picture: String)

/** An album, single or EP. [type] is Deezer's record_type: "album", "single", "ep" or "compile". */
data class Album(
    val id: Long,
    val title: String,
    val cover: String,
    val coverBig: String,
    val releaseDate: String,
    val type: String,
    val artist: String = "",
)

/** Deezer's public API: free, no key. Used only for metadata (titles, artists, covers, charts). */
class Deezer(private val http: OkHttpClient) {

    suspend fun chartTracks(limit: Int = 50) = tracks(get("chart/0/tracks", "limit" to "$limit"))

    suspend fun chartArtists(limit: Int = 50) = artists(get("chart/0/artists", "limit" to "$limit"))

    suspend fun searchTracks(query: String, limit: Int = 40) =
        tracks(get("search/track", "q" to query, "limit" to "$limit"))

    suspend fun searchArtists(query: String, limit: Int = 20) =
        artists(get("search/artist", "q" to query, "limit" to "$limit"))

    suspend fun artist(id: Long) = parseArtist(get("artist/$id"))

    suspend fun artistTopTracks(id: Long, limit: Int = 20) = tracks(get("artist/$id/top", "limit" to "$limit"))

    /** The artist's own releases (albums, singles, EPs), newest first, one per title. */
    suspend fun artistAlbums(id: Long): List<Album> = artistAlbumCache[id] ?: run {
        val all = mutableListOf<Album>()
        var index = 0
        while (index < MAX_ALBUMS) {
            val page = get("artist/$id/albums", "limit" to "100", "index" to "$index")
            val data = page.optJSONArray("data").objects().map(::parseAlbum)
            all += data
            if (data.isEmpty() || !page.has("next")) break
            index += data.size
        }
        // Deezer lists every edition (clean/explicit, regional) separately: keep the first of each title.
        all.sortedByDescending { it.releaseDate }.distinctBy { it.type to it.title.lowercase() }
            .also { artistAlbumCache[id] = it }
    }

    suspend fun album(id: Long): Album = parseAlbum(get("album/$id"))

    /** Every track on an album, with the album's cover filled in (Deezer leaves it out here). */
    suspend fun albumTracks(album: Album): List<Song> = albumTrackCache[album.id] ?: run {
        val json = get("album/${album.id}/tracks", "limit" to "200")
        json.optJSONArray("data").objects().mapNotNull { t ->
            val artist = t.optJSONObject("artist") ?: return@mapNotNull null
            Song(
                id = t.getLong("id"),
                title = t.optString("title"),
                artist = artist.optString("name"),
                artistId = artist.optLong("id"),
                album = album.title,
                cover = album.cover,
                coverBig = album.coverBig,
                durationSec = t.optInt("duration"),
            )
        }.also { albumTrackCache[album.id] = it }
    }

    /**
     * Deezer often files a hit under a greatest-hits compilation ("Lose Yourself" on "Curtain Call:
     * The Hits"), so the app would show that compilation's cover instead of the album the song is
     * known by. This swaps in the original album: the oldest of the artist's own albums (then
     * singles/EPs) that has a track with the same name. Songs with no better match are returned as is.
     */
    suspend fun originalRelease(song: Song): Song {
        if (song.artistId <= 0) return song
        resolvedCache[song.id]?.let { return it }
        // One lookup per artist at a time, so the rest reuse its album lists instead of racing for them.
        return artistLocks.getOrPut(song.artistId) { Mutex() }.withLock {
            resolvedCache[song.id] ?: try {
                (findOriginal(song) ?: song).also { resolvedCache[song.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                song // offline or rate-limited: keep what we have and try again next time
            }
        }
    }

    /** [originalRelease] for the songs in a list that look like they come from a compilation. */
    suspend fun withOriginalCovers(songs: List<Song>): List<Song> = coroutineScope {
        songs.map { song ->
            if (!looksLikeCompilation(song.album)) async { song }
            else async { lookups.withPermit { originalRelease(song) } }
        }.awaitAll()
    }

    private suspend fun findOriginal(song: Song): Song? {
        val albums = artistAlbums(song.artistId)
        val own = albums.firstOrNull { it.title.equals(song.album, ignoreCase = true) }
        // Already on one of the artist's own records, and not a hits collection: nothing to fix.
        if (own != null && own.type != "compile" && !looksLikeCompilation(own.title)) return null
        val name = baseTitle(song.title)
        val candidates = albums
            .filter { it.type != "compile" && !looksLikeCompilation(it.title) }
            .sortedWith(compareBy<Album> { if (it.type == "album") 0 else 1 }.thenBy { it.releaseDate })
            .take(MAX_ALBUM_LOOKUPS)
        for (album in candidates) {
            val tracks = runCatching { albumTracks(album) }.getOrNull() ?: continue
            if (tracks.any { baseTitle(it.title) == name }) {
                return song.copy(album = album.title, cover = album.cover, coverBig = album.coverBig)
            }
        }
        return null
    }

    private val artistAlbumCache = ConcurrentHashMap<Long, List<Album>>()
    private val albumTrackCache = ConcurrentHashMap<Long, List<Song>>()
    private val resolvedCache = ConcurrentHashMap<Long, Song>()
    private val artistLocks = ConcurrentHashMap<Long, Mutex>()
    /** Deezer allows ~50 requests / 5 s; keep cover lookups from crowding out the page itself. */
    private val lookups = Semaphore(4)

    /** Deezer's artist radio: songs by the artist and similar artists, for autoplay. */
    suspend fun artistRadio(id: Long, limit: Int = 40) = tracks(get("artist/$id/radio", "limit" to "$limit"))

    /**
     * Best Deezer match for a song known only by name (from an imported playlist): an exact
     * artist + title search first, then a looser one. Among the top results, the one whose length
     * is closest to [durationSec] wins, so a live version or remix doesn't sneak in.
     */
    suspend fun findTrack(title: String, artist: String, durationSec: Int = 0): Song? {
        val cleanTitle = title.replace(Regex("""\s*[(\[](feat|ft|with|from|prod)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
        val queries = buildList {
            if (artist.isNotBlank()) add("artist:\"$artist\" track:\"$cleanTitle\"")
            add("$artist $cleanTitle".trim())
        }
        for (q in queries) {
            val found = runCatching { searchTracks(q, 8) }.getOrDefault(emptyList())
            if (found.isEmpty()) continue
            val name = baseTitle(cleanTitle)
            val sameName = found.filter { baseTitle(it.title) == name }.ifEmpty { found }
            return if (durationSec > 0) sameName.minBy { kotlin.math.abs(it.durationSec - durationSec) } else sameName.first()
        }
        return null
    }

    suspend fun relatedArtists(id: Long, limit: Int = 15) = artists(get("artist/$id/related", "limit" to "$limit"))

    /**
     * Deezer hides whole catalogues from some countries (in Nepal, Ed Sheeran's own albums don't
     * show up in search or on his page), so requests go through Musiclify's relay, which asks Deezer
     * from the US. If the relay can't answer (down, or Deezer rate-limits it), ask Deezer directly.
     */
    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            runCatching { fetch(RELAY + path, params) }.getOrElse { fetch(DIRECT + path, params) }
        }

    private fun fetch(base: String, params: Array<out Pair<String, String>>): JSONObject {
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Deezer HTTP ${response.code}")
            val json = JSONObject(response.body!!.string())
            json.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
            json
        }
    }

    private fun tracks(json: JSONObject): List<Song> =
        json.optJSONArray("data").objects().mapNotNull { t ->
            // No "readable" filter: that's Deezer's own streaming rights in this region. Musiclify
            // only uses Deezer for names and covers (audio comes from YouTube), so those songs play fine.
            val artist = t.optJSONObject("artist") ?: return@mapNotNull null
            val album = t.optJSONObject("album")
            Song(
                id = t.getLong("id"),
                title = t.optString("title"),
                artist = artist.optString("name"),
                artistId = artist.optLong("id"),
                album = album?.optString("title").orEmpty(),
                cover = album?.optString("cover_medium").orEmpty(),
                coverBig = album?.optString("cover_xl")?.ifEmpty { null } ?: album?.optString("cover_big").orEmpty(),
                durationSec = t.optInt("duration"),
            )
        }

    private fun parseAlbum(a: JSONObject) = Album(
        id = a.getLong("id"),
        title = a.optString("title"),
        cover = a.optString("cover_medium"),
        coverBig = a.optString("cover_xl").ifEmpty { a.optString("cover_big") },
        releaseDate = a.optString("release_date"),
        type = a.optString("record_type", "album"),
        artist = a.optJSONObject("artist")?.optString("name").orEmpty(),
    )

    private fun artists(json: JSONObject) = json.optJSONArray("data").objects().map(::parseArtist)

    private fun parseArtist(a: JSONObject) = Artist(
        id = a.getLong("id"),
        name = a.optString("name"),
        picture = a.optString("picture_big").ifEmpty { a.optString("picture_medium") },
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private const val RELAY = "https://musiclify.vercel.app/api/deezer/"
        private const val DIRECT = "https://api.deezer.com/"
        private const val MAX_ALBUMS = 300
        private const val MAX_ALBUM_LOOKUPS = 15

        private val COMPILATION = Regex(
            """\b(hits|best of|greatest|the very best|collection|essentials?|anthology|number ones|#1'?s|""" +
                """the singles|now that'?s what i call|playlist|top \d+|love songs|wedding)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun looksLikeCompilation(albumTitle: String) = COMPILATION.containsMatchIn(albumTitle)

        /** "Lose Yourself (From \"8 Mile\")" and "Stan - Remastered" -> "lose yourself", "stan". */
        fun baseTitle(title: String): String = title
            .replace(Regex("""\s*[(\[].*?[)\]]"""), "")
            .substringBefore(" - ")
            .lowercase()
            .filter { it.isLetterOrDigit() }
    }
}
