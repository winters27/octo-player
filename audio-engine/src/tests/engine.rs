//! The whole engine, playing through the silent device.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::api::{EndReason, Engine, EngineEvent, EngineListener, PlaybackState, QueueItem, RepeatMode};
use crate::error::ErrorKind;
use crate::output::null::{Capture, NullDriver, Pump};
use crate::output::{DeviceEvent, OutputFormat};
use crate::testing::fixtures::*;

const RATE: u32 = 48_000;

#[derive(Default)]
struct Recorder {
    events: Mutex<Vec<EngineEvent>>,
}

impl EngineListener for Recorder {
    fn on_event(&self, event: EngineEvent) {
        self.events.lock().unwrap().push(event);
    }
}

impl Recorder {
    fn all(&self) -> Vec<EngineEvent> {
        self.events.lock().unwrap().clone()
    }

    // Songs starting, songs ending, joins and the end of the queue, in order.
    fn story(&self) -> Vec<String> {
        self.all()
            .iter()
            .filter_map(|e| match e {
                EngineEvent::TrackStarted { item_id, .. } => Some(format!("start {item_id}")),
                EngineEvent::TrackEnded { item_id, reason } => Some(format!("end {item_id} {reason:?}")),
                EngineEvent::GaplessTransition { from_id, to_id } => {
                    Some(format!("gapless {from_id} {to_id}"))
                }
                EngineEvent::QueueEnded => Some("queue end".into()),
                EngineEvent::Error { item_id, .. } => Some(format!("error {item_id:?}")),
                _ => None,
            })
            .collect()
    }

    fn starts(&self) -> Vec<String> {
        self.all()
            .iter()
            .filter_map(|e| match e {
                EngineEvent::TrackStarted { item_id, .. } => Some(item_id.clone()),
                _ => None,
            })
            .collect()
    }

    fn find(&self, found: impl Fn(&EngineEvent) -> bool) -> Option<EngineEvent> {
        self.events.lock().unwrap().iter().find(|e| found(e)).cloned()
    }

    fn wait_for(&self, what: &str, timeout: Duration, found: impl Fn(&EngineEvent) -> bool) -> EngineEvent {
        let until = Instant::now() + timeout;
        loop {
            if let Some(e) = self.events.lock().unwrap().iter().find(|e| found(e)) {
                return e.clone();
            }
            assert!(Instant::now() < until, "no {what} event; got {:?}", self.all());
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

fn engine(speed: f64) -> (Arc<Engine>, Arc<Recorder>, Capture) {
    let capture: Capture = Arc::new(Mutex::new(Vec::new()));
    let c = capture.clone();
    let engine = Engine::with_driver(Box::new(move || {
        Box::new(NullDriver::new(RATE, 2).capturing(c).at_speed(speed))
    }));
    let recorder = Arc::new(Recorder::default());
    engine.set_listener(recorder.clone());
    (engine, recorder, capture)
}

// An engine on a device that plays only when the test pumps it, so a busy
// machine slows the test down without changing what is heard.
fn driven_engine() -> (Arc<Engine>, Arc<Recorder>, Capture, Pump) {
    let capture: Capture = Arc::new(Mutex::new(Vec::new()));
    let (driver, pump) = NullDriver::driven(RATE, 2, Some(capture.clone()));
    let engine = Engine::with_driver(Box::new(move || Box::new(driver)));
    let recorder = Arc::new(Recorder::default());
    engine.set_listener(recorder.clone());
    (engine, recorder, capture, pump)
}

// Plays buffers until an event `found` arrives, up to `most` of them, then
// waits for it.
fn play_until(
    pump: &Pump,
    events: &Recorder,
    what: &str,
    most: usize,
    found: impl Fn(&EngineEvent) -> bool,
) -> EngineEvent {
    for _ in 0..most {
        pump.play();
        if let Some(e) = events.find(&found) {
            return e;
        }
    }
    events.wait_for(what, Duration::from_secs(10), found)
}

fn wait_until(what: &str, done: impl Fn() -> bool) {
    let until = Instant::now() + Duration::from_secs(10);
    while !done() {
        assert!(Instant::now() < until, "never {what}");
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn item(id: &str, path: &Path) -> QueueItem {
    QueueItem {
        id: id.into(),
        source: path.to_string_lossy().into(),
        album_id: None,
        album_order: None,
        duration_ms: None,
        replay_gain: None,
        headers: Vec::new(),
        secure_headers: Vec::new(),
        genre: None,
        bpm: None,
        live: false,
        hls: false,
    }
}

// Red when: `HttpHeader`'s `Debug` shows its value again (a queue item's
// `Debug` then shows its gateway sign-in).
#[test]
fn a_queue_items_secure_header_values_never_show_in_debug_output() {
    let login = crate::api::HttpHeader { name: "Authorization".into(), value: "Basic c2VjcmV0".into() };
    let song = QueueItem { secure_headers: vec![login], ..item("s", Path::new("")) };
    let shown = format!("{song:?}");
    assert!(shown.contains("Authorization") && !shown.contains("c2VjcmV0"), "{shown}");
}

#[test]
fn plays_a_queue_gaplessly_with_events() {
    let dir = temp_dir();
    let first = sine(441.0, RATE, 2, 0, 24_000, 0.5);
    let second = sine(441.0, RATE, 2, 24_000, 24_000, 0.5);
    let (a, b) = (dir.join("a.flac"), dir.join("b.wav"));
    write_flac(&a, RATE, 2, &first, &[]);
    write_wav(&b, RATE, 2, &second);
    let (engine, events, capture, pump) = driven_engine();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    let all = events.all();
    let names: Vec<String> = all
        .iter()
        .filter_map(|e| match e {
            EngineEvent::TrackStarted { item_id, .. } => Some(format!("start {item_id}")),
            EngineEvent::TrackEnded { item_id, reason } => Some(format!("end {item_id} {reason:?}")),
            EngineEvent::GaplessTransition { from_id, to_id } => Some(format!("gapless {from_id} {to_id}")),
            EngineEvent::QueueEnded => Some("queue end".into()),
            _ => None,
        })
        .collect();
    assert_eq!(
        names,
        ["start a", "end a Finished", "gapless a b", "start b", "end b Finished", "queue end"],
        "{all:?}"
    );
    assert_eq!(engine.state(), PlaybackState::Ended);
    // What reached the device is the unbroken tone (after the first
    // moment's fade-in and the limiter's short delay at the front).
    let played = capture.lock().unwrap().clone();
    let tone: Vec<f32> = first.iter().chain(&second).map(|&s| s as f32 / 32768.0).collect();
    let lines_up = |k: usize, i: usize| played.get((k + i) * 2) == Some(&tone[i * 2]);
    let start = (0..20_000).find(|&k| (3_000..3_050).all(|i| lines_up(k, i))).expect("tone found in output");
    // Past the first moment's fade-in, every frame across the join matches.
    for i in 2_000..47_900 {
        assert!(lines_up(start, i), "frame {i}");
    }
    engine.shutdown();
}

#[test]
fn events_arrive_at_other_speeds_too() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, 30_000, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 30_000, 30_000, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_speed(1.5, 1.0).unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    let all = events.all();
    let starts: Vec<&str> = all
        .iter()
        .filter_map(|e| match e {
            EngineEvent::TrackStarted { item_id, .. } => Some(item_id.as_str()),
            _ => None,
        })
        .collect();
    assert_eq!(starts, ["a", "b"], "{all:?}");
    assert!(all.iter().any(|e| matches!(e, EngineEvent::GaplessTransition { .. })));
    engine.shutdown();
}

#[test]
fn clock_is_monotonic_and_tracks_real_time() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.load(vec![item("a", &a)], 0, 0, true).unwrap();
    pump.play();
    events.wait_for("start", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    // Each buffer is heard over the 10 ms from when it is played: read the
    // clock every millisecond across 1.2 s of buffers.
    let mut first = None;
    let mut last = 0.0;
    for buffer in 0..120 {
        let at = pump.play();
        for ms in 0..=10 {
            let p = engine.position_at(at + Duration::from_millis(ms));
            assert_eq!(p.item_id.as_deref(), Some("a"));
            assert!(p.position_ms >= last, "went back from {last} to {}", p.position_ms);
            last = p.position_ms;
            // It moves as fast as time does, between buffers too.
            let first = *first.get_or_insert(p.position_ms);
            let heard = (buffer * 10 + ms) as f64;
            let moved = p.position_ms - first;
            assert!((moved - heard).abs() < 0.5, "moved {moved} ms in {heard} ms");
        }
    }
    engine.shutdown();
}

#[test]
fn seek_and_start_position_are_accurate() {
    let dir = temp_dir();
    let a = dir.join("a.flac");
    write_flac(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 5, 0.3), &[]);
    let (engine, events, _, pump) = driven_engine();
    engine.load(vec![item("a", &a)], 0, 1_000, false).unwrap();
    // Loaded paused at 1 s: that is the position before anything plays.
    wait_until("loaded", || engine.position().item_id.is_some());
    let p = engine.position().position_ms;
    assert!((p - 1_000.0).abs() < 1.0, "{p}");
    engine.play().unwrap();
    wait_until("playing", || engine.state() == PlaybackState::Playing);
    let at = pump.play();
    events.wait_for("start", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let p = engine.position_at(at + Duration::from_millis(10)).position_ms;
    assert!((1_000.0..1_100.0).contains(&p), "{p}");
    engine.seek(3_250).unwrap();
    // Once the seek is taken, before any of the new sound is heard, the
    // position already shows the target.
    wait_until("at the target", || (engine.position().position_ms - 3_250.0).abs() < 15.0);
    let mut at = Instant::now();
    for _ in 0..30 {
        at = pump.play();
    }
    let p = engine.position_at(at + Duration::from_millis(10)).position_ms;
    assert!((3_450.0..3_600.0).contains(&p), "{p}");
    engine.shutdown();
}

#[test]
fn crossfades_between_songs_from_different_albums() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_crossfade(1_000).unwrap();
    // Library lengths, as the app always has them.
    let mut x = item("a", &a);
    x.album_id = Some("one".into());
    x.album_order = Some(1);
    x.duration_ms = Some(3_000);
    let mut y = item("b", &b);
    y.album_id = Some("two".into());
    y.album_order = Some(2);
    y.duration_ms = Some(3_000);
    engine.load(vec![x, y], 0, 0, true).unwrap();
    let fade =
        play_until(&pump, &events, "crossfade", 400, |e| matches!(e, EngineEvent::CrossfadeStarted { .. }));
    assert_eq!(
        fade,
        EngineEvent::CrossfadeStarted { from_id: "a".into(), to_id: "b".into(), duration_ms: 1_000 }
    );
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
}

#[test]
fn an_album_in_order_stays_gapless_with_crossfade_on() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_crossfade(1_000).unwrap();
    let mut x = item("a", &a);
    x.album_id = Some("one".into());
    x.album_order = Some(1);
    x.duration_ms = Some(2_000);
    let mut y = item("b", &b);
    y.album_id = Some("one".into());
    y.album_order = Some(2);
    y.duration_ms = Some(2_000);
    engine.load(vec![x, y], 0, 0, true).unwrap();
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    let all = events.all();
    assert!(all.iter().any(|e| matches!(e, EngineEvent::GaplessTransition { .. })), "{all:?}");
    assert!(!all.iter().any(|e| matches!(e, EngineEvent::CrossfadeStarted { .. })));
    engine.shutdown();
}

#[test]
fn a_seek_before_the_song_is_heard_still_starts_it() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    // Straight after the load, before anything is heard.
    engine.seek(2_500).unwrap();
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    let all = events.all();
    let names: Vec<String> = all
        .iter()
        .filter_map(|e| match e {
            EngineEvent::TrackStarted { item_id, .. } => Some(format!("start {item_id}")),
            EngineEvent::TrackEnded { item_id, reason } => Some(format!("end {item_id} {reason:?}")),
            _ => None,
        })
        .collect();
    assert_eq!(names, ["start a", "end a Finished", "start b", "end b Finished"], "{all:?}");
    engine.shutdown();
}

#[test]
fn a_missing_song_is_reported_and_skipped() {
    let dir = temp_dir();
    let b = dir.join("b.wav");
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, 12_000, 0.3));
    let (engine, events, _, pump) = driven_engine();
    let missing = item("gone", Path::new("Z:/no/such/song.flac"));
    engine.load(vec![missing, item("b", &b)], 0, 0, true).unwrap();
    let error = events.wait_for("error", Duration::from_secs(10), |e| matches!(e, EngineEvent::Error { .. }));
    assert!(
        matches!(error, EngineEvent::Error { kind: ErrorKind::NotFound, item_id: Some(ref id), .. } if id == "gone")
    );
    play_until(
        &pump,
        &events,
        "b",
        100,
        |e| matches!(e, EngineEvent::TrackStarted { item_id, .. } if item_id == "b"),
    );
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
}

#[test]
fn pause_resume_skip_and_stop() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 4, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 4, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    pump.play();
    events.wait_for("start", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    for _ in 0..20 {
        pump.play();
    }
    engine.pause().unwrap();
    wait_until("paused", || engine.state() == PlaybackState::Paused);
    // The fade out, and then the device keeps asking while paused. (Read a
    // second on, well past anything a buffer played.)
    let mut at = Instant::now();
    for _ in 0..5 {
        at = pump.play();
    }
    let held = engine.position_at(at + Duration::from_secs(1)).position_ms;
    for _ in 0..20 {
        at = pump.play();
    }
    let still = engine.position_at(at + Duration::from_secs(1)).position_ms;
    assert!((still - held).abs() < 1.0, "the clock stands still while paused");
    engine.play().unwrap();
    wait_until("playing", || engine.state() == PlaybackState::Playing);
    let mut at = Instant::now();
    for _ in 0..20 {
        at = pump.play();
    }
    assert!(engine.position_at(at + Duration::from_millis(10)).position_ms > held + 100.0);
    engine.skip_next().unwrap();
    play_until(
        &pump,
        &events,
        "b",
        100,
        |e| matches!(e, EngineEvent::TrackStarted { item_id, .. } if item_id == "b"),
    );
    assert!(
        events.all().contains(&EngineEvent::TrackEnded { item_id: "a".into(), reason: EndReason::Skipped })
    );
    wait_until("b current", || engine.queue().current_index == Some(1));
    engine.stop().unwrap();
    events.wait_for(
        "stopped",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::TrackEnded { item_id, reason: EndReason::Stopped } if item_id == "b"),
    );
    wait_until("idle", || engine.state() == PlaybackState::Idle);
    engine.shutdown();
}

#[test]
fn the_same_songs_given_again_keep_the_next_one_lined_up() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize / 2, 0.3));
    let (engine, events, _, pump) = driven_engine();
    // An album in order joins gaplessly, once b's length is read from b.
    engine.set_crossfade(1_000).unwrap();
    let on_album = |id: &str, path: &Path, order: i32| QueueItem {
        album_id: Some("one".into()),
        album_order: Some(order),
        ..item(id, path)
    };
    let songs = || vec![on_album("a", &a, 1), on_album("b", &b, 2)];
    engine.load(songs(), 0, 0, true).unwrap();
    pump.play();
    events.wait_for("start", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    // Once the join is planned, b is lined up and read. With its file gone,
    // only the song already lined up can still play.
    events.wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    std::fs::remove_file(&b).unwrap();
    engine.replace_upcoming(vec![on_album("b", &b, 2)]).unwrap();
    engine.replace_queue(songs(), 0).unwrap();
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    assert_eq!(
        events.story(),
        ["start a", "end a Finished", "gapless a b", "start b", "end b Finished", "queue end"]
    );
    engine.shutdown();
}

#[test]
fn a_new_order_keeps_the_song_playing_and_plays_on_in_the_new_order() {
    let dir = temp_dir();
    let songs: Vec<QueueItem> = ["a", "b", "c", "d"]
        .iter()
        .map(|id| {
            let path = dir.join(format!("{id}.wav"));
            write_wav(&path, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
            item(id, &path)
        })
        .collect();
    let (engine, events, _, pump) = driven_engine();
    engine.load(songs.clone(), 2, 0, true).unwrap();
    let at = pump.play();
    events.wait_for("start c", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let before = engine.position_at(at + Duration::from_millis(10));
    // The index given is stale on purpose: the playing song is found by id.
    let [a, b, c, d] = [0, 1, 2, 3].map(|i| songs[i].clone());
    engine.replace_queue(vec![d, a, c, b], 0).unwrap();
    wait_until("the new order", || engine.queue().item_ids == ["d", "a", "c", "b"]);
    let after = engine.position_at(at + Duration::from_millis(10));
    assert_eq!(after.item_id.as_deref(), Some("c"));
    assert!(after.position_ms >= before.position_ms, "went back from {before:?} to {after:?}");
    assert_eq!(engine.queue().current_index, Some(2));
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    assert_eq!(
        events.story(),
        ["start c", "end c Finished", "gapless c b", "start b", "end b Finished", "queue end"]
    );
    engine.shutdown();
}

#[test]
fn repeat_all_goes_round_the_new_order() {
    let dir = temp_dir();
    let songs: Vec<QueueItem> = ["a", "b", "c"]
        .iter()
        .map(|id| {
            let path = dir.join(format!("{id}.wav"));
            write_wav(&path, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
            item(id, &path)
        })
        .collect();
    let (engine, events, _, pump) = driven_engine();
    engine.set_repeat(RepeatMode::All).unwrap();
    engine.load(songs.clone(), 0, 0, true).unwrap();
    pump.play();
    events.wait_for("start a", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let [a, b, c] = [0, 1, 2].map(|i| songs[i].clone());
    engine.replace_queue(vec![c, a, b], 1).unwrap();
    wait_until("the new order", || engine.queue().item_ids == ["c", "a", "b"]);
    // Three 2 s songs and the start of the fourth.
    for _ in 0..700 {
        if events.starts().len() >= 4 {
            break;
        }
        pump.play();
    }
    wait_until("four starts", || events.starts().len() >= 4);
    assert_eq!(events.starts()[..4], ["a", "b", "c", "a"]);
    engine.shutdown();
}

#[test]
fn a_lost_device_that_will_not_reopen_carries_on_once_one_does() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 20, 0.3));
    let (driver, pump) = NullDriver::driven(RATE, 2, None);
    let (trouble, refuse) = (driver.events(), driver.refusal());
    let engine = Engine::with_driver(Box::new(move || Box::new(driver)));
    let events = Arc::new(Recorder::default());
    engine.set_listener(events.clone());
    engine.load(vec![item("a", &a)], 0, 0, true).unwrap();
    pump.play();
    events.wait_for("start", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    for _ in 0..30 {
        pump.play();
    }

    let device_errors = || {
        events
            .all()
            .iter()
            .filter(|e| matches!(e, EngineEvent::Error { kind: ErrorKind::Device, .. }))
            .count()
    };
    // Unplugged, with nothing else to play to: told once more. Answers
    // where it stopped.
    let unplug = |errors: usize| {
        refuse.store(true, Ordering::Release);
        trouble.lock().unwrap().push(DeviceEvent::Lost("unplugged".into()));
        wait_until("the device error", || device_errors() == errors);
        let held = engine.position();
        assert_eq!(held.item_id.as_deref(), Some("a"));
        std::thread::sleep(Duration::from_millis(200));
        assert_eq!(engine.position().position_ms, held.position_ms, "the place is held");
        held.position_ms
    };
    // Carries on from the place held, not from the start.
    let carries_on_from = |held: f64| {
        let mut at = Instant::now();
        for _ in 0..10 {
            at = pump.play();
        }
        let moved = engine.position_at(at + Duration::from_millis(10));
        assert_eq!(moved.item_id.as_deref(), Some("a"));
        assert!(moved.position_ms > held + 50.0, "never carried on from {held}; got {:?}", events.all());
        assert!(moved.position_ms < held + 400.0, "from {held} to {}", moved.position_ms);
    };

    // Back when Play is pressed.
    let held = unplug(1);
    assert!(held > 250.0, "{held}");
    refuse.store(false, Ordering::Release);
    engine.play().unwrap();
    carries_on_from(held);

    // Back when an output is chosen.
    let held = unplug(2);
    refuse.store(false, Ordering::Release);
    engine.set_output_device(None).unwrap();
    carries_on_from(held);

    // Back by itself, trying again now and then without an error each time.
    let held = unplug(3);
    std::thread::sleep(Duration::from_millis(2_500));
    assert_eq!(engine.position().position_ms, held);
    assert_eq!(device_errors(), 3);
    refuse.store(false, Ordering::Release);
    carries_on_from(held);
    assert_eq!(device_errors(), 3);
    engine.shutdown();
}

// A stream waits for the set amount of sound before it starts; with none
// set it starts as soon as there is sound. A file never waits.
#[test]
fn a_stream_waits_for_start_after() {
    use crate::testing::http_server::{Behaviour, TestServer};
    // Noise, so the file is as big as real music.
    let mut x: u32 = 1;
    let noise: Vec<i16> = (0..RATE as usize * 8)
        .map(|_| {
            x = x.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            (x >> 16) as i16 / 4
        })
        .collect();
    let song = flac_bytes(RATE, 2, &noise, &[]);
    // The song arrives as fast as it plays, as on a slow connection.
    let quiet = Behaviour { per_second: Some(song.len() / 4), ..Behaviour::default() };
    let time_to_start = |start_after: u32, behaviour: &Behaviour| {
        let server = TestServer::start(song.clone(), behaviour.clone());
        let (engine, events, _) = engine(1.0);
        engine.set_start_after(start_after).unwrap();
        let begun = Instant::now();
        engine
            .load(vec![QueueItem { source: server.url(), ..item("s", Path::new("")) }], 0, 0, true)
            .unwrap();
        events.wait_for("start", Duration::from_secs(15), |e| matches!(e, EngineEvent::TrackStarted { .. }));
        let took = begun.elapsed();
        engine.shutdown();
        took
    };
    // With no wait it starts on the first second of the song, all the
    // server sends until it is dropped.
    let one_second = Behaviour { stall_after: Some(song.len() / 4), ..quiet.clone() };
    time_to_start(0, &one_second);
    // With one, it starts only once 2.5 s of the song is in, which takes
    // that long to arrive.
    let waited = time_to_start(2_500, &quiet);
    assert!(waited >= Duration::from_millis(2_500), "{waited:?}");
}

// A stream shorter than the wait starts once all of it is in.
#[test]
fn a_stream_shorter_than_start_after_starts_at_its_end() {
    use crate::testing::http_server::{Behaviour, TestServer};
    let song = flac_bytes(RATE, 2, &sine(440.0, RATE, 2, 0, 12_000, 0.3), &[]);
    let server = TestServer::start(song, Behaviour::default());
    let (engine, events, _) = engine(4.0);
    engine.set_start_after(10_000).unwrap();
    engine.load(vec![QueueItem { source: server.url(), ..item("tiny", Path::new("")) }], 0, 0, true).unwrap();
    events.wait_for("queue end", Duration::from_secs(5), |e| matches!(e, EngineEvent::QueueEnded));
    assert_eq!(events.story(), ["start tiny", "end tiny Finished", "queue end"]);
    engine.shutdown();
}

#[test]
fn a_server_with_a_trusted_certificate_plays() {
    use crate::api::TrustedCertificate;
    use crate::testing::http_server::{Behaviour, SelfSigned, TestServer};
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let song = flac_bytes(RATE, 2, &sine(440.0, RATE, 2, 0, 12_000, 0.3), &[]);
    let server = TestServer::start_tls(song, Behaviour::default(), &certificate);
    let streamed = |id: &str| QueueItem { source: server.url(), ..item(id, Path::new("")) };

    // Not trusted: the song fails, as a network problem.
    let (engine, events, _) = engine(4.0);
    engine.load(vec![streamed("refused")], 0, 0, true).unwrap();
    let error = events.wait_for("error", Duration::from_secs(10), |e| matches!(e, EngineEvent::Error { .. }));
    assert!(matches!(error, EngineEvent::Error { kind: ErrorKind::Network, .. }), "{error:?}");

    // Trusted for its host: it plays to the end.
    engine.set_trusted_certificates(vec![TrustedCertificate {
        host: "127.0.0.1".into(),
        sha256: certificate.fingerprint(),
    }]);
    engine.load(vec![streamed("trusted")], 0, 0, true).unwrap();
    events.wait_for(
        "trusted song's end",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::TrackEnded { item_id, .. } if item_id == "trusted"),
    );
    let story = events.story();
    let trusted: Vec<&String> = story.iter().filter(|line| line.contains("trusted")).collect();
    assert_eq!(trusted, ["start trusted", "end trusted Finished"], "{story:?}");
    engine.shutdown();
}

// Red when: `set_client_certificate` keeps the old identity on a refusal,
// or the engine's client never offers the identity it was given.
#[test]
fn a_client_certificate_reaches_its_server_and_a_refused_one_leaves_none() {
    use crate::api::{ClientCertificate, Origin, TrustedCertificate};
    use crate::error::EngineError;
    use crate::testing::http_server::{ClientAuthServer, ClientCa, SelfSigned};
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let ca = ClientCa::generate();
    let client = ca.issue("listener", &rcgen::PKCS_ECDSA_P256_SHA256);
    let clients = [ca.der.clone()];
    let song = flac_bytes(RATE, 2, &sine(440.0, RATE, 2, 0, 12_000, 0.3), &[]);
    let server = ClientAuthServer::start(song, &certificate, Some(&clients), &rustls::version::TLS13);
    let streamed = |id: &str| QueueItem { source: server.url(), ..item(id, Path::new("")) };
    let (engine, events, _) = engine(4.0);
    engine.set_trusted_certificates(vec![TrustedCertificate {
        host: "127.0.0.1".into(),
        sha256: certificate.fingerprint(),
    }]);
    let origins = vec![Origin { scheme: "https".into(), host: "127.0.0.1".into(), port: server.port() }];
    let given =
        ClientCertificate { origins: origins.clone(), chain: client.chain.clone(), key: client.key.clone() };
    engine.set_client_certificate(Some(given)).unwrap();
    engine.load(vec![streamed("offered")], 0, 0, true).unwrap();
    events.wait_for(
        "offered song's end",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::TrackEnded { item_id, .. } if item_id == "offered"),
    );
    let story = events.story();
    let offered: Vec<&String> = story.iter().filter(|line| line.contains("offered")).collect();
    assert_eq!(offered, ["start offered", "end offered Finished"], "{story:?}");
    let shown = Some(client.chain[0].clone());
    let leaves: Vec<_> = server.seen().into_iter().map(|seen| seen.leaf).collect();
    assert!(!leaves.is_empty() && leaves.iter().all(|leaf| *leaf == shown), "{leaves:?}");
    // A key the engine cannot sign with: refused, and none is offered.
    let refused = ClientCertificate { origins, chain: client.chain.clone(), key: b"not a key".to_vec() };
    let err = engine.set_client_certificate(Some(refused)).unwrap_err();
    assert!(matches!(err, EngineError::Failed { kind: ErrorKind::InvalidArgument, .. }), "{err:?}");
    let asked = server.seen().len();
    engine.load(vec![streamed("refused")], 0, 0, true).unwrap();
    let error = events.wait_for(
        "refused song's error",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::Error { item_id: Some(id), .. } if id == "refused"),
    );
    assert!(matches!(error, EngineEvent::Error { kind: ErrorKind::Network, .. }), "{error:?}");
    assert_eq!(server.seen().len(), asked, "a request reached the server with no certificate");
    engine.shutdown();
}

#[test]
fn says_what_the_device_runs_at_and_what_the_song_is() {
    let dir = temp_dir();
    let a = dir.join("a.flac");
    write_flac(&a, 44_100, 2, &sine(300.0, 44_100, 2, 0, 44_100, 0.3), &[]);
    let (engine, events, _) = engine(1.0);
    assert_eq!(engine.output_format(), None, "no device opened yet");
    engine.load(vec![item("a", &a)], 0, 0, true).unwrap();

    let opened =
        events.wait_for("device", Duration::from_secs(5), |e| matches!(e, EngineEvent::DeviceChanged { .. }));
    let want = OutputFormat { sample_rate: RATE, channels: 2, sample_format: "f32".into(), bits: Some(32) };
    let EngineEvent::DeviceChanged { device, format } = opened else { unreachable!() };
    assert_eq!(device.map(|d| d.name), Some("No sound".into()));
    assert_eq!(format.as_ref(), Some(&want));
    assert_eq!(engine.output_format(), Some(want));

    // The song's own format comes with its start, before any resampling.
    let started =
        events.wait_for("start", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let EngineEvent::TrackStarted { info: Some(info), .. } = started else {
        panic!("no song info: {started:?}")
    };
    assert_eq!(info.codec, "flac");
    assert!(info.lossless);
    assert_eq!((info.sample_rate, info.channels, info.bits_per_sample), (44_100, 2, Some(16)));
    engine.shutdown();
}

// A skip to a song still opening stops reporting the song skipped away
// from: once the engine has left a song, it no longer says it is playing it.
#[test]
fn a_skip_to_a_song_still_opening_stops_reporting_the_one_left() {
    let dir = temp_dir();
    let a = dir.join("left.wav");
    write_wav(&a, RATE, 2, &sine(440.0, RATE, 2, 0, RATE as usize * 20, 0.3));
    // A server that takes the call and never answers: the song never opens.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}/slow.flac", listener.local_addr().unwrap());
    std::thread::spawn(move || {
        let held: Vec<_> = listener.incoming().take(8).collect();
        std::thread::sleep(Duration::from_secs(30));
        drop(held);
    });
    let slow = QueueItem { source: url, ..item("slow", Path::new("")) };

    let (engine, events, _) = engine(1.0);
    engine.set_position_interval(50).unwrap();
    engine.load(vec![item("left", &a), slow], 0, 0, true).unwrap();
    events.wait_for(
        "left heard",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::Position { item_id, .. } if item_id == "left"),
    );
    engine.skip_to(1).unwrap();
    // A word already on its way is fine; once the engine waits on the new
    // song, none for the song left.
    events.wait_for(
        "waiting on slow",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::Buffering { item_id: Some(id) } if id == "slow"),
    );
    let before = events.all().len();
    std::thread::sleep(Duration::from_millis(800));
    let after: Vec<EngineEvent> = events.all().split_off(before);
    let stale = after
        .iter()
        .filter(|e| matches!(e, EngineEvent::Position { item_id, .. } if item_id == "left"))
        .count();
    assert_eq!(stale, 0, "still told of the song left: {after:?}");
    assert!(!events.starts().contains(&"slow".to_string()), "the stalled song never starts");
    engine.shutdown();
}

// From a skip until the next song is heard, the place held is that song's
// start; no Position names the song left with it, even when the new song
// opens late and starts being heard.
#[test]
fn a_skip_to_a_song_that_opens_late_never_reports_the_one_left() {
    let dir = temp_dir();
    let a = dir.join("left.wav");
    write_wav(&a, RATE, 2, &sine(440.0, RATE, 2, 0, RATE as usize * 20, 0.3));
    let b = dir.join("late.wav");
    write_wav(&b, RATE, 2, &sine(660.0, RATE, 2, 0, RATE as usize * 5, 0.3));
    let body = std::fs::read(&b).unwrap();
    // A server that answers each call a second late.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}/late.wav", listener.local_addr().unwrap());
    std::thread::spawn(move || {
        for stream in listener.incoming().take(8) {
            let Ok(mut stream) = stream else { continue };
            let body = body.clone();
            std::thread::spawn(move || {
                use std::io::{BufRead, Write};
                let mut reader = std::io::BufReader::new(stream.try_clone().unwrap());
                let mut line = String::new();
                while reader.read_line(&mut line).is_ok_and(|n| n > 2) {
                    line.clear();
                }
                std::thread::sleep(Duration::from_secs(1));
                let head = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(head.as_bytes());
                let _ = stream.write_all(&body);
            });
        }
    });
    let late = QueueItem { source: url, ..item("late", Path::new("")) };

    let (engine, events, _) = engine(1.0);
    engine.set_position_interval(50).unwrap();
    engine.load(vec![item("left", &a), late], 0, 0, true).unwrap();
    events.wait_for(
        "left heard",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::Position { item_id, .. } if item_id == "left"),
    );
    engine.skip_to(1).unwrap();
    events.wait_for(
        "waiting on late",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::Buffering { item_id: Some(id) } if id == "late"),
    );
    let before = events.all().len();
    events.wait_for(
        "late started",
        Duration::from_secs(10),
        |e| matches!(e, EngineEvent::TrackStarted { item_id, .. } if item_id == "late"),
    );
    std::thread::sleep(Duration::from_millis(300));
    let after: Vec<EngineEvent> = events.all().split_off(before);
    let stale: Vec<_> = after
        .iter()
        .filter(|e| matches!(e, EngineEvent::Position { item_id, .. } if item_id == "left"))
        .collect();
    assert!(stale.is_empty(), "told of the song left after the skip: {after:?}");
    engine.shutdown();
}

#[test]
fn a_next_song_of_unknown_length_still_crossfades() {
    // Songs from outside the library come with no length: the blend waits
    // for the next song's decoder to say how long it is.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_crossfade(1_000).unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    // Nothing is played until the plan is made, however long b takes to open.
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, entry_ms, overlap_ms, reason, .. } = planned else {
        unreachable!()
    };
    assert_eq!((start_ms, entry_ms, overlap_ms), (2_000, 0, 1_000));
    assert!(reason.starts_with("automix: crossfade at 2.00 s over 1.00 s"), "{reason}");
    let fade =
        play_until(&pump, &events, "crossfade", 400, |e| matches!(e, EngineEvent::CrossfadeStarted { .. }));
    assert_eq!(
        fade,
        EngineEvent::CrossfadeStarted { from_id: "a".into(), to_id: "b".into(), duration_ms: 1_000 }
    );
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    assert!(!events.all().iter().any(|e| matches!(e, EngineEvent::GaplessTransition { .. })));
    engine.shutdown();
}

#[test]
fn a_crossfade_off_still_says_why_the_join_is_gapless() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { overlap_ms, reason, .. } = planned else { unreachable!() };
    assert_eq!((overlap_ms, reason.as_str()), (0, "automix: gapless, crossfade is off"));
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
}

fn test_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let len = input.current.duration_ms as i64;
    let mut plan = crate::automix::TransitionPlan::fixed_crossfade(len, 500, "test plan");
    plan.k = 0.4;
    plan.start_ms = 9_000;
    plan.entry_ms = 500;
    plan
}

#[test]
fn a_smart_plan_starts_early_enters_late_and_cuts_the_outgoing_song() {
    // Song A on the left at a steady level for 12 s; song B on the right as
    // a ramp that tells each frame's place in the song.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    let a_samples: Vec<i16> = (0..RATE as usize * 12).flat_map(|_| [8_192, 0]).collect();
    write_wav(&a, RATE, 2, &a_samples);
    let b_samples: Vec<i16> = (0..RATE as usize * 4).flat_map(|n| [0, (n % 15_000) as i16]).collect();
    write_wav(&b, RATE, 2, &b_samples);
    let (engine, events, capture, pump) = driven_engine();
    engine.set_test_planner(Some(test_plan));
    engine.set_crossfade(1_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings {
            smart_transitions: true,
            filter_sweeps: true,
            match_tempo: false,
            max_overlap_ms: 8_000,
            fade_curve: crate::automix::FadeCurve::EqualPower,
            longest_intro_ms: 16_000,
        })
        .unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    // The tail of A is scouted from a second in; the song then holds still
    // until the plan is made.
    for _ in 0..110 {
        pump.play();
    }
    let planned = events
        .wait_for("plan", Duration::from_secs(30), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, entry_ms, overlap_ms, reason, .. } = planned else {
        unreachable!()
    };
    assert_eq!((start_ms, entry_ms, overlap_ms), (9_000, 500, 500), "{reason}");
    assert!(reason.contains("test plan"), "{reason}");
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();

    let out = capture.lock().unwrap().clone();
    // A is heard for its first 9 s and the half second of the blend, then
    // stops although it had 2.5 s left.
    let heard = out.chunks_exact(2).filter(|f| f[0] != 0.0).count();
    assert_eq!(heard, RATE as usize * 19 / 2, "A heard for {heard} frames");
    // From there on B plays alone, from 0.5 s in plus the blend's half second.
    let last = out.chunks_exact(2).rposition(|f| f[0] != 0.0).unwrap();
    let next = out.chunks_exact(2).skip(last + 1).find(|f| f[1] != 0.0).unwrap();
    assert_eq!((next[1] * 32768.0).round() as usize, RATE as usize % 15_000);
}

// A blend at 9 s over 2 s whose next song comes in at 1.03 times its speed.
fn matched_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let len = input.current.duration_ms as i64;
    let mut plan = crate::automix::TransitionPlan::fixed_crossfade(len, 2_000, "matched plan");
    (plan.kind, plan.k, plan.start_ms) = (crate::automix::TransitionKind::Blend, 0.4, 9_000);
    plan.beat_match_rate = Some(1.03);
    plan
}

#[test]
fn a_matched_blend_plays_its_rate_however_soon_the_next_song_ends() {
    // Song A on the left for 12 s; song B on the right for 4 s, which ends
    // before its rate has eased back to normal.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    let a_samples: Vec<i16> = (0..RATE as usize * 12).flat_map(|_| [8_192, 0]).collect();
    write_wav(&a, RATE, 2, &a_samples);
    let b_samples: Vec<i16> = (0..RATE as usize * 4).flat_map(|_| [0, 8_192]).collect();
    write_wav(&b, RATE, 2, &b_samples);
    let (engine, events, capture, pump) = driven_engine();
    engine.set_test_planner(Some(matched_plan));
    engine.set_crossfade(2_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    // With both songs profiled no scout is waited for: the plan waits only
    // for B's decoder, and is made before anything is played.
    engine.set_song_profile("a".into(), Some(profile_of(12_000, 0))).unwrap();
    engine.set_song_profile("b".into(), Some(profile_of(4_000, 0))).unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, overlap_ms, reason, .. } = planned else { unreachable!() };
    assert_eq!((start_ms, overlap_ms), (9_000, 2_000), "{reason}");
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
    // B's 4 s go by faster than 4 s: 1.03 times through the blend, then
    // easing back, so B is heard for about 3.89 s.
    let out = capture.lock().unwrap().clone();
    let b_heard = out.chunks_exact(2).filter(|f| f[1] != 0.0).count();
    assert!((182_000..189_000).contains(&b_heard), "B heard for {b_heard} frames: {reason}");
    // Decided in time to line B up, so the plan kept its rate.
    assert!(!reason.contains("late"), "{reason}");
}

// A blend at 9 s over 2 s whose two songs are heard together for all of it,
// their beats meeting at its start and `drift` ms apart in song time by
// its end.
fn drifting_plan(input: &crate::automix::PlanInput, drift: f64) -> crate::automix::TransitionPlan {
    let mut plan = matched_plan(input);
    plan.beat_match_rate = None;
    (plan.both_heard_start_ms, plan.both_heard_end_ms, plan.lock_ms) = (0, 2_000, 0.0);
    (plan.drift_gap, plan.drift_at_end_ms) = (drift / 2_000.0, drift);
    plan
}

fn drifting_30(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    drifting_plan(input, 30.0)
}

fn drifting_10(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    drifting_plan(input, 10.0)
}

// Frames of A (left) heard when the speed is halved just as its blend
// into B (right) begins, under `planner`.
fn heard_when_slowed(planner: crate::automix::Planner) -> usize {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    let a_samples: Vec<i16> = (0..RATE as usize * 12).flat_map(|_| [8_192, 0]).collect();
    write_wav(&a, RATE, 2, &a_samples);
    let b_samples: Vec<i16> = (0..RATE as usize * 6).flat_map(|_| [0, 8_192]).collect();
    write_wav(&b, RATE, 2, &b_samples);
    let (engine, events, capture, pump) = driven_engine();
    engine.set_test_planner(Some(planner));
    engine.set_crossfade(2_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    // Profiled, so the plan waits for no scout and is made before anything
    // is played: in time, its drift kept.
    engine.set_song_profile("a".into(), Some(profile_of(12_000, 0))).unwrap();
    engine.set_song_profile("b".into(), Some(profile_of(6_000, 0))).unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { reason, .. } = planned else { unreachable!() };
    assert!(!reason.contains("late"), "{reason}");
    play_until(&pump, &events, "blend", 1_500, |e| matches!(e, EngineEvent::CrossfadeStarted { .. }));
    engine.set_speed(0.5, 1.0).unwrap();
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(20), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
    let out = capture.lock().unwrap().clone();
    out.chunks_exact(2).filter(|f| f[0] != 0.0).count()
}

#[test]
fn a_lowered_speed_cuts_a_blend_where_its_beats_would_be_heard_apart() {
    // 30 ms apart in song time by the end is 60 ms heard at half speed: the
    // 20 ms limit there is reached 1.33 s into the blend, where it is cut,
    // so A is heard for its 9 s and about 2.5 s more.
    let cut = heard_when_slowed(drifting_30);
    assert!(cut < RATE as usize * 12, "A heard for {cut} frames");
    assert!(cut > RATE as usize * 11, "A heard for {cut} frames");
    // 10 ms apart is 20 ms heard: the blend plays out, A for the blend's
    // 2 s of song heard over 4 s.
    let kept = heard_when_slowed(drifting_10);
    assert!(kept > RATE as usize * 25 / 2, "A heard for {kept} frames");
}

// What each plan made for a 30 s song was asked from: where it plans
// from, and whether its beats stay locked at any pace.
static ASKED: Mutex<Vec<(f64, i64, bool)>> = Mutex::new(Vec::new());

// A blend at 24 s already passed when the song is started at 25 s; asked
// again, a blend from where it is asked, ending before the song does.
fn asking_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let len = input.current.duration_ms as i64;
    let c = &input.context;
    if len == 30_000 {
        ASKED.lock().unwrap().push((c.pace, c.now_ms, c.lock_at_pace));
    }
    let mut plan = crate::automix::TransitionPlan::fixed_crossfade(len, 1_000, "asked");
    (plan.kind, plan.k, plan.entry_ms) = (crate::automix::TransitionKind::Blend, 0.4, 0);
    plan.start_ms = if c.now_ms < 26_000 { 24_000 } else { c.now_ms.min(len - 1_000) };
    plan
}

#[test]
fn a_blend_that_starts_too_soon_is_planned_again_from_where_the_next_song_is_ready() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 30, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 8, 0.3));
    for speed in [0.5, 1.0] {
        ASKED.lock().unwrap().clear();
        let (engine, events, _, _pump) = driven_engine();
        engine.set_test_planner(Some(asking_plan));
        engine.set_speed(speed as f32, 1.0).unwrap();
        engine.set_crossfade(3_000).unwrap();
        engine
            .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
            .unwrap();
        engine.load(vec![item("a", &a), item("b", &b)], 0, 25_000, true).unwrap();
        let planned = events.wait_for("plan", Duration::from_secs(10), |e| {
            matches!(e, EngineEvent::TransitionPlanned { .. })
        });
        let EngineEvent::TransitionPlanned { start_ms, reason, .. } = planned else { unreachable!() };
        engine.shutdown();
        let asked = ASKED.lock().unwrap().clone();
        // Asked twice: once from where the song is, once from where the next
        // song can be lined up and read again, nothing taken off.
        assert_eq!(asked.len(), 2, "speed {speed}: {asked:?}");
        let (now, again) = (asked[0].1, asked[1].1);
        let ready = now + (3_000.0 * speed) as i64 + (500.0 * speed) as i64;
        assert_eq!(again, ready, "speed {speed}: {asked:?}");
        assert!(asked.iter().all(|&(pace, _, locked)| pace == speed && locked), "{asked:?}");
        assert_eq!(start_ms as i64, ready, "speed {speed}: {reason}");
        assert!(!reason.contains("late"), "speed {speed}: {reason}");
    }
}

fn late_entry_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let len = input.current.duration_ms as i64;
    let mut plan = crate::automix::TransitionPlan::fixed_crossfade(len, 2_500, "test plan");
    plan.start_ms = 9_000;
    plan.entry_ms = 500;
    plan
}

#[test]
fn a_late_plan_into_a_song_entered_part_way_still_blends() {
    // Started inside a planned blend whose next song comes in 0.5 s into
    // itself: the next song is moved there, and must still blend in.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 12, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 4, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_test_planner(Some(late_entry_plan));
    engine.set_crossfade(3_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings {
            smart_transitions: true,
            filter_sweeps: false,
            match_tempo: false,
            max_overlap_ms: 8_000,
            fade_curve: crate::automix::FadeCurve::EqualPower,
            longest_intro_ms: 16_000,
        })
        .unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 9_500, true).unwrap();
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { reason, .. } = planned else { unreachable!() };
    assert!(reason.contains("late"), "{reason}");
    let joined = play_until(&pump, &events, "join", 400, |e| {
        matches!(e, EngineEvent::CrossfadeStarted { .. } | EngineEvent::GaplessTransition { .. })
    });
    assert!(matches!(joined, EngineEvent::CrossfadeStarted { .. }), "{joined:?}");
    engine.shutdown();
}

#[test]
fn starting_inside_the_blend_window_blends_over_what_is_left() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_crossfade(1_000).unwrap();
    // 2.2 s into a 3 s song: the blend at 2 s has already passed. Nothing is
    // played until the plan is made, however long song B takes to open.
    engine.load(vec![item("a", &a), item("b", &b)], 0, 2_200, true).unwrap();
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, overlap_ms, reason, .. } = planned else { unreachable!() };
    assert!((2_200..2_450).contains(&start_ms), "{start_ms}: {reason}");
    assert!((start_ms + overlap_ms).abs_diff(3_000) <= 1, "{reason}");
    assert!(reason.contains("late"), "{reason}");
    let fade =
        play_until(&pump, &events, "crossfade", 100, |e| matches!(e, EngineEvent::CrossfadeStarted { .. }));
    let EngineEvent::CrossfadeStarted { duration_ms, .. } = fade else { unreachable!() };
    assert!(duration_ms.abs_diff(overlap_ms) <= 1, "{duration_ms} vs {overlap_ms}");
    engine.shutdown();
}

#[test]
fn a_song_that_stops_short_of_its_length_still_hands_over() {
    // The file says 40 s, but its sound stops after 3 s: the next song is
    // decided once the decoder reaches the end, not 40 s in.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 40, 0.3));
    let bytes = std::fs::read(&a).unwrap();
    std::fs::write(&a, &bytes[..44 + RATE as usize * 3 * 4]).unwrap();
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_crossfade(1_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    play_until(
        &pump,
        &events,
        "b",
        600,
        |e| matches!(e, EngineEvent::TrackStarted { item_id, .. } if item_id == "b"),
    );
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    // The join that counts is decided again on the 3 s of sound: a blend
    // inside it, or a gapless join at its end when too little was left.
    let last_plan = events
        .all()
        .into_iter()
        .rfind(|e| matches!(e, EngineEvent::TransitionPlanned { .. }))
        .expect("a plan");
    let EngineEvent::TransitionPlanned { start_ms, overlap_ms, .. } = last_plan else { unreachable!() };
    assert!(start_ms + overlap_ms <= 3_000, "{start_ms} + {overlap_ms}");
    engine.shutdown();
}

// What the planner was handed when it planned from the songs' profiles:
// the hop of each section, and the level and tempo of the playing song.
type Handed = (Option<i32>, Option<i32>, Option<f64>, Option<f64>);

static HANDED: Mutex<Option<Handed>> = Mutex::new(None);

fn recording_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    if input.current.duration_ms == 12_000 {
        *HANDED.lock().unwrap() = Some((
            input.tail.map(|t| t.envelope.hop_ms),
            input.head.map(|h| h.envelope.hop_ms),
            input.context.body_level_db,
            input.context.tempo_prior,
        ));
    }
    test_plan(input)
}

fn profile_of(duration_ms: i64, tail_from_ms: i64) -> crate::automix::SongProfile {
    let tempo = crate::automix::SongProfileTempo {
        bpm: 120.0,
        beat_ms: 500.0,
        first_beat_ms: 0.0,
        downbeat_ms: 0.0,
        confidence: 2.0,
        consistency: 0.9,
        steady: true,
    };
    let section = |start_ms: i64, hops: usize| crate::automix::SongProfileSection {
        start_ms,
        hop_ms: 100,
        levels: vec![-20.0; hops],
        body_db: -20.0,
        gate_db: -65.0,
        sound_start_ms: Some(start_ms),
        sound_end_ms: Some(start_ms + hops as i64 * 100),
        outro_start_ms: Some(start_ms + hops as i64 * 100),
        intro_end_ms: Some(start_ms),
        boundaries_ms: Vec::new(),
        tempo: None,
    };
    let head_hops = (duration_ms.min(crate::automix::profile::PROFILE_HEAD_MS) / 100) as usize;
    let tail_hops = ((duration_ms - tail_from_ms) / 100) as usize;
    crate::automix::SongProfile {
        duration_ms,
        body_db: Some(-20.0),
        tempo: Some(tempo),
        head: section(0, head_hops),
        tail: section(tail_from_ms, tail_hops),
        revision: crate::automix::PROFILE_REVISION,
    }
}

// A 14 s blend ending with the outgoing song, the length of a long intro.
fn intro_length_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let len = input.current.duration_ms as i64;
    crate::automix::TransitionPlan::fixed_crossfade(len, 14_000, "a 14 s intro")
}

#[test]
fn a_long_intro_is_decided_before_it_starts() {
    // With the scouts never done, the decision waits for its deadline,
    // which must come early enough for a 14 s intro to start as planned
    // rather than late.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 24, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 20, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_test_planner(Some(intro_length_plan));
    engine.hold_scouts(true);
    engine.set_crossfade(8_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    let planned =
        play_until(&pump, &events, "plan", 1_500, |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, overlap_ms, reason, .. } = planned else { unreachable!() };
    assert_eq!((start_ms, overlap_ms), (10_000, 14_000), "{reason}");
    assert!(!reason.contains("late"), "{reason}");
    engine.shutdown();
}

// The next song's deck gain over the playing one's that the player planned
// with, and the planner's intro level given it and given decks at one gain.
static GAIN_PLANS: Mutex<Option<(f64, f64, f64)>> = Mutex::new(None);

fn gain_recording_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let plan = crate::automix::plan(input);
    let mut even = input.clone();
    even.context.incoming_gain_db = 0.0;
    let even_level = crate::automix::plan(&even).intro_level;
    *GAIN_PLANS.lock().unwrap() = Some((input.context.incoming_gain_db, plan.intro_level, even_level));
    plan
}

#[test]
fn an_intro_is_trimmed_for_the_gain_the_next_songs_deck_plays_it_at() {
    // ReplayGain on Track, the next song tagged 6 dB louder than the
    // playing one: the player plans with the next song 6 dB over it, and
    // the intro's level is the planner's for that, not for decks at one
    // gain. The next song's 11 s quiet start makes the intro.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 60, 0.3));
    let quiet = RATE as usize * 11;
    let mut next = sine(500.0, RATE, 2, 0, quiet, 0.06);
    next.extend(sine(500.0, RATE, 2, quiet, RATE as usize * 29, 0.3));
    write_wav(&b, RATE, 2, &next);
    let (engine, events, _, pump) = driven_engine();
    engine.set_test_planner(Some(gain_recording_plan));
    engine
        .set_replaygain(crate::sound::model::ReplayGainSettings {
            mode: crate::sound::model::ReplayGainMode::Track,
            ..Default::default()
        })
        .unwrap();
    engine.set_crossfade(8_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    let tagged = |id: &str, path: &Path, gain: f32| QueueItem {
        replay_gain: Some(crate::sound::replaygain::ReplayGainInfo {
            track_gain: Some(gain),
            track_peak: None,
            album_gain: None,
            album_peak: None,
        }),
        ..item(id, path)
    };
    engine.load(vec![tagged("a", &a, 0.0), tagged("b", &b, 6.0)], 0, 0, true).unwrap();
    let planned =
        play_until(&pump, &events, "plan", 1_500, |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { reason, .. } = planned else { unreachable!() };
    let (gain, level, even) = GAIN_PLANS.lock().unwrap().take().expect("a plan");
    assert!((gain - 6.0).abs() < 1e-3, "{gain}: {reason}");
    assert!(reason.contains("intro at"), "{reason}");
    assert!((level - even).abs() > 1e-3, "{level} against {even}: {reason}");
    engine.shutdown();
}

// The longest blend and the longest intro the planner was handed, for a
// playing song 13 s long.
static LENGTHS: Mutex<Option<(i64, i64)>> = Mutex::new(None);

fn length_recording_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    if input.current.duration_ms == 13_000 {
        *LENGTHS.lock().unwrap() = Some((input.settings.max_overlap_ms, input.settings.longest_intro_ms));
    }
    test_plan(input)
}

#[test]
fn the_longest_intro_is_kept_to_16_s_and_planned_in_song_time() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 13, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 4, 0.3));
    let (engine, events, _) = engine(2.0);
    engine.set_test_planner(Some(length_recording_plan));
    engine.set_speed(0.5, 1.0).unwrap();
    engine.set_crossfade(8_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings {
            smart_transitions: true,
            longest_intro_ms: 20_000,
            ..Default::default()
        })
        .unwrap();
    engine.set_song_profile("a".into(), Some(profile_of(13_000, 0))).unwrap();
    engine.set_song_profile("b".into(), Some(profile_of(4_000, 0))).unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    events.wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    // 8 s and 16 s of listening at half speed are 4 s and 8 s of the songs.
    assert_eq!(LENGTHS.lock().unwrap().take(), Some((4_000, 8_000)));
    engine.shutdown();
}

#[test]
fn a_song_with_a_profile_is_planned_from_it_without_the_live_level() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 12, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 4, 0.3));
    let (engine, events, _) = engine(2.0);
    engine.set_test_planner(Some(recording_plan));
    engine.set_crossfade(1_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings {
            smart_transitions: true,
            filter_sweeps: true,
            match_tempo: false,
            max_overlap_ms: 8_000,
            fade_curve: crate::automix::FadeCurve::EqualPower,
            longest_intro_ms: 16_000,
        })
        .unwrap();
    engine.set_song_profile("a".into(), Some(profile_of(12_000, 0))).unwrap();
    engine.set_song_profile("b".into(), Some(profile_of(4_000, 0))).unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    events.wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let handed = HANDED.lock().unwrap().take();
    // Both sections at the profile's 100 ms, not the scout's 10 ms; no level
    // from the live tap; the profile's whole-song tempo.
    assert_eq!(handed, Some((Some(100), Some(100), None, Some(120.0))));
    engine.shutdown();
}

#[test]
fn a_profile_that_is_not_whole_is_refused_and_one_can_be_forgotten() {
    let (engine, _, _) = engine(1.0);
    let mut empty = profile_of(12_000, 0);
    empty.tail.levels.clear();
    let refused = engine.set_song_profile("a".into(), Some(empty)).unwrap_err();
    assert!(matches!(&refused, crate::error::EngineError::Failed { kind: ErrorKind::InvalidArgument, .. }));
    let mut timeless = profile_of(12_000, 0);
    timeless.duration_ms = 0;
    assert!(engine.set_song_profile("a".into(), Some(timeless)).is_err());
    engine.set_song_profile("a".into(), Some(profile_of(12_000, 0))).unwrap();
    engine.set_song_profile("a".into(), None).unwrap();
    engine.shutdown();
}

#[test]
fn the_decoders_length_stands_over_the_listed_one() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
    let (engine, events, _) = engine(1.0);
    // Listed longer than the file is, as an outside song can be.
    let mut x = item("a", &a);
    x.duration_ms = Some(5_000);
    engine.load(vec![x.clone()], 0, 0, true).unwrap();
    events.wait_for("start", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let until = Instant::now() + Duration::from_secs(5);
    while engine.position().duration_ms != Some(2_000) {
        assert!(Instant::now() < until, "length {:?}", engine.position().duration_ms);
        std::thread::sleep(Duration::from_millis(5));
    }
    // The app handing the queue over again with the listed length does
    // not bring the listed length back.
    engine.replace_queue(vec![x], 0).unwrap();
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(engine.position().duration_ms, Some(2_000));
    engine.shutdown();
}

// Songs listed with lengths their sound does not have, as outside songs
// can be: the blend is timed by the lengths the decoders found. By its
// listing the next song is too short for more than 0.6 s of blend.
#[test]
fn a_crossfade_is_timed_by_the_decoded_lengths() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_crossfade(1_000).unwrap();
    let listed = |id: &str, path: &Path, ms: u64| QueueItem { duration_ms: Some(ms), ..item(id, path) };
    engine.load(vec![listed("a", &a, 60_000), listed("b", &b, 1_200)], 0, 0, true).unwrap();
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, entry_ms, overlap_ms, reason, .. } = planned else {
        unreachable!()
    };
    assert_eq!((start_ms, entry_ms, overlap_ms), (2_000, 0, 1_000), "{reason}");
    let fade =
        play_until(&pump, &events, "crossfade", 400, |e| matches!(e, EngineEvent::CrossfadeStarted { .. }));
    assert_eq!(
        fade,
        EngineEvent::CrossfadeStarted { from_id: "a".into(), to_id: "b".into(), duration_ms: 1_000 }
    );
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
}

// Says in its reason which lengths the planner was handed.
fn length_telling_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let len = input.current.duration_ms as i64;
    let why = format!("lengths {} and {}", input.current.duration_ms, input.next.duration_ms);
    crate::automix::TransitionPlan::fixed_crossfade(len, 500, &why)
}

#[test]
fn a_smart_plan_is_made_with_the_decoded_lengths() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 10, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _, pump) = driven_engine();
    engine.set_test_planner(Some(length_telling_plan));
    engine.set_crossfade(1_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    let listed = |id: &str, path: &Path| QueueItem { duration_ms: Some(60_000), ..item(id, path) };
    engine.load(vec![listed("a", &a), listed("b", &b)], 0, 0, true).unwrap();
    let planned =
        play_until(&pump, &events, "plan", 1_200, |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, overlap_ms, reason, .. } = planned else { unreachable!() };
    assert!(reason.contains("lengths 10000 and 3000"), "{reason}");
    assert_eq!((start_ms, overlap_ms), (9_500, 500), "{reason}");
    pump.play_out();
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
}

// A server for the song at `path` that answers no call until `open` is set,
// so the song's decoder opens only then.
fn held_server(path: &Path, open: Arc<AtomicBool>) -> crate::testing::http_server::ScriptServer {
    let body = std::fs::read(path).unwrap();
    crate::testing::http_server::ScriptServer::start(move |_, stream, stopped| {
        use std::io::Write;
        while !open.load(Ordering::Acquire) {
            if stopped.load(Ordering::Acquire) {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        let head = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        let _ = stream.write_all(head.as_bytes());
        let _ = stream.write_all(&body);
    })
}

// A server for the song at `path` that sends its first second and its last
// 64 KB at once (the decoder reads tags there) and the rest only once
// `open` is set, so the song's decoder opens and says how long it is, while
// a place further in has no sound until then.
fn held_after_start(path: &Path, open: Arc<AtomicBool>) -> crate::testing::http_server::ScriptServer {
    let body = std::fs::read(path).unwrap();
    let first = (44 + RATE as usize * 4).min(body.len());
    let tail = body.len().saturating_sub(64 << 10);
    crate::testing::http_server::ScriptServer::start(move |asked, stream, stopped| {
        use std::io::Write;
        let from = asked
            .header("range")
            .and_then(|r| r.strip_prefix("bytes=")?.split('-').next()?.parse::<usize>().ok())
            .unwrap_or(0)
            .min(body.len());
        let head = if from > 0 {
            format!(
                "HTTP/1.1 206 Partial Content\r\nContent-Type: audio/wav\r\nContent-Range: bytes {from}-{}/{}\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n",
                body.len() - 1,
                body.len(),
                body.len() - from
            )
        } else {
            format!(
                "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n",
                body.len()
            )
        };
        let _ = stream.write_all(head.as_bytes());
        let sent = if from >= tail { body.len() } else { from.max(first) };
        let _ = stream.write_all(&body[from..sent]);
        let _ = stream.flush();
        while !open.load(Ordering::Acquire) {
            if stopped.load(Ordering::Acquire) {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        let _ = stream.write_all(&body[sent..]);
    })
}

// A 16 s blend ending with the outgoing song, the longest intro, saying
// which lengths it was planned from.
fn longest_intro_plan(input: &crate::automix::PlanInput) -> crate::automix::TransitionPlan {
    let len = input.current.duration_ms as i64;
    let why = format!("lengths {} and {}", input.current.duration_ms, input.next.duration_ms);
    crate::automix::TransitionPlan::fixed_crossfade(len, 16_000, &why)
}

// Hands every event on to `recorder`, and sets `open` as a plan is told,
// so a held server answers only once the decision is made, whatever the
// test is doing then.
struct OpenOnPlan {
    recorder: Arc<Recorder>,
    open: Arc<AtomicBool>,
}

impl EngineListener for OpenOnPlan {
    fn on_event(&self, event: EngineEvent) {
        if matches!(event, EngineEvent::TransitionPlanned { .. }) {
            self.open.store(true, Ordering::Release);
        }
        self.recorder.on_event(event);
    }
}

#[test]
fn a_next_song_that_opens_late_still_has_its_intro_decided_in_time() {
    // A 24 s song with an 8 s crossfade and smart transitions, started 21 s
    // from its end: song B is lined up then, before the 20 s a 16 s intro,
    // its 3 s lead and the margin need. B's server answers nothing until the
    // plan is made, and with both songs profiled nothing else is waited
    // for: the decision is made 20 s from the end with B still opening, by
    // its listed length, in time for the intro to start as planned at 8 s.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 24, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 20, 0.3));
    let open = Arc::new(AtomicBool::new(false));
    let server = held_server(&b, open.clone());
    let late =
        QueueItem { source: server.url("/b.wav"), duration_ms: Some(20_000), ..item("b", Path::new("")) };
    let (engine, events, _, pump) = driven_engine();
    engine.set_listener(Arc::new(OpenOnPlan { recorder: events.clone(), open }));
    engine.set_test_planner(Some(longest_intro_plan));
    engine.set_crossfade(8_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    engine.set_song_profile("a".into(), Some(profile_of(24_000, 0))).unwrap();
    engine.set_song_profile("b".into(), Some(profile_of(20_000, 0))).unwrap();
    engine.load(vec![item("a", &a), late], 0, 3_000, true).unwrap();
    let planned =
        play_until(&pump, &events, "plan", 300, |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { start_ms, overlap_ms, reason, .. } = planned else { unreachable!() };
    assert_eq!((start_ms, overlap_ms), (8_000, 16_000), "{reason}");
    assert!(reason.contains("lengths 24000 and 20000"), "{reason}");
    assert!(!reason.contains("late"), "{reason}");
    // B opens after the decision and before the intro's start: the intro
    // plays as planned.
    let fade =
        play_until(&pump, &events, "intro", 600, |e| matches!(e, EngineEvent::CrossfadeStarted { .. }));
    assert_eq!(
        fade,
        EngineEvent::CrossfadeStarted { from_id: "a".into(), to_id: "b".into(), duration_ms: 16_000 }
    );
    engine.shutdown();
}

#[test]
fn a_next_song_lined_up_inside_its_intro_window_waits_for_its_decoder() {
    // Started 6 s into the same 24 s song, B is lined up 18 s from A's end,
    // inside the window a 16 s intro needs: it never had the time for one,
    // so the decision waits for B's decoder until the crossfade's own
    // deadline, 12 s from the end, and is made with the length the decoder
    // found rather than the one listed. A's server holds A's sound past
    // its first second, so the player knows A's length while A at 6 s has
    // no place yet: B is not lined up as if A were at its start, 24 s from
    // its end and before the window.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 24, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 20, 0.3));
    let a_open = Arc::new(AtomicBool::new(false));
    let a_server = held_after_start(&a, a_open.clone());
    let open = Arc::new(AtomicBool::new(false));
    let server = held_server(&b, open.clone());
    let held = QueueItem { source: a_server.url("/a.wav"), ..item("a", Path::new("")) };
    let late =
        QueueItem { source: server.url("/b.wav"), duration_ms: Some(60_000), ..item("b", Path::new("")) };
    let (engine, events, _, pump) = driven_engine();
    engine.set_test_planner(Some(length_telling_plan));
    engine.set_crossfade(8_000).unwrap();
    engine
        .set_automix(crate::automix::AutomixSettings { smart_transitions: true, ..Default::default() })
        .unwrap();
    engine.set_song_profile("a".into(), Some(profile_of(24_000, 0))).unwrap();
    engine.set_song_profile("b".into(), Some(profile_of(20_000, 0))).unwrap();
    engine.load(vec![held, late], 0, 6_000, true).unwrap();
    wait_until("A's length known", || engine.infos_len() >= 1);
    a_open.store(true, Ordering::Release);
    // B is asked for once it is lined up, as the decision is first tried;
    // a little more play passes the decision again, with B still opening.
    wait_until("B asked for", || !server.asked().is_empty());
    for _ in 0..20 {
        pump.play();
    }
    let early = events.find(|e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    assert!(early.is_none(), "decided before B opened: {early:?}");
    // Nothing more is played, so only B's decoder can bring the decision.
    open.store(true, Ordering::Release);
    let planned = events
        .wait_for("plan", Duration::from_secs(10), |e| matches!(e, EngineEvent::TransitionPlanned { .. }));
    let EngineEvent::TransitionPlanned { reason, .. } = planned else { unreachable!() };
    assert!(reason.contains("lengths 24000 and 20000"), "{reason}");
    engine.shutdown();
}

#[test]
fn a_live_radio_stream_plays_on_with_its_titles_and_takes_no_seek() {
    use crate::testing::http_server::ScriptServer;
    let server = ScriptServer::start(|_, conn, stopped| super::live::icy_radio(stopped, conn));
    let (engine, events, _, pump) = driven_engine();
    let radio = QueueItem { source: server.url("/stream"), live: true, ..item("radio", Path::new("")) };
    // A start time means nothing to a live stream.
    engine.load(vec![radio], 0, 5_000, true).unwrap();
    let started =
        play_until(&pump, &events, "start", 1_000, |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let EngineEvent::TrackStarted { info: Some(info), .. } = started else { panic!("{started:?}") };
    assert_eq!((info.codec.as_str(), info.duration_ms), ("mp3", None));
    let second =
        |e: &EngineEvent| matches!(e, EngineEvent::StreamTitle { title, .. } if title.ends_with("Song 1"));
    play_until(&pump, &events, "the second title", 1_000, second);
    // Block 3 is a second into the stream: the title comes as that is heard.
    let heard = engine.position().position_ms;
    assert!((900.0..2_000.0).contains(&heard), "{heard}");
    let titles: Vec<(String, String, String)> = events
        .all()
        .into_iter()
        .filter_map(|e| match e {
            EngineEvent::StreamTitle { item_id, title, url } => Some((item_id, title, url)),
            _ => None,
        })
        .collect();
    let named = |t: &str, url: &str| ("radio".to_string(), t.to_string(), url.to_string());
    assert_eq!(
        titles,
        [
            named("Test FM - Song 0", "https://img.test/0.jpg"),
            named("Test FM - Song 1", "https://img.test/1.jpg")
        ]
    );
    // A seek is ignored: it plays on from where it was, over the same connection.
    engine.seek(60_000).unwrap();
    for _ in 0..50 {
        pump.play();
    }
    let after = engine.position().position_ms;
    assert!(after > heard && after < heard + 2_000.0, "{heard} then {after}");
    assert_eq!(server.asked().len(), 1);
    assert_eq!(events.story(), ["start radio"]);
    engine.shutdown();
}

#[test]
fn a_live_stream_paused_a_long_while_starts_again_where_it_is_now() {
    use crate::testing::http_server::ScriptServer;
    let server = ScriptServer::start(|_, conn, stopped| super::live::icy_radio(stopped, conn));
    let (engine, events, _, pump) = driven_engine();
    let radio = QueueItem { source: server.url("/stream"), live: true, ..item("radio", Path::new("")) };
    engine.load(vec![radio], 0, 0, true).unwrap();
    play_until(&pump, &events, "start", 1_000, |e| matches!(e, EngineEvent::TrackStarted { .. }));
    for _ in 0..20 {
        pump.play();
    }
    // A short pause carries on over the same connection.
    engine.pause().unwrap();
    pump.play();
    engine.play().unwrap();
    for _ in 0..20 {
        pump.play();
    }
    assert_eq!(server.asked().len(), 1);
    // A long one (300 ms in tests) connects again, at the live edge. The
    // pause counts from when the engine takes it, so the wait starts once
    // it says it is paused.
    let paused = |e: &EngineEvent| matches!(e, EngineEvent::StateChanged { state: PlaybackState::Paused });
    engine.pause().unwrap();
    pump.play();
    wait_until("paused again", || events.all().iter().filter(|e| paused(e)).count() == 2);
    std::thread::sleep(Duration::from_millis(400));
    let before = events.all().len();
    engine.play().unwrap();
    // Until the engine takes the play, the output is paused and a pump
    // gives silence at once; after it, each pump waits for sound.
    let playing =
        |e: &EngineEvent| matches!(e, EngineEvent::StateChanged { state } if *state != PlaybackState::Paused);
    wait_until("playing again", || events.all()[before..].iter().any(playing));
    let again = |e: &EngineEvent| matches!(e, EngineEvent::TrackStarted { .. });
    let until = Instant::now() + Duration::from_secs(10);
    while events.all().iter().filter(|e| again(e)).count() < 2 {
        assert!(Instant::now() < until, "never started again: {:?}", events.story());
        pump.play();
    }
    assert_eq!(server.asked().len(), 2);
    assert!(engine.position().position_ms < 2_000.0, "{}", engine.position().position_ms);
    engine.shutdown();
}

#[test]
fn the_player_keeps_infos_only_for_queued_songs() {
    let dir = temp_dir();
    let songs: Vec<QueueItem> = ["a", "b", "c", "d", "e", "f", "x"]
        .iter()
        .map(|id| {
            let path = dir.join(format!("{id}.wav"));
            write_wav(&path, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
            item(id, &path)
        })
        .collect();
    let (driver, pump) = NullDriver::driven(RATE, 2, None);
    let engine = Engine::with_driver(Box::new(move || Box::new(driver)));
    let events = Arc::new(Recorder::default());
    engine.set_listener(events.clone());
    let mut first = songs[..3].to_vec();
    first.push(songs[6].clone());
    engine.load(first, 0, 0, true).unwrap();
    let started_x = || events.starts().iter().any(|id| id == "x");
    for _ in 0..3_000 {
        pump.play();
        if started_x() {
            break;
        }
    }
    wait_until("the start of x", started_x);
    engine.load(songs[3..6].to_vec(), 0, 0, true).unwrap();
    for _ in 0..3_000 {
        pump.play();
        if events.starts().iter().any(|id| id == "d") {
            break;
        }
    }
    wait_until("the start of d", || events.starts().iter().any(|id| id == "d"));
    // About 2 s more.
    for _ in 0..200 {
        pump.play();
    }
    // Three queued and the heard one.
    assert!(engine.infos_len() <= 4, "infos kept {}", engine.infos_len());
    engine.shutdown();
}

#[test]
fn track_infos_are_counted_and_given_back() {
    static INFOS: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
    let dir = temp_dir();
    let songs: Vec<QueueItem> = ["a", "b"]
        .iter()
        .map(|id| {
            let path = dir.join(format!("{id}.wav"));
            write_wav(&path, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
            item(id, &path)
        })
        .collect();
    let (driver, pump) = NullDriver::driven(RATE, 2, None);
    let engine = Engine::with_driver_counting(Box::new(move || Box::new(driver)), &INFOS);
    engine.load(songs, 0, 0, true).unwrap();
    for _ in 0..3_000 {
        pump.play();
        if engine.infos_len() > 0 {
            break;
        }
    }
    // Both are written on the player thread, so they are read until they agree.
    wait_until("the count to follow the infos", || {
        let held = engine.infos_len() as i64;
        held > 0 && INFOS.load(Ordering::Relaxed) == held
    });
    engine.shutdown();
    assert_eq!(INFOS.load(Ordering::Relaxed), 0);
}

#[test]
fn device_reopens_are_counted() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 5, 0.3));
    let (driver, pump) = NullDriver::driven(RATE, 2, None);
    let (trouble, refuse) = (driver.events(), driver.refusal());
    let engine = Engine::with_driver(Box::new(move || Box::new(driver)));
    let events = Arc::new(Recorder::default());
    engine.set_listener(events.clone());
    engine.load(vec![item("a", &a)], 0, 0, true).unwrap();
    pump.play();
    events.wait_for("start", Duration::from_secs(10), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    for _ in 0..3 {
        trouble.lock().unwrap().push(DeviceEvent::Lost("t".into()));
        for _ in 0..5 {
            pump.play();
        }
    }
    wait_until("three reopens", || {
        pump.play();
        engine.device_reopens() == 3
    });
    // A device that will not open is not counted, however often it is tried.
    refuse.store(true, Ordering::Release);
    trouble.lock().unwrap().push(DeviceEvent::Lost("t".into()));
    std::thread::sleep(Duration::from_secs(3));
    assert_eq!(engine.device_reopens(), 3);
    refuse.store(false, Ordering::Release);
    wait_until("the fourth reopen", || {
        pump.play();
        engine.device_reopens() == 4
    });
    engine.shutdown();
}
