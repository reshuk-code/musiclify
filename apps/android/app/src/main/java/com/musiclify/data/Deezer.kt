package com.musiclify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class Artist(val id: Long, val name: String, val picture: String)

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

    suspend fun relatedArtists(id: Long, limit: Int = 15) = artists(get("artist/$id/related", "limit" to "$limit"))

    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val url = "https://api.deezer.com/$path".toHttpUrl().newBuilder()
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Deezer HTTP ${response.code}")
                val json = JSONObject(response.body!!.string())
                json.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
                json
            }
        }

    private fun tracks(json: JSONObject): List<Song> =
        json.optJSONArray("data").objects().mapNotNull { t ->
            if (t.optBoolean("readable", true).not()) return@mapNotNull null
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

    private fun artists(json: JSONObject) = json.optJSONArray("data").objects().map(::parseArtist)

    private fun parseArtist(a: JSONObject) = Artist(
        id = a.getLong("id"),
        name = a.optString("name"),
        picture = a.optString("picture_big").ifEmpty { a.optString("picture_medium") },
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
