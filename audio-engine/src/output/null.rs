//! A silent device for tests and machines without sound: a thread that
//! pulls from the ring at the pace a real device would, optionally keeping
//! what it "played" for a test to look at. Driven by hand instead, it plays
//! a buffer only when a test asks, so the time it keeps is the test's.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use super::{DeviceEvent, Driver, OpenedOutput, OutputDevice, OutputFormat, Renderer};
use crate::error::Failure;

/// Everything the null device played, interleaved in its channel count.
pub type Capture = Arc<Mutex<Vec<f32>>>;

// The renderer of a device driven by hand, while it is open.
type Slot = Arc<Mutex<Option<Renderer>>>;

pub struct NullDriver {
    rate: u32,
    channels: u16,
    // Buffers pulled per second of wall time, relative to real time.
    speed: f64,
    capture: Option<Capture>,
    stop: Arc<AtomicBool>,
    running: Arc<AtomicBool>,
    events: Arc<Mutex<Vec<DeviceEvent>>>,
    refuse: Arc<AtomicBool>,
    driven: Option<Slot>,
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
            refuse: Arc::new(AtomicBool::new(false)),
            driven: None,
        }
    }

    /// A device with no pace of its own: it plays a buffer each time the
    /// returned pump is told to, and nothing in between.
    pub fn driven(rate: u32, channels: u16, capture: Option<Capture>) -> (Self, Pump) {
        let slot: Slot = Arc::new(Mutex::new(None));
        let mut driver = Self::new(rate, channels);
        driver.driven = Some(slot.clone());
        let pump = Pump { slot, capture, channels: channels as usize, period: rate as usize / 100 };
        (driver, pump)
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

    /// A handle to make the device refuse to open while set, as a test, the
    /// way an unplugged one does.
    pub fn refusal(&self) -> Arc<AtomicBool> {
        self.refuse.clone()
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
        if self.refuse.load(Ordering::Acquire) {
            return Err(Failure::new(crate::error::ErrorKind::Device, "no sound device"));
        }
        let mut renderer = make(self.rate, self.channels);
        // Takes floats, as most systems' shared mixers do.
        let format = OutputFormat::new(self.rate, self.channels, cpal::SampleFormat::F32);
        if let Some(slot) = &self.driven {
            *slot.lock().unwrap() = Some(renderer);
            return Ok(OpenedOutput { format, device: self.device() });
        }
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
        Ok(OpenedOutput { format, device: self.device() })
    }

    fn close(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(slot) = &self.driven {
            slot.lock().unwrap().take();
        }
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

/// Plays the buffers of a device made with [`NullDriver::driven`].
pub struct Pump {
    slot: Slot,
    capture: Option<Capture>,
    channels: usize,
    period: usize,
}

impl Pump {
    // Long enough for any machine, however busy, to get a buffer ready.
    const PATIENCE: Duration = Duration::from_secs(30);

    /// Plays one 10 ms buffer, heard from the moment it is played, and
    /// returns that moment. Waits first for the device to be open and for
    /// a whole buffer of sound, unless a jump is to be played, the mixer has
    /// nothing more to give or playback is paused: a buffer is never cut
    /// short because the machine was slow to make it. Also waits while a
    /// planned blend's incoming song has no sound ready, so the blend is
    /// never reached before that song could have been read.
    pub fn play(&self) -> Instant {
        let until = Instant::now() + Self::PATIENCE;
        let mut buf = vec![0f32; self.period * self.channels];
        loop {
            let mut slot = self.slot.lock().unwrap();
            if let Some(renderer) = slot.as_mut() {
                let shared = renderer.shared();
                let full = renderer.playable_frames() >= self.period && !shared.blend_waiting();
                if full || renderer.jump_pending() || shared.mix_ended() || shared.is_paused() {
                    let at = Instant::now();
                    renderer.render_heard_at(&mut buf, at);
                    drop(slot);
                    if let Some(c) = &self.capture {
                        c.lock().unwrap().extend_from_slice(&buf);
                    }
                    return at;
                }
            }
            drop(slot);
            assert!(Instant::now() < until, "no buffer of sound became ready to play");
            thread::sleep(Duration::from_millis(1));
        }
    }

    /// Plays buffers until the mixer has given all it has and all of it
    /// has been played.
    pub fn play_out(&self) {
        loop {
            {
                let slot = self.slot.lock().unwrap();
                if let Some(renderer) = slot.as_ref()
                    && renderer.shared().mix_ended()
                    && renderer.ring_empty()
                {
                    return;
                }
            }
            self.play();
        }
    }
}
