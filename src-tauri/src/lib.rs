mod config;
mod meter;
mod tunnel;

use std::sync::Arc;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, State};

use config::{Profile, Settings, Store};
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
fn save_settings(store: State<'_, Arc<Store>>, settings: Settings) -> Result<Settings, String> {
    store.save_settings(settings)
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

#[tauri::command]
async fn connect(app: AppHandle, profile_id: String) -> Result<(), String> {
    let store = app.state::<Arc<Store>>().inner().clone();
    let profile = store
        .profile(&profile_id)
        .ok_or_else(|| "No such profile".to_string())?;
    store.set_last_profile(Some(profile_id));
    tunnel::connect(app, profile).await
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
            app.manage(store.clone());
            app.manage(tunnel_state);

            build_tray(app.handle())?;

            if store.settings().connect_on_launch {
                if let Some(id) = store.last_profile() {
                    let handle = app.handle().clone();
                    tauri::async_runtime::spawn(async move {
                        if let Some(profile) = handle.state::<Arc<Store>>().profile(&id) {
                            let _ = tunnel::connect(handle.clone(), profile).await;
                        }
                    });
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let store = window.app_handle().state::<Arc<Store>>();
                if store.settings().minimise_to_tray {
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
            connect,
            disconnect,
        ])
        .run(tauri::generate_context!())
        .expect("error while running slipstream client");
}
