//! "Link with your phone": a short-lived local server the phone talks to after scanning a QR code.
//!
//! The QR holds this PC's LAN address and a one-time 256-bit key. Every payload is sealed with
//! AES-256-GCM using that key, so other devices on the network can't read or forge data.
//! Nothing goes through the internet. The session closes after one transfer or 10 minutes.

use crate::error::{Error, Result};
use crate::{AppState, crypto, sync};
use serde::Serialize;
use std::io::Read;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager};

pub struct LinkSession {
    server: Arc<tiny_http::Server>,
}

impl Drop for LinkSession {
    fn drop(&mut self) {
        self.server.unblock();
    }
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct LinkInfo {
    pub payload: String,
    pub host: String,
    pub port: u16,
    pub expires_at: i64,
}

#[derive(Serialize, Clone)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum LinkEvent {
    Imported { summary: sync::ImportSummary },
    Exported,
    Error { message: String },
    Closed,
}

const TTL_MS: i64 = 10 * 60 * 1000;
const MAX_BODY: u64 = 20 * 1024 * 1024;

pub fn start(app: &AppHandle) -> Result<LinkInfo> {
    let host = local_ip_address::local_ip()
        .map_err(|_| Error::msg("Not connected to a network. Connect this PC to Wi-Fi first."))?
        .to_string();
    let server = Arc::new(tiny_http::Server::http("0.0.0.0:0").map_err(|e| Error::msg(e.to_string()))?);
    let port = server.server_addr().to_ip().map(|a| a.port()).ok_or_else(|| Error::msg("no port"))?;
    let key = crypto::new_link_key();
    let expires_at = crate::db::now_ms() + TTL_MS;

    let state = app.state::<AppState>();
    *state.link.lock().unwrap() = Some(LinkSession { server: server.clone() });

    let app2 = app.clone();
    std::thread::spawn(move || serve(app2, server, key, expires_at));

    let payload = format!("musiclify://link?v=1&h={host}&p={port}&k={}", crypto::encode_key(&key));
    Ok(LinkInfo { payload, host, port, expires_at })
}

pub fn stop(app: &AppHandle) {
    app.state::<AppState>().link.lock().unwrap().take();
}

fn serve(app: AppHandle, server: Arc<tiny_http::Server>, key: [u8; 32], expires_at: i64) {
    loop {
        let req = match server.recv_timeout(std::time::Duration::from_secs(2)) {
            Ok(Some(r)) => r,
            Ok(None) => {
                if crate::db::now_ms() > expires_at {
                    break;
                }
                continue;
            }
            Err(_) => break, // unblocked / stopped
        };
        if handle(&app, req_method_path(&req), &key, req) {
            break;
        }
    }
    let _ = app.emit("link-event", LinkEvent::Closed);
    stop(&app);
}

fn req_method_path(req: &tiny_http::Request) -> (String, String) {
    (req.method().as_str().to_uppercase(), req.url().split('?').next().unwrap_or("").to_string())
}

fn respond(req: tiny_http::Request, status: u16, body: String) {
    let header = tiny_http::Header::from_bytes(&b"Content-Type"[..], &b"text/plain; charset=utf-8"[..]).unwrap();
    let _ = req.respond(tiny_http::Response::from_string(body).with_status_code(status).with_header(header));
}

/// Returns true when the session is finished (one successful transfer).
fn handle(app: &AppHandle, (method, path): (String, String), key: &[u8; 32], mut req: tiny_http::Request) -> bool {
    let state = app.state::<AppState>();
    match (method.as_str(), path.as_str()) {
        ("GET", "/v1/ping") => {
            respond(req, 200, r#"{"app":"musiclify","device":"desktop","v":1}"#.into());
            false
        }
        // Long-term pairing for the Sync button: sealed with the one-time key like everything else.
        ("POST", "/v1/pair") => {
            let mut body = String::new();
            if req.as_reader().take(64 * 1024).read_to_string(&mut body).is_err() {
                respond(req, 400, "bad body".into());
                return false;
            }
            let result = (|| -> Result<String> {
                let plain = crypto::open(key, &body)?;
                let v: serde_json::Value = serde_json::from_slice(&plain)?;
                let name: String = v.get("name").and_then(|x| x.as_str()).unwrap_or("Phone").chars().take(60).collect();
                let id = crypto::new_device_id();
                let pair_key = crypto::encode_key(&crypto::new_link_key());
                crate::db::add_paired(&state.db.lock().unwrap(), &id, &name, &pair_key)?;
                let port = *state.sync_port.lock().unwrap();
                let resp = serde_json::json!({
                    "deviceId": id, "pairKey": pair_key, "port": port, "pcName": crate::syncsrv::pc_name(),
                });
                crypto::seal(key, resp.to_string().as_bytes())
            })();
            match result {
                Ok(sealed) => {
                    respond(req, 200, sealed);
                    let _ = app.emit("sync-event", serde_json::json!({ "kind": "paired" }));
                }
                Err(e) => respond(req, 403, e.to_string()),
            }
            false
        }
        ("GET", "/v1/export") => {
            let result = (|| -> Result<String> {
                let export = sync::export(&state.db.lock().unwrap())?;
                crypto::seal(key, &serde_json::to_vec(&export)?)
            })();
            match result {
                Ok(sealed) => {
                    respond(req, 200, sealed);
                    let _ = app.emit("link-event", LinkEvent::Exported);
                    true
                }
                Err(e) => {
                    respond(req, 500, e.to_string());
                    false
                }
            }
        }
        ("POST", "/v1/import") => {
            let mut body = String::new();
            if req.as_reader().take(MAX_BODY).read_to_string(&mut body).is_err() {
                respond(req, 400, "bad body".into());
                return false;
            }
            let result = (|| -> Result<sync::ImportSummary> {
                let plain = crypto::open(key, &body)?;
                let export: sync::Export = serde_json::from_slice(&plain)?;
                sync::import(&mut state.db.lock().unwrap(), &export)
            })();
            match result {
                Ok(summary) => {
                    respond(req, 200, r#"{"ok":true}"#.into());
                    let _ = app.emit("link-event", LinkEvent::Imported { summary });
                    true
                }
                Err(e) => {
                    // Wrong key = someone else on the network; keep the session open for the real phone.
                    respond(req, 403, e.to_string());
                    let _ = app.emit("link-event", LinkEvent::Error { message: e.to_string() });
                    false
                }
            }
        }
        _ => {
            respond(req, 404, "not found".into());
            false
        }
    }
}
