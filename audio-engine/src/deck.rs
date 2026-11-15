//! One song being decoded on its own thread, a little ahead of playback.
//! Opening, network waits and decoding all happen there, so the mixing
//! thread never waits on any of them.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::Duration;

use crate::decode::{Decoder, TrackInfo};
use crate::error::Failure;
use crate::fifo::Fifo;
use crate::source::{
    self,
    http::{HttpOptions, TitleSlot},
    icy::StreamMeta,
};

/// How many times one song's time-seeking stream may be opened again after
/// it broke off or its server answered busy, before the song fails.
const REOPENS: u32 = 3;

/// How much decoded sound a deck keeps ready, in seconds.
const AHEAD_SECS: f64 = 0.75;

/// What a deck is doing.
#[derive(Clone, Debug, PartialEq)]
pub enum DeckStatus {
    Opening,
    Playing,
    /// Decoded to the end; what is buffered can still be read.
    Ended,
    Failed(Failure),
}

/// What one read from a deck gave.
#[derive(Clone, Debug, PartialEq)]
pub struct DeckRead {
    /// Frames copied out.
    pub frames: usize,
    /// Where the first of them sits in the song, in seconds.
    pub start_secs: f64,
    /// Nothing more will come: the song is over or failed.
    pub finished: bool,
    pub failure: Option<Failure>,
}

struct State {
    pcm: Fifo,
    /// How much sound to keep ready once playing, in frames.
    steady: usize,
    head_secs: f64,
    status: DeckStatus,
    info: Option<TrackInfo>,
    seek: Option<f64>,
    seek_generation: u64,
    stop: bool,
    capacity: usize,
    // A live stream's titles, by the time in it where they start.
    titles: VecDeque<(f64, StreamMeta)>,
}

struct Shared {
    state: Mutex<State>,
    wake: Condvar,
    starved: Arc<AtomicBool>,
    cancel: Arc<AtomicBool>,
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// A song decoding in the background. Dropping it stops the thread.
pub struct Deck {
    shared: Arc<Shared>,
    key: u64,
    live: bool,
}

impl Deck {
    /// Starts opening `source` (a path or an address) at `start_secs`.
    pub fn open(key: u64, source: String, start_secs: f64, http: HttpOptions) -> Deck {
        Deck::open_ahead(key, source, start_secs, http, AHEAD_SECS)
    }

    /// The same, decoding up to `first_secs` ahead before the first read
    /// (never less than usual), so playback can wait for that much. A live
    /// stream always starts where its server is.
    pub fn open_ahead(
        key: u64,
        source: String,
        start_secs: f64,
        mut http: HttpOptions,
        first_secs: f64,
    ) -> Deck {
        let starved = Arc::new(AtomicBool::new(false));
        let cancel = Arc::new(AtomicBool::new(false));
        let live = http.live;
        let start_secs = if live { 0.0 } else { start_secs };
        http.starved = Some(starved.clone());
        http.cancel = Some(cancel.clone());
        http.titles = live.then(|| Arc::new(TitleSlot::default()));
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                pcm: Fifo::default(),
                steady: 0,
                head_secs: start_secs,
                status: DeckStatus::Opening,
                info: None,
                seek: (start_secs > 0.0).then_some(start_secs),
                seek_generation: 0,
                stop: false,
                capacity: 0,
                titles: VecDeque::new(),
            }),
            wake: Condvar::new(),
            starved,
            cancel,
        });
        let worker = shared.clone();
        let first = first_secs.max(AHEAD_SECS);
        let spawned = thread::Builder::new().name("octo-deck".into()).spawn(move || {
            let watch = worker.clone();
            // Whatever goes wrong in there, the player hears of it.
            if std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| run(worker, source, http, first)))
                .is_err()
            {
                let mut state = watch.lock();
                let broke = Failure::new(crate::error::ErrorKind::Other, "decoding broke down");
                state.status = DeckStatus::Failed(broke);
                watch.wake.notify_all();
            }
        });
        if let Err(e) = spawned {
            let mut state = shared.lock();
            state.status = DeckStatus::Failed(Failure::new(crate::error::ErrorKind::Other, e.to_string()));
        }
        Deck { shared, key, live }
    }

    /// Which queue entry this deck plays.
    pub fn key(&self) -> u64 {
        self.key
    }

    /// Whether this is a live stream, which is never sought.
    pub fn is_live(&self) -> bool {
        self.live
    }

    /// The newest title of a live stream that starts by `heard_secs` into
    /// it, when there is one not taken before.
    pub fn take_title(&self, heard_secs: f64) -> Option<StreamMeta> {
        let mut state = self.shared.lock();
        let mut latest = None;
        while state.titles.front().is_some_and(|(at, _)| *at <= heard_secs) {
            latest = state.titles.pop_front().map(|(_, title)| title);
        }
        latest
    }

    pub fn info(&self) -> Option<TrackInfo> {
        self.shared.lock().info.clone()
    }

    pub fn status(&self) -> DeckStatus {
        self.shared.lock().status.clone()
    }

    /// Whether the song is open and has sound ready, or has finished.
    pub fn is_ready(&self) -> bool {
        let state = self.shared.lock();
        !state.pcm.is_empty() || matches!(state.status, DeckStatus::Ended | DeckStatus::Failed(_))
    }

    /// Whether a read is waiting on the network.
    pub fn is_starved(&self) -> bool {
        self.shared.starved.load(Ordering::Relaxed)
    }

    /// Frames buffered and ready.
    pub fn buffered(&self) -> usize {
        self.shared.lock().pcm.len() / 2
    }

    /// Whether as much sound is ready as the deck decodes ahead, or the
    /// song has been decoded to its end (or failed).
    pub fn is_filled(&self) -> bool {
        let state = self.shared.lock();
        let done = matches!(state.status, DeckStatus::Ended | DeckStatus::Failed(_));
        done || (state.capacity > 0 && state.pcm.len() / 2 >= state.capacity)
    }

    /// Takes up to `out.len() / 2` stereo frames.
    pub fn read(&self, out: &mut [f32]) -> DeckRead {
        let mut state = self.shared.lock();
        let rate = state.info.as_ref().map(|i| i.sample_rate).unwrap_or(44_100) as f64;
        let start_secs = state.head_secs;
        let samples = state.pcm.pop_into(out);
        let frames = samples / 2;
        state.head_secs += frames as f64 / rate;
        // Once playing, only the usual amount is kept ahead.
        if frames > 0 && state.steady > 0 {
            state.capacity = state.steady;
        }
        // Wake the decoder once half the buffer is free, not on every read.
        if frames > 0 && state.pcm.len() / 2 < state.capacity / 2 {
            self.shared.wake.notify_all();
        }
        let failure = match &state.status {
            DeckStatus::Failed(f) => Some(f.clone()),
            _ => None,
        };
        let done = matches!(state.status, DeckStatus::Ended | DeckStatus::Failed(_));
        DeckRead { frames, start_secs, finished: done && state.pcm.is_empty(), failure }
    }

    /// Jumps to `secs`. What is buffered is dropped at once. A live stream
    /// stays where it is.
    pub fn seek(&self, secs: f64) {
        if self.live {
            return;
        }
        let mut state = self.shared.lock();
        state.seek = Some(secs.max(0.0));
        state.seek_generation += 1;
        state.pcm.clear();
        state.head_secs = secs.max(0.0);
        if state.status == DeckStatus::Ended {
            state.status = DeckStatus::Playing;
        }
        self.shared.wake.notify_all();
    }
}

impl Drop for Deck {
    fn drop(&mut self) {
        self.shared.cancel.store(true, Ordering::Relaxed);
        let mut state = self.shared.lock();
        state.stop = true;
        self.shared.wake.notify_all();
    }
}

fn run(shared: Arc<Shared>, source: String, http: HttpOptions, first_secs: f64) {
    // A transcode is opened at its start time rather than sought into.
    let by_time = source::seeks_by_time(&source);
    let mut offset = 0.0;
    // How often this song's stream has been opened again at a time.
    let mut reopens = 0u32;
    // After an opening again at a whole second, the time the song had
    // reached: the new stream's sound before it is heard already.
    let mut skip_until: Option<f64> = None;
    let opened = if by_time {
        let start = shared.lock().seek.take().unwrap_or(0.0);
        let (address, landed) = source::at_time(&source, start);
        offset = landed;
        shared.lock().head_secs = landed;
        match source::open(&address, http.clone()).and_then(Decoder::open) {
            // Broken off before it could be read: opened again at its time,
            // one of the song's reopens.
            Err(f) if f.reopen => {
                open_at_time(&source, &http, start, &mut reopens, false).map(|(decoder, at)| {
                    offset = at;
                    shared.lock().head_secs = at;
                    decoder
                })
            }
            opened => opened,
        }
    } else {
        match source::open(&source, http.clone()).and_then(Decoder::open) {
            // The server let go of the song's file while it was opened (it
            // refused a later part the reader asked for): opened again from
            // its first byte, one of the song's reopens.
            Err(f) if f.reopen => {
                reopen_from_start(&source, &http, 0.0, &mut reopens).map(|(decoder, _)| decoder)
            }
            opened => opened,
        }
    };
    let mut decoder = match opened {
        Ok(d) => d,
        Err(failure) => {
            log::warn!("could not open {source}: {failure}");
            let mut state = shared.lock();
            state.status = DeckStatus::Failed(failure);
            shared.wake.notify_all();
            return;
        }
    };
    {
        let mut state = shared.lock();
        let rate = decoder.sample_rate() as f64;
        state.steady = (rate * AHEAD_SECS) as usize;
        state.capacity = (rate * first_secs) as usize;
        state.pcm = Fifo::with_capacity(state.capacity * 2 + 16_384);
        state.info = Some(decoder.info().clone());
        state.status = DeckStatus::Playing;
        shared.wake.notify_all();
    }
    let mut block = Vec::new();
    loop {
        let generation;
        {
            let mut state = shared.lock();
            loop {
                if state.stop {
                    return;
                }
                if let Some(target) = state.seek.take() {
                    let generation = state.seek_generation;
                    drop(state);
                    let landed = if by_time {
                        // Opened again at the time, as the server makes it from there.
                        match open_at_time(&source, &http, target, &mut reopens, true) {
                            Ok((fresh, at)) => {
                                decoder = fresh;
                                offset = at;
                                skip_until = None;
                                Ok(at)
                            }
                            Err(f) => Err(f),
                        }
                    } else {
                        match decoder.seek(target) {
                            // The server let go of the song's file: opened again from
                            // its first byte and sought there.
                            Err(f) if f.reopen => {
                                match reopen_from_start(&source, &http, target, &mut reopens) {
                                    Ok((fresh, at)) => {
                                        decoder = fresh;
                                        skip_until = None;
                                        Ok(at)
                                    }
                                    Err(f) => Err(f),
                                }
                            }
                            landed => landed,
                        }
                    };
                    state = shared.lock();
                    if state.seek_generation == generation {
                        match landed {
                            Ok(at) => state.head_secs = at,
                            Err(f) => {
                                log::warn!("seek failed: {f}");
                                state.status = DeckStatus::Failed(f);
                            }
                        }
                        state.pcm.clear();
                    }
                    continue;
                }
                let full = state.pcm.len() / 2 >= state.capacity;
                let idle = matches!(state.status, DeckStatus::Ended | DeckStatus::Failed(_));
                if full || idle {
                    state = shared
                        .wake
                        .wait_timeout(state, Duration::from_millis(500))
                        .unwrap_or_else(|e| e.into_inner())
                        .0;
                    continue;
                }
                break;
            }
            generation = state.seek_generation;
        }
        let result = decoder.next_block(&mut block);
        let mut state = shared.lock();
        if state.seek_generation != generation || state.seek.is_some() {
            // A seek came in while decoding: this block is from before it.
            continue;
        }
        match result {
            Ok(true) => {
                let rate = decoder.sample_rate() as f64;
                let mut block_secs = offset + decoder.position_secs() - (block.len() / 2) as f64 / rate;
                if let Some(until) = skip_until {
                    let frames = block.len() / 2;
                    let early = (((until - block_secs) * rate).round().max(0.0) as usize).min(frames);
                    if early == frames {
                        continue;
                    }
                    block.drain(..early * 2);
                    block_secs += early as f64 / rate;
                    skip_until = None;
                }
                if state.pcm.is_empty() {
                    state.head_secs = block_secs;
                }
                state.pcm.push(&block);
                // A title the stream reached while this was read starts with it.
                if let Some(title) = http.titles.as_ref().and_then(|slot| slot.take()) {
                    state.titles.push_back((block_secs, title));
                }
            }
            Ok(false) => state.status = DeckStatus::Ended,
            Err(f) if by_time && f.reopen => {
                // Made on the way and broken off: opened again at the time it
                // reached, where its server makes it from.
                let resume = offset + decoder.position_secs();
                drop(state);
                log::warn!("the stream broke off at {resume:.1} s ({f}); opening it again there");
                let reopened = open_at_time(&source, &http, resume, &mut reopens, false);
                let mut state = shared.lock();
                if state.seek_generation == generation && state.seek.is_none() {
                    match reopened {
                        Ok((fresh, at)) => {
                            decoder = fresh;
                            offset = at;
                            skip_until = Some(resume);
                        }
                        Err(failure) => {
                            log::warn!("decoding stopped: {failure}");
                            state.status = DeckStatus::Failed(failure);
                        }
                    }
                }
            }
            Err(f) if f.reopen => {
                // The server let go of the file this song was read from (it
                // refused a later part as busy): opened again from its first
                // byte, the file the server has now, and on from where it was.
                let resume = offset + decoder.position_secs();
                drop(state);
                log::warn!("the server let go of the song's file at {resume:.1} s ({f}); opening it again");
                let reopened = reopen_from_start(&source, &http, resume, &mut reopens);
                let mut state = shared.lock();
                if state.seek_generation == generation && state.seek.is_none() {
                    match reopened {
                        Ok((fresh, _)) => {
                            decoder = fresh;
                            // A seek can land a little before where the song was: what is heard
                            // already is left out.
                            skip_until = Some(resume);
                        }
                        Err(failure) => {
                            log::warn!("decoding stopped: {failure}");
                            state.status = DeckStatus::Failed(failure);
                        }
                    }
                }
            }
            Err(f) => {
                log::warn!("decoding stopped: {f}");
                state.status = DeckStatus::Failed(f);
            }
        }
        shared.wake.notify_all();
    }
}

// Opens a song's own address again from its first byte and seeks it to
// `secs`: what a server that let go of the file a play was reading asks of
// its player (Octo answers a later part 503 when a kept copy is gone). One of
// the song's reopens.
fn reopen_from_start(
    source: &str,
    http: &HttpOptions,
    secs: f64,
    reopens: &mut u32,
) -> Result<(Decoder, f64), Failure> {
    loop {
        if *reopens >= REOPENS {
            return Err(Failure::new(crate::error::ErrorKind::Network, "the stream kept breaking off"));
        }
        *reopens += 1;
        let opened = source::open(source, http.clone()).and_then(Decoder::open).and_then(|mut decoder| {
            let at = if secs > 0.0 { decoder.seek(secs)? } else { 0.0 };
            Ok((decoder, at))
        });
        match opened {
            Ok(opened) => return Ok(opened),
            // A server busy for a moment (restarting, say): a pause, then another try, while the
            // song has reopens left.
            Err(f) if mendable(&f) && *reopens < REOPENS => {
                log::warn!("could not open the song again at {secs:.1} s ({f}); trying again");
                if !pause(http) {
                    return Err(f);
                }
            }
            Err(f) => return Err(f),
        }
    }
}

// Opens a time-seeking stream at `secs`. A failure an opening again can mend
// (the stream broke off, or its server answered busy) is tried again after
// `http.reopen_wait`, while the song has reopens left. With `free_first`
// (a seek) the first try is not one of them; after a break every try is.
fn open_at_time(
    source: &str,
    http: &HttpOptions,
    secs: f64,
    reopens: &mut u32,
    free_first: bool,
) -> Result<(Decoder, f64), Failure> {
    let mut counted = !free_first;
    loop {
        if counted {
            if *reopens >= REOPENS {
                return Err(Failure::new(crate::error::ErrorKind::Network, "the stream kept breaking off"));
            }
            *reopens += 1;
        }
        let (address, at) = source::seek_at_time(source, secs);
        match source::open(&address, http.clone()).and_then(Decoder::open) {
            Ok(decoder) => return Ok((decoder, at)),
            Err(f) if mendable(&f) => {
                if *reopens >= REOPENS {
                    return Err(f);
                }
                log::warn!("could not open the stream again at {secs:.1} s ({f}); trying again");
                if !pause(http) {
                    return Err(f);
                }
                counted = true;
            }
            Err(f) => return Err(f),
        }
    }
}

// Whether opening a time-seeking stream again can mend this failure.
fn mendable(failure: &Failure) -> bool {
    failure.reopen || failure.status == Some(503)
}

// Waits `http.reopen_wait`, false when the deck is let go meanwhile.
fn pause(http: &HttpOptions) -> bool {
    let until = std::time::Instant::now() + http.reopen_wait;
    while std::time::Instant::now() < until {
        if http.cancel.as_ref().is_some_and(|c| c.load(Ordering::Relaxed)) {
            return false;
        }
        thread::sleep(Duration::from_millis(10).min(http.reopen_wait));
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::fixtures::*;
    use std::time::Instant;

    fn wait_ready(deck: &Deck) {
        let until = Instant::now() + Duration::from_secs(5);
        while !deck.is_ready() {
            assert!(Instant::now() < until, "deck never got ready");
            thread::sleep(Duration::from_millis(2));
        }
    }

    fn read_all(deck: &Deck) -> (Vec<f32>, Option<Failure>) {
        let mut all = Vec::new();
        let mut buf = vec![0.0; 2048];
        let until = Instant::now() + Duration::from_secs(10);
        loop {
            let r = deck.read(&mut buf);
            all.extend_from_slice(&buf[..r.frames * 2]);
            if r.finished {
                return (all, r.failure);
            }
            if r.frames == 0 {
                assert!(Instant::now() < until);
                thread::sleep(Duration::from_millis(1));
            }
        }
    }

    #[test]
    fn decodes_ahead_and_to_the_end() {
        let dir = temp_dir();
        let path = dir.join("a.wav");
        let samples = sine(440.0, 44_100, 2, 0, 100_000, 0.5);
        write_wav(&path, 44_100, 2, &samples);
        let deck = Deck::open(7, path.to_string_lossy().into(), 0.0, HttpOptions::default());
        wait_ready(&deck);
        assert_eq!(deck.key(), 7);
        assert_eq!(deck.info().unwrap().sample_rate, 44_100);
        let (all, failure) = read_all(&deck);
        assert!(failure.is_none());
        assert_eq!(all.len(), samples.len());
        assert_eq!(all[1234], samples[1234] as f32 / 32768.0);
    }

    #[test]
    fn opens_at_a_position_and_seeks() {
        let dir = temp_dir();
        let path = dir.join("b.flac");
        let rate = 48_000;
        let samples: Vec<i16> = (0..rate * 2).flat_map(|n| [(n % 20_000) as i16, 0]).collect();
        write_flac(&path, rate, 2, &samples, &[]);
        let deck = Deck::open(1, path.to_string_lossy().into(), 1.0, HttpOptions::default());
        wait_ready(&deck);
        let mut buf = vec![0.0; 64];
        let r = deck.read(&mut buf);
        assert!((r.start_secs - 1.0).abs() < 1e-9);
        assert_eq!(buf[0], (48_000 % 20_000) as f32 / 32768.0);

        deck.seek(0.5);
        wait_ready(&deck);
        let r = deck.read(&mut buf);
        assert!((r.start_secs - 0.5).abs() < 1e-9, "{}", r.start_secs);
        assert_eq!(buf[0], (24_000 % 20_000) as f32 / 32768.0);
    }

    #[test]
    fn decodes_further_ahead_before_the_first_read_then_as_usual() {
        let dir = temp_dir();
        let path = dir.join("long.wav");
        write_wav(&path, 48_000, 2, &sine(440.0, 48_000, 2, 0, 48_000 * 4, 0.5));
        let deck = Deck::open_ahead(1, path.to_string_lossy().into(), 0.0, HttpOptions::default(), 2.0);
        let until = Instant::now() + Duration::from_secs(5);
        while !deck.is_filled() {
            assert!(Instant::now() < until, "never filled");
            thread::sleep(Duration::from_millis(2));
        }
        assert!(deck.buffered() >= 96_000 - 1, "{}", deck.buffered());
        // A song shorter than that is ready once decoded to its end.
        let short = dir.join("short.wav");
        write_wav(&short, 48_000, 2, &sine(440.0, 48_000, 2, 0, 4_800, 0.5));
        let tiny = Deck::open_ahead(2, short.to_string_lossy().into(), 0.0, HttpOptions::default(), 2.0);
        let until = Instant::now() + Duration::from_secs(5);
        while !tiny.is_filled() {
            assert!(Instant::now() < until, "a short song never counted as ready");
            thread::sleep(Duration::from_millis(2));
        }
        assert_eq!(tiny.buffered(), 4_800);
    }

    // A transcode has no length to jump in by bytes: it is opened again at
    // the time, and the sound from there counts as that time.
    #[test]
    fn a_transcode_seeks_by_opening_at_a_time() {
        use crate::testing::http_server::{Behaviour, TestServer};
        let rate = 48_000;
        let samples: Vec<i16> = (0..rate * 3).flat_map(|n| [(n % 20_000) as i16, 0]).collect();
        let server = TestServer::start(flac_bytes(rate, 2, &samples, &[]), Behaviour::default());
        let address = format!("{}?id=1&format=mp3&maxBitRate=192", server.url());
        let deck = Deck::open(1, address, 0.0, HttpOptions::default());
        wait_ready(&deck);
        deck.seek(1.4);
        let until = Instant::now() + Duration::from_secs(5);
        let mut buf = vec![0.0; 64];
        let r = loop {
            let r = deck.read(&mut buf);
            if r.frames > 0 {
                break r;
            }
            assert!(Instant::now() < until, "nothing after the seek");
            thread::sleep(Duration::from_millis(2));
        };
        assert!((r.start_secs - 1.0).abs() < 1e-9, "{}", r.start_secs);
        // The server's answer starts at its own beginning: what it made from 1 s.
        assert_eq!(buf[0], 0.0);
        let targets = server.targets_asked();
        assert_eq!(targets.len(), 2, "{targets:?}");
        assert!(!targets[0].contains("timeOffset"), "{targets:?}");
        assert!(targets[1].ends_with("&timeOffset=1"), "{targets:?}");
        assert!(server.ranges_asked().iter().all(|&start| start == 0), "{:?}", server.ranges_asked());

        // Opened partway in, it asks for that time from the start.
        let later = Deck::open(2, format!("{}?id=1&format=mp3", server.url()), 2.0, HttpOptions::default());
        wait_ready(&later);
        assert!((later.read(&mut buf).start_secs - 2.0).abs() < 1e-9);
        assert!(server.targets_asked().last().unwrap().ends_with("timeOffset=2"));
    }

    // The original file still jumps by bytes.
    #[test]
    fn the_original_file_seeks_by_bytes() {
        use crate::testing::http_server::{Behaviour, TestServer};
        let rate = 48_000;
        let samples: Vec<i16> = (0..rate * 3).flat_map(|n| [(n % 20_000) as i16, 0]).collect();
        let server = TestServer::start(flac_bytes(rate, 2, &samples, &[]), Behaviour::default());
        let deck = Deck::open(1, format!("{}?id=1&format=raw", server.url()), 0.0, HttpOptions::default());
        wait_ready(&deck);
        deck.seek(2.0);
        let until = Instant::now() + Duration::from_secs(5);
        let mut buf = vec![0.0; 64];
        let r = loop {
            let r = deck.read(&mut buf);
            if r.frames > 0 && (r.start_secs - 2.0).abs() < 1e-9 {
                break r;
            }
            assert!(Instant::now() < until, "nothing after the seek");
            thread::sleep(Duration::from_millis(2));
        };
        assert_eq!(buf[0], (96_000 % 20_000) as f32 / 32768.0, "{r:?}");
        assert!(server.targets_asked().iter().all(|t| !t.contains("timeOffset")));
    }

    #[test]
    fn a_missing_file_fails() {
        let deck = Deck::open(1, "Z:/nothing/here.flac".into(), 0.0, HttpOptions::default());
        wait_ready(&deck);
        let r = deck.read(&mut [0.0; 8]);
        assert!(r.finished);
        assert_eq!(r.failure.unwrap().kind, crate::error::ErrorKind::NotFound);
    }

    // A song made on the way, as a server streams it: FLAC here, so the
    // test reads exact frames. Every answer from `timeOffset=N` is the song
    // from N seconds on; the first answer says its whole length and hangs
    // up part way, as a broken connection does. `busy` makes every answer
    // to a reopen a 503.
    fn made_on_the_way(rate: u32, secs: usize, busy: bool) -> crate::testing::http_server::ScriptServer {
        use std::io::Write;
        // Each whole second's answer is made before the server starts, so an
        // answer never waits on its encoding (slow in a debug build under load)
        // and the deck's wait for its first answer measures the deck alone.
        let bodies: Vec<Vec<u8>> = (0..=secs)
            .map(|from| {
                let frames = rate as usize * secs - rate as usize * from;
                flac_bytes(rate, 2, &sine(440.0, rate, 2, rate as usize * from, frames, 0.5), &[])
            })
            .collect();
        crate::testing::http_server::ScriptServer::start(move |asked, conn, _| {
            let offset = asked
                .target
                .split(['?', '&'])
                .find_map(|p| p.strip_prefix("timeOffset="))
                .and_then(|v| v.parse::<usize>().ok());
            if busy && offset.is_some() {
                let _ = conn.write_all(
                    b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                );
                return;
            }
            let body = &bodies[offset.unwrap_or(0).min(secs)];
            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = conn.write_all(head.as_bytes());
            let sent = if offset.is_none() { body.len() * 2 / 5 } else { body.len() };
            let _ = conn.write_all(&body[..sent]);
        })
    }

    fn quick_reopen() -> HttpOptions {
        HttpOptions { reopen_wait: Duration::from_millis(10), ..HttpOptions::default() }
    }

    // A stream made on the way that breaks off goes on from the time it
    // reached, with nothing heard twice and nothing missed. Red when a
    // break is resumed by bytes (no timeOffset request) or the deck fails.
    #[test]
    fn a_broken_stream_made_on_the_way_goes_on_from_its_time() {
        let rate = 48_000;
        let server = made_on_the_way(rate, 4, false);
        let deck = Deck::open(1, server.url("/rest/stream?id=1&format=opus"), 0.0, quick_reopen());
        wait_ready(&deck);
        let (all, failure) = read_all(&deck);
        assert!(failure.is_none(), "{failure:?}");
        let frames = all.len() / 2;
        assert!(frames.abs_diff(rate as usize * 4) <= 4_096, "{frames}");
        let asked: Vec<String> = server.asked().into_iter().map(|a| a.target).collect();
        assert_eq!(asked.len(), 2, "{asked:?}");
        assert!(asked[1].contains("timeOffset="), "{asked:?}");
    }

    // A server that answers busy to every reopen ends the song after three
    // tries. Red when a 503 is retried by the stream's own six retries, or
    // tried for ever.
    #[test]
    fn a_stream_made_on_the_way_fails_after_three_reopens() {
        let rate = 48_000;
        let server = made_on_the_way(rate, 4, true);
        let deck = Deck::open(1, server.url("/rest/stream?id=1&format=opus"), 0.0, quick_reopen());
        wait_ready(&deck);
        let (_, failure) = read_all(&deck);
        assert_eq!(failure.map(|f| f.kind), Some(crate::error::ErrorKind::Http));
        let reopened = server.asked().iter().filter(|a| a.target.contains("timeOffset=")).count();
        assert_eq!(reopened, REOPENS as usize);
    }

    // A seek near the start of a stream made on the way names its time.
    // Red when a seek opens the plain address.
    #[test]
    fn a_seek_near_the_start_names_its_time() {
        let rate = 48_000;
        let server = crate::testing::http_server::ScriptServer::start(move |_, conn, _| {
            use std::io::Write;
            let samples = sine(440.0, rate, 2, 0, rate as usize * 2, 0.5);
            let body = flac_bytes(rate, 2, &samples, &[]);
            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = conn.write_all(head.as_bytes());
            let _ = conn.write_all(&body);
        });
        let deck = Deck::open(1, server.url("/rest/stream?id=1&format=opus"), 0.0, quick_reopen());
        wait_ready(&deck);
        deck.seek(0.4);
        let until = Instant::now() + Duration::from_secs(5);
        while server.asked().len() < 2 {
            assert!(Instant::now() < until, "the seek asked nothing");
            thread::sleep(Duration::from_millis(5));
        }
        assert!(server.asked()[1].target.contains("timeOffset=0"), "{:?}", server.asked());
    }

    // A plain stream whose server refuses a later part as busy (Octo does,
    // when the kept copy a play read is gone) is opened again from its first
    // byte and goes on from where it was, to the song's end. Red when the 503
    // is asked again by bytes and the song fails.
    #[test]
    fn a_plain_stream_refused_part_way_is_opened_again_from_its_first_byte() {
        use std::io::Write;
        use std::sync::atomic::AtomicUsize;
        let rate = 48_000;
        let samples = sine(440.0, rate, 2, 0, rate as usize * 4, 0.5);
        let body = flac_bytes(rate, 2, &samples, &[]);
        let firsts = Arc::new(AtomicUsize::new(0));
        let seen = Arc::clone(&firsts);
        let server = crate::testing::http_server::ScriptServer::start(move |asked, conn, _| {
            let from = asked
                .header("range")
                .and_then(|r| r.strip_prefix("bytes="))
                .and_then(|r| r.split('-').next())
                .and_then(|from| from.parse::<usize>().ok())
                .unwrap_or(0);
            if from == 0 {
                let first = seen.fetch_add(1, Ordering::SeqCst) == 0;
                let head = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = conn.write_all(head.as_bytes());
                // The first answer hangs up part way, as a broken connection does.
                let sent = if first { body.len() * 2 / 5 } else { body.len() };
                let _ = conn.write_all(&body[..sent]);
                return;
            }
            if seen.load(Ordering::SeqCst) == 1 {
                // The file the play was reading is gone: its later parts are refused.
                let _ = conn.write_all(
                    b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                );
                return;
            }
            let head = format!(
                "HTTP/1.1 206 Partial Content\r\nContent-Type: audio/flac\r\nContent-Range: bytes {from}-{}/{}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len() - 1,
                body.len(),
                body.len() - from
            );
            let _ = conn.write_all(head.as_bytes());
            let _ = conn.write_all(&body[from..]);
        });
        let deck = Deck::open(1, server.url("/rest/stream?id=1&format=raw"), 0.0, quick_reopen());
        wait_ready(&deck);
        let (all, failure) = read_all(&deck);
        assert!(failure.is_none(), "{failure:?}");
        let frames = all.len() / 2;
        assert!(frames.abs_diff(rate as usize * 4) <= 4_096, "{frames}");
        assert_eq!(firsts.load(Ordering::SeqCst), 2, "opened again once from its first byte");
    }

    // A seek into a plain stream whose server now refuses later parts as
    // busy opens the song again from its first byte and lands at the seek.
    // Red when the seek's reopen flag is lost and the seek fails the song.
    #[test]
    fn a_seek_into_a_plain_stream_refused_part_way_opens_it_again_there() {
        use std::io::Write;
        use std::sync::atomic::{AtomicBool, AtomicUsize};
        let rate = 48_000;
        // Long enough that the seek lands much further past what the first
        // file sent than the source waits on an open answer for.
        let secs = 120;
        let samples = sine(440.0, rate, 2, 0, rate as usize * secs, 0.5);
        let body = flac_bytes(rate, 2, &samples, &[]);
        // What the first file sends before it holds: enough to open it.
        let part = 128 << 10;
        // A seek starts looking from the middle, which must lie past what was
        // sent by more than the source waits on an open answer for (512 KB),
        // so the read there asks the server.
        assert!(body.len() / 2 - part > 600_000, "{} bytes is too short", body.len());
        let refused = Arc::new(AtomicBool::new(false));
        let starts = Arc::new(AtomicUsize::new(0));
        let (was_refused, started) = (Arc::clone(&refused), Arc::clone(&starts));
        let server = crate::testing::http_server::ScriptServer::start(move |asked, conn, stop| {
            let from = asked
                .header("range")
                .and_then(|r| r.strip_prefix("bytes="))
                .and_then(|r| r.split('-').next())
                .and_then(|from| from.parse::<usize>().ok())
                .unwrap_or(0);
            let first_file = !was_refused.load(Ordering::SeqCst);
            // The tags a reader looks for at the end of a file it opens.
            let tail = from + 4_096 >= body.len();
            if first_file && !tail && from > part {
                // The file the play was reading is gone: its later parts are refused.
                was_refused.store(true, Ordering::SeqCst);
                let _ = conn.write_all(
                    b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                );
                return;
            }
            if !first_file && from == 0 {
                // Asked from the first byte once refused: the song opened again.
                started.fetch_add(1, Ordering::SeqCst);
            }
            let head = if from == 0 {
                format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n",
                    body.len()
                )
            } else {
                format!(
                    "HTTP/1.1 206 Partial Content\r\nContent-Type: audio/flac\r\nContent-Range: bytes {from}-{}/{}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len() - 1,
                    body.len(),
                    body.len() - from
                )
            };
            let _ = conn.write_all(head.as_bytes());
            if first_file && !tail {
                // While the first file is read, an answer sends up to a part of
                // it, then holds the connection open, so the seek below is what
                // asks for the rest.
                let _ = conn.write_all(&body[from..part.max(from)]);
                let _ = conn.flush();
                while !stop.load(Ordering::Acquire) {
                    thread::sleep(Duration::from_millis(5));
                }
            } else {
                let _ = conn.write_all(&body[from..]);
            }
        });
        let deck = Deck::open(1, server.url("/rest/stream?id=1&format=raw"), 0.0, quick_reopen());
        wait_ready(&deck);
        deck.seek(secs as f64 - 0.5);
        let (all, failure) = read_all(&deck);
        assert!(refused.load(Ordering::SeqCst), "the seek met the refusal");
        assert!(failure.is_none(), "{failure:?}");
        let frames = all.len() / 2;
        assert!(frames.abs_diff(rate as usize / 2) <= 4_096, "{frames}");
        assert!(starts.load(Ordering::SeqCst) >= 1, "opened again from its first byte");
    }
}
