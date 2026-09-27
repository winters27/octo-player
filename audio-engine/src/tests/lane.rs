//! Gapless joins and the change to the device rate.

use std::path::Path;
use std::time::{Duration, Instant};

use crate::deck::Deck;
use crate::lane::{Lane, LaneState, Span};
use crate::sound::model::{ReplayGainMode, ReplayGainSettings};
use crate::sound::replaygain::{Loudness, ReplayGainInfo};
use crate::source::http::HttpOptions;
use crate::testing::fixtures::*;

fn deck(key: u64, path: &Path) -> Deck {
    Deck::open(key, path.to_string_lossy().into(), 0.0, HttpOptions::default())
}

// Plays the whole lane, collecting the sound and the song spans.
fn play_out(lane: &mut Lane) -> (Vec<f32>, Vec<Span>) {
    let mut all = Vec::new();
    let mut spans = Vec::new();
    let mut block = vec![0.0; 480 * 2];
    let mut got = Vec::new();
    let until = Instant::now() + Duration::from_secs(20);
    loop {
        let state = lane.fill(480);
        let n = lane.pull(&mut block, &mut got);
        all.extend_from_slice(&block[..n * 2]);
        for s in &got {
            match spans.last_mut() {
                Some(Span { key, frames, .. }) if *key == s.key && !s.joined => *frames += s.frames,
                _ => spans.push(*s),
            }
        }
        if state == LaneState::Ended && lane.available() == 0 {
            return (all, spans);
        }
        if n == 0 {
            assert!(Instant::now() < until, "lane stalled");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}

#[test]
fn gapless_join_is_sample_exact() {
    let dir = temp_dir();
    let rate = 48_000;
    // One unbroken tone cut into two files at an awkward frame.
    let first = sine(997.0, rate, 2, 0, 33_333, 0.6);
    let second = sine(997.0, rate, 2, 33_333, 20_000, 0.6);
    let (a, b) = (dir.join("a.flac"), dir.join("b.wav"));
    write_flac(&a, rate, 2, &first, &[]);
    write_wav(&b, rate, 2, &second);

    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), rate);
    lane.set_next(Some(deck(2, &b)), Loudness::default());
    lane.last = true;
    let (out, spans) = play_out(&mut lane);

    let expected: Vec<f32> = first.iter().chain(&second).map(|&s| s as f32 / 32768.0).collect();
    assert_eq!(out.len(), expected.len());
    assert_eq!(out, expected, "the join adds, drops or changes nothing");
    assert_eq!(spans.len(), 2, "{spans:?}");
    assert_eq!((spans[0].key, spans[0].frames, spans[0].joined), (1, 33_333, false));
    assert_eq!((spans[1].key, spans[1].frames, spans[1].joined), (2, 20_000, true));
    assert_eq!(spans[1].start_secs, 0.0);
}

#[test]
fn gapless_join_through_the_resampler_is_seamless() {
    let dir = temp_dir();
    let rate = 44_100;
    let first = sine(1_000.0, rate, 2, 0, 30_001, 0.5);
    let second = sine(1_000.0, rate, 2, 30_001, 25_000, 0.5);
    let whole: Vec<i16> = first.iter().chain(&second).copied().collect();
    let (a, b, c) = (dir.join("a.flac"), dir.join("b.flac"), dir.join("whole.flac"));
    write_flac(&a, rate, 2, &first, &[]);
    write_flac(&b, rate, 2, &second, &[]);
    write_flac(&c, rate, 2, &whole, &[]);

    let mut split = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), 48_000);
    split.set_next(Some(deck(2, &b)), Loudness::default());
    split.last = true;
    let (joined, spans) = play_out(&mut split);

    let mut single = Lane::new(deck(3, &c), Loudness::default(), &ReplayGainSettings::default(), 48_000);
    single.last = true;
    let (reference, _) = play_out(&mut single);

    // The two songs go through the resampler as one stream: the output is
    // the same as resampling the whole tone in one piece.
    assert_eq!(joined.len(), reference.len());
    assert_eq!(joined, reference);
    let expected_len = (whole.len() / 2) as f64 * 48_000.0 / 44_100.0;
    assert!(((joined.len() / 2) as f64 - expected_len).abs() <= 1.0);
    // The second song begins where its first frame lands at the new rate.
    let join_at = 30_001.0 * 48_000.0 / 44_100.0;
    assert!((spans[0].frames as f64 - join_at).abs() <= 1.0, "{spans:?}");
}

#[test]
fn resampler_keeps_pitch_level_and_timing() {
    let dir = temp_dir();
    let path = dir.join("tone.wav");
    let samples = sine(1_000.0, 44_100, 2, 0, 44_100, 0.5);
    write_wav(&path, 44_100, 2, &samples);
    let mut lane = Lane::new(deck(1, &path), Loudness::default(), &ReplayGainSettings::default(), 48_000);
    lane.last = true;
    let (out, _) = play_out(&mut lane);
    assert_eq!(out.len() / 2, 48_000);
    // Frame k of the output is the tone at time k / 48000: the resampler's
    // delay is gone and nothing drifts. Edges are left out.
    let mut worst = 0f32;
    for k in 2_000..46_000 {
        let t = k as f64 / 48_000.0;
        let ideal = ((2.0 * std::f64::consts::PI * 1_000.0 * t).sin() * 0.5) as f32;
        worst = worst.max((out[k * 2] - ideal).abs());
    }
    assert!(worst < 2e-3, "largest error {worst}");
    assert!((frequency(&out, 48_000) - 1_000.0).abs() < 0.5);
}

#[test]
fn replaygain_applies_per_song() {
    let dir = temp_dir();
    let rate = 48_000;
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&a, rate, 2, &vec![16_384; 4_000]);
    write_wav(&b, rate, 2, &vec![16_384; 4_000]);
    // Song A comes with a stored gain of -6.02 dB (half), song B with
    // +6.02 dB (double), as a server keeps them.
    let db = 20.0 * 2f32.log10();
    let stored = |gain| Loudness {
        stored: Some(ReplayGainInfo { track_gain: Some(gain), ..Default::default() }),
        follows_same_album: false,
    };
    let settings =
        ReplayGainSettings { mode: ReplayGainMode::Track, prevent_clipping: false, ..Default::default() };
    let mut lane = Lane::new(deck(1, &a), stored(-db), &settings, rate);
    lane.set_next(Some(deck(2, &b)), stored(db));
    lane.last = true;
    let (out, _) = play_out(&mut lane);
    assert!((out[10] - 0.25).abs() < 1e-6, "{}", out[10]);
    // The level changes exactly at the join, with no glide.
    assert!((out[1_999 * 2] - 0.25).abs() < 1e-6);
    assert!((out[2_000 * 2] - 1.0).abs() < 1e-6);
}

#[test]
fn seek_restarts_the_lane_at_the_new_place() {
    let dir = temp_dir();
    let path = dir.join("ramp.wav");
    let samples: Vec<i16> = (0..96_000).flat_map(|n| [(n / 4) as i16, 0]).collect();
    write_wav(&path, 48_000, 2, &samples);
    let mut lane = Lane::new(deck(1, &path), Loudness::default(), &ReplayGainSettings::default(), 48_000);
    lane.last = true;
    let until = Instant::now() + Duration::from_secs(5);
    while lane.fill(4_800) != LaneState::Ready {
        assert!(Instant::now() < until);
        std::thread::sleep(Duration::from_millis(1));
    }
    lane.seek(1.5);
    let mut spans = Vec::new();
    let mut block = vec![0.0; 200];
    while lane.fill(100) != LaneState::Ready {
        assert!(Instant::now() < until);
        std::thread::sleep(Duration::from_millis(1));
    }
    lane.pull(&mut block, &mut spans);
    assert_eq!(block[0], (72_000 / 4) as f32 / 32768.0);
    assert!((spans[0].start_secs - 1.5).abs() < 1e-9);
    assert!(!spans[0].joined);
}
