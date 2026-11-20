package com.musiclify.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

data class ImportSummary(
    val name: String,
    val songs: Int,
    val liked: Int,
    val playlists: Int,
    val artists: Int,
    val history: Int,
    val lyrics: Int,
    /** Songs that were offline on the other device; the UI offers to download them here. */
    val offlineIds: List<Long>,
    /** Items removed here because they were deleted on the other device. */
    val removed: Int = 0,
)

/**
 * Library export/import for device linking. The JSON format ("musiclify-export" v2) is shared
 * with the desktop app (desktop/src-tauri/src/sync.rs) and still reads v1.
 *
 * Carries everything about a library: profile + settings, favourite artists (in order), every
 * known song with its YouTube match, like date, cached lyrics and whether it was offline,
 * playlists with song order, and full history. Never carries audio files or YouTube cookies.
 */
class Sync(private val db: MusicDatabase, private val prefs: Prefs) {
    private val dao = db.dao()

    suspend fun export(): String {
        val songs = JSONArray()
        dao.exportSongs().forEach { s ->
            songs.put(
                JSONObject()
                    .put("id", s.id).put("title", s.title).put("artist", s.artist).put("artistId", s.artistId)
                    .put("album", s.album).put("cover", s.cover).put("coverBig", s.coverBig)
                    .put("durationSec", s.durationSec).put("youtubeId", s.youtubeId ?: JSONObject.NULL)
                    .put("liked", s.liked).put("likedAt", s.likedAt ?: JSONObject.NULL)
                    .put("downloaded", s.downloaded)
                    .put("lyricsJson", s.lyricsJson ?: JSONObject.NULL)
            )
        }
        val playlists = JSONArray()
        dao.allPlaylists().forEach { p ->
            val entries = dao.playlistEntries(p.id)
            playlists.put(
                JSONObject().put("name", p.name).put("createdAt", p.createdAt)
                    .put("songIds", JSONArray(entries.map { it.songId }))
                    .put("addedAt", JSONArray(entries.map { it.addedAt }))
            )
        }
        val history = JSONArray()
        dao.historyList().forEach { history.put(JSONObject().put("songId", it.songId).put("playedAt", it.playedAt)) }
        val artists = JSONArray()
        dao.favoriteArtistsList().forEach { artists.put(JSONObject().put("id", it.id).put("name", it.name).put("picture", it.picture).put("addedAt", it.addedAt)) }
        val deletions = JSONArray()
        dao.tombstones().forEach { deletions.put(JSONObject().put("kind", it.kind).put("key", it.key).put("at", it.at)) }

        return JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("profile", JSONObject().put("name", prefs.userName).put("shareMatches", prefs.shareMatches))
            .put("favoriteArtists", artists).put("songs", songs).put("playlists", playlists).put("history", history).put("deletions", deletions)
            .toString()
    }

    /** Merge another device's library into this one (union; nothing local is deleted). */
    suspend fun import(json: String): ImportSummary {
        val o = JSONObject(json)
        require(o.optString("format") == FORMAT) { "Not a Musiclify export" }
        val songsJson = o.optJSONArray("songs") ?: JSONArray()
        val playlistsJson = o.optJSONArray("playlists") ?: JSONArray()
        val artistsJson = o.optJSONArray("favoriteArtists") ?: JSONArray()
        val historyJson = o.optJSONArray("history") ?: JSONArray()
        val profile = o.optJSONObject("profile")
        val deletionsJson = o.optJSONArray("deletions") ?: JSONArray()
        var removed = 0
        val wasNew = !prefs.onboarded
        var liked = 0
        var lyrics = 0
        var history = 0
        val offline = mutableListOf<Long>()

        db.withTransaction {
            // 1) Deletions first: remove what the other device deleted after it was added here,
            //    and keep the deletion so it travels on to other devices.
            deletionsJson.objects().forEach { d ->
                val kind = d.optString("kind"); val key = d.optString("key"); val at = d.optLong("at")
                removed += when (kind) {
                    "like" -> key.toLongOrNull()?.let { dao.unlikeIfOlder(it, at) } ?: 0
                    "artist" -> key.toLongOrNull()?.let { dao.unfollowIfOlder(it, at) } ?: 0
                    "playlist" -> { dao.clearPlaylistIfOlder(key, at); dao.deletePlaylistIfOlder(key, at) }
                    "playlist_song" -> {
                        val name = key.substringBefore(TOMB_SEP); val sid = key.substringAfter(TOMB_SEP, "").toLongOrNull()
                        if (sid == null) 0 else dao.removePlaylistSongIfOlder(name, sid, at)
                    }
                    "history" -> dao.clearHistoryBefore(at)
                    else -> 0 // from a newer version: keep for forwarding
                }
                if (kind.isNotEmpty() && key.isNotEmpty()) dao.addTombstone(kind, key, at)
            }
            suspend fun deleted(kind: String, key: String, itemAt: Long) = (dao.tombstoneAt(kind, key) ?: Long.MIN_VALUE) >= itemAt

            dao.insertSongs(songsJson.objects().map { s ->
                Song(
                    id = s.getLong("id"), title = s.optString("title"), artist = s.optString("artist"),
                    artistId = s.optLong("artistId"), album = s.optString("album"), cover = s.optString("cover"),
                    coverBig = s.optString("coverBig"), durationSec = s.optInt("durationSec"),
                )
            })
            songsJson.objects().forEach { s ->
                val id = s.getLong("id")
                s.str("youtubeId")?.let { dao.setYoutubeIdIfMissing(id, it) }
                if (s.optBoolean("liked")) {
                    val at = if (s.isNull("likedAt")) System.currentTimeMillis() else s.getLong("likedAt")
                    if (!deleted("like", id.toString(), at)) liked += dao.markLiked(id, at)
                }
                s.str("lyricsJson")?.let { lyrics += dao.setLyricsIfMissing(id, it) }
                if (s.optBoolean("downloaded") && dao.isDownloaded(id) != true) offline += id
            }

            // Favourite artists keep the other device's order, after any already here.
            val base = maxOf(dao.maxFavoriteAddedAt(), System.currentTimeMillis())
            dao.addFavoriteArtistsIfMissing(artistsJson.objects().filterNot { a ->
                deleted("artist", a.getLong("id").toString(), a.optLong("addedAt", 0L))
            }.mapIndexed { i, a ->
                FavoriteArtist(a.getLong("id"), a.optString("name"), a.optString("picture"), base + i)
            })

            playlistsJson.objects().forEach { p ->
                val name = p.optString("name").ifBlank { return@forEach }
                val createdAt = p.optLong("createdAt", System.currentTimeMillis())
                if (deleted("playlist", name, createdAt)) return@forEach
                val pid = dao.playlistIdByName(name) ?: dao.createPlaylist(Playlist(name = name, createdAt = createdAt))
                val ids = p.optJSONArray("songIds") ?: JSONArray()
                val added = p.optJSONArray("addedAt")
                for (i in 0 until ids.length()) {
                    val at = if (added != null && i < added.length()) added.getLong(i) else createdAt + i
                    if (!deleted("playlist_song", "$name$TOMB_SEP${ids.getLong(i)}", at)) dao.addToPlaylist(PlaylistSong(pid, ids.getLong(i), at))
                }
            }

            val clearedAt = dao.tombstoneAt("history", "all") ?: Long.MIN_VALUE
            historyJson.objects().forEach { h ->
                val songId = h.getLong("songId")
                val at = h.getLong("playedAt")
                if (at <= clearedAt) return@forEach
                if (dao.historyCount(songId, at) == 0) {
                    dao.addHistory(HistoryEntry(songId = songId, playedAt = at))
                    history++
                }
            }
        }

        val incomingName = profile?.optString("name").orEmpty().trim()
        if (prefs.userName.isBlank() && incomingName.isNotEmpty()) prefs.userName = incomingName
        // A brand-new device adopts the other device's settings.
        if (wasNew && profile != null && profile.has("shareMatches")) prefs.shareMatches = profile.optBoolean("shareMatches", true)

        return ImportSummary(
            name = prefs.userName, songs = songsJson.length(), liked = liked, playlists = playlistsJson.length(),
            artists = artistsJson.length(), history = history, lyrics = lyrics, offlineIds = offline, removed = removed,
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

    companion object {
        const val FORMAT = "musiclify-export"
        const val VERSION = 3
    }
}
