//! One song being decoded on its own thread, a little ahead of playback.
//! Opening, network waits and decoding all happen there, so the mixing
//! thread never waits on any of them.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::Duration;

use crate::decode::{Decoder, TrackInfo};
use crate::error::Failure;
use crate::fifo::Fifo;
use crate::source::{self, http::HttpOptions};

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
    head_secs: f64,
    status: DeckStatus,
    info: Option<TrackInfo>,
    seek: Option<f64>,
    seek_generation: u64,
    stop: bool,
    capacity: usize,
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
}

impl Deck {
    /// Starts opening `source` (a path or an address) at `start_secs`.
    pub fn open(key: u64, source: String, start_secs: f64, mut http: HttpOptions) -> Deck {
        let starved = Arc::new(AtomicBool::new(false));
        let cancel = Arc::new(AtomicBool::new(false));
        http.starved = Some(starved.clone());
        http.cancel = Some(cancel.clone());
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                pcm: Fifo::default(),
                head_secs: start_secs,
                status: DeckStatus::Opening,
                info: None,
                seek: (start_secs > 0.0).then_some(start_secs),
                seek_generation: 0,
                stop: false,
                capacity: 0,
            }),
            wake: Condvar::new(),
            starved,
            cancel,
        });
        let worker = shared.clone();
        let spawned =
            thread::Builder::new().name("octo-deck".into()).spawn(move || run(worker, source, http));
        if let Err(e) = spawned {
            let mut state = shared.lock();
            state.status = DeckStatus::Failed(Failure::new(crate::error::ErrorKind::Other, e.to_string()));
        }
        Deck { shared, key }
    }

    /// Which queue entry this deck plays.
    pub fn key(&self) -> u64 {
        self.key
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

    /// Takes up to `out.len() / 2` stereo frames.
    pub fn read(&self, out: &mut [f32]) -> DeckRead {
        let mut state = self.shared.lock();
        let rate = state.info.as_ref().map(|i| i.sample_rate).unwrap_or(44_100) as f64;
        let start_secs = state.head_secs;
        let samples = state.pcm.pop_into(out);
        let frames = samples / 2;
        state.head_secs += frames as f64 / rate;
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

    /// Jumps to `secs`. What is buffered is dropped at once.
    pub fn seek(&self, secs: f64) {
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

fn run(shared: Arc<Shared>, source: String, http: HttpOptions) {
    let opened = source::open(&source, http).and_then(Decoder::open);
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
        state.capacity = (rate * AHEAD_SECS) as usize;
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
                    let landed = decoder.seek(target);
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
                if state.pcm.is_empty() {
                    let rate = decoder.sample_rate() as f64;
                    state.head_secs = decoder.position_secs() - (block.len() / 2) as f64 / rate;
                }
                state.pcm.push(&block);
            }
            Ok(false) => state.status = DeckStatus::Ended,
            Err(f) => {
                log::warn!("decoding stopped: {f}");
                state.status = DeckStatus::Failed(f);
            }
        }
        shared.wake.notify_all();
    }
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
    fn a_missing_file_fails() {
        let deck = Deck::open(1, "Z:/nothing/here.flac".into(), 0.0, HttpOptions::default());
        wait_ready(&deck);
        let r = deck.read(&mut [0.0; 8]);
        assert!(r.finished);
        assert_eq!(r.failure.unwrap().kind, crate::error::ErrorKind::NotFound);
    }
}
