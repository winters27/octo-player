//! A silent device for tests and machines without sound: a thread that
//! pulls from the ring at the pace a real device would, optionally keeping
//! what it "played" for a test to look at.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use super::{DeviceEvent, Driver, OpenedOutput, OutputDevice, Renderer};
use crate::error::Failure;

/// Everything the null device played, interleaved in its channel count.
pub type Capture = Arc<Mutex<Vec<f32>>>;

pub struct NullDriver {
    rate: u32,
    channels: u16,
    // Buffers pulled per second of wall time, relative to real time.
    speed: f64,
    capture: Option<Capture>,
    stop: Arc<AtomicBool>,
    running: Arc<AtomicBool>,
    events: Arc<Mutex<Vec<DeviceEvent>>>,
}

impl NullDriver {
    pub fn new(rate: u32, channels: u16) -> Self {
        Self {
            rate,
            channels,
            speed: 1.0,
            capture: None,
            stop: Arc::new(AtomicBool::new(false)),
            running: Arc::new(AtomicBool::new(false)),
            events: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// Keeps what is played.
    pub fn capturing(mut self, capture: Capture) -> Self {
        self.capture = Some(capture);
        self
    }

    /// Runs faster (or slower) than real time.
    pub fn at_speed(mut self, speed: f64) -> Self {
        self.speed = speed.max(0.01);
        self
    }

    /// A handle to report device trouble, as a test.
    pub fn events(&self) -> Arc<Mutex<Vec<DeviceEvent>>> {
        self.events.clone()
    }

    fn device(&self) -> OutputDevice {
        OutputDevice { id: "null".into(), name: "No sound".into(), is_default: true }
    }
}

impl Driver for NullDriver {
    fn devices(&mut self) -> Vec<OutputDevice> {
        vec![self.device()]
    }

    fn default_device(&mut self) -> Option<OutputDevice> {
        Some(self.device())
    }

    fn open(
        &mut self,
        _id: Option<&str>,
        make: &mut dyn FnMut(u32, u16) -> Renderer,
    ) -> Result<OpenedOutput, Failure> {
        self.close();
        let mut renderer = make(self.rate, self.channels);
        let stop = Arc::new(AtomicBool::new(false));
        self.stop = stop.clone();
        self.running.store(true, Ordering::Release);
        let running = self.running.clone();
        let capture = self.capture.clone();
        let (rate, channels, speed) = (self.rate, self.channels as usize, self.speed);
        let period = rate as usize / 100; // 10 ms buffers
        let wait = Duration::from_secs_f64(0.01 / speed);
        thread::Builder::new()
            .name("octo-null-output".into())
            .spawn(move || {
                let mut buf = vec![0f32; period * channels];
                let mut next = Instant::now();
                while !stop.load(Ordering::Acquire) {
                    if running.load(Ordering::Acquire) {
                        renderer.render(&mut buf, Duration::ZERO);
                        if let Some(c) = &capture {
                            c.lock().unwrap().extend_from_slice(&buf);
                        }
                    }
                    // A steady pace like a real device's: after a late
                    // wake it catches up, unless it fell far behind.
                    next += wait;
                    let now = Instant::now();
                    if next > now {
                        thread::sleep(next - now);
                    } else if now - next > Duration::from_millis(200) {
                        next = now;
                    }
                }
            })
            .map_err(|e| Failure::new(crate::error::ErrorKind::Device, e.to_string()))?;
        Ok(OpenedOutput { rate: self.rate, channels: self.channels, device: self.device() })
    }

    fn close(&mut self) {
        self.stop.store(true, Ordering::Release);
    }

    fn set_running(&mut self, running: bool) {
        self.running.store(running, Ordering::Release);
    }

    fn take_events(&mut self) -> Vec<DeviceEvent> {
        self.events.lock().map(|mut l| std::mem::take(&mut *l)).unwrap_or_default()
    }
}

impl Drop for NullDriver {
    fn drop(&mut self) {
        self.close();
    }
}
