//! What the planner needs to know about a stretch of a song: its body
//! level, where its sound starts and ends, where its outro begins, where
//! its level steps, and its beat grid.

use super::envelope::{EnvelopeBuilder, SILENT_DB, SectionEnvelope, db_of};

/// The quietest a silence gate can be, in dBFS.
pub const SILENCE_FLOOR_DB: f64 = -60.0;

/// How far below a song's body level its silence gate sits.
pub const SILENCE_BELOW_BODY_DB: f64 = 45.0;

/// How far below the body level the song has to stay for its outro to begin.
pub const OUTRO_BELOW_BODY_DB: f64 = 6.0;

/// How much the level must change between the two seconds before and after
/// a moment for it to count as a section boundary.
pub const BOUNDARY_STEP_DB: f64 = 4.0;

/// The body level is this percentile of the 400 ms level. A song's start,
/// or the whole song heard as it plays, uses `HEAD_BODY_PERCENTILE`; the end
/// of a song alone uses `TAIL_BODY_PERCENTILE`, since a tail that is mostly
/// outro would otherwise read too quiet.
pub const HEAD_BODY_PERCENTILE: f64 = 0.75;
pub const TAIL_BODY_PERCENTILE: f64 = 0.9;

/// The tempo range the beat finder looks in.
pub const SLOWEST_BPM: f64 = 70.0;
pub const FASTEST_BPM: f64 = 180.0;

/// A tempo is trusted when its autocorrelation peak stands this far above
/// the mean of the range and this share of its beats land on an onset peak.
pub const TEMPO_CONFIDENT: f64 = 1.5;
pub const BEAT_CONSISTENT: f64 = 0.55;

/// How near an onset peak a beat has to fall to count as landing on it.
pub const BEAT_HIT_MS: f64 = 35.0;

/// Beats in a bar.
pub const BEATS_PER_BAR: i32 = 4;

// Below this autocorrelation peak the onsets are too faint to carry a beat,
// so no tempo is reported.
const FAINTEST_BEAT: f64 = 1e-4;

// An onset peak must reach at least this, and one standard deviation above
// the mean onset of the sounding part.
const FAINTEST_PEAK: f64 = 0.05;

const BODY_WINDOW_MS: i32 = 400;
const SOUND_WINDOW_MS: i32 = 50;
const OUTRO_WINDOW_MS: i32 = 1000;
const BOUNDARY_SIDE_MS: i32 = 2000;

const SQRT_2: f64 = std::f64::consts::SQRT_2;

/// Rounds halves up, the same in every language.
pub fn round_half_up(x: f64) -> f64 {
    (x + 0.5).floor()
}

/// A song's beat grid. Beat times are song times in milliseconds: beats fall
/// on `first_beat_ms + n * beat_ms` and bars start on
/// `downbeat_ms + n * bar_ms`. `consistency` is the share of the grid's
/// beats that land on an onset peak.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Tempo {
    pub bpm: f64,
    pub confidence: f64,
    pub consistency: f64,
    pub beat_ms: f64,
    pub first_beat_ms: f64,
    pub downbeat_ms: f64,
}

impl Tempo {
    pub fn confident(&self) -> bool {
        self.confidence >= TEMPO_CONFIDENT && self.consistency >= BEAT_CONSISTENT
    }

    pub fn bar_ms(&self) -> f64 {
        self.beat_ms * BEATS_PER_BAR as f64
    }

    /// The beat nearest a time, extending the grid either way.
    pub fn nearest_beat(&self, ms: f64) -> f64 {
        self.first_beat_ms + round_half_up((ms - self.first_beat_ms) / self.beat_ms) * self.beat_ms
    }

    /// Every bar line from `from_ms` to `to_ms`, both included.
    pub fn bars_between(&self, from_ms: f64, to_ms: f64) -> Vec<f64> {
        grid_between(self.downbeat_ms, self.bar_ms(), from_ms, to_ms)
    }

    /// Every beat from `from_ms` to `to_ms`, both included.
    pub fn beats_between(&self, from_ms: f64, to_ms: f64) -> Vec<f64> {
        grid_between(self.first_beat_ms, self.beat_ms, from_ms, to_ms)
    }

    /// The first bar line at or after a time.
    pub fn downbeat_from(&self, ms: f64) -> f64 {
        self.downbeat_ms + ((ms - self.downbeat_ms) / self.bar_ms() - 1e-9).ceil() * self.bar_ms()
    }

    /// The same grid at double or half the tempo, as often as it takes to
    /// come within a factor of sqrt 2 of `bpm`.
    pub fn in_octave_of(&self, bpm: f64) -> Tempo {
        if bpm <= 0.0 {
            return *self;
        }
        let mut factor = 1.0;
        while self.bpm * factor > bpm * SQRT_2 {
            factor /= 2.0;
        }
        while self.bpm * factor < bpm / SQRT_2 {
            factor *= 2.0;
        }
        if factor == 1.0 {
            return *self;
        }
        Tempo { bpm: self.bpm * factor, beat_ms: self.beat_ms / factor, ..*self }
    }
}

fn grid_between(anchor: f64, step: f64, from_ms: f64, to_ms: f64) -> Vec<f64> {
    let mut points = Vec::new();
    let mut n = ((from_ms - anchor) / step - 1e-9).ceil();
    loop {
        let at = anchor + n * step;
        if at > to_ms + 1e-6 {
            break;
        }
        points.push(at);
        n += 1.0;
    }
    points
}

/// What the planner needs to know about one stretch of a song. Times are
/// song times in milliseconds. The sound times are `None` when the stretch
/// is silent throughout; the tempo is `None` when no beat was found.
#[derive(Clone, Debug, PartialEq)]
pub struct SectionFeatures {
    /// The level of the song's body, in dBFS.
    pub body_db: f64,
    /// Below this the song counts as silent.
    pub gate_db: f64,
    pub sound_start_ms: Option<i64>,
    pub sound_end_ms: Option<i64>,
    /// From here on the song never again comes within 6 dB of its body level.
    pub outro_start_ms: Option<i64>,
    /// Where the level steps up or down by 4 dB or more, two seconds either side.
    pub boundaries_ms: Vec<i64>,
    pub tempo: Option<Tempo>,
}

/// An envelope with the features found in it.
#[derive(Clone, Debug, PartialEq)]
pub struct SectionAnalysis {
    pub envelope: SectionEnvelope,
    pub features: SectionFeatures,
    prefix: Vec<f64>,
    body: Vec<f64>,
    sound: Vec<f64>,
    outro: Vec<f64>,
}

impl SectionAnalysis {
    /// Finds the features of an envelope. `tag_bpm`, when the song's tags
    /// carry a tempo, picks between double and half time. `body_level_db`,
    /// when known from elsewhere, replaces the body level measured here at
    /// `body_percentile`.
    pub fn of(
        envelope: SectionEnvelope,
        tag_bpm: Option<f64>,
        body_level_db: Option<f64>,
        body_percentile: f64,
    ) -> SectionAnalysis {
        let hop = envelope.hop_ms;
        let prefix = prefix_of(&power_of(&envelope.db));
        let body = smoothed(&prefix, frames_of(BODY_WINDOW_MS, hop));
        let sound = smoothed(&prefix, frames_of(SOUND_WINDOW_MS, hop));
        let outro = smoothed(&prefix, frames_of(OUTRO_WINDOW_MS, hop));
        let body_db = body_level_db.unwrap_or_else(|| body_level_from(&body, body_percentile));
        let levels = levels_for(&envelope, body_db, &sound, &outro);
        let tempo = match levels.sound_start {
            None => None,
            Some(_) => tempo_of(&envelope, levels.first, levels.last, tag_bpm),
        };
        let features = SectionFeatures {
            body_db,
            gate_db: levels.gate,
            sound_start_ms: levels.sound_start,
            sound_end_ms: levels.sound_end,
            outro_start_ms: levels.outro_start,
            boundaries_ms: boundaries(&envelope, &body),
            tempo,
        };
        SectionAnalysis { envelope, features, prefix, body, sound, outro }
    }

    /// The mean level from `from_ms` to `to_ms` in dBFS, power averaged, over
    /// the part of that span the envelope covers; [`SILENT_DB`] when it
    /// covers none.
    pub fn level_db(&self, from_ms: f64, to_ms: f64) -> f64 {
        let from = 0.max(self.envelope.index_at(from_ms));
        let to = (self.envelope.size() as i64).min(self.envelope.index_at(to_ms));
        if to <= from {
            return SILENT_DB as f64;
        }
        let (from, to) = (from as usize, to as usize);
        db_of((self.prefix[to] - self.prefix[from]) / (to - from) as f64) as f64
    }

    /// The share of the hops from `from_ms` to `to_ms` whose 400 ms level is
    /// at or above `threshold_db`; 0 when the envelope covers none of them.
    pub fn share_at_least(&self, from_ms: f64, to_ms: f64, threshold_db: f64) -> f64 {
        let from = 0.max(self.envelope.index_at(from_ms));
        let to = (self.envelope.size() as i64).min(self.envelope.index_at(to_ms));
        if to <= from {
            return 0.0;
        }
        let (from, to) = (from as usize, to as usize);
        let at = self.body[from..to].iter().filter(|&&b| b >= threshold_db).count();
        at as f64 / (to - from) as f64
    }

    /// The same section measured against a body level known from elsewhere,
    /// such as the whole song heard as it played: the gate, sound times and
    /// outro move with it; the boundaries and tempo stay.
    pub fn with_body_level(&self, body_db: f64) -> SectionAnalysis {
        let levels = levels_for(&self.envelope, body_db, &self.sound, &self.outro);
        let mut copy = self.clone();
        copy.features.body_db = body_db;
        copy.features.gate_db = levels.gate;
        copy.features.sound_start_ms = levels.sound_start;
        copy.features.sound_end_ms = levels.sound_end;
        copy.features.outro_start_ms = levels.outro_start;
        copy
    }
}

/// The features of the start of a song.
pub fn analyze_head(envelope: SectionEnvelope, tag_bpm: Option<f64>) -> SectionAnalysis {
    SectionAnalysis::of(envelope, tag_bpm, None, HEAD_BODY_PERCENTILE)
}

/// The features of the end of a song. Without a body level from the whole
/// song, the tail's own upper level stands in for it.
pub fn analyze_tail(
    envelope: SectionEnvelope,
    tag_bpm: Option<f64>,
    body_level_db: Option<f64>,
) -> SectionAnalysis {
    SectionAnalysis::of(envelope, tag_bpm, body_level_db, TAIL_BODY_PERCENTILE)
}

/// The body level of a whole envelope: the given percentile of its 400 ms
/// level over the hops above the silence gate, or `None` when it is silent.
pub fn body_level_of(envelope: &SectionEnvelope, percentile: f64) -> Option<f64> {
    let body = smoothed(&prefix_of(&power_of(&envelope.db)), frames_of(BODY_WINDOW_MS, envelope.hop_ms));
    Some(body_level_from(&body, percentile)).filter(|&b| b > SILENT_DB as f64)
}

// The gate depends on the body level and the body level on the gate, so the
// body is measured above the floor first, then above its own gate.
fn body_level_from(body: &[f64], percentile: f64) -> f64 {
    let mut gate = SILENCE_FLOOR_DB;
    let mut body_db = SILENT_DB as f64;
    for _ in 0..2 {
        let Some(level) = percentile_above(body, gate, percentile) else { continue };
        body_db = level;
        gate = SILENCE_FLOOR_DB.max(level - SILENCE_BELOW_BODY_DB);
    }
    body_db
}

struct Levels {
    gate: f64,
    first: i64,
    last: i64,
    sound_start: Option<i64>,
    sound_end: Option<i64>,
    outro_start: Option<i64>,
}

fn levels_for(envelope: &SectionEnvelope, body_db: f64, sound: &[f64], outro: &[f64]) -> Levels {
    let gate = SILENCE_FLOOR_DB.max(body_db - SILENCE_BELOW_BODY_DB);
    let mut first = -1i64;
    let mut last = -1i64;
    for (i, &s) in sound.iter().enumerate() {
        if s > gate {
            if first < 0 {
                first = i as i64;
            }
            last = i as i64;
        }
    }
    let sound_start = (first >= 0).then(|| envelope.time_of(first as usize));
    let sound_end = (last >= 0).then(|| envelope.time_of(last as usize + 1));
    let outro_start = sound_end.map(|end| {
        let mut last_loud = -1i64;
        for (i, &o) in outro.iter().enumerate() {
            if o >= body_db - OUTRO_BELOW_BODY_DB {
                last_loud = i as i64;
            }
        }
        envelope.time_of((last_loud + 1) as usize).min(end)
    });
    Levels { gate, first, last, sound_start, sound_end, outro_start }
}

fn frames_of(ms: i32, hop_ms: i32) -> usize {
    1.max(ms / hop_ms) as usize
}

fn power_of(db: &[f32]) -> Vec<f64> {
    db.iter().map(|&d| 10f64.powf(d as f64 / 10.0)).collect()
}

fn prefix_of(values: &[f64]) -> Vec<f64> {
    let mut prefix = vec![0.0; values.len() + 1];
    for (i, v) in values.iter().enumerate() {
        prefix[i + 1] = prefix[i] + v;
    }
    prefix
}

// The level of each hop averaged, as power, with the hops up to half the
// window either side (fewer at the edges).
fn smoothed(prefix: &[f64], window: usize) -> Vec<f64> {
    let size = prefix.len() - 1;
    let half = window / 2;
    (0..size)
        .map(|i| {
            let from = i.saturating_sub(half);
            let to = size.min(i + half + 1);
            db_of((prefix[to] - prefix[from]) / (to - from) as f64) as f64
        })
        .collect()
}

// A percentile of the levels above a gate, the lower of the two neighbors
// when it falls between two, or None when none is above it.
fn percentile_above(levels: &[f64], gate: f64, percentile: f64) -> Option<f64> {
    let mut above: Vec<f64> = levels.iter().copied().filter(|&l| l > gate).collect();
    if above.is_empty() {
        return None;
    }
    above.sort_by(|a, b| a.total_cmp(b));
    Some(above[(percentile * (above.len() - 1) as f64).floor() as usize])
}

// Hops where the mean of the 400 ms level (in dB) over the two seconds
// after differs from the two seconds before by BOUNDARY_STEP_DB or more,
// keeping only the strongest change within two seconds either way. Earlier
// wins a tie.
fn boundaries(envelope: &SectionEnvelope, body: &[f64]) -> Vec<i64> {
    let side = frames_of(BOUNDARY_SIDE_MS, envelope.hop_ms);
    let size = envelope.size();
    if size < 2 * side {
        return Vec::new();
    }
    let prefix = prefix_of(body);
    let mut change = vec![0.0; size];
    for (i, c) in change.iter_mut().enumerate().take(size - side + 1).skip(side) {
        let before = (prefix[i] - prefix[i - side]) / side as f64;
        let after = (prefix[i + side] - prefix[i]) / side as f64;
        *c = (after - before).abs();
    }
    let mut found: Vec<usize> = Vec::new();
    for i in side..size {
        let step = change[i];
        if step < BOUNDARY_STEP_DB {
            continue;
        }
        let mut peak = true;
        for (j, &c) in
            change.iter().enumerate().take((size - 1).min(i + side) + 1).skip(i.saturating_sub(side))
        {
            if (j < i && c >= step) || (j > i && c > step) {
                peak = false;
                break;
            }
        }
        if peak && !found.iter().any(|&f| f.abs_diff(i) < side) {
            found.push(i);
        }
    }
    found.into_iter().map(|i| envelope.time_of(i)).collect()
}

// The beat grid from the onsets: the strongest autocorrelation lag between
// FASTEST_BPM and SLOWEST_BPM, refined with a parabola through its
// neighbors and moved to the tag's octave when there is a tag; the beat
// phase with the largest onset sum along the grid; and of the four beats in
// a bar, the one whose beats carry the most bass onset. The consistency is
// measured over the sounding hops first to last.
fn tempo_of(envelope: &SectionEnvelope, first: i64, last: i64, tag_bpm: Option<f64>) -> Option<Tempo> {
    let onset = &envelope.onset;
    let size = onset.len();
    let hop = envelope.hop_ms as f64;
    let per_minute = 60_000.0 / hop;
    let shortest = (per_minute / FASTEST_BPM).floor() as usize;
    let longest = (per_minute / SLOWEST_BPM).ceil() as usize;
    if size < 2 * (longest + 1) {
        return None;
    }

    let mut r = vec![0.0f64; longest + 2];
    for lag in shortest - 1..=longest + 1 {
        let mut sum = 0.0;
        for i in 0..size - lag {
            sum += onset[i] as f64 * onset[i + lag] as f64;
        }
        r[lag] = sum / (size - lag) as f64;
    }
    let mut peak = shortest;
    let mut mean = 0.0;
    for lag in shortest..=longest {
        mean += r[lag];
        if r[lag] > r[peak] {
            peak = lag;
        }
    }
    mean /= (longest - shortest + 1) as f64;
    if mean <= 0.0 || r[peak] < FAINTEST_BEAT {
        return None;
    }
    let confidence = r[peak] / mean;

    let left = r[peak - 1];
    let right = r[peak + 1];
    let curve = left - 2.0 * r[peak] + right;
    let shift = if curve < 0.0 { (0.5 * (left - right) / curve).clamp(-0.5, 0.5) } else { 0.0 };
    let mut period = peak as f64 + shift;
    if let Some(tag) = tag_bpm.filter(|&t| t > 0.0) {
        while per_minute / period > tag * SQRT_2 {
            period *= 2.0;
        }
        while per_minute / period < tag / SQRT_2 {
            period /= 2.0;
        }
    }

    let mut best_phase = 0;
    let mut best = -1.0;
    for p in 0..period.ceil() as i64 {
        let sum = comb_mean(onset, p as f64, period, 0, 1);
        if sum > best {
            best = sum;
            best_phase = p;
        }
    }
    let mut phase = best_phase as f64;

    // Fit the grid to the onset peaks the beats land on, which corrects a
    // period read off whole hops: first to the peaks within a quarter beat,
    // then twice to those within BEAT_HIT_MS.
    let peaks = onset_peaks(envelope, first, last);
    for pass in 0..3 {
        let reach = if pass == 0 { period / 4.0 } else { BEAT_HIT_MS / hop };
        let Some((fit_phase, fit_period)) = fit_grid(&peaks, phase, period, size, reach) else { continue };
        period = fit_period;
        phase = fit_phase - (fit_phase / period).floor() * period;
    }

    let mut bar = 0;
    let mut best_bass = -1.0;
    for q in 0..BEATS_PER_BAR {
        let sum = comb_mean(&envelope.low_onset, phase, period, q, BEATS_PER_BAR);
        if sum > best_bass {
            best_bass = sum;
            bar = q;
        }
    }

    let beat_ms = period * hop;
    let first_beat = envelope.start_ms as f64 + phase * hop;
    Some(Tempo {
        bpm: 60_000.0 / beat_ms,
        confidence,
        consistency: consistency_of(envelope, &peaks, first, last, first_beat, beat_ms),
        beat_ms,
        first_beat_ms: first_beat,
        downbeat_ms: first_beat + bar as f64 * beat_ms,
    })
}

// The onset peaks from hop `first` to hop `last`, as hop indexes: a hop
// above the hop before and at least the hop after, reaching FAINTEST_PEAK
// and one standard deviation above the mean onset of those hops.
fn onset_peaks(envelope: &SectionEnvelope, first: i64, last: i64) -> Vec<i64> {
    let onset = &envelope.onset;
    if last <= first {
        return Vec::new();
    }
    let mut sum = 0.0;
    let mut squares = 0.0;
    for &x in &onset[first as usize..=last as usize] {
        sum += x as f64;
        squares += x as f64 * x as f64;
    }
    let n = (last - first + 1) as f64;
    let mean = sum / n;
    let spread = (squares / n - mean * mean).max(0.0).sqrt();
    let threshold = FAINTEST_PEAK.max(mean + spread);
    let mut peaks = Vec::new();
    let from = first.max(1);
    let to = last.min(onset.len() as i64 - 2);
    let mut i = from;
    while i <= to {
        let k = i as usize;
        let x = onset[k];
        if x as f64 >= threshold && x > onset[k - 1] && x >= onset[k + 1] {
            peaks.push(i);
        }
        i += 1;
    }
    peaks
}

// A least-squares line through the peak nearest each beat of the grid
// (within `reach` hops; the earlier of two equally near): the fitted phase
// and period in hops, or None when fewer than 8 beats, or under half of
// them, find a peak.
fn fit_grid(peaks: &[i64], phase: f64, period: f64, size: usize, reach: f64) -> Option<(f64, f64)> {
    let mut count = 0i64;
    let mut beats = 0i64;
    let (mut sn, mut st, mut snn, mut snt) = (0.0, 0.0, 0.0, 0.0);
    let mut at = 0;
    let mut n = 0i64;
    loop {
        let beat = phase + n as f64 * period;
        if beat >= size as f64 {
            break;
        }
        beats += 1;
        while at < peaks.len() && (peaks[at] as f64) < beat - reach {
            at += 1;
        }
        let mut nearest: Option<usize> = None;
        let mut j = at;
        while j < peaks.len() && peaks[j] as f64 <= beat + reach {
            let closer = match nearest {
                None => true,
                Some(m) => (peaks[j] as f64 - beat).abs() < (peaks[m] as f64 - beat).abs(),
            };
            if closer {
                nearest = Some(j);
            }
            j += 1;
        }
        if let Some(m) = nearest {
            let t = peaks[m] as f64;
            count += 1;
            sn += n as f64;
            st += t;
            snn += n as f64 * n as f64;
            snt += n as f64 * t;
        }
        n += 1;
    }
    if count < 8 || count * 2 < beats {
        return None;
    }
    let c = count as f64;
    let d = c * snn - sn * sn;
    if d <= 0.0 {
        return None;
    }
    let slope = (c * snt - sn * st) / d;
    if slope <= 0.0 {
        return None;
    }
    Some(((st - slope * sn) / c, slope))
}

// The share of the grid's beats from hop `first` to hop `last` that fall
// within BEAT_HIT_MS of an onset peak.
fn consistency_of(
    envelope: &SectionEnvelope,
    peaks: &[i64],
    first: i64,
    last: i64,
    first_beat: f64,
    beat_ms: f64,
) -> f64 {
    let beats = grid_between(
        first_beat,
        beat_ms,
        envelope.time_of(first as usize) as f64,
        envelope.time_of(last as usize) as f64,
    );
    if beats.is_empty() {
        return 0.0;
    }
    let time = |i: usize| envelope.time_of(peaks[i] as usize) as f64;
    let mut hits = 0;
    let mut at = 0;
    for &beat in &beats {
        while at < peaks.len() && time(at) < beat - BEAT_HIT_MS {
            at += 1;
        }
        if at < peaks.len() && (time(at) - beat).abs() <= BEAT_HIT_MS {
            hits += 1;
        }
    }
    hits as f64 / beats.len() as f64
}

// The mean of values at phase + n * period (rounded to a hop), taking every
// `every`th beat from beat `from`.
fn comb_mean(values: &[f32], phase: f64, period: f64, from: i32, every: i32) -> f64 {
    let mut sum = 0.0;
    let mut count = 0;
    let mut n = from;
    loop {
        let i = round_half_up(phase + n as f64 * period) as i64;
        if i >= values.len() as i64 {
            break;
        }
        sum += values[i as usize] as f64;
        count += 1;
        n += every;
    }
    if count == 0 { 0.0 } else { sum / count as f64 }
}

/// Listens to a song as it plays and keeps its envelope, so the whole
/// song's body level and tempo are known when the next transition is
/// planned. Feed it the same samples the listener hears; start it again for
/// each song.
pub struct LiveTap {
    builder: EnvelopeBuilder,
}

impl LiveTap {
    pub fn new(sample_rate: u32, channels: u32) -> Self {
        LiveTap { builder: EnvelopeBuilder::new(sample_rate, channels, 0) }
    }

    pub fn push(&mut self, samples: &[f32]) {
        self.builder.push(samples);
    }

    pub fn push_i16(&mut self, samples: &[i16]) {
        self.builder.push_i16(samples);
    }

    /// How much has been heard, in milliseconds.
    pub fn heard_ms(&self) -> i64 {
        self.builder.heard_ms()
    }

    /// The body level of everything heard, or `None` while it is all silence.
    pub fn body_level_db(&self) -> Option<f64> {
        body_level_of(&self.builder.build(), HEAD_BODY_PERCENTILE)
    }

    /// The tempo of everything heard when it is trusted, else `None`.
    pub fn tempo_prior(&self, tag_bpm: Option<f64>) -> Option<f64> {
        SectionAnalysis::of(self.builder.build(), tag_bpm, None, HEAD_BODY_PERCENTILE)
            .features
            .tempo
            .filter(Tempo::confident)
            .map(|t| t.bpm)
    }
}
