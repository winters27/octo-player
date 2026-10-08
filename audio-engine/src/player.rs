//! The engine's own thread: it owns the queue, the mixer and the device,
//! keeps the ring to the device topped up, lines up the next song for a
//! gapless join or a crossfade, and turns what the listener actually hears
//! into events.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::thread::{self, Thread};
use std::time::{Duration, Instant};

use crossbeam_channel::{Receiver, Sender, TryRecvError};

use crate::api::{
    EndReason, EngineEvent, EngineListener, PlaybackPosition, PlaybackState, QueueItem, QueueSnapshot,
    RepeatMode,
};
use crate::crossfade::{FadeSong, SHORTEST_FADE_MS, crossfade_length};
use crate::deck::{Deck, DeckStatus};
use crate::decode::TrackInfo;
use crate::lane::Lane;
use crate::mixer::{Marker, MixState, Mixer, Transition};
use crate::output::{
    DeviceEvent, Driver, OpenedOutput, OutputDevice, OutputFormat, OutputShared, Renderer, ring,
};
use crate::pace::Pace;
use crate::sound::model::{DspSettings, EqSettings, ReplayGainSettings, SoundSettings};
use crate::sound::replaygain::{Loudness, stored_replay_gain};
use crate::source::http::{self, HttpOptions};
use crate::source::trust::Trust;
use crate::timeline::{Moment, Timeline};

/// How much sound is kept queued for the device, and the most it holds.
const RING_TARGET_SECS: f64 = 0.12;
const RING_SECS: f64 = 0.4;

/// How far ahead of a song's end the next one is opened.
const PREPARE_SECS: f64 = 20.0;

/// How long without sound, while playing, before it counts as buffering.
const STARVED_GRACE: Duration = Duration::from_millis(80);

/// How long a pause lasts before the device stream is stopped, so the
/// computer can sleep.
const IDLE_DEVICE_AFTER: Duration = Duration::from_secs(5);

const DEFAULT_DEVICE_CHECK: Duration = Duration::from_secs(2);

pub enum Command {
    Load { items: Vec<QueueItem>, start_index: usize, start_ms: u64, play: bool },
    PlayNext(Vec<QueueItem>),
    Enqueue(Vec<QueueItem>),
    ReplaceUpcoming(Vec<QueueItem>),
    ReplaceQueue { items: Vec<QueueItem>, current: usize },
    SkipTo(usize),
    SkipNext,
    Play,
    Pause,
    Stop,
    Seek(u64),
    SetVolume(f32),
    SetMuted(bool),
    SetCrossfade(u32),
    SetEq(EqSettings),
    SetReplayGain(ReplayGainSettings),
    SetDsp(DspSettings),
    SetPace(Pace),
    SetRepeat(RepeatMode),
    SetStopAfterCurrent(bool),
    SetOutputDevice(Option<String>),
    Devices(Sender<Vec<OutputDevice>>),
    SetPositionInterval(u32),
    Shutdown,
}

/// What the player thread is given to run with.
pub struct Driven {
    pub driver: Box<dyn Driver>,
    pub commands: Receiver<Command>,
    pub events: Sender<EngineEvent>,
    pub shared: Arc<Shared>,
}

#[derive(Clone, Debug, PartialEq)]
struct QueueEntryView {
    key: u64,
    id: String,
    duration_ms: Option<u64>,
}

/// A snapshot for the app's quick questions, answered without a round trip.
#[derive(Clone, Debug)]
pub struct Status {
    pub state: PlaybackState,
    pub volume: f32,
    pub device: Option<OutputDevice>,
    /// What the open device's stream runs at.
    pub format: Option<OutputFormat>,
    pub info: Option<TrackInfo>,
    queue: Vec<QueueEntryView>,
    heard: Option<u64>,
}

/// What the app's threads and the player thread share.
pub struct Shared {
    pub listener: Mutex<Option<Arc<dyn EngineListener>>>,
    status: Mutex<Status>,
    timeline: Timeline,
    output: Mutex<Option<Arc<OutputShared>>>,
    player: OnceLock<Thread>,
    /// The certificates trusted for streams, changed from the app's threads.
    pub trust: Arc<Trust>,
    // The client every stream fetches with, knowing those certificates.
    agent: ureq::Agent,
}

impl Shared {
    pub fn new() -> Self {
        let trust = Arc::new(Trust::default());
        Shared {
            listener: Mutex::new(None),
            status: Mutex::new(Status {
                state: PlaybackState::Idle,
                volume: 1.0,
                device: None,
                format: None,
                info: None,
                queue: Vec::new(),
                heard: None,
            }),
            timeline: Timeline::new(),
            output: Mutex::new(None),
            player: OnceLock::new(),
            agent: http::agent(trust.clone()),
            trust,
        }
    }

    pub fn set_player_thread(&self, thread: Thread) {
        let _ = self.player.set(thread);
    }

    /// Wakes the player thread to act on a command now.
    pub fn wake(&self) {
        if let Some(t) = self.player.get() {
            t.unpark();
        }
    }

    pub fn status(&self) -> Status {
        self.lock_status().clone()
    }

    fn lock_status(&self) -> MutexGuard<'_, Status> {
        self.status.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn heard_frame(&self) -> Option<f64> {
        let output = self.output.lock().unwrap_or_else(|e| e.into_inner()).clone();
        output.and_then(|o| o.clock.heard(Instant::now()))
    }

    pub fn position(&self) -> PlaybackPosition {
        let moment = self.timeline.at(self.heard_frame());
        let status = self.lock_status();
        let Some(moment) = moment else {
            return PlaybackPosition { item_id: None, index: None, position_ms: 0.0, duration_ms: None };
        };
        let index = status.queue.iter().position(|e| e.key == moment.key);
        let entry = index.map(|i| &status.queue[i]);
        PlaybackPosition {
            item_id: entry.map(|e| e.id.clone()),
            index: index.map(|i| i as u32),
            position_ms: moment.secs * 1_000.0,
            duration_ms: entry.and_then(|e| e.duration_ms),
        }
    }

    pub fn underruns(&self) -> u64 {
        let output = self.output.lock().unwrap_or_else(|e| e.into_inner()).clone();
        output.map(|o| o.underruns()).unwrap_or(0)
    }

    pub fn queue(&self) -> QueueSnapshot {
        let status = self.lock_status();
        let current = status.heard.and_then(|k| status.queue.iter().position(|e| e.key == k));
        QueueSnapshot {
            item_ids: status.queue.iter().map(|e| e.id.clone()).collect(),
            current_index: current.map(|i| i as u32),
        }
    }
}

impl Default for Shared {
    fn default() -> Self {
        Self::new()
    }
}

struct Entry {
    key: u64,
    item: QueueItem,
    failed: bool,
}

struct Out {
    opened: OpenedOutput,
    shared: Arc<OutputShared>,
    producer: rtrb::Producer<f32>,
    written: u64,
    target: u64,
}

struct Player {
    driver: Box<dyn Driver>,
    commands: Receiver<Command>,
    events: Sender<EngineEvent>,
    shared: Arc<Shared>,
    queue: Vec<Entry>,
    current: Option<usize>,
    next_key: u64,
    settings: SoundSettings,
    crossfade_ms: u32,
    pace: Pace,
    repeat: RepeatMode,
    stop_after_current: bool,
    volume: f32,
    muted: bool,
    device_choice: Option<String>,
    out: Option<Out>,
    mixer: Option<Mixer>,
    mix_state: MixState,
    playing: bool,
    state: PlaybackState,
    ended: bool,
    prepared: Option<u64>,
    heard: Option<u64>,
    heard_frame: f64,
    end_reason: EndReason,
    starved_since: Option<Instant>,
    buffering: bool,
    tick: Duration,
    last_tick: Instant,
    silent_since: Option<Instant>,
    device_running: bool,
    last_default_check: Instant,
    // Where the song was when the device was lost and none would open, to
    // carry on from there once one does.
    lost_at: Option<Moment>,
    infos: HashMap<u64, TrackInfo>,
    block: Vec<f32>,
    markers: Vec<Marker>,
    crossed: Vec<Marker>,
}

/// Runs the player until it is told to shut down.
pub fn run(driven: Driven) {
    let mut p = Player {
        driver: driven.driver,
        commands: driven.commands,
        events: driven.events,
        shared: driven.shared,
        queue: Vec::new(),
        current: None,
        next_key: 1,
        settings: SoundSettings::default(),
        crossfade_ms: 0,
        pace: Pace::default(),
        repeat: RepeatMode::Off,
        stop_after_current: false,
        volume: 1.0,
        muted: false,
        device_choice: None,
        out: None,
        mixer: None,
        mix_state: MixState::Ended,
        playing: false,
        state: PlaybackState::Idle,
        ended: false,
        prepared: None,
        heard: None,
        heard_frame: 0.0,
        end_reason: EndReason::Skipped,
        starved_since: None,
        buffering: false,
        tick: Duration::ZERO,
        last_tick: Instant::now(),
        silent_since: None,
        device_running: false,
        last_default_check: Instant::now(),
        lost_at: None,
        infos: HashMap::new(),
        block: vec![0.0; 2048],
        markers: Vec::new(),
        crossed: Vec::new(),
    };
    loop {
        loop {
            match p.commands.try_recv() {
                Ok(Command::Shutdown) | Err(TryRecvError::Disconnected) => {
                    p.shutdown();
                    return;
                }
                Ok(command) => p.handle(command),
                Err(TryRecvError::Empty) => break,
            }
        }
        p.check_device();
        p.manage_queue();
        p.produce();
        p.follow_heard();
        p.publish();
        let wait = if p.playing { Duration::from_millis(10) } else { Duration::from_millis(50) };
        thread::park_timeout(wait);
    }
}

impl Player {
    fn emit(&self, event: EngineEvent) {
        let _ = self.events.send(event);
    }

    fn set_state(&mut self, state: PlaybackState) {
        if self.state != state {
            self.state = state;
            self.emit(EngineEvent::StateChanged { state });
        }
    }

    fn handle(&mut self, command: Command) {
        match command {
            Command::Load { items, start_index, start_ms, play } => {
                self.load(items, start_index, start_ms, play)
            }
            Command::PlayNext(items) => {
                let at = self.current.map(|c| c + 1).unwrap_or(0).min(self.queue.len());
                let entries: Vec<Entry> = items.into_iter().map(|i| self.entry(i)).collect();
                self.queue.splice(at..at, entries);
                self.queue_changed();
            }
            Command::Enqueue(items) => {
                let entries: Vec<Entry> = items.into_iter().map(|i| self.entry(i)).collect();
                self.queue.extend(entries);
                self.queue_changed();
            }
            Command::ReplaceUpcoming(items) => {
                let keep = self.current.map(|c| c + 1).unwrap_or(0).min(self.queue.len());
                let old = self.queue.split_off(keep);
                let entries = self.reuse_entries(old, items);
                self.queue.extend(entries);
                self.queue_changed();
            }
            Command::ReplaceQueue { items, current } => self.replace_queue(items, current),
            Command::SkipTo(index) => {
                if index < self.queue.len() {
                    self.current = Some(index);
                    self.start_current(0.0, Transition::Start, EndReason::Skipped);
                }
            }
            Command::SkipNext => match self.following(false) {
                Some(index) => {
                    self.current = Some(index);
                    self.start_current(0.0, Transition::Start, EndReason::Skipped);
                }
                None => self.stop(EndReason::Skipped),
            },
            Command::Play => self.play(),
            Command::Pause => self.pause(),
            Command::Stop => self.stop(EndReason::Stopped),
            Command::Seek(ms) => self.seek(ms as f64 / 1_000.0),
            Command::SetVolume(v) => {
                self.volume = v;
                self.apply_volume();
            }
            Command::SetMuted(m) => {
                self.muted = m;
                self.apply_volume();
            }
            Command::SetCrossfade(ms) => {
                self.crossfade_ms = ms;
                if let Some(m) = &mut self.mixer {
                    m.finish_fade();
                }
                self.drop_prepared();
            }
            Command::SetEq(eq) => {
                self.settings.eq = eq;
                self.apply_sound();
            }
            Command::SetReplayGain(rg) => {
                self.settings.replay_gain = rg;
                self.apply_sound();
            }
            Command::SetDsp(dsp) => {
                self.settings.dsp = dsp;
                self.apply_sound();
            }
            Command::SetPace(pace) => {
                self.pace = pace;
                if let Some(m) = &mut self.mixer {
                    m.set_pace(pace);
                }
                self.drop_prepared();
            }
            Command::SetRepeat(mode) => {
                self.repeat = mode;
                self.drop_prepared();
            }
            Command::SetStopAfterCurrent(on) => {
                self.stop_after_current = on;
                self.drop_prepared();
            }
            Command::SetOutputDevice(id) => {
                self.device_choice = id;
                // Also when the device was lost and none opened since, if
                // there is a song to carry on with.
                if self.out.is_some() || self.mixer.is_some() {
                    self.reopen_output(true);
                }
            }
            Command::Devices(reply) => {
                let _ = reply.send(self.driver.devices());
            }
            Command::SetPositionInterval(ms) => self.tick = Duration::from_millis(ms as u64),
            Command::Shutdown => {}
        }
    }

    fn entry(&mut self, item: QueueItem) -> Entry {
        let key = self.next_key;
        self.next_key += 1;
        Entry { key, item, failed: false }
    }

    // Entries for items coming into the queue. An item whose id one of the
    // `old` entries has keeps that entry's key, and whether it failed, so
    // the song playing and the one lined up after it carry on untouched.
    fn reuse_entries(&mut self, old: Vec<Entry>, items: Vec<QueueItem>) -> Vec<Entry> {
        let mut known: HashMap<String, Entry> = HashMap::new();
        for e in old {
            known.entry(e.item.id.clone()).or_insert(e);
        }
        items
            .into_iter()
            .map(|mut item| match known.remove(&item.id) {
                Some(e) => {
                    if item.duration_ms.is_none() {
                        item.duration_ms = e.item.duration_ms;
                    }
                    Entry { key: e.key, item, failed: e.failed }
                }
                None => self.entry(item),
            })
            .collect()
    }

    // Replaces the whole queue around the playing song, which carries on
    // untouched. It is found in the new list by its id, since the engine
    // may have moved on to the next song before the app heard of it;
    // `current` is used only when it is not there.
    fn replace_queue(&mut self, items: Vec<QueueItem>, current: usize) {
        if items.is_empty() {
            self.load(items, 0, 0, false);
            return;
        }
        let playing = self
            .mixer
            .as_ref()
            .and_then(|m| m.lane())
            .map(|l| l.current().key())
            .or_else(|| self.current_entry().map(|e| e.key));
        let old = std::mem::take(&mut self.queue);
        self.queue = self.reuse_entries(old, items);
        let found = playing.and_then(|k| self.index_of(k));
        self.current = Some(found.unwrap_or(current.min(self.queue.len() - 1)));
        self.queue_changed();
    }

    fn index_of(&self, key: u64) -> Option<usize> {
        self.queue.iter().position(|e| e.key == key)
    }

    fn current_entry(&self) -> Option<&Entry> {
        self.current.and_then(|c| self.queue.get(c))
    }

    fn apply_volume(&mut self) {
        if let Some(out) = &self.out {
            out.shared.set_volume(if self.muted { 0.0 } else { self.volume });
        }
    }

    fn apply_sound(&mut self) {
        if let Some(m) = &mut self.mixer {
            m.set_sound(&self.settings);
        }
    }

    // The entry after the current one, skipping songs that failed. With
    // `repeat_one` honoured, the current one again.
    fn following(&self, repeat_one: bool) -> Option<usize> {
        let current = self.current?;
        if repeat_one && self.repeat == RepeatMode::One {
            return (!self.queue[current].failed).then_some(current);
        }
        let len = self.queue.len();
        for step in 1..=len {
            let i = current + step;
            let i = if i >= len {
                if self.repeat != RepeatMode::All {
                    return None;
                }
                i % len
            } else {
                i
            };
            if !self.queue[i].failed {
                return Some(i);
            }
        }
        None
    }

    // How a queued song is levelled, following the song before it.
    fn loudness(&self, index: usize, previous: Option<usize>) -> Loudness {
        let item = &self.queue[index].item;
        let before = previous.and_then(|p| self.queue.get(p)).and_then(|e| e.item.album_id.as_ref());
        Loudness {
            stored: item.replay_gain.as_ref().and_then(stored_replay_gain),
            follows_same_album: item.album_id.is_some() && item.album_id.as_ref() == before,
        }
    }

    fn open_deck(&self, index: usize, start_secs: f64) -> Deck {
        let entry = &self.queue[index];
        let http = HttpOptions {
            agent: self.shared.agent.clone(),
            headers: entry.item.headers.iter().map(|h| (h.name.clone(), h.value.clone())).collect(),
            ..Default::default()
        };
        Deck::open(entry.key, entry.item.source.clone(), start_secs, http)
    }

    fn load(&mut self, items: Vec<QueueItem>, start_index: usize, start_ms: u64, play: bool) {
        self.queue = items.into_iter().map(|i| self.entry(i)).collect();
        self.publish_queue();
        if self.queue.is_empty() {
            self.stop(EndReason::Skipped);
            return;
        }
        self.current = Some(start_index.min(self.queue.len() - 1));
        self.playing = play;
        self.start_current(start_ms as f64 / 1_000.0, Transition::Start, EndReason::Skipped);
    }

    // Starts the current entry from `secs`, replacing whatever plays.
    fn start_current(&mut self, secs: f64, transition: Transition, reason: EndReason) {
        let Some(index) = self.current else { return };
        if !self.ensure_output() {
            // With a song already loaded, carry on with this one once a
            // device opens.
            if self.mixer.is_some() {
                self.hold_place(Some(Moment { key: self.queue[index].key, secs }));
            }
            return;
        }
        self.lost_at = None;
        let previous = self.heard.and_then(|k| self.index_of(k));
        let loudness = self.loudness(index, previous);
        let deck = self.open_deck(index, secs);
        let key = self.queue[index].key;
        self.drop_prepared();
        self.flush_output(Moment { key, secs });
        let rate = self.out.as_ref().map(|o| o.opened.rate()).unwrap_or(48_000);
        let base = self.out.as_ref().map(|o| o.written).unwrap_or(0);
        if self.mixer.as_ref().is_none_or(|m| m.rate() != rate) {
            self.mixer = Some(Mixer::new(rate, base, &self.settings, self.pace));
        }
        let mixer = self.mixer.as_mut().expect("mixer just made");
        mixer.clear();
        let lane = Lane::new(deck, loudness, &self.settings.replay_gain, rate);
        mixer.start(lane, transition);
        self.mix_state = MixState::Waiting;
        self.ended = false;
        self.end_reason = reason;
        self.starved_since = None;
        let shared = self.out.as_ref().map(|o| o.shared.clone());
        if let Some(s) = shared {
            s.set_paused(!self.playing);
        }
        if self.playing {
            self.wake_device();
            self.set_state(PlaybackState::Playing);
        } else {
            self.set_state(PlaybackState::Paused);
        }
    }

    fn play(&mut self) {
        if self.queue.is_empty() {
            return;
        }
        self.playing = true;
        if self.mixer.is_none() || self.ended {
            if self.current.is_none() {
                self.current = Some(0);
            }
            self.start_current(0.0, Transition::Start, EndReason::Finished);
            return;
        }
        if self.out.is_none() {
            // The device was lost: open one again, carrying on from where
            // the song was heard.
            self.reopen_output(true);
            return;
        }
        self.wake_device();
        if let Some(out) = &self.out {
            out.shared.set_paused(false);
        }
        self.set_state(if self.buffering { PlaybackState::Buffering } else { PlaybackState::Playing });
    }

    fn pause(&mut self) {
        self.playing = false;
        if let Some(out) = &self.out {
            out.shared.set_paused(true);
        }
        self.starved_since = None;
        if self.mixer.is_some() && !self.ended {
            self.set_state(PlaybackState::Paused);
        }
    }

    fn stop(&mut self, reason: EndReason) {
        if let Some(key) = self.heard.take() {
            self.emit_ended(key, reason);
        }
        self.drop_prepared();
        self.mixer = None;
        self.playing = false;
        self.ended = false;
        if let Some(out) = &self.out {
            out.shared.set_paused(true);
            out.shared.flush_to(out.written);
        }
        self.shared.timeline.clear();
        self.set_state(PlaybackState::Idle);
    }

    fn seek(&mut self, secs: f64) {
        let Some(index) = self.current else { return };
        let key = self.queue[index].key;
        let can_seek = self.mixer.as_ref().and_then(|m| m.lane()).is_some_and(|l| l.current().key() == key);
        if !can_seek || self.ended {
            let reason = if self.ended { EndReason::Finished } else { EndReason::Skipped };
            self.start_current(secs, Transition::Start, reason);
            return;
        }
        self.drop_prepared();
        if let Some(m) = &mut self.mixer {
            m.seek(secs);
        }
        self.mix_state = MixState::Waiting;
        if self.out.is_none() {
            self.hold_place(Some(Moment { key, secs }));
        }
        self.flush_output(Moment { key, secs });
    }

    // Drops what the device has queued, with a short fade, and holds the
    // position at `moment` until the new sound is heard.
    fn flush_output(&mut self, moment: Moment) {
        if let Some(out) = &self.out {
            let heard = self.shared.heard_frame().unwrap_or(0.0);
            out.shared.flush_to(out.written);
            self.shared.timeline.discard_unheard(heard, out.written);
            self.shared.timeline.hold(out.written, moment);
            self.heard_frame = self.heard_frame.max(out.written as f64 - 0.5);
        }
    }

    // After the queue changed around the playing song.
    fn queue_changed(&mut self) {
        if let Some(key) = self.mixer.as_ref().and_then(|m| m.lane()).map(|l| l.current().key()) {
            self.current = self.index_of(key).or(self.current);
        }
        let next_key = self.following(true).map(|i| self.queue[i].key);
        if self.prepared.is_some() && self.prepared != next_key {
            self.drop_prepared();
        }
        if let Some(lane) = self.mixer.as_mut().and_then(|m| m.lane_mut())
            && next_key.is_some()
        {
            lane.last = false;
        }
        self.publish_queue();
    }

    // Forgets the song lined up after the current one.
    fn drop_prepared(&mut self) {
        self.prepared = None;
        if let Some(m) = &mut self.mixer {
            drop(m.cancel_planned_fade());
            if let Some(lane) = m.lane_mut() {
                lane.set_next(None, Loudness::default());
                lane.last = false;
            }
        }
    }

    fn manage_queue(&mut self) {
        let Some(mixer) = &mut self.mixer else { return };
        for (key, failure) in mixer.take_failures() {
            if let Some(i) = self.queue.iter().position(|e| e.key == key) {
                self.queue[i].failed = true;
                let id = self.queue[i].item.id.clone();
                let _ = self.events.send(EngineEvent::Error {
                    kind: failure.kind,
                    message: failure.message.clone(),
                    item_id: Some(id),
                });
            }
            if self.prepared == Some(key) {
                self.prepared = None;
                if let Some(lane) = mixer.lane_mut() {
                    lane.set_next(None, Loudness::default());
                }
            }
        }
        let Some(lane) = mixer.lane() else { return };
        // Keep what the decoder found out, for events and positions.
        for deck in [Some(lane.current()), lane.next()].into_iter().flatten() {
            if !self.infos.contains_key(&deck.key())
                && let Some(info) = deck.info()
            {
                if let Some(i) = self.queue.iter().position(|e| e.key == deck.key())
                    && self.queue[i].item.duration_ms.is_none()
                {
                    self.queue[i].item.duration_ms = info.duration_ms;
                }
                self.infos.insert(deck.key(), info);
            }
        }
        // Follow the lane onto the song it plays now.
        let lane_key = lane.current().key();
        if self.current_entry().map(|e| e.key) != Some(lane_key)
            && let Some(i) = self.index_of(lane_key)
        {
            self.current = Some(i);
            if self.prepared == Some(lane_key) {
                self.prepared = None;
            }
        }
        if self.prepared.is_none() {
            self.prepare_next();
        }
    }

    // Lines up the next song, for a gapless join or a crossfade.
    fn prepare_next(&mut self) {
        let Some(current) = self.current else { return };
        let Some(mixer) = &self.mixer else { return };
        let Some(lane) = mixer.lane() else { return };
        if lane.next().is_some() || mixer.has_planned_fade() {
            return;
        }
        let next = if self.stop_after_current { None } else { self.following(true) };
        let Some(next) = next else {
            if let Some(lane) = self.mixer.as_mut().and_then(|m| m.lane_mut()) {
                lane.last = true;
            }
            return;
        };
        let deck_status = lane.current().status();
        let duration = self
            .infos
            .get(&self.queue[current].key)
            .and_then(|i| i.duration_ms)
            .or(self.queue[current].item.duration_ms);
        let position = lane.position().map(|(_, s)| s).unwrap_or(0.0);
        let remaining = duration.map(|d| d as f64 / 1_000.0 - position);
        let failed = matches!(deck_status, DeckStatus::Failed(_));
        let window = PREPARE_SECS + self.crossfade_ms as f64 / 1_000.0 * self.pace.speed as f64;
        if !failed && remaining.is_some_and(|r| r > window) {
            return;
        }
        let deck = self.open_deck(next, 0.0);
        let loudness = self.loudness(next, Some(current));
        let key = self.queue[next].key;
        self.prepared = Some(key);

        let song = |e: &Entry, duration: Option<u64>| FadeSong {
            album_id: e.item.album_id.clone(),
            album_order: e.item.album_order,
            duration_ms: duration.unwrap_or(0),
        };
        let current_song = song(&self.queue[current], duration);
        let next_song = song(&self.queue[next], self.queue[next].item.duration_ms);
        let fade_ms = crossfade_length(
            &current_song,
            Some(&next_song),
            self.crossfade_ms as u64,
            self.repeat == RepeatMode::One,
            self.stop_after_current,
        );
        let speed = self.pace.speed as f64;
        let rate = mixer.rate() as f64;
        let mixer = self.mixer.as_mut().expect("mixer");
        let current_key = self.queue[current].key;
        if fade_ms > 0 && !failed {
            let total = current_song.duration_ms as f64 / 1_000.0;
            let blend = fade_ms as f64 / 1_000.0 * speed;
            let at = total - blend;
            if position <= at {
                mixer.plan_fade(current_key, at, (blend * rate) as u64, deck, loudness);
                return;
            }
            // Late, after a seek near the end: blend over what is left.
            let left = total - position;
            if left / speed * 1_000.0 >= SHORTEST_FADE_MS as f64 {
                mixer.plan_fade(current_key, position, (left * rate) as u64, deck, loudness);
                return;
            }
        }
        if let Some(lane) = mixer.lane_mut() {
            lane.set_next(Some(deck), loudness);
        }
    }

    // Keeps the device's ring topped up.
    fn produce(&mut self) {
        let (Some(out), Some(mixer)) = (&mut self.out, &mut self.mixer) else { return };
        let mut buffered = out.written.saturating_sub(out.shared.read_frames());
        while buffered < out.target {
            let room = out.producer.slots() / 2;
            let n = ((out.target - buffered) as usize).min(self.block.len() / 2).min(room);
            if n == 0 {
                break;
            }
            let (made, state) = mixer.render(&mut self.block[..n * 2], &mut self.markers);
            self.mix_state = state;
            if !self.markers.is_empty() {
                self.shared.timeline.push(&self.markers);
                self.markers.clear();
            }
            if made == 0 {
                break;
            }
            if out.producer.push_entire_slice(&self.block[..made * 2]).is_err() {
                log::error!("the ring had less room than it said");
                break;
            }
            out.written += made as u64;
            buffered += made as u64;
            if made < n {
                break;
            }
        }
        out.shared.set_expect_sound(self.playing && self.mix_state == MixState::Playing);
    }

    // Turns what is heard into events, and notices buffering and the end.
    fn follow_heard(&mut self) {
        let Some(out) = &self.out else { return };
        let now = Instant::now();
        let heard = out.shared.clock.heard(now);
        let buffered = out.written.saturating_sub(out.shared.read_frames());
        let written = out.written;
        let rate = out.opened.rate() as u64;
        if let Some(frame) = heard {
            self.crossed.clear();
            self.shared.timeline.crossed(self.heard_frame, frame, &mut self.crossed);
            let crossed = std::mem::take(&mut self.crossed);
            for m in &crossed {
                if let Some(t) = m.transition {
                    self.on_transition(m.key, t, rate);
                }
            }
            self.crossed = crossed;
            self.heard_frame = self.heard_frame.max(frame);
        }

        // Buffering: playing, but the ring has run dry waiting on a song.
        if self.playing && self.mixer.is_some() && !self.ended {
            let starved = self.mix_state == MixState::Waiting && buffered < rate / 200;
            if starved {
                let since = *self.starved_since.get_or_insert(now);
                if !self.buffering && now - since > STARVED_GRACE {
                    self.buffering = true;
                    let id = self.current_entry().map(|e| e.item.id.clone());
                    self.emit(EngineEvent::Buffering { item_id: id });
                    self.set_state(PlaybackState::Buffering);
                }
            } else {
                self.starved_since = None;
                if self.buffering && buffered > rate / 20 {
                    self.buffering = false;
                    let id = self.current_entry().map(|e| e.item.id.clone());
                    self.emit(EngineEvent::Ready { item_id: id });
                    self.set_state(PlaybackState::Playing);
                }
            }
        }

        // The end: everything mixed has been played out and heard.
        if !self.ended
            && self.mixer.is_some()
            && self.mix_state == MixState::Ended
            && buffered == 0
            && heard.is_none_or(|h| h >= written as f64 - 1.0)
        {
            self.on_end();
        }

        // Not for a song skipped away from while the next one opens: its
        // sound is gone, and the app took the word as the song going on
        // and went back to it. Through a join or a crossfade the song
        // heard is still playing out, and is told of as ever.
        let left = self.mix_state == MixState::Waiting && self.current_entry().map(|e| e.key) != self.heard;
        if self.playing
            && !left
            && !self.tick.is_zero()
            && now - self.last_tick >= self.tick
            && let Some(key) = self.heard
        {
            self.last_tick = now;
            let position = self.shared.position();
            if let Some(i) = self.index_of(key) {
                let id = self.queue[i].item.id.clone();
                self.emit(EngineEvent::Position { item_id: id, position_ms: position.position_ms });
            }
        }
    }

    fn id_of(&self, key: u64) -> Option<String> {
        self.index_of(key).map(|i| self.queue[i].item.id.clone())
    }

    fn emit_ended(&self, key: u64, reason: EndReason) {
        if let Some(id) = self.id_of(key) {
            self.emit(EngineEvent::TrackEnded { item_id: id, reason });
        }
    }

    fn on_transition(&mut self, key: u64, transition: Transition, rate: u64) {
        let previous = self.heard;
        match transition {
            Transition::Seek if previous == Some(key) => return,
            // A seek before the song was first heard (its start was never
            // heard, or never mixed) is where it starts being heard.
            Transition::Start | Transition::Seek => {
                if let Some(p) = previous {
                    self.emit_ended(p, self.end_reason);
                }
            }
            Transition::Gapless => {
                if let Some(p) = previous {
                    self.emit_ended(p, EndReason::Finished);
                    if let (Some(from), Some(to)) = (self.id_of(p), self.id_of(key)) {
                        self.emit(EngineEvent::GaplessTransition { from_id: from, to_id: to });
                    }
                }
            }
            Transition::Crossfade { from, frames } => {
                self.emit_ended(from, EndReason::Finished);
                if let (Some(from), Some(to)) = (self.id_of(from), self.id_of(key)) {
                    let speed = self.pace.speed as f64;
                    let duration_ms = (frames as f64 / rate as f64 / speed * 1_000.0) as u64;
                    self.emit(EngineEvent::CrossfadeStarted { from_id: from, to_id: to, duration_ms });
                }
            }
        }
        self.heard = Some(key);
        self.end_reason = EndReason::Skipped;
        if let Some(i) = self.index_of(key) {
            let info = self.infos.get(&key).cloned();
            let id = self.queue[i].item.id.clone();
            self.emit(EngineEvent::TrackStarted { item_id: id, index: i as u32, info });
        }
        self.publish_queue();
    }

    fn on_end(&mut self) {
        self.ended = true;
        if let Some(key) = self.heard.take() {
            self.emit_ended(key, EndReason::Finished);
        }
        if self.stop_after_current {
            // Pause at the end of the song, ready at the start of the next.
            self.stop_after_current = false;
            if let Some(next) = self.following(true) {
                self.current = Some(next);
                self.playing = false;
                self.start_current(0.0, Transition::Start, EndReason::Finished);
                return;
            }
        }
        self.playing = false;
        if let Some(out) = &self.out {
            out.shared.set_paused(true);
        }
        self.emit(EngineEvent::QueueEnded);
        self.set_state(PlaybackState::Ended);
    }

    // Opens the device if it is not open. False if there is none.
    fn ensure_output(&mut self) -> bool {
        if self.out.is_none() {
            self.open_output(true);
        }
        self.out.is_some()
    }

    // Opens the chosen device, or the default. `report` sends an error
    // when none opens; the quiet retries after a lost device leave it out.
    fn open_output(&mut self, report: bool) {
        let choice = self.device_choice.clone();
        let result = self.try_open(choice.as_deref());
        let result = match result {
            Err(e) if choice.is_some() => {
                log::warn!("could not open the chosen device ({e}); using the default");
                self.try_open(None)
            }
            other => other,
        };
        match result {
            Ok(()) => {
                let device = self.out.as_ref().map(|o| o.opened.device.clone());
                let format = self.out.as_ref().map(|o| o.opened.format.clone());
                let mut status = self.lock_status();
                status.device = device.clone();
                status.format = format.clone();
                drop(status);
                self.emit(EngineEvent::DeviceChanged { device, format });
            }
            Err(e) if report => {
                self.emit(EngineEvent::Error { kind: e.kind, message: e.message, item_id: None });
            }
            Err(e) => log::info!("still no sound device: {e}"),
        }
    }

    fn try_open(&mut self, id: Option<&str>) -> Result<(), crate::error::Failure> {
        let volume = if self.muted { 0.0 } else { self.volume };
        let paused = !self.playing;
        let mut made: Option<(rtrb::Producer<f32>, Arc<OutputShared>)> = None;
        let opened = self.driver.open(id, &mut |rate, channels| {
            let (producer, consumer) = ring((rate as f64 * RING_SECS) as usize);
            let shared = Arc::new(OutputShared::new(rate));
            shared.set_volume(volume);
            shared.set_paused(paused);
            made = Some((producer, shared.clone()));
            Renderer::new(consumer, shared, rate, channels)
        })?;
        let (producer, shared) = made.expect("renderer made on open");
        *self.shared.output.lock().unwrap_or_else(|e| e.into_inner()) = Some(shared.clone());
        let target = (opened.rate() as f64 * RING_TARGET_SECS) as u64;
        self.out = Some(Out { opened, shared, producer, written: 0, target });
        self.heard_frame = 0.0;
        self.device_running = true;
        self.silent_since = None;
        Ok(())
    }

    // Moves to another device (or the new default), carrying on from what
    // was heard; with no device open, since it was lost, from what was
    // heard last.
    fn reopen_output(&mut self, report: bool) {
        let heard = self.shared.timeline.at(self.shared.heard_frame()).or(self.lost_at);
        self.driver.close();
        self.out = None;
        *self.shared.output.lock().unwrap_or_else(|e| e.into_inner()) = None;
        self.open_output(report);
        if self.mixer.is_none() || self.ended {
            self.lost_at = None;
            return;
        }
        if self.out.is_none() {
            self.hold_place(heard);
            return;
        }
        self.lost_at = None;
        // The ring and its clock start over on the new device.
        self.shared.timeline.clear();
        self.mixer = None;
        let resume = heard.and_then(|m| self.index_of(m.key).map(|i| (i, m.secs)));
        if let Some((index, secs)) = resume {
            self.current = Some(index);
            self.start_current(secs, Transition::Seek, EndReason::Skipped);
        } else {
            self.start_current(0.0, Transition::Start, EndReason::Skipped);
        }
    }

    // With no device open: keeps where the song is, to carry on from there
    // once one opens, and shows it as the position meanwhile.
    fn hold_place(&mut self, moment: Option<Moment>) {
        self.lost_at = moment;
        self.shared.timeline.clear();
        if let Some(m) = moment {
            self.shared.timeline.hold(0, m);
        }
    }

    fn wake_device(&mut self) {
        if !self.device_running {
            self.driver.set_running(true);
            self.device_running = true;
        }
        self.silent_since = None;
    }

    fn check_device(&mut self) {
        let events = self.driver.take_events();
        for event in events {
            match event {
                DeviceEvent::Lost(why) => {
                    log::warn!("sound device lost: {why}");
                    self.reopen_output(true);
                }
                DeviceEvent::Rerouted => {
                    // The same stream, so the same format, on another device.
                    let device = self.driver.default_device();
                    let format = self.out.as_ref().map(|o| o.opened.format.clone());
                    self.lock_status().device = device.clone();
                    self.emit(EngineEvent::DeviceChanged { device, format });
                }
            }
        }
        let Some(out) = &self.out else {
            // Lost, and no device would open: try again now and then while
            // a song is meant to be playing, without a fresh error each time.
            if self.playing
                && self.mixer.is_some()
                && !self.ended
                && self.last_default_check.elapsed() > DEFAULT_DEVICE_CHECK
            {
                self.last_default_check = Instant::now();
                self.reopen_output(false);
            }
            return;
        };
        // Following the system default: move when it changes.
        if self.device_choice.is_none() && self.last_default_check.elapsed() > DEFAULT_DEVICE_CHECK {
            self.last_default_check = Instant::now();
            let current = out.opened.device.id.clone();
            if let Some(default) = self.driver.default_device()
                && !current.is_empty()
                && default.id != current
            {
                log::info!("default device is now {}", default.name);
                self.reopen_output(true);
                return;
            }
        }
        // A long pause lets the device sleep.
        let Some(out) = &self.out else { return };
        if !self.playing && out.shared.is_silent() {
            let since = *self.silent_since.get_or_insert(Instant::now());
            if self.device_running && since.elapsed() > IDLE_DEVICE_AFTER {
                self.driver.set_running(false);
                self.device_running = false;
            }
        } else {
            self.silent_since = None;
        }
    }

    fn lock_status(&self) -> MutexGuard<'_, Status> {
        self.shared.lock_status()
    }

    fn publish_queue(&self) {
        let queue: Vec<QueueEntryView> = self
            .queue
            .iter()
            .map(|e| QueueEntryView { key: e.key, id: e.item.id.clone(), duration_ms: e.item.duration_ms })
            .collect();
        let mut status = self.lock_status();
        status.queue = queue;
        status.heard = self.heard;
    }

    fn publish(&self) {
        let info = self.heard.and_then(|k| self.infos.get(&k).cloned());
        let mut status = self.lock_status();
        status.state = self.state;
        status.volume = self.volume;
        status.heard = self.heard;
        if status.info != info {
            status.info = info;
        }
        // Durations learned from the decoder.
        for (view, entry) in status.queue.iter_mut().zip(&self.queue) {
            if view.key == entry.key {
                view.duration_ms = entry.item.duration_ms;
            }
        }
    }

    fn shutdown(&mut self) {
        self.mixer = None;
        self.driver.close();
        self.out = None;
        *self.shared.output.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}
