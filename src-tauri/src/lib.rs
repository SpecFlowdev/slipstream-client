mod config;
mod meter;
mod rules;
mod sysproxy;
mod traffic;
mod tunnel;

use std::sync::atomic::Ordering;
use std::sync::Arc;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, State};

use config::{Profile, SessionRecord, Settings, Store};
use rules::Rule;
use tunnel::{LogLine, Status, TunnelState};

#[tauri::command]
fn list_profiles(store: State<'_, Arc<Store>>) -> Vec<Profile> {
    store.profiles()
}

#[tauri::command]
fn save_profile(store: State<'_, Arc<Store>>, profile: Profile) -> Result<Profile, String> {
    store.save_profile(profile)
}

#[tauri::command]
fn delete_profile(store: State<'_, Arc<Store>>, id: String) -> Result<(), String> {
    store.delete_profile(&id)
}

#[tauri::command]
fn get_settings(store: State<'_, Arc<Store>>) -> Settings {
    store.settings()
}

#[tauri::command]
fn save_settings(
    store: State<'_, Arc<Store>>,
    tunnel_state: State<'_, Arc<TunnelState>>,
    settings: Settings,
) -> Result<Settings, String> {
    let saved = store.save_settings(settings)?;
    // Applies to a session already in progress, not just the next connect.
    tunnel_state
        .kill_switch
        .store(saved.kill_switch, Ordering::Relaxed);
    Ok(saved)
}

#[tauri::command]
fn list_rules(store: State<'_, Arc<Store>>) -> Vec<Rule> {
    store.rules()
}

/// Saves the rule list and hands it straight to the running relay, so a rule
/// takes effect on connections opened from now on without a reconnect.
#[tauri::command]
fn save_rules(
    store: State<'_, Arc<Store>>,
    tunnel_state: State<'_, Arc<TunnelState>>,
    rules: Vec<Rule>,
) -> Result<Vec<Rule>, String> {
    let saved = store.save_rules(rules)?;
    tunnel_state.rules.write().unwrap().replace(saved.clone());
    Ok(saved)
}

#[tauri::command]
fn get_history(store: State<'_, Arc<Store>>) -> Vec<SessionRecord> {
    store.history()
}

#[tauri::command]
fn clear_history(store: State<'_, Arc<Store>>) -> Result<(), String> {
    store.clear_history()
}

#[tauri::command]
fn get_status(state: State<'_, Arc<TunnelState>>) -> Status {
    state.status()
}

#[tauri::command]
fn get_logs(state: State<'_, Arc<TunnelState>>) -> Vec<LogLine> {
    state.logs()
}

#[tauri::command]
fn clear_logs(state: State<'_, Arc<TunnelState>>) {
    state.clear_logs()
}

#[tauri::command]
fn read_cert_file(path: String) -> Result<String, String> {
    let body = std::fs::read_to_string(&path).map_err(|err| err.to_string())?;
    if !body.contains("BEGIN CERTIFICATE") {
        return Err("That file does not contain a PEM certificate".into());
    }
    Ok(body)
}

const WALLPAPER_EXTENSIONS: &[&str] = &["png", "jpg", "jpeg", "webp", "gif", "bmp"];

/// Copies the chosen image into the app's config directory and returns that
/// path, so the frontend can hand it to Tauri's asset protocol. Storing a
/// copy (like `read_cert_file` does for certificates) means a later launch
/// does not depend on the original file still being where the user picked it
/// from, and avoids putting the image bytes into the JSON settings file.
///
/// The filename includes the current time rather than being the fixed
/// `wallpaper.<ext>` it used to be: picking a second image with the same
/// extension as the first produced the exact same path both times, and the
/// asset protocol's response for that path was cached by the WebView, so the
/// new image never actually appeared — the app kept showing the first one
/// picked. A path that changes on every pick can't be served from a stale
/// cache entry.
#[tauri::command]
fn set_wallpaper(app: AppHandle, path: String) -> Result<String, String> {
    let source = std::path::Path::new(&path);
    let ext = source
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_ascii_lowercase())
        .filter(|e| WALLPAPER_EXTENSIONS.contains(&e.as_str()))
        .ok_or_else(|| "Choose an image file (PNG, JPEG, WebP, GIF or BMP)".to_string())?;

    let dir = app.path().app_config_dir().map_err(|err| err.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|err| err.to_string())?;
    clear_wallpaper_files(&dir)?;

    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or_default();
    let dest = dir.join(format!("wallpaper-{stamp}.{ext}"));
    std::fs::copy(source, &dest).map_err(|err| err.to_string())?;
    Ok(dest.to_string_lossy().to_string())
}

#[tauri::command]
fn clear_wallpaper(app: AppHandle) -> Result<(), String> {
    let dir = app.path().app_config_dir().map_err(|err| err.to_string())?;
    clear_wallpaper_files(&dir)
}

fn clear_wallpaper_files(dir: &std::path::Path) -> Result<(), String> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Ok(());
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        // "wallpaper" without the trailing dot also matches the old
        // `wallpaper.<ext>` naming, so upgrading from an earlier version
        // still cleans up its file.
        if name.to_string_lossy().starts_with("wallpaper") {
            let _ = std::fs::remove_file(entry.path());
        }
    }
    Ok(())
}

#[tauri::command]
async fn connect(app: AppHandle, profile_id: String) -> Result<(), String> {
    let store = app.state::<Arc<Store>>().inner().clone();
    let profile = store
        .profile(&profile_id)
        .ok_or_else(|| "No such profile".to_string())?;
    store.set_last_profile(Some(profile_id));
    let system_proxy = store.settings().system_proxy;
    tunnel::connect(app, profile, system_proxy).await
}

#[tauri::command]
async fn disconnect(app: AppHandle) -> Result<(), String> {
    tunnel::disconnect(app).await;
    Ok(())
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "Show window", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;

    TrayIconBuilder::with_id("main")
        .icon(app.default_window_icon().unwrap().clone())
        .tooltip("Slipstream")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            "quit" => {
                let handle = app.clone();
                tauri::async_runtime::spawn(async move {
                    tunnel::disconnect(handle.clone()).await;
                    handle.exit(0);
                });
            }
            _ => {}
        })
        .build(app)?;
    Ok(())
}

/// Whether it's safe to build the tray icon on this platform.
///
/// On Linux the tray icon (libayatana-appindicator) fatally crashes the
/// process on native Wayland — GDK aborts at the C level before any Rust
/// code can catch it, so there is no panic to recover from, just a dead
/// process a second after launch. `main.rs` steers GDK through X11/XWayland
/// to dodge it, which helps but isn't a guarantee: some Wayland desktop
/// sessions still leave the app on native Wayland regardless (no XWayland
/// installed at all, or something in that session overriding the backend
/// choice again after main.rs sets it), and this exact crash kept coming
/// back on user reports across several attempts at that route. Rather than
/// keep chasing backend-negotiation edge cases, Linux never builds the tray
/// at all: libayatana-appindicator is never touched, so it has nothing to
/// crash on, on any desktop. Closing the window quits the app instead of
/// minimising to a tray that doesn't exist (see the `TrayAvailable` check
/// below), which is the one user-visible trade-off.
#[cfg(target_os = "linux")]
fn tray_is_safe_here() -> bool {
    false
}

#[cfg(not(target_os = "linux"))]
fn tray_is_safe_here() -> bool {
    true
}

/// Whether `build_tray` actually succeeded this run. Hiding the window on
/// close instead of quitting only makes sense when there's a tray icon left
/// to bring it back with; without one that would stop the app dead with no
/// way to reach it again.
struct TrayAvailable(bool);

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let dir = app.path().app_config_dir()?;
            std::fs::create_dir_all(&dir)?;

            let store = Arc::new(Store::load(&dir));
            let tunnel_state = Arc::new(TunnelState::default());
            tunnel_state
                .kill_switch
                .store(store.settings().kill_switch, Ordering::Relaxed);
            tunnel_state.rules.write().unwrap().replace(store.rules());
            app.manage(store.clone());
            app.manage(tunnel_state);

            let tray_built = if tray_is_safe_here() {
                match build_tray(app.handle()) {
                    Ok(()) => true,
                    Err(err) => {
                        eprintln!("tray icon unavailable, continuing without it: {err}");
                        false
                    }
                }
            } else {
                eprintln!("skipping the tray icon on this platform to avoid the Wayland crash");
                false
            };
            app.manage(TrayAvailable(tray_built));

            if store.settings().connect_on_launch {
                if let Some(id) = store.last_profile() {
                    let handle = app.handle().clone();
                    tauri::async_runtime::spawn(async move {
                        let store = handle.state::<Arc<Store>>();
                        if let Some(profile) = store.profile(&id) {
                            let system_proxy = store.settings().system_proxy;
                            let _ = tunnel::connect(handle.clone(), profile, system_proxy).await;
                        }
                    });
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let app = window.app_handle();
                let store = app.state::<Arc<Store>>();
                let tray_available = app.state::<TrayAvailable>().0;
                if store.settings().minimise_to_tray && tray_available {
                    // Keep the tunnel up; the window is only the front end.
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            list_profiles,
            save_profile,
            delete_profile,
            get_settings,
            save_settings,
            get_status,
            get_logs,
            clear_logs,
            read_cert_file,
            list_rules,
            save_rules,
            get_history,
            clear_history,
            set_wallpaper,
            clear_wallpaper,
            connect,
            disconnect,
        ])
        .build(tauri::generate_context!())
        .expect("error while running slipstream client")
        .run(|app, event| {
            // Quitting with the system proxy still pointed at a port that is
            // about to stop answering would leave the whole desktop offline,
            // so the teardown runs on the way out as well as on disconnect.
            if let tauri::RunEvent::Exit = event {
                tunnel::shutdown_blocking(app);
            }
        });
}
