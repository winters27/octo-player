//! Playback speed and pitch, as the Android app offers them (`Pace.kt`).
//!
//! Speed without a pitch change uses pitch-synchronous overlap-add: the
//! sound's repeating period is found, and whole periods are skipped (faster)
//! or repeated (slower) with a short crossfade, so voices keep their pitch.
//! This is the same family of method as the one the Android player uses.
//! A pitch change on top is a plain rate change done after it.

use crate::fifo::Fifo;

/// How fast music can play, from half speed to double.
pub const SLOWEST_SPEED: f32 = 0.5;
pub const FASTEST_SPEED: f32 = 2.0;

/// How far the pitch can be moved either way, in semitones.
pub const PITCH_RANGE_SEMITONES: i32 = 6;

// The range of periods searched, as frequencies: the pitch of voices and
// most instruments' fundamentals.
const LOWEST_PITCH_HZ: u32 = 65;
const HIGHEST_PITCH_HZ: u32 = 400;

// The search runs on a copy made smaller to about this rate, then is
// refined at the full rate.
const SEARCH_RATE: u32 = 4_000;

// Closer to 1 than this counts as no change.
const SAME: f32 = 1e-4;

/// The speed, and the pitch as a multiple of the original.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pace {
    pub speed: f32,
    pub pitch: f32,
}

impl Default for Pace {
    fn default() -> Self {
        Self { speed: 1.0, pitch: 1.0 }
    }
}

impl Pace {
    /// A pace inside the allowed ranges.
    pub fn clamped(speed: f32, pitch: f32) -> Self {
        let lowest = 2f32.powf(-PITCH_RANGE_SEMITONES as f32 / 12.0);
        let highest = 2f32.powf(PITCH_RANGE_SEMITONES as f32 / 12.0);
        let speed = if speed.is_finite() { speed.clamp(SLOWEST_SPEED, FASTEST_SPEED) } else { 1.0 };
        let pitch = if pitch.is_finite() { pitch.clamp(lowest, highest) } else { 1.0 };
        Self { speed, pitch }
    }

    pub fn is_normal(&self) -> bool {
        (self.speed - 1.0).abs() < SAME && (self.pitch - 1.0).abs() < SAME
    }
}

/// Changes the speed of interleaved audio while keeping the pitch.
pub struct Stretch {
    channels: usize,
    rate: f64,
    min_period: usize,
    max_period: usize,
    skip: usize,
    input: Fifo,
    // Frames still to copy straight through after an overlap.
    copy_left: usize,
    // The part of a frame the lengths have rounded away, carried on.
    carry: f64,
    mono: Vec<f32>,
    small: Vec<f32>,
}

impl Stretch {
    pub fn new(sample_rate: u32, channels: usize, rate: f64) -> Self {
        let skip = (sample_rate / SEARCH_RATE).max(1) as usize;
        let min_period = (sample_rate / HIGHEST_PITCH_HZ) as usize;
        let max_period = (sample_rate / LOWEST_PITCH_HZ) as usize;
        Self {
            channels,
            rate,
            min_period,
            max_period,
            skip,
            input: Fifo::with_capacity(max_period * 4 * channels),
            copy_left: 0,
            carry: 0.0,
            mono: Vec::new(),
            small: Vec::new(),
        }
    }

    /// Input frames waiting inside.
    pub fn pending(&self) -> usize {
        self.input.len() / self.channels
    }

    /// Takes interleaved frames and appends what comes out to `out`.
    pub fn process(&mut self, samples: &[f32], out: &mut Fifo) {
        self.input.push(samples);
        let ch = self.channels;
        let need = self.max_period * 2;
        loop {
            let held = self.input.len() / ch;
            if self.copy_left > 0 {
                let n = self.copy_left.min(held);
                if n == 0 {
                    break;
                }
                out.push(&self.input.as_slice()[..n * ch]);
                self.input.consume(n * ch);
                self.copy_left -= n;
                continue;
            }
            if held < need {
                break;
            }
            let period = self.find_period();
            if self.rate > 1.0 {
                self.skip_period(period, out);
            } else {
                self.repeat_period(period, out);
            }
        }
    }

    /// Hands out everything still inside unchanged, when the speed goes back
    /// to normal or the stream ends.
    pub fn flush(&mut self, out: &mut Fifo) {
        out.push(self.input.as_slice());
        self.input.clear();
        self.copy_left = 0;
    }

    pub fn clear(&mut self) {
        self.input.clear();
        self.copy_left = 0;
        self.carry = 0.0;
    }

    // Faster: fades from one period into the next, dropping one period,
    // then copies enough to land on the speed on average.
    fn skip_period(&mut self, period: usize, out: &mut Fifo) {
        let exact = period as f64 / (self.rate - 1.0) + self.carry;
        let length = exact.floor().max(1.0) as usize;
        self.carry = exact - length as f64;
        let overlap = length.min(period);
        let ch = self.channels;
        let x = self.input.as_slice();
        let dst = out.extend_zeroed(overlap * ch);
        for i in 0..overlap {
            let w = i as f32 / overlap as f32;
            for c in 0..ch {
                let a = x[i * ch + c];
                let b = x[(period + i) * ch + c];
                dst[i * ch + c] = a + (b - a) * w;
            }
        }
        self.input.consume((period + overlap) * ch);
        self.copy_left = length - overlap;
    }

    // Slower: plays a period, then fades from the period after it back into
    // that same period, playing it twice, then copies on.
    fn repeat_period(&mut self, period: usize, out: &mut Fifo) {
        let exact = period as f64 * self.rate / (1.0 - self.rate) + self.carry;
        let length = exact.floor().max(1.0) as usize;
        self.carry = exact - length as f64;
        let overlap = length.min(period);
        let ch = self.channels;
        let x = self.input.as_slice();
        out.push(&x[..period * ch]);
        let x = self.input.as_slice();
        let dst = out.extend_zeroed(overlap * ch);
        for i in 0..overlap {
            let w = i as f32 / overlap as f32;
            for c in 0..ch {
                let a = x[(period + i) * ch + c];
                let b = x[i * ch + c];
                dst[i * ch + c] = a + (b - a) * w;
            }
        }
        self.input.consume(overlap * ch);
        self.copy_left = length - overlap;
    }

    // The repeating period at the front of the input: the lag where the
    // sound differs least from itself, found roughly on a smaller copy and
    // then refined at the full rate.
    fn find_period(&mut self) -> usize {
        let ch = self.channels;
        let frames = self.max_period * 2;
        let x = &self.input.as_slice()[..frames * ch];
        self.mono.clear();
        self.mono.extend(x.chunks_exact(ch).map(|f| f.iter().sum::<f32>()));

        let skip = self.skip;
        self.small.clear();
        self.small.extend(self.mono.chunks_exact(skip).map(|c| c.iter().sum::<f32>()));
        let lo = (self.min_period / skip).max(1);
        let hi = (self.max_period / skip).max(lo);
        let rough = best_lag(&self.small, lo, hi, hi);
        if skip == 1 {
            return rough;
        }
        let centre = rough * skip;
        let lo = centre.saturating_sub(skip).max(self.min_period);
        let hi = (centre + skip).min(self.max_period);
        best_lag(&self.mono, lo, hi, self.max_period)
    }
}

// The lag in `lo..=hi` where the signal differs least from itself, per
// frame of lag, over a window of `window` samples.
fn best_lag(signal: &[f32], lo: usize, hi: usize, window: usize) -> usize {
    let mut best = lo;
    let mut best_score = f32::INFINITY;
    for lag in lo..=hi {
        if lag + window > signal.len() {
            break;
        }
        let diff: f32 =
            signal[..window].iter().zip(&signal[lag..lag + window]).map(|(a, b)| (a - b).abs()).sum();
        // Longer lags cover more, so they are judged per frame of lag,
        // which also keeps the search off a multiple of the true period.
        let score = diff / lag as f32;
        if score < best_score {
            best_score = score;
            best = lag;
        }
    }
    best
}

/// A plain rate change, which moves pitch and speed together, by cubic
/// interpolation between frames.
pub struct Varispeed {
    channels: usize,
    ratio: f64,
    input: Fifo,
    pos: f64,
    made: Vec<f32>,
}

impl Varispeed {
    pub fn new(channels: usize, ratio: f64) -> Self {
        // Starts with one silent frame before the first, so the curve has
        // a point on each side from the very start.
        let mut input = Fifo::with_capacity(4096);
        input.push(&vec![0.0; channels]);
        Self { channels, ratio, input, pos: 1.0, made: Vec::new() }
    }

    /// Input frames waiting inside.
    pub fn pending(&self) -> f64 {
        (self.input.len() / self.channels) as f64 - self.pos
    }

    pub fn process(&mut self, samples: &[f32], out: &mut Fifo) {
        self.input.push(samples);
        let ch = self.channels;
        let held = self.input.len() / ch;
        let x = self.input.as_slice();
        self.made.clear();
        while (self.pos as usize) + 2 < held {
            let i = self.pos as usize;
            let t = (self.pos - i as f64) as f32;
            for c in 0..ch {
                let p0 = x[(i - 1) * ch + c];
                let p1 = x[i * ch + c];
                let p2 = x[(i + 1) * ch + c];
                let p3 = x[(i + 2) * ch + c];
                self.made.push(hermite(p0, p1, p2, p3, t));
            }
            self.pos += self.ratio;
        }
        out.push(&self.made);
        // Keep one frame of history before the current position.
        let drop = (self.pos as usize).saturating_sub(1);
        self.input.consume(drop * ch);
        self.pos -= drop as f64;
    }

    pub fn clear(&mut self) {
        self.input.clear();
        self.input.push(&vec![0.0; self.channels]);
        self.pos = 1.0;
    }
}

fn hermite(p0: f32, p1: f32, p2: f32, p3: f32, t: f32) -> f32 {
    let c1 = 0.5 * (p2 - p0);
    let c2 = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
    let c3 = 0.5 * (p3 - p0) + 1.5 * (p1 - p2);
    ((c3 * t + c2) * t + c1) * t + p1
}

/// The speed and pitch stage of the playback chain. Off at normal pace,
/// where it hands frames through untouched.
pub struct PaceStage {
    sample_rate: u32,
    channels: usize,
    pace: Pace,
    stretch: Option<Stretch>,
    varispeed: Option<Varispeed>,
    middle: Fifo,
    out: Fifo,
}

impl PaceStage {
    pub fn new(sample_rate: u32, channels: usize) -> Self {
        Self {
            sample_rate,
            channels,
            pace: Pace::default(),
            stretch: None,
            varispeed: None,
            middle: Fifo::default(),
            out: Fifo::default(),
        }
    }

    pub fn pace(&self) -> Pace {
        self.pace
    }

    /// Whether frames go through the stage, or past it.
    pub fn is_active(&self) -> bool {
        !self.pace.is_normal() || !self.out.is_empty()
    }

    /// Changes the pace. What is already inside comes out unchanged first.
    pub fn set(&mut self, pace: Pace) {
        if pace == self.pace {
            return;
        }
        self.flush_inside();
        self.pace = pace;
        let stretch_rate = (pace.speed / pace.pitch) as f64;
        self.stretch = ((stretch_rate - 1.0).abs() > SAME as f64)
            .then(|| Stretch::new(self.sample_rate, self.channels, stretch_rate));
        self.varispeed =
            ((pace.pitch - 1.0).abs() > SAME).then(|| Varispeed::new(self.channels, pace.pitch as f64));
    }

    /// Takes frames in.
    pub fn push(&mut self, samples: &[f32]) {
        match (&mut self.stretch, &mut self.varispeed) {
            (Some(s), Some(v)) => {
                self.middle.clear();
                s.process(samples, &mut self.middle);
                v.process(self.middle.as_slice(), &mut self.out);
            }
            (Some(s), None) => s.process(samples, &mut self.out),
            (None, Some(v)) => v.process(samples, &mut self.out),
            (None, None) => self.out.push(samples),
        }
    }

    /// Frames ready to take out.
    pub fn available(&self) -> usize {
        self.out.len() / self.channels
    }

    /// Takes out up to `out.len()` samples.
    pub fn pull(&mut self, out: &mut [f32]) -> usize {
        self.out.pop_into(out) / self.channels
    }

    /// Input frames inside that have not come out yet, so the clock can
    /// tell which input frame is at the front of the output.
    pub fn pending_input(&self) -> f64 {
        let stretch_rate = (self.pace.speed / self.pace.pitch) as f64;
        let pitch = self.pace.pitch as f64;
        let out_frames = self.available() as f64;
        let (v_pending, v_out_ratio) = match &self.varispeed {
            Some(v) => (v.pending(), pitch),
            None => (0.0, 1.0),
        };
        let s_pending = self.stretch.as_ref().map(|s| s.pending() as f64).unwrap_or(0.0);
        let s_ratio = if self.stretch.is_some() { stretch_rate } else { 1.0 };
        s_pending + (v_pending + out_frames * v_out_ratio) * s_ratio
    }

    /// Hands out what is inside as it is, at the end of the sound.
    pub fn drain(&mut self) {
        self.flush_inside();
    }

    /// Forgets everything inside, after a jump to another place.
    pub fn clear(&mut self) {
        if let Some(s) = &mut self.stretch {
            s.clear();
        }
        if let Some(v) = &mut self.varispeed {
            v.clear();
        }
        self.middle.clear();
        self.out.clear();
    }

    // Pushes what the pieces hold out as it is.
    fn flush_inside(&mut self) {
        if let Some(s) = &mut self.stretch {
            s.flush(&mut self.out);
        }
        if let Some(v) = &mut self.varispeed {
            // Its last frames are few; hand them through without the rate.
            let ch = self.channels;
            let held = v.input.as_slice();
            let from = (v.pos as usize).min(held.len() / ch);
            self.out.push(&held[from * ch..]);
            v.clear();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::PI;

    fn sine(freq: f32, rate: u32, frames: usize) -> Vec<f32> {
        (0..frames)
            .flat_map(|n| {
                let s = (2.0 * PI * freq * n as f32 / rate as f32).sin() * 0.5;
                [s, s]
            })
            .collect()
    }

    // The frequency of a stereo signal from its upward zero crossings.
    fn frequency(samples: &[f32], rate: u32) -> f32 {
        let left: Vec<f32> = samples.chunks_exact(2).map(|f| f[0]).collect();
        let crossings: Vec<usize> =
            (1..left.len()).filter(|&i| left[i - 1] < 0.0 && left[i] >= 0.0).collect();
        let span = (crossings[crossings.len() - 1] - crossings[0]) as f32;
        (crossings.len() - 1) as f32 * rate as f32 / span
    }

    fn run(pace: Pace, input: &[f32]) -> Vec<f32> {
        let mut stage = PaceStage::new(48_000, 2);
        stage.set(pace);
        for chunk in input.chunks(1024) {
            stage.push(chunk);
        }
        let mut out = vec![0.0; stage.available() * 2];
        stage.pull(&mut out);
        out
    }

    #[test]
    fn faster_and_slower_keep_the_pitch() {
        let rate = 48_000;
        let input = sine(440.0, rate, rate as usize * 2);
        for speed in [0.5f32, 0.75, 1.25, 1.5, 2.0] {
            let out = run(Pace { speed, pitch: 1.0 }, &input);
            let frames = out.len() / 2;
            let expected = input.len() as f32 / 2.0 / speed;
            // Everything but the last few periods held inside comes out.
            assert!(
                (frames as f32 - expected).abs() < 3_000.0,
                "speed {speed}: {frames} frames, want {expected}"
            );
            let f = frequency(&out[..frames.min(40_000) * 2], rate);
            assert!((f - 440.0).abs() < 4.0, "speed {speed}: {f} Hz");
        }
    }

    #[test]
    fn pitch_moves_by_semitones_at_the_same_speed() {
        let rate = 48_000;
        let input = sine(440.0, rate, rate as usize * 2);
        let up = 2f32.powf(2.0 / 12.0);
        let out = run(Pace { speed: 1.0, pitch: up }, &input);
        let frames = out.len() / 2;
        assert!((frames as f32 - rate as f32 * 2.0).abs() < 4_000.0, "{frames}");
        let f = frequency(&out[..40_000 * 2], rate);
        assert!((f - 440.0 * up).abs() < 5.0, "{f}");
    }

    #[test]
    fn tape_style_moves_both() {
        let rate = 48_000;
        let input = sine(440.0, rate, rate as usize);
        let out = run(Pace { speed: 1.5, pitch: 1.5 }, &input);
        let f = frequency(&out, rate);
        assert!((f - 660.0).abs() < 5.0, "{f}");
        assert!(((out.len() / 2) as f32 - rate as f32 / 1.5).abs() < 100.0);
    }

    #[test]
    fn normal_pace_passes_through() {
        let mut stage = PaceStage::new(44_100, 2);
        assert!(!stage.is_active());
        stage.set(Pace::clamped(3.0, 1.0));
        assert_eq!(stage.pace().speed, FASTEST_SPEED);
        stage.set(Pace::default());
        assert!(!stage.is_active());
    }

    #[test]
    fn stretch_output_has_no_jumps() {
        // Overlap-add of a steady tone must stay smooth: no sample-to-sample
        // step larger than the tone itself can make.
        let rate = 48_000;
        let input = sine(220.0, rate, rate as usize);
        let out = run(Pace { speed: 1.3, pitch: 1.0 }, &input);
        let biggest = 0.5 * 2.0 * PI * 220.0 / rate as f32;
        for w in out.chunks_exact(2).collect::<Vec<_>>().windows(2) {
            assert!((w[1][0] - w[0][0]).abs() < biggest * 1.5, "jump {} -> {}", w[0][0], w[1][0]);
        }
    }
}
