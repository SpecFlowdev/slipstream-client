// Keep the console window from appearing alongside the GUI on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // libayatana-appindicator's tray icon triggers a fatal Wayland protocol
    // error on native Wayland (GDK aborts the process before any Rust code
    // can run: "Gdk-Message: Error 71 (Protocol error) dispatching to Wayland
    // display"), so the app dies within a second of launch with no panic to
    // catch. Routing GDK through XWayland instead avoids it. Set before GTK
    // initializes.
    //
    // Many Wayland desktop sessions (GNOME, KDE Plasma) export
    // `GDK_BACKEND=wayland` for every process, session-wide, specifically to
    // stop apps from using X11. An earlier version of this check only set
    // the variable when it was completely unset, so on those sessions it saw
    // the DE's own "wayland" value already there and left it alone — the app
    // kept crashing despite the fix. Override unless the existing value
    // already lists x11 as an option.
    //
    // "x11,wayland": GDK tries backends in order and keeps the first that
    // connects. X11 normally succeeds via XWayland; on the rare setup with no
    // XWayland at all, it falls through to native Wayland, and lib.rs skips
    // building the tray icon in that case so the crash has nothing to
    // trigger it.
    #[cfg(target_os = "linux")]
    {
        let has_x11_option = std::env::var("GDK_BACKEND")
            .map(|value| value.split(',').any(|backend| backend == "x11"))
            .unwrap_or(false);
        if !has_x11_option {
            std::env::set_var("GDK_BACKEND", "x11,wayland");
        }
    }

    slipstream_client_lib::run()
}
