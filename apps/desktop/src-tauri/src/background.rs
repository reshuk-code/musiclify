//! Keep Musiclify available for the paired phone even when its window is closed:
//! system-tray icon, close-to-tray, optional start with Windows (hidden), and bringing the
//! window back when the phone hands playback over.

use crate::db;
use crate::AppState;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, WindowEvent};
use tauri_plugin_autostart::ManagerExt;

pub const MINIMIZED_ARG: &str = "--minimized";

/// Show, restore and focus the main window.
pub fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

pub fn setup(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open Musiclify", true, None::<&str>)?;
    let toggle = MenuItem::with_id(app, "toggle", "Play / Pause", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit Musiclify", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &toggle, &quit])?;

    TrayIconBuilder::with_id("main")
        .icon(app.default_window_icon().cloned().expect("app icon"))
        .tooltip("Musiclify")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "toggle" => {
                let _ = app.emit("tray-toggle", ());
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;

    // Closing the window hides it to the tray so the phone can still reach this PC.
    if let Some(w) = app.get_webview_window("main") {
        let w2 = w.clone();
        w.on_window_event(move |e| {
            if let WindowEvent::CloseRequested { api, .. } = e {
                api.prevent_close();
                let _ = w2.hide();
            }
        });
        // Started with Windows: stay in the tray until needed.
        if std::env::args().any(|a| a == MINIMIZED_ARG) {
            let _ = w.hide();
        }
    }

    // First run: start with Windows by default (can be turned off in Settings).
    let state = app.state::<AppState>();
    let first = db::setting(&state.db.lock().unwrap(), "autostart_init").ok().flatten().is_none();
    if first {
        let _ = app.autolaunch().enable();
        let _ = db::set_setting(&state.db.lock().unwrap(), "autostart_init", Some("1"));
    }
    Ok(())
}

#[tauri::command]
pub fn autostart_get(app: AppHandle) -> bool {
    app.autolaunch().is_enabled().unwrap_or(false)
}

#[tauri::command]
pub fn autostart_set(app: AppHandle, enabled: bool) -> Result<(), String> {
    let l = app.autolaunch();
    if enabled { l.enable() } else { l.disable() }.map_err(|e| e.to_string())
}
