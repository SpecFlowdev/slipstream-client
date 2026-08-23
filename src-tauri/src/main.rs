// Keep the console window from appearing alongside the GUI on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // libayatana-appindicator's tray icon triggers a fatal Wayland protocol
    // error on native Wayland (GDK aborts the process before any Rust code
    // can run: "Gdk-Message: Error 71 (Protocol error) dispatching to Wayland
    // display"), so the app dies within a second of launch with no panic to
    // catch. Routing GDK through XWayland instead avoids it. Set before GTK
    // initializes, and only if the user has not chosen a backend themselves.
    // "x11,wayland": GDK tries backends in order and keeps the first that
    // connects. X11 normally succeeds via XWayland; on the rare setup with no
    // XWayland at all, it falls through to native Wayland instead of leaving
    // the app with no window.
    #[cfg(target_os = "linux")]
    if std::env::var_os("GDK_BACKEND").is_none() {
        std::env::set_var("GDK_BACKEND", "x11,wayland");
    }

    slipstream_client_lib::run()
}
