//! A song's transition profile as the app hands it over, from a server that
//! worked it out from the whole file: the same profile [`TransitionProfile`]
//! keeps, with each end's levels already in dBFS.

use super::features::{SectionFeatures, Tempo};
use super::profile::{PROFILE_VERSION, SectionProfile, TransitionProfile};

/// A beat grid: beats on `first_beat_ms + n * beat_ms`, bars on
/// `downbeat_ms + n * 4 * beat_ms`, in song time.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SongProfileTempo {
    pub bpm: f64,
    pub beat_ms: f64,
    pub first_beat_ms: f64,
    pub downbeat_ms: f64,
    pub confidence: f64,
    pub consistency: f64,
    pub steady: bool,
}

/// One end of a song: its level in dBFS every `hop_ms` from `start_ms`, and
/// its features.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SongProfileSection {
    pub start_ms: i64,
    pub hop_ms: u32,
    pub levels: Vec<f32>,
    pub body_db: f64,
    pub gate_db: f64,
    #[uniffi(default)]
    pub sound_start_ms: Option<i64>,
    #[uniffi(default)]
    pub sound_end_ms: Option<i64>,
    #[uniffi(default)]
    pub outro_start_ms: Option<i64>,
    #[uniffi(default)]
    pub intro_end_ms: Option<i64>,
    #[uniffi(default)]
    pub boundaries_ms: Vec<i64>,
    #[uniffi(default)]
    pub tempo: Option<SongProfileTempo>,
}

/// What the planner needs about one song: its first 30 s and last 60 s, and
/// the whole song's level and tempo.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SongProfile {
    pub duration_ms: i64,
    #[uniffi(default)]
    pub body_db: Option<f64>,
    #[uniffi(default)]
    pub tempo: Option<SongProfileTempo>,
    pub head: SongProfileSection,
    pub tail: SongProfileSection,
}

impl SongProfile {
    /// The profile the planner reads, or `None` when it is not whole: no
    /// length, an end without levels, or a level or tempo that is no number.
    pub fn to_profile(&self) -> Option<TransitionProfile> {
        if self.duration_ms <= 0 {
            return None;
        }
        Some(TransitionProfile {
            version: PROFILE_VERSION,
            duration_ms: self.duration_ms,
            body_db: self.body_db.filter(|b| b.is_finite()),
            tempo: self.tempo.as_ref().and_then(tempo_of),
            head: section_of(&self.head)?,
            tail: section_of(&self.tail)?,
        })
    }
}

fn section_of(s: &SongProfileSection) -> Option<SectionProfile> {
    let hop_ms = i32::try_from(s.hop_ms).ok().filter(|&h| h > 0)?;
    if s.levels.is_empty() || !s.levels.iter().all(|l| l.is_finite()) {
        return None;
    }
    if !s.body_db.is_finite() || !s.gate_db.is_finite() {
        return None;
    }
    Some(SectionProfile {
        start_ms: s.start_ms,
        hop_ms,
        levels: s.levels.clone(),
        features: SectionFeatures {
            body_db: s.body_db,
            gate_db: s.gate_db,
            sound_start_ms: s.sound_start_ms,
            sound_end_ms: s.sound_end_ms,
            outro_start_ms: s.outro_start_ms,
            intro_end_ms: s.intro_end_ms,
            boundaries_ms: s.boundaries_ms.clone(),
            tempo: s.tempo.as_ref().and_then(tempo_of),
        },
    })
}

fn tempo_of(t: &SongProfileTempo) -> Option<Tempo> {
    let numbers = [t.bpm, t.beat_ms, t.first_beat_ms, t.downbeat_ms, t.confidence, t.consistency];
    if t.bpm <= 0.0 || t.beat_ms <= 0.0 || !numbers.iter().all(|n| n.is_finite()) {
        return None;
    }
    Some(Tempo {
        bpm: t.bpm,
        confidence: t.confidence,
        consistency: t.consistency,
        steady: t.steady,
        beat_ms: t.beat_ms,
        first_beat_ms: t.first_beat_ms,
        downbeat_ms: t.downbeat_ms,
    })
}

impl From<&TransitionProfile> for SongProfile {
    fn from(p: &TransitionProfile) -> Self {
        let tempo = |t: &Tempo| SongProfileTempo {
            bpm: t.bpm,
            beat_ms: t.beat_ms,
            first_beat_ms: t.first_beat_ms,
            downbeat_ms: t.downbeat_ms,
            confidence: t.confidence,
            consistency: t.consistency,
            steady: t.steady,
        };
        let section = |s: &SectionProfile| SongProfileSection {
            start_ms: s.start_ms,
            hop_ms: s.hop_ms as u32,
            levels: s.levels.clone(),
            body_db: s.features.body_db,
            gate_db: s.features.gate_db,
            sound_start_ms: s.features.sound_start_ms,
            sound_end_ms: s.features.sound_end_ms,
            outro_start_ms: s.features.outro_start_ms,
            intro_end_ms: s.features.intro_end_ms,
            boundaries_ms: s.features.boundaries_ms.clone(),
            tempo: s.features.tempo.as_ref().map(tempo),
        };
        SongProfile {
            duration_ms: p.duration_ms,
            body_db: p.body_db,
            tempo: p.tempo.as_ref().map(tempo),
            head: section(&p.head),
            tail: section(&p.tail),
        }
    }
}
