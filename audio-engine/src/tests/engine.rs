//! The whole engine, playing through the silent device.

use std::path::Path;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::api::{EndReason, Engine, EngineEvent, EngineListener, PlaybackState, QueueItem, RepeatMode};
use crate::error::ErrorKind;
use crate::output::null::{Capture, NullDriver};
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
fn events_arrive_at_other_speeds_too() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, 30_000, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 30_000, 30_000, 0.3));
    let (engine, events, _) = engine(2.0);
    engine.set_speed(1.5, 1.0).unwrap();
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
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
fn a_seek_before_the_song_is_heard_still_starts_it() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 3, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize, 0.3));
    let (engine, events, _) = engine(4.0);
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    // Straight after the load, before anything is heard.
    engine.seek(2_500).unwrap();
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

#[test]
fn the_same_songs_given_again_keep_the_next_one_lined_up() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 2, 0.3));
    write_wav(&b, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize / 2, 0.3));
    let (engine, events, _) = engine(2.0);
    engine.load(vec![item("a", &a), item("b", &b)], 0, 0, true).unwrap();
    events.wait_for("start", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    // By now b is lined up and read. With its file gone, only the song
    // already lined up can still play.
    std::thread::sleep(Duration::from_millis(150));
    std::fs::remove_file(&b).unwrap();
    engine.replace_upcoming(vec![item("b", &b)]).unwrap();
    engine.replace_queue(vec![item("a", &a), item("b", &b)], 0).unwrap();
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
    let (engine, events, _) = engine(4.0);
    engine.load(songs.clone(), 2, 0, true).unwrap();
    events.wait_for("start c", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let before = engine.position();
    // The index given is stale on purpose: the playing song is found by id.
    let [a, b, c, d] = [0, 1, 2, 3].map(|i| songs[i].clone());
    engine.replace_queue(vec![d, a, c, b], 0).unwrap();
    std::thread::sleep(Duration::from_millis(30));
    let after = engine.position();
    assert_eq!(after.item_id.as_deref(), Some("c"));
    assert!(after.position_ms >= before.position_ms, "went back from {before:?} to {after:?}");
    let queue = engine.queue();
    assert_eq!(queue.item_ids, ["d", "a", "c", "b"]);
    assert_eq!(queue.current_index, Some(2));
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
    let (engine, events, _) = engine(4.0);
    engine.set_repeat(RepeatMode::All).unwrap();
    engine.load(songs.clone(), 0, 0, true).unwrap();
    events.wait_for("start a", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    let [a, b, c] = [0, 1, 2].map(|i| songs[i].clone());
    engine.replace_queue(vec![c, a, b], 1).unwrap();
    let until = Instant::now() + Duration::from_secs(10);
    while events.starts().len() < 4 {
        assert!(Instant::now() < until, "{:?}", events.all());
        std::thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(events.starts()[..4], ["a", "b", "c", "a"]);
    engine.shutdown();
}

#[test]
fn a_lost_device_that_will_not_reopen_carries_on_once_one_does() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, RATE as usize * 20, 0.3));
    let driver = NullDriver::new(RATE, 2);
    let (trouble, refuse) = (driver.events(), driver.refusal());
    let engine = Engine::with_driver(Box::new(move || Box::new(driver)));
    let events = Arc::new(Recorder::default());
    engine.set_listener(events.clone());
    engine.load(vec![item("a", &a)], 0, 0, true).unwrap();
    events.wait_for("start", Duration::from_secs(5), |e| matches!(e, EngineEvent::TrackStarted { .. }));
    std::thread::sleep(Duration::from_millis(300));

    let device_errors = || {
        events
            .all()
            .iter()
            .filter(|e| matches!(e, EngineEvent::Error { kind: ErrorKind::Device, .. }))
            .count()
    };
    // Unplugged, with nothing else to play to. Answers where it stopped.
    let unplug = || {
        refuse.store(true, Ordering::Release);
        trouble.lock().unwrap().push(DeviceEvent::Lost("unplugged".into()));
        std::thread::sleep(Duration::from_millis(100));
        let held = engine.position();
        assert_eq!(held.item_id.as_deref(), Some("a"));
        std::thread::sleep(Duration::from_millis(200));
        assert_eq!(engine.position().position_ms, held.position_ms, "the place is held");
        held.position_ms
    };
    // Carries on from the place held, not from the start.
    let carries_on_from = |held: f64| {
        let until = Instant::now() + Duration::from_secs(5);
        let moved = loop {
            let p = engine.position();
            if p.position_ms > held + 50.0 {
                break p;
            }
            assert!(Instant::now() < until, "never carried on from {held}; got {:?}", events.all());
            std::thread::sleep(Duration::from_millis(5));
        };
        assert_eq!(moved.item_id.as_deref(), Some("a"));
        assert!(moved.position_ms < held + 400.0, "from {held} to {}", moved.position_ms);
    };

    // Back when Play is pressed.
    let held = unplug();
    assert!(held > 250.0, "{held}");
    assert_eq!(device_errors(), 1);
    refuse.store(false, Ordering::Release);
    engine.play().unwrap();
    carries_on_from(held);

    // Back when an output is chosen.
    let held = unplug();
    refuse.store(false, Ordering::Release);
    engine.set_output_device(None).unwrap();
    carries_on_from(held);

    // Back by itself, trying again now and then without an error each time.
    let held = unplug();
    std::thread::sleep(Duration::from_millis(2_500));
    assert_eq!(engine.position().position_ms, held);
    assert_eq!(device_errors(), 3);
    refuse.store(false, Ordering::Release);
    carries_on_from(held);
    assert_eq!(device_errors(), 3);
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
    events.wait_for("left heard", Duration::from_secs(10), |e| {
        matches!(e, EngineEvent::Position { item_id, .. } if item_id == "left")
    });
    engine.skip_to(1).unwrap();
    // A word already on its way is fine; after that, none for the song left.
    std::thread::sleep(Duration::from_millis(300));
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
                let head = format!("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                let _ = stream.write_all(head.as_bytes());
                let _ = stream.write_all(&body);
            });
        }
    });
    let late = QueueItem { source: url, ..item("late", Path::new("")) };

    let (engine, events, _) = engine(1.0);
    engine.set_position_interval(50).unwrap();
    engine.load(vec![item("left", &a), late], 0, 0, true).unwrap();
    events.wait_for("left heard", Duration::from_secs(10), |e| {
        matches!(e, EngineEvent::Position { item_id, .. } if item_id == "left")
    });
    engine.skip_to(1).unwrap();
    std::thread::sleep(Duration::from_millis(150));
    let before = events.all().len();
    events.wait_for("late started", Duration::from_secs(10), |e| {
        matches!(e, EngineEvent::TrackStarted { item_id, .. } if item_id == "late")
    });
    std::thread::sleep(Duration::from_millis(300));
    let after: Vec<EngineEvent> = events.all().split_off(before);
    let stale: Vec<_> = after
        .iter()
        .filter(|e| matches!(e, EngineEvent::Position { item_id, .. } if item_id == "left"))
        .collect();
    assert!(stale.is_empty(), "told of the song left after the skip: {after:?}");
    engine.shutdown();
}
