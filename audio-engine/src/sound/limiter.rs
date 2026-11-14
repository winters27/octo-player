//! The look-ahead limiter, with the Android app's numbers (`Limiter.kt`).

/// Just below full scale, where the limiter holds peaks.
pub const LIMITER_CEILING_DB: f32 = -1.0;

/// How far ahead the limiter looks, and how slowly the level comes back.
pub const LIMITER_LOOKAHEAD_MS: f32 = 1.5;
pub const LIMITER_RELEASE_MS: f32 = 100.0;

/// With the true peak read, the sound between two samples is read at this
/// many points a sample (a quarter, a half and three quarters of the way
/// along, besides the samples themselves), each from this many samples on
/// either side.
pub const TRUE_PEAK_STEPS: usize = 4;
pub const TRUE_PEAK_HALF_TAPS: usize = 16;
const TRUE_PEAK_TAPS: usize = TRUE_PEAK_HALF_TAPS * 2;

/// Keeps peaks from going over the ceiling. The sound runs a moment late, so
/// the limiter sees a peak coming and turns the level down in time instead
/// of clipping it, then lets the level back up slowly so the change is not
/// heard. All channels move together, so the stereo picture stays put.
///
/// Here the limiter sits on one continuous stream after the mix, so songs
/// flow through it without anything held back between them.
///
/// Its switches can change at any frame. Each frame keeps the ones it came
/// in with: a frame that came in with the limiter off is never held at the
/// ceiling, and its sound between samples is never read, however the
/// switches stand when it comes out. Its level still follows the frames
/// after it, so the level is down in time for a need that comes in later.
///
/// Coming back up, the level lands exactly on unity once the remaining step
/// is lost in float rounding, so sound that needs no limiting after a peak
/// passes through bit for bit.
#[derive(Debug)]
pub struct Limiter {
    channels: usize,
    latency: usize,
    /// Off, the level returns to unity and the sound only passes through
    /// the delay, so the delay never changes.
    pub enabled: bool,
    /// Also holds the sound between samples under the ceiling (the true
    /// peak), so a sum whose samples all stay under it does not pass it once
    /// it is turned back into sound. Off, only the samples count, and the
    /// sound comes out exactly as without this. It needs a look-ahead of at
    /// least `2 * TRUE_PEAK_HALF_TAPS` frames (0.7 ms at 48 kHz) and is left
    /// out with a shorter one.
    pub true_peak: bool,
    /// Turns the level down in a straight line from unity across the
    /// look-ahead, reaching each frame's need just as that frame comes out,
    /// instead of dropping it at once when the need comes into view, so the
    /// level never steps. A need read between samples comes into view as
    /// late as the frame that completed the reading, so its line reaches it
    /// as the first sample the reading used comes out, and holds it from
    /// there. Off, the level drops at once, as it always has.
    pub ramped_attack: bool,
    // The last `TRUE_PEAK_TAPS` frames in, each channel's kept twice over so
    // its newest frames are one slice from `recent_at`.
    recent: Vec<f32>,
    recent_at: usize,
    // The filter that reads each point between two samples.
    between: [[f32; TRUE_PEAK_TAPS]; TRUE_PEAK_STEPS - 1],
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
    // By frame number, for every frame in view (from `out_count` up to
    // `in_count`): what its samples need, what the sound between samples
    // read as it came in needs, and whether it came in with the limiter on
    // and with the ramped attack.
    need_of: Vec<f32>,
    between_of: Vec<f32>,
    limited_of: Vec<bool>,
    ramped_of: Vec<bool>,
    // One past the newest frame that came in with the ramped attack: while
    // that frame is in view, the level follows the ramp.
    ramped_until: u64,
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
            true_peak: false,
            ramped_attack: false,
            recent: vec![0.0; channels * TRUE_PEAK_TAPS * 2],
            recent_at: 0,
            between: between_taps(),
            ceiling: 10f32.powf(ceiling_db / 20.0),
            release: 1.0 - (-1.0 / (sample_rate as f32 * release_ms / 1_000.0)).exp(),
            held: vec![0.0; latency * channels],
            head: 0,
            held_frames: 0,
            need: vec![0.0; latency + 2],
            need_at: vec![0; latency + 2],
            need_front: 0,
            need_size: 0,
            need_of: vec![1.0; latency + 1],
            between_of: vec![1.0; latency + 1],
            limited_of: vec![false; latency + 1],
            ramped_of: vec![false; latency + 1],
            ramped_until: 0,
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
            // Kept whether or not the true peak is read, so it can start
            // reading at any frame.
            self.remember(frame);
            let slot = self.slot_of(self.in_count);
            self.limited_of[slot] = self.enabled;
            self.ramped_of[slot] = self.ramped_attack;
            let wanted = if self.enabled && peak > self.ceiling { self.ceiling / peak } else { 1.0 };
            let mut between = 1.0;
            if self.enabled && self.true_peak && self.latency >= TRUE_PEAK_TAPS && self.reads_limited_frames()
            {
                // The reading is about the frames `TRUE_PEAK_HALF_TAPS` back,
                // but its need counts from this frame, so it holds the level
                // down from `latency` frames before this one: over every
                // sample the reading used.
                let read = self.peak_between();
                if read > self.ceiling {
                    between = self.ceiling / read;
                }
            }
            self.need_of[slot] = wanted;
            self.between_of[slot] = between;
            self.push_need(wanted.min(between));
            if self.ramped_attack {
                self.ramped_until = self.in_count + 1;
            }
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
        self.ramped_until = 0;
        self.in_count = 0;
        self.out_count = 0;
        self.gain = 1.0;
        self.recent.fill(0.0);
        self.recent_at = 0;
    }

    // Where the frame numbered `frame` keeps what it came in with.
    fn slot_of(&self, frame: u64) -> usize {
        (frame % self.need_of.len() as u64) as usize
    }

    // Whether the reading taken as the frame numbered `in_count` comes in
    // is about frames the limiter was on for: it reads the points between
    // the frames `TRUE_PEAK_HALF_TAPS` and `TRUE_PEAK_HALF_TAPS - 1` back,
    // and counts once the newer of the two came in with the limiter on.
    fn reads_limited_frames(&self) -> bool {
        let back = (TRUE_PEAK_HALF_TAPS - 1) as u64;
        self.in_count < back || self.limited_of[self.slot_of(self.in_count - back)]
    }

    fn remember(&mut self, frame: &[f32]) {
        for (ch, &s) in frame.iter().enumerate() {
            let at = ch * TRUE_PEAK_TAPS * 2 + self.recent_at;
            self.recent[at] = s;
            self.recent[at + TRUE_PEAK_TAPS] = s;
        }
        self.recent_at = (self.recent_at + 1) % TRUE_PEAK_TAPS;
    }

    // The loudest point read between the two samples in the middle of the
    // frames remembered, on any channel.
    fn peak_between(&self) -> f32 {
        let mut peak = 0f32;
        for ch in 0..self.channels {
            let from = ch * TRUE_PEAK_TAPS * 2 + self.recent_at;
            let frames = &self.recent[from..from + TRUE_PEAK_TAPS];
            for taps in &self.between {
                let y: f32 = frames.iter().zip(taps).map(|(x, h)| x * h).sum();
                peak = peak.max(y.abs());
            }
        }
        peak
    }

    // Writes the oldest held frame, turned down as far as any frame in view
    // needs. Down is instant, or a straight line across the look-ahead for
    // the needs that came in with the ramped attack; back up is slow. Held
    // at the ceiling only when it came in with the limiter on.
    fn emit(&mut self, output: &mut [f32], at: usize) {
        while self.need_size > 0 && self.need_at[self.need_front] < self.out_count {
            self.need_front = (self.need_front + 1) % self.need.len();
            self.need_size -= 1;
        }
        let target = if self.ramped_until > self.out_count {
            self.ramp_target()
        } else if self.need_size > 0 {
            self.need[self.need_front]
        } else {
            1.0
        };
        self.gain = if target < self.gain {
            target
        } else {
            let next = self.gain + (target - self.gain) * self.release;
            // The step lost in rounding would leave the level just short of
            // its target for good (0.99986 for unity at 48 kHz).
            if next == self.gain { target } else { next }
        };
        let limited = self.limited_of[self.slot_of(self.out_count)];
        let from = self.head * self.channels;
        for ch in 0..self.channels {
            let mut y = self.held[from + ch] * self.gain;
            if limited {
                y = y.clamp(-self.ceiling, self.ceiling);
            }
            output[at + ch] = y;
        }
        self.out_count += 1;
    }

    // The lowest of what the frames in view ask for: a frame that came in
    // with the ramped attack asks for the straight line from unity, as it
    // came into view, down to its need as it comes out (for its reading
    // between samples, down by the first sample the reading used); any
    // other frame asks for its need at once.
    fn ramp_target(&self) -> f32 {
        let mut target = 1f32;
        for frame in self.out_count..self.in_count {
            let slot = self.slot_of(frame);
            let (wanted, between) = (self.need_of[slot], self.between_of[slot]);
            if !self.ramped_of[slot] {
                target = target.min(wanted).min(between);
                continue;
            }
            if wanted < 1.0 {
                let ahead = (frame - self.out_count) as f32;
                target = target.min(wanted + (1.0 - wanted) * ahead / self.latency as f32);
            }
            if between < 1.0 {
                // A reading needs a look-ahead of at least `TRUE_PEAK_TAPS`.
                let span = (self.latency - (TRUE_PEAK_TAPS - 1)) as f32;
                let ahead = frame.saturating_sub(self.out_count + (TRUE_PEAK_TAPS - 1) as u64) as f32;
                target = target.min(between + (1.0 - between) * ahead / span);
            }
        }
        target
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

// For each point read between two samples, a windowed sinc (Blackman) over
// the `TRUE_PEAK_TAPS` samples around it, oldest first, with the point
// between the middle two; scaled so a steady level reads as itself.
fn between_taps() -> [[f32; TRUE_PEAK_TAPS]; TRUE_PEAK_STEPS - 1] {
    use std::f64::consts::PI;
    let half = TRUE_PEAK_HALF_TAPS as f64;
    let mut all = [[0.0; TRUE_PEAK_TAPS]; TRUE_PEAK_STEPS - 1];
    for (step, taps) in all.iter_mut().enumerate() {
        let along = (step + 1) as f64 / TRUE_PEAK_STEPS as f64;
        let mut raw = [0.0f64; TRUE_PEAK_TAPS];
        for (j, h) in raw.iter_mut().enumerate() {
            // How far the point read lies from sample `j`.
            let d = along + half - 1.0 - j as f64;
            let sinc = (PI * d).sin() / (PI * d);
            let window = 0.42 + 0.5 * (PI * d / half).cos() + 0.08 * (2.0 * PI * d / half).cos();
            *h = sinc * window;
        }
        let sum: f64 = raw.iter().sum();
        for (t, h) in taps.iter_mut().zip(raw) {
            *t = (h / sum) as f32;
        }
    }
    all
}
