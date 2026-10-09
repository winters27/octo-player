//! Planned transitions between songs: where the blend starts in the
//! outgoing song, where the incoming one comes in, how long they overlap,
//! the shape of the gain curve, the filter sweeps over each song, and an
//! optional rate for the incoming song so the two tempos line up.
//!
//! The mixer runs a plan; [`plan`] makes one from the sections the scout
//! decoded. A plan with `k = 0`, no filters and no rate is the plain
//! equal-power crossfade.

use std::f32::consts::FRAC_PI_2;

use crate::scout::PcmSink;

/// Slope of the S-curve mixed into the gain curve.
const SIGMOID_SLOPE: f32 = 12.0;

/// Curve parameter of the plain crossfade used when no analysis is there.
pub const PLAIN_K: f32 = 0.4;

/// How much of the outgoing song's end the scout decodes, in seconds.
pub const TAIL_SECS: f64 = 60.0;

/// How much of the incoming song's start the scout decodes, in seconds.
pub const HEAD_SECS: f64 = 30.0;

/// How far before the end of the outgoing song a planned blend may start.
pub const LATEST_EXIT_SECS: f64 = 45.0;

/// After the blend, how long the incoming song takes to ease back to its
/// own rate when its tempo was matched.
pub const RATE_EASE_SECS: f64 = 5.0;

/// The closest the incoming rate may be moved from normal, either way.
pub const RATE_RANGE: f32 = 0.06;

/// Below this level the outgoing song counts as silent during a blend.
pub const SILENCE_GATE_DB: f32 = -60.0;

/// The least time between deciding a blend and its start, so the incoming
/// song is open and buffered from its entry point in time.
pub const LEAD_SECS: f64 = 3.0;

/// The user's choices for transitions.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AutomixSettings {
    /// Choose where each blend starts from the music; off is the fixed
    /// crossfade at the end of the song.
    pub smart_transitions: bool,
    /// Sweep filters over both songs during a smart blend.
    pub filter_sweeps: bool,
    /// Nudge the incoming song's tempo to the outgoing one's.
    pub match_tempo: bool,
    /// The longest a smart blend may last, in milliseconds; 0 uses the
    /// crossfade length.
    pub max_overlap_ms: u32,
}

impl Default for AutomixSettings {
    fn default() -> Self {
        Self { smart_transitions: false, filter_sweeps: true, match_tempo: false, max_overlap_ms: 8_000 }
    }
}

/// The character of a blend's gain curve.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CurveKind {
    /// The equal-power crossfade, `k = 0`.
    EqualPower,
    /// The plain crossfade with a little S-curve, used without analysis.
    Plain,
    /// A short, quick swap.
    Cut,
    /// The incoming song is louder and lifts in.
    Lift,
    /// A long, smooth blend.
    Blend,
}

impl CurveKind {
    pub fn name(self) -> &'static str {
        match self {
            CurveKind::EqualPower => "equal-power",
            CurveKind::Plain => "plain",
            CurveKind::Cut => "cut",
            CurveKind::Lift => "lift",
            CurveKind::Blend => "blend",
        }
    }
}

/// One planned transition. Times are song times: the outgoing song's for
/// `start_secs` and `beats_secs`, the incoming song's for `entry_secs`.
#[derive(Clone, Debug, PartialEq)]
pub struct TransitionPlan {
    /// When, in the outgoing song, the incoming one comes in.
    pub start_secs: f64,
    /// Where the incoming song starts playing from.
    pub entry_secs: f64,
    /// How long both sound; the outgoing song stops at its end.
    pub overlap_secs: f64,
    pub kind: CurveKind,
    /// Gain curve parameter: 0 is equal power, 1 is the S-curve alone.
    pub k: f32,
    /// How deep the filter sweeps go, 0 for none.
    pub filter_strength: f32,
    /// Beats of the outgoing song inside the overlap, for stepping its
    /// low-pass on the beat; empty for a smooth sweep.
    pub beats_secs: Vec<f64>,
    /// The incoming song's playback rate through the blend, eased back to 1
    /// after it; `None` plays it at its own rate.
    pub rate: Option<f32>,
    /// When the outgoing song stays below this level (dBFS) for 0.3 s during
    /// the blend, the blend finishes early.
    pub silence_gate_db: Option<f32>,
    /// Both songs are lowered by this many dB through the blend.
    pub headroom_db: f32,
    /// Why this plan, for the log.
    pub reason: String,
}

impl TransitionPlan {
    /// The crossfade at the end of the outgoing song: `overlap_secs` long,
    /// ending as the song does, with curve parameter `k`. With `k = 0` it is
    /// exactly the equal-power crossfade; otherwise the blend also finishes
    /// early once the outgoing song falls silent.
    pub fn fixed(a_len_secs: f64, overlap_secs: f64, k: f32, reason: &str) -> Self {
        TransitionPlan {
            start_secs: (a_len_secs - overlap_secs).max(0.0),
            entry_secs: 0.0,
            overlap_secs,
            kind: if k == 0.0 { CurveKind::EqualPower } else { CurveKind::Plain },
            k,
            filter_strength: 0.0,
            beats_secs: Vec::new(),
            rate: None,
            silence_gate_db: (k != 0.0).then_some(SILENCE_GATE_DB),
            headroom_db: 0.0,
            reason: reason.to_string(),
        }
    }

    /// This plan when its start has already passed, or comes too soon to
    /// line the incoming song up: the blend runs at the end of the outgoing
    /// song over what is left of it, at most the planned overlap, from the
    /// incoming song's start, with the same curve and filters but no beat
    /// steps or tempo matching.
    pub fn late(&self, a_len_secs: f64, position_secs: f64) -> Self {
        let overlap = (a_len_secs - position_secs).min(self.overlap_secs).max(0.0);
        TransitionPlan {
            start_secs: position_secs.max(a_len_secs - overlap),
            entry_secs: 0.0,
            overlap_secs: overlap,
            beats_secs: Vec::new(),
            rate: None,
            reason: format!("late ({})", self.reason),
            ..self.clone()
        }
    }

    /// The line logged for this plan.
    pub fn describe(&self) -> String {
        let rate = self.rate.map(|r| format!(", rate {r:.3}")).unwrap_or_default();
        let headroom = if self.headroom_db > 0.0 {
            format!(", headroom {:.1} dB", self.headroom_db)
        } else {
            String::new()
        };
        format!(
            "automix: {} at {:.2}s, entry {:.2}s, overlap {:.2}s, k {:.2}, filters {:.2}{rate}{headroom}: {}",
            self.kind.name(),
            self.start_secs,
            self.entry_secs,
            self.overlap_secs,
            self.k,
            self.filter_strength,
            self.reason
        )
    }
}

fn sigmoid(t: f32) -> f32 {
    1.0 / (1.0 + (-SIGMOID_SLOPE * (t - 0.5)).exp())
}

/// The S-curve, scaled to run from exactly 0 at `t = 0` to 1 at `t = 1`.
pub fn s_curve(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    let (lo, hi) = (sigmoid(0.0), sigmoid(1.0));
    (sigmoid(t) - lo) / (hi - lo)
}

/// The outgoing and incoming gains at progress `t` (0 to 1) for curve
/// parameter `k`. The incoming gain keeps the summed power at one. With
/// `k = 0` this is exactly the equal-power crossfade.
pub fn gains(t: f32, k: f32) -> (f32, f32) {
    if k == 0.0 {
        return (crate::crossfade::fade_out_volume(t), crate::crossfade::fade_in_volume(t));
    }
    let t = t.clamp(0.0, 1.0);
    let out = (t * FRAC_PI_2).cos() * (1.0 - k) + (1.0 - s_curve(t)) * k;
    let out = out.clamp(0.0, 1.0);
    (out, (1.0 - out * out).max(0.0).sqrt())
}

// From `from` to `to` on a log scale, `p` of the way (0 to 1).
fn log_lerp(from: f32, to: f32, p: f32) -> f32 {
    from * (to / from).powf(p.clamp(0.0, 1.0))
}

/// The outgoing song's low-pass at progress `t`: 18 kHz down to
/// `18000 * (450 / 18000)^strength` Hz at the end, log-linearly.
pub fn outgoing_low_pass_hz(t: f32, strength: f32) -> f32 {
    let end = 18_000.0 * (450.0f32 / 18_000.0).powf(strength);
    log_lerp(18_000.0, end, t)
}

/// The outgoing song's high-pass at progress `t`: 10 Hz up to
/// `300 * strength` Hz by halfway, held after.
pub fn outgoing_high_pass_hz(t: f32, strength: f32) -> f32 {
    let end = (300.0 * strength).max(10.0);
    log_lerp(10.0, end, t / 0.5)
}

/// The incoming song's high-pass at progress `t`: `675 * strength` Hz
/// down to 20 Hz, fully open from 60 % of the way.
pub fn incoming_high_pass_hz(t: f32, strength: f32) -> f32 {
    let start = (675.0 * strength).max(20.0);
    log_lerp(start, 20.0, t / 0.6)
}

/// The outgoing low-pass stepped on the beat: it holds the smooth sweep's
/// value from the last beat and glides to each new beat's value over an
/// eighth of a bar (half a beat). `beats` are progress values, in order.
pub fn stepped_low_pass_hz(t: f32, strength: f32, beats: &[f32]) -> f32 {
    let Some(i) = beats.iter().rposition(|&b| b <= t) else {
        return outgoing_low_pass_hz(0.0, strength);
    };
    let here = beats[i];
    let before = if i > 0 {
        outgoing_low_pass_hz(beats[i - 1], strength)
    } else {
        outgoing_low_pass_hz(0.0, strength)
    };
    let target = outgoing_low_pass_hz(here, strength);
    let spacing = match (beats.get(i + 1), i.checked_sub(1).map(|j| beats[j])) {
        (Some(&next), _) => next - here,
        (None, Some(prev)) => here - prev,
        (None, None) => 0.0,
    };
    // Four beats to the bar: an eighth of a bar is half a beat.
    let glide = spacing * 4.0 / 8.0;
    if glide <= 0.0 {
        return target;
    }
    log_lerp(before, target, (t - here) / glide)
}

/// What the scout learned from one decoded section of a song. Times are
/// song times.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SectionAnalysis {
    /// Where the section starts in the song.
    pub start_secs: f64,
    /// How much was decoded.
    pub secs: f64,
    pub sample_rate: u32,
}

/// Takes in a section's mono sound as the scout decodes it, and sums it up
/// for the planner.
#[derive(Default)]
pub struct SectionAnalyzer {
    analysis: SectionAnalysis,
    frames: u64,
}

impl SectionAnalyzer {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn finish(mut self) -> SectionAnalysis {
        if self.analysis.sample_rate > 0 {
            self.analysis.secs = self.frames as f64 / self.analysis.sample_rate as f64;
        }
        self.analysis
    }
}

impl PcmSink for SectionAnalyzer {
    fn begin(&mut self, start_secs: f64, sample_rate: u32) {
        self.analysis.start_secs = start_secs;
        self.analysis.sample_rate = sample_rate;
    }

    fn pcm(&mut self, mono: &[f32]) {
        self.frames += mono.len() as u64;
    }
}

/// What the live tap learned about a song from what has played of it.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct LiveAnalysis {
    /// Where the tap began in the song.
    pub start_secs: f64,
    /// How much it has heard.
    pub secs: f64,
    pub sample_rate: u32,
    /// The mean level of what it heard, in dBFS (-100 for silence).
    pub mean_db: f32,
}

/// Takes in the playing song's mono sound as it is mixed (on the mixing
/// thread, never the device's) and keeps a running summary of it.
#[derive(Clone, Debug, Default)]
pub struct LiveAnalyzer {
    start_secs: f64,
    sample_rate: u32,
    frames: u64,
    power: f64,
}

impl LiveAnalyzer {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn summary(&self) -> LiveAnalysis {
        let mean = if self.frames > 0 { self.power / self.frames as f64 } else { 0.0 };
        LiveAnalysis {
            start_secs: self.start_secs,
            secs: if self.sample_rate > 0 { self.frames as f64 / self.sample_rate as f64 } else { 0.0 },
            sample_rate: self.sample_rate,
            mean_db: if mean > 0.0 { (10.0 * mean.log10()).max(-100.0) as f32 } else { -100.0 },
        }
    }
}

impl PcmSink for LiveAnalyzer {
    fn begin(&mut self, start_secs: f64, sample_rate: u32) {
        self.start_secs = start_secs;
        self.sample_rate = sample_rate;
    }

    fn pcm(&mut self, mono: &[f32]) {
        self.frames += mono.len() as u64;
        self.power += mono.iter().map(|&s| s as f64 * s as f64).sum::<f64>();
    }
}

/// What the planner works from.
#[derive(Clone, Debug)]
pub struct PlanInput<'a> {
    pub a_len_secs: f64,
    pub b_len_secs: f64,
    /// The end of the outgoing song.
    pub a_tail: &'a SectionAnalysis,
    /// The start of the incoming song.
    pub b_head: &'a SectionAnalysis,
    /// What the live tap heard of the outgoing song while it played.
    pub a_live: Option<&'a LiveAnalysis>,
    /// The blend length the crossfade rules allow, in song time.
    pub fixed_overlap_secs: f64,
    /// The longest blend the settings allow, in song time.
    pub max_overlap_secs: f64,
    pub settings: &'a AutomixSettings,
}

/// Plans the transition from the scouted sections. For now the plain
/// crossfade at the end of the outgoing song.
pub fn plan(input: &PlanInput) -> TransitionPlan {
    #[cfg(test)]
    if let Some(planner) = *TEST_PLANNER.lock().unwrap_or_else(|e| e.into_inner()) {
        return planner(input);
    }
    TransitionPlan::fixed(input.a_len_secs, input.fixed_overlap_secs, PLAIN_K, "plain crossfade at the end")
}

/// A planner a test puts in place of `plan`.
#[cfg(test)]
pub type Planner = fn(&PlanInput) -> TransitionPlan;

#[cfg(test)]
pub static TEST_PLANNER: std::sync::Mutex<Option<Planner>> = std::sync::Mutex::new(None);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn curve_ends_and_middle() {
        for k in [0.0, 0.2, 0.4, 0.55, 0.75, 1.0] {
            let (o0, i0) = gains(0.0, k);
            let (o1, i1) = gains(1.0, k);
            assert!((o0 - 1.0).abs() < 1e-6 && i0.abs() < 1e-3, "k {k}: {o0} {i0}");
            assert!(o1.abs() < 1e-6 && (i1 - 1.0).abs() < 1e-6, "k {k}: {o1} {i1}");
            let (o, i) = gains(0.5, k);
            let expected = std::f32::consts::FRAC_1_SQRT_2 * (1.0 - k) + 0.5 * k;
            assert!((o - expected).abs() < 1e-6, "k {k}: {o} vs {expected}");
            assert!((o * o + i * i - 1.0).abs() < 1e-5);
        }
        // k = 0.4 halfway: 0.7071 * 0.6 + 0.5 * 0.4.
        assert!((gains(0.5, 0.4).0 - 0.624_264).abs() < 1e-5);
        // A quarter of the way the S-curve has barely moved: 0.0452.
        assert!((s_curve(0.25) - 0.045_18).abs() < 1e-4, "{}", s_curve(0.25));
        assert!((gains(0.25, 0.4).0 - 0.936_26).abs() < 1e-4, "{}", gains(0.25, 0.4).0);
        assert_eq!(s_curve(0.0), 0.0);
        assert!((s_curve(1.0) - 1.0).abs() < 1e-6);
        assert!((s_curve(0.5) - 0.5).abs() < 1e-6);
    }

    #[test]
    fn zero_k_is_the_equal_power_crossfade_exactly() {
        for i in 0..=100 {
            let t = i as f32 / 100.0;
            let (o, n) = gains(t, 0.0);
            assert_eq!(o, crate::crossfade::fade_out_volume(t));
            assert_eq!(n, crate::crossfade::fade_in_volume(t));
        }
    }

    #[test]
    fn the_out_gain_only_falls() {
        for k in [0.2, 0.4, 0.75] {
            let mut last = 1.0;
            for i in 0..=200 {
                let (o, _) = gains(i as f32 / 200.0, k);
                assert!(o <= last + 1e-6);
                last = o;
            }
        }
    }

    #[test]
    fn sweeps_follow_their_paths() {
        let s = 0.7;
        assert!((outgoing_low_pass_hz(0.0, s) - 18_000.0).abs() < 1e-2);
        let end = 18_000.0 * (450.0f32 / 18_000.0).powf(s);
        assert!((outgoing_low_pass_hz(1.0, s) - end).abs() < 0.5);
        assert!((outgoing_high_pass_hz(0.0, s) - 10.0).abs() < 1e-4);
        assert!((outgoing_high_pass_hz(0.5, s) - 210.0).abs() < 1e-2);
        assert!((outgoing_high_pass_hz(0.9, s) - 210.0).abs() < 1e-2);
        assert!((incoming_high_pass_hz(0.0, s) - 472.5).abs() < 1e-2);
        assert!((incoming_high_pass_hz(0.6, s) - 20.0).abs() < 1e-3);
        assert!((incoming_high_pass_hz(1.0, s) - 20.0).abs() < 1e-3);
        // Halfway on a log scale is the geometric mean.
        let mid = outgoing_low_pass_hz(0.5, s);
        assert!((mid - (18_000.0 * end).sqrt()).abs() < 1.0);
    }

    #[test]
    fn stepped_low_pass_holds_then_glides_on_each_beat() {
        let s = 0.7;
        let beats = [0.0, 0.25, 0.5, 0.75];
        // Glide is half a beat: 0.125 of the overlap.
        let at = |t| stepped_low_pass_hz(t, s, &beats);
        assert!((at(0.1) - 18_000.0).abs() < 1e-2);
        let on_second = outgoing_low_pass_hz(0.25, s);
        assert!((at(0.25) - 18_000.0).abs() < 1e-2);
        assert!((at(0.25 + 0.125) - on_second).abs() < 0.5);
        assert!((at(0.45) - on_second).abs() < 0.5);
        let half = at(0.25 + 0.0625);
        assert!(half < 18_000.0 && half > on_second);
        assert!((stepped_low_pass_hz(0.5, s, &[]) - 18_000.0).abs() < 1e-2);
    }

    #[test]
    fn a_late_plan_blends_over_what_is_left() {
        let mut plan = TransitionPlan::fixed(200.0, 6.0, 0.55, "test");
        plan.start_secs = 150.0;
        plan.entry_secs = 1.2;
        plan.filter_strength = 0.7;
        plan.beats_secs = vec![150.0, 150.5];
        plan.rate = Some(1.02);
        // Passed, with more than the overlap left: at the end of the song.
        let late = plan.late(200.0, 160.0);
        assert_eq!((late.start_secs, late.entry_secs, late.overlap_secs), (194.0, 0.0, 6.0));
        assert_eq!((late.k, late.filter_strength), (0.55, 0.7));
        assert!(late.beats_secs.is_empty() && late.rate.is_none());
        assert_eq!(late.silence_gate_db, Some(SILENCE_GATE_DB));
        // Less than the overlap left: from now, over what is left.
        let later = plan.late(200.0, 197.5);
        assert_eq!((later.start_secs, later.overlap_secs), (197.5, 2.5));
        assert!(later.reason.starts_with("late"));
    }

    #[test]
    fn the_live_analyzer_measures_what_it_hears() {
        let mut live = LiveAnalyzer::new();
        live.begin(3.0, 1_000);
        live.pcm(&[0.5; 500]);
        live.pcm(&[-0.5; 500]);
        let a = live.summary();
        assert_eq!((a.start_secs, a.secs, a.sample_rate), (3.0, 1.0, 1_000));
        assert!((a.mean_db - 20.0 * 0.5f32.log10()).abs() < 1e-4, "{}", a.mean_db);
        assert_eq!(LiveAnalyzer::new().summary().mean_db, -100.0);
    }

    #[test]
    fn the_planner_falls_back_to_the_plain_crossfade() {
        let section = SectionAnalysis::default();
        let settings = AutomixSettings::default();
        let input = PlanInput {
            a_len_secs: 200.0,
            b_len_secs: 180.0,
            a_tail: &section,
            b_head: &section,
            a_live: None,
            fixed_overlap_secs: 6.0,
            max_overlap_secs: 8.0,
            settings: &settings,
        };
        let p = plan(&input);
        assert_eq!((p.start_secs, p.entry_secs, p.overlap_secs, p.k), (194.0, 0.0, 6.0, PLAIN_K));
        assert_eq!(p.filter_strength, 0.0);
        assert!(p.describe().starts_with("automix: plain at 194.00s"));
    }
}
