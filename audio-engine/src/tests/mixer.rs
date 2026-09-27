//! Crossfades, markers and the whole mixing chain.

use std::path::Path;
use std::time::{Duration, Instant};

use crate::crossfade::{fade_in_volume, fade_out_volume};
use crate::deck::Deck;
use crate::lane::Lane;
use crate::mixer::{Marker, MixState, Mixer, Transition};
use crate::pace::Pace;
use crate::sound::model::ReplayGainSettings;
use crate::sound::model::SoundSettings;
use crate::sound::replaygain::Loudness;
use crate::source::http::HttpOptions;
use crate::testing::fixtures::*;

const RATE: u32 = 48_000;

fn deck(key: u64, path: &Path) -> Deck {
    Deck::open(key, path.to_string_lossy().into(), 0.0, HttpOptions::default())
}

fn wait_ready(d: &Deck) {
    let until = Instant::now() + Duration::from_secs(5);
    while !d.is_ready() {
        assert!(Instant::now() < until);
        std::thread::sleep(Duration::from_millis(1));
    }
}

// Renders everything, waiting out the decks.
fn render_all(mixer: &mut Mixer) -> (Vec<f32>, Vec<Marker>) {
    let mut out = Vec::new();
    let mut markers = Vec::new();
    let mut block = vec![0.0; 1024];
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        let (n, state) = mixer.render(&mut block, &mut markers);
        out.extend_from_slice(&block[..n * 2]);
        // Once a crossfade has begun, nothing follows the incoming song.
        if mixer.is_fading()
            && let Some(lane) = mixer.lane_mut()
        {
            lane.last = true;
        }
        if state == MixState::Ended && n == 0 {
            return (out, markers);
        }
        if n == 0 {
            assert!(Instant::now() < until, "mixer stalled");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}

fn dc_file(path: &Path, frames: usize, left: i16, right: i16) {
    let samples: Vec<i16> = (0..frames).flat_map(|_| [left, right]).collect();
    write_wav(path, RATE, 2, &samples);
}

#[test]
fn crossfade_is_equal_power_and_on_time() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    // Song A only on the left, song B only on the right.
    dc_file(&a, 96_000, 16_384, 0);
    dc_file(&b, 96_000, 0, 16_384);
    let settings = SoundSettings::default();
    let mut mixer = Mixer::new(RATE, 0, &settings, Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    let fade_frames = 24_000u64; // 0.5 s
    mixer.plan_fade(1, 1.5, fade_frames, next, Loudness::default());
    let (out, markers) = render_all(&mut mixer);

    let latency = 72; // the limiter's delay at 48 kHz
    let fade_start = 72_000 + latency;
    // Before: all A. After: all B.
    assert_eq!(out[(fade_start - 10) * 2], 0.5);
    assert_eq!(out[(fade_start - 10) * 2 + 1], 0.0);
    for i in (0..fade_frames as usize).step_by(997) {
        let p = i as f32 / fade_frames as f32;
        let (l, r) = (out[(fade_start + i) * 2], out[(fade_start + i) * 2 + 1]);
        assert!((l - 0.5 * fade_out_volume(p)).abs() < 1e-6, "left at {i}");
        assert!((r - 0.5 * fade_in_volume(p)).abs() < 1e-6, "right at {i}");
        // Equal power: the total level never dips or swells.
        assert!(((l * l + r * r) - 0.25).abs() < 1e-5);
    }
    let after = fade_start + fade_frames as usize + 10;
    assert_eq!((out[after * 2], out[after * 2 + 1]), (0.0, 0.5));
    // B plays from its start at the fade, so the whole is A's 1.5 s plus B.
    assert_eq!(out.len() / 2, 72_000 + 96_000 + latency);

    let fade = markers.iter().find(|m| m.key == 2).expect("marker for B");
    assert_eq!(fade.frame, fade_start as u64);
    assert_eq!(fade.secs, 0.0);
    assert_eq!(fade.transition, Some(Transition::Crossfade { from: 1, frames: fade_frames }));
}

#[test]
fn gapless_marker_lands_on_the_join() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    dc_file(&a, 10_000, 1_000, 1_000);
    dc_file(&b, 10_000, 2_000, 2_000);
    let mut mixer = Mixer::new(RATE, 500, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.set_next(Some(deck(2, &b)), Loudness::default());
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let (out, markers) = render_all(&mut mixer);
    assert_eq!(out.len() / 2, 20_000 + 72);
    let first = markers[0];
    assert_eq!(
        (first.frame, first.key, first.secs, first.transition),
        (500 + 72, 1, 0.0, Some(Transition::Start))
    );
    let join = markers.iter().find(|m| m.key == 2).unwrap();
    assert_eq!((join.frame, join.secs, join.transition), (500 + 72 + 10_000, 0.0, Some(Transition::Gapless)));
    assert_eq!(markers.len(), 2, "steady play needs no more markers: {markers:?}");
}

#[test]
fn markers_follow_speed() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, 96_000, 0.4));
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace { speed: 1.5, pitch: 1.0 });
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let (out, markers) = render_all(&mut mixer);
    let frames = out.len() / 2;
    // The start is told even though the speed stage blurs the first frame.
    assert_eq!(markers.iter().filter(|m| m.transition == Some(Transition::Start)).count(), 1);
    // The last few periods inside the speed stage come out unchanged.
    assert!((frames as f64 - 64_000.0).abs() < 600.0, "{frames}");
    // Wherever the clock is read, it tells the song time at 1.5x.
    // (The last few periods, played out unchanged at the end, are left out.)
    for m in markers.iter().filter(|m| m.frame < frames as u64 - 2_000) {
        let expected = (m.frame.saturating_sub(72)) as f64 * 1.5 / RATE as f64;
        assert!((m.secs - expected).abs() < 0.01, "{m:?}");
        assert!((m.secs_per_frame - 1.5 / RATE as f64).abs() < 1e-12);
    }
}

#[test]
fn seeking_marks_the_new_place() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, 96_000, 0.4));
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let mut block = vec![0.0; 4_800 * 2];
    let mut markers = Vec::new();
    let until = Instant::now() + Duration::from_secs(5);
    let mut made = 0;
    while made < 4_800 {
        made += mixer.render(&mut block, &mut markers).0;
        assert!(Instant::now() < until);
    }
    mixer.seek(1.25);
    markers.clear();
    let until = Instant::now() + Duration::from_secs(5);
    loop {
        let (n, _) = mixer.render(&mut block, &mut markers);
        if n > 0 {
            break;
        }
        assert!(Instant::now() < until);
        std::thread::sleep(Duration::from_millis(1));
    }
    let seek = markers.iter().find(|m| m.transition == Some(Transition::Seek)).expect("seek marker");
    assert!((seek.secs - 1.25).abs() < 1e-9);
}
