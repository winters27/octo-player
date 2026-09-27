//! The whole engine, playing through the silent device.

use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::api::{EndReason, Engine, EngineEvent, EngineListener, PlaybackState, QueueItem};
use crate::error::ErrorKind;
use crate::output::null::{Capture, NullDriver};
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

fn item(id: &str, path: &Path) -> QueueItem {
    QueueItem {
        id: id.into(),
        source: path.to_string_lossy().into(),
        album_id: None,
        album_order: None,
        duration_ms: None,
        replay_gain: None,
        headers: Vec::new(),
    }
}

#[test]
fn plays_a_queue_gaplessly_with_events() {
    let dir = temp_dir();
    let first = sine(441.0, RATE, 2, 0, 24_000, 0.5);
    let second = sine(441.0, RATE, 2, 24_000, 24_000, 0.5);
    let (a, b) = (dir.join("a.flac"), dir.join("b.wav"));
    write_flac(&a, RATE, 2, &first, &[]);
    write_wav(&b, RATE, 2, &second);
    let (engine, events, capture) = engine(1.0);
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
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
    assert_eq!(engine.underruns(), 0, "the device never ran dry");
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
fn clock_is_monotonic_and_tracks_real_time() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _) = engine(1.0);
    engine.load(vec![item("a", &a)], 0, 0, true).unwrap();
    events.wait_for("start", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let started = Instant::now();
    let first = engine.position().position_ms;
    let mut last = first;
    let mut samples = 0;
    while started.elapsed() < Duration::from_millis(1_200) {
        let p = engine.position();
        assert_eq!(p.item_id.as_deref(), Some("a"));
        assert!(p.position_ms >= last, "went back from {last} to {}", p.position_ms);
        last = p.position_ms;
        samples += 1;
        std::thread::sleep(Duration::from_millis(1));
    }
    // (How often the loop runs depends on the timer resolution of the machine.)
    assert!(samples > 20);
    // It moved as fast as time did, within the device's buffer.
    let moved = last - first;
    let elapsed = started.elapsed().as_secs_f64() * 1_000.0;
    assert!((moved - elapsed).abs() < 40.0, "moved {moved} ms in {elapsed} ms");
    engine.shutdown();
}

#[test]
fn seek_and_start_position_are_accurate() {
    let dir = temp_dir();
    let a = dir.join("a.flac");
    write_flac(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 5, 0.3), &[]);
    let (engine, events, _) = engine(1.0);
    engine.load(vec![item("a", &a)], 0, 1_000, false).unwrap();
    // Loaded paused at 1 s: that is the position before anything plays.
    std::thread::sleep(Duration::from_millis(50));
    assert!((engine.position().position_ms - 1_000.0).abs() < 1.0);
    engine.play().unwrap();
    events.wait_for("start", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let p = engine.position().position_ms;
    assert!((1_000.0..1_100.0).contains(&p), "{p}");
    engine.seek(3_250).unwrap();
    // Straight after the call the position already shows the target.
    std::thread::sleep(Duration::from_millis(5));
    let p = engine.position().position_ms;
    assert!((p - 3_250.0).abs() < 15.0, "{p}");
    std::thread::sleep(Duration::from_millis(300));
    let p = engine.position().position_ms;
    assert!((3_450.0..3_600.0).contains(&p), "{p}");
    engine.shutdown();
}

#[test]
fn crossfades_between_songs_from_different_albums() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    write_wav(&b, RATE, 2, &sine(500.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    let (engine, events, _) = engine(4.0);
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
    let fade = events.wait_for("crossfade", Duration::from_secs(10), |e| {
        matches!(e, EngineEvent::CrossfadeStarted { .. })
    });
    assert_eq!(
        fade,
        EngineEvent::CrossfadeStarted { from_id: "a".into(), to_id: "b".into(), duration_ms: 1_000 }
    );
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
}

#[test]
fn an_album_in_order_stays_gapless_with_crossfade_on() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
    let (engine, events, _) = engine(4.0);
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
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    let all = events.all();
    assert!(all.iter().any(|e| matches!(e, EngineEvent::GaplessTransition { .. })), "{all:?}");
    assert!(!all.iter().any(|e| matches!(e, EngineEvent::CrossfadeStarted { .. })));
    engine.shutdown();
}

#[test]
fn a_missing_song_is_reported_and_skipped() {
    let dir = temp_dir();
    let b = dir.join("b.wav");
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, 12_000, 0.3));
    let (engine, events, _) = engine(4.0);
    let missing = item("gone", Path::new("Z:/no/such/song.flac"));
    engine.load(vec![missing, item("b", &b)], 0, 0, true).unwrap();
    let error = events.wait_for("error", Duration::from_secs(5), |e| matches!(e, EngineEvent::Error { .. }));
    assert!(
        matches!(error, EngineEvent::Error { kind: ErrorKind::NotFound, item_id: Some(ref id), .. } if id == "gone")
    );
    events.wait_for(
        "b",
        Duration::from_secs(5),
        |e| matches!(e, EngineEvent::TrackStarted { item_id, .. } if item_id == "b"),
    );
    events.wait_for("queue end", Duration::from_secs(10), |e| matches!(e, EngineEvent::QueueEnded));
    engine.shutdown();
}

#[test]
fn pause_resume_skip_and_stop() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 4, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 4, 0.3));
    let (engine, events, _) = engine(1.0);
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    events.wait_for("start", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    std::thread::sleep(Duration::from_millis(200));
    engine.pause().unwrap();
    std::thread::sleep(Duration::from_millis(60));
    assert_eq!(engine.state(), PlaybackState::Paused);
    let held = engine.position().position_ms;
    std::thread::sleep(Duration::from_millis(200));
    assert!((engine.position().position_ms - held).abs() < 1.0, "the clock stands still while paused");
    engine.play().unwrap();
    std::thread::sleep(Duration::from_millis(200));
    assert!(engine.position().position_ms > held + 100.0);
    engine.skip_next().unwrap();
    events.wait_for(
        "b",
        Duration::from_secs(5),
        |e| matches!(e, EngineEvent::TrackStarted { item_id, .. } if item_id == "b"),
    );
    assert!(
        events.all().contains(&EngineEvent::TrackEnded { item_id: "a".into(), reason: EndReason::Skipped })
    );
    assert_eq!(engine.queue().current_index, Some(1));
    engine.stop().unwrap();
    events.wait_for(
        "stopped",
        Duration::from_secs(5),
        |e| matches!(e, EngineEvent::TrackEnded { item_id, reason: EndReason::Stopped } if item_id == "b"),
    );
    std::thread::sleep(Duration::from_millis(30));
    assert_eq!(engine.state(), PlaybackState::Idle);
    engine.shutdown();
}
