package com.musiclify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A song from another service, known only by name. */
data class ImportTrack(val title: String, val artist: String, val durationSec: Int)

data class ImportSource(val name: String, val service: String, val tracks: List<ImportTrack>)

data class ImportResult(val name: String, val matched: List<Song>, val missing: List<ImportTrack>)

/**
 * Turns a Spotify or YouTube playlist link into Musiclify songs: reads the public track list
 * (no account or API key), then finds each song on Deezer.
 */
class PlaylistImporter(private val http: OkHttpClient, private val deezer: Deezer) {

    sealed interface Link {
        data class Spotify(val kind: String, val id: String) : Link
        data class YouTube(val listId: String) : Link
    }

    suspend fun fetch(url: String): ImportSource = when (val link = parse(url)) {
        is Link.Spotify -> spotify(link)
        is Link.YouTube -> youtube(link)
        null -> throw IOException("Paste a Spotify or YouTube playlist link.")
    }

    /** Find every track on Deezer, a few at a time; [onProgress] gets (done, total). */
    suspend fun match(source: ImportSource, onProgress: (Int, Int) -> Unit): ImportResult = coroutineScope {
        val done = AtomicInteger(0)
        val gate = Semaphore(3) // stay well inside Deezer's 50 requests / 5 s
        val found = source.tracks.map { t ->
            async {
                gate.withPermit {
                    val song = runCatching { deezer.findTrack(t.title, t.artist, t.durationSec) }.getOrNull()
                    onProgress(done.incrementAndGet(), source.tracks.size)
                    t to song
                }
            }
        }.awaitAll()
        ImportResult(
            name = source.name,
            matched = found.mapNotNull { it.second }.distinctBy { it.id },
            missing = found.filter { it.second == null }.map { it.first },
        )
    }

    // ---------------------------------------------------------------- Spotify

    /** Spotify's embed page carries the track list (up to ~100 songs) in its page data. */
    private suspend fun spotify(link: Link.Spotify): ImportSource = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/${link.kind}/${link.id}")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
            .build()
        val html = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify didn't share this ${link.kind} (HTTP ${r.code}). Is it public?")
            r.body!!.string()
        }
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: throw IOException("Couldn't read this Spotify ${link.kind}.")
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: throw IOException("Couldn't read this Spotify ${link.kind}.")
        val list = entity.optJSONArray("trackList") ?: throw IOException("This Spotify ${link.kind} has no songs.")
        val tracks = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { t ->
            ImportTrack(
                title = t.optString("title"),
                // "Artist, Other artist": the first one is the main artist.
                artist = t.optString("subtitle").split(",").first().replace(' ', ' ').trim(),
                durationSec = (t.optLong("duration") / 1000).toInt(),
            )
        }.filter { it.title.isNotBlank() }
        ImportSource(entity.optString("name").ifBlank { "Spotify playlist" }, "Spotify", tracks)
    }

    // ---------------------------------------------------------------- YouTube

    private suspend fun youtube(link: Link.YouTube): ImportSource = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/playlist?list=${link.listId}"
        val service = ServiceList.YouTube
        val info = runCatching { PlaylistInfo.getInfo(service, url) }
            .getOrElse { throw IOException("Couldn't open this YouTube playlist. Is it public?") }
        val items = info.relatedItems.toMutableList()
        var page: Page? = info.nextPage
        while (Page.isValid(page) && items.size < MAX_TRACKS) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            items += more.items
            page = more.nextPage
        }
        ImportSource(info.name.ifBlank { "YouTube playlist" }, "YouTube", items.take(MAX_TRACKS).map(::fromVideo))
    }

    /** "Artist - Song (Official Video)" or a "Artist - Topic" channel upload -> artist + title. */
    private fun fromVideo(v: StreamInfoItem): ImportTrack {
        val uploader = v.uploaderName.orEmpty()
        val name = v.name.replace(VIDEO_NOISE, "").trim()
        val (artist, title) = when {
            uploader.endsWith(" - Topic") -> uploader.removeSuffix(" - Topic") to name
            " - " in name -> name.substringBefore(" - ").trim() to name.substringAfter(" - ").trim()
            else -> uploader.removeSuffix("VEVO").trim() to name
        }
        return ImportTrack(title, artist, v.duration.toInt().coerceAtLeast(0))
    }

    companion object {
        private const val MAX_TRACKS = 500
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val VIDEO_NOISE = Regex(
            """\s*[(\[](official|lyric|lyrics|audio|video|visualizer|hd|4k|mv|m/v)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val SPOTIFY = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(?:embed/)?(playlist|album)/([A-Za-z0-9]+)""")
        private val YOUTUBE_LIST = Regex("""[?&]list=([A-Za-z0-9_-]+)""")

        fun parse(url: String): Link? {
            val u = url.trim()
            SPOTIFY.find(u)?.let { return Link.Spotify(it.groupValues[1], it.groupValues[2]) }
            if ("youtube.com" in u || "youtu.be" in u) YOUTUBE_LIST.find(u)?.let { return Link.YouTube(it.groupValues[1]) }
            return null
        }
    }
}
