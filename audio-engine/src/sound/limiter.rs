//! The look-ahead limiter, with the Android app's numbers (`Limiter.kt`).

/// Just below full scale, where the limiter holds peaks.
pub const LIMITER_CEILING_DB: f32 = -1.0;

/// How far ahead the limiter looks, and how slowly the level comes back.
pub const LIMITER_LOOKAHEAD_MS: f32 = 1.5;
pub const LIMITER_RELEASE_MS: f32 = 100.0;

/// Keeps peaks from going over the ceiling. The sound runs a moment late, so
/// the limiter sees a peak coming and turns the level down in time instead
/// of clipping it, then lets the level back up slowly so the change is not
/// heard. All channels move together, so the stereo picture stays put.
///
/// Here the limiter sits on one continuous stream after the mix, so songs
/// flow through it without anything held back between them.
#[derive(Debug)]
pub struct Limiter {
    channels: usize,
    latency: usize,
    /// Off, the level returns to unity and the sound only passes through
    /// the delay, so the delay never changes.
    pub enabled: bool,
    ceiling: f32,
    release: f32,
    // The delayed frames, oldest first from `head`.
    held: Vec<f32>,
    head: usize,
    held_frames: usize,
    // The level each frame needs, kept as a rising queue so the lowest one
    // in the look-ahead window is always at the front.
    need: Vec<f32>,
    need_at: Vec<u64>,
    need_front: usize,
    need_size: usize,
    // Counts frames in and out, to know which needs are still in view.
    in_count: u64,
    out_count: u64,
    // The level applied now.
    gain: f32,
}

impl Limiter {
    pub fn new(channels: usize, sample_rate: u32) -> Self {
        Self::with(channels, sample_rate, LIMITER_CEILING_DB, LIMITER_LOOKAHEAD_MS, LIMITER_RELEASE_MS)
    }

    pub fn with(
        channels: usize,
        sample_rate: u32,
        ceiling_db: f32,
        lookahead_ms: f32,
        release_ms: f32,
    ) -> Self {
        let latency = ((sample_rate as f32 * lookahead_ms / 1_000.0).round() as usize).max(1);
        Self {
            channels,
            latency,
            enabled: true,
            ceiling: 10f32.powf(ceiling_db / 20.0),
            release: 1.0 - (-1.0 / (sample_rate as f32 * release_ms / 1_000.0)).exp(),
            held: vec![0.0; latency * channels],
            head: 0,
            held_frames: 0,
            need: vec![0.0; latency + 2],
            need_at: vec![0; latency + 2],
            need_front: 0,
            need_size: 0,
            in_count: 0,
            out_count: 0,
            gain: 1.0,
        }
    }

    /// Frames of delay.
    pub fn latency(&self) -> usize {
        self.latency
    }

    /// The level the limiter applies now, 1 when it is not holding anything down.
    pub fn gain(&self) -> f32 {
        self.gain
    }

    /// Takes `frames` frames from `input` and writes the frames that come
    /// out of the delay to `output`. Returns how many frames came out: fewer
    /// than went in only while the delay is filling.
    pub fn process(&mut self, input: &[f32], frames: usize, output: &mut [f32]) -> usize {
        let ch = self.channels;
        let mut out_index = 0;
        let mut written = 0;
        for frame in input[..frames * ch].chunks_exact(ch) {
            let peak = frame.iter().fold(0f32, |p, s| p.max(s.abs()));
            let level = if self.enabled && peak > self.ceiling { self.ceiling / peak } else { 1.0 };
            self.push_need(level);
            self.in_count += 1;

            if self.held_frames < self.latency {
                let slot = ((self.head + self.held_frames) % self.latency) * ch;
                self.held[slot..slot + ch].copy_from_slice(frame);
                self.held_frames += 1;
            } else {
                // The oldest frame leaves and the new one takes its place.
                self.emit(output, out_index);
                let slot = self.head * ch;
                self.held[slot..slot + ch].copy_from_slice(frame);
                self.head = (self.head + 1) % self.latency;
                out_index += ch;
                written += 1;
            }
        }
        written
    }

    /// Hands out every frame still in the delay. `output` needs room for
    /// `latency` frames.
    pub fn drain(&mut self, output: &mut [f32]) -> usize {
        let count = self.held_frames;
        let mut out_index = 0;
        for _ in 0..count {
            self.emit(output, out_index);
            self.head = (self.head + 1) % self.latency;
            self.held_frames -= 1;
            out_index += self.channels;
        }
        self.head = 0;
        count
    }

    /// Forgets the audio in the delay, after a jump to another place.
    pub fn clear(&mut self) {
        self.head = 0;
        self.held_frames = 0;
        self.need_front = 0;
        self.need_size = 0;
        self.in_count = 0;
        self.out_count = 0;
        self.gain = 1.0;
    }

    // Writes the oldest held frame, turned down as far as any frame in view
    // needs. Down is instant; back up is slow.
    fn emit(&mut self, output: &mut [f32], at: usize) {
        while self.need_size > 0 && self.need_at[self.need_front] < self.out_count {
            self.need_front = (self.need_front + 1) % self.need.len();
            self.need_size -= 1;
        }
        let target = if self.need_size > 0 { self.need[self.need_front] } else { 1.0 };
        self.gain = if target < self.gain { target } else { self.gain + (target - self.gain) * self.release };
        let from = self.head * self.channels;
        for ch in 0..self.channels {
            let mut y = self.held[from + ch] * self.gain;
            if self.enabled {
                y = y.clamp(-self.ceiling, self.ceiling);
            }
            output[at + ch] = y;
        }
        self.out_count += 1;
    }

    fn push_need(&mut self, level: f32) {
        let len = self.need.len();
        // Anything asking for more level than this one can never be the
        // lowest while this one is in view.
        while self.need_size > 0 && self.need[(self.need_front + self.need_size - 1) % len] >= level {
            self.need_size -= 1;
        }
        let slot = (self.need_front + self.need_size) % len;
        self.need[slot] = level;
        self.need_at[slot] = self.in_count;
        self.need_size += 1;
    }
}
