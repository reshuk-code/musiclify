mod background;
mod crypto;
mod db;
mod error;
mod link;
mod net;
mod sync;
mod syncsrv;
mod youtube;

use db::{Artist, PlaylistSummary, Song};
use error::{Error, Result};
use rusqlite::Connection;
use serde::Serialize;
use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::http::{Response, StatusCode, header};
use tauri::{AppHandle, Emitter, Manager, State};

pub struct AppState {
    db: Mutex<Connection>,
    key: [u8; 16],
    library: PathBuf,
    cookies: PathBuf,
    http: reqwest::Client,
    /// In-flight downloads; waiters get the result when it finishes.
    downloading: Mutex<HashMap<i64, tokio::sync::watch::Receiver<Option<std::result::Result<(), String>>>>>,
    link: Mutex<Option<link::LinkSession>>,
    /// TCP port of the sync service for paired phones.
    sync_port: Mutex<u16>,
}

type S<'a> = State<'a, AppState>;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Profile {
    name: String,
    onboarded: bool,
    share_matches: bool,
    has_cookies: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PlaySource {
    url: String,
    local: bool,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct DownloadProgress {
    id: i64,
    progress: f32,
    done: bool,
    error: Option<String>,
}

fn local_url(id: i64) -> String {
    if cfg!(windows) { format!("http://musiclify.localhost/song/{id}") } else { format!("musiclify://localhost/song/{id}") }
}

// ---------------------------------------------------------------- profile & settings

#[tauri::command]
fn get_profile(s: S) -> Result<Profile> {
    let c = s.db.lock().unwrap();
    Ok(Profile {
        name: db::setting(&c, "user_name")?.unwrap_or_default(),
        onboarded: db::setting(&c, "onboarded")?.as_deref() == Some("1"),
        share_matches: db::setting(&c, "share_matches")?.as_deref() != Some("0"),
        has_cookies: s.cookies.exists(),
    })
}

#[tauri::command]
fn set_profile(s: S, name: Option<String>, onboarded: Option<bool>) -> Result<()> {
    let c = s.db.lock().unwrap();
    if let Some(n) = name {
        db::set_setting(&c, "user_name", Some(n.trim()))?;
    }
    if let Some(o) = onboarded {
        db::set_setting(&c, "onboarded", Some(if o { "1" } else { "0" }))?;
    }
    Ok(())
}

#[tauri::command]
fn set_share_matches(s: S, enabled: bool) -> Result<()> {
    db::set_setting(&s.db.lock().unwrap(), "share_matches", Some(if enabled { "1" } else { "0" }))
}

fn share_matches(s: &AppState) -> bool {
    db::setting(&s.db.lock().unwrap(), "share_matches").ok().flatten().as_deref() != Some("0")
}

/// Save (or remove with None) a Netscape cookies.txt for YouTube.
#[tauri::command]
fn set_cookies(s: S, text: Option<String>) -> Result<()> {
    match text {
        None => {
            let _ = std::fs::remove_file(&s.cookies);
            Ok(())
        }
        Some(t) if t.contains("youtube.com") => Ok(std::fs::write(&s.cookies, t)?),
        Some(_) => Err(Error::msg("No youtube.com cookies found in that file.")),
    }
}

#[tauri::command]
async fn fetch_json(s: S<'_>, url: String) -> Result<serde_json::Value> {
    net::fetch_json(&s.http, &url).await
}

// ---------------------------------------------------------------- favourite artists

#[tauri::command]
fn favorites_get(s: S) -> Result<Vec<Artist>> {
    db::favorite_artists(&s.db.lock().unwrap())
}

#[tauri::command]
fn favorites_set(s: S, artists: Vec<Artist>) -> Result<()> {
    db::set_favorite_artists(&s.db.lock().unwrap(), &artists)
}

#[tauri::command]
fn favorite_toggle(s: S, artist: Artist) -> Result<bool> {
    db::toggle_favorite(&s.db.lock().unwrap(), &artist)
}

// ---------------------------------------------------------------- songs, likes, history

#[tauri::command]
fn songs_insert(s: S, songs: Vec<Song>) -> Result<()> {
    db::insert_songs(&s.db.lock().unwrap(), &songs)
}

#[tauri::command]
fn like_toggle(s: S, song: Song) -> Result<bool> {
    let c = s.db.lock().unwrap();
    db::insert_songs(&c, std::slice::from_ref(&song))?;
    db::toggle_like(&c, song.id)
}

#[tauri::command]
fn liked_list(s: S) -> Result<Vec<Song>> {
    db::liked(&s.db.lock().unwrap())
}

#[tauri::command]
fn downloads_list(s: S) -> Result<Vec<Song>> {
    db::downloads(&s.db.lock().unwrap())
}

#[tauri::command]
fn recent_list(s: S, limit: i64) -> Result<Vec<Song>> {
    db::recent(&s.db.lock().unwrap(), limit)
}

#[tauri::command]
fn history_add(s: S, song_id: i64) -> Result<()> {
    db::add_history(&s.db.lock().unwrap(), song_id, db::now_ms())
}

#[tauri::command]
fn recent_ids(s: S, limit: i64) -> Result<Vec<i64>> {
    db::recent_song_ids(&s.db.lock().unwrap(), limit)
}

#[tauri::command]
fn song_set_album_art(s: S, id: i64, album: String, cover: String, cover_big: String) -> Result<()> {
    db::set_album_art(&s.db.lock().unwrap(), id, &album, &cover, &cover_big)
}

#[tauri::command]
fn stats_get(s: S, since: i64) -> Result<db::Stats> {
    db::stats(&s.db.lock().unwrap(), since)
}

#[tauri::command]
fn history_clear(s: S) -> Result<()> {
    db::clear_history(&s.db.lock().unwrap())
}

#[tauri::command]
fn lyrics_get(s: S, id: i64) -> Result<Option<String>> {
    db::lyrics(&s.db.lock().unwrap(), id)
}

#[tauri::command]
fn lyrics_set(s: S, id: i64, json: String) -> Result<()> {
    db::set_lyrics(&s.db.lock().unwrap(), id, &json)
}

// ---------------------------------------------------------------- playlists

#[tauri::command]
fn playlists_list(s: S) -> Result<Vec<PlaylistSummary>> {
    db::playlists(&s.db.lock().unwrap())
}

#[tauri::command]
fn playlist_create(s: S, name: String, song: Option<Song>) -> Result<i64> {
    let c = s.db.lock().unwrap();
    let id = db::create_playlist(&c, name.trim(), db::now_ms())?;
    if let Some(song) = song {
        db::insert_songs(&c, std::slice::from_ref(&song))?;
        db::add_to_playlist(&c, id, song.id, db::now_ms())?;
    }
    Ok(id)
}

/// A new playlist filled with songs in the given order (playlist import).
#[tauri::command]
fn playlist_create_with(s: S, name: String, songs: Vec<Song>) -> Result<i64> {
    let c = s.db.lock().unwrap();
    let now = db::now_ms();
    let id = db::create_playlist(&c, name.trim(), now)?;
    db::insert_songs(&c, &songs)?;
    for (i, song) in songs.iter().enumerate() {
        db::add_to_playlist(&c, id, song.id, now + i as i64)?;
    }
    Ok(id)
}

#[tauri::command]
fn playlist_add(s: S, playlist_id: i64, song: Song) -> Result<()> {
    let c = s.db.lock().unwrap();
    db::insert_songs(&c, std::slice::from_ref(&song))?;
    db::add_to_playlist(&c, playlist_id, song.id, db::now_ms())
}

#[tauri::command]
fn playlist_remove(s: S, playlist_id: i64, song_id: i64) -> Result<()> {
    db::remove_from_playlist(&s.db.lock().unwrap(), playlist_id, song_id)
}

#[tauri::command]
fn playlist_songs(s: S, playlist_id: i64) -> Result<Vec<Song>> {
    db::playlist_songs(&s.db.lock().unwrap(), playlist_id)
}

#[tauri::command]
fn playlist_rename(s: S, playlist_id: i64, name: String) -> Result<()> {
    db::rename_playlist(&s.db.lock().unwrap(), playlist_id, name.trim())
}

#[tauri::command]
fn playlist_delete(s: S, playlist_id: i64) -> Result<()> {
    db::delete_playlist(&s.db.lock().unwrap(), playlist_id)
}

// ---------------------------------------------------------------- playback

/// Pick the YouTube video for a song: our own earlier match, else the shared cache, else a search.
/// Returns (video id, came from shared cache, video duration if already known).
async fn pick_video(app: &AppHandle, s: &AppState, song: &Song) -> Result<(String, bool, i64)> {
    if let Some(yt) = db::song(&s.db.lock().unwrap(), song.id)?.and_then(|x| x.youtube_id) {
        return Ok((yt, false, 0));
    }
    if share_matches(s) {
        if let Some(m) = net::match_lookup(&s.http, song.id).await {
            return Ok((m.youtube_id, true, 0));
        }
    }
    let (id, dur) = youtube::search(app, Some(s.cookies.as_path()), song).await?;
    Ok((id, false, dur))
}

fn is_local(s: &AppState, id: i64) -> Result<bool> {
    let ext = db::file_ext(&s.db.lock().unwrap(), id)?;
    match ext {
        Some(ext) if youtube::song_path(&s.library, id, &ext).exists() => Ok(true),
        Some(_) => {
            db::set_downloaded(&s.db.lock().unwrap(), id, None)?; // file was removed
            Ok(false)
        }
        None => Ok(false),
    }
}

fn emit_progress(app: &AppHandle, id: i64, progress: f32, done: bool, error: Option<String>) {
    let _ = app.emit("download-progress", DownloadProgress { id, progress, done, error });
}

/// Make sure a song is in the encrypted library. Concurrent calls for the same song
/// (e.g. a click while it's being prefetched) share one download.
async fn ensure_downloaded(app: &AppHandle, song: &Song) -> Result<()> {
    let s = app.state::<AppState>();
    if is_local(&s, song.id)? {
        return Ok(());
    }
    let in_flight = s.downloading.lock().unwrap().get(&song.id).cloned();
    if let Some(mut rx) = in_flight {
        loop {
            if let Some(result) = rx.borrow().clone() {
                return result.map_err(Error::msg);
            }
            if rx.changed().await.is_err() {
                break;
            }
        }
        return if is_local(&s, song.id)? { Ok(()) } else { Err(Error::msg("Download failed")) };
    }

    let (tx, rx) = tokio::sync::watch::channel(None);
    s.downloading.lock().unwrap().insert(song.id, rx);
    let result = download_now(app, &s, song).await;
    s.downloading.lock().unwrap().remove(&song.id);
    let _ = tx.send(Some(result.as_ref().map(|_| ()).map_err(|e| e.to_string())));
    emit_progress(app, song.id, 1.0, true, result.as_ref().err().map(|e| e.to_string()));
    result
}

async fn download_now(app: &AppHandle, s: &AppState, song: &Song) -> Result<()> {
    db::insert_songs(&s.db.lock().unwrap(), std::slice::from_ref(song))?;
    let cookies = Some(s.cookies.as_path());
    let (mut video, from_shared, mut known_dur) = pick_video(app, s, song).await?;
    let progress = |p: f32| emit_progress(app, song.id, p, false, None);

    let mut got = youtube::download(app, cookies, &s.library, &video, &s.key, song.id, song.duration_sec, progress).await?;

    // A shared match must agree with the song's length; otherwise ignore it and search ourselves.
    if from_shared && !youtube::duration_matches(got.duration, song.duration_sec) {
        let _ = std::fs::remove_file(youtube::song_path(&s.library, song.id, &got.ext));
        let (id, dur) = youtube::search(app, cookies, song).await?;
        video = id;
        known_dur = dur;
        got = youtube::download(app, cookies, &s.library, &video, &s.key, song.id, song.duration_sec, progress).await?;
    }

    {
        let c = s.db.lock().unwrap();
        db::set_youtube_id(&c, song.id, &video)?;
        db::set_downloaded(&c, song.id, Some(&got.ext))?;
    }
    let duration = if got.duration > 0 { got.duration } else { known_dur };
    if share_matches(s) && youtube::duration_matches(duration, song.duration_sec) {
        net::match_submit(s.http.clone(), song.id, video, duration);
    }
    Ok(())
}

/// Get a playable URL for a song: downloads it (encrypted) first if needed, then serves it
/// from the local library through the decrypting musiclify:// protocol.
#[tauri::command]
async fn prepare_song(app: AppHandle, song: Song) -> Result<PlaySource> {
    ensure_downloaded(&app, &song).await?;
    Ok(PlaySource { url: local_url(song.id), local: true })
}

/// Download several songs (e.g. ones that were offline on a linked phone) one after another,
/// so YouTube sees a gentle, steady pace rather than a burst.
#[tauri::command]
fn download_many(app: AppHandle, ids: Vec<i64>) -> Result<usize> {
    let count = ids.len();
    tauri::async_runtime::spawn(async move {
        for id in ids {
            let song = { db::song(&app.state::<AppState>().db.lock().unwrap(), id) };
            if let Ok(Some(song)) = song {
                let _ = ensure_downloaded(&app, &song).await;
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            }
        }
    });
    Ok(count)
}

/// Download for offline use without playing (e.g. prefetching the next song).
#[tauri::command]
async fn download_song(app: AppHandle, song: Song) -> Result<()> {
    tauri::async_runtime::spawn(async move {
        let _ = ensure_downloaded(&app, &song).await;
    });
    Ok(())
}

#[tauri::command]
fn remove_download(s: S, id: i64) -> Result<()> {
    let c = s.db.lock().unwrap();
    if let Some(ext) = db::file_ext(&c, id)? {
        let _ = std::fs::remove_file(youtube::song_path(&s.library, id, &ext));
    }
    db::set_downloaded(&c, id, None)
}

#[tauri::command]
fn clear_downloads(s: S) -> Result<()> {
    for entry in std::fs::read_dir(&s.library)?.flatten() {
        let _ = std::fs::remove_file(entry.path());
    }
    db::clear_downloaded(&s.db.lock().unwrap())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DownloadItem {
    song: Song,
    bytes: u64,
    last_played: Option<i64>,
    plays: i64,
}

/// Downloads with their size on disk, least recently played first (for "free up space").
#[tauri::command]
fn downloads_usage(s: S) -> Result<Vec<DownloadItem>> {
    let rows = db::download_usage(&s.db.lock().unwrap())?;
    Ok(rows
        .into_iter()
        .map(|(song, ext, last_played, plays)| {
            let bytes = ext
                .and_then(|e| std::fs::metadata(youtube::song_path(&s.library, song.id, &e)).ok())
                .map(|m| m.len())
                .unwrap_or(0);
            DownloadItem { song, bytes, last_played, plays }
        })
        .collect())
}

#[tauri::command]
fn remove_downloads(s: S, ids: Vec<i64>) -> Result<()> {
    let c = s.db.lock().unwrap();
    for id in ids {
        if let Some(ext) = db::file_ext(&c, id)? {
            let _ = std::fs::remove_file(youtube::song_path(&s.library, id, &ext));
        }
        db::set_downloaded(&c, id, None)?;
    }
    Ok(())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DiskSpace {
    free: u64,
    total: u64,
}

/// Free and total space on the drive that holds the library.
#[tauri::command]
fn disk_space(s: S) -> Result<DiskSpace> {
    Ok(DiskSpace { free: fs2::available_space(&s.library)?, total: fs2::total_space(&s.library)? })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ExternalPlaylist {
    name: String,
    entries: Vec<youtube::PlaylistEntry>,
}

#[tauri::command]
async fn youtube_playlist(app: AppHandle, list_id: String) -> Result<ExternalPlaylist> {
    let cookies = app.state::<AppState>().cookies.clone();
    let (name, entries) = youtube::playlist(&app, Some(cookies.as_path()), &list_id).await?;
    Ok(ExternalPlaylist { name, entries })
}

#[tauri::command]
async fn spotify_embed(s: S<'_>, kind: String, id: String) -> Result<String> {
    net::spotify_embed(&s.http, &kind, &id).await
}

/// Save a share card (PNG, base64) to the Downloads folder; returns where it went.
#[tauri::command]
fn save_image(app: AppHandle, name: String, png_base64: String) -> Result<String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD.decode(png_base64.as_bytes()).map_err(|_| Error::msg("bad image"))?;
    let safe: String = name.chars().map(|c| if c.is_alphanumeric() || c == '-' || c == ' ' { c } else { '_' }).collect();
    let dir = app.path().download_dir().or_else(|_| app.path().picture_dir()).map_err(|e| Error::msg(e.to_string()))?;
    let path = dir.join(format!("{}.png", safe.trim()));
    std::fs::write(&path, bytes)?;
    Ok(path.to_string_lossy().into_owned())
}

#[tauri::command]
fn storage_bytes(s: S) -> Result<u64> {
    Ok(std::fs::read_dir(&s.library)?.flatten().filter_map(|e| e.metadata().ok()).map(|m| m.len()).sum())
}

// ---------------------------------------------------------------- device linking

#[tauri::command]
fn link_start(app: AppHandle) -> Result<link::LinkInfo> {
    link::start(&app)
}

#[tauri::command]
fn link_stop(app: AppHandle) {
    link::stop(&app);
}

#[tauri::command]
fn paired_list(s: S) -> Result<Vec<db::PairedDevice>> {
    db::paired_devices(&s.db.lock().unwrap())
}

#[tauri::command]
fn unpair(s: S, id: String) -> Result<()> {
    db::remove_paired(&s.db.lock().unwrap(), &id)
}

// ---------------------------------------------------------------- musiclify:// protocol

/// Serve decrypted bytes of a downloaded song, honouring HTTP Range so seeking works.
fn serve_song(app: &AppHandle, request: &tauri::http::Request<Vec<u8>>) -> Response<Vec<u8>> {
    let not_found = || Response::builder().status(StatusCode::NOT_FOUND).body(Vec::new()).unwrap();
    let s = app.state::<AppState>();
    let Some(id) = request.uri().path().strip_prefix("/song/").and_then(|v| v.parse::<i64>().ok()) else {
        return not_found();
    };
    let Ok(Some(ext)) = db::file_ext(&s.db.lock().unwrap(), id) else { return not_found() };
    let path = youtube::song_path(&s.library, id, &ext);
    let Ok(mut file) = std::fs::File::open(&path) else { return not_found() };
    let total = file.metadata().map(|m| m.len()).unwrap_or(0);

    const MAX_CHUNK: u64 = 2 * 1024 * 1024;
    let (start, end) = request
        .headers()
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("bytes="))
        .and_then(|v| {
            let (a, b) = v.split_once('-')?;
            let start: u64 = a.parse().ok()?;
            let end: u64 = if b.is_empty() { total.saturating_sub(1) } else { b.parse().ok()? };
            Some((start, end))
        })
        .unwrap_or((0, total.saturating_sub(1)));
    let end = end.min(total.saturating_sub(1)).min(start + MAX_CHUNK - 1);
    if start >= total {
        return Response::builder()
            .status(StatusCode::RANGE_NOT_SATISFIABLE)
            .header(header::CONTENT_RANGE, format!("bytes */{total}"))
            .body(Vec::new())
            .unwrap();
    }

    let mut buf = vec![0u8; (end - start + 1) as usize];
    if file.seek(SeekFrom::Start(start)).is_err() || file.read_exact(&mut buf).is_err() {
        return not_found();
    }
    let mut cipher = crypto::song_cipher(&s.key, id, start);
    crypto::apply(&mut cipher, &mut buf);

    let mime = match ext.as_str() {
        "webm" => "audio/webm",
        "mp3" => "audio/mpeg",
        _ => "audio/mp4",
    };
    Response::builder()
        .status(StatusCode::PARTIAL_CONTENT)
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{total}"))
        .header(header::CONTENT_LENGTH, buf.len().to_string())
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .body(buf)
        .unwrap()
}

// ---------------------------------------------------------------- app

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // A second launch just brings the running (possibly tray-hidden) window forward.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| background::show_main(app)))
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![background::MINIMIZED_ARG]),
        ))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_shell::init())
        .register_asynchronous_uri_scheme_protocol("musiclify", |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            std::thread::spawn(move || responder.respond(serve_song(&app, &request)));
        })
        .setup(|app| {
            let data = app.path().app_data_dir()?;
            let library = data.join("library");
            std::fs::create_dir_all(&library)?;
            let key = crypto::load_or_create_media_key().map_err(|e| e.to_string())?;
            let db = db::open(&data.join("musiclify.db")).map_err(|e| e.to_string())?;
            app.manage(AppState {
                db: Mutex::new(db),
                key,
                library,
                cookies: data.join("cookies.txt"),
                http: net::client(),
                downloading: Mutex::new(HashMap::new()),
                link: Mutex::new(None),
                sync_port: Mutex::new(0),
            });
            let port = syncsrv::start(app.handle());
            *app.state::<AppState>().sync_port.lock().unwrap() = port;
            background::setup(app)?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_profile, set_profile, set_share_matches, set_cookies, fetch_json,
            favorites_get, favorites_set, favorite_toggle,
            songs_insert, like_toggle, liked_list, downloads_list, recent_list, history_add, history_clear,
            recent_ids, song_set_album_art, stats_get, downloads_usage, remove_downloads, disk_space,
            youtube_playlist, spotify_embed, save_image,
            lyrics_get, lyrics_set,
            playlists_list, playlist_create, playlist_create_with, playlist_add, playlist_remove, playlist_songs, playlist_rename, playlist_delete,
            prepare_song, download_song, download_many, remove_download, clear_downloads, storage_bytes,
            link_start, link_stop, paired_list, unpair,
            background::autostart_get, background::autostart_set,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Musiclify");
}
