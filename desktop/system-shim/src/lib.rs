//! The Octo desktop app's hooks into the operating system, as a few plain C
//! functions the Kotlin side calls through JNA:
//!
//! - Windows: the system media controls (the media keys, the volume
//!   flyout, the lock screen and headset buttons), plus sleep and wake.
//! - macOS: the Now Playing centre and remote commands (the media keys,
//!   Control Centre, the Touch Bar, AirPods), plus sleep and wake.
//!
//! Linux does all of this on the Kotlin side over D-Bus, so this library is
//! not built there.
//!
//! Strings are UTF-8 and NUL terminated. Every call returns 0 when it
//! worked, or a negative number (an HRESULT on Windows) when it did not.
//! What the listener does comes back through one callback, on a thread of
//! the system's choosing.

use std::ffi::{CStr, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;

#[cfg(windows)]
mod windows_controls;
#[cfg(windows)]
use windows_controls as platform;

#[cfg(target_os = "macos")]
mod mac_controls;
#[cfg(target_os = "macos")]
use mac_controls as platform;

#[cfg(not(any(windows, target_os = "macos")))]
mod platform {
    use crate::{Status, Track};

    pub fn start() -> Result<(), i32> {
        Err(crate::NOT_SUPPORTED)
    }
    pub fn set_track(_: &Track) -> Result<(), i32> {
        Err(crate::NOT_SUPPORTED)
    }
    pub fn set_playback(_: Status, _: i64, _: bool, _: bool) -> Result<(), i32> {
        Err(crate::NOT_SUPPORTED)
    }
    pub fn clear() -> Result<(), i32> {
        Err(crate::NOT_SUPPORTED)
    }
    pub fn stop() {}
    pub fn describe_sessions() -> Result<String, i32> {
        Err(crate::NOT_SUPPORTED)
    }
}

/// What the listener asked for, with a value where one is needed.
pub type EventCallback = extern "C" fn(kind: i32, value: i64);

pub const EVENT_PLAY: i32 = 1;
pub const EVENT_PAUSE: i32 = 2;
pub const EVENT_TOGGLE: i32 = 3;
pub const EVENT_NEXT: i32 = 4;
pub const EVENT_PREVIOUS: i32 = 5;
pub const EVENT_STOP: i32 = 6;
/// The value is the place to go to, in milliseconds.
pub const EVENT_SEEK: i32 = 7;
/// The machine is about to sleep.
pub const EVENT_SLEEP: i32 = 8;
/// The machine woke up.
pub const EVENT_WAKE: i32 = 9;

pub const NOT_SUPPORTED: i32 = -1;
pub const NOT_STARTED: i32 = -2;
pub const BAD_ARGUMENT: i32 = -3;
pub const PANICKED: i32 = -4;

/// Whether anything is playing, as the system shows it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    Stopped,
    Playing,
    Paused,
}

impl Status {
    fn from_code(code: i32) -> Option<Status> {
        match code {
            1 => Some(Status::Stopped),
            2 => Some(Status::Playing),
            3 => Some(Status::Paused),
            _ => None,
        }
    }
}

/// Milliseconds as Windows timeline ticks of 100 nanoseconds.
pub fn ticks(ms: i64) -> i64 {
    ms.max(0).saturating_mul(10_000)
}

/// Milliseconds as the seconds macOS's Now Playing wants.
pub fn seconds(ms: i64) -> f64 {
    ms.max(0) as f64 / 1000.0
}

/// How fast Now Playing should move the time on by itself.
pub fn rate(status: Status) -> f64 {
    if status == Status::Playing { 1.0 } else { 0.0 }
}

/// A place in the song, kept inside it when its length is known.
pub fn clamp_position(position_ms: i64, duration_ms: i64) -> i64 {
    if duration_ms > 0 { position_ms.clamp(0, duration_ms) } else { position_ms.max(0) }
}

/// The song to show.
#[derive(Clone, Debug, Default)]
pub struct Track {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_artist: String,
    pub duration_ms: i64,
    /// The cover as an encoded image (JPEG or PNG), if there is one.
    pub art: Option<Vec<u8>>,
}

static CALLBACK: Mutex<Option<EventCallback>> = Mutex::new(None);

/// Tells the app what the listener asked for.
pub(crate) fn emit(kind: i32, value: i64) {
    let callback = *CALLBACK.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(callback) = callback {
        callback(kind, value);
    }
}

fn guarded(work: impl FnOnce() -> Result<(), i32>) -> i32 {
    match catch_unwind(AssertUnwindSafe(work)) {
        Ok(Ok(())) => 0,
        Ok(Err(code)) => code,
        Err(_) => PANICKED,
    }
}

unsafe fn text(value: *const c_char) -> String {
    if value.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(value) }.to_string_lossy().into_owned()
}

/// Which version of these calls the library offers.
#[unsafe(no_mangle)]
pub extern "C" fn octo_system_version() -> i32 {
    1
}

/// Starts listening to the system: its media buttons, and sleep and wake.
/// Calling it again only changes the callback.
#[unsafe(no_mangle)]
pub extern "C" fn octo_system_start(callback: Option<EventCallback>) -> i32 {
    guarded(|| {
        *CALLBACK.lock().unwrap_or_else(|e| e.into_inner()) = callback;
        platform::start()
    })
}

/// Shows a song. `art` may be null, with `art_len` 0, for no cover.
///
/// # Safety
/// The strings must be null or NUL terminated, and `art` must point to
/// `art_len` bytes when it is not null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_system_set_track(
    title: *const c_char,
    artist: *const c_char,
    album: *const c_char,
    album_artist: *const c_char,
    duration_ms: i64,
    art: *const u8,
    art_len: usize,
) -> i32 {
    guarded(|| {
        let track = unsafe {
            Track {
                title: text(title),
                artist: text(artist),
                album: text(album),
                album_artist: text(album_artist),
                duration_ms: duration_ms.max(0),
                art: if art.is_null() || art_len == 0 {
                    None
                } else {
                    Some(std::slice::from_raw_parts(art, art_len).to_vec())
                },
            }
        };
        platform::set_track(&track)
    })
}

/// Shows whether the song plays, where it is, and which buttons work.
/// `status` is 1 stopped, 2 playing, 3 paused.
#[unsafe(no_mangle)]
pub extern "C" fn octo_system_set_playback(
    status: i32,
    position_ms: i64,
    can_previous: i32,
    can_next: i32,
) -> i32 {
    guarded(|| {
        let status = Status::from_code(status).ok_or(BAD_ARGUMENT)?;
        platform::set_playback(status, position_ms.max(0), can_previous != 0, can_next != 0)
    })
}

/// Takes the song away, as when the queue is emptied.
#[unsafe(no_mangle)]
pub extern "C" fn octo_system_clear() -> i32 {
    guarded(platform::clear)
}

/// Stops listening and lets go of everything.
#[unsafe(no_mangle)]
pub extern "C" fn octo_system_stop() {
    let _ = catch_unwind(|| {
        platform::stop();
        *CALLBACK.lock().unwrap_or_else(|e| e.into_inner()) = None;
    });
}

/// What the system itself shows as playing, one line per app, for checking
/// by hand that the app's song arrived. Each line is the app's id, title,
/// artist, album, status, position and length in milliseconds, and 1 or 0
/// for a cover, separated by tabs. Writes up to `capacity` bytes and
/// answers the full length, or a negative number when it cannot tell.
///
/// # Safety
/// `buffer` must point to `capacity` writable bytes, or be null with 0.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_system_describe_sessions(buffer: *mut u8, capacity: usize) -> i64 {
    let result = catch_unwind(platform::describe_sessions);
    match result {
        Ok(Ok(text)) => {
            let bytes = text.as_bytes();
            if !buffer.is_null() {
                let n = bytes.len().min(capacity);
                unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer, n) };
            }
            bytes.len() as i64
        }
        Ok(Err(code)) => code as i64,
        Err(_) => PANICKED as i64,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_ticks_are_ten_thousand_to_the_millisecond() {
        assert_eq!(ticks(1), 10_000);
        assert_eq!(ticks(200_000), 2_000_000_000);
        assert_eq!(ticks(-5), 0);
    }

    #[test]
    fn mac_gets_seconds_and_a_rate() {
        assert_eq!(seconds(42_500), 42.5);
        assert_eq!(rate(Status::Playing), 1.0);
        assert_eq!(rate(Status::Paused), 0.0);
        assert_eq!(rate(Status::Stopped), 0.0);
    }

    #[test]
    fn positions_stay_inside_the_song() {
        assert_eq!(clamp_position(250_000, 200_000), 200_000);
        assert_eq!(clamp_position(-1, 200_000), 0);
        assert_eq!(clamp_position(90_000, 0), 90_000);
    }

    #[test]
    fn status_codes_match_the_app() {
        assert_eq!(Status::from_code(1), Some(Status::Stopped));
        assert_eq!(Status::from_code(2), Some(Status::Playing));
        assert_eq!(Status::from_code(3), Some(Status::Paused));
        assert_eq!(Status::from_code(0), None);
        assert_eq!(octo_system_set_playback(9, 0, 0, 0), BAD_ARGUMENT);
    }
}
