//! Spotifast desktop command.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() -> eframe::Result<()> {
    spotifast::entrypoint::run()
}
