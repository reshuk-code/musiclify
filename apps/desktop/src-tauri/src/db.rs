//! Local SQLite database: same model as the Android app.

use crate::error::Result;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Song {
    pub id: i64,
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub artist_id: i64,
    #[serde(default)]
    pub album: String,
    #[serde(default)]
    pub cover: String,
    #[serde(default)]
    pub cover_big: String,
    #[serde(default)]
    pub duration_sec: i64,
    #[serde(default)]
    pub youtube_id: Option<String>,
    #[serde(default)]
    pub liked: bool,
    #[serde(default)]
    pub liked_at: Option<i64>,
    #[serde(default)]
    pub downloaded: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Artist {
    pub id: i64,
    pub name: String,
    #[serde(default)]
    pub picture: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistSummary {
    pub id: i64,
    pub name: String,
    pub created_at: i64,
    pub song_count: i64,
    pub cover: Option<String>,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn open(path: &Path) -> Result<Connection> {
    let conn = Connection::open(path)?;
    conn.execute_batch(
        r#"
        PRAGMA journal_mode = WAL;
        PRAGMA foreign_keys = ON;
        CREATE TABLE IF NOT EXISTS songs (
            id INTEGER PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, artist_id INTEGER NOT NULL DEFAULT 0,
            album TEXT NOT NULL DEFAULT '', cover TEXT NOT NULL DEFAULT '', cover_big TEXT NOT NULL DEFAULT '',
            duration_sec INTEGER NOT NULL DEFAULT 0, youtube_id TEXT, liked INTEGER NOT NULL DEFAULT 0, liked_at INTEGER,
            downloaded INTEGER NOT NULL DEFAULT 0, file_ext TEXT, lyrics_json TEXT
        );
        CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT, song_id INTEGER NOT NULL, played_at INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS history_song ON history(song_id);
        CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS playlist_songs (
            playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
            song_id INTEGER NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (playlist_id, song_id)
        );
        CREATE TABLE IF NOT EXISTS favorite_artists (id INTEGER PRIMARY KEY, name TEXT NOT NULL, picture TEXT NOT NULL DEFAULT '', added_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS tombstones (kind TEXT NOT NULL, key TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (kind, key));
        CREATE TABLE IF NOT EXISTS paired_devices (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL, created_at INTEGER NOT NULL, last_sync INTEGER
        );
        "#,
    )?;
    Ok(conn)
}

const SONG_COLS: &str = "id, title, artist, artist_id, album, cover, cover_big, duration_sec, youtube_id, liked, liked_at, downloaded";

fn row_song(r: &rusqlite::Row) -> rusqlite::Result<Song> {
    Ok(Song {
        id: r.get(0)?,
        title: r.get(1)?,
        artist: r.get(2)?,
        artist_id: r.get(3)?,
        album: r.get(4)?,
        cover: r.get(5)?,
        cover_big: r.get(6)?,
        duration_sec: r.get(7)?,
        youtube_id: r.get(8)?,
        liked: r.get::<_, i64>(9)? != 0,
        liked_at: r.get(10)?,
        downloaded: r.get::<_, i64>(11)? != 0,
    })
}

fn query_songs(c: &Connection, sql: &str, p: impl rusqlite::Params) -> Result<Vec<Song>> {
    let mut stmt = c.prepare(sql)?;
    let rows = stmt.query_map(p, row_song)?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

// ---------------- settings ----------------

pub fn setting(c: &Connection, key: &str) -> Result<Option<String>> {
    Ok(c.query_row("SELECT value FROM settings WHERE key = ?1", [key], |r| r.get(0)).optional()?)
}

pub fn set_setting(c: &Connection, key: &str, value: Option<&str>) -> Result<()> {
    match value {
        Some(v) => c.execute("INSERT INTO settings(key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = ?2", params![key, v])?,
        None => c.execute("DELETE FROM settings WHERE key = ?1", [key])?,
    };
    Ok(())
}

// ---------------- tombstones (deletion sync) ----------------
//
// Every deletion leaves a (kind, key, at) record so it can travel to linked devices.
// Kinds: like:<songId> · playlist:<name> · playlist_song:<name>\x1f<songId> · artist:<id> · history:all
// A deletion only removes an item that is older than it; re-adding later wins (newest change wins).

pub const SEP: char = '\u{1f}';

pub fn add_tombstone(c: &Connection, kind: &str, key: &str, at: i64) -> Result<()> {
    c.execute(
        "INSERT INTO tombstones (kind, key, at) VALUES (?1, ?2, ?3)
         ON CONFLICT(kind, key) DO UPDATE SET at = MAX(at, excluded.at)",
        params![kind, key, at],
    )?;
    Ok(())
}

pub fn tombstone(c: &Connection, kind: &str, key: &str) -> Result<Option<i64>> {
    Ok(c.query_row("SELECT at FROM tombstones WHERE kind = ?1 AND key = ?2", params![kind, key], |r| r.get(0)).optional()?)
}

pub fn tombstones(c: &Connection) -> Result<Vec<(String, String, i64)>> {
    let mut stmt = c.prepare("SELECT kind, key, at FROM tombstones")?;
    let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

fn playlist_name(c: &Connection, id: i64) -> Result<Option<String>> {
    Ok(c.query_row("SELECT name FROM playlists WHERE id = ?1", [id], |r| r.get(0)).optional()?)
}

// ---------------- songs ----------------

/// Insert songs that aren't known yet; never overwrites likes/downloads/matches.
pub fn insert_songs(c: &Connection, songs: &[Song]) -> Result<()> {
    let mut stmt = c.prepare(
        "INSERT OR IGNORE INTO songs (id, title, artist, artist_id, album, cover, cover_big, duration_sec)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
    )?;
    for s in songs {
        stmt.execute(params![s.id, s.title, s.artist, s.artist_id, s.album, s.cover, s.cover_big, s.duration_sec])?;
    }
    Ok(())
}

pub fn song(c: &Connection, id: i64) -> Result<Option<Song>> {
    Ok(query_songs(c, &format!("SELECT {SONG_COLS} FROM songs WHERE id = ?1"), [id])?.into_iter().next())
}

/// The original album's name and cover, found after the song was saved (greatest-hits fix).
pub fn set_album_art(c: &Connection, id: i64, album: &str, cover: &str, cover_big: &str) -> Result<()> {
    c.execute("UPDATE songs SET album = ?2, cover = ?3, cover_big = ?4 WHERE id = ?1", params![id, album, cover, cover_big])?;
    Ok(())
}

pub fn set_youtube_id(c: &Connection, id: i64, yt: &str) -> Result<()> {
    c.execute("UPDATE songs SET youtube_id = ?2 WHERE id = ?1", params![id, yt])?;
    Ok(())
}

pub fn set_downloaded(c: &Connection, id: i64, ext: Option<&str>) -> Result<()> {
    c.execute("UPDATE songs SET downloaded = ?2, file_ext = ?3 WHERE id = ?1", params![id, ext.is_some() as i64, ext])?;
    Ok(())
}

pub fn file_ext(c: &Connection, id: i64) -> Result<Option<String>> {
    Ok(c.query_row("SELECT file_ext FROM songs WHERE id = ?1 AND downloaded = 1", [id], |r| r.get(0)).optional()?.flatten())
}

pub fn clear_downloaded(c: &Connection) -> Result<()> {
    c.execute("UPDATE songs SET downloaded = 0, file_ext = NULL", [])?;
    Ok(())
}

pub fn toggle_like(c: &Connection, id: i64) -> Result<bool> {
    let liked: i64 = c.query_row("SELECT liked FROM songs WHERE id = ?1", [id], |r| r.get(0))?;
    let now_liked = liked == 0;
    c.execute(
        "UPDATE songs SET liked = ?2, liked_at = ?3 WHERE id = ?1",
        params![id, now_liked as i64, if now_liked { Some(now_ms()) } else { None }],
    )?;
    if !now_liked {
        add_tombstone(c, "like", &id.to_string(), now_ms())?;
    }
    Ok(now_liked)
}

pub fn liked(c: &Connection) -> Result<Vec<Song>> {
    query_songs(c, &format!("SELECT {SONG_COLS} FROM songs WHERE liked = 1 ORDER BY liked_at DESC"), [])
}

pub fn downloads(c: &Connection) -> Result<Vec<Song>> {
    query_songs(c, &format!("SELECT {SONG_COLS} FROM songs WHERE downloaded = 1 ORDER BY title COLLATE NOCASE"), [])
}

pub fn lyrics(c: &Connection, id: i64) -> Result<Option<String>> {
    Ok(c.query_row("SELECT lyrics_json FROM songs WHERE id = ?1", [id], |r| r.get(0)).optional()?.flatten())
}

pub fn set_lyrics(c: &Connection, id: i64, json: &str) -> Result<()> {
    c.execute("UPDATE songs SET lyrics_json = ?2 WHERE id = ?1", params![id, json])?;
    Ok(())
}

// ---------------- history ----------------

pub fn add_history(c: &Connection, song_id: i64, at: i64) -> Result<()> {
    c.execute("INSERT INTO history (song_id, played_at) VALUES (?1, ?2)", params![song_id, at])?;
    Ok(())
}

pub fn recent(c: &Connection, limit: i64) -> Result<Vec<Song>> {
    query_songs(
        c,
        &format!(
            "SELECT {} FROM songs s JOIN (SELECT song_id, MAX(played_at) AS last_played FROM history GROUP BY song_id) h
             ON h.song_id = s.id ORDER BY h.last_played DESC LIMIT ?1",
            SONG_COLS.split(", ").map(|c| format!("s.{c}")).collect::<Vec<_>>().join(", ")
        ),
        [limit],
    )
}

pub fn recent_song_ids(c: &Connection, limit: i64) -> Result<Vec<i64>> {
    let mut stmt = c.prepare("SELECT song_id FROM history ORDER BY played_at DESC LIMIT ?1")?;
    let rows = stmt.query_map([limit], |r| r.get(0))?.collect::<rusqlite::Result<Vec<i64>>>()?;
    Ok(rows)
}

// ---------------- listening stats ----------------

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SongPlays {
    pub song: Song,
    pub plays: i64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistPlays {
    pub artist_id: i64,
    pub artist: String,
    pub plays: i64,
    pub cover: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Stats {
    pub minutes: i64,
    pub plays: i64,
    pub song_count: i64,
    pub top_songs: Vec<SongPlays>,
    pub top_artists: Vec<ArtistPlays>,
}

fn prefixed_cols(prefix: &str) -> String {
    SONG_COLS.split(", ").map(|c| format!("{prefix}.{c}")).collect::<Vec<_>>().join(", ")
}

/// Plays since `since` (ms): a play is counted each time a song starts.
pub fn stats(c: &Connection, since: i64) -> Result<Stats> {
    let seconds: i64 = c.query_row(
        "SELECT COALESCE(SUM(s.duration_sec), 0) FROM history h JOIN songs s ON s.id = h.song_id WHERE h.played_at >= ?1",
        [since], |r| r.get(0))?;
    let plays: i64 = c.query_row("SELECT COUNT(*) FROM history WHERE played_at >= ?1", [since], |r| r.get(0))?;
    let song_count: i64 = c.query_row("SELECT COUNT(DISTINCT song_id) FROM history WHERE played_at >= ?1", [since], |r| r.get(0))?;

    let mut stmt = c.prepare(&format!(
        "SELECT {}, COUNT(h.id) AS plays FROM history h JOIN songs s ON s.id = h.song_id
         WHERE h.played_at >= ?1 GROUP BY s.id ORDER BY plays DESC, MAX(h.played_at) DESC LIMIT 10",
        prefixed_cols("s")))?;
    let top_songs = stmt
        .query_map([since], |r| Ok(SongPlays { song: row_song(r)?, plays: r.get(12)? }))?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    let mut stmt = c.prepare(
        "SELECT s.artist_id, s.artist, COUNT(h.id) AS plays, MAX(s.cover) FROM history h JOIN songs s ON s.id = h.song_id
         WHERE h.played_at >= ?1 GROUP BY s.artist_id ORDER BY plays DESC LIMIT 5")?;
    let top_artists = stmt
        .query_map([since], |r| Ok(ArtistPlays { artist_id: r.get(0)?, artist: r.get(1)?, plays: r.get(2)?, cover: r.get(3)? }))?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    Ok(Stats { minutes: seconds / 60, plays, song_count, top_songs, top_artists })
}

// ---------------- storage ----------------

/// Downloads with their file type and how much they've been played, least recently played first.
pub fn download_usage(c: &Connection) -> Result<Vec<(Song, Option<String>, Option<i64>, i64)>> {
    let mut stmt = c.prepare(&format!(
        "SELECT {}, s.file_ext, MAX(h.played_at) AS last_played, COUNT(h.id)
         FROM songs s LEFT JOIN history h ON h.song_id = s.id
         WHERE s.downloaded = 1 GROUP BY s.id ORDER BY last_played IS NOT NULL, last_played ASC",
        prefixed_cols("s")))?;
    let rows = stmt
        .query_map([], |r| Ok((row_song(r)?, r.get(12)?, r.get(13)?, r.get(14)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn clear_history(c: &Connection) -> Result<()> {
    c.execute("DELETE FROM history", [])?;
    add_tombstone(c, "history", "all", now_ms())
}

// ---------------- playlists ----------------

pub fn playlists(c: &Connection) -> Result<Vec<PlaylistSummary>> {
    let mut stmt = c.prepare(
        "SELECT p.id, p.name, p.created_at, COUNT(ps.song_id),
                (SELECT s.cover FROM playlist_songs x JOIN songs s ON s.id = x.song_id WHERE x.playlist_id = p.id ORDER BY x.added_at LIMIT 1)
         FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id GROUP BY p.id ORDER BY p.created_at DESC",
    )?;
    let rows = stmt
        .query_map([], |r| {
            Ok(PlaylistSummary { id: r.get(0)?, name: r.get(1)?, created_at: r.get(2)?, song_count: r.get(3)?, cover: r.get(4)? })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn create_playlist(c: &Connection, name: &str, created_at: i64) -> Result<i64> {
    c.execute("INSERT INTO playlists (name, created_at) VALUES (?1, ?2)", params![name, created_at])?;
    Ok(c.last_insert_rowid())
}

pub fn rename_playlist(c: &Connection, id: i64, name: &str) -> Result<()> {
    // Linked devices know playlists by name: the old name is deleted, the new one is created.
    if let Some(old) = playlist_name(c, id)? {
        if old != name {
            add_tombstone(c, "playlist", &old, now_ms())?;
            c.execute("UPDATE playlists SET name = ?2, created_at = ?3 WHERE id = ?1", params![id, name, now_ms()])?;
            c.execute("UPDATE playlist_songs SET added_at = ?2 WHERE playlist_id = ?1 AND added_at < ?2", params![id, now_ms()])?;
        }
    }
    Ok(())
}

pub fn delete_playlist(c: &Connection, id: i64) -> Result<()> {
    if let Some(name) = playlist_name(c, id)? {
        add_tombstone(c, "playlist", &name, now_ms())?;
    }
    c.execute("DELETE FROM playlists WHERE id = ?1", [id])?;
    Ok(())
}

pub fn add_to_playlist(c: &Connection, pid: i64, song_id: i64, at: i64) -> Result<()> {
    c.execute("INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id, added_at) VALUES (?1, ?2, ?3)", params![pid, song_id, at])?;
    Ok(())
}

pub fn remove_from_playlist(c: &Connection, pid: i64, song_id: i64) -> Result<()> {
    c.execute("DELETE FROM playlist_songs WHERE playlist_id = ?1 AND song_id = ?2", params![pid, song_id])?;
    if let Some(name) = playlist_name(c, pid)? {
        add_tombstone(c, "playlist_song", &format!("{name}{SEP}{song_id}"), now_ms())?;
    }
    Ok(())
}

pub fn playlist_songs(c: &Connection, pid: i64) -> Result<Vec<Song>> {
    query_songs(
        c,
        &format!(
            "SELECT {} FROM songs s JOIN playlist_songs ps ON ps.song_id = s.id WHERE ps.playlist_id = ?1 ORDER BY ps.added_at",
            SONG_COLS.split(", ").map(|c| format!("s.{c}")).collect::<Vec<_>>().join(", ")
        ),
        [pid],
    )
}

// ---------------- paired devices (sync) ----------------

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    pub created_at: i64,
    pub last_sync: Option<i64>,
}

pub fn add_paired(c: &Connection, id: &str, name: &str, key_b64: &str) -> Result<()> {
    c.execute(
        "INSERT OR REPLACE INTO paired_devices (id, name, key, created_at, last_sync) VALUES (?1, ?2, ?3, ?4, NULL)",
        params![id, name, key_b64, now_ms()],
    )?;
    Ok(())
}

pub fn paired_key(c: &Connection, id: &str) -> Result<Option<(String, String)>> {
    Ok(c.query_row("SELECT name, key FROM paired_devices WHERE id = ?1", [id], |r| Ok((r.get(0)?, r.get(1)?))).optional()?)
}

pub fn touch_paired(c: &Connection, id: &str) -> Result<()> {
    c.execute("UPDATE paired_devices SET last_sync = ?2 WHERE id = ?1", params![id, now_ms()])?;
    Ok(())
}

pub fn paired_devices(c: &Connection) -> Result<Vec<PairedDevice>> {
    let mut stmt = c.prepare("SELECT id, name, created_at, last_sync FROM paired_devices ORDER BY created_at")?;
    let rows = stmt
        .query_map([], |r| Ok(PairedDevice { id: r.get(0)?, name: r.get(1)?, created_at: r.get(2)?, last_sync: r.get(3)? }))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn remove_paired(c: &Connection, id: &str) -> Result<()> {
    c.execute("DELETE FROM paired_devices WHERE id = ?1", [id])?;
    Ok(())
}

// ---------------- favourite artists ----------------

pub fn favorite_artists(c: &Connection) -> Result<Vec<Artist>> {
    let mut stmt = c.prepare("SELECT id, name, picture FROM favorite_artists ORDER BY added_at")?;
    let rows = stmt
        .query_map([], |r| Ok(Artist { id: r.get(0)?, name: r.get(1)?, picture: r.get(2)? }))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn set_favorite_artists(c: &Connection, artists: &[Artist]) -> Result<()> {
    let now = now_ms();
    for old in favorite_artists(c)? {
        if !artists.iter().any(|a| a.id == old.id) {
            add_tombstone(c, "artist", &old.id.to_string(), now)?;
        }
    }
    c.execute("DELETE FROM favorite_artists", [])?;
    for (i, a) in artists.iter().enumerate() {
        c.execute(
            "INSERT OR REPLACE INTO favorite_artists (id, name, picture, added_at) VALUES (?1, ?2, ?3, ?4)",
            params![a.id, a.name, a.picture, now + i as i64],
        )?;
    }
    Ok(())
}

pub fn toggle_favorite(c: &Connection, a: &Artist) -> Result<bool> {
    let exists: bool = c.query_row("SELECT COUNT(*) FROM favorite_artists WHERE id = ?1", [a.id], |r| r.get::<_, i64>(0))? > 0;
    if exists {
        c.execute("DELETE FROM favorite_artists WHERE id = ?1", [a.id])?;
        add_tombstone(c, "artist", &a.id.to_string(), now_ms())?;
    } else {
        c.execute(
            "INSERT INTO favorite_artists (id, name, picture, added_at) VALUES (?1, ?2, ?3, ?4)",
            params![a.id, a.name, a.picture, now_ms()],
        )?;
    }
    Ok(!exists)
}
