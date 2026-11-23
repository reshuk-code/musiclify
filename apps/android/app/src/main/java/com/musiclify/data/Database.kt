package com.musiclify.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A song. Metadata comes from Deezer; the id is the Deezer track id. */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val cover: String,
    val coverBig: String,
    val durationSec: Int,
    /** YouTube video this song was matched to (cached so we only search once). */
    val youtubeId: String? = null,
    val liked: Boolean = false,
    val likedAt: Long? = null,
    /** Fully stored on the device (encrypted) and playable offline. */
    val downloaded: Boolean = false,
    /** Cached lyrics, see [Lyrics.toJson]. */
    val lyricsJson: String? = null,
)

@Entity(tableName = "history", indices = [Index("songId")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"], indices = [Index("songId")])
data class PlaylistSong(val playlistId: Long, val songId: Long, val addedAt: Long)

@Entity(tableName = "favorite_artists")
data class FavoriteArtist(
    @PrimaryKey val id: Long,
    val name: String,
    val picture: String,
    val addedAt: Long,
)

/**
 * A deletion, kept so it can sync to linked devices (same scheme as the desktop app):
 * like:<songId> · playlist:<name> · playlist_song:<name>\u001f<songId> · artist:<id> · history:all.
 * A deletion only removes items older than it; re-adding later wins.
 */
@Entity(tableName = "tombstones", primaryKeys = ["kind", "key"])
data class Tombstone(val kind: String, val key: String, val at: Long)

const val TOMB_SEP = '\u001f'

/** A downloaded song with how much it's been listened to (for choosing what to remove). */
data class DownloadUsage(
    @Embedded val song: Song,
    val lastPlayed: Long?,
    val plays: Int,
)

data class SongPlays(
    @Embedded val song: Song,
    val plays: Int,
)

data class ArtistPlays(
    val artistId: Long,
    val artist: String,
    val plays: Int,
    val cover: String?,
)

data class PlaylistSummary(
    @Embedded val playlist: Playlist,
    val songCount: Int,
    val cover: String?,
)

@Dao
interface MusicDao {
    // ---- songs ----
    /** Never overwrites an existing row, so likes / downloads / matches survive. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<Song>)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun song(id: Long): Song?

    /** Blocking variant for the player's loader thread. */
    @Query("SELECT * FROM songs WHERE id = :id")
    fun songBlocking(id: Long): Song?

    /** The original album's name and cover, found after the song was saved (see Deezer.originalRelease). */
    @Query("UPDATE songs SET album = :album, cover = :cover, coverBig = :coverBig WHERE id = :id")
    suspend fun setAlbumArt(id: Long, album: String, cover: String, coverBig: String)

    @Query("UPDATE songs SET youtubeId = :youtubeId WHERE id = :id")
    fun setYoutubeId(id: Long, youtubeId: String?)

    @Query("UPDATE songs SET liked = :liked, likedAt = :at WHERE id = :id")
    suspend fun setLiked(id: Long, liked: Boolean, at: Long?)

    @Query("UPDATE songs SET downloaded = :downloaded WHERE id = :id")
    suspend fun setDownloaded(id: Long, downloaded: Boolean)

    @Query("UPDATE songs SET downloaded = 0")
    suspend fun clearDownloaded()

    @Query("UPDATE songs SET lyricsJson = :json WHERE id = :id")
    suspend fun setLyrics(id: Long, json: String)

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    fun likedSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE downloaded = 1 ORDER BY title COLLATE NOCASE")
    fun downloadedSongs(): Flow<List<Song>>

    // ---- history ----
    @Insert
    suspend fun addHistory(entry: HistoryEntry)

    @Query(
        """SELECT s.* FROM songs s
           JOIN (SELECT songId, MAX(playedAt) AS lastPlayed FROM history GROUP BY songId) h ON h.songId = s.id
           ORDER BY h.lastPlayed DESC LIMIT :limit"""
    )
    fun recentSongs(limit: Int): Flow<List<Song>>

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("SELECT songId FROM history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recentSongIds(limit: Int): List<Long>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun songsByIds(ids: List<Long>): List<Song>

    // ---- listening stats ----
    @Query(
        """SELECT s.*, COUNT(h.id) AS plays FROM history h JOIN songs s ON s.id = h.songId
           WHERE h.playedAt >= :since GROUP BY s.id ORDER BY plays DESC, MAX(h.playedAt) DESC LIMIT :limit"""
    )
    suspend fun topSongs(since: Long, limit: Int): List<SongPlays>

    @Query(
        """SELECT s.artistId AS artistId, s.artist AS artist, COUNT(h.id) AS plays, MAX(s.cover) AS cover
           FROM history h JOIN songs s ON s.id = h.songId
           WHERE h.playedAt >= :since GROUP BY s.artistId ORDER BY plays DESC LIMIT :limit"""
    )
    suspend fun topArtists(since: Long, limit: Int): List<ArtistPlays>

    @Query("SELECT COALESCE(SUM(s.durationSec), 0) FROM history h JOIN songs s ON s.id = h.songId WHERE h.playedAt >= :since")
    suspend fun secondsListened(since: Long): Long

    @Query("SELECT COUNT(*) FROM history WHERE playedAt >= :since")
    suspend fun playCount(since: Long): Int

    @Query("SELECT COUNT(DISTINCT songId) FROM history WHERE playedAt >= :since")
    suspend fun distinctSongCount(since: Long): Int

    // ---- storage ----
    /** Downloads, least recently played first (never-played ones at the top). */
    @Query(
        """SELECT s.*, MAX(h.playedAt) AS lastPlayed, COUNT(h.id) AS plays
           FROM songs s LEFT JOIN history h ON h.songId = s.id
           WHERE s.downloaded = 1 GROUP BY s.id
           ORDER BY lastPlayed IS NOT NULL, lastPlayed ASC"""
    )
    suspend fun downloadUsage(): List<DownloadUsage>

    // ---- playlists ----
    @Insert
    suspend fun createPlaylist(playlist: Playlist): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearPlaylist(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToPlaylist(entry: PlaylistSong)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeFromPlaylist(playlistId: Long, songId: Long)

    @Query(
        """SELECT p.*, COUNT(ps.songId) AS songCount,
              (SELECT s.cover FROM playlist_songs x JOIN songs s ON s.id = x.songId
               WHERE x.playlistId = p.id ORDER BY x.addedAt LIMIT 1) AS cover
           FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
           GROUP BY p.id ORDER BY p.createdAt DESC"""
    )
    fun playlists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: Long): Flow<Playlist?>

    @Query(
        """SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id
           WHERE ps.playlistId = :id ORDER BY ps.addedAt"""
    )
    fun playlistSongs(id: Long): Flow<List<Song>>

    // ---- favourite artists ----
    @Query("SELECT * FROM favorite_artists ORDER BY addedAt")
    fun favoriteArtists(): Flow<List<FavoriteArtist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavoriteArtists(artists: List<FavoriteArtist>)

    @Query("DELETE FROM favorite_artists WHERE id = :id")
    suspend fun removeFavoriteArtist(id: Long)

    @Query("DELETE FROM favorite_artists")
    suspend fun clearFavoriteArtists()

    // ---- device linking (export / import) ----
    @Query("SELECT * FROM songs")
    suspend fun exportSongs(): List<Song>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY addedAt")
    suspend fun playlistEntries(id: Long): List<PlaylistSong>

    @Query("UPDATE songs SET lyricsJson = :json WHERE id = :id AND lyricsJson IS NULL")
    suspend fun setLyricsIfMissing(id: Long, json: String): Int

    @Query("SELECT downloaded FROM songs WHERE id = :id")
    suspend fun isDownloaded(id: Long): Boolean?

    @Query("SELECT * FROM playlists ORDER BY createdAt")
    suspend fun allPlaylists(): List<Playlist>

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :id ORDER BY addedAt")
    suspend fun playlistSongIds(id: Long): List<Long>

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 20000")
    suspend fun historyList(): List<HistoryEntry>

    @Query("SELECT * FROM favorite_artists ORDER BY addedAt")
    suspend fun favoriteArtistsList(): List<FavoriteArtist>

    @Query("UPDATE songs SET youtubeId = :youtubeId WHERE id = :id AND youtubeId IS NULL")
    suspend fun setYoutubeIdIfMissing(id: Long, youtubeId: String)

    @Query("UPDATE songs SET liked = 1, likedAt = COALESCE(likedAt, :at) WHERE id = :id")
    suspend fun markLiked(id: Long, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavoriteArtistsIfMissing(artists: List<FavoriteArtist>)

    @Query("SELECT id FROM playlists WHERE name = :name LIMIT 1")
    suspend fun playlistIdByName(name: String): Long?

    @Query("SELECT COUNT(*) FROM history WHERE songId = :songId AND playedAt = :playedAt")
    suspend fun historyCount(songId: Long, playedAt: Long): Int

    @Query("SELECT COALESCE(MAX(addedAt), 0) FROM favorite_artists")
    suspend fun maxFavoriteAddedAt(): Long

    @Query("SELECT COUNT(*) FROM favorite_artists")
    suspend fun favoriteCount(): Int

    // ---- deletion sync ----
    @Query("INSERT INTO tombstones (kind, `key`, at) VALUES (:kind, :key, :at) ON CONFLICT(kind, `key`) DO UPDATE SET at = MAX(at, excluded.at)")
    suspend fun addTombstone(kind: String, key: String, at: Long)

    @Query("SELECT at FROM tombstones WHERE kind = :kind AND `key` = :key")
    suspend fun tombstoneAt(kind: String, key: String): Long?

    @Query("SELECT * FROM tombstones")
    suspend fun tombstones(): List<Tombstone>

    @Query("SELECT name FROM playlists WHERE id = :id")
    suspend fun playlistName(id: Long): String?

    @Query("UPDATE songs SET liked = 0, likedAt = NULL WHERE id = :id AND liked = 1 AND COALESCE(likedAt, 0) <= :at")
    suspend fun unlikeIfOlder(id: Long, at: Long): Int

    @Query("DELETE FROM favorite_artists WHERE id = :id AND addedAt <= :at")
    suspend fun unfollowIfOlder(id: Long, at: Long): Int

    @Query("DELETE FROM playlist_songs WHERE playlistId IN (SELECT id FROM playlists WHERE name = :name AND createdAt <= :at)")
    suspend fun clearPlaylistIfOlder(name: String, at: Long): Int

    @Query("DELETE FROM playlists WHERE name = :name AND createdAt <= :at")
    suspend fun deletePlaylistIfOlder(name: String, at: Long): Int

    @Query("DELETE FROM playlist_songs WHERE songId = :songId AND addedAt <= :at AND playlistId = (SELECT id FROM playlists WHERE name = :name)")
    suspend fun removePlaylistSongIfOlder(name: String, songId: Long, at: Long): Int

    @Query("DELETE FROM history WHERE playedAt <= :at")
    suspend fun clearHistoryBefore(at: Long): Int

    @Query("UPDATE playlists SET createdAt = :at WHERE id = :id")
    suspend fun touchPlaylist(id: Long, at: Long)

    @Query("UPDATE playlist_songs SET addedAt = :at WHERE playlistId = :id AND addedAt < :at")
    suspend fun touchPlaylistSongs(id: Long, at: Long)
}

@Database(
    entities = [Song::class, HistoryEntry::class, Playlist::class, PlaylistSong::class, FavoriteArtist::class, Tombstone::class],
    version = 2,
    exportSchema = true,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao

    companion object {
        /** v2: tombstones table for deletion sync. Keeps all existing data. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tombstones` (`kind` TEXT NOT NULL, `key` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`kind`, `key`))")
            }
        }

        fun create(context: Context) =
            Room.databaseBuilder(context, MusicDatabase::class.java, "musiclify.db").addMigrations(MIGRATION_1_2).build()
    }
}
