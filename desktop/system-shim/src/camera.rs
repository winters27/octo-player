//! A webcam, for reading QR codes: the cameras the system has, and the
//! newest picture from one, as plain RGB. The camera runs on a thread of
//! its own (Windows wants it on the thread that opened it); the app asks
//! for the newest picture whenever it wants one, and the QR code is read
//! on the app's side.

use nokhwa::Camera;
use nokhwa::pixel_format::RgbFormat;
use nokhwa::utils::{ApiBackend, CameraIndex, RequestedFormat, RequestedFormatType};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

/// The newest picture: its size and its pixels, three bytes each, row by row.
#[derive(Default)]
struct Picture {
    width: u32,
    height: u32,
    rgb: Vec<u8>,
    // Counts up with each new picture, so the app can tell a new one.
    serial: u64,
    // Why the camera stopped, when it did.
    failed: Option<String>,
}

pub struct Running {
    picture: Arc<Mutex<Picture>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

/// The cameras the system has, by name, in the order `open` counts them.
pub fn names() -> Result<Vec<String>, String> {
    let backend = nokhwa::native_api_backend().unwrap_or(ApiBackend::Auto);
    nokhwa::query(backend)
        .map(|cameras| cameras.into_iter().map(|info| info.human_name()).collect())
        .map_err(|e| e.to_string())
}

/// Starts camera `index` on a thread of its own. It keeps the newest
/// picture until stopped.
pub fn open(index: u32) -> Running {
    let picture = Arc::new(Mutex::new(Picture::default()));
    let stop = Arc::new(AtomicBool::new(false));
    let thread = {
        let picture = picture.clone();
        let stop = stop.clone();
        std::thread::Builder::new()
            .name("octo-camera".into())
            .spawn(move || run(index, &picture, &stop))
            .ok()
    };
    Running { picture, stop, thread }
}

fn run(index: u32, picture: &Mutex<Picture>, stop: &AtomicBool) {
    let fail = |why: String| {
        if let Ok(mut now) = picture.lock() {
            now.failed = Some(why);
        }
    };
    let format = RequestedFormat::new::<RgbFormat>(RequestedFormatType::AbsoluteHighestFrameRate);
    let mut camera = match Camera::new(CameraIndex::Index(index), format) {
        Ok(camera) => camera,
        Err(e) => return fail(e.to_string()),
    };
    if let Err(e) = camera.open_stream() {
        return fail(e.to_string());
    }
    while !stop.load(Ordering::Relaxed) {
        let frame = match camera.frame() {
            Ok(frame) => frame,
            Err(e) => {
                fail(e.to_string());
                break;
            }
        };
        let Ok(image) = frame.decode_image::<RgbFormat>() else { continue };
        if let Ok(mut now) = picture.lock() {
            now.width = image.width();
            now.height = image.height();
            now.rgb = image.into_raw();
            now.serial += 1;
        }
    }
    let _ = camera.stop_stream();
}

impl Running {
    /// Copies the newest picture into `out` when it is newer than `seen`.
    /// Answers its serial, or 0 when there is none newer yet, and the size.
    pub fn copy_newest(&self, seen: u64, out: &mut [u8]) -> Result<(u64, u32, u32), String> {
        let now = self.picture.lock().map_err(|_| "camera lock".to_string())?;
        if let Some(why) = &now.failed {
            return Err(why.clone());
        }
        if now.serial == seen || now.rgb.is_empty() {
            return Ok((0, now.width, now.height));
        }
        if out.len() < now.rgb.len() {
            return Ok((0, now.width, now.height));
        }
        out[..now.rgb.len()].copy_from_slice(&now.rgb);
        Ok((now.serial, now.width, now.height))
    }

    pub fn close(mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
