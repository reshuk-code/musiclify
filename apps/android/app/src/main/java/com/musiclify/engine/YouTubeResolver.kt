package com.musiclify.engine

import com.musiclify.data.LrcLib
import com.musiclify.data.MusicDao
import com.musiclify.data.Song
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Finds the YouTube upload of a song and its direct audio URL, entirely on the device.
 *
 * All methods block; call them from a background thread (the player's loader thread does).
 */
class YouTubeResolver(private val dao: MusicDao, private val matchCache: MatchCache) {

    private data class StreamUrl(val url: String, val expiresAt: Long)

    private val streamUrls = ConcurrentHashMap<Long, StreamUrl>()

    /** Direct, short-lived audio URL for [songId]. */
    fun audioUrl(songId: Long): String {
        streamUrls[songId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.url }

        val song = dao.songBlocking(songId) ?: throw IOException("Unknown song $songId")
        val info = resolveVideo(song)

        val stream = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate })
            .firstOrNull()
            ?: throw IOException("No playable audio found for this song")

        // YouTube URLs expire after ~6 h; refresh a bit earlier.
        streamUrls[songId] = StreamUrl(stream.content, System.currentTimeMillis() + 4 * 3600_000L)
        return stream.content
    }

    /**
     * Get the YouTube video for a song: our own earlier match, else the shared cache (verified
     * by duration so a bad shared entry can't hijack playback), else a fresh YouTube search.
     */
    private fun resolveVideo(song: Song): StreamInfo {
        song.youtubeId?.let { return streamInfo(it) }

        matchCache.lookup(song.id)?.let { shared ->
            val info = runCatching { streamInfo(shared.youtubeId) }.getOrNull()
            if (info != null && durationMatches(info.duration, song.durationSec)) {
                dao.setYoutubeId(song.id, shared.youtubeId)
                matchCache.submit(song.id, shared.youtubeId, info.duration.toInt()) // confirm it
                return info
            }
        }

        val videoId = findVideoId(song)
        val info = streamInfo(videoId)
        dao.setYoutubeId(song.id, videoId)
        if (durationMatches(info.duration, song.durationSec)) matchCache.submit(song.id, videoId, info.duration.toInt())
        return info
    }

    private fun streamInfo(videoId: String): StreamInfo =
        StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")

    private fun durationMatches(videoSec: Long, songSec: Int) =
        songSec <= 0 || videoSec <= 0 || abs(videoSec - songSec) <= 10

    /** Forget the cached URL (e.g. after HTTP 403) so the next play fetches a fresh one. */
    fun invalidate(songId: Long) {
        streamUrls.remove(songId)
    }

    /** Pick the YouTube video that best matches this exact song. */
    fun findVideoId(song: Song): String {
        val title = LrcLib.cleanTitle(song.title)
        val query = "${song.artist} $title"

        // YouTube Music "songs" results are the official audio uploads: best matches.
        var candidates = search(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        if (candidates.isEmpty()) candidates = search("$query audio", YoutubeSearchQueryHandlerFactory.VIDEOS)
        val best = candidates.minByOrNull { score(it, song, title) }
            ?: throw IOException("Couldn't find this song on YouTube")
        return videoId(best.url) ?: throw IOException("Bad YouTube URL: ${best.url}")
    }

    private fun search(query: String, filter: String): List<StreamInfoItem> {
        val handler = ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf(filter), "")
        return SearchInfo.getInfo(ServiceList.YouTube, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .take(10)
    }

    private fun score(item: StreamInfoItem, song: Song, title: String): Double {
        val name = item.name.lowercase()
        val wanted = title.lowercase()
        var s = if (item.duration > 0 && song.durationSec > 0) abs(item.duration - song.durationSec).toDouble() else 30.0
        s += UNWANTED.count { it in name && it !in wanted } * 60
        if (wanted !in name) s += 20
        if (!item.uploaderName.orEmpty().contains(song.artist, ignoreCase = true) &&
            !name.contains(song.artist.lowercase())
        ) s += 10
        return s
    }

    private fun videoId(url: String): String? =
        Regex("""[?&]v=([\w-]{11})""").find(url)?.groupValues?.get(1)
            ?: Regex("""youtu\.be/([\w-]{11})""").find(url)?.groupValues?.get(1)

    private companion object {
        val UNWANTED = listOf(
            "live", "cover", "remix", "karaoke", "instrumental", "reaction", "8d", "slowed",
            "sped up", "nightcore", "lofi", "lo-fi", "tutorial", "1 hour", "reverb",
        )
    }
}
