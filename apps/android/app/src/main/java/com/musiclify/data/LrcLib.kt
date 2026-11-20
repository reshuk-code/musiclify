package com.musiclify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

data class LyricLine(val startMs: Long, val text: String)

data class Lyrics(val synced: Boolean, val lines: List<LyricLine>) {
    fun toJson(): String = JSONObject()
        .put("synced", synced)
        .put("lines", JSONArray().apply { lines.forEach { put(JSONArray().put(it.startMs).put(it.text)) } })
        .toString()

    companion object {
        val NONE = Lyrics(false, emptyList())

        fun fromJson(json: String): Lyrics {
            val o = JSONObject(json)
            val arr = o.getJSONArray("lines")
            return Lyrics(
                o.optBoolean("synced"),
                (0 until arr.length()).map { arr.getJSONArray(it).let { l -> LyricLine(l.getLong(0), l.getString(1)) } },
            )
        }
    }
}

/** LRCLIB (lrclib.net): free synced-lyrics database, no key needed. */
class LrcLib(private val http: OkHttpClient) {

    suspend fun find(artist: String, title: String, durationSec: Int): Lyrics? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTitle(title)
        val exact = request("get", "artist_name" to artist, "track_name" to cleanTitle, "duration" to "$durationSec")
            ?.let { JSONObject(it) }
        val hit = exact?.takeIf { it.hasLyrics() } ?: run {
            val results = request("search", "artist_name" to artist, "track_name" to cleanTitle)
                ?.let { JSONArray(it) } ?: JSONArray()
            (0 until results.length()).map { results.getJSONObject(it) }
                .filter { it.hasLyrics() && abs(it.optDouble("duration", 0.0) - durationSec) <= 15 }
                .minWithOrNull(compareBy({ it.isNull("syncedLyrics") }, { abs(it.optDouble("duration") - durationSec) }))
        } ?: return@withContext null

        val synced = hit.optString("syncedLyrics").takeIf { !hit.isNull("syncedLyrics") && it.isNotBlank() }
        if (synced != null) Lyrics(true, parseLrc(synced))
        else Lyrics(false, hit.optString("plainLyrics").lines().filter { it.isNotBlank() }.map { LyricLine(0, it.trim()) })
    }

    private fun request(path: String, vararg params: Pair<String, String>): String? {
        val url = "https://lrclib.net/api/$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("User-Agent", "Musiclify (open source)").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("LRCLIB HTTP ${r.code}")
            return r.body?.string()
        }
    }

    private fun JSONObject.hasLyrics() =
        (!isNull("syncedLyrics") && optString("syncedLyrics").isNotBlank()) ||
            (!isNull("plainLyrics") && optString("plainLyrics").isNotBlank())

    companion object {
        private val LINE = Regex("""\[(\d+):(\d+(?:\.\d+)?)](.*)""")
        private val WORD_TAG = Regex("""<\d+:\d+(?:\.\d+)?>""")

        fun parseLrc(lrc: String): List<LyricLine> = lrc.lines().mapNotNull { raw ->
            val m = LINE.matchEntire(raw.trim()) ?: return@mapNotNull null
            val ms = (m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toDouble() * 1000).toLong()
            val text = WORD_TAG.replace(m.groupValues[3], "").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) null else LyricLine(ms, text)
        }

        /** Strip "(feat. X)", "- Remastered 2011", "(Live)" etc. so lookups match the base song. */
        fun cleanTitle(title: String): String = title
            .replace(Regex("""\s*[(\[](feat\.?|ft\.?|with|from|remaster|live|radio edit|explicit)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+.*(remaster|version|edit|live|mix).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
