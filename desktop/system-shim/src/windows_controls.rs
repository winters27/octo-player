//! Windows: the System Media Transport Controls, the panel behind the media
//! keys, the volume flyout, the lock screen and Bluetooth headset buttons.
//!
//! A desktop app reaches them through a window of its own. This library
//! makes a hidden one on its own thread, so they keep working while Octo's
//! window is closed to the tray, and that window also hears the machine
//! going to sleep and waking.

use std::sync::Mutex;
use std::sync::mpsc;
use std::thread;

use windows::Foundation::{TimeSpan, TypedEventHandler};
use windows::Media::Control::GlobalSystemMediaTransportControlsSessionManager;
use windows::Media::{
    MediaPlaybackStatus, MediaPlaybackType, PlaybackPositionChangeRequestedEventArgs,
    SystemMediaTransportControls, SystemMediaTransportControlsButton,
    SystemMediaTransportControlsButtonPressedEventArgs, SystemMediaTransportControlsTimelineProperties,
};
use windows::Storage::Streams::{DataWriter, InMemoryRandomAccessStream, RandomAccessStreamReference};
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::Com::CoIncrementMTAUsage;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::WinRT::ISystemMediaTransportControlsInterop;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetMessageW, MSG,
    PBT_APMRESUMEAUTOMATIC, PBT_APMSUSPEND, PostMessageW, PostQuitMessage, RegisterClassW, TranslateMessage,
    WINDOW_EX_STYLE, WM_CLOSE, WM_DESTROY, WM_POWERBROADCAST, WNDCLASSW, WS_OVERLAPPEDWINDOW,
};
use windows::core::{HSTRING, Ref, w};

use crate::{
    EVENT_NEXT, EVENT_PAUSE, EVENT_PLAY, EVENT_PREVIOUS, EVENT_SEEK, EVENT_SLEEP, EVENT_STOP, EVENT_WAKE,
    NOT_STARTED, Status, Track, emit,
};

struct Controls {
    smtc: SystemMediaTransportControls,
    window: isize,
    button_token: i64,
    seek_token: i64,
    // The song's length, which the timeline needs with every position.
    duration_ms: i64,
}

// The controls are agile WinRT objects, and every call to them is made
// under the lock below.
unsafe impl Send for Controls {}

static CONTROLS: Mutex<Option<Controls>> = Mutex::new(None);

fn code(error: windows::core::Error) -> i32 {
    error.code().0
}

fn with_controls<T>(work: impl FnOnce(&mut Controls) -> windows::core::Result<T>) -> Result<T, i32> {
    let mut guard = CONTROLS.lock().unwrap_or_else(|e| e.into_inner());
    let controls = guard.as_mut().ok_or(NOT_STARTED)?;
    work(controls).map_err(code)
}

pub fn start() -> Result<(), i32> {
    let mut guard = CONTROLS.lock().unwrap_or_else(|e| e.into_inner());
    if guard.is_some() {
        return Ok(());
    }
    // Keeps a multithreaded apartment alive for the whole process, so any
    // thread that calls in can use WinRT without setting it up first.
    unsafe { CoIncrementMTAUsage() }.map_err(code)?;
    let window = hidden_window()?;
    let controls = connect(HWND(window as *mut _)).map_err(|e| {
        close_window(window);
        code(e)
    })?;
    *guard = Some(Controls { window, ..controls });
    Ok(())
}

fn connect(window: HWND) -> windows::core::Result<Controls> {
    let interop =
        windows::core::factory::<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>()?;
    let smtc: SystemMediaTransportControls = unsafe { interop.GetForWindow(window)? };
    smtc.SetIsEnabled(true)?;
    smtc.SetIsPlayEnabled(true)?;
    smtc.SetIsPauseEnabled(true)?;
    smtc.SetIsStopEnabled(true)?;
    smtc.SetIsNextEnabled(false)?;
    smtc.SetIsPreviousEnabled(false)?;
    smtc.SetPlaybackStatus(MediaPlaybackStatus::Closed)?;
    let button_token = smtc.ButtonPressed(&TypedEventHandler::new(
        |_, args: Ref<SystemMediaTransportControlsButtonPressedEventArgs>| {
            if let Some(args) = args.as_ref() {
                let kind = match args.Button()? {
                    SystemMediaTransportControlsButton::Play => EVENT_PLAY,
                    SystemMediaTransportControlsButton::Pause => EVENT_PAUSE,
                    SystemMediaTransportControlsButton::Stop => EVENT_STOP,
                    SystemMediaTransportControlsButton::Next => EVENT_NEXT,
                    SystemMediaTransportControlsButton::Previous => EVENT_PREVIOUS,
                    _ => return Ok(()),
                };
                emit(kind, 0);
            }
            Ok(())
        },
    ))?;
    let seek_token = smtc.PlaybackPositionChangeRequested(&TypedEventHandler::new(
        |_, args: Ref<PlaybackPositionChangeRequestedEventArgs>| {
            if let Some(args) = args.as_ref() {
                emit(EVENT_SEEK, args.RequestedPlaybackPosition()?.Duration / TICKS_PER_MS);
            }
            Ok(())
        },
    ))?;
    Ok(Controls { smtc, window: 0, button_token, seek_token, duration_ms: 0 })
}

// Timeline values are in ticks of 100 nanoseconds.
const TICKS_PER_MS: i64 = 10_000;

fn span(ms: i64) -> TimeSpan {
    TimeSpan { Duration: ms.max(0) * TICKS_PER_MS }
}

pub fn set_track(track: &Track) -> Result<(), i32> {
    with_controls(|controls| {
        let display = controls.smtc.DisplayUpdater()?;
        display.ClearAll()?;
        display.SetType(MediaPlaybackType::Music)?;
        let music = display.MusicProperties()?;
        music.SetTitle(&HSTRING::from(&track.title))?;
        music.SetArtist(&HSTRING::from(&track.artist))?;
        music.SetAlbumTitle(&HSTRING::from(&track.album))?;
        music.SetAlbumArtist(&HSTRING::from(&track.album_artist))?;
        match &track.art {
            Some(bytes) => display.SetThumbnail(&picture(bytes)?)?,
            None => display.SetThumbnail(None)?,
        }
        display.Update()?;
        controls.duration_ms = track.duration_ms;
        timeline(controls, 0)
    })
}

// The cover as a stream the system can read.
fn picture(bytes: &[u8]) -> windows::core::Result<RandomAccessStreamReference> {
    let stream = InMemoryRandomAccessStream::new()?;
    let writer = DataWriter::CreateDataWriter(&stream)?;
    writer.WriteBytes(bytes)?;
    writer.StoreAsync()?.join()?;
    writer.FlushAsync()?.join()?;
    writer.DetachStream()?;
    stream.Seek(0)?;
    RandomAccessStreamReference::CreateFromStream(&stream)
}

fn timeline(controls: &Controls, position_ms: i64) -> windows::core::Result<()> {
    let line = SystemMediaTransportControlsTimelineProperties::new()?;
    let end = controls.duration_ms.max(0);
    line.SetStartTime(span(0))?;
    line.SetEndTime(span(end))?;
    line.SetMinSeekTime(span(0))?;
    line.SetMaxSeekTime(span(end))?;
    line.SetPosition(span(if end > 0 { position_ms.min(end) } else { position_ms }))?;
    controls.smtc.UpdateTimelineProperties(&line)
}

pub fn set_playback(status: Status, position_ms: i64, can_previous: bool, can_next: bool) -> Result<(), i32> {
    with_controls(|controls| {
        let smtc = &controls.smtc;
        smtc.SetPlaybackStatus(match status {
            Status::Stopped => MediaPlaybackStatus::Stopped,
            Status::Playing => MediaPlaybackStatus::Playing,
            Status::Paused => MediaPlaybackStatus::Paused,
        })?;
        smtc.SetIsPreviousEnabled(can_previous)?;
        smtc.SetIsNextEnabled(can_next)?;
        timeline(controls, position_ms)
    })
}

pub fn clear() -> Result<(), i32> {
    with_controls(|controls| {
        let display = controls.smtc.DisplayUpdater()?;
        display.ClearAll()?;
        display.Update()?;
        controls.duration_ms = 0;
        controls.smtc.SetPlaybackStatus(MediaPlaybackStatus::Closed)
    })
}

pub fn stop() {
    let taken = CONTROLS.lock().unwrap_or_else(|e| e.into_inner()).take();
    if let Some(controls) = taken {
        let _ = controls.smtc.RemoveButtonPressed(controls.button_token);
        let _ = controls.smtc.RemovePlaybackPositionChangeRequested(controls.seek_token);
        let _ = controls.smtc.DisplayUpdater().and_then(|d| {
            d.ClearAll()?;
            d.Update()
        });
        let _ = controls.smtc.SetPlaybackStatus(MediaPlaybackStatus::Closed);
        let _ = controls.smtc.SetIsEnabled(false);
        close_window(controls.window);
    }
}

pub fn describe_sessions() -> Result<String, i32> {
    describe().map_err(code)
}

fn describe() -> windows::core::Result<String> {
    unsafe { CoIncrementMTAUsage() }?;
    let manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync()?.join()?;
    let sessions = manager.GetSessions()?;
    let mut lines = Vec::new();
    for session in &sessions {
        let about = session.TryGetMediaPropertiesAsync()?.join()?;
        let status = session.GetPlaybackInfo()?.PlaybackStatus()?;
        let line = session.GetTimelineProperties()?;
        lines.push(format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            session.SourceAppUserModelId()?,
            about.Title()?,
            about.Artist()?,
            about.AlbumTitle()?,
            status.0,
            line.Position()?.Duration / TICKS_PER_MS,
            line.EndTime()?.Duration / TICKS_PER_MS,
            if about.Thumbnail().is_ok() { 1 } else { 0 },
        ));
    }
    Ok(lines.join("\n"))
}

// A window that is never shown, with its own thread to hear messages on.
fn hidden_window() -> Result<isize, i32> {
    let (sender, receiver) = mpsc::channel::<Result<isize, i32>>();
    thread::Builder::new()
        .name("octo-system-window".into())
        .spawn(move || unsafe {
            let made = (|| -> windows::core::Result<HWND> {
                let module = GetModuleHandleW(None)?;
                let class = WNDCLASSW {
                    lpfnWndProc: Some(window_messages),
                    hInstance: module.into(),
                    lpszClassName: w!("OctoSystemWindow"),
                    ..Default::default()
                };
                // A second start after a stop finds the class already there.
                RegisterClassW(&class);
                CreateWindowExW(
                    WINDOW_EX_STYLE(0),
                    w!("OctoSystemWindow"),
                    w!("Octo"),
                    WS_OVERLAPPEDWINDOW,
                    0,
                    0,
                    0,
                    0,
                    None,
                    None,
                    Some(module.into()),
                    None,
                )
            })();
            match made {
                Ok(window) => {
                    let _ = sender.send(Ok(window.0 as isize));
                    let mut message = MSG::default();
                    while GetMessageW(&mut message, None, 0, 0).as_bool() {
                        let _ = TranslateMessage(&message);
                        DispatchMessageW(&message);
                    }
                }
                Err(e) => {
                    let _ = sender.send(Err(code(e)));
                }
            }
        })
        .map_err(|_| crate::PANICKED)?;
    receiver.recv().map_err(|_| crate::PANICKED)?
}

fn close_window(window: isize) {
    if window != 0 {
        unsafe {
            let _ = PostMessageW(Some(HWND(window as *mut _)), WM_CLOSE, WPARAM(0), LPARAM(0));
        }
    }
}

unsafe extern "system" fn window_messages(window: HWND, message: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    match message {
        WM_POWERBROADCAST => {
            match w.0 as u32 {
                PBT_APMSUSPEND => emit(EVENT_SLEEP, 0),
                PBT_APMRESUMEAUTOMATIC => emit(EVENT_WAKE, 0),
                _ => {}
            }
            LRESULT(1)
        }
        WM_CLOSE => {
            unsafe {
                let _ = DestroyWindow(window);
            }
            LRESULT(0)
        }
        WM_DESTROY => {
            unsafe { PostQuitMessage(0) };
            LRESULT(0)
        }
        _ => unsafe { DefWindowProcW(window, message, w, l) },
    }
}
