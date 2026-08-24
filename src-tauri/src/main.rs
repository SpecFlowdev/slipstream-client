// Keep the console window from appearing alongside the GUI on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

/// Re-launches this process under `GDK_BACKEND=x11` when running on Wayland.
///
/// The app dies about a second after launch on several Wayland desktops with
/// `Gdk-Message: Error 71 (Protocol error) dispatching to Wayland display` —
/// a fatal error GDK raises at the C level, so there is no Rust panic to
/// catch, just a process that is suddenly gone. Running the very same binary
/// as `GDK_BACKEND=x11 slipstream-client` fixes it, confirmed by hand.
///
/// Two earlier attempts set `GDK_BACKEND` with `set_var` at the top of
/// `main()` and neither actually fixed it in the field. Setting the variable
/// in our own already-running process is *not* equivalent to launching with
/// it: GTK, GDK and their dependencies run initialisation from library
/// constructors and cached state that can be established before `main()` is
/// ever entered, so by the time we write the variable the backend decision
/// can already be made and our write is simply too late.
///
/// So instead of setting the variable and hoping, this re-executes the
/// binary with the variable already in the environment, which is byte for
/// byte the command line that is known to work. `exec` replaces the current
/// process image rather than forking, so there is no second process, no
/// orphan, and no window flashing twice — from the outside it is exactly as
/// if the user had typed the variable themselves.
#[cfg(target_os = "linux")]
fn relaunch_under_x11_if_needed() {
    use std::os::unix::process::CommandExt;

    // Set on the child we spawn below, so the new process knows not to try
    // again. Without it a failure to reach the X server would loop forever.
    const GUARD: &str = "SLIPSTREAM_X11_RELAUNCH";

    if std::env::var_os(GUARD).is_some() {
        return;
    }
    // Not a Wayland session: nothing to steer away from.
    if std::env::var_os("WAYLAND_DISPLAY").is_none() {
        return;
    }
    // No X server to switch to. XWayland always exports DISPLAY, so an unset
    // DISPLAY means this session genuinely has no X11 at all; staying on
    // Wayland is the only option. The tray icon (the known trigger) is not
    // built on Linux, so the usual cause is already gone in that case.
    if std::env::var_os("DISPLAY").is_none() {
        return;
    }
    // Already pinned to X11 by the user or by an earlier relaunch.
    if std::env::var("GDK_BACKEND").is_ok_and(|value| value == "x11") {
        return;
    }

    let Ok(exe) = std::env::current_exe() else {
        return;
    };

    // `exec` only ever returns if it failed, so anything past this line means
    // the relaunch did not happen and we carry on as we are.
    let err = std::process::Command::new(exe)
        .args(std::env::args_os().skip(1))
        .env("GDK_BACKEND", "x11")
        .env(GUARD, "1")
        .exec();
    eprintln!("could not relaunch under X11 ({err}); continuing on the current backend");
}

/// Turns off WebKitGTK's DMA-BUF renderer, which leaves the window blank on
/// a good number of Linux setups.
///
/// The symptom is an empty grey window and `Failed to create GBM buffer of
/// size WxH: Invalid argument` on stderr: WebKit's accelerated path is
/// asking the GPU for a buffer through GBM and being refused, then failing
/// to draw anything at all rather than falling back. It shows up on the
/// NVIDIA proprietary driver, on virtualised GPUs, and wherever the DRM
/// render node is not usable in the way WebKit expects — none of which the
/// app can do anything about from the outside.
///
/// The software path it falls back to is entirely adequate here: this
/// window is charts, tables and text, not a compositor. A blank window is
/// fatal, a slightly less accelerated one is not, so the trade is easy.
///
/// Unlike `GDK_BACKEND`, setting this from inside the process is enough —
/// WebKit reads it when the web view is created, long after `main` starts,
/// and the value is inherited by the WebKit subprocesses that do the actual
/// rendering. Anyone who wants the accelerated path back can set the
/// variable to `0` themselves and this leaves it alone.
#[cfg(target_os = "linux")]
fn disable_dmabuf_renderer_by_default() {
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }
}

fn main() {
    #[cfg(target_os = "linux")]
    {
        relaunch_under_x11_if_needed();
        disable_dmabuf_renderer_by_default();
    }

    slipstream_client_lib::run()
}
