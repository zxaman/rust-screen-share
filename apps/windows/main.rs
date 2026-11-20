//! Portable offline launcher. No sockets, services, or elevated privileges.
#![windows_subsystem = "windows"]
use std::{env, ffi::OsStr, fs, io, os::windows::ffi::OsStrExt, path::PathBuf, ptr};

#[link(name = "shell32")]
extern "system" {
    fn ShellExecuteW(
        hwnd: usize,
        verb: *const u16,
        file: *const u16,
        parameters: *const u16,
        dir: *const u16,
        show: i32,
    ) -> isize;
}
#[link(name = "user32")]
extern "system" {
    fn MessageBoxW(hwnd: usize, text: *const u16, caption: *const u16, flags: u32) -> i32;
}
fn wide(s: &OsStr) -> Vec<u16> {
    s.encode_wide().chain(Some(0)).collect()
}
fn open() -> io::Result<()> {
    let dir = PathBuf::from(
        env::var_os("LOCALAPPDATA")
            .ok_or_else(|| io::Error::other("Local app data folder is unavailable"))?,
    )
    .join("Nodeport Preview");
    fs::create_dir_all(&dir)?;
    let path = dir.join("workspace.html");
    fs::write(&path, include_bytes!(env!("NODEPORT_HTML")))?;
    let file = wide(path.as_os_str());
    let verb = wide("open".as_ref());
    // The file path is passed as a separate argument, never to a command shell.
    let rc = unsafe { ShellExecuteW(0, verb.as_ptr(), file.as_ptr(), ptr::null(), ptr::null(), 1) };
    if rc <= 32 {
        return Err(io::Error::other(format!(
            "Cannot open the workspace. Set a default browser for HTML files (error {rc})."
        )));
    }
    Ok(())
}
fn main() {
    if let Err(err) = open() {
        let msg = wide(err.to_string().as_ref());
        let title = wide("Nodeport Preview".as_ref());
        unsafe { MessageBoxW(0, msg.as_ptr(), title.as_ptr(), 0x10) };
        std::process::exit(1);
    }
}
