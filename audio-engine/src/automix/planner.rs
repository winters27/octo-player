//! Chooses where and how one song hands over to the next.

use super::curves::{
    BEAT_MATCH_SETTLE_MS, BLEND_CURVE, CROSSFADE_CURVE, CUT_CURVE, FILTER_STRENGTH, FadeCurve, LIFT_CURVE,
    automix_in_gain, automix_out_gain, beat_match_rate_at, blend_gains, incoming_high_pass_hz, intro_gains,
    intro_in_gain, intro_out_gain, outgoing_high_pass_hz, outgoing_low_pass_hz, past_hold,
    stepped_outgoing_low_pass_hz,
};
use super::features::{
    BEATS_PER_BAR, SectionAnalysis, SectionFeatures, TEMPO_CONFIDENT, Tempo, fold_tempo_ratio, round_half_up,
};
use crate::crossfade::{FadeSong, SHORTEST_FADE_MS, crossfade_length};

/// Songs shorter than this get the plain crossfade, never a planned one.
pub const SHORTEST_AUTOMIX_SONG_MS: i64 = 35_000;

/// The shortest planned blend, and the longest that still counts as a cut.
pub const SHORTEST_BLEND_MS: i64 = 1_800;
pub const CUT_BLEND_MS: i64 = 2_400;

/// The latest a blend may end: this long before the outgoing song's end.
pub const END_MARGIN_MS: i64 = 250;

/// The earliest a blend may start: this far into the song, this share of
/// it, once the song has played long enough to count as played (half of it
/// or `COUNTS_AS_PLAYED_MS`, whichever is less), and no more than
/// `LONGEST_EARLY_EXIT_MS` before the latest end.
pub const EARLIEST_START_MS: i64 = 30_000;
pub const EARLIEST_START_SHARE: f64 = 0.5;
pub const COUNTS_AS_PLAYED_MS: i64 = 240_000;
pub const LONGEST_EARLY_EXIT_MS: i64 = 45_000;

/// The incoming song needs this long to get ready before the blend starts.
pub const PRE_ROLL_MS: i64 = 3_000;

/// Without a beat grid the start is tried every this many milliseconds.
pub const START_STEP_MS: i64 = 250;

/// Both songs play this much quieter through the overlap, so their sum does
/// not clip.
pub const OVERLAP_HEADROOM_DB: f64 = 1.0;

/// A plan's `late_move_bars` when it may move later by any number of bars.
pub const LATE_MOVE_UNBOUNDED: i32 = i32::MAX;

/// The longest a next song's quiet intro may play under the end of the
/// song before it, in milliseconds.
pub const AUTOMIX_LONGEST_INTRO_MS: i64 = 16_000;

/// An intro plays under the outgoing song only when its quiet stretch lasts
/// at least this long.
pub const INTRO_SHORTEST_MS: i64 = 4_000;

/// The outgoing song fades out under an intro over this many bars when the
/// blend is locked to the bar, else over `INTRO_FADE_MS`.
pub const INTRO_FADE_BARS: i32 = 2;
pub const INTRO_FADE_MS: i64 = 3_000;

/// The incoming song rises to its own level over this long at the start of
/// an intro (one bar when locked).
pub const INTRO_RISE_MS: i64 = 1_000;

/// Through an intro's hold the incoming song, with its rise, is lowered (the
/// plan's `intro_level`) until it stays at least `INTRO_UNDER_OUTGOING_DB`
/// under the outgoing song held at its own level, at the same moments, for
/// `INTRO_UNDER_OUTGOING_SHARE` of the hold where it sounds: a short break in
/// the outgoing song, where the incoming one plays alone, does not count.
/// Then, over the whole overlap with the planned gains and the overlap's
/// headroom, the two together must at every step stay under the louder of
/// the two songs alone, by `-INTRO_EXCESS_LIMIT_DB` at least (the flat power
/// sum's excess), and it is lowered further until they do. The levels are
/// flat ones of the channels' mean, each song's as its deck plays it
/// (`TransitionContext::incoming_gain_db`); heard with the ear's weighting
/// and in stereo the incoming song can sit a few dB nearer the outgoing one,
/// which the margins leave room for. An intro that would need it lowered by
/// over `INTRO_TRIM_CAP_DB` is not planned.
pub const INTRO_UNDER_OUTGOING_DB: f64 = 8.0;
pub const INTRO_UNDER_OUTGOING_SHARE: f64 = 0.95;
pub const INTRO_EXCESS_LIMIT_DB: f64 = -0.5;
pub const INTRO_TRIM_CAP_DB: f64 = 30.0;

/// How many times the trim the overlap needs is halved between the hold's
/// and `INTRO_TRIM_CAP_DB`.
const INTRO_TRIM_STEPS: usize = 14;

/// Music at full level the outgoing song would lose after a blend's end:
/// above `KEPT_MUSIC_LIMIT_MS` the start is left out, and each second below
/// it costs `KEPT_MUSIC_WEIGHT`. Full level is within
/// `KEPT_MUSIC_BELOW_BODY_DB` of the body.
pub const KEPT_MUSIC_LIMIT_MS: i64 = 1_000;
pub const KEPT_MUSIC_WEIGHT: f64 = 0.5;
pub const KEPT_MUSIC_BELOW_BODY_DB: f64 = 6.0;

/// Match tempo reaches tempos this far apart, as a share.
pub const BEAT_MATCH_LARGEST_GAP: f64 = 0.06;

/// Wherever both songs are clearly heard together, their beats drift apart
/// by at most this much; clearly heard is within `BOTH_HEARD_BELOW_BODY_DB`
/// of a song's body.
pub const DRIFT_LIMIT_MS: f64 = 40.0;
pub const BOTH_HEARD_BELOW_BODY_DB: f64 = 10.0;

// How finely the planner walks a blend to find where both songs are heard.
const HEARD_STEP_MS: f64 = 10.0;

// A song's grid point this close before its start counts as at its start.
const GRID_AT_START_MS: f64 = 1.0;

/// Songs in genres holding any of these words (in any case) get the plain
/// crossfade, never a planned one.
pub const PLAIN_CROSSFADE_GENRES: [&str; 6] =
    ["classical", "opera", "spoken", "audiobook", "podcast", "comedy"];

/// How the two songs meet.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum TransitionKind {
    /// One song straight after the other, no overlap.
    Gapless,
    /// The fixed crossfade at the end of the song.
    #[default]
    Crossfade,
    /// A short planned swap.
    Cut,
    /// A planned blend into a louder song.
    Lift,
    /// A planned blend.
    Blend,
    /// The next song's quiet intro under the end of this one: this song
    /// holds its level, then fades out around where the next one is full.
    Intro,
}

impl TransitionKind {
    pub fn name(self) -> &'static str {
        match self {
            TransitionKind::Gapless => "gapless",
            TransitionKind::Crossfade => "crossfade",
            TransitionKind::Cut => "cut",
            TransitionKind::Lift => "lift",
            TransitionKind::Blend => "blend",
            TransitionKind::Intro => "intro",
        }
    }
}

/// What the player sets for planned transitions.
#[derive(Clone, Debug, PartialEq)]
pub struct PlanSettings {
    /// The longest blend, from the crossfade slider; 0 turns crossfade off.
    pub max_overlap_ms: i64,
    /// The longest a next song's quiet intro may play under the end of
    /// this one.
    pub longest_intro_ms: i64,
    /// Off plays the fixed crossfade at the end of each song.
    pub smart: bool,
    pub filter_sweeps: bool,
    /// Nudges the incoming song's speed to the outgoing song's tempo.
    pub beat_match: bool,
    /// The shape of the fixed crossfade, with smart transitions off.
    /// Planned blends keep their own curves.
    pub fade_curve: FadeCurve,
}

impl PlanSettings {
    pub fn new(max_overlap_ms: i64) -> Self {
        PlanSettings {
            max_overlap_ms,
            longest_intro_ms: AUTOMIX_LONGEST_INTRO_MS,
            smart: true,
            filter_sweeps: true,
            beat_match: false,
            fade_curve: FadeCurve::EqualPower,
        }
    }
}

/// Where the player is when it plans, and what it knows besides the
/// scouted sections. `now_ms` is the outgoing song's position and
/// `played_ms` how much of it has actually been heard (less than `now_ms`
/// after a seek forward). `body_level_db` and `tempo_prior` come from a live
/// tap of the outgoing song. Skip-silence moves media time away from the
/// beat, and so does a pace other than 1 unless `lock_at_pace` says the
/// player changes the speed of both songs together after they are mixed:
/// either turns off bar lock and tempo matching. `pace` is also the speed
/// the beats are heard at. `incoming_gain_db` is the incoming song's deck
/// gain less the outgoing song's, in dB, as the decks apply them
/// (ReplayGain, preamp, the untagged fallback): an intro's trim compares
/// the two songs as they are heard.
#[derive(Clone, Debug, PartialEq)]
pub struct TransitionContext {
    pub now_ms: i64,
    pub played_ms: i64,
    pub repeat_one: bool,
    pub stop_at_end_of_song: bool,
    pub pace: f64,
    pub skip_silence: bool,
    pub current_genre: Option<String>,
    pub next_genre: Option<String>,
    pub body_level_db: Option<f64>,
    pub tempo_prior: Option<f64>,
    pub lock_at_pace: bool,
    pub incoming_gain_db: f64,
}

impl Default for TransitionContext {
    fn default() -> Self {
        TransitionContext {
            now_ms: 0,
            played_ms: 0,
            repeat_one: false,
            stop_at_end_of_song: false,
            pace: 1.0,
            skip_silence: false,
            current_genre: None,
            next_genre: None,
            body_level_db: None,
            tempo_prior: None,
            lock_at_pace: false,
            incoming_gain_db: 0.0,
        }
    }
}

/// How to move from one song to the next. At `start_ms` into the outgoing
/// song the incoming song starts playing from `entry_ms`, and over
/// `overlap_ms` the volumes follow [`blend_gains`]: the automix curve with
/// weight `k`, or a fixed crossfade's own `curve`, both lowered by
/// `headroom_db`. With a `filter_strength` above 0 the filter sweeps run
/// through the overlap and are bypassed once it ends. `beat_match_rate`,
/// when set, is the incoming song's playback rate through the blend.
/// `beat_ms` and `beat_anchor_ms`, when set, are the outgoing song's beat
/// length and one of its beats in song time: the blend is locked to the bar
/// and the low-pass steps on the beat. `late` marks a blend placed at the
/// end because there was no time or data to choose a better place.
/// `reason` says why this plan was chosen.
#[derive(Clone, Debug, PartialEq)]
pub struct TransitionPlan {
    pub start_ms: i64,
    pub entry_ms: i64,
    pub overlap_ms: i64,
    pub kind: TransitionKind,
    pub k: f64,
    pub filter_strength: f64,
    pub beat_match_rate: Option<f64>,
    pub beat_ms: Option<f64>,
    pub beat_anchor_ms: Option<f64>,
    pub late: bool,
    pub headroom_db: f64,
    pub reason: String,
    /// The shape of a fixed crossfade; equal power follows `k`.
    pub curve: FadeCurve,
    /// For an intro, the share of the overlap through which the outgoing
    /// song holds its level before it fades, and the share over which the
    /// incoming song rises to its own level (see [`intro_gains`]). 0 for
    /// every other kind.
    pub hold_share: f64,
    pub rise_share: f64,
    /// For an intro, the incoming song's gain through the hold, lowered from
    /// 1 so it stays under the outgoing song; it comes up to its own level
    /// as the outgoing song fades (see [`intro_in_gain`]). 1 for every other
    /// kind.
    pub intro_level: f64,
    /// The latest the blend may end, in the outgoing song's time: where its
    /// sound ends (short of its end) for a planned blend or a late one, and
    /// the song's length for the fixed crossfades and the gapless join.
    pub latest_end_ms: i64,
    /// The most whole bars of the outgoing song the plan may move later,
    /// with its shape kept, and still keep the two songs' beats together:
    /// [`LATE_MOVE_UNBOUNDED`] when that holds wherever the blend sits (never
    /// an intro, whose trim must hold where it moves to: with no bar grid it
    /// is 1 when any move to the latest end may be made), 0 when it may not
    /// move at all.
    pub late_move_bars: i32,
    /// Counted from `start_ms` in the outgoing song's time, both songs are
    /// clearly heard together from `both_heard_start_ms` to
    /// `both_heard_end_ms` (both 0 when they never are), and their beats
    /// meet at `lock_ms`.
    pub both_heard_start_ms: i64,
    pub both_heard_end_ms: i64,
    pub lock_ms: f64,
    /// How fast the beats part from the lock point, the share their tempos
    /// are apart; 0 when they cannot drift (a matched rate, equal tempos) or
    /// are not lined up.
    pub drift_gap: f64,
    /// How far apart the beats get in song time while both songs are heard.
    /// Heard at a speed `s` they are that far apart over `s`:
    /// [`TransitionPlan::drop_cut_at_ms`] says where a speed lowered to `s`
    /// puts them too far apart.
    pub drift_at_end_ms: f64,
}

impl TransitionPlan {
    pub fn bar_locked(&self) -> bool {
        self.beat_ms.is_some()
    }

    pub fn progress_at(&self, outgoing_ms: f64) -> f64 {
        if self.overlap_ms <= 0 {
            1.0
        } else {
            ((outgoing_ms - self.start_ms as f64) / self.overlap_ms as f64).clamp(0.0, 1.0)
        }
    }

    /// Both volumes at `t` through the blend: outgoing, then incoming.
    pub fn gains(&self, t: f64) -> (f64, f64) {
        let (kind, curve, k) = (self.kind, self.curve, self.k);
        let (hold, rise, level) = (self.hold_share, self.rise_share, self.intro_level);
        transition_gains(kind, curve, k, hold, rise, level, t, self.overlap_ms as f64)
    }

    /// Where, from `start_ms`, a speed lowered to `speed` while the blend is
    /// `from_ms` along first puts its beats too far apart, never inside the
    /// hold (see [`drop_cut_at_ms`]).
    pub fn drop_cut_at_ms(&self, speed: f64, from_ms: f64) -> Option<f64> {
        let heard = (self.both_heard_start_ms as f64, self.both_heard_end_ms as f64);
        let hold_end = self.hold_share * self.overlap_ms as f64;
        drop_cut_at_ms(heard, hold_end, self.lock_ms, self.drift_gap, speed, from_ms)
    }

    pub fn out_gain(&self, t: f64) -> f64 {
        self.gains(t).0
    }

    pub fn in_gain(&self, t: f64) -> f64 {
        self.gains(t).1
    }

    /// The outgoing song's beats inside the blend as places through it (0
    /// to 1). A beat within a twentieth of a beat of either end counts as
    /// the end itself and is left out.
    pub fn beat_progress(&self) -> Vec<f64> {
        let (Some(beat), Some(anchor)) = (self.beat_ms, self.beat_anchor_ms) else { return Vec::new() };
        if self.overlap_ms <= 0 || beat <= 0.0 {
            return Vec::new();
        }
        let grid = Tempo {
            bpm: 60_000.0 / beat,
            confidence: TEMPO_CONFIDENT,
            consistency: 1.0,
            steady: true,
            beat_ms: beat,
            first_beat_ms: anchor,
            downbeat_ms: anchor,
        };
        let margin = beat / 20.0;
        let (start, overlap) = (self.start_ms as f64, self.overlap_ms as f64);
        grid.beats_between(start + margin, start + overlap - margin)
            .into_iter()
            .map(|b| (b - start) / overlap)
            .collect()
    }

    /// How long the stepped low-pass takes to glide to each beat's cutoff,
    /// as a share of the blend: an eighth of a bar. `None` when the blend is
    /// not locked to the bar and the low-pass sweeps smoothly.
    pub fn low_pass_glide(&self) -> Option<f64> {
        let beat = self.beat_ms?;
        (self.overlap_ms > 0).then(|| beat * BEATS_PER_BAR as f64 / 8.0 / self.overlap_ms as f64)
    }

    /// The outgoing low-pass: stepped on the beat when the blend is locked
    /// to the bar, gliding over an eighth of a bar.
    pub fn outgoing_low_pass_at(&self, t: f64) -> f64 {
        match self.low_pass_glide() {
            None => outgoing_low_pass_hz(t, self.filter_strength),
            Some(glide) => {
                stepped_outgoing_low_pass_hz(t, self.filter_strength, &self.beat_progress(), glide)
            }
        }
    }

    pub fn outgoing_high_pass_at(&self, t: f64) -> f64 {
        outgoing_high_pass_hz(t, self.filter_strength)
    }

    pub fn incoming_high_pass_at(&self, t: f64) -> f64 {
        incoming_high_pass_hz(t, self.filter_strength)
    }

    /// The incoming song's playback rate `since_entry_ms` after it came in.
    pub fn incoming_rate_at(&self, since_entry_ms: f64) -> f64 {
        self.beat_match_rate.map_or(1.0, |r| beat_match_rate_at(r, since_entry_ms, self.overlap_ms as f64))
    }
}

/// Plans the move from the playing song to the next one. `tail` is the
/// analysis of the end of the playing song ([`super::analyze_tail`]) and
/// `head` of the start of the next one ([`super::analyze_head`]), either
/// `None` when it is not ready. Every rule of `crossfade_length` still
/// holds: when it gives no crossfade the songs play gaplessly. With smart
/// transitions off the plan is the equal-power crossfade
/// ([`TransitionPlan::fixed_crossfade`]); for a song under 35 s or in a genre
/// that should not be mixed, the fixed crossfade with the plain curve. Without the tail, or when no
/// start is left at least [`PRE_ROLL_MS`] ahead, the plan is a late one: the
/// blend at the end.
///
/// When both songs' tempos are trusted (each confident, the outgoing one
/// agreeing with the whole song's, and the beats able to stay locked at
/// this pace) every plan is locked to the outgoing song's bars, the
/// incoming song entering on its own grid, and a rate matches tempos up to
/// [`BEAT_MATCH_LARGEST_GAP`] apart. Without a rate, wherever both songs are
/// clearly heard their beats stay within [`DRIFT_LIMIT_MS`] times the pace
/// of each other: a blend starts later or becomes a short cut until they
/// do, or the songs join gaplessly. A place that would cut over
/// [`KEPT_MUSIC_LIMIT_MS`] of the outgoing song's full-level music is never
/// planned; with none left the songs join gaplessly too. With both tempos
/// confident but no lock, a blend where both songs are clearly heard cannot
/// be kept on the beat at all, so only one where they never are is planned.
/// A plan with a rate whose next song is too short to ease back to its own
/// speed is planned again without the rate.
pub fn plan_transition(
    current: &FadeSong,
    next: Option<&FadeSong>,
    tail: Option<&SectionAnalysis>,
    head: Option<&SectionAnalysis>,
    settings: &PlanSettings,
    context: &TransitionContext,
) -> TransitionPlan {
    let len_a = current.duration_ms as i64;
    let plain = crossfade_length(
        current,
        next,
        settings.max_overlap_ms.max(0) as u64,
        context.repeat_one,
        context.stop_at_end_of_song,
    ) as i64;
    let Some(next) = next.filter(|_| plain != 0) else {
        let why = gapless_reason(current, next, settings.max_overlap_ms, context);
        return gapless_plan(len_a, &why);
    };
    let len_b = next.duration_ms as i64;
    if !settings.smart {
        return TransitionPlan::fixed_crossfade(len_a, plain, "smart transitions off")
            .with_curve(settings.fade_curve);
    }
    let genres = [context.current_genre.as_deref(), context.next_genre.as_deref()];
    if let Some(genre) = genres.into_iter().flatten().find(|g| is_plain_genre(g)) {
        return crossfade_plan(len_a, plain, &format!("genre {genre}"));
    }
    if len_a < SHORTEST_AUTOMIX_SONG_MS || len_b < SHORTEST_AUTOMIX_SONG_MS {
        return crossfade_plan(len_a, plain, &format!("a song under {} s", SHORTEST_AUTOMIX_SONG_MS / 1000));
    }
    let matched = plan_pass(len_a, len_b, tail, head, settings, context, true);
    let Some(rate) = matched.beat_match_rate else { return matched };
    // The incoming song spends overlap * rate of its own time in the blend
    // and needs the settle after it to ease back to its own speed.
    if (len_b - matched.entry_ms) as f64 > matched.overlap_ms as f64 * rate + BEAT_MATCH_SETTLE_MS + 1_000.0 {
        return matched;
    }
    plan_pass(len_a, len_b, tail, head, settings, context, false)
}

// One pass of the planner, with a rate when `with_rate` allows one.
fn plan_pass(
    len_a: i64,
    len_b: i64,
    tail: Option<&SectionAnalysis>,
    head: Option<&SectionAnalysis>,
    settings: &PlanSettings,
    context: &TransitionContext,
    with_rate: bool,
) -> TransitionPlan {
    let limit = (settings.max_overlap_ms as f64).min(len_a.min(len_b) as f64 / 2.0);
    let sweeps = if settings.filter_sweeps { FILTER_STRENGTH } else { 0.0 };
    let head_features = head.map(|h| &h.features).filter(|f| f.sound_start_ms.is_some());
    let head_entry = head_features.and_then(|f| f.sound_start_ms).unwrap_or(0);
    let fixed_end = (len_a - END_MARGIN_MS) as f64;
    let now = context.now_ms as f64;

    let Some(tail) = tail else {
        let why = "no analysis of this song's end";
        return loose_plan(len_a, fixed_end, limit, now, head_entry, None, head, sweeps, why);
    };
    let measured = match context.body_level_db {
        Some(db) => tail.with_body_level(db),
        None => tail.clone(),
    };
    let a = &measured.features;
    let (Some(sound_end_a), Some(outro_a)) = (a.sound_end_ms, a.outro_start_ms) else {
        let why = "this song's end is silent";
        return loose_plan(len_a, fixed_end, limit, now, head_entry, None, head, sweeps, why);
    };

    let latest_end = sound_end_a.min(len_a - END_MARGIN_MS) as f64;
    let outro_start = (outro_a as f64).min(latest_end);
    let mut tempo_a = a.tempo.filter(Tempo::confident);
    let mut dropped = false;
    if let (Some(t), Some(prior)) = (tempo_a, context.tempo_prior.filter(|&p| p > 0.0)) {
        // The end must keep the tempo the whole song has.
        let moved = t.in_octave_of(prior);
        if (moved.bpm / prior - 1.0).abs() <= 0.04 {
            tempo_a = Some(moved);
        } else {
            tempo_a = None;
            dropped = true;
        }
    }
    let tempo_b = head_features.and_then(|f| f.tempo).filter(Tempo::confident);
    let can_lock = (context.pace == 1.0 || context.lock_at_pace) && !context.skip_silence;
    let confident = tempo_a.is_some() && tempo_b.is_some();
    let lock_a = tempo_a.filter(|_| confident && can_lock);
    let lock_b = tempo_b.filter(|_| lock_a.is_some());
    let ratio = match (lock_a, lock_b) {
        (Some(a), Some(b)) => Some(fold_tempo_ratio(a.bpm / b.bpm)),
        _ => None,
    };
    let gap = ratio.map_or(0.0, |r| (r - 1.0).abs());
    let rate = ratio
        .filter(|_| with_rate && settings.beat_match && gap > 0.0 && gap <= BEAT_MATCH_LARGEST_GAP)
        .map(|r| r.clamp(0.94, 1.06));

    // The entry of a locked plan is on the incoming song's own grid, at or
    // before its first sound.
    let sound_start_b = head_entry as f64;
    let (entry, lead_in) = match lock_b {
        Some(b) => grid_entry(&b, sound_start_b, rate.is_some()),
        None => (sound_start_b, 0.0),
    };
    let speed_b = rate.unwrap_or(1.0);
    let pass = PlanPass {
        len_a,
        limit,
        sweeps,
        a: &measured.features,
        measured: &measured,
        head,
        head_features,
        settings,
        context,
        latest_end,
        outro_start,
        dropped,
        lock_a,
        ratio,
        gap,
        rate,
        entry,
        lead: lead_in / speed_b,
        lead_hold: if lead_in > 0.0 { sound_start_b / speed_b } else { 0.0 },
        blind: confident && !can_lock,
        speed_b,
        bounded: lock_a.is_some() && rate.is_none() && gap > 0.0,
        drift_limit: DRIFT_LIMIT_MS * context.pace,
        lo: earliest_start(len_a, latest_end, &measured, context),
        phrase_anchor: lock_a
            .map(|a| a.downbeat_ms + round_half_up((outro_start - a.downbeat_ms) / a.bar_ms()) * a.bar_ms()),
    };
    pass.plan()
}

// Where a planned blend may start at the earliest: far enough into the
// song, once it counts as played at the current pace of listening, no
// further than the longest early exit before the end, inside the tail, and
// after the incoming song has had time to get ready.
fn earliest_start(len_a: i64, latest_end: f64, tail: &SectionAnalysis, context: &TransitionContext) -> f64 {
    let counts_as_played = context.now_ms + 0.max((len_a / 2).min(COUNTS_AS_PLAYED_MS) - context.played_ms);
    (EARLIEST_START_SHARE * len_a as f64)
        .max(EARLIEST_START_MS as f64)
        .max(counts_as_played as f64)
        .max(latest_end - LONGEST_EARLY_EXIT_MS as f64)
        .max(tail.envelope.start_ms as f64)
        .max((context.now_ms + PRE_ROLL_MS) as f64)
}

// The incoming song's entry on its own grid: its last downbeat (with a
// rate) or beat at or before its first sound, so none of its sound is cut.
// When that falls before the song's start the song enters at 0, and the
// second value is how far into the song its first grid point lies: the
// outgoing song's start moves that much earlier, so that point lands on
// the outgoing song's line.
fn grid_entry(b: &Tempo, sound_start_ms: f64, downbeats: bool) -> (f64, f64) {
    let (step, anchor) = if downbeats { (b.bar_ms(), b.downbeat_ms) } else { (b.beat_ms, b.first_beat_ms) };
    let last = anchor + ((sound_start_ms - anchor) / step + 1e-9).floor() * step;
    if last >= -GRID_AT_START_MS { (last.max(0.0), 0.0) } else { (0.0, last + step) }
}

// A place a blend or intro may go: from `start_ms` over `overlap_ms` in the
// outgoing song's time, its beats meeting the incoming song's at `lock_ms`
// (the start, or later by the incoming song's lead-in). An intro holds and
// rises over its shares, the incoming song at `level` through the hold; any
// other shape holds until `hold_share`, then follows curve weight `k`, or
// when that is `None` the curve its own length takes.
#[derive(Clone, Copy, Debug)]
struct Shape {
    start_ms: f64,
    lock_ms: f64,
    overlap_ms: f64,
    on_bar: bool,
    intro: bool,
    hold_share: f64,
    rise_share: f64,
    k: Option<f64>,
    level: f64,
}

impl Shape {
    fn end_ms(&self) -> f64 {
        self.start_ms + self.overlap_ms
    }

    fn shifted(&self, ms: f64) -> Shape {
        Shape { start_ms: self.start_ms + ms, lock_ms: self.lock_ms + ms, ..*self }
    }
}

struct PlanPass<'a> {
    len_a: i64,
    limit: f64,
    sweeps: f64,
    measured: &'a SectionAnalysis,
    a: &'a SectionFeatures,
    head: Option<&'a SectionAnalysis>,
    head_features: Option<&'a SectionFeatures>,
    settings: &'a PlanSettings,
    context: &'a TransitionContext,
    latest_end: f64,
    outro_start: f64,
    // The outgoing song's tempo was not the whole song's.
    dropped: bool,
    // The outgoing song's grid when the plan is locked to it.
    lock_a: Option<Tempo>,
    ratio: Option<f64>,
    gap: f64,
    rate: Option<f64>,
    entry: f64,
    // From a locked shape's start to where the two grids meet, in the
    // outgoing song's time.
    lead: f64,
    // With a lead, from a shape's start to the incoming song's first sound,
    // in the outgoing song's time: the blend holds until then.
    lead_hold: f64,
    // Both tempos confident with no lock: only a shape where both songs are
    // never clearly heard together is planned.
    blind: bool,
    // How fast the incoming song plays through the blend.
    speed_b: f64,
    // The beats drift apart through the blend and must be kept within the
    // limit wherever both songs are clearly heard.
    bounded: bool,
    drift_limit: f64,
    lo: f64,
    phrase_anchor: Option<f64>,
}

impl PlanPass<'_> {
    // A blend from `start_ms` over `overlap_ms`, its beats meeting at
    // `lock_ms`, holding through the incoming song's silent lead-in.
    fn blend(&self, start_ms: f64, lock_ms: f64, overlap_ms: f64, on_bar: bool) -> Shape {
        Shape {
            start_ms,
            lock_ms,
            overlap_ms,
            on_bar,
            intro: false,
            hold_share: if self.lead_hold > 0.0 { (self.lead_hold / overlap_ms).min(1.0) } else { 0.0 },
            rise_share: 0.0,
            k: None,
            level: 1.0,
        }
    }

    // The share of a blend `overlap_ms` long held through the incoming
    // song's silent lead-in.
    fn lead_hold_share(&self, overlap_ms: i64) -> f64 {
        if self.lead_hold > 0.0 { (self.lead_hold / overlap_ms as f64).min(1.0) } else { 0.0 }
    }

    fn plan(&self) -> TransitionPlan {
        let mut bars = 0;
        let mut overlap = match (self.lock_a, self.ratio) {
            (Some(a), Some(_)) => {
                bars = if self.gap > 0.08 { 2 } else { 4 };
                bars as f64 * a.bar_ms()
            }
            _ => self.latest_end - self.outro_start,
        };
        // The next song's quiet start, in the outgoing song's time.
        let quiet = self
            .head_features
            .and_then(|f| f.intro_end_ms)
            .map_or(0.0, |end| (end as f64 - self.entry).max(0.0) / self.speed_b);
        if quiet > self.fitted(overlap, bars)
            && quiet >= INTRO_SHORTEST_MS as f64
            && !self.dropped
            && let Some(intro) = self.plan_intro(quiet)
        {
            return intro;
        }
        // Without an intro the blend lasts at least until the next song is
        // near its full level.
        let lengthened = quiet > overlap;
        if lengthened {
            match self.lock_a.filter(|_| bars > 0) {
                Some(a) => {
                    bars = (quiet / a.bar_ms() - 1e-9).ceil() as i32;
                    overlap = bars as f64 * a.bar_ms();
                }
                None => overlap = quiet,
            }
        }
        overlap = self.fitted(overlap, bars);

        let hi = self.latest_end - overlap;
        if hi < self.lo {
            let why = format!("no start left before {}", seconds(hi as i64));
            return self.late_plan(self.context.now_ms as f64, overlap, &why);
        }
        // Bar lines when the blend is locked to the bar, otherwise a fixed
        // step back from the latest start.
        let shapes: Vec<Shape> = match self.lock_a {
            Some(a) => a
                .bars_between(self.lo + self.lead, hi + self.lead)
                .into_iter()
                .map(|l| self.blend(l - self.lead, l, overlap, true))
                .collect(),
            None => steps(self.lo, hi).into_iter().map(|s| self.blend(s, s, overlap, false)).collect(),
        };
        let Some(&last) = shapes.last() else {
            let why = format!("no bar line left before {}", seconds(hi as i64));
            return self.late_plan(self.lo, overlap, &why);
        };
        let mut all = shapes.clone();
        if self.lock_a.is_some()
            && !shapes.iter().any(|s| self.kept(s) <= KEPT_MUSIC_LIMIT_MS as f64)
            && let Some(stretched) = self.stretched(&last)
        {
            all.push(stretched);
        }
        let fading: Vec<Shape> = all.into_iter().filter(|s| self.fades(s)).collect();
        if fading.is_empty() {
            return gapless_plan(self.len_a, "the next song's silent start leaves no time to fade");
        }
        let allowed: Vec<Shape> = fading.iter().filter_map(|s| self.allow(s, self.lo)).collect();
        if allowed.is_empty() {
            return gapless_plan(self.len_a, self.unheard_reason());
        }
        let Some(best) = self.best(&allowed, |s| self.blend_score(s)) else {
            return gapless_plan(self.len_a, "every blend left would cut the song short");
        };
        self.blend_plan(&best, lengthened)
    }

    // A blend's overlap kept between SHORTEST_BLEND_MS and the limit, in
    // whole bars when it was planned in `bars` of them and whole bars fit.
    fn fitted(&self, overlap: f64, bars: i32) -> f64 {
        let mut length = overlap.max(SHORTEST_BLEND_MS as f64).min(self.limit);
        if let Some(a) = self.lock_a
            && bars > 0
            && length < bars as f64 * a.bar_ms()
        {
            // Cut back to whole bars that fit, when that is still long enough.
            let fit = (self.limit / a.bar_ms() + 1e-9).floor() as i32;
            let whole = fit as f64 * a.bar_ms();
            if fit >= 1 && whole >= SHORTEST_BLEND_MS as f64 {
                length = whole;
            }
        }
        (length + 0.5).floor()
    }

    // Full-level music the outgoing song loses after the shape ends.
    fn kept(&self, s: &Shape) -> f64 {
        self.measured.ms_at_least(s.end_ms(), self.latest_end, self.a.body_db - KEPT_MUSIC_BELOW_BODY_DB)
    }

    // Whether a blend fades the outgoing song out over at least
    // SHORTEST_FADE_MS after any hold through the incoming song's silent
    // lead-in, rather than holding it to the end and dropping it there.
    fn fades(&self, s: &Shape) -> bool {
        s.intro || (1.0 - s.hold_share) * s.overlap_ms >= SHORTEST_FADE_MS as f64 - 1e-6
    }

    // The last bar-line blend stretched to end exactly at the latest end,
    // from its bar line or, over the limit, from the next one.
    fn stretched(&self, last: &Shape) -> Option<Shape> {
        let bar = self.lock_a?.bar_ms();
        let mut shape = self.blend(last.start_ms, last.lock_ms, self.latest_end - last.start_ms, true);
        if shape.overlap_ms > self.limit {
            let lock = last.lock_ms + bar;
            shape = self.blend(lock - self.lead, lock, self.latest_end - (lock - self.lead), true);
        }
        (shape.overlap_ms >= SHORTEST_BLEND_MS as f64).then_some(shape)
    }

    // A blend candidate as it may be planned: capped to the drift bound,
    // only with both songs never heard together when the beats cannot be
    // lined up, or as it is.
    fn allow(&self, s: &Shape, earliest: f64) -> Option<Shape> {
        if self.bounded {
            self.cap(s, earliest)
        } else if self.blind {
            self.stretch(s).is_none().then_some(*s)
        } else {
            Some(*s)
        }
    }

    // The candidate's end kept and its start moved later by whole bars
    // until the beats stay within the bound; once that would leave under
    // SHORTEST_BLEND_MS, a cut from the earliest beat of A that keeps them
    // within it. Every one fades A out (`fades`). `None` when no cut does.
    fn cap(&self, s: &Shape, earliest: f64) -> Option<Shape> {
        let Some(a) = self.lock_a else { return Some(*s) };
        let bar = a.bar_ms();
        let mut shape = *s;
        loop {
            if self.meets(&shape) && self.fades(&shape) {
                return Some(shape);
            }
            let lock = shape.lock_ms + bar;
            let start = lock - self.lead;
            if shape.end_ms() - start < SHORTEST_BLEND_MS as f64 {
                break;
            }
            shape = self.blend(start, lock, s.end_ms() - start, shape.on_bar);
        }
        self.cut(s.end_ms(), earliest)
    }

    // A cut ending at `end`, starting on a beat of A no earlier than
    // `earliest`, from SHORTEST_BLEND_MS down to SHORTEST_FADE_MS long:
    // the longest whose beats stay within the bound.
    fn cut(&self, end: f64, earliest: f64) -> Option<Shape> {
        let grid = self.lock_a?;
        let from = earliest.max(end - SHORTEST_BLEND_MS as f64);
        let to = end - SHORTEST_FADE_MS as f64;
        grid.beats_between(from + self.lead, to + self.lead)
            .into_iter()
            .map(|beat| self.blend(beat - self.lead, beat, end - (beat - self.lead), self.on_bar_line(beat)))
            .find(|shape| self.meets(shape) && self.fades(shape))
    }

    fn on_bar_line(&self, ms: f64) -> bool {
        let Some(grid) = self.lock_a else { return false };
        let bars = (ms - grid.downbeat_ms) / grid.bar_ms();
        (bars - round_half_up(bars)).abs() < 1e-6
    }

    fn meets(&self, s: &Shape) -> bool {
        if self.bounded {
            self.offset(s) <= self.drift_limit
        } else if self.blind {
            self.stretch(s).is_none()
        } else {
            true
        }
    }

    // How far apart the beats are, in song time, where they are furthest
    // apart while both songs are clearly heard: they meet at the lock point
    // and drift apart by the tempo gap either side of it.
    fn offset(&self, s: &Shape) -> f64 {
        let Some((first, last)) = self.stretch(s) else { return 0.0 };
        self.gap * 0.0f64.max((last - s.lock_ms).max(s.lock_ms - first))
    }

    // The first and last moments, in the outgoing song's time, where both
    // songs are within BOTH_HEARD_BELOW_BODY_DB of their bodies through the
    // shape, with its gains and the overlap's headroom; `None` when they
    // never are.
    fn stretch(&self, s: &Shape) -> Option<(f64, f64)> {
        let b = self.head?;
        if self.head_features.is_none() || s.overlap_ms <= 0.0 {
            return None;
        }
        let k = s.k.unwrap_or_else(|| {
            curve_of(kind_of(s.overlap_ms, Some(self.measured), self.head, s.start_ms, self.entry))
        });
        let room = 10f64.powf(-OVERLAP_HEADROOM_DB / 20.0);
        let a_floor = self.a.body_db - BOTH_HEARD_BELOW_BODY_DB;
        let b_floor = b.features.body_db - BOTH_HEARD_BELOW_BODY_DB;
        let mut heard: Option<(f64, f64)> = None;
        let count = (s.overlap_ms / HEARD_STEP_MS).floor() as i64;
        for i in 0..=count {
            let since = i as f64 * HEARD_STEP_MS;
            let t = since / s.overlap_ms;
            let (out, into) = if s.intro {
                (intro_out_gain(t, s.hold_share), intro_in_gain(t, s.rise_share, s.hold_share, s.level))
            } else {
                let rest = past_hold(t, s.hold_share);
                (automix_out_gain(rest, k), automix_in_gain(rest, k))
            };
            if out <= 0.0 || into <= 0.0 {
                continue;
            }
            let at = s.start_ms + since;
            if self.measured.level_at(at) + 20.0 * (out * room).log10() < a_floor {
                continue;
            }
            if b.level_at(self.entry + since * self.speed_b) + 20.0 * (into * room).log10() < b_floor {
                continue;
            }
            heard = Some((heard.map_or(at, |(first, _)| first), at));
        }
        heard
    }

    // The level an intro's incoming song plays at through the hold: the
    // hold's own (`hold_level`), lowered further while the mix's excess over
    // the louder song alone passes INTRO_EXCESS_LIMIT_DB somewhere in the
    // overlap (`excess`), to the highest level that keeps it within that,
    // found by halving the trim between the hold's and INTRO_TRIM_CAP_DB
    // INTRO_TRIM_STEPS times. `None` when that takes over INTRO_TRIM_CAP_DB.
    fn under_outgoing(&self, s: &Shape) -> Option<f64> {
        let held = self.hold_level(s)?;
        if self.excess(s, held) <= INTRO_EXCESS_LIMIT_DB {
            return Some(held);
        }
        let mut lo = -20.0 * held.log10();
        let mut hi = INTRO_TRIM_CAP_DB;
        if self.excess(s, 10f64.powf(-hi / 20.0)) > INTRO_EXCESS_LIMIT_DB {
            return None;
        }
        for _ in 0..INTRO_TRIM_STEPS {
            let mid = (lo + hi) / 2.0;
            if self.excess(s, 10f64.powf(-mid / 20.0)) > INTRO_EXCESS_LIMIT_DB {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        Some(10f64.powf(-hi / 20.0))
    }

    // How far an intro's mix stands over the louder of its two songs alone,
    // the incoming one at `level` through the hold: at each step of the
    // overlap where either song sounds above its silence gate, the flat
    // power sum of both with their planned gains and the overlap's headroom,
    // less the louder song's own level there, each as its deck plays it; the
    // loudest step. Negative infinity when no step counts.
    fn excess(&self, s: &Shape, level: f64) -> f64 {
        let Some(b) = self.head else { return f64::NEG_INFINITY };
        let room = 10f64.powf(-OVERLAP_HEADROOM_DB / 20.0);
        let mut most = f64::NEG_INFINITY;
        let count = (s.overlap_ms / HEARD_STEP_MS).floor() as i64;
        for i in 0..=count {
            let since = i as f64 * HEARD_STEP_MS;
            let t = since / s.overlap_ms;
            let outgoing = self.measured.level_at(s.start_ms + since);
            let own = b.level_at(self.entry + since * self.speed_b);
            if outgoing <= self.a.gate_db && own <= b.features.gate_db {
                continue;
            }
            let incoming = own + self.context.incoming_gain_db;
            let out = intro_out_gain(t, s.hold_share) * room;
            let into = intro_in_gain(t, s.rise_share, s.hold_share, level) * room;
            let sum = 10f64.powf(outgoing / 10.0) * out * out + 10f64.powf(incoming / 10.0) * into * into;
            most = most.max(10.0 * sum.max(1e-30).log10() - outgoing.max(incoming));
        }
        most
    }

    // The level that keeps an intro's incoming song INTRO_UNDER_OUTGOING_DB
    // under the outgoing song through the hold, walked as the both-heard
    // stretch is: at each step where the incoming song, at its own level
    // with its rise, sounds above its silence gate, its 400 ms level as its
    // deck plays it less the outgoing song's at the same moment; the
    // INTRO_UNDER_OUTGOING_SHARE percentile of those (the lower of two
    // neighbors) sets how far it is lowered, 1 when it is already that far
    // under or never sounds there. `None` when that takes over
    // INTRO_TRIM_CAP_DB.
    fn hold_level(&self, s: &Shape) -> Option<f64> {
        let Some(b) = self.head else { return Some(1.0) };
        let hold = s.hold_share * s.overlap_ms;
        let gate = b.features.gate_db;
        let count = (hold / HEARD_STEP_MS).floor() as i64;
        let mut apart: Vec<f64> = Vec::new();
        for i in 0..count {
            let since = i as f64 * HEARD_STEP_MS;
            let into = intro_in_gain(since / s.overlap_ms, s.rise_share, s.hold_share, 1.0);
            if into <= 0.0 {
                continue;
            }
            let level = b.level_at(self.entry + since * self.speed_b) + 20.0 * into.log10();
            if level <= gate {
                continue;
            }
            apart.push(level + self.context.incoming_gain_db - self.measured.level_at(s.start_ms + since));
        }
        if apart.is_empty() {
            return Some(1.0);
        }
        apart.sort_by(f64::total_cmp);
        let trim = apart[(INTRO_UNDER_OUTGOING_SHARE * (apart.len() - 1) as f64).floor() as usize]
            + INTRO_UNDER_OUTGOING_DB;
        if trim <= 0.0 {
            return Some(1.0);
        }
        (trim <= INTRO_TRIM_CAP_DB).then(|| 10f64.powf(-trim / 20.0))
    }

    // The best scoring shape; the later one wins a tie. Shapes that cut over
    // KEPT_MUSIC_LIMIT_MS of the outgoing song's full-level music are left
    // out; `None` when no other is left.
    fn best(&self, shapes: &[Shape], score: impl Fn(&Shape) -> f64) -> Option<Shape> {
        let pool: Vec<Shape> =
            shapes.iter().filter(|s| self.kept(s) <= KEPT_MUSIC_LIMIT_MS as f64).copied().collect();
        let mut best = *pool.last()?;
        let mut best_score = f64::NEG_INFINITY;
        for s in &pool {
            let value = score(s);
            if value >= best_score {
                best_score = value;
                best = *s;
            }
        }
        Some(best)
    }

    // The terms every shape shares: ending early, music cut, a section
    // boundary where the beats meet, and the bar and phrase it starts on.
    fn shared_score(&self, s: &Shape) -> f64 {
        let mut score = -0.08 * (self.latest_end - s.end_ms()) / 1000.0;
        if self.a.boundaries_ms.iter().any(|&b| (b as f64 - s.lock_ms).abs() <= 250.0) {
            score += 1.0;
        }
        if let (Some(grid), Some(anchor), true) = (self.lock_a, self.phrase_anchor, s.on_bar) {
            score += 0.6;
            let bars_back = round_half_up((anchor - s.lock_ms) / grid.bar_ms()) as i64;
            if bars_back % 8 == 0 {
                score += 0.8;
            }
        }
        score - KEPT_MUSIC_WEIGHT * self.kept(s) / 1000.0
    }

    fn blend_score(&self, s: &Shape) -> f64 {
        let mut score = self.shared_score(s);
        if self.outro_start < self.latest_end - 1000.0 {
            score -= 0.15 * (s.lock_ms - self.outro_start).abs() / 1000.0;
        }
        score - 1.5 * self.measured.share_at_least(s.start_ms, s.end_ms(), self.a.body_db - 3.0)
    }

    fn blend_plan(&self, s: &Shape, lengthened: bool) -> TransitionPlan {
        let kind = kind_of(s.overlap_ms, Some(self.measured), self.head, s.start_ms, self.entry);
        let start_ms = round_half_up(s.start_ms) as i64;
        let overlap_ms = round_half_up(s.end_ms()) as i64 - start_ms;
        let entry_ms = round_half_up(self.entry) as i64;
        let mut reason = format!("{} at {} over {}", kind.name(), seconds(start_ms), seconds(overlap_ms));
        reason += &self.bars_of(overlap_ms);
        reason += &format!(", next song from {}", seconds(entry_ms));
        if lengthened && let Some(end) = self.head_features.and_then(|f| f.intro_end_ms) {
            reason += &format!(" (full from {})", seconds(end));
        }
        reason += &format!(", outro at {}", seconds(self.outro_start as i64));
        reason += &format!(", latest end {}", seconds(self.latest_end as i64));
        if self.head.is_none() {
            reason += ", no analysis of the next song's start";
        }
        if let Some(r) = self.rate {
            reason += &format!(", rate {}", decimals(r, 3));
        }
        let plan = TransitionPlan {
            start_ms,
            entry_ms,
            overlap_ms,
            kind,
            k: curve_of(kind),
            filter_strength: self.sweeps,
            beat_match_rate: self.rate,
            beat_ms: self.lock_a.map(|a| a.beat_ms),
            beat_anchor_ms: self.lock_a.map(|a| a.first_beat_ms),
            late: false,
            headroom_db: OVERLAP_HEADROOM_DB,
            reason,
            curve: FadeCurve::EqualPower,
            hold_share: self.lead_hold_share(overlap_ms),
            rise_share: 0.0,
            intro_level: 1.0,
            latest_end_ms: round_half_up(self.latest_end) as i64,
            late_move_bars: 0,
            both_heard_start_ms: 0,
            both_heard_end_ms: 0,
            lock_ms: 0.0,
            drift_gap: 0.0,
            drift_at_end_ms: 0.0,
        };
        self.finish(plan, s.lock_ms)
    }

    // " (N bars of X BPM)" when the overlap is whole bars of a locked plan.
    fn bars_of(&self, overlap_ms: i64) -> String {
        let Some(grid) = self.lock_a else { return String::new() };
        let bars = round_half_up(overlap_ms as f64 / grid.bar_ms());
        if bars >= 1.0 && (overlap_ms as f64 - bars * grid.bar_ms()).abs() <= 1.0 {
            format!(" ({} bars of {} BPM)", bars as i64, decimals(grid.display_bpm(), 1))
        } else {
            String::new()
        }
    }

    // The next song's quiet start played under the end of this one: the
    // outgoing song holds, then fades over two bars (or INTRO_FADE_MS
    // unlocked, at most half the quiet start) centered on where the next
    // song is full, a bar line from where the beats meet when locked; the
    // next song rises over a bar (INTRO_RISE_MS unlocked; a quarter of the
    // overlap or the hold when shorter), lowered through
    // the hold as far as each start needs to stay under the outgoing song
    // (`under_outgoing`). `None` when no start is left, the beats or levels
    // allow none or every one left would cut the outgoing song short, and
    // the lengthened blend is planned instead.
    fn plan_intro(&self, quiet: f64) -> Option<TransitionPlan> {
        let bar = self.lock_a.map(|a| a.bar_ms());
        let rise = bar.unwrap_or(INTRO_RISE_MS as f64);
        let fade = bar.map_or(INTRO_FADE_MS as f64, |b| INTRO_FADE_BARS as f64 * b).min(quiet / 2.0);
        let longest = self.settings.longest_intro_ms as f64;
        let lead = self.lead;
        let full_at = match bar {
            Some(b) => {
                let bars = ((quiet - lead) / b - 1e-9)
                    .ceil()
                    .min(((longest - fade / 2.0 - lead) / b + 1e-9).floor());
                lead + bars * b
            }
            None => quiet.min(longest - fade / 2.0),
        };
        let hold = full_at - fade / 2.0;
        if hold <= 0.0 {
            return None;
        }
        let capped = full_at < quiet - 1e-6;
        let overlap = (full_at + fade / 2.0 + 0.5).floor();
        let intro = |start: f64, lock: f64, length: f64| Shape {
            start_ms: start,
            lock_ms: lock,
            overlap_ms: length,
            on_bar: bar.is_some(),
            intro: true,
            hold_share: hold / length,
            rise_share: rise.min(length / 4.0).min(hold) / length,
            k: None,
            level: 1.0,
        };
        let hi = self.latest_end - overlap;
        if hi < self.lo {
            return None;
        }
        let shapes: Vec<Shape> = match self.lock_a {
            Some(a) => a
                .bars_between(self.lo + self.lead, hi + self.lead)
                .into_iter()
                .map(|l| intro(l - self.lead, l, overlap))
                .collect(),
            None => steps(self.lo, hi).into_iter().map(|s| intro(s, s, overlap)).collect(),
        };
        let &last = shapes.last()?;
        let mut all = shapes.clone();
        if let Some(bar) = bar
            && !shapes.iter().any(|s| self.kept(s) <= KEPT_MUSIC_LIMIT_MS as f64)
        {
            // Only the outgoing song's fade end moves, to the latest end.
            let length = self.latest_end - last.start_ms;
            if length <= longest {
                all.push(intro(last.start_ms, last.lock_ms, length));
            } else {
                // Over the longest intro: one bar later, keeping where the
                // next song is full from the start, when the fade left is
                // still at least half the planned one.
                let lock = last.lock_ms + bar;
                let start = lock - self.lead;
                if self.latest_end - start - hold >= fade / 2.0 {
                    all.push(intro(start, lock, self.latest_end - start));
                }
            }
        }
        let allowed: Vec<Shape> = all
            .into_iter()
            .filter_map(|s| self.under_outgoing(&s).map(|level| Shape { level, ..s }))
            .filter(|s| self.meets(s))
            .collect();
        if allowed.is_empty() {
            return None;
        }
        let best = self.best(&allowed, |s| {
            let mut score = self.shared_score(s);
            if self.outro_start < self.latest_end - 1000.0 {
                score -= 0.15 * (s.start_ms + hold - self.outro_start).abs() / 1000.0;
            }
            score
        })?;
        let start_ms = round_half_up(best.start_ms) as i64;
        let overlap_ms = round_half_up(best.end_ms()) as i64 - start_ms;
        let entry_ms = round_half_up(self.entry) as i64;
        let full_from = self.head_features.and_then(|f| f.intro_end_ms).unwrap_or(entry_ms);
        let mut reason = format!("intro at {} over {}", seconds(start_ms), seconds(overlap_ms));
        reason += &self.bars_of(overlap_ms);
        reason += &format!(", next song from {} (full from {}", seconds(entry_ms), seconds(full_from));
        if capped {
            reason += ", capped";
        }
        reason += &format!("), outro at {}", seconds(self.outro_start as i64));
        reason += &format!(", latest end {}", seconds(self.latest_end as i64));
        if best.level < 1.0 {
            reason += &format!(", next song lowered {} dB", decimals(-20.0 * best.level.log10(), 1));
        }
        if let Some(r) = self.rate {
            reason += &format!(", rate {}", decimals(r, 3));
        }
        let plan = TransitionPlan {
            start_ms,
            entry_ms,
            overlap_ms,
            kind: TransitionKind::Intro,
            k: curve_of(TransitionKind::Intro),
            filter_strength: 0.0,
            beat_match_rate: self.rate,
            beat_ms: self.lock_a.map(|a| a.beat_ms),
            beat_anchor_ms: self.lock_a.map(|a| a.first_beat_ms),
            late: false,
            headroom_db: OVERLAP_HEADROOM_DB,
            reason,
            curve: FadeCurve::EqualPower,
            hold_share: hold / overlap_ms as f64,
            rise_share: rise.min(overlap_ms as f64 / 4.0).min(hold) / overlap_ms as f64,
            intro_level: best.level,
            latest_end_ms: round_half_up(self.latest_end) as i64,
            late_move_bars: 0,
            both_heard_start_ms: 0,
            both_heard_end_ms: 0,
            lock_ms: 0.0,
            drift_gap: 0.0,
            drift_at_end_ms: 0.0,
        };
        Some(self.finish(plan, best.lock_ms))
    }

    // The blend at the end, ending at the latest end, when no planned start
    // is left after `earliest`. Locked: from A's first bar line at or after
    // the later of `earliest` and a full `overlap` before the end, capped
    // to the drift bound like a planned blend. Otherwise as it always was,
    // and with the beats unable to line up only when both songs are never
    // heard together.
    fn late_plan(&self, earliest: f64, overlap: f64, why: &str) -> TransitionPlan {
        let Some(grid) = self.lock_a else {
            let entry_ms = round_half_up(self.entry) as i64;
            let plan = loose_plan(
                self.len_a,
                self.latest_end,
                overlap,
                earliest,
                entry_ms,
                Some(self.measured),
                self.head,
                self.sweeps,
                why,
            );
            if plan.kind == TransitionKind::Gapless {
                return plan;
            }
            if self.blind && self.stretch(&shape_of(&plan, plan.start_ms as f64)).is_some() {
                return gapless_plan(self.len_a, self.unheard_reason());
            }
            let lock = plan.start_ms as f64;
            return self.finish(plan, lock);
        };
        let from = earliest.max(self.latest_end - overlap);
        let lock = grid.downbeat_from(from + self.lead);
        let start = lock - self.lead;
        let length = self.latest_end - start;
        let shape = if !self.bounded {
            Some(self.blend(start, lock, length, true)).filter(|s| self.fades(s))
        } else if length >= SHORTEST_BLEND_MS as f64 {
            self.cap(&self.blend(start, lock, length, true), from)
        } else {
            self.cut(self.latest_end, from)
        };
        let Some(shape) = shape else {
            return gapless_plan(self.len_a, &format!("too late to blend: {why}"));
        };
        let kind = kind_of(shape.overlap_ms, Some(self.measured), self.head, shape.start_ms, self.entry);
        let start_ms = round_half_up(shape.start_ms) as i64;
        let overlap_ms = round_half_up(shape.end_ms()) as i64 - start_ms;
        let entry_ms = round_half_up(self.entry) as i64;
        let matched = self.rate.map_or(String::new(), |r| format!(", rate {}", decimals(r, 3)));
        let plan = TransitionPlan {
            start_ms,
            entry_ms,
            overlap_ms,
            kind,
            k: curve_of(kind),
            filter_strength: self.sweeps,
            beat_match_rate: self.rate,
            beat_ms: Some(grid.beat_ms),
            beat_anchor_ms: Some(grid.first_beat_ms),
            late: true,
            headroom_db: OVERLAP_HEADROOM_DB,
            reason: format!(
                "late {} at {} over {}, next song from {}{matched}: {why}",
                kind.name(),
                seconds(start_ms),
                seconds(overlap_ms),
                seconds(entry_ms)
            ),
            curve: FadeCurve::EqualPower,
            hold_share: self.lead_hold_share(overlap_ms),
            rise_share: 0.0,
            intro_level: 1.0,
            latest_end_ms: round_half_up(self.latest_end) as i64,
            late_move_bars: 0,
            both_heard_start_ms: 0,
            both_heard_end_ms: 0,
            lock_ms: 0.0,
            drift_gap: 0.0,
            drift_at_end_ms: 0.0,
        };
        self.finish(plan, shape.lock_ms)
    }

    fn unheard_reason(&self) -> &'static str {
        if self.bounded { "the beats would drift apart" } else { "the beats cannot be lined up" }
    }

    // The plan with how far a late start may move it, where both songs are
    // heard together, where its beats meet and how far they part.
    fn finish(&self, plan: TransitionPlan, lock: f64) -> TransitionPlan {
        let shape = shape_of(&plan, lock);
        let heard = self.stretch(&shape);
        let start = plan.start_ms as f64;
        TransitionPlan {
            late_move_bars: self.late_move_bars(&shape, &plan),
            both_heard_start_ms: heard.map_or(0, |(first, _)| round_half_up(first - start) as i64),
            both_heard_end_ms: heard.map_or(0, |(_, last)| round_half_up(last - start) as i64),
            lock_ms: lock - start,
            drift_gap: if self.bounded { self.gap } else { 0.0 },
            drift_at_end_ms: if self.bounded { self.offset(&shape) } else { 0.0 },
            ..plan
        }
    }

    // The most whole bars the plan may move later and still end by the
    // latest end with its beats within the bound there; unbounded when the
    // bound does not depend on where it sits. None for a plan whose beats
    // could not be lined up, kept only where both songs are never heard
    // together, which a move could change. An intro is never unbounded: a
    // move must also leave its incoming song needing no deeper trim than
    // planned (`trim_holds`). With a bar grid it counts those bars; with
    // none, where the player moves it any amount up to the latest end, it is
    // 1 when there is room and every START_STEP_MS step there (and the
    // latest end itself) keeps that, else 0.
    fn late_move_bars(&self, shape: &Shape, plan: &TransitionPlan) -> i32 {
        if self.blind {
            return 0;
        }
        let bar = self.lock_a.map(|a| a.bar_ms());
        let end = (plan.start_ms + plan.overlap_ms) as f64;
        let latest = plan.latest_end_ms as f64;
        if shape.intro {
            let keeps = |ms: f64| {
                let moved = shape.shifted(ms);
                self.meets(&moved) && self.trim_holds(&moved)
            };
            let Some(bar) = bar else {
                let room = latest - end;
                if room <= 1e-6 {
                    return 0;
                }
                let mut ms = START_STEP_MS as f64;
                while ms < room - 1e-6 {
                    if !keeps(ms) {
                        return 0;
                    }
                    ms += START_STEP_MS as f64;
                }
                return i32::from(keeps(room));
            };
            let mut bars = 0;
            while end + (bars + 1) as f64 * bar <= latest + 1e-6 && keeps((bars + 1) as f64 * bar) {
                bars += 1;
            }
            return bars;
        }
        let Some(bar) = bar.filter(|_| self.bounded) else {
            return LATE_MOVE_UNBOUNDED;
        };
        let mut bars = 0;
        while end + (bars + 1) as f64 * bar <= latest + 1e-6
            && self.meets(&shape.shifted((bars + 1) as f64 * bar))
        {
            bars += 1;
        }
        bars
    }

    // Whether an intro's shape, at the level it was planned with, needs no
    // deeper trim where it sits: the hold's level is no lower, and the mix's
    // excess over the louder song alone is within INTRO_EXCESS_LIMIT_DB.
    fn trim_holds(&self, s: &Shape) -> bool {
        self.hold_level(s).is_some_and(|held| held >= s.level - 1e-9)
            && self.excess(s, s.level) <= INTRO_EXCESS_LIMIT_DB
    }
}

// A plan's shape, its beats meeting at `lock`, with its own curve weight.
fn shape_of(plan: &TransitionPlan, lock: f64) -> Shape {
    Shape {
        start_ms: plan.start_ms as f64,
        lock_ms: lock,
        overlap_ms: plan.overlap_ms as f64,
        on_bar: false,
        intro: plan.kind == TransitionKind::Intro,
        hold_share: plan.hold_share,
        rise_share: plan.rise_share,
        k: Some(plan.k),
        level: plan.intro_level,
    }
}

fn steps(from: f64, to: f64) -> Vec<f64> {
    let mut steps = Vec::new();
    let mut at = to;
    while at >= from - 1e-6 {
        steps.push(at);
        at -= START_STEP_MS as f64;
    }
    steps.reverse();
    steps
}

/// The outgoing and incoming volumes at `t` (0 to 1) through a blend of
/// `kind` lasting `overlap_ms`: an intro's hold and rise over its shares,
/// the incoming song at `intro_level` through the hold ([`intro_gains`]);
/// any other kind held until `hold_share` (the outgoing song at 1, the
/// incoming one at 0), then the curve with weight `k` or the fixed
/// crossfade's `curve` ([`blend_gains`]) over the rest.
#[allow(clippy::too_many_arguments)]
pub fn transition_gains(
    kind: TransitionKind,
    curve: FadeCurve,
    k: f64,
    hold_share: f64,
    rise_share: f64,
    intro_level: f64,
    t: f64,
    overlap_ms: f64,
) -> (f64, f64) {
    match kind {
        TransitionKind::Intro => intro_gains(t, hold_share, rise_share, intro_level),
        _ => blend_gains(curve, k, past_hold(t, hold_share), overlap_ms * (1.0 - hold_share)),
    }
}

/// Where, counted from a blend's start, a speed lowered to `speed` while
/// the blend is `from_ms` along first puts its two songs' beats heard over
/// [`DRIFT_LIMIT_MS`] apart while both are still clearly heard: the first
/// moment at or after `from_ms` in the both-heard stretch `heard` where
/// `gap * |t - lock_ms|` passes `DRIFT_LIMIT_MS * speed` (`from_ms` itself
/// when they are already that far apart there, else the moment after the
/// lock point where the offset reaches the limit, when the stretch goes on
/// past it). The outgoing song keeps its own level through a hold (an
/// intro's, or the incoming song's silent lead-in), so the moment is never
/// before `hold_end_ms`. `None` when no such moment comes, and the blend
/// plays on as planned.
pub fn drop_cut_at_ms(
    heard: (f64, f64),
    hold_end_ms: f64,
    lock_ms: f64,
    gap: f64,
    speed: f64,
    from_ms: f64,
) -> Option<f64> {
    let (start, end) = heard;
    if gap <= 0.0 || end <= 0.0 {
        return None;
    }
    let from = from_ms.max(start).max(hold_end_ms);
    if from > end {
        return None;
    }
    let reach = DRIFT_LIMIT_MS * speed / gap;
    if from < lock_ms - reach || from > lock_ms + reach {
        return Some(from);
    }
    let at = lock_ms + reach;
    (at < end).then_some(at)
}

/// Whether a genre gets the plain crossfade, never a planned one.
pub fn is_plain_genre(genre: &str) -> bool {
    let lower = genre.to_ascii_lowercase();
    PLAIN_CROSSFADE_GENRES.iter().any(|g| lower.contains(g))
}

// A cut for a short overlap; a lift when the next song's first 8 s are at
// least 3 dB louder than this song through the overlap; else a blend.
fn kind_of(
    overlap: f64,
    tail: Option<&SectionAnalysis>,
    head: Option<&SectionAnalysis>,
    start: f64,
    entry: f64,
) -> TransitionKind {
    if overlap <= CUT_BLEND_MS as f64 {
        return TransitionKind::Cut;
    }
    match (tail, head) {
        (Some(tail), Some(head))
            if head.level_db(entry, entry + 8_000.0) >= tail.level_db(start, start + overlap) + 3.0 =>
        {
            TransitionKind::Lift
        }
        _ => TransitionKind::Blend,
    }
}

fn curve_of(kind: TransitionKind) -> f64 {
    match kind {
        TransitionKind::Cut => CUT_CURVE,
        TransitionKind::Lift => LIFT_CURVE,
        TransitionKind::Blend => BLEND_CURVE,
        // An intro's gains come from its hold and rise, not a weight.
        TransitionKind::Intro => 0.0,
        _ => CROSSFADE_CURVE,
    }
}

// The blend at the end with no bar lock: ending at latest_end, over
// `overlap` or whatever is left after `earliest`, with the curve and
// filters. Too little left to blend plays gaplessly.
#[allow(clippy::too_many_arguments)]
fn loose_plan(
    len_a: i64,
    latest_end: f64,
    overlap: f64,
    earliest: f64,
    entry_ms: i64,
    tail: Option<&SectionAnalysis>,
    head: Option<&SectionAnalysis>,
    sweeps: f64,
    why: &str,
) -> TransitionPlan {
    let start = earliest.max(latest_end - overlap);
    let length = (latest_end - start + 0.5).floor();
    if length < SHORTEST_FADE_MS as f64 {
        return gapless_plan(len_a, &format!("too late to blend: {why}"));
    }
    let kind = kind_of(length, tail, head, start, entry_ms as f64);
    let start_ms = (start + 0.5).floor() as i64;
    TransitionPlan {
        start_ms,
        entry_ms,
        overlap_ms: length as i64,
        kind,
        k: curve_of(kind),
        filter_strength: sweeps,
        beat_match_rate: None,
        beat_ms: None,
        beat_anchor_ms: None,
        late: true,
        headroom_db: OVERLAP_HEADROOM_DB,
        reason: format!(
            "late {} at {} over {}, next song from {}: {why}",
            kind.name(),
            seconds(start_ms),
            seconds(length as i64),
            seconds(entry_ms)
        ),
        curve: FadeCurve::EqualPower,
        hold_share: 0.0,
        rise_share: 0.0,
        intro_level: 1.0,
        latest_end_ms: (latest_end + 0.5).floor() as i64,
        late_move_bars: LATE_MOVE_UNBOUNDED,
        both_heard_start_ms: 0,
        both_heard_end_ms: 0,
        lock_ms: 0.0,
        drift_gap: 0.0,
        drift_at_end_ms: 0.0,
    }
}

fn gapless_plan(len_a: i64, reason: &str) -> TransitionPlan {
    TransitionPlan {
        start_ms: len_a.max(0),
        entry_ms: 0,
        overlap_ms: 0,
        kind: TransitionKind::Gapless,
        k: CROSSFADE_CURVE,
        filter_strength: 0.0,
        beat_match_rate: None,
        beat_ms: None,
        beat_anchor_ms: None,
        late: false,
        headroom_db: 0.0,
        reason: format!("gapless: {reason}"),
        curve: FadeCurve::EqualPower,
        hold_share: 0.0,
        rise_share: 0.0,
        intro_level: 1.0,
        latest_end_ms: len_a.max(0),
        late_move_bars: LATE_MOVE_UNBOUNDED,
        both_heard_start_ms: 0,
        both_heard_end_ms: 0,
        lock_ms: 0.0,
        drift_gap: 0.0,
        drift_at_end_ms: 0.0,
    }
}

fn crossfade_plan(len_a: i64, length: i64, why: &str) -> TransitionPlan {
    TransitionPlan {
        start_ms: len_a - length,
        entry_ms: 0,
        overlap_ms: length,
        kind: TransitionKind::Crossfade,
        k: CROSSFADE_CURVE,
        filter_strength: 0.0,
        beat_match_rate: None,
        beat_ms: None,
        beat_anchor_ms: None,
        late: false,
        headroom_db: OVERLAP_HEADROOM_DB,
        reason: format!("crossfade at {} over {}: {why}", seconds(len_a - length), seconds(length)),
        curve: FadeCurve::EqualPower,
        hold_share: 0.0,
        rise_share: 0.0,
        intro_level: 1.0,
        latest_end_ms: len_a,
        late_move_bars: LATE_MOVE_UNBOUNDED,
        both_heard_start_ms: 0,
        both_heard_end_ms: 0,
        lock_ms: 0.0,
        drift_gap: 0.0,
        drift_at_end_ms: 0.0,
    }
}

fn gapless_reason(
    current: &FadeSong,
    next: Option<&FadeSong>,
    fade_ms: i64,
    context: &TransitionContext,
) -> String {
    let why = match next {
        _ if fade_ms <= 0 => "crossfade off",
        None => "nothing next",
        _ if context.repeat_one => "repeat one",
        _ if context.stop_at_end_of_song => "stopping at the end of the song",
        Some(next) if current.duration_ms == 0 || next.duration_ms == 0 => "a song's length is unknown",
        Some(next) if current.album_id.is_some() && current.album_id == next.album_id => {
            "the album plays in order"
        }
        Some(_) => "a song too short to blend",
    };
    why.to_string()
}

/// Milliseconds as seconds with two decimals, like "182.50 s".
pub fn seconds(ms: i64) -> String {
    let sign = if ms < 0 { "-" } else { "" };
    let abs = ms.unsigned_abs();
    format!("{sign}{}.{:02} s", abs / 1000, (abs % 1000) / 10)
}

// A positive number with a fixed count of decimals, like "120.0".
fn decimals(x: f64, places: u32) -> String {
    let scale = 10i64.pow(places);
    let scaled = (x * scale as f64 + 0.5).floor() as i64;
    format!("{}.{:0width$}", scaled / scale, scaled % scale, width = places as usize)
}
