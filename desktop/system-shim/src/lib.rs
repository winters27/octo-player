//! The Octo desktop app's hooks into the operating system, as a few plain C
//! functions the Kotlin side calls through JNA:
//!
//! - Windows: the system media controls (the media keys, the volume
//!   flyout, the lock screen and headset buttons), plus sleep and wake.
//! - macOS: the Now Playing centre and remote commands (the media keys,
//!   Control Centre, the Touch Bar, AirPods), plus sleep and wake.
//! - Windows only, since version 3: the taskbar button's thumbnail buttons
//!   and progress, and the jump list.
//! - Every system, since version 4: a webcam's pictures, for reading QR
//!   codes.
//!
//! Linux does the rest on the Kotlin side over D-Bus, so there this library
//! only has the camera.
//!
//! Strings are UTF-8 and NUL terminated. Every call returns 0 when it
//! worked, or a negative number (an HRESULT on Windows) when it did not.
//! What the listener does comes back through one callback, on a thread of
//! the system's choosing.

use std::ffi::{CStr, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;

#[cfg(windows)]
mod jump_list;
#[cfg(windows)]
mod taskbar;
#[cfg(windows)]
mod windows_controls;
#[cfg(windows)]
use windows_controls as platform;

mod camera;

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
    pub fn set_playback(_: Status, _: i64, _: bool, _: bool, _: f64) -> Result<(), i32> {
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

/// How fast Now Playing should move the time on by itself: the song's
/// speed while playing, and 0 while paused or waiting for sound (a speed
/// of 0 while playing).
pub fn rate(status: Status, speed: f64) -> f64 {
    if status == Status::Playing && speed.is_finite() { speed.max(0.0) } else { 0.0 }
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
    4
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

/// Shows whether the song plays, where it is, and which buttons work, at
/// the speed it was recorded. `status` is 1 stopped, 2 playing, 3 paused.
#[unsafe(no_mangle)]
pub extern "C" fn octo_system_set_playback(
    status: i32,
    position_ms: i64,
    can_previous: i32,
    can_next: i32,
) -> i32 {
    octo_system_set_playback_at_rate(status, position_ms, can_previous, can_next, 1.0)
}

/// The same, with `rate`: how fast the place moves on while playing (1.5
/// at one and a half times the speed, 0 while waiting for sound). Since
/// version 2.
#[unsafe(no_mangle)]
pub extern "C" fn octo_system_set_playback_at_rate(
    status: i32,
    position_ms: i64,
    can_previous: i32,
    can_next: i32,
    rate: f64,
) -> i32 {
    guarded(|| {
        let status = Status::from_code(status).ok_or(BAD_ARGUMENT)?;
        platform::set_playback(
            status,
            position_ms.max(0),
            can_previous != 0,
            can_next != 0,
            crate::rate(status, rate),
        )
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

// The webcam, on every system (version 4 on).

/// The camera stopped or could not start.
pub const CAMERA_FAILED: i32 = -5;

static CAMERAS: Mutex<Vec<(i64, camera::Running)>> = Mutex::new(Vec::new());
static NEXT_CAMERA: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

/// The cameras the system has, one name a line, in the order
/// `octo_camera_open` counts them. Writes up to `capacity` bytes and
/// answers the full length, or a negative number when it cannot tell.
///
/// # Safety
/// `buffer` must point to `capacity` writable bytes, or be null with 0.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_camera_names(buffer: *mut u8, capacity: usize) -> i64 {
    match catch_unwind(camera::names) {
        Ok(Ok(names)) => unsafe { write_lines(&names, buffer, capacity) },
        Ok(Err(_)) => CAMERA_FAILED as i64,
        Err(_) => PANICKED as i64,
    }
}

/// Starts camera `index`. Answers a handle above 0 for the other calls.
#[unsafe(no_mangle)]
pub extern "C" fn octo_camera_open(index: u32) -> i64 {
    let Ok(running) = catch_unwind(|| camera::open(index)) else { return PANICKED as i64 };
    let handle = NEXT_CAMERA.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    match CAMERAS.lock() {
        Ok(mut cameras) => {
            cameras.push((handle, running));
            handle
        }
        Err(_) => PANICKED as i64,
    }
}

/// Copies the camera's newest picture, when it is newer than `seen`, into
/// `buffer` as RGB, three bytes a pixel, and its width and height into
/// `size[0]` and `size[1]`. Answers the picture's number (above 0), 0 when
/// there is no newer one yet or it does not fit (the size says how big it
/// is), or CAMERA_FAILED once the camera has stopped.
///
/// # Safety
/// `buffer` must point to `capacity` writable bytes and `size` to two
/// writable numbers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_camera_picture(handle: i64, seen: u64, buffer: *mut u8, capacity: usize, size: *mut u32) -> i64 {
    if buffer.is_null() || size.is_null() {
        return BAD_ARGUMENT as i64;
    }
    let Ok(cameras) = CAMERAS.lock() else { return PANICKED as i64 };
    let Some((_, running)) = cameras.iter().find(|(h, _)| *h == handle) else { return BAD_ARGUMENT as i64 };
    let out = unsafe { std::slice::from_raw_parts_mut(buffer, capacity) };
    match running.copy_newest(seen, out) {
        Ok((serial, width, height)) => {
            unsafe {
                *size = width;
                *size.add(1) = height;
            }
            serial as i64
        }
        Err(_) => CAMERA_FAILED as i64,
    }
}

/// Stops the camera and lets it go.
#[unsafe(no_mangle)]
pub extern "C" fn octo_camera_close(handle: i64) {
    let running = CAMERAS.lock().ok().and_then(|mut cameras| {
        let at = cameras.iter().position(|(h, _)| *h == handle)?;
        Some(cameras.remove(at).1)
    });
    if let Some(running) = running {
        let _ = catch_unwind(AssertUnwindSafe(|| running.close()));
    }
}

// The taskbar and the jump list, on Windows only (version 3 on). Elsewhere
// every call answers NOT_SUPPORTED.

/// What a thumbnail button click asks for: 1 previous, 2 play or pause,
/// 3 next. Called on a thread of this library's own.
pub type ButtonCallback = extern "C" fn(button: i32);

/// Starts looking after the taskbar button of the app's window, `window`
/// being its handle (an HWND). Clicks come back through `callback`.
#[unsafe(no_mangle)]
pub extern "C" fn octo_taskbar_attach(window: isize, callback: Option<ButtonCallback>) -> i32 {
    #[cfg(windows)]
    return guarded(|| taskbar::attach(window, callback));
    #[cfg(not(windows))]
    {
        let _ = (window, callback);
        NOT_SUPPORTED
    }
}

/// Lets go of the window's taskbar button.
#[unsafe(no_mangle)]
pub extern "C" fn octo_taskbar_detach() {
    #[cfg(windows)]
    let _ = catch_unwind(taskbar::detach);
}

/// The size in pixels the thumbnail buttons' icons are drawn at, or a
/// negative number where there are none.
#[unsafe(no_mangle)]
pub extern "C" fn octo_taskbar_icon_size() -> i32 {
    #[cfg(windows)]
    return catch_unwind(taskbar::icon_size).unwrap_or(PANICKED);
    #[cfg(not(windows))]
    NOT_SUPPORTED
}

/// The four icons (previous, play, pause, next), each `size` by `size`
/// pixels of blue, green, red and alpha bytes, not premultiplied, rows top
/// down, one after another.
///
/// # Safety
/// `pixels` must point to `length` readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_taskbar_set_icons(pixels: *const u8, length: usize, size: i32) -> i32 {
    if pixels.is_null() {
        return BAD_ARGUMENT;
    }
    #[cfg(windows)]
    return guarded(|| taskbar::set_icons(unsafe { std::slice::from_raw_parts(pixels, length) }, size));
    #[cfg(not(windows))]
    {
        let _ = (length, size);
        NOT_SUPPORTED
    }
}

/// The buttons: `shown` 0 hides all three; `playing` puts pause in the
/// middle; the next three say which can be pressed; then each one's tip.
///
/// # Safety
/// The tips must be null or NUL terminated.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn octo_taskbar_set_buttons(
    shown: i32,
    playing: i32,
    previous: i32,
    toggle: i32,
    next: i32,
    previous_tip: *const c_char,
    toggle_tip: *const c_char,
    next_tip: *const c_char,
) -> i32 {
    #[cfg(windows)]
    return guarded(|| {
        taskbar::set_buttons(taskbar::Buttons {
            shown: shown != 0,
            playing: playing != 0,
            previous: previous != 0,
            toggle: toggle != 0,
            next: next != 0,
            previous_tip: unsafe { text(previous_tip) },
            toggle_tip: unsafe { text(toggle_tip) },
            next_tip: unsafe { text(next_tip) },
        })
    });
    #[cfg(not(windows))]
    {
        let _ = (shown, playing, previous, toggle, next, previous_tip, toggle_tip, next_tip);
        NOT_SUPPORTED
    }
}

/// The progress on the button: `kind` 0 none, 1 normal, 2 paused, 3 an
/// error; `done` out of `total`, in any unit.
#[unsafe(no_mangle)]
pub extern "C" fn octo_taskbar_set_progress(kind: i32, done: i64, total: i64) -> i32 {
    #[cfg(windows)]
    return guarded(|| {
        let kind = taskbar::ProgressKind::from_code(kind).ok_or(BAD_ARGUMENT)?;
        taskbar::set_progress(taskbar::Progress::new(kind, done, total))
    });
    #[cfg(not(windows))]
    {
        let _ = (kind, done, total);
        NOT_SUPPORTED
    }
}

/// Applies the buttons and progress now and answers how the taskbar took
/// them, for checking by hand. The app itself never waits on this.
#[unsafe(no_mangle)]
pub extern "C" fn octo_taskbar_sync_now() -> i32 {
    #[cfg(windows)]
    return guarded(taskbar::sync_now);
    #[cfg(not(windows))]
    NOT_SUPPORTED
}

// Writes `lines` joined by newlines into the caller's buffer, as much as
// fits, and answers the full length.
#[cfg_attr(not(windows), allow(dead_code))]
unsafe fn write_lines(lines: &[String], buffer: *mut u8, capacity: usize) -> i64 {
    let text = lines.join("\n");
    let bytes = text.as_bytes();
    if !buffer.is_null() {
        let n = bytes.len().min(capacity);
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer, n) };
    }
    bytes.len() as i64
}

/// Replaces the jump list. `items` holds one entry per line, its fields
/// split by tabs: heading, name, the command line Octo starts with, tip,
/// icon file (empty for the program's own) and icon number. `program` is
/// the program each entry starts. `app_id` is null for the app's own list.
/// The command lines of entries the listener took out of the list come
/// back one per line in `removed`, and the answer is their full length, or
/// a negative number when the list could not be set.
///
/// # Safety
/// The strings must be null or NUL terminated, and `removed` must point to
/// `capacity` writable bytes, or be null with 0.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_jump_list_set(
    app_id: *const c_char,
    program: *const c_char,
    items: *const c_char,
    removed: *mut u8,
    capacity: usize,
) -> i64 {
    #[cfg(windows)]
    {
        let id = unsafe { text(app_id) };
        let program = unsafe { text(program) };
        let items = jump_list::parse_items(&unsafe { text(items) });
        match catch_unwind(move || jump_list::set(Some(id).filter(|i| !i.is_empty()), program, items)) {
            Ok(Ok(gone)) => unsafe { write_lines(&gone, removed, capacity) },
            Ok(Err(code)) => code as i64,
            Err(_) => PANICKED as i64,
        }
    }
    #[cfg(not(windows))]
    {
        let _ = (app_id, program, items, removed, capacity);
        NOT_SUPPORTED as i64
    }
}

/// The command lines of the entries the listener took out of the jump
/// list, one per line, written as `octo_jump_list_set` writes them.
///
/// # Safety
/// As for `octo_jump_list_set`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_jump_list_removed(
    app_id: *const c_char,
    removed: *mut u8,
    capacity: usize,
) -> i64 {
    #[cfg(windows)]
    {
        let id = unsafe { text(app_id) };
        match catch_unwind(move || jump_list::removed(Some(id).filter(|i| !i.is_empty()))) {
            Ok(Ok(gone)) => unsafe { write_lines(&gone, removed, capacity) },
            Ok(Err(code)) => code as i64,
            Err(_) => PANICKED as i64,
        }
    }
    #[cfg(not(windows))]
    {
        let _ = (app_id, removed, capacity);
        NOT_SUPPORTED as i64
    }
}

/// Empties the jump list. `app_id` is null for the app's own.
///
/// # Safety
/// `app_id` must be null or NUL terminated.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn octo_jump_list_clear(app_id: *const c_char) -> i32 {
    #[cfg(windows)]
    {
        let id = unsafe { text(app_id) };
        guarded(move || jump_list::clear(Some(id).filter(|i| !i.is_empty())))
    }
    #[cfg(not(windows))]
    {
        let _ = app_id;
        NOT_SUPPORTED
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
        assert_eq!(rate(Status::Playing, 1.0), 1.0);
        assert_eq!(rate(Status::Playing, 1.5), 1.5);
        assert_eq!(rate(Status::Playing, 0.0), 0.0, "waiting for sound");
        assert_eq!(rate(Status::Playing, f64::NAN), 0.0);
        assert_eq!(rate(Status::Paused, 1.5), 0.0);
        assert_eq!(rate(Status::Stopped, 1.0), 0.0);
    }

    #[test]
    fn the_rate_call_checks_its_status_too() {
        assert_eq!(octo_system_set_playback_at_rate(9, 0, 0, 0, 1.5), BAD_ARGUMENT);
        assert_eq!(octo_system_version(), 4);
    }

    #[test]
    fn lines_are_written_as_far_as_they_fit() {
        let lines = vec!["octo://play/album/1".to_string(), "octo://play/album/2".to_string()];
        let mut small = [0u8; 10];
        let full = unsafe { write_lines(&lines, small.as_mut_ptr(), small.len()) };
        assert_eq!(full, 39);
        assert_eq!(&small, b"octo://pla");
        assert_eq!(unsafe { write_lines(&[], std::ptr::null_mut(), 0) }, 0);
        assert_eq!(unsafe { octo_taskbar_set_icons(std::ptr::null(), 0, 16) }, BAD_ARGUMENT);
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
