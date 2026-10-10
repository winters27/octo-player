//! Getting sound to the device. The mixing thread writes into a lock-free
//! ring; the device's callback reads it. The callback never locks, never
//! allocates and never waits: it only copies, applies the volume and the
//! short fades for pause, resume and jumps, and notes the time for the clock.

pub mod clock;
pub mod cpal_driver;
pub mod null;

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use crate::error::Failure;
use clock::Clock;

/// How long the fade is for pause, resume, volume changes and jumps.
pub const FADE_MS: f32 = 10.0;

/// A sound device the app can pick.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct OutputDevice {
    /// Stable across runs where the system allows; pass it back to choose it.
    pub id: String,
    pub name: String,
    pub is_default: bool,
}

/// Something the device side noticed, for the mixing thread to act on.
#[derive(Clone, Debug, PartialEq)]
pub enum DeviceEvent {
    /// The device is gone or the stream must be made again.
    Lost(String),
    /// The system moved the stream to another device by itself.
    Rerouted,
}

/// What the device's stream runs at, as it was opened: the rate, the
/// channels and the kind of samples the device is given.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct OutputFormat {
    pub sample_rate: u32,
    pub channels: u32,
    /// Like "f32", "i16", "i24", "i32": float or whole numbers, and their size.
    pub sample_format: String,
    /// The bits in each sample, when known.
    pub bits: Option<u32>,
}

impl OutputFormat {
    pub fn new(sample_rate: u32, channels: u16, sample_format: cpal::SampleFormat) -> Self {
        Self {
            sample_rate,
            channels: channels as u32,
            sample_format: sample_format.to_string(),
            bits: Some(sample_format.bits_per_sample()),
        }
    }
}

/// What an opened device runs at.
#[derive(Clone, Debug, PartialEq)]
pub struct OpenedOutput {
    pub format: OutputFormat,
    pub device: OutputDevice,
}

impl OpenedOutput {
    pub fn rate(&self) -> u32 {
        self.format.sample_rate
    }
}

/// A way to reach sound devices: the real one, or a silent one for tests.
pub trait Driver {
    fn devices(&mut self) -> Vec<OutputDevice>;
    /// The system's default device now.
    fn default_device(&mut self) -> Option<OutputDevice>;
    /// Opens `id`, or the default with `None`. `make` builds the callback's
    /// renderer once the rate and channels are known.
    fn open(
        &mut self,
        id: Option<&str>,
        make: &mut dyn FnMut(u32, u16) -> Renderer,
    ) -> Result<OpenedOutput, Failure>;
    fn close(&mut self);
    /// Stops or restarts the device stream, to let the device sleep during
    /// a long pause.
    fn set_running(&mut self, running: bool);
    fn take_events(&mut self) -> Vec<DeviceEvent>;
}

/// What the mixing thread and the callback share, all atomics.
pub struct OutputShared {
    volume: AtomicU32,
    paused: AtomicBool,
    // The callback drops what is in the ring up to this frame, after a fade.
    flush_to: AtomicU64,
    read: AtomicU64,
    underruns: AtomicU64,
    // Paused, faded out and not reading: nothing is sounding.
    silent: AtomicBool,
    // The mixer has sound flowing, so a dry ring is a glitch, not a start,
    // an end or a wait for the network.
    expect_sound: AtomicBool,
    // The mixer has given all it has: what is in the ring is the last of it.
    mix_ended: AtomicBool,
    // A blend is planned whose incoming song has no sound ready yet.
    blend_waiting: AtomicBool,
    pub clock: Clock,
}

impl OutputShared {
    pub fn new(rate: u32) -> Self {
        Self {
            volume: AtomicU32::new(1f32.to_bits()),
            paused: AtomicBool::new(true),
            flush_to: AtomicU64::new(0),
            read: AtomicU64::new(0),
            underruns: AtomicU64::new(0),
            silent: AtomicBool::new(true),
            expect_sound: AtomicBool::new(false),
            mix_ended: AtomicBool::new(false),
            blend_waiting: AtomicBool::new(false),
            clock: Clock::new(rate),
        }
    }

    /// The level after the shaping, as a factor (mute is 0).
    pub fn set_volume(&self, linear: f32) {
        self.volume.store(linear.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
    }

    pub fn set_paused(&self, paused: bool) {
        self.paused.store(paused, Ordering::Release);
        if !paused {
            self.silent.store(false, Ordering::Release);
        }
    }

    pub fn is_paused(&self) -> bool {
        self.paused.load(Ordering::Acquire)
    }

    /// Paused and fully faded out.
    pub fn is_silent(&self) -> bool {
        self.silent.load(Ordering::Acquire)
    }

    /// Drops everything written up to `frame`, with a short fade.
    pub fn flush_to(&self, frame: u64) {
        self.flush_to.fetch_max(frame, Ordering::AcqRel);
    }

    /// Ring frames the callback has taken, played or dropped.
    pub fn read_frames(&self) -> u64 {
        self.read.load(Ordering::Acquire)
    }

    /// Whether sound is flowing from the mixer, so the ring running dry
    /// counts as a glitch.
    pub fn set_expect_sound(&self, on: bool) {
        self.expect_sound.store(on, Ordering::Relaxed);
    }

    pub fn underruns(&self) -> u64 {
        self.underruns.load(Ordering::Relaxed)
    }

    /// Whether the mixer has given all it has, so no more sound will reach
    /// the ring until something new plays.
    pub fn set_mix_ended(&self, ended: bool) {
        self.mix_ended.store(ended, Ordering::Release);
    }

    pub fn mix_ended(&self) -> bool {
        self.mix_ended.load(Ordering::Acquire)
    }

    /// Whether a blend is planned whose incoming song has no sound ready
    /// yet: it starts only if that song is ready by then.
    pub fn set_blend_waiting(&self, waiting: bool) {
        self.blend_waiting.store(waiting, Ordering::Release);
    }

    pub fn blend_waiting(&self) -> bool {
        self.blend_waiting.load(Ordering::Acquire)
    }
}

/// The part that runs in the device callback.
pub struct Renderer {
    consumer: rtrb::Consumer<f32>,
    shared: Arc<OutputShared>,
    channels: usize,
    read: u64,
    gain: f32,
    pause_gain: f32,
    step: f32,
    flushing: bool,
    starved: bool,
}

/// Makes the ring between the mixing thread and a renderer, holding
/// `frames` stereo frames.
pub fn ring(frames: usize) -> (rtrb::Producer<f32>, rtrb::Consumer<f32>) {
    rtrb::RingBuffer::new(frames * 2)
}

impl Renderer {
    pub fn new(consumer: rtrb::Consumer<f32>, shared: Arc<OutputShared>, rate: u32, channels: u16) -> Self {
        let ramp_frames = (rate as f32 * FADE_MS / 1_000.0).max(1.0);
        let start = shared.read_frames();
        Self {
            consumer,
            shared,
            channels: channels.max(1) as usize,
            read: start,
            gain: 0.0,
            pause_gain: 0.0,
            step: 1.0 / ramp_frames,
            flushing: false,
            starved: false,
        }
    }

    /// Fills one device buffer. `latency` is how long until its first frame
    /// is heard.
    pub fn render<T: cpal::SizedSample + cpal::FromSample<f32>>(&mut self, out: &mut [T], latency: Duration) {
        self.render_heard_at(out, Instant::now() + latency);
    }

    /// Fills one device buffer whose first frame is heard at `heard_at`.
    pub fn render_heard_at<T: cpal::SizedSample + cpal::FromSample<f32>>(
        &mut self,
        out: &mut [T],
        heard_at: Instant,
    ) {
        let ch = self.channels;
        let frames = out.len() / ch;
        let volume = f32::from_bits(self.shared.volume.load(Ordering::Relaxed));
        let paused = self.shared.paused.load(Ordering::Acquire);
        let flush_to = self.shared.flush_to.load(Ordering::Acquire);
        if flush_to > self.read {
            self.flushing = true;
        }
        let mut i = 0;
        let mut last_played: Option<(u64, usize)> = None;

        // A jump: fade out what is playing, then drop the rest of it.
        if self.flushing {
            let old = (flush_to.saturating_sub(self.read) as usize).min(self.consumer.slots() / 2);
            let fade = ((self.pause_gain / self.step).ceil() as usize).min(old).min(frames);
            i = self.play(out, 0, fade, volume, 0.0, &mut last_played);
            if self.pause_gain <= 0.0 || i == old {
                let drop = (flush_to.saturating_sub(self.read) as usize).min(self.consumer.slots() / 2);
                if let Ok(chunk) = self.consumer.read_chunk(drop * 2) {
                    chunk.commit_all();
                }
                self.read += drop as u64;
                self.pause_gain = 0.0;
                if self.read >= flush_to {
                    self.flushing = false;
                }
            }
        }

        let want = if paused || self.flushing { 0.0 } else { 1.0 };
        if !(want == 0.0 && self.pause_gain <= 0.0) {
            let avail = self.consumer.slots() / 2;
            // Paused: only as far as the fade-out goes.
            let limit = if want == 0.0 { (self.pause_gain / self.step).ceil() as usize } else { usize::MAX };
            let n = (frames - i).min(avail).min(limit);
            i = self.play(out, i, n, volume, want, &mut last_played);
            if i < frames && want > 0.0 {
                // The ring ran dry while playing.
                if !self.starved && self.shared.expect_sound.load(Ordering::Relaxed) {
                    self.shared.underruns.fetch_add(1, Ordering::Relaxed);
                }
                self.starved = true;
            } else {
                self.starved = false;
            }
        }
        // Silence for the rest.
        for s in &mut out[i * ch..] {
            *s = T::from_sample(0.0);
        }
        self.shared.silent.store(paused && self.pause_gain <= 0.0, Ordering::Release);
        self.shared.read.store(self.read, Ordering::Release);
        if let Some((index, offset)) = last_played {
            self.shared.clock.record(index, offset, heard_at);
        }
    }

    /// Ring frames the next buffer can play: what is waiting, less what a
    /// jump still has to drop.
    pub fn playable_frames(&self) -> usize {
        let flush_to = self.shared.flush_to.load(Ordering::Acquire);
        let dropping = flush_to.saturating_sub(self.read) as usize;
        (self.consumer.slots() / 2).saturating_sub(dropping)
    }

    /// Whether the next buffer starts with a jump: a fade out and a drop.
    pub fn jump_pending(&self) -> bool {
        self.flushing || self.shared.flush_to.load(Ordering::Acquire) > self.read
    }

    /// Whether the ring holds no sound at all.
    pub fn ring_empty(&self) -> bool {
        self.consumer.slots() == 0
    }

    pub fn shared(&self) -> &Arc<OutputShared> {
        &self.shared
    }

    // Plays `n` frames from the ring into `out` from frame `at`, ramping
    // the pause gain toward `want`. Returns the frame after the last written.
    fn play<T: cpal::SizedSample + cpal::FromSample<f32>>(
        &mut self,
        out: &mut [T],
        at: usize,
        n: usize,
        volume: f32,
        want: f32,
        last_played: &mut Option<(u64, usize)>,
    ) -> usize {
        if n == 0 {
            return at;
        }
        let Ok(chunk) = self.consumer.read_chunk(n * 2) else { return at };
        let (first, second) = chunk.as_slices();
        let ch = self.channels;
        let (mut gain, mut pause_gain, step) = (self.gain, self.pause_gain, self.step);
        let mut frame = at;
        for pair in first.chunks_exact(2).chain(second.chunks_exact(2)) {
            // Volume and pause each move at most one ramp step per frame.
            gain += (volume - gain).clamp(-step, step);
            pause_gain += (want - pause_gain).clamp(-step, step);
            let g = gain * pause_gain;
            let (l, r) = (pair[0] * g, pair[1] * g);
            let dst = &mut out[frame * ch..(frame + 1) * ch];
            if ch == 1 {
                dst[0] = T::from_sample((l + r) * 0.5);
            } else {
                dst[0] = T::from_sample(l);
                dst[1] = T::from_sample(r);
                for extra in &mut dst[2..] {
                    *extra = T::from_sample(0.0);
                }
            }
            frame += 1;
        }
        chunk.commit_all();
        self.gain = gain;
        self.pause_gain = pause_gain;
        self.read += n as u64;
        *last_played = Some((self.read, frame));
        frame
    }
}
