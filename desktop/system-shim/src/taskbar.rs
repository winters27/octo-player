//! Windows: the app's taskbar button. Three buttons on its thumbnail
//! (previous, play or pause, next) and the song's progress across it.
//!
//! The buttons live on the app's own window, so this library listens to
//! that window's messages (it wraps the window's message handler) for two
//! things only: a click on a button, and the taskbar making the window's
//! button again, which happens when the window comes back from the tray or
//! Explorer restarts, and after which the buttons must be added again.
//!
//! Every taskbar call runs on one thread of this library's own, with a
//! single-threaded apartment the taskbar object is made for. The app's
//! window thread only passes messages on, so it never waits on the taskbar.

use std::sync::Mutex;
use std::sync::atomic::{AtomicIsize, AtomicU32, Ordering};
use std::sync::mpsc;
use std::thread;

use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::Graphics::Gdi::{
    BI_RGB, BITMAPINFO, BITMAPINFOHEADER, CreateBitmap, CreateDIBSection, DIB_RGB_COLORS, DeleteObject,
    HBITMAP,
};
use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE, CoCreateInstance, CoInitializeEx,
    CoUninitialize,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Shell::{
    ITaskbarList3, TBPF_ERROR, TBPF_NOPROGRESS, TBPF_NORMAL, TBPF_PAUSED, TBPFLAG, THB_FLAGS, THB_ICON,
    THB_TOOLTIP, THBF_DISABLED, THBF_ENABLED, THBF_HIDDEN, THBN_CLICKED, THUMBBUTTON, TaskbarList,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CallWindowProcW, ChangeWindowMessageFilterEx, CreateIconIndirect, CreateWindowExW, DefWindowProcW,
    DestroyIcon, DestroyWindow, DispatchMessageW, GWLP_WNDPROC, GetMessageW, GetSystemMetrics,
    GetWindowLongPtrW, HICON, HWND_MESSAGE, ICONINFO, IsWindow, MSG, MSGFLT_ALLOW, PostMessageW,
    PostQuitMessage, RegisterClassW, RegisterWindowMessageW, SM_CXSMICON, SendMessageW, SetWindowLongPtrW,
    TranslateMessage, WINDOW_EX_STYLE, WM_APP, WM_CLOSE, WM_COMMAND, WM_DESTROY, WNDCLASSW, WNDPROC,
    WS_OVERLAPPED,
};
use windows::core::w;

use crate::{BAD_ARGUMENT, NOT_STARTED, PANICKED};

/// What a button click asks for, as the app hears it.
pub type TaskbarCallback = extern "C" fn(button: i32);

pub const BUTTON_PREVIOUS: u32 = 1;
pub const BUTTON_TOGGLE: u32 = 2;
pub const BUTTON_NEXT: u32 = 3;

/// The icons, in this order, in every call that hands them over.
pub const ICON_PREVIOUS: usize = 0;
pub const ICON_PLAY: usize = 1;
pub const ICON_PAUSE: usize = 2;
pub const ICON_NEXT: usize = 3;
pub const ICON_COUNT: usize = 4;

/// How the progress shows on the button.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum ProgressKind {
    #[default]
    None,
    Normal,
    Paused,
    Error,
}

impl ProgressKind {
    pub fn from_code(code: i32) -> Option<ProgressKind> {
        match code {
            0 => Some(ProgressKind::None),
            1 => Some(ProgressKind::Normal),
            2 => Some(ProgressKind::Paused),
            3 => Some(ProgressKind::Error),
            _ => None,
        }
    }

    fn flag(self) -> TBPFLAG {
        match self {
            ProgressKind::None => TBPF_NOPROGRESS,
            ProgressKind::Normal => TBPF_NORMAL,
            ProgressKind::Paused => TBPF_PAUSED,
            ProgressKind::Error => TBPF_ERROR,
        }
    }
}

/// The progress wanted: how it shows, and how far along, out of `total`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct Progress {
    pub kind: ProgressKind,
    pub done: u64,
    pub total: u64,
}

impl Progress {
    /// Kept inside the bar, with a length of at least 1 so Windows takes it.
    pub fn new(kind: ProgressKind, done: i64, total: i64) -> Progress {
        let total = total.max(1) as u64;
        Progress { kind, done: (done.max(0) as u64).min(total), total }
    }
}

/// The buttons wanted: which shows play and which pause, what each says,
/// and which can be pressed.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Buttons {
    pub shown: bool,
    pub playing: bool,
    pub previous: bool,
    pub toggle: bool,
    pub next: bool,
    pub previous_tip: String,
    pub toggle_tip: String,
    pub next_tip: String,
}

/// Up to 259 UTF-16 units and the ending NUL, as a button's tip holds.
pub fn tip(text: &str) -> [u16; 260] {
    let mut out = [0u16; 260];
    for (slot, unit) in out.iter_mut().take(259).zip(text.encode_utf16()) {
        *slot = unit;
    }
    out
}

/// The three buttons as the taskbar takes them.
pub fn thumb_buttons(buttons: &Buttons, icons: &[isize; ICON_COUNT]) -> [THUMBBUTTON; 3] {
    let flags = |enabled: bool| {
        if !buttons.shown {
            THBF_HIDDEN
        } else if enabled {
            THBF_ENABLED
        } else {
            THBF_DISABLED
        }
    };
    let one = |id: u32, icon: isize, text: &str, enabled: bool| THUMBBUTTON {
        dwMask: THB_ICON | THB_TOOLTIP | THB_FLAGS,
        iId: id,
        iBitmap: 0,
        hIcon: HICON(icon as *mut _),
        szTip: tip(text),
        dwFlags: flags(enabled),
    };
    [
        one(BUTTON_PREVIOUS, icons[ICON_PREVIOUS], &buttons.previous_tip, buttons.previous),
        one(
            BUTTON_TOGGLE,
            if buttons.playing { icons[ICON_PAUSE] } else { icons[ICON_PLAY] },
            &buttons.toggle_tip,
            buttons.toggle,
        ),
        one(BUTTON_NEXT, icons[ICON_NEXT], &buttons.next_tip, buttons.next),
    ]
}

// What the app wants shown, set from any thread and applied on the worker.
struct Wanted {
    window: isize,
    buttons: Buttons,
    progress: Progress,
    // Icons handed over and not yet taken by the worker.
    new_icons: Option<[isize; ICON_COUNT]>,
    callback: Option<TaskbarCallback>,
}

static WANTED: Mutex<Option<Wanted>> = Mutex::new(None);

// Read by the app window's message handler, which must not wait on a lock.
static APP_WINDOW: AtomicIsize = AtomicIsize::new(0);
static OLD_HANDLER: AtomicIsize = AtomicIsize::new(0);
static WORKER: AtomicIsize = AtomicIsize::new(0);
static BUTTON_CREATED: AtomicU32 = AtomicU32::new(0);

// The worker's own messages.
const SYNC: u32 = WM_APP + 1;
const RECREATED: u32 = WM_APP + 2;
const CLICKED: u32 = WM_APP + 3;

fn code(error: windows::core::Error) -> i32 {
    error.code().0
}

fn wanted<T>(work: impl FnOnce(&mut Wanted) -> T) -> Result<T, i32> {
    let mut guard = WANTED.lock().unwrap_or_else(|e| e.into_inner());
    guard.as_mut().map(work).ok_or(NOT_STARTED)
}

/// The size Windows draws the buttons' icons at, in pixels.
pub fn icon_size() -> i32 {
    let size = unsafe { GetSystemMetrics(SM_CXSMICON) };
    if size > 0 { size } else { 16 }
}

/// Starts looking after `window`'s taskbar button. Calling it again for
/// the same window only changes the callback.
pub fn attach(window: isize, callback: Option<TaskbarCallback>) -> Result<(), i32> {
    if window == 0 || !unsafe { IsWindow(Some(HWND(window as *mut _))) }.as_bool() {
        return Err(BAD_ARGUMENT);
    }
    {
        let mut guard = WANTED.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(existing) = guard.as_mut()
            && existing.window == window
        {
            existing.callback = callback;
            return Ok(());
        }
    }
    detach();
    // A window still wrapped by us cannot hand its handler over to another.
    if OLD_HANDLER.load(Ordering::SeqCst) != 0 {
        return Err(crate::NOT_SUPPORTED);
    }
    let created = unsafe { RegisterWindowMessageW(w!("TaskbarButtonCreated")) };
    BUTTON_CREATED.store(created, Ordering::SeqCst);
    let worker = worker_window()?;
    WORKER.store(worker, Ordering::SeqCst);
    *WANTED.lock().unwrap_or_else(|e| e.into_inner()) = Some(Wanted {
        window,
        buttons: Buttons::default(),
        progress: Progress::default(),
        new_icons: None,
        callback,
    });
    let hwnd = HWND(window as *mut _);
    unsafe {
        // Lets the taskbar's messages through should Octo ever run raised.
        let _ = ChangeWindowMessageFilterEx(hwnd, created, MSGFLT_ALLOW, None);
        let _ = ChangeWindowMessageFilterEx(hwnd, WM_COMMAND, MSGFLT_ALLOW, None);
        APP_WINDOW.store(window, Ordering::SeqCst);
        let old = SetWindowLongPtrW(hwnd, GWLP_WNDPROC, app_window_messages as usize as isize);
        OLD_HANDLER.store(old, Ordering::SeqCst);
    }
    // The window may already have its button, so the buttons are tried now
    // too; if not, the taskbar says when it is there.
    post(RECREATED, 0);
    Ok(())
}

/// Lets go of the window: its message handler goes back as it was (when
/// nothing wrapped it since), and the worker ends.
pub fn detach() {
    let window = APP_WINDOW.swap(0, Ordering::SeqCst);
    let old = OLD_HANDLER.load(Ordering::SeqCst);
    if window != 0 && old != 0 {
        let hwnd = HWND(window as *mut _);
        unsafe {
            // Something that wrapped the window after us still calls ours,
            // which then keeps passing everything on to the old handler.
            if IsWindow(Some(hwnd)).as_bool()
                && GetWindowLongPtrW(hwnd, GWLP_WNDPROC) == app_window_messages as usize as isize
            {
                SetWindowLongPtrW(hwnd, GWLP_WNDPROC, old);
                OLD_HANDLER.store(0, Ordering::SeqCst);
            }
        }
    }
    let worker = WORKER.swap(0, Ordering::SeqCst);
    if worker != 0 {
        unsafe {
            let _ = PostMessageW(Some(HWND(worker as *mut _)), WM_CLOSE, WPARAM(0), LPARAM(0));
        }
    }
    *WANTED.lock().unwrap_or_else(|e| e.into_inner()) = None;
}

/// Sets the four icons from 32-bit pixels, blue, green, red and alpha,
/// not premultiplied, rows top down, `size` by `size` each, one after
/// another in the order of the ICON_ constants.
pub fn set_icons(pixels: &[u8], size: i32) -> Result<(), i32> {
    if size <= 0 || size > 256 || pixels.len() != size as usize * size as usize * 4 * ICON_COUNT {
        return Err(BAD_ARGUMENT);
    }
    if WORKER.load(Ordering::SeqCst) == 0 {
        return Err(NOT_STARTED);
    }
    let one = size as usize * size as usize * 4;
    let mut made = [0isize; ICON_COUNT];
    for (index, slot) in made.iter_mut().enumerate() {
        match icon(&pixels[index * one..(index + 1) * one], size) {
            Ok(handle) => *slot = handle,
            Err(e) => {
                made.iter().filter(|h| **h != 0).for_each(|h| destroy_icon(*h));
                return Err(e);
            }
        }
    }
    let replaced = wanted(|w| w.new_icons.replace(made))?;
    // Icons handed over twice before the worker took the first are dropped.
    if let Some(old) = replaced {
        old.iter().for_each(|h| destroy_icon(*h));
    }
    post(SYNC, 0);
    Ok(())
}

pub fn set_buttons(buttons: Buttons) -> Result<(), i32> {
    let changed = wanted(|w| {
        let changed = w.buttons != buttons;
        w.buttons = buttons;
        changed
    })?;
    if changed {
        post(SYNC, 0);
    }
    Ok(())
}

pub fn set_progress(progress: Progress) -> Result<(), i32> {
    let changed = wanted(|w| {
        let changed = w.progress != progress;
        w.progress = progress;
        changed
    })?;
    if changed {
        post(SYNC, 0);
    }
    Ok(())
}

/// Applies everything now and answers how the taskbar took it. For
/// checking by hand and the tests; the app never waits on this.
pub fn sync_now() -> Result<(), i32> {
    let worker = WORKER.load(Ordering::SeqCst);
    if worker == 0 {
        return Err(NOT_STARTED);
    }
    let answer = unsafe { SendMessageW(HWND(worker as *mut _), SYNC, Some(WPARAM(1)), None) };
    if answer.0 == 0 { Ok(()) } else { Err(answer.0 as i32) }
}

fn post(message: u32, value: usize) {
    let worker = WORKER.load(Ordering::SeqCst);
    if worker != 0 {
        unsafe {
            let _ = PostMessageW(Some(HWND(worker as *mut _)), message, WPARAM(value), LPARAM(0));
        }
    }
}

// The app's window: a click on a button and a new taskbar button are
// passed to the worker; everything else goes on as before.
unsafe extern "system" fn app_window_messages(window: HWND, message: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    let old = OLD_HANDLER.load(Ordering::SeqCst);
    let created = BUTTON_CREATED.load(Ordering::SeqCst);
    if created != 0 && message == created {
        post(RECREATED, 0);
    } else if message == WM_COMMAND && ((w.0 >> 16) & 0xFFFF) as u32 == THBN_CLICKED {
        post(CLICKED, w.0 & 0xFFFF);
        return LRESULT(0);
    }
    if old == 0 {
        return unsafe { DefWindowProcW(window, message, w, l) };
    }
    let handler: WNDPROC = unsafe { std::mem::transmute::<isize, WNDPROC>(old) };
    unsafe { CallWindowProcW(handler, window, message, w, l) }
}

// The worker's state, only ever touched on its own thread.
struct Applied {
    list: Option<ITaskbarList3>,
    added: bool,
    icons: Option<[isize; ICON_COUNT]>,
    buttons: Option<Buttons>,
    progress: Option<Progress>,
}

thread_local! {
    static APPLIED: std::cell::RefCell<Applied> = const {
        std::cell::RefCell::new(Applied { list: None, added: false, icons: None, buttons: None, progress: None })
    };
}

// Brings the taskbar up to what is wanted. With `again` the button is new,
// so everything is sent afresh.
fn apply(again: bool) -> windows::core::Result<()> {
    let taken = {
        let mut guard = WANTED.lock().unwrap_or_else(|e| e.into_inner());
        guard.as_mut().map(|w| (w.window, w.buttons.clone(), w.progress, w.new_icons.take()))
    };
    let Some((window, buttons, progress, new_icons)) = taken else { return Ok(()) };
    let hwnd = HWND(window as *mut _);
    APPLIED.with(|cell| {
        let mut applied = cell.borrow_mut();
        if again {
            applied.list = None;
            applied.added = false;
            applied.buttons = None;
            applied.progress = None;
        }
        let mut retired = None;
        if let Some(icons) = new_icons {
            retired = applied.icons.replace(icons);
            applied.buttons = None;
        }
        let list = match &applied.list {
            Some(list) => list.clone(),
            None => {
                let list: ITaskbarList3 =
                    unsafe { CoCreateInstance(&TaskbarList, None, CLSCTX_INPROC_SERVER)? };
                unsafe { list.HrInit()? };
                applied.list = Some(list.clone());
                list
            }
        };
        let mut result = Ok(());
        if let Some(icons) = applied.icons
            && applied.buttons.as_ref() != Some(&buttons)
        {
            let thumb = thumb_buttons(&buttons, &icons);
            let sent = if applied.added {
                unsafe { list.ThumbBarUpdateButtons(hwnd, &thumb) }
            } else {
                unsafe { list.ThumbBarAddButtons(hwnd, &thumb) }
            };
            match sent {
                Ok(()) => {
                    applied.added = true;
                    applied.buttons = Some(buttons);
                }
                Err(e) => result = Err(e),
            }
        }
        // The icons the buttons used before are let go once replaced.
        if let Some(old) = retired {
            old.iter().for_each(|h| destroy_icon(*h));
        }
        if applied.progress != Some(progress) {
            let shown = unsafe {
                if progress.kind == ProgressKind::None {
                    list.SetProgressState(hwnd, TBPF_NOPROGRESS)
                } else {
                    // A value first, since a value turns "no progress" into normal.
                    list.SetProgressValue(hwnd, progress.done, progress.total)
                        .and_then(|_| list.SetProgressState(hwnd, progress.kind.flag()))
                }
            };
            match shown {
                Ok(()) => applied.progress = Some(progress),
                Err(e) => result = result.and(Err(e)),
            }
        }
        result
    })
}

fn emit_click(button: u32) {
    let callback = WANTED.lock().unwrap_or_else(|e| e.into_inner()).as_ref().and_then(|w| w.callback);
    if let Some(callback) = callback {
        callback(button as i32);
    }
}

// A message-only window on a thread of its own, where the taskbar is used.
fn worker_window() -> Result<isize, i32> {
    let (sender, receiver) = mpsc::channel::<Result<isize, i32>>();
    thread::Builder::new()
        .name("octo-taskbar".into())
        .spawn(move || unsafe {
            let apartment = CoInitializeEx(None, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
            let made = (|| -> windows::core::Result<HWND> {
                let module = GetModuleHandleW(None)?;
                let class = WNDCLASSW {
                    lpfnWndProc: Some(worker_messages),
                    hInstance: module.into(),
                    lpszClassName: w!("OctoTaskbarWorker"),
                    ..Default::default()
                };
                RegisterClassW(&class);
                CreateWindowExW(
                    WINDOW_EX_STYLE(0),
                    w!("OctoTaskbarWorker"),
                    w!("Octo taskbar"),
                    WS_OVERLAPPED,
                    0,
                    0,
                    0,
                    0,
                    Some(HWND_MESSAGE),
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
            APPLIED.with(|cell| {
                let mut applied = cell.borrow_mut();
                applied.list = None;
                if let Some(icons) = applied.icons.take() {
                    icons.iter().for_each(|h| destroy_icon(*h));
                }
            });
            if apartment.is_ok() {
                CoUninitialize();
            }
        })
        .map_err(|_| PANICKED)?;
    receiver.recv().map_err(|_| PANICKED)?
}

unsafe extern "system" fn worker_messages(window: HWND, message: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    match message {
        SYNC | RECREATED => {
            let result = std::panic::catch_unwind(|| apply(message == RECREATED));
            match result {
                Ok(Ok(())) => LRESULT(0),
                Ok(Err(e)) => LRESULT(e.code().0 as isize),
                Err(_) => LRESULT(PANICKED as isize),
            }
        }
        CLICKED => {
            let _ = std::panic::catch_unwind(|| emit_click(w.0 as u32));
            LRESULT(0)
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

// An icon from straight-alpha pixels, blue first, rows top down.
fn icon(pixels: &[u8], size: i32) -> Result<isize, i32> {
    unsafe {
        let info = BITMAPINFO {
            bmiHeader: BITMAPINFOHEADER {
                biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                biWidth: size,
                // Negative: the rows run top down.
                biHeight: -size,
                biPlanes: 1,
                biBitCount: 32,
                biCompression: BI_RGB.0,
                ..Default::default()
            },
            ..Default::default()
        };
        let mut bits: *mut core::ffi::c_void = std::ptr::null_mut();
        let color: HBITMAP =
            CreateDIBSection(None, &info, DIB_RGB_COLORS, &mut bits, None, 0).map_err(code)?;
        if bits.is_null() {
            let _ = DeleteObject(color.into());
            return Err(BAD_ARGUMENT);
        }
        std::ptr::copy_nonoverlapping(pixels.as_ptr(), bits as *mut u8, pixels.len());
        // With 32-bit colour the alpha decides; the mask is only required.
        let mask = CreateBitmap(size, size, 1, 1, None);
        let made = CreateIconIndirect(&ICONINFO {
            fIcon: true.into(),
            xHotspot: 0,
            yHotspot: 0,
            hbmMask: mask,
            hbmColor: color,
        });
        let _ = DeleteObject(color.into());
        let _ = DeleteObject(mask.into());
        made.map(|h| h.0 as isize).map_err(code)
    }
}

fn destroy_icon(handle: isize) {
    if handle != 0 {
        unsafe {
            let _ = DestroyIcon(HICON(handle as *mut _));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::UI::WindowsAndMessaging::{WS_EX_TOOLWINDOW, WS_POPUP};

    #[test]
    fn tips_are_cut_to_fit_and_end_in_nul() {
        let short = tip("Next");
        assert_eq!(&short[..4], &"Next".encode_utf16().collect::<Vec<_>>()[..]);
        assert_eq!(short[4], 0);
        let long = tip(&"a".repeat(400));
        assert_eq!(long[258], 'a' as u16);
        assert_eq!(long[259], 0);
    }

    #[test]
    fn the_middle_button_shows_pause_while_playing() {
        let icons = [11, 22, 33, 44];
        let playing = Buttons {
            shown: true,
            playing: true,
            previous: true,
            toggle: true,
            next: false,
            ..Default::default()
        };
        let thumb = thumb_buttons(&playing, &icons);
        assert_eq!(thumb.iter().map(|b| b.iId).collect::<Vec<_>>(), vec![1, 2, 3]);
        assert_eq!(thumb[0].hIcon.0 as isize, 11);
        assert_eq!(thumb[1].hIcon.0 as isize, 33, "pause while playing");
        assert_eq!(thumb[2].hIcon.0 as isize, 44);
        assert_eq!(thumb[2].dwFlags, THBF_DISABLED, "nothing next");
        assert_eq!(thumb[0].dwFlags, THBF_ENABLED);
        let paused = Buttons { playing: false, ..playing };
        assert_eq!(thumb_buttons(&paused, &icons)[1].hIcon.0 as isize, 22, "play while paused");
        let hidden = Buttons::default();
        assert!(thumb_buttons(&hidden, &icons).iter().all(|b| b.dwFlags == THBF_HIDDEN));
    }

    #[test]
    fn progress_stays_inside_the_bar() {
        assert_eq!(
            Progress::new(ProgressKind::Normal, 500, 1000),
            Progress { kind: ProgressKind::Normal, done: 500, total: 1000 }
        );
        assert_eq!(Progress::new(ProgressKind::Normal, 5000, 1000).done, 1000);
        assert_eq!(
            Progress::new(ProgressKind::Paused, -3, 0),
            Progress { kind: ProgressKind::Paused, done: 0, total: 1 }
        );
        assert_eq!(ProgressKind::from_code(3), Some(ProgressKind::Error));
        assert_eq!(ProgressKind::from_code(9), None);
    }

    // A window of the test's own, never shown and never on the taskbar.
    fn test_window() -> HWND {
        unsafe {
            let module = GetModuleHandleW(None).unwrap();
            let class = WNDCLASSW {
                lpfnWndProc: Some(test_messages),
                hInstance: module.into(),
                lpszClassName: w!("OctoTaskbarTestWindow"),
                ..Default::default()
            };
            RegisterClassW(&class);
            CreateWindowExW(
                WS_EX_TOOLWINDOW,
                w!("OctoTaskbarTestWindow"),
                w!("Octo taskbar test"),
                WS_POPUP,
                -32000,
                -32000,
                10,
                10,
                None,
                None,
                Some(module.into()),
                None,
            )
            .unwrap()
        }
    }

    unsafe extern "system" fn test_messages(window: HWND, message: u32, w: WPARAM, l: LPARAM) -> LRESULT {
        unsafe { DefWindowProcW(window, message, w, l) }
    }

    extern "C" fn heard(_: i32) {}

    #[test]
    fn a_hidden_window_of_our_own_takes_every_call() {
        let window = test_window();
        let handle = window.0 as isize;
        assert_eq!(attach(0, None), Err(BAD_ARGUMENT));
        attach(handle, Some(heard)).expect("attach");
        // The window's handler is wrapped, and goes back when let go.
        let wrapped = unsafe { GetWindowLongPtrW(window, GWLP_WNDPROC) };
        assert_eq!(wrapped, app_window_messages as usize as isize);
        let size = icon_size();
        assert!(size >= 16);
        let pixels = vec![0xFFu8; size as usize * size as usize * 4 * ICON_COUNT];
        set_icons(&pixels, size).expect("icons");
        assert_eq!(set_icons(&pixels[1..], size), Err(BAD_ARGUMENT));
        set_buttons(Buttons {
            shown: true,
            playing: true,
            previous: true,
            toggle: true,
            next: true,
            previous_tip: "Previous".into(),
            toggle_tip: "Pause".into(),
            next_tip: "Next".into(),
        })
        .expect("buttons");
        set_progress(Progress::new(ProgressKind::Normal, 30_000, 200_000)).expect("progress");
        // The window has no taskbar button, so what the taskbar answers is
        // reported rather than required; the calls themselves must go through.
        let answer = sync_now();
        println!("taskbar answered {answer:?} for a window with no button");
        set_progress(Progress::new(ProgressKind::None, 0, 0)).expect("no progress");
        let _ = sync_now();
        detach();
        let restored = unsafe { GetWindowLongPtrW(window, GWLP_WNDPROC) };
        assert_eq!(restored, test_messages as usize as isize);
        assert_eq!(set_buttons(Buttons::default()), Err(NOT_STARTED));
        unsafe {
            let _ = DestroyWindow(window);
        }
    }
}
