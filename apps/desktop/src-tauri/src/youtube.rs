//! YouTube matching and audio download via the bundled yt-dlp, with on-the-fly encryption.
//!
//! yt-dlp writes the audio to stdout; we encrypt each chunk as it arrives and write only
//! ciphertext to disk, so a song never exists unencrypted on this PC. (YouTube's media URLs
//! are protected, so they can't be fetched directly: yt-dlp's downloader is required.)

use crate::crypto;
use crate::db::Song;
use crate::error::{Error, Result};
use regex::Regex;
use serde_json::Value;
use std::path::{Path, PathBuf};
use tauri::AppHandle;
use tauri_plugin_shell::ShellExt;
use tauri_plugin_shell::process::CommandEvent;
use tokio::io::AsyncWriteExt;

const UNWANTED: &[&str] = &[
    "live", "cover", "remix", "karaoke", "instrumental", "reaction", "8d", "slowed", "sped up",
    "nightcore", "lofi", "lo-fi", "tutorial", "1 hour", "reverb",
];

fn base_args(cookies: Option<&Path>) -> Vec<String> {
    let mut a: Vec<String> = vec!["--no-warnings".into(), "--ignore-config".into()];
    if let Some(c) = cookies.filter(|c| c.exists()) {
        a.push("--cookies".into());
        a.push(c.to_string_lossy().into_owned());
    }
    a
}

fn friendly(stderr: &str) -> String {
    let line = stderr.lines().rev().find(|l| l.contains("ERROR")).unwrap_or("YouTube download failed");
    let l = line.to_lowercase();
    if l.contains("not a bot") || l.contains("429") || l.contains("sign in") {
        "YouTube is blocking requests from this PC for now. Try again later, or import YouTube cookies in Settings.".into()
    } else {
        line.trim_start_matches("ERROR: ").to_string()
    }
}

/// Strip "(feat. X)", "- Remastered 2011", etc.
pub fn clean_title(title: &str) -> String {
    let re1 = Regex::new(r"(?i)\s*[(\[](feat\.?|ft\.?|with|from|remaster|live|radio edit|explicit)[^)\]]*[)\]]").unwrap();
    let re2 = Regex::new(r"(?i)\s+-\s+.*(remaster|version|edit|live|mix).*$").unwrap();
    re2.replace(&re1.replace_all(title, ""), "").trim().to_string()
}

/// Search YouTube; returns (video id, video duration in seconds) of the best match.
pub async fn search(app: &AppHandle, cookies: Option<&Path>, song: &Song) -> Result<(String, i64)> {
    let title = clean_title(&song.title);
    let mut args = base_args(cookies);
    args.extend(["--flat-playlist".into(), "-J".into(), format!("ytsearch8:{} - {} audio", song.artist, title)]);
    let out = app
        .shell()
        .sidecar("yt-dlp")
        .map_err(|e| Error::msg(format!("yt-dlp missing: {e}")))?
        .args(args)
        .output()
        .await
        .map_err(|e| Error::msg(format!("yt-dlp failed to start: {e}")))?;
    if !out.status.success() {
        return Err(Error::msg(friendly(&String::from_utf8_lossy(&out.stderr))));
    }
    let v: Value = serde_json::from_slice(&out.stdout)?;
    let wanted = title.to_lowercase();
    let artist = song.artist.to_lowercase();

    v.get("entries")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|e| {
            let id = e.get("id")?.as_str()?.to_string();
            let name = e.get("title").and_then(Value::as_str).unwrap_or_default().to_lowercase();
            let channel = e.get("channel").or(e.get("uploader")).and_then(Value::as_str).unwrap_or_default().to_lowercase();
            let dur = e.get("duration").and_then(Value::as_f64).unwrap_or(0.0) as i64;
            let mut score = if dur > 0 && song.duration_sec > 0 { (dur - song.duration_sec).abs() } else { 30 };
            score += UNWANTED.iter().filter(|w| name.contains(*w) && !wanted.contains(*w)).count() as i64 * 60;
            if !name.contains(&wanted) {
                score += 20;
            }
            if !channel.contains(&artist) && !name.contains(&artist) {
                score += 10;
            }
            if channel.ends_with("- topic") {
                score -= 10;
            }
            Some((score, id, dur))
        })
        .min_by_key(|(s, _, _)| *s)
        .map(|(_, id, dur)| (id, dur))
        .ok_or_else(|| Error::msg("Couldn't find this song on YouTube"))
}

pub fn duration_matches(video: i64, song: i64) -> bool {
    song <= 0 || video <= 0 || (video - song).abs() <= 10
}

pub fn song_path(lib: &Path, id: i64, ext: &str) -> PathBuf {
    lib.join(format!("{id}.{ext}.enc"))
}

fn detect_ext(first: &[u8]) -> &'static str {
    if first.len() >= 8 && &first[4..8] == b"ftyp" {
        "m4a"
    } else if first.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) {
        "webm"
    } else {
        "m4a"
    }
}

pub struct Downloaded {
    pub ext: String,
    pub duration: i64,
}

/// Download a video's audio, encrypting it as it streams in. Returns the container and duration.
pub async fn download(
    app: &AppHandle,
    cookies: Option<&Path>,
    lib: &Path,
    video_id: &str,
    key: &[u8; 16],
    song_id: i64,
    duration_hint: i64,
    mut progress: impl FnMut(f32),
) -> Result<Downloaded> {
    let part = lib.join(format!("{song_id}.part"));
    let dur_file = lib.join(format!("{song_id}.dur"));
    let mut args = base_args(cookies);
    args.extend([
        "-f".into(), "bestaudio[ext=m4a]/bestaudio".into(),
        "--no-part".into(), "--no-progress".into(), "--quiet".into(), "--no-playlist".into(),
        "--print-to-file".into(), "before_dl:%(duration)s".into(), dur_file.to_string_lossy().into_owned(),
        "-o".into(), "-".into(),
        format!("https://www.youtube.com/watch?v={video_id}"),
    ]);
    let (mut rx, _child) = app
        .shell()
        .sidecar("yt-dlp")
        .map_err(|e| Error::msg(format!("yt-dlp missing: {e}")))?
        .args(args)
        .set_raw_out(true)
        .spawn()
        .map_err(|e| Error::msg(format!("yt-dlp failed to start: {e}")))?;

    let mut file = tokio::fs::File::create(&part).await?;
    let mut pos: u64 = 0;
    let mut ext: Option<&'static str> = None;
    let mut stderr = String::new();
    let mut code: Option<i32> = None;
    // ~128 kbit/s AAC => ~16 KB per second of audio; good enough for a progress estimate.
    let expected = (duration_hint.max(60) as f32) * 16_500.0;

    while let Some(event) = rx.recv().await {
        match event {
            CommandEvent::Stdout(mut buf) => {
                if ext.is_none() {
                    ext = Some(detect_ext(&buf));
                }
                let mut cipher = crypto::song_cipher(key, song_id, pos);
                crypto::apply(&mut cipher, &mut buf);
                file.write_all(&buf).await?;
                pos += buf.len() as u64;
                progress((pos as f32 / expected).min(0.99));
            }
            CommandEvent::Stderr(b) => stderr.push_str(&String::from_utf8_lossy(&b)),
            CommandEvent::Error(e) => stderr.push_str(&e),
            CommandEvent::Terminated(t) => code = t.code,
            _ => {}
        }
    }
    file.flush().await?;
    drop(file);

    if code != Some(0) || pos == 0 {
        let _ = tokio::fs::remove_file(&part).await;
        let _ = tokio::fs::remove_file(&dur_file).await;
        return Err(Error::msg(friendly(&stderr)));
    }
    let ext = ext.unwrap_or("m4a");
    tokio::fs::rename(&part, song_path(lib, song_id, ext)).await?;
    let duration = tokio::fs::read_to_string(&dur_file).await.ok()
        .and_then(|s| s.trim().parse::<f64>().ok())
        .unwrap_or(0.0) as i64;
    let _ = tokio::fs::remove_file(&dur_file).await;
    Ok(Downloaded { ext: ext.to_string(), duration })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean_title_strips_features_and_versions() {
        assert_eq!(clean_title("One Dance (feat. Wizkid & Kyla)"), "One Dance");
        assert_eq!(clean_title("Hey Jude - Remastered 2015"), "Hey Jude");
        assert_eq!(clean_title("Song [Live]"), "Song");
        assert_eq!(clean_title("Plain Title"), "Plain Title");
    }

    #[test]
    fn duration_matching_allows_ten_seconds_and_unknowns() {
        assert!(duration_matches(200, 205));
        assert!(duration_matches(200, 210));
        assert!(!duration_matches(200, 215));
        assert!(duration_matches(0, 200), "unknown video length should not reject");
        assert!(duration_matches(200, 0), "unknown song length should not reject");
    }

    #[test]
    fn detect_ext_reads_container_magic() {
        assert_eq!(detect_ext(b"\0\0\0\x20ftypM4A "), "m4a");
        assert_eq!(detect_ext(&[0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0]), "webm");
        assert_eq!(detect_ext(b"xx"), "m4a");
    }
}
