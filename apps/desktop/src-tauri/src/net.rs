//! Outbound HTTP: allowlisted JSON fetches for the UI, and the shared match cache.

use crate::error::{Error, Result};
use serde_json::Value;
use std::time::Duration;

/// Shared song-match cache (services/matchcache). Override with MUSICLIFY_MATCH_CACHE_URL.
pub fn match_cache_url() -> String {
    std::env::var("MUSICLIFY_MATCH_CACHE_URL").unwrap_or_else(|_| {
        "https://br-weathered-scene-bibw5h1p-matchcache.compute.c-5.ap-southeast-1.aws.neon.tech".into()
    })
}

/// The UI can only reach these hosts (Deezer has no CORS, so requests go through Rust).
/// api.github.com is only used to read the public releases list for update checks.
/// musiclify.vercel.app is Musiclify's Deezer relay (see apps/website/src/app/api/deezer).
const ALLOWED_HOSTS: &[&str] = &["api.deezer.com", "lrclib.net", "api.github.com", "musiclify.vercel.app"];

pub fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent("Musiclify-Desktop (open source)")
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .build()
        .expect("http client")
}

/// GET a JSON document. Returns `Value::Null` for 404 so callers can treat it as "not found".
pub async fn fetch_json(http: &reqwest::Client, url: &str) -> Result<Value> {
    let parsed = url::Url::parse(url).map_err(|_| Error::msg("bad url"))?;
    let host = parsed.host_str().unwrap_or_default();
    if parsed.scheme() != "https" || !ALLOWED_HOSTS.contains(&host) {
        return Err(Error::msg(format!("host not allowed: {host}")));
    }
    let resp = http.get(parsed).send().await?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(Value::Null);
    }
    Ok(resp.error_for_status()?.json().await?)
}

/// The page Spotify serves for embedding a public playlist or album; its page data lists the tracks.
pub async fn spotify_embed(http: &reqwest::Client, kind: &str, id: &str) -> Result<String> {
    if !matches!(kind, "playlist" | "album") || id.is_empty() || !id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return Err(Error::msg("That doesn't look like a Spotify playlist link."));
    }
    let resp = http
        .get(format!("https://open.spotify.com/embed/{kind}/{id}"))
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36")
        .send()
        .await?;
    if !resp.status().is_success() {
        return Err(Error::msg(format!("Spotify didn't share this {kind} (HTTP {}). Is it public?", resp.status().as_u16())));
    }
    Ok(resp.text().await?)
}

pub struct SharedMatch {
    pub youtube_id: String,
}

pub async fn match_lookup(http: &reqwest::Client, deezer_id: i64) -> Option<SharedMatch> {
    let url = format!("{}/v1/match/{deezer_id}", match_cache_url());
    let resp = http.get(url).timeout(Duration::from_secs(3)).send().await.ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let v: Value = resp.json().await.ok()?;
    Some(SharedMatch { youtube_id: v.get("youtube_id")?.as_str()?.to_string() })
}

pub fn match_submit(http: reqwest::Client, deezer_id: i64, youtube_id: String, duration_sec: i64) {
    if duration_sec <= 0 {
        return;
    }
    tauri::async_runtime::spawn(async move {
        let body = serde_json::json!({ "deezer_id": deezer_id, "youtube_id": youtube_id, "duration_sec": duration_sec });
        let _ = http
            .post(format!("{}/v1/match", match_cache_url()))
            .json(&body)
            .timeout(Duration::from_secs(5))
            .send()
            .await;
    });
}
