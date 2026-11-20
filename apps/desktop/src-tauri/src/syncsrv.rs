//! Always-on (while the app is open) sync service for paired phones.
//!
//! * A phone pairs once during QR linking (link.rs `/v1/pair`) and receives a long-term key.
//! * After that it can sync any time: GET `/v1/sync/export` (our library, sealed with its key) and
//!   POST `/v1/sync/import` (its library, sealed with its key). Unknown devices or bad seals are refused.
//! * UDP discovery on port 47816 lets the phone find this PC again if its LAN address changed.
//!
//! Everything stays on the local network.

use crate::error::Result;
use crate::{AppState, crypto, db, sync};
use std::io::Read;
use tauri::{AppHandle, Emitter, Manager};

pub const DISCOVERY_PORT: u16 = 47816;
const PREFERRED_PORT: u16 = 47815;
const MAX_BODY: u64 = 30 * 1024 * 1024;

pub fn pc_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into())
}

/// Start the sync server and discovery responder; returns the TCP port (0 if it couldn't start).
pub fn start(app: &AppHandle) -> u16 {
    let server = tiny_http::Server::http(("0.0.0.0", PREFERRED_PORT)).or_else(|_| tiny_http::Server::http("0.0.0.0:0"));
    let Ok(server) = server else { return 0 };
    let port = server.server_addr().to_ip().map(|a| a.port()).unwrap_or(0);

    let app2 = app.clone();
    std::thread::spawn(move || {
        for req in server.incoming_requests() {
            handle(&app2, req);
        }
    });
    std::thread::spawn(move || discovery(port));
    port
}

fn discovery(port: u16) {
    let Ok(sock) = std::net::UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) else { return };
    let mut buf = [0u8; 64];
    loop {
        if let Ok((n, from)) = sock.recv_from(&mut buf) {
            if &buf[..n] == b"MUSICLIFY_DISCOVER" {
                let _ = sock.send_to(format!("MUSICLIFY {port}").as_bytes(), from);
            }
        }
    }
}

fn respond(req: tiny_http::Request, status: u16, body: String) {
    let header = tiny_http::Header::from_bytes(&b"Content-Type"[..], &b"text/plain; charset=utf-8"[..]).unwrap();
    let _ = req.respond(tiny_http::Response::from_string(body).with_status_code(status).with_header(header));
}

fn handle(app: &AppHandle, mut req: tiny_http::Request) {
    let state = app.state::<AppState>();
    let path = req.url().split('?').next().unwrap_or("").to_string();
    let method = req.method().as_str().to_uppercase();

    if method == "GET" && path == "/v1/sync/hello" {
        return respond(req, 200, r#"{"app":"musiclify"}"#.into());
    }

    let device = req.headers().iter()
        .find(|h| h.field.equiv("X-Musiclify-Device"))
        .map(|h| h.value.as_str().to_string());
    let found = device.as_deref().and_then(|d| db::paired_key(&state.db.lock().unwrap(), d).ok().flatten());
    let (Some(device), Some((name, key_b64))) = (device, found) else {
        return respond(req, 401, "This device isn't paired. Link it again with the QR code.".into());
    };
    let Ok(key) = crypto::decode_key(&key_b64) else { return respond(req, 500, "bad key".into()) };

    match (method.as_str(), path.as_str()) {
        ("GET", "/v1/sync/export") => {
            let result = (|| -> Result<String> {
                let export = sync::export(&state.db.lock().unwrap())?;
                crypto::seal(&key, &serde_json::to_vec(&export)?)
            })();
            match result {
                Ok(sealed) => respond(req, 200, sealed),
                Err(e) => respond(req, 500, e.to_string()),
            }
        }
        ("POST", "/v1/sync/import") => {
            let mut body = String::new();
            if req.as_reader().take(MAX_BODY).read_to_string(&mut body).is_err() {
                return respond(req, 400, "bad body".into());
            }
            let result = (|| -> Result<sync::ImportSummary> {
                let plain = crypto::open(&key, &body)?;
                let export: sync::Export = serde_json::from_slice(&plain)?;
                let summary = sync::import(&mut state.db.lock().unwrap(), &export)?;
                db::touch_paired(&state.db.lock().unwrap(), &device)?;
                Ok(summary)
            })();
            match result {
                Ok(summary) => {
                    respond(req, 200, r#"{"ok":true}"#.into());
                    let _ = app.emit("sync-event", serde_json::json!({ "kind": "synced", "device": name, "summary": summary }));
                }
                Err(e) => respond(req, 403, e.to_string()),
            }
        }
        // Playback handoff: the phone sends its queue + position, we continue playing here.
        ("POST", "/v1/sync/handoff") => {
            let mut body = String::new();
            if req.as_reader().take(MAX_BODY).read_to_string(&mut body).is_err() {
                return respond(req, 400, "bad body".into());
            }
            let result = (|| -> Result<serde_json::Value> {
                let plain = crypto::open(&key, &body)?;
                let v: serde_json::Value = serde_json::from_slice(&plain)?;
                let songs: Vec<db::Song> = serde_json::from_value(v.get("songs").cloned().unwrap_or_default())?;
                if songs.is_empty() {
                    return Err(crate::error::Error::msg("empty queue"));
                }
                db::insert_songs(&state.db.lock().unwrap(), &songs)?;
                Ok(v)
            })();
            match result {
                Ok(v) => {
                    respond(req, 200, r#"{"ok":true}"#.into());
                    let _ = app.emit("handoff", serde_json::json!({ "device": name, "handoff": v }));
                    // Bring the window back (it may be hidden in the tray) so the user sees it continue.
                    crate::background::show_main(app);
                }
                Err(e) => respond(req, 403, e.to_string()),
            }
        }
        _ => respond(req, 404, "not found".into()),
    }
}
