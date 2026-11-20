//! Library export/import used by device linking. The JSON format is shared with the Android app
//! (android/.../data/Sync.kt). Version 2 carries everything about a library except the audio files
//! themselves and secrets (YouTube cookies never leave the device they were imported on).

use crate::db::{self, Artist, Song};
use crate::error::Result;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};

pub const FORMAT: &str = "musiclify-export";
pub const VERSION: u32 = 3;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Export {
    pub format: String,
    pub version: u32,
    pub profile: Profile,
    #[serde(default)]
    pub favorite_artists: Vec<ExportArtist>,
    #[serde(default)]
    pub songs: Vec<ExportSong>,
    #[serde(default)]
    pub playlists: Vec<PlaylistExport>,
    #[serde(default)]
    pub history: Vec<HistoryExport>,
    /// Deletions (tombstones) so un-likes, removals and unfollows sync too; absent before v3.
    #[serde(default)]
    pub deletions: Vec<Deletion>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportArtist {
    #[serde(flatten)]
    pub artist: Artist,
    #[serde(default)]
    pub added_at: Option<i64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Deletion {
    pub kind: String,
    pub key: String,
    pub at: i64,
}

#[derive(Debug, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    #[serde(default)]
    pub name: String,
    /// Shared song-match setting; absent in v1 exports.
    #[serde(default)]
    pub share_matches: Option<bool>,
}

/// A song plus device-independent extras. `downloaded` means "was offline on the sending device".
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportSong {
    #[serde(flatten)]
    pub song: Song,
    #[serde(default)]
    pub lyrics_json: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistExport {
    pub name: String,
    pub created_at: i64,
    pub song_ids: Vec<i64>,
    /// When each song was added (same order as song_ids); absent in v1.
    #[serde(default)]
    pub added_at: Vec<i64>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryExport {
    pub song_id: i64,
    pub played_at: i64,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ImportSummary {
    pub name: String,
    pub songs: usize,
    pub liked: usize,
    pub playlists: usize,
    pub artists: usize,
    pub history: usize,
    pub lyrics: usize,
    /// Songs that were offline on the other device; the UI offers to download them here.
    pub offline_ids: Vec<i64>,
    /// Items removed here because they were deleted on the other device.
    pub removed: usize,
}

pub fn export(c: &Connection) -> Result<Export> {
    let mut stmt = c.prepare(
        "SELECT id, title, artist, artist_id, album, cover, cover_big, duration_sec, youtube_id, liked, liked_at, downloaded, lyrics_json
         FROM songs",
    )?;
    let songs = stmt
        .query_map([], |r| {
            Ok(ExportSong {
                song: Song {
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
                },
                lyrics_json: r.get(12)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    let mut playlists = Vec::new();
    for p in db::playlists(c)? {
        let mut ps = c.prepare("SELECT song_id, added_at FROM playlist_songs WHERE playlist_id = ?1 ORDER BY added_at")?;
        let rows = ps
            .query_map([p.id], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        playlists.push(PlaylistExport {
            name: p.name,
            created_at: p.created_at,
            song_ids: rows.iter().map(|x| x.0).collect(),
            added_at: rows.iter().map(|x| x.1).collect(),
        });
    }

    let mut hstmt = c.prepare("SELECT song_id, played_at FROM history ORDER BY played_at DESC LIMIT 20000")?;
    let history = hstmt
        .query_map([], |r| Ok(HistoryExport { song_id: r.get(0)?, played_at: r.get(1)? }))?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    Ok(Export {
        format: FORMAT.into(),
        version: VERSION,
        profile: Profile {
            name: db::setting(c, "user_name")?.unwrap_or_default(),
            share_matches: Some(db::setting(c, "share_matches")?.as_deref() != Some("0")),
        },
        favorite_artists: {
            let mut st = c.prepare("SELECT id, name, picture, added_at FROM favorite_artists ORDER BY added_at")?;
            let rows = st.query_map([], |r| Ok(ExportArtist {
                artist: Artist { id: r.get(0)?, name: r.get(1)?, picture: r.get(2)? },
                added_at: r.get(3)?,
            }))?.collect::<rusqlite::Result<Vec<_>>>()?;
            rows
        },
        songs,
        playlists,
        history,
        deletions: db::tombstones(c)?.into_iter().map(|(kind, key, at)| Deletion { kind, key, at }).collect(),
    })
}

/// Merge an export into this device's library (union; nothing local is deleted).
pub fn import(c: &mut Connection, e: &Export) -> Result<ImportSummary> {
    if e.format != FORMAT {
        return Err(crate::error::Error::msg("Not a Musiclify export"));
    }
    let tx = c.transaction()?;
    let was_new = db::setting(&tx, "onboarded")?.as_deref() != Some("1");

    // 1) Deletions first: remove anything here that the other device deleted after it was added,
    //    and remember the deletion so it keeps travelling to other devices.
    let mut removed = 0;
    for d in &e.deletions {
        removed += match d.kind.as_str() {
            "like" => tx.execute(
                "UPDATE songs SET liked = 0, liked_at = NULL WHERE id = ?1 AND liked = 1 AND COALESCE(liked_at, 0) <= ?2",
                params![d.key.parse::<i64>().unwrap_or(-1), d.at],
            )?,
            "artist" => tx.execute(
                "DELETE FROM favorite_artists WHERE id = ?1 AND added_at <= ?2",
                params![d.key.parse::<i64>().unwrap_or(-1), d.at],
            )?,
            "playlist" => tx.execute("DELETE FROM playlists WHERE name = ?1 AND created_at <= ?2", params![d.key, d.at])?,
            "playlist_song" => match d.key.split_once(db::SEP) {
                Some((name, sid)) => tx.execute(
                    "DELETE FROM playlist_songs WHERE song_id = ?2 AND added_at <= ?3
                     AND playlist_id = (SELECT id FROM playlists WHERE name = ?1)",
                    params![name, sid.parse::<i64>().unwrap_or(-1), d.at],
                )?,
                None => 0,
            },
            "history" => tx.execute("DELETE FROM history WHERE played_at <= ?1", [d.at])?,
            _ => 0, // unknown kind from a newer version: keep it for forwarding, ignore here
        };
        db::add_tombstone(&tx, &d.kind, &d.key, d.at)?;
    }
    // Is `item_at` older than a known deletion of (kind, key)? Then it stays deleted.
    let deleted = |kind: &str, key: &str, item_at: i64| -> Result<bool> {
        Ok(db::tombstone(&tx, kind, key)?.is_some_and(|at| at >= item_at))
    };

    // 2) Songs, likes, lyrics, offline list.
    let plain: Vec<Song> = e.songs.iter().map(|s| Song { downloaded: false, ..s.song.clone() }).collect();
    db::insert_songs(&tx, &plain)?;
    let (mut liked, mut lyrics) = (0, 0);
    let mut offline_ids = Vec::new();
    for s in &e.songs {
        let id = s.song.id;
        if let Some(yt) = &s.song.youtube_id {
            tx.execute("UPDATE songs SET youtube_id = ?2 WHERE id = ?1 AND youtube_id IS NULL", params![id, yt])?;
        }
        if s.song.liked {
            let at = s.song.liked_at.unwrap_or_else(db::now_ms);
            if !deleted("like", &id.to_string(), at)? {
                liked += tx.execute(
                    "UPDATE songs SET liked = 1, liked_at = COALESCE(liked_at, ?2) WHERE id = ?1 AND liked = 0",
                    params![id, at],
                )?;
            }
        }
        if let Some(l) = &s.lyrics_json {
            lyrics += tx.execute("UPDATE songs SET lyrics_json = ?2 WHERE id = ?1 AND lyrics_json IS NULL", params![id, l])?;
        }
        if s.song.downloaded {
            let here: Option<i64> = tx.query_row("SELECT downloaded FROM songs WHERE id = ?1", [id], |r| r.get(0)).optional()?;
            if here != Some(1) {
                offline_ids.push(id);
            }
        }
    }

    // 3) Favourite artists: keep the other device's order, after any already here.
    let base: i64 = tx.query_row("SELECT COALESCE(MAX(added_at), 0) FROM favorite_artists", [], |r| r.get(0))?;
    let base = base.max(db::now_ms());
    for (i, a) in e.favorite_artists.iter().enumerate() {
        let at = a.added_at.unwrap_or(0);
        if deleted("artist", &a.artist.id.to_string(), at)? {
            continue;
        }
        tx.execute(
            "INSERT OR IGNORE INTO favorite_artists (id, name, picture, added_at) VALUES (?1, ?2, ?3, ?4)",
            params![a.artist.id, a.artist.name, a.artist.picture, base + i as i64],
        )?;
    }

    // 4) Playlists and their songs.
    for p in &e.playlists {
        if deleted("playlist", &p.name, p.created_at)? {
            continue;
        }
        let existing: Option<i64> = tx.query_row("SELECT id FROM playlists WHERE name = ?1", [&p.name], |r| r.get(0)).optional()?;
        let pid = match existing {
            Some(id) => id,
            None => db::create_playlist(&tx, &p.name, p.created_at)?,
        };
        for (i, sid) in p.song_ids.iter().enumerate() {
            let at = p.added_at.get(i).copied().unwrap_or(p.created_at + i as i64);
            if !deleted("playlist_song", &format!("{}{}{}", p.name, db::SEP, sid), at)? {
                db::add_to_playlist(&tx, pid, *sid, at)?;
            }
        }
    }

    // 5) History (skipping anything older than a "clear history").
    let cleared_at = db::tombstone(&tx, "history", "all")?.unwrap_or(i64::MIN);
    let mut history = 0;
    for h in e.history.iter().filter(|h| h.played_at > cleared_at) {
        history += tx.execute(
            "INSERT INTO history (song_id, played_at) SELECT ?1, ?2
             WHERE NOT EXISTS (SELECT 1 FROM history WHERE song_id = ?1 AND played_at = ?2)",
            params![h.song_id, h.played_at],
        )?;
    }

    let local_name = db::setting(&tx, "user_name")?.unwrap_or_default();
    if local_name.trim().is_empty() && !e.profile.name.trim().is_empty() {
        db::set_setting(&tx, "user_name", Some(e.profile.name.trim()))?;
    }
    // A brand-new device adopts the other device's settings.
    if was_new {
        if let Some(share) = e.profile.share_matches {
            db::set_setting(&tx, "share_matches", Some(if share { "1" } else { "0" }))?;
        }
    }
    let name = db::setting(&tx, "user_name")?.unwrap_or_default();
    tx.commit()?;

    Ok(ImportSummary {
        name,
        songs: e.songs.len(),
        liked,
        playlists: e.playlists.len(),
        artists: e.favorite_artists.len(),
        history,
        lyrics,
        offline_ids,
        removed,
    })
}
