//! Mixes what the listener hears: the playing lane, a second lane during a
//! crossfade, then speed and pitch, then the sound shaping. It also notes
//! which song and which moment of it each output frame holds, for the clock.

use std::collections::VecDeque;

use crate::crossfade::{fade_in_volume, fade_out_volume};
use crate::deck::Deck;
use crate::error::Failure;
use crate::lane::{Lane, LaneState, Span};
use crate::pace::{Pace, PaceStage};
use crate::sound::model::SoundSettings;
use crate::sound::replaygain::Loudness;
use crate::sound::shaper::SoundShaper;

/// Frames mixed per step: about 5 ms.
const BLOCK: usize = 256;

/// What began at a point in the output.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Transition {
    /// A song started on its own: the first one, after a skip, or a load.
    Start,
    /// A song followed the one before it with no gap.
    Gapless,
    /// A song began fading in over the one before it.
    Crossfade { from: u64, frames: u64 },
    /// The same song carries on from another place.
    Seek,
}

/// Output frame `frame` holds second `secs` of song `key`; later frames
/// move on by `secs_per_frame` each until the next marker.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Marker {
    pub frame: u64,
    pub key: u64,
    pub secs: f64,
    pub secs_per_frame: f64,
    pub transition: Option<Transition>,
}

/// Where mixing stands after a render.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MixState {
    Playing,
    /// Waiting on a song: opening, the network, or the next one.
    Waiting,
    /// Everything has played out.
    Ended,
}

// The song and moment at a mix frame, before speed changes.
#[derive(Clone, Copy, Debug)]
struct MixMark {
    frame: u64,
    key: u64,
    secs: f64,
    transition: Option<Transition>,
}

// A crossfade lined up to start at a moment of the current song.
struct PlannedFade {
    key: u64,
    at_secs: f64,
    frames: u64,
    deck: Deck,
    loudness: Loudness,
}

struct Fade {
    outgoing: Lane,
    frames: u64,
    done: u64,
}

pub struct Mixer {
    rate: u32,
    lane: Option<Lane>,
    fade: Option<Fade>,
    planned: Option<PlannedFade>,
    pace: PaceStage,
    shaper: SoundShaper,
    settings: SoundSettings,
    song_gain: f32,
    a: Vec<f32>,
    b: Vec<f32>,
    block: Vec<f32>,
    spans: Vec<Span>,
    spans_b: Vec<Span>,
    // Mix frames pushed on to the speed stage, and marks for them.
    mixed: u64,
    marks: VecDeque<MixMark>,
    next_transition: Option<Transition>,
    // Output frames made before the shaper's delay.
    made: u64,
    // Output frame numbering starts here.
    base: u64,
    tail_left: usize,
    failures: Vec<(u64, Failure)>,
    last_marker: Option<Marker>,
}

impl Mixer {
    /// A mixer at output `rate`, numbering its frames from `base`.
    pub fn new(rate: u32, base: u64, settings: &SoundSettings, pace: Pace) -> Self {
        let mut shaper = SoundShaper::new(rate, 2);
        shaper.apply(settings, 1.0, true);
        let mut stage = PaceStage::new(rate, 2);
        stage.set(pace);
        Mixer {
            rate,
            lane: None,
            fade: None,
            planned: None,
            pace: stage,
            shaper,
            settings: settings.clone(),
            song_gain: 1.0,
            a: vec![0.0; BLOCK * 2],
            b: vec![0.0; BLOCK * 2],
            block: vec![0.0; BLOCK * 2],
            spans: Vec::new(),
            spans_b: Vec::new(),
            mixed: 0,
            marks: VecDeque::new(),
            next_transition: Some(Transition::Start),
            made: 0,
            base,
            tail_left: 0,
            failures: Vec::new(),
            last_marker: None,
        }
    }

    pub fn rate(&self) -> u32 {
        self.rate
    }

    pub fn lane(&self) -> Option<&Lane> {
        self.lane.as_ref()
    }

    pub fn lane_mut(&mut self) -> Option<&mut Lane> {
        self.lane.as_mut()
    }

    pub fn is_fading(&self) -> bool {
        self.fade.is_some()
    }

    pub fn has_planned_fade(&self) -> bool {
        self.planned.is_some()
    }

    /// Starts playing `lane` in place of whatever played.
    pub fn start(&mut self, lane: Lane, transition: Transition) {
        self.lane = Some(lane);
        self.fade = None;
        self.planned = None;
        self.next_transition = Some(transition);
        self.tail_left = self.shaper.latency();
    }

    /// Stops everything; what the stages hold is dropped.
    pub fn clear(&mut self) {
        self.lane = None;
        self.fade = None;
        self.planned = None;
        self.pace.clear();
        self.shaper.clear_held();
    }

    /// Jumps within the current song.
    pub fn seek(&mut self, secs: f64) {
        self.fade = None;
        self.planned = None;
        if let Some(lane) = &mut self.lane {
            lane.seek(secs);
        }
        self.pace.clear();
        self.shaper.clear_held();
        self.next_transition = Some(Transition::Seek);
        self.tail_left = self.shaper.latency();
    }

    /// Takes up new sound settings, gliding into them.
    pub fn set_sound(&mut self, settings: &SoundSettings) {
        self.settings = settings.clone();
        self.shaper.apply(settings, self.song_gain, false);
        if let Some(lane) = &mut self.lane {
            lane.set_replay_gain(&settings.replay_gain);
        }
        if let Some(fade) = &mut self.fade {
            fade.outgoing.set_replay_gain(&settings.replay_gain);
        }
    }

    pub fn settings(&self) -> &SoundSettings {
        &self.settings
    }

    // The ReplayGain of the song playing now decides whether the limiter
    // needs to run at all.
    fn follow_song_gain(&mut self) {
        let gain = self.lane.as_ref().map(|l| l.current_gain()).unwrap_or(1.0);
        if gain != self.song_gain {
            self.song_gain = gain;
            let settings = self.settings.clone();
            self.shaper.apply(&settings, gain, false);
        }
    }

    pub fn set_pace(&mut self, pace: Pace) {
        self.pace.set(pace);
    }

    pub fn pace(&self) -> Pace {
        self.pace.pace()
    }

    /// Lines up a crossfade into `deck`, starting when song `key` reaches
    /// `at_secs` and lasting `frames` mix frames.
    pub fn plan_fade(&mut self, key: u64, at_secs: f64, frames: u64, deck: Deck, loudness: Loudness) {
        self.planned = Some(PlannedFade { key, at_secs, frames, deck, loudness });
    }

    /// Drops a planned crossfade, handing its deck back.
    pub fn cancel_planned_fade(&mut self) -> Option<Deck> {
        self.planned.take().map(|p| p.deck)
    }

    /// Ends a crossfade at once: the outgoing song stops.
    pub fn finish_fade(&mut self) {
        self.fade = None;
    }

    /// Songs that failed while playing, since the last call.
    pub fn take_failures(&mut self) -> Vec<(u64, Failure)> {
        let mut all = std::mem::take(&mut self.failures);
        if let Some(lane) = &mut self.lane {
            all.extend(lane.take_failures());
        }
        if let Some(fade) = &mut self.fade {
            all.extend(fade.outgoing.take_failures());
        }
        all
    }

    /// Fills `out` (interleaved stereo) as far as it can. Returns the frames
    /// made; `markers` gets where songs start or the clock needs a new
    /// reference point.
    pub fn render(&mut self, out: &mut [f32], markers: &mut Vec<Marker>) -> (usize, MixState) {
        let wanted = out.len() / 2;
        let mut made = 0;
        let mut state = MixState::Playing;
        while made < wanted {
            let n = (wanted - made).min(BLOCK);
            let got = if self.pace.is_active() {
                self.render_paced(n, markers)
            } else {
                self.render_direct(n, markers)
            };
            let (frames, s) = got;
            if frames == 0 {
                state = s;
                if s == MixState::Ended && self.tail_left > 0 {
                    // Let the shaper's held-back tail out.
                    let n = self.tail_left.min(wanted - made).min(BLOCK);
                    self.block[..n * 2].fill(0.0);
                    self.tail_left -= n;
                    self.shaper.process(&mut self.block, n);
                    out[made * 2..(made + n) * 2].copy_from_slice(&self.block[..n * 2]);
                    made += n;
                    self.made += n as u64;
                    continue;
                }
                break;
            }
            self.shaper.process(&mut self.block, frames);
            out[made * 2..(made + frames) * 2].copy_from_slice(&self.block[..frames * 2]);
            made += frames;
            self.made += frames as u64;
        }
        (made, state)
    }

    // At normal pace: mix straight into the block.
    fn render_direct(&mut self, n: usize, markers: &mut Vec<Marker>) -> (usize, MixState) {
        let start = self.mixed;
        let (frames, state) = self.mix(n);
        if frames > 0 {
            self.emit_markers(start as f64, frames, 1.0, markers);
        }
        (frames, state)
    }

    // With a speed or pitch change: mix into the speed stage until it has
    // enough to hand out.
    fn render_paced(&mut self, n: usize, markers: &mut Vec<Marker>) -> (usize, MixState) {
        let mut state = MixState::Playing;
        while self.pace.available() < n {
            let (frames, s) = self.mix(BLOCK);
            if frames == 0 {
                state = s;
                if s == MixState::Ended {
                    // The last of the song is still inside the stage.
                    self.pace.drain();
                }
                break;
            }
            self.pace.push(&self.block[..frames * 2]);
        }
        let pending = self.pace.pending_input();
        let start = self.mixed as f64 - pending;
        let frames = self.pace.pull(&mut self.block[..n * 2]);
        if frames > 0 {
            self.emit_markers(start, frames, self.pace.pace().speed as f64, markers);
            state = MixState::Playing;
        }
        (frames, state)
    }

    // Turns the marks for mix frames from `start` on into output markers
    // for `frames` output frames, where each output frame is `step` mix frames.
    fn emit_markers(&mut self, start: f64, frames: usize, step: f64, markers: &mut Vec<Marker>) {
        let latency = self.shaper.latency() as u64;
        let first_out = self.base + self.made + latency;
        let end = start + frames as f64 * step;
        let secs_per_frame = step / self.rate as f64;
        // The mark in force at the start, then any that begin inside.
        let from = self.marks.iter().rposition(|m| (m.frame as f64) <= start + 1e-6).unwrap_or(0);
        for i in from..self.marks.len() {
            let mark = self.marks[i];
            let at = (mark.frame as f64).max(start);
            if i > from && at >= end {
                break;
            }
            let offset = ((at - start) / step).round() as u64;
            let secs = mark.secs + (at - mark.frame as f64) / self.rate as f64;
            let transition = if (mark.frame as f64) >= start - 1e-6 { mark.transition } else { None };
            let marker =
                Marker { frame: first_out + offset, key: mark.key, secs, secs_per_frame, transition };
            // Only when it tells the clock something new.
            let predicted = self.last_marker.map(|m| {
                m.key == marker.key
                    && (m.secs + (marker.frame - m.frame.min(marker.frame)) as f64 * m.secs_per_frame - secs)
                        .abs()
                        < 0.002
                    && (m.secs_per_frame - secs_per_frame).abs() < 1e-12
            });
            if marker.transition.is_some() || predicted != Some(true) {
                markers.push(marker);
                self.last_marker = Some(marker);
            }
        }
        // Keep only the mark in force from here on.
        while self.marks.len() > 1 && (self.marks[1].frame as f64) <= end {
            self.marks.pop_front();
        }
    }

    // Mixes up to `n` frames into `self.block`.
    fn mix(&mut self, wanted: usize) -> (usize, MixState) {
        let mut n = self.frames_before_fade(wanted);
        if n == 0 && self.planned.is_some() {
            self.begin_fade();
            n = wanted;
        }
        let Some(lane) = &mut self.lane else {
            return (0, MixState::Ended);
        };
        let state = lane.fill(n);
        let fade_state = self.fade.as_mut().map(|f| f.outgoing.fill(n));
        let mut frames = lane.available().min(n);
        if let Some(fade) = &self.fade {
            // Both songs sound together; wait for both unless the old one is over.
            if !fade.outgoing.is_ended() {
                frames = frames.min(fade.outgoing.available());
            }
        }
        if frames == 0 {
            let state = match (state, fade_state) {
                (LaneState::Ended, None | Some(LaneState::Ended)) => MixState::Ended,
                _ => MixState::Waiting,
            };
            return (0, state);
        }
        let got = lane.pull(&mut self.a[..frames * 2], &mut self.spans);
        debug_assert_eq!(got, frames);
        let spans = std::mem::take(&mut self.spans);
        let mut transition = self.next_transition.take();
        for span in &spans {
            let t = if span.joined { Some(Transition::Gapless) } else { transition.take() };
            self.push_mark(span.key, span.start_secs, t);
            self.mixed += span.frames as u64;
        }
        self.spans = spans;
        self.follow_song_gain();
        self.block[..frames * 2].copy_from_slice(&self.a[..frames * 2]);
        if let Some(fade) = &mut self.fade {
            let old = fade.outgoing.pull(&mut self.b[..frames * 2], &mut self.spans_b);
            self.b[old * 2..frames * 2].fill(0.0);
            for i in 0..frames {
                let p = (fade.done + i as u64) as f32 / fade.frames.max(1) as f32;
                let (gin, gout) = (fade_in_volume(p), fade_out_volume(p));
                self.block[i * 2] = self.a[i * 2] * gin + self.b[i * 2] * gout;
                self.block[i * 2 + 1] = self.a[i * 2 + 1] * gin + self.b[i * 2 + 1] * gout;
            }
            fade.done += frames as u64;
            if fade.done >= fade.frames || fade.outgoing.is_ended() {
                self.failures.extend(fade.outgoing.take_failures());
                self.fade = None;
            }
        }
        (frames, MixState::Playing)
    }

    fn push_mark(&mut self, key: u64, secs: f64, transition: Option<Transition>) {
        // A plain continuation of the last mark needs no new one.
        if transition.is_none()
            && let Some(last) = self.marks.back()
            && last.key == key
            && (last.secs + (self.mixed - last.frame) as f64 / self.rate as f64 - secs).abs() < 0.0005
        {
            return;
        }
        self.marks.push_back(MixMark { frame: self.mixed, key, secs, transition });
    }

    // How many frames can be mixed before a planned crossfade begins.
    fn frames_before_fade(&self, n: usize) -> usize {
        let (Some(plan), Some(lane)) = (&self.planned, &self.lane) else { return n };
        let Some((key, secs)) = lane.position() else { return n };
        if key != plan.key {
            return n;
        }
        let left = ((plan.at_secs - secs) * self.rate as f64).round();
        if left <= 0.0 { 0 } else { (left as usize).min(n) }
    }

    fn begin_fade(&mut self) {
        let Some(plan) = self.planned.take() else { return };
        let Some(mut old) = self.lane.take() else { return };
        if !plan.deck.is_ready() {
            // Not ready in time: the song ends as usual and the next one
            // follows without a gap.
            old.set_next(Some(plan.deck), plan.loudness);
            self.lane = Some(old);
            return;
        }
        let from = old.current().key();
        old.set_next(None, Loudness::default());
        old.last = true;
        let incoming = Lane::new(plan.deck, plan.loudness, &self.settings.replay_gain, self.rate);
        self.lane = Some(incoming);
        self.next_transition = Some(Transition::Crossfade { from, frames: plan.frames });
        self.fade = Some(Fade { outgoing: old, frames: plan.frames, done: 0 });
    }
}
