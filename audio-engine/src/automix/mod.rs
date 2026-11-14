//! Planned transitions between songs: where the blend starts in the
//! outgoing song, where the incoming one comes in, how long they overlap,
//! the shape of the gain curve, the filter sweeps over each song, and an
//! optional rate for the incoming song so the two tempos line up.
//!
//! The scout decodes the end of the playing song and the start of the next
//! one into envelopes ([`SectionAnalyzer`]), unless the app handed over the
//! song's [`TransitionProfile`] from its server; a live tap follows the
//! playing song as it is heard ([`LiveAnalyzer`]); [`plan`] turns them into a
//! [`TransitionPlan`] that the mixer runs.

pub mod curves;
pub mod dsp;
pub mod envelope;
pub mod features;
pub mod planner;
pub mod profile;
pub mod song_profile;

pub use curves::*;
pub use dsp::{BUTTERWORTH_Q, Biquad};
pub use envelope::{ENVELOPE_HOP_MS, EnvelopeBuilder, SILENT_DB, SectionEnvelope, db_of};
pub use features::{
    LiveTap, SILENCE_BELOW_BODY_DB, SILENCE_FLOOR_DB, SectionAnalysis, SectionFeatures, Tempo, analyze_head,
    analyze_tail, body_level_of, fold_tempo_ratio,
};
pub use planner::{
    AUTOMIX_LONGEST_INTRO_MS, DRIFT_LIMIT_MS, LATE_MOVE_UNBOUNDED, PRE_ROLL_MS, PlanSettings,
    TransitionContext, TransitionKind, TransitionPlan, drop_cut_at_ms, is_plain_genre, plan_transition,
    transition_gains,
};
pub use profile::{
    PROFILE_REVISION, PROFILE_VERSION, SectionProfile, TransitionProfile, level_code, level_of_code,
};
pub use song_profile::{SongProfile, SongProfileSection, SongProfileTempo};

use crate::crossfade::{FadeSong, SHORTEST_FADE_MS};
use crate::scout::PcmSink;

/// How much of the outgoing song's end the scout decodes, in seconds.
pub const TAIL_SECS: f64 = 60.0;

/// How much of the incoming song's start the scout decodes, in seconds.
pub const HEAD_SECS: f64 = 45.0;

/// How far before the end of the outgoing song a planned blend may start.
pub const LATEST_EXIT_SECS: f64 = planner::LONGEST_EARLY_EXIT_MS as f64 / 1_000.0;

/// After the blend, how long the incoming song takes to ease back to its
/// own rate when its tempo was matched.
pub const RATE_EASE_SECS: f64 = BEAT_MATCH_SETTLE_MS / 1_000.0;

/// The closest the incoming rate may be moved from normal, either way.
pub const RATE_RANGE: f64 = 0.06;

/// The least time between deciding a blend and its start, so the incoming
/// song is open and buffered from its entry point in time.
pub const LEAD_SECS: f64 = PRE_ROLL_MS as f64 / 1_000.0;

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
    /// The shape of the fixed crossfade, with smart transitions off.
    pub fade_curve: FadeCurve,
    /// The longest a next song's quiet intro may play under the end of the
    /// song before it, in milliseconds, at most 16 s.
    #[uniffi(default = 16000)]
    pub longest_intro_ms: u32,
}

impl Default for AutomixSettings {
    fn default() -> Self {
        Self {
            smart_transitions: false,
            filter_sweeps: true,
            match_tempo: false,
            max_overlap_ms: 8_000,
            fade_curve: FadeCurve::EqualPower,
            longest_intro_ms: AUTOMIX_LONGEST_INTRO_MS as u32,
        }
    }
}

impl AutomixSettings {
    /// What the planner works with for these settings, with the crossfade
    /// `crossfade_ms` long and the songs playing at `speed`. The planner's
    /// lengths are in song time, so they grow and shrink with the speed.
    pub fn plan_settings(&self, crossfade_ms: u32, speed: f64) -> PlanSettings {
        let max_ms = if self.max_overlap_ms > 0 { self.max_overlap_ms } else { crossfade_ms };
        PlanSettings {
            max_overlap_ms: (max_ms as f64 * speed).round() as i64,
            longest_intro_ms: (self.longest_intro_ms as f64 * speed).round() as i64,
            smart: true,
            filter_sweeps: self.filter_sweeps,
            beat_match: self.match_tempo,
            fade_curve: self.fade_curve,
        }
    }
}

/// The outgoing and incoming gains at progress `t` (0 to 1) for curve
/// weight `k`. The incoming gain keeps the summed power at one; `k = 0` is
/// the equal-power crossfade.
pub fn gains(t: f64, k: f64) -> (f64, f64) {
    (automix_out_gain(t, k), automix_in_gain(t, k))
}

impl TransitionPlan {
    /// The equal-power crossfade at the end of the outgoing song: `overlap_ms`
    /// long, ending as the song does. [`TransitionPlan::with_curve`] gives
    /// it another shape.
    pub fn fixed_crossfade(len_a_ms: i64, overlap_ms: i64, why: &str) -> Self {
        let start_ms = (len_a_ms - overlap_ms).max(0);
        TransitionPlan {
            start_ms,
            entry_ms: 0,
            overlap_ms,
            kind: TransitionKind::Crossfade,
            k: 0.0,
            filter_strength: 0.0,
            beat_match_rate: None,
            beat_ms: None,
            beat_anchor_ms: None,
            late: false,
            headroom_db: 0.0,
            reason: format!(
                "crossfade at {} over {}: {why}",
                planner::seconds(start_ms),
                planner::seconds(overlap_ms)
            ),
            curve: FadeCurve::EqualPower,
            hold_share: 0.0,
            rise_share: 0.0,
            intro_level: 1.0,
            latest_end_ms: len_a_ms,
            late_move_bars: LATE_MOVE_UNBOUNDED,
            both_heard_start_ms: 0,
            both_heard_end_ms: 0,
            lock_ms: 0.0,
            drift_gap: 0.0,
            drift_at_end_ms: 0.0,
        }
    }

    /// This fixed crossfade with `curve` for its shape, and the headroom
    /// that shape plays with.
    pub fn with_curve(self, curve: FadeCurve) -> Self {
        TransitionPlan { curve, headroom_db: fade_headroom_db(curve), ..self }
    }

    /// This plan when its start has already passed, or comes too soon to
    /// line the incoming song up: the blend ends where it would have, starts
    /// at `now_ms` at the earliest, keeps its entry, curve and filters, and
    /// drops its bar lock and tempo matching, so it may move by any number
    /// of bars and its beats are no longer lined up. A blend held through
    /// the incoming song's silent lead-in holds as long from its new start.
    /// `None` when too little is left to blend, or to fade the outgoing song
    /// out over SHORTEST_FADE_MS after such a hold, and for an intro, whose
    /// trim was measured for where it was planned.
    pub fn late_from(&self, now_ms: i64) -> Option<Self> {
        if self.kind == TransitionKind::Intro {
            return None;
        }
        let end = self.start_ms + self.overlap_ms;
        let start_ms = self.start_ms.max(now_ms);
        let overlap_ms = end - start_ms;
        if overlap_ms < SHORTEST_FADE_MS as i64 {
            return None;
        }
        let hold_share = (self.hold_share * self.overlap_ms as f64 / overlap_ms as f64).min(1.0);
        if (1.0 - hold_share) * (overlap_ms as f64) < SHORTEST_FADE_MS as f64 - 1e-6 {
            return None;
        }
        Some(TransitionPlan {
            start_ms,
            overlap_ms,
            hold_share,
            beat_match_rate: None,
            beat_ms: None,
            beat_anchor_ms: None,
            late: true,
            late_move_bars: LATE_MOVE_UNBOUNDED,
            both_heard_start_ms: 0,
            both_heard_end_ms: 0,
            lock_ms: 0.0,
            drift_gap: 0.0,
            drift_at_end_ms: 0.0,
            reason: format!(
                "late {} at {} over {} ({})",
                self.kind.name(),
                planner::seconds(start_ms),
                planner::seconds(overlap_ms),
                self.reason
            ),
            ..self.clone()
        })
    }

    /// What plays when this plan's start comes before `now_ms + lead_ms`,
    /// too soon to line the incoming song up, with `settle_ms` more for the
    /// incoming song to read again after it is moved to its entry. With
    /// smart transitions a planned kind (a blend, cut, lift or intro, late
    /// or not) is planned again by `replan` from `now_ms + lead_ms +
    /// settle_ms`, so every bound holds at its new place and its entry is
    /// the new plan's own. A crossfade, as planned or as planned again (a
    /// genre's, a short song's, or one standing in for a plan that did not
    /// fit), and a new plan still starting before then, blend over what is
    /// left from `now_ms + settle_ms` with their entry kept. The fixed
    /// crossfade of smart transitions off blends over what is left from
    /// `now_ms`, later by the settle only when it enters part way. A
    /// gapless plan joins gaplessly; `None` when too little is left.
    pub fn started_late(
        self,
        now_ms: i64,
        lead_ms: i64,
        settle_ms: i64,
        smart: bool,
        replan: impl FnOnce(i64) -> TransitionPlan,
    ) -> Option<TransitionPlan> {
        if !smart {
            let settle = if self.entry_ms > 0 { settle_ms } else { 0 };
            return self.late_from(now_ms + settle);
        }
        let ready = now_ms + lead_ms + settle_ms;
        let plan = if self.kind == TransitionKind::Crossfade { self } else { replan(ready) };
        match plan.kind {
            TransitionKind::Gapless => Some(plan),
            TransitionKind::Crossfade => plan.late_from(now_ms + settle_ms),
            _ if plan.start_ms < ready => plan.late_from(now_ms + settle_ms),
            _ => Some(plan),
        }
    }

    pub fn start_secs(&self) -> f64 {
        self.start_ms as f64 / 1_000.0
    }

    pub fn entry_secs(&self) -> f64 {
        self.entry_ms as f64 / 1_000.0
    }

    pub fn overlap_secs(&self) -> f64 {
        self.overlap_ms as f64 / 1_000.0
    }

    /// The line logged for this plan.
    pub fn describe(&self) -> String {
        format!("automix: {}", self.reason)
    }
}

/// Which part of a song a [`SectionAnalyzer`] takes in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SectionKind {
    Head,
    Tail,
}

/// Takes in a section's mono sound as the scout decodes it, builds its
/// envelope, and finds its features.
pub struct SectionAnalyzer {
    kind: SectionKind,
    tag_bpm: Option<f64>,
    builder: Option<EnvelopeBuilder>,
}

impl SectionAnalyzer {
    /// `tag_bpm` is the song's tempo from its tags, when it has one.
    pub fn new(kind: SectionKind, tag_bpm: Option<f64>) -> Self {
        SectionAnalyzer { kind, tag_bpm, builder: None }
    }

    /// The section's features; `None` when no sound came in.
    pub fn finish(self) -> Option<SectionAnalysis> {
        let envelope = self.builder?.into_envelope();
        if envelope.size() == 0 {
            return None;
        }
        Some(match self.kind {
            SectionKind::Head => analyze_head(envelope, self.tag_bpm, None),
            SectionKind::Tail => analyze_tail(envelope, self.tag_bpm, None),
        })
    }
}

impl PcmSink for SectionAnalyzer {
    fn begin(&mut self, start_secs: f64, sample_rate: u32) {
        let start_ms = (start_secs * 1_000.0).round() as i64;
        self.builder = Some(EnvelopeBuilder::new(sample_rate, 1, start_ms));
    }

    fn pcm(&mut self, mono: &[f32]) {
        if let Some(b) = &mut self.builder {
            b.push(mono);
        }
    }
}

/// What the live tap learned about a song from what has played of it.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct LiveAnalysis {
    /// How much of the song has been heard, in milliseconds.
    pub heard_ms: i64,
    /// The body level of what was heard, `None` while it was all silence.
    pub body_level_db: Option<f64>,
    /// The tempo of what was heard, when it is trusted.
    pub tempo_prior: Option<f64>,
}

/// Takes in the playing song's mono sound as it is mixed (on the mixing
/// thread, never the device's) and keeps its envelope.
#[derive(Default)]
pub struct LiveAnalyzer {
    tap: Option<LiveTap>,
}

impl LiveAnalyzer {
    pub fn new() -> Self {
        Self::default()
    }

    /// What has been heard so far. `tag_bpm` picks the tempo's octave.
    pub fn summary(&self, tag_bpm: Option<f64>) -> LiveAnalysis {
        let Some(tap) = &self.tap else { return LiveAnalysis::default() };
        LiveAnalysis {
            heard_ms: tap.heard_ms(),
            body_level_db: tap.body_level_db(),
            tempo_prior: tap.tempo_prior(tag_bpm),
        }
    }

    /// How much has been heard, in milliseconds.
    pub fn heard_ms(&self) -> i64 {
        self.tap.as_ref().map_or(0, |t| t.heard_ms())
    }
}

impl PcmSink for LiveAnalyzer {
    fn begin(&mut self, _start_secs: f64, sample_rate: u32) {
        self.tap = Some(LiveTap::new(sample_rate, 1));
    }

    fn pcm(&mut self, mono: &[f32]) {
        if let Some(tap) = &mut self.tap {
            tap.push(mono);
        }
    }
}

/// What the planner works from.
#[derive(Clone, Debug)]
pub struct PlanInput<'a> {
    pub current: FadeSong,
    pub next: FadeSong,
    /// The end of the outgoing song, when it was scouted.
    pub tail: Option<&'a SectionAnalysis>,
    /// The start of the incoming song, when it was scouted.
    pub head: Option<&'a SectionAnalysis>,
    pub settings: PlanSettings,
    pub context: TransitionContext,
}

/// Plans the transition from the scouted sections.
pub fn plan(input: &PlanInput) -> TransitionPlan {
    plan_transition(
        &input.current,
        Some(&input.next),
        input.tail,
        input.head,
        &input.settings,
        &input.context,
    )
}

/// A planner a test puts in place of `plan` on one engine.
#[cfg(test)]
pub type Planner = fn(&PlanInput) -> TransitionPlan;

#[cfg(test)]
mod fade_vectors;
#[cfg(test)]
mod synthetic;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod vectors;
