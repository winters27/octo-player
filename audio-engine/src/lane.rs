//! A lane plays songs one after another with no gap between them, changed
//! to the device's sample rate. Songs at the same rate go through one
//! resampler without a break, so the join is exact to the sample. During a
//! crossfade two lanes play at once.

use std::collections::VecDeque;

use rubato::audioadapter_buffers::direct::InterleavedSlice;
use rubato::{Fft, FixedSync, Indexing, Resampler as _};

use crate::deck::{Deck, DeckStatus};
use crate::error::Failure;
use crate::fifo::Fifo;
use crate::sound::model::ReplayGainSettings;
use crate::sound::replaygain::{Loudness, choose_replay_gain, replay_gain_factor};
use crate::sound::shaper::{GLIDE_MS, Glide};

// Frames the resampler takes per step.
const CHUNK: usize = 1024;

/// A stretch of lane output that belongs to one song.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Span {
    pub frames: usize,
    pub key: u64,
    /// Where the span starts in the song, in seconds.
    pub start_secs: f64,
    /// The song starts here, straight after the one before it.
    pub joined: bool,
}

/// What stopped a fill short.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LaneState {
    /// Enough sound is ready.
    Ready,
    /// Waiting on a deck: opening, the network, or the next song.
    Waiting,
    /// The last song has played out.
    Ended,
}

// One song feeding the lane.
struct Feed {
    deck: Deck,
    loudness: Loudness,
    gain: Glide,
    started: bool,
}

impl Feed {
    fn new(deck: Deck, loudness: Loudness) -> Self {
        Feed { deck, loudness, gain: Glide::new(1.0), started: false }
    }

    // The song's ReplayGain factor, from its own tags once they are read,
    // or the values it was queued with.
    fn factor(&self, settings: &ReplayGainSettings) -> f32 {
        let tags = self.deck.info().and_then(|i| i.replay_gain);
        let song = self.loudness.song(choose_replay_gain(tags, self.loudness.stored));
        replay_gain_factor(settings, Some(&song))
    }
}

// Where a song's sound begins in the lane's output.
struct Mark {
    out_frame: f64,
    key: u64,
    start_secs: f64,
    joined: bool,
}

struct Rate {
    inner: Fft<f32>,
    out: Vec<f32>,
}

pub struct Lane {
    out_rate: u32,
    current: Feed,
    next: Option<Feed>,
    /// No song follows the current one.
    pub last: bool,
    replay_gain: ReplayGainSettings,
    in_rate: u32,
    resampler: Option<Rate>,
    inbuf: Vec<f32>,
    scratch: Vec<f32>,
    out: Fifo,
    marks: VecDeque<Mark>,
    // Output frames made, taken, and where the current run of one input
    // rate began, all counted from the lane's start.
    out_made: u64,
    out_taken: u64,
    epoch_out: f64,
    epoch_in: u64,
    delay_left: usize,
    failures: Vec<(u64, Failure)>,
    ended: bool,
    // The next song began since the last look.
    switched: bool,
}

impl Lane {
    /// A lane starting with `deck`, levelled by `replay_gain`.
    pub fn new(deck: Deck, loudness: Loudness, replay_gain: &ReplayGainSettings, out_rate: u32) -> Self {
        Lane {
            out_rate,
            current: Feed::new(deck, loudness),
            next: None,
            last: false,
            replay_gain: replay_gain.clone(),
            in_rate: 0,
            resampler: None,
            inbuf: Vec::with_capacity(CHUNK * 4),
            scratch: vec![0.0; CHUNK * 4],
            out: Fifo::with_capacity(CHUNK * 8),
            marks: VecDeque::new(),
            out_made: 0,
            out_taken: 0,
            epoch_out: 0.0,
            epoch_in: 0,
            delay_left: 0,
            failures: Vec::new(),
            ended: false,
            switched: false,
        }
    }

    pub fn current(&self) -> &Deck {
        &self.current.deck
    }

    pub fn next(&self) -> Option<&Deck> {
        self.next.as_ref().map(|f| &f.deck)
    }

    /// Lines up the song that follows the current one without a gap.
    pub fn set_next(&mut self, deck: Option<Deck>, loudness: Loudness) {
        self.next = deck.map(|deck| Feed::new(deck, loudness));
        if self.next.is_some() {
            self.last = false;
        }
    }

    /// Takes the next deck back out, for a crossfade into it instead.
    pub fn take_next(&mut self) -> Option<Deck> {
        self.next.take().map(|f| f.deck)
    }

    /// Takes up new ReplayGain settings; the playing song glides to its
    /// new level.
    pub fn set_replay_gain(&mut self, settings: &ReplayGainSettings) {
        self.replay_gain = settings.clone();
        if self.current.started {
            let frames = (self.in_rate.max(1) as f32 * GLIDE_MS / 1_000.0) as usize;
            let factor = self.current.factor(settings);
            self.current.gain.to(factor, frames);
        }
    }

    /// The level the playing song is at, as a factor.
    pub fn current_gain(&self) -> f32 {
        self.current.gain.value()
    }

    /// Whether the last song has played out of the lane.
    pub fn is_ended(&self) -> bool {
        self.ended && self.out.is_empty()
    }

    /// Songs that failed while playing, by key, since the last call.
    pub fn take_failures(&mut self) -> Vec<(u64, Failure)> {
        std::mem::take(&mut self.failures)
    }

    /// Whether the next song began since the last call.
    pub fn take_switched(&mut self) -> bool {
        std::mem::take(&mut self.switched)
    }

    /// Where the song at the front of the lane's output is, in seconds.
    pub fn position(&self) -> Option<(u64, f64)> {
        let at = self.out_taken as f64;
        let mark = self.marks.iter().rev().find(|m| m.out_frame <= at + 0.5)?;
        Some((mark.key, mark.start_secs + (at - mark.out_frame) / self.out_rate as f64))
    }

    /// Jumps within the current song. Everything held is dropped.
    pub fn seek(&mut self, secs: f64) {
        self.current.deck.seek(secs);
        self.current.started = false;
        self.reset_chain();
        self.ended = false;
    }

    fn reset_chain(&mut self) {
        self.inbuf.clear();
        self.out.clear();
        self.marks.clear();
        self.resampler = None;
        self.in_rate = 0;
        self.out_made = 0;
        self.out_taken = 0;
        self.epoch_out = 0.0;
        self.epoch_in = 0;
        self.delay_left = 0;
    }

    /// Frames ready to take out.
    pub fn available(&self) -> usize {
        self.out.len() / 2
    }

    /// Takes `out.len() / 2` frames, or fewer if not enough are ready, and
    /// says which songs they came from.
    pub fn pull(&mut self, out: &mut [f32], spans: &mut Vec<Span>) -> usize {
        spans.clear();
        let frames = self.out.pop_into(out) / 2;
        let start = self.out_taken as f64;
        let end = start + frames as f64;
        let mut at = start;
        // The song playing at the start of the range, then each new one.
        let first = self.marks.iter().rposition(|m| m.out_frame <= start + 0.5);
        let from = first.unwrap_or(0);
        for i in from..self.marks.len() {
            let mark = &self.marks[i];
            if i > from && mark.out_frame >= end - 0.5 {
                break;
            }
            let span_start = mark.out_frame.max(at);
            let next_start =
                self.marks.get(i + 1).map(|m| m.out_frame.min(end)).unwrap_or(end).max(span_start);
            let len = (next_start.round() - span_start.round()).max(0.0) as usize;
            if len > 0 || (i == from && frames > 0 && spans.is_empty()) {
                spans.push(Span {
                    frames: len,
                    key: mark.key,
                    start_secs: mark.start_secs + (span_start - mark.out_frame) / self.out_rate as f64,
                    joined: mark.joined && (span_start - mark.out_frame).abs() < 0.5,
                });
            }
            at = next_start;
        }
        // Rounding can leave the last span a frame short or long.
        let counted: usize = spans.iter().map(|s| s.frames).sum();
        if let Some(last) = spans.last_mut() {
            last.frames = (last.frames + frames).saturating_sub(counted);
        }
        self.out_taken += frames as u64;
        // Marks for songs already fully out are no longer needed.
        while self.marks.len() > 1 && self.marks[1].out_frame <= self.out_taken as f64 {
            self.marks.pop_front();
        }
        frames
    }

    /// Makes at least `wanted` frames ready if it can.
    pub fn fill(&mut self, wanted: usize) -> LaneState {
        while self.out.len() / 2 < wanted {
            if self.ended {
                return LaneState::Ended;
            }
            match self.step() {
                LaneState::Ready => {}
                other => return other,
            }
        }
        LaneState::Ready
    }

    // Gathers one chunk of input and turns it into output.
    fn step(&mut self) -> LaneState {
        if self.in_rate == 0 {
            // Nothing known yet about the first song's rate.
            match self.current.deck.info() {
                Some(info) => self.begin_epoch(info.sample_rate),
                None => {
                    if let DeckStatus::Failed(f) = self.current.deck.status() {
                        return self.current_failed(f);
                    }
                    return LaneState::Waiting;
                }
            }
        }
        let chunk = if self.resampler.is_some() { CHUNK } else { CHUNK / 2 };
        while self.inbuf.len() / 2 < chunk {
            let want = chunk - self.inbuf.len() / 2;
            let read = self.current.deck.read(&mut self.scratch[..want * 2]);
            if read.frames > 0 {
                if !self.current.started {
                    self.current.started = true;
                    // The song's own level, from its first frame.
                    let factor = self.current.factor(&self.replay_gain);
                    self.current.gain.to(factor, 0);
                    let in_index = self.epoch_in + (self.inbuf.len() / 2) as u64;
                    let out_frame = self.out_frame_of(in_index);
                    let joined = self.out_made > 0 || !self.inbuf.is_empty();
                    self.marks.push_back(Mark {
                        out_frame,
                        key: self.current.deck.key(),
                        start_secs: read.start_secs,
                        joined,
                    });
                }
                for frame in self.scratch[..read.frames * 2].chunks_exact_mut(2) {
                    let g = self.current.gain.advance();
                    frame[0] *= g;
                    frame[1] *= g;
                }
                self.inbuf.extend_from_slice(&self.scratch[..read.frames * 2]);
                continue;
            }
            if !read.finished {
                return LaneState::Waiting;
            }
            if let Some(f) = read.failure {
                self.failures.push((self.current.deck.key(), f));
            }
            // The current song is over: on to the next one, or the end.
            let Some(next) = self.next.take() else {
                if self.last {
                    self.finish_epoch();
                    self.ended = true;
                    return LaneState::Ended;
                }
                return LaneState::Waiting;
            };
            let Some(info) = next.deck.info() else {
                if let DeckStatus::Failed(f) = next.deck.status() {
                    // It will never play; the owner lines up another.
                    self.failures.push((next.deck.key(), f));
                    return LaneState::Waiting;
                }
                self.next = Some(next);
                return LaneState::Waiting;
            };
            if info.sample_rate != self.in_rate {
                self.finish_epoch();
                self.begin_epoch(info.sample_rate);
            }
            self.current = next;
            self.switched = true;
        }
        self.process_chunk(chunk, None);
        LaneState::Ready
    }

    fn current_failed(&mut self, f: Failure) -> LaneState {
        self.failures.push((self.current.deck.key(), f));
        match self.next.take() {
            Some(next) => {
                self.current = next;
                self.switched = true;
                LaneState::Ready
            }
            None if self.last => {
                self.ended = true;
                LaneState::Ended
            }
            None => LaneState::Waiting,
        }
    }

    // Where an input frame of the current rate's run lands in the output.
    // The resampler's delay is dropped, so the two line up exactly.
    fn out_frame_of(&self, in_index: u64) -> f64 {
        let ratio = self.out_rate as f64 / self.in_rate.max(1) as f64;
        self.epoch_out + in_index as f64 * ratio
    }

    // Starts a run of input at one sample rate.
    fn begin_epoch(&mut self, rate: u32) {
        self.in_rate = rate;
        self.epoch_out = self.out_made as f64;
        self.epoch_in = 0;
        self.inbuf.clear();
        if rate == self.out_rate {
            self.resampler = None;
            self.delay_left = 0;
        } else {
            match Fft::<f32>::new(rate as usize, self.out_rate as usize, CHUNK, 2, FixedSync::Input) {
                Ok(inner) => {
                    self.delay_left = inner.output_delay();
                    let out = vec![0.0; inner.output_frames_max() * 2];
                    self.resampler = Some(Rate { inner, out });
                }
                Err(e) => {
                    log::error!("no resampler for {rate} to {}: {e}", self.out_rate);
                    self.resampler = None;
                    self.delay_left = 0;
                }
            }
        }
    }

    // Plays out what is left of the current rate's input, exactly.
    fn finish_epoch(&mut self) {
        let partial = self.inbuf.len() / 2;
        if self.resampler.is_none() {
            self.out.push(&self.inbuf);
            self.inbuf.clear();
            self.out_made += partial as u64;
            self.epoch_in += partial as u64;
            return;
        }
        let ratio = self.out_rate as f64 / self.in_rate as f64;
        let total_in = self.epoch_in + partial as u64;
        let expected = self.epoch_out + (total_in as f64 * ratio).round();
        if partial > 0 {
            self.process_chunk(CHUNK, Some(partial));
        }
        // Push silence through until the tail of the real sound is out.
        let mut guard = 0;
        while (self.out_made as f64) < expected && guard < 64 {
            self.process_chunk(CHUNK, Some(0));
            guard += 1;
        }
        let extra = (self.out_made as f64 - expected).max(0.0) as usize;
        if extra > 0 {
            let keep = self.out.len() - extra * 2;
            let held: Vec<f32> = self.out.as_slice()[..keep].to_vec();
            self.out.clear();
            self.out.push(&held);
            self.out_made -= extra as u64;
        }
        self.epoch_in = total_in;
    }

    // Resamples (or copies) one chunk of `inbuf`. With `partial`, only that
    // many frames are real and the rest is silence.
    fn process_chunk(&mut self, chunk: usize, partial: Option<usize>) {
        let real = partial.unwrap_or(chunk);
        match &mut self.resampler {
            None => {
                let samples = real.min(self.inbuf.len() / 2) * 2;
                self.out.push(&self.inbuf[..samples]);
                self.inbuf.drain(..samples);
                self.out_made += (samples / 2) as u64;
                self.epoch_in += (samples / 2) as u64;
            }
            Some(rate) => {
                let needed = rate.inner.input_frames_next();
                if self.inbuf.len() < needed * 2 {
                    self.inbuf.resize(needed * 2, 0.0);
                }
                let input = InterleavedSlice::new(&self.inbuf[..], 2, needed).expect("input size");
                let out_frames = rate.inner.output_frames_max();
                let mut output =
                    InterleavedSlice::new_mut(&mut rate.out[..], 2, out_frames).expect("output size");
                let indexing = partial.map(|n| Indexing::new().partial_len(n));
                match rate.inner.process_into_buffer(&input, &mut output, indexing.as_ref()) {
                    Ok((_, made)) => {
                        let skip = self.delay_left.min(made);
                        self.delay_left -= skip;
                        self.out.push(&rate.out[skip * 2..made * 2]);
                        self.out_made += (made - skip) as u64;
                    }
                    Err(e) => log::error!("resampling failed: {e}"),
                }
                let used = real.min(needed);
                self.inbuf.drain(..(used * 2).min(self.inbuf.len()));
                if partial.is_some() {
                    self.inbuf.clear();
                }
                self.epoch_in += used as u64;
            }
        }
    }
}
