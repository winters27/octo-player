//! Shows a song in the system's media controls, then reads back what the
//! system says is playing, so a person can check the library by hand:
//!
//!     cargo run --example smoke
//!
//! Press a media key within the few seconds it waits to see the event.

use std::ffi::CString;
use std::thread::sleep;
use std::time::Duration;

use octo_system::*;

extern "C" fn heard(kind: i32, value: i64) {
    println!("event {kind} {value}");
}

fn main() {
    let text = |s: &str| CString::new(s).unwrap();
    let (title, artist, album) = (text("Octo smoke check"), text("Octo"), text("Checks"));
    // A 1 by 1 PNG, enough to count as a cover.
    let art: &[u8] = &[
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00,
        0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00,
        0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x60, 0x60, 0xF8, 0x0F, 0x00, 0x01, 0x04,
        0x01, 0x00, 0x5F, 0xE5, 0xC3, 0x4B, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60,
        0x82,
    ];
    println!("start {}", octo_system_start(Some(heard)));
    let shown = unsafe {
        octo_system_set_track(
            title.as_ptr(),
            artist.as_ptr(),
            album.as_ptr(),
            artist.as_ptr(),
            200_000,
            art.as_ptr(),
            art.len(),
        )
    };
    println!("track {shown}");
    println!("playback {}", octo_system_set_playback(2, 42_000, 1, 1));
    sleep(Duration::from_millis(1500));
    let mut buffer = vec![0u8; 8192];
    let n = unsafe { octo_system_describe_sessions(buffer.as_mut_ptr(), buffer.len()) };
    if n >= 0 {
        println!("the system shows:\n{}", String::from_utf8_lossy(&buffer[..(n as usize).min(buffer.len())]));
    } else {
        println!("describe failed {n}");
    }
    sleep(Duration::from_secs(4));
    println!("clear {}", octo_system_clear());
    octo_system_stop();
}
