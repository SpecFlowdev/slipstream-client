// Keep the console window from appearing alongside the GUI on Windows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    slipstream_client_lib::run()
}
