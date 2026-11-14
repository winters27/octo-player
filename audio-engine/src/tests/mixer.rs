//! Crossfades, markers and the whole mixing chain.

use std::path::Path;
use std::time::{Duration, Instant};

use crate::crossfade::{fade_in_volume, fade_out_volume};
use crate::deck::Deck;
use crate::lane::Lane;
use crate::mixer::{FadeShape, LowPassSteps, Marker, MixState, Mixer, Transition};
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
fn a_render_says_the_mix_has_ended_only_once_the_limiters_tail_is_out() {
    // 4,950 frames of song and the limiter's 72 held back, rendered 1,000
    // at a time: the fifth render ends the song with 50 of the tail, so 22
    // are still to come and it must not say the mix has ended.
    let dir = temp_dir();
    let a = dir.join("a.wav");
    dc_file(&a, 4_950, 8_192, 8_192);
    let song = deck(1, &a);
    let until = Instant::now() + Duration::from_secs(5);
    while !song.is_filled() {
        assert!(Instant::now() < until, "the song never decoded");
        std::thread::sleep(Duration::from_millis(1));
    }
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(song, Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let mut block = vec![0.0; 2_000];
    let mut markers = Vec::new();
    let mut renders = Vec::new();
    loop {
        let (n, state) = mixer.render(&mut block, &mut markers);
        renders.push(n);
        if state == MixState::Ended {
            break;
        }
        assert!(renders.len() < 10, "{renders:?}");
    }
    assert_eq!(renders.iter().sum::<usize>(), 4_950 + 72, "{renders:?}");
    assert_eq!(mixer.render(&mut block, &mut markers), (0, MixState::Ended), "{renders:?}");
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
    mixer.plan_fade(1, 1.5, fade_frames, next, Loudness::default(), FadeShape::default());
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

// Song A on the left at a steady level; song B on the right as a ramp that
// tells each frame's place in the song (frame n holds n % 15_000).
fn place_file(path: &Path, frames: usize) {
    let samples: Vec<i16> = (0..frames).flat_map(|n| [0, (n % 15_000) as i16]).collect();
    write_wav(path, RATE, 2, &samples);
}

fn place_of(sample: f32) -> usize {
    (sample * 32768.0).round() as usize
}

// Renders A (2 s, left) into B (right) with a blend at 1 s lasting
// `frames`, shaped by `shape`, with B starting at `entry` seconds.
fn blend(shape: FadeShape, frames: u64, entry: f64, b_frames: usize) -> (Vec<f32>, Vec<Marker>) {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    dc_file(&a, 96_000, 8_192, 0);
    place_file(&b, b_frames);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = Deck::open(2, b.to_string_lossy().into(), entry, HttpOptions::default());
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, frames, next, Loudness::default(), shape);
    render_all(&mut mixer)
}

const LATENCY: usize = 72;

#[test]
fn the_incoming_song_starts_from_its_entry_point() {
    let fade = 12_000u64;
    let (out, markers) = blend(FadeShape::default(), fade, 0.5, 96_000);
    let fade_start = 48_000 + LATENCY;
    // Past the blend B plays alone, from 0.5 s plus the time since the blend began.
    let after = fade_start + fade as usize + 100;
    let expected = (24_000 + fade as usize + 100) % 15_000;
    assert_eq!(place_of(out[after * 2 + 1]), expected);
    let b = markers.iter().find(|m| m.key == 2).expect("marker for B");
    assert_eq!(b.frame, fade_start as u64);
    assert!((b.secs - 0.5).abs() < 1e-9, "{}", b.secs);
    // B plays from 0.5 s to its end after A's first second.
    assert_eq!(out.len() / 2, 48_000 + 96_000 - 24_000 + LATENCY);
}

#[test]
fn the_outgoing_song_stops_when_the_blend_ends() {
    let fade = 12_000u64;
    let (out, _) = blend(FadeShape { k: 0.4, ..Default::default() }, fade, 0.0, 96_000);
    let fade_start = 48_000 + LATENCY;
    let end = fade_start + fade as usize;
    // A still had 0.75 s to go, but nothing of it is left after the blend.
    assert!(out[(end - 200) * 2] > 0.0);
    for i in end..end + 48_000 {
        assert_eq!(out[i * 2], 0.0, "A still heard at {i}");
    }
    assert_eq!(out.len() / 2, 48_000 + 96_000 + LATENCY);
}

#[test]
fn the_curve_follows_k() {
    let fade = 24_000u64;
    let k = 0.55;
    let (out, _) = blend(FadeShape { k, ..Default::default() }, fade, 0.0, 96_000);
    let fade_start = 48_000 + LATENCY;
    for i in (0..fade as usize).step_by(1_999) {
        let (gout, _) = crate::automix::gains(i as f64 / fade as f64, k);
        assert!((out[(fade_start + i) * 2] as f64 - 0.25 * gout).abs() < 1e-6, "at {i}");
    }
    // Halfway the outgoing song is at cos(pi/4) * (1 - k) + k / 2.
    let half = out[(fade_start + fade as usize / 2) * 2] as f64 / 0.25;
    assert!((half - (std::f64::consts::FRAC_1_SQRT_2 * (1.0 - k) + k / 2.0)).abs() < 1e-4, "{half}");
}

#[test]
fn a_fixed_crossfade_follows_its_curve() {
    use crate::automix::{FadeCurve, fade_out_gain};
    let fade = 24_000u64;
    let fade_start = 48_000 + LATENCY;
    for curve in [FadeCurve::Linear, FadeCurve::SCurve, FadeCurve::EvenLoudness, FadeCurve::MixOnly] {
        let (out, _) = blend(FadeShape { curve, ..Default::default() }, fade, 0.0, 96_000);
        for i in (0..fade as usize).step_by(997) {
            let want = 0.25 * fade_out_gain(curve, i as f64 / fade as f64, 500.0);
            assert!((out[(fade_start + i) * 2] as f64 - want).abs() < 1e-6, "{curve:?} at {i}");
        }
    }
}

#[test]
fn filter_sweeps_touch_only_the_blend() {
    let fade = 24_000u64;
    let plain = FadeShape { k: 0.4, ..Default::default() };
    let steps = LowPassSteps { beats: vec![0.25, 0.5, 0.75], glide: 0.125 };
    let swept = FadeShape { k: 0.4, filter_strength: 0.7, steps: Some(steps), ..Default::default() };
    let (a, _) = blend(plain, fade, 0.0, 96_000);
    let (b, _) = blend(swept, fade, 0.0, 96_000);
    assert_eq!(a.len(), b.len());
    let fade_start = 48_000 + LATENCY;
    let fade_end = fade_start + fade as usize;
    // Before the blend, and once B's high-pass has handed over to its dry
    // sound after it: the very same samples.
    let released = fade_end + RATE as usize / 100;
    assert_eq!(a[..fade_start * 2], b[..fade_start * 2]);
    let first = (released * 2..a.len()).find(|&i| a[i] != b[i]);
    assert_eq!(first, None, "{:?}", first.map(|i| (i, a[i], b[i])));
    // The hand-over is smooth: B's ramp, which the high-pass had taken
    // down to nothing, comes back without a step.
    let step =
        (fade_end - 10..released + 10).map(|i| (b[i * 2 + 1] - b[(i - 1) * 2 + 1]).abs()).fold(0.0, f32::max);
    assert!(step < 0.01, "a step of {step} where the filter came off");
    // Inside it the filters are at work: the steady A loses its level to
    // the high-pass, and B's ramp is changed by its own high-pass.
    let late = fade_start + fade as usize * 3 / 4;
    assert!(b[late * 2].abs() < a[late * 2].abs() * 0.5, "{} vs {}", b[late * 2], a[late * 2]);
    let early = fade_start + 2_000;
    assert!((a[early * 2 + 1] - b[early * 2 + 1]).abs() > 1e-3);
}

#[test]
fn a_swept_blend_starts_without_a_click() {
    // The outgoing song is a steady level: any step where the filters come
    // in is the filters' own.
    let fade = 24_000u64;
    let swept = FadeShape { k: 0.4, filter_strength: 0.7, ..Default::default() };
    let (out, _) = blend(swept, fade, 0.0, 96_000);
    let fade_start = 48_000 + LATENCY;
    let step = (fade_start - 10..fade_start + 2_000)
        .map(|i| (out[i * 2] - out[(i - 1) * 2]).abs())
        .fold(0.0, f32::max);
    assert!(step < 0.002, "a step of {step} where the filters came in");
    // A little later the sound is the filtered one.
    let (plain, _) = blend(FadeShape { k: 0.4, ..Default::default() }, fade, 0.0, 96_000);
    let later = fade_start + 4_000;
    assert!((out[later * 2] - plain[later * 2]).abs() > 1e-3);
}

#[test]
fn the_limiter_catches_a_blend_that_would_clip() {
    // Two loud songs, each passing through untouched on its own: their sum
    // in the middle of an equal-power blend would pass full scale.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    dc_file(&a, 96_000, 31_130, 31_130);
    dc_file(&b, 96_000, 31_130, 31_130);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, 24_000, next, Loudness::default(), FadeShape::default());
    let (out, _) = render_all(&mut mixer);
    let ceiling = 10f32.powf(crate::sound::limiter::LIMITER_CEILING_DB / 20.0);
    let fade_start = 48_000 + LATENCY;
    // Before the blend the song is left exactly as it is.
    assert!((out[(fade_start - 100) * 2] - 0.95).abs() < 1e-4, "{}", out[(fade_start - 100) * 2]);
    let loudest = out[fade_start * 2..(fade_start + 24_000) * 2].iter().fold(0.0f32, |m, s| m.max(s.abs()));
    assert!(loudest <= ceiling + 1e-4, "{loudest}");
    // Equal power would have reached 0.95 * 1.41 at its middle.
    let middle = out[(fade_start + 12_000) * 2];
    assert!(middle > 0.8 && middle <= ceiling + 1e-4, "{middle}");
}

// Full-scale white noise on both sides, the same for the same seed.
fn noise(frames: usize, seed: u64) -> Vec<i16> {
    let mut state = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
    (0..frames * 2)
        .map(|_| {
            state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            ((state >> 33) as i64 % 65_535 - 32_767) as i16
        })
        .collect()
}

fn loudest(out: &[f32], from: usize, to: usize) -> f32 {
    out[from * 2..to * 2].iter().fold(0.0f32, |m, s| m.max(s.abs()))
}

// Song A (`a_frames` long) blending at 1 s into B (2 s) over `fade` frames
// with `shape`, under `settings`.
fn blend_of(a: &[i16], b: &[i16], fade: u64, shape: FadeShape, settings: &SoundSettings) -> Vec<f32> {
    let dir = temp_dir();
    let (pa, pb) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&pa, RATE, 2, a);
    write_wav(&pb, RATE, 2, b);
    let mut mixer = Mixer::new(RATE, 0, settings, Pace::default());
    let mut lane = Lane::new(deck(1, &pa), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &pb);
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, fade, next, Loudness::default(), shape);
    render_all(&mut mixer).0
}

fn limiter_off() -> SoundSettings {
    let mut settings = SoundSettings::default();
    settings.dsp.limiter = false;
    settings
}

#[test]
fn every_blend_is_limited_whatever_the_limiter_setting() {
    let ceiling = 10f32.powf(crate::sound::limiter::LIMITER_CEILING_DB / 20.0);
    let shape = FadeShape { k: 0.4, ..Default::default() };
    let out = blend_of(&noise(96_000, 1), &noise(96_000, 2), 24_000, shape, &limiter_off());
    let fade_start = 48_000 + LATENCY;
    // A alone, with its blend lined up but not yet begun, is left as it is.
    assert!(loudest(&out, LATENCY, fade_start - 100) > 0.99);
    // Both songs together never pass the ceiling.
    let through = loudest(&out, fade_start, fade_start + 24_000);
    assert!(through <= ceiling + 1e-4, "{through}");
    // B alone afterwards follows the setting again.
    assert!(loudest(&out, fade_start + 24_000 + 2_000, out.len() / 2) > 0.99);
}

// A tone at a quarter of the sample rate whose samples fall halfway between
// its peaks, so the sound between samples is 3 dB over them.
fn quarter_rate(frames: usize, amplitude: f64) -> Vec<i16> {
    (0..frames)
        .flat_map(|n| {
            let wave = (std::f64::consts::FRAC_PI_2 * n as f64 + std::f64::consts::FRAC_PI_4).sin();
            [(wave * amplitude * 32_767.0).round() as i16; 2]
        })
        .collect()
}

#[test]
fn a_blend_whose_sum_peaks_between_samples_stays_under_the_ceiling() {
    // Each song alone peaks at -2.5 dBTP. In the middle of an equal-power
    // blend their sum's samples stay at -2.5 dBFS while the sound between
    // them reaches +0.5 dBFS; with the Limiter off the blend is still held.
    let song = quarter_rate(96_000, 0.75);
    let out = blend_of(&song, &song, 24_000, FadeShape::default(), &limiter_off());
    let fade_start = 48_000 + LATENCY;
    let db = |level: f64| 20.0 * level.log10();
    let middle = db(crate::testing::fixtures::true_peak(&out, fade_start + 9_000, fade_start + 15_000));
    let ceiling = crate::sound::limiter::LIMITER_CEILING_DB as f64;
    assert!(middle <= ceiling + 0.02, "true peak {middle} dBTP in the middle of the blend");
    // The outgoing song alone before its blend is left as it is.
    let before = db(crate::testing::fixtures::true_peak(&out, fade_start - 6_000, fade_start - 100));
    assert!((before - db(0.75)).abs() < 0.01, "{before} dBTP before the blend");
}

#[test]
fn an_outgoing_song_that_ends_inside_its_blend_lets_the_next_one_up_gently() {
    let fade = 48_000u64;
    let shape = FadeShape { k: 0.4, headroom_db: 1.0, ..Default::default() };
    // A ends 0.3 of the way through the blend.
    let a_frames = 48_000 + 14_400;
    let a: Vec<i16> = (0..a_frames).flat_map(|_| [8_192, 0]).collect();
    let b: Vec<i16> = (0..96_000).flat_map(|_| [0, 8_192]).collect();
    let out = blend_of(&a, &b, fade, shape.clone(), &SoundSettings::default());
    let fade_start = 48_000 + LATENCY;
    let gone = (fade_start..out.len() / 2).find(|&i| out[i * 2] == 0.0).expect("A ended");
    assert!(gone.abs_diff(fade_start + 14_400) <= 256, "A ended at {gone}");
    // B was at its in-gain there, well under its own level, and climbs from
    // it to its own level over 250 ms in even steps.
    let from = out[(gone - 1) * 2 + 1];
    assert!(from < 0.15, "{from}");
    let climb = 0.25 - from;
    let lift = RATE as usize / 4;
    let step = (gone - 10..gone + lift + 512)
        .map(|i| (out[i * 2 + 1] - out[(i - 1) * 2 + 1]).abs())
        .fold(0.0f32, f32::max);
    assert!(step < climb / lift as f32 * 2.0, "a step of {step} on a climb of {climb}");
    let halfway = out[(gone + lift / 2 + 256) * 2 + 1];
    assert!(halfway > from + climb * 0.3 && halfway < from + climb * 0.8, "{halfway}");
    assert!((out[(gone + lift + 512) * 2 + 1] - 0.25).abs() < 1e-6);

    // With full-scale noise and the limiter off, the climb is limited too,
    // and B alone after it follows the setting.
    let ceiling = 10f32.powf(crate::sound::limiter::LIMITER_CEILING_DB / 20.0);
    let a = noise(a_frames, 3);
    let out = blend_of(&a, &noise(96_000, 4), fade, shape, &limiter_off());
    // The climb lasts at least 250 ms from A's end.
    let climbing = fade_start + 14_400 + lift;
    let through = loudest(&out, fade_start, climbing);
    assert!(through <= ceiling + 1e-4, "{through}");
    assert!(loudest(&out, climbing + 24_000, out.len() / 2) > 0.99);
}

#[test]
fn an_outgoing_song_that_runs_out_before_its_blend_is_followed_without_one() {
    // A's sound ends at 0.8 s, before the blend planned at 1 s: B follows it
    // at once at its own level, a gapless join, never a near-silent start.
    let dir = temp_dir();
    let (pa, pb) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&pa, RATE, 2, &(0..38_400).flat_map(|_| [8_192, 0]).collect::<Vec<i16>>());
    write_wav(&pb, RATE, 2, &(0..96_000).flat_map(|_| [0, 8_192]).collect::<Vec<i16>>());
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let lane = Lane::new(deck(1, &pa), Loudness::default(), &ReplayGainSettings::default(), RATE);
    mixer.start(lane, Transition::Start);
    let next = deck(2, &pb);
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, 48_000, next, Loudness::default(), FadeShape { k: 0.4, ..Default::default() });
    let (mut out, mut markers, mut block) = (Vec::new(), Vec::new(), vec![0.0; 1024]);
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        let (n, state) = mixer.render(&mut block, &mut markers);
        out.extend_from_slice(&block[..n * 2]);
        assert!(!mixer.is_fading(), "no blend");
        // Once B plays, nothing follows it.
        if let Some(lane) = mixer.lane_mut()
            && lane.current().key() == 2
        {
            lane.last = true;
        }
        if state == MixState::Ended && n == 0 {
            break;
        }
        if n == 0 {
            assert!(Instant::now() < until, "mixer stalled");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    let joined = 38_400 + LATENCY;
    let join = markers.iter().find(|m| m.key == 2).expect("marker for B");
    assert_eq!((join.frame, join.transition), (joined as u64, Some(Transition::Gapless)));
    assert_eq!(out[(joined - 1) * 2], 0.25, "A to its last frame");
    for i in joined..joined + 96_000 {
        assert_eq!((out[i * 2], out[i * 2 + 1]), (0.0, 0.25), "B alone at its own level at {i}");
    }
    assert_eq!(out.len() / 2, joined + 96_000);
}

// Song A blending at 1 s into B over `fade` frames with `shape`, under
// `settings`, at `speed`: the sound and where the blend begins in it.
fn paced_blend_of(
    a: &[i16],
    b: &[i16],
    fade: u64,
    shape: FadeShape,
    settings: &SoundSettings,
    speed: f32,
) -> (Vec<f32>, usize) {
    let dir = temp_dir();
    let (pa, pb) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&pa, RATE, 2, a);
    write_wav(&pb, RATE, 2, b);
    let mut mixer = Mixer::new(RATE, 0, settings, Pace { speed, pitch: 1.0 });
    let mut lane = Lane::new(deck(1, &pa), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &pb);
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, fade, next, Loudness::default(), shape);
    let (out, markers) = render_all(&mut mixer);
    let begins =
        markers.iter().find(|m| matches!(m.transition, Some(Transition::Crossfade { .. }))).expect("a blend");
    (out, begins.frame as usize)
}

// A on the left: hot (0.99) alone up to its blend at frame `start`, quiet
// (0.2) in it, and ending 0.3 of the way through, so B climbs over 250 ms
// after. B on the right: quiet through the blend and the climb, then hot.
// Mixed with the Limiter off at `pace`.
fn hot_either_side_of_a_lifted_blend(pace: Pace, start: usize) -> Vec<f32> {
    const HOT: i16 = 32_440;
    const QUIET: i16 = 6_554;
    let a: Vec<i16> = (0..start + 14_400).flat_map(|n| [if n < start { HOT } else { QUIET }, 0]).collect();
    let b: Vec<i16> = (0..96_000).flat_map(|n| [0, if n < 26_400 { QUIET } else { HOT }]).collect();
    let dir = temp_dir();
    let (pa, pb) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&pa, RATE, 2, &a);
    write_wav(&pb, RATE, 2, &b);
    let mut mixer = Mixer::new(RATE, 0, &limiter_off(), pace);
    let mut lane = Lane::new(deck(1, &pa), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &pb);
    wait_ready(&next);
    let shape = FadeShape { k: 0.4, ..Default::default() };
    mixer.plan_fade(1, start as f64 / RATE as f64, 48_000, next, Loudness::default(), shape);
    render_all(&mut mixer).0
}

#[test]
fn a_song_alone_either_side_of_a_blend_is_never_limited() {
    let hot = 32_440.0 / 32_768.0;
    // At normal speed A comes out as it went in up to the blend's first
    // frame, and B from the frame its climb ends on.
    let out = hot_either_side_of_a_lifted_blend(Pace::default(), 48_000);
    for i in LATENCY + 4_800..LATENCY + 48_000 {
        assert_eq!(out[i * 2], hot, "A at {i}");
    }
    let climbed = LATENCY + 48_000 + 14_400 + 12_000;
    for i in climbed..climbed + 4_800 {
        assert_eq!(out[i * 2 + 1], hot, "B at {i}");
    }
    // At 1.25 times speed a block of the speed stage can fall across either
    // edge (from 48,250 the blend's first frame comes 200 frames into a
    // block), and the frames on the outside are still at the song's own
    // level. The pitch moves with the speed, a plain rate change, so the
    // songs' edges stay sharp.
    let mut held = Vec::new();
    for start in [48_000, 48_250] {
        let out = hot_either_side_of_a_lifted_blend(Pace { speed: 1.25, pitch: 1.25 }, start);
        let frames = out.len() / 2;
        let quiet = (4_800..frames).find(|&i| out[i * 2].abs() < 0.5).expect("A quiet in the blend");
        let hot_again = (quiet..frames).find(|&i| out[i * 2 + 1] > 0.5).expect("B hot after its climb");
        if let Some(i) = (quiet - 1_500..quiet - 80).find(|&i| out[i * 2] <= 0.95) {
            held.push(format!("from {start}: A {} before the blend at {}", quiet - i, out[i * 2]));
        }
        if let Some(i) = (hot_again + 80..hot_again + 1_500).find(|&i| out[i * 2 + 1] <= 0.95) {
            held.push(format!("from {start}: B {} after its climb at {}", i - hot_again, out[i * 2 + 1]));
        }
    }
    assert!(held.is_empty(), "{held:?}");
}

#[test]
fn a_hot_song_is_turned_down_only_over_the_look_ahead_before_its_blend() {
    // A at 0.99 alone and on into its blend at 1 s, with the Limiter off:
    // the blend's first frames need the level down, and it comes down along
    // the limiter's line over the 72 frames before them, no earlier, never
    // stepping, to the ceiling at the blend.
    let ceiling = 10f32.powf(crate::sound::limiter::LIMITER_CEILING_DB / 20.0);
    let hot = 32_440.0 / 32_768.0;
    let a: Vec<i16> = (0..96_000).flat_map(|_| [32_440, 0]).collect();
    let b: Vec<i16> = (0..96_000).flat_map(|_| [0, 6_554]).collect();
    let out = blend_of(&a, &b, 24_000, FadeShape { k: 0.4, ..Default::default() }, &limiter_off());
    let fade_start = 48_000 + LATENCY;
    for i in LATENCY + 4_800..fade_start - LATENCY {
        assert_eq!(out[i * 2], hot, "A at {i}, {} before the blend", fade_start - i);
    }
    // The steepest line is the true peak's, over the look-ahead less the
    // 31 frames its reading looks back.
    let line = (1.0 - ceiling / hot) / (LATENCY - 31) as f32;
    for i in fade_start - LATENCY..fade_start {
        let (was, now) = (out[(i - 1) * 2] / hot, out[i * 2] / hot);
        assert!(now <= was && was - now <= line + 1e-6, "A at {i}: {was} to {now}, line {line}");
    }
    assert!(out[(fade_start - 1) * 2] < hot * 0.95, "down by the blend");
    assert!(out[fade_start * 2] <= ceiling + 1e-6, "{}", out[fade_start * 2]);
}

#[test]
fn a_blend_is_limited_on_every_block_at_speeds_below_one() {
    let ceiling = 10f32.powf(crate::sound::limiter::LIMITER_CEILING_DB / 20.0);
    let shape = FadeShape { k: 0.4, ..Default::default() };
    for speed in [0.5f32, 0.75] {
        let (a, b) = (noise(96_000, 5), noise(96_000, 6));
        let (out, begins) = paced_blend_of(&a, &b, 24_000, shape.clone(), &limiter_off(), speed);
        // A alone before its blend is left as it is.
        assert!(loudest(&out, LATENCY, begins - 2_000) > 0.99, "speed {speed}");
        // Every frame of the blend, half a second of the songs heard over
        // a second or more, stays under the ceiling.
        let heard = (24_000.0 / speed as f64) as usize;
        let through = loudest(&out, begins, begins + heard - 512);
        assert!(through <= ceiling + 1e-4, "speed {speed}: {through}");
    }
}

// A (6 s) blending at 1 s into B (6 s) over `fade` frames with `shape`,
// under `settings`; at each of `speeds`' output frames the speed is set the
// way the player sets it. The sound, and where each setting put the cut,
// in the blend's frames.
fn blend_with_speeds(
    a: &[i16],
    b: &[i16],
    fade: u64,
    shape: FadeShape,
    settings: &SoundSettings,
    speeds: &[(usize, f64)],
) -> (Vec<f32>, Vec<Option<u64>>) {
    let dir = temp_dir();
    let (pa, pb) = (dir.join("a.wav"), dir.join("b.wav"));
    write_wav(&pa, RATE, 2, a);
    write_wav(&pb, RATE, 2, b);
    let mut mixer = Mixer::new(RATE, 0, settings, Pace::default());
    let mut lane = Lane::new(deck(1, &pa), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &pb);
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, fade, next, Loudness::default(), shape);
    let mut out = Vec::new();
    let mut markers = Vec::new();
    let mut block = vec![0.0; 256];
    let mut cuts = Vec::new();
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        let (n, state) = mixer.render(&mut block, &mut markers);
        out.extend_from_slice(&block[..n * 2]);
        if mixer.is_fading()
            && let Some(lane) = mixer.lane_mut()
        {
            lane.last = true;
        }
        if let Some(&(at, speed)) = speeds.get(cuts.len())
            && out.len() / 2 >= at
        {
            cuts.push(mixer.follow_speed(speed));
        }
        if state == MixState::Ended && n == 0 {
            assert_eq!(cuts.len(), speeds.len(), "every speed was set");
            return (out, cuts);
        }
        if n == 0 {
            assert!(Instant::now() < until, "mixer stalled");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}

fn steady_left() -> Vec<i16> {
    (0..RATE as usize * 6).flat_map(|_| [8_192, 0]).collect()
}

fn steady_right() -> Vec<i16> {
    (0..RATE as usize * 6).flat_map(|_| [0, 8_192]).collect()
}

// The output frame holding the blend's frame `at` (it begins at 1 s).
fn blend_frame(at: u64) -> usize {
    48_000 + LATENCY + at as usize
}

#[test]
fn a_lowered_speed_cuts_an_intro_only_once_its_hold_is_over() {
    // A 4 s intro: A at its own level for 2 s; both heard together from
    // 1.9 s to 3.5 s, the beats parting by 1% from the start, 35 ms by the
    // end of that. At half speed 20 ms is the limit, reached 2 s in: the
    // cut waits for it, A held at full through the hold.
    let intro = FadeShape {
        kind: crate::automix::TransitionKind::Intro,
        hold_share: 0.5,
        rise_share: 0.1,
        both_heard_ms: (1_900.0, 3_500.0),
        lock_ms: 0.0,
        drift_gap: 0.01,
        ..Default::default()
    };
    let slowed = [(blend_frame(24_000), 0.5)];
    let (out, cuts) = blend_with_speeds(
        &steady_left(),
        &steady_right(),
        192_000,
        intro,
        &SoundSettings::default(),
        &slowed,
    );
    assert_eq!(cuts, vec![Some(96_000)]);
    assert_eq!(out[blend_frame(95_000) * 2], 0.25, "A held at its own level");
    // Then A goes over 20 ms, while B comes up to its own level.
    assert_eq!(out[blend_frame(96_000 + 960 + 256) * 2], 0.0);
    assert!((out[blend_frame(96_000 + 960 + 12_000 + 512) * 2 + 1] - 0.25).abs() < 1e-6);
}

#[test]
fn a_lowered_speed_never_cuts_inside_an_intros_hold() {
    // A 4 s intro: A at its own level for 3 s; both heard together from
    // 0.5 s to 3.75 s, the beats meeting at 3.5 s and parting by 1%. At half
    // speed 20 ms is the limit, 2 s either side of the lock: slowed 0.5 s in,
    // the beats are past it at once, but A keeps its hold, and from the
    // hold's end they stay within it to the stretch's end, so no cut comes.
    let intro = FadeShape {
        kind: crate::automix::TransitionKind::Intro,
        hold_share: 0.75,
        rise_share: 0.1,
        both_heard_ms: (500.0, 3_750.0),
        lock_ms: 3_500.0,
        drift_gap: 0.01,
        ..Default::default()
    };
    let slowed = [(blend_frame(24_000), 0.5)];
    let (out, cuts) = blend_with_speeds(
        &steady_left(),
        &steady_right(),
        192_000,
        intro,
        &SoundSettings::default(),
        &slowed,
    );
    assert_eq!(cuts, vec![None]);
    assert_eq!(out[blend_frame(143_000) * 2], 0.25, "A held at its own level");
}

// A 2 s blend whose two songs are heard together for its first 1.5 s, the
// beats parting by 2% from the start: 30 ms by then, 60 ms heard at half
// speed, the limit of 20 ms there reached 1 s in.
fn drifting() -> FadeShape {
    FadeShape { k: 0.4, both_heard_ms: (0.0, 1_500.0), lock_ms: 0.0, drift_gap: 0.02, ..Default::default() }
}

#[test]
fn a_blend_cut_by_a_lowered_speed_lets_the_next_song_up_gently_and_limited() {
    let slowed = [(blend_frame(14_400), 0.5)];
    let (out, cuts) = blend_with_speeds(
        &steady_left(),
        &steady_right(),
        96_000,
        drifting(),
        &SoundSettings::default(),
        &slowed,
    );
    assert_eq!(cuts, vec![Some(48_000)]);
    // A goes over 20 ms from 1 s in; B, held there, then climbs to its own
    // level over 250 ms in even steps, never stepping.
    let cut = blend_frame(48_000);
    assert!(out[(cut - 10) * 2] > 0.05);
    assert_eq!(out[(cut + 960 + 256) * 2], 0.0);
    let from = out[cut * 2 + 1];
    let climb = 0.25 - from;
    assert!(climb > 0.02, "{from}");
    let lift = RATE as usize / 4;
    let step = (cut - 10..cut + 960 + lift + 512)
        .map(|i| (out[i * 2 + 1] - out[(i - 1) * 2 + 1]).abs())
        .fold(0.0f32, f32::max);
    assert!(step < climb / lift as f32 * 2.0, "a step of {step} on a climb of {climb}");
    assert!((out[(cut + 960 + lift + 512) * 2 + 1] - 0.25).abs() < 1e-6);
    // With full-scale noise and the Limiter off, nothing passes the ceiling
    // through the blend, the cut and the climb.
    let ceiling = 10f32.powf(crate::sound::limiter::LIMITER_CEILING_DB / 20.0);
    let (out, _) = blend_with_speeds(
        &noise(288_000, 7),
        &noise(288_000, 8),
        96_000,
        drifting(),
        &limiter_off(),
        &slowed,
    );
    let through = loudest(&out, blend_frame(0), cut + 960 + lift);
    assert!(through <= ceiling + 1e-4, "{through}");
}

#[test]
fn a_speed_that_keeps_the_beats_close_enough_leaves_the_blend_alone() {
    let early = blend_frame(14_400);
    let cases = [
        ("within the limit", FadeShape { drift_gap: 0.005, ..drifting() }, vec![(early, 0.5)]),
        ("past the stretch", drifting(), vec![(blend_frame(76_800), 0.5)]),
        ("matched", FadeShape { drift_gap: 0.0, rate: Some(1.02), ..drifting() }, vec![(early, 0.5)]),
        ("planned speed", drifting(), vec![(early, 1.0)]),
        // Lowered, then back before the cut: cleared.
        ("back again", drifting(), vec![(early, 0.5), (blend_frame(28_800), 1.0)]),
    ];
    for (what, shape, speeds) in cases {
        let (out, cuts) = blend_with_speeds(
            &steady_left(),
            &steady_right(),
            96_000,
            shape,
            &SoundSettings::default(),
            &speeds,
        );
        assert_eq!(*cuts.last().unwrap(), None, "{what}");
        // A plays its whole blend.
        assert!(out[blend_frame(90_000) * 2] > 0.0, "{what}");
    }
}

#[test]
fn a_song_with_no_blend_plays_exactly_as_before() {
    // A blend later in a song changes nothing before it.
    let dir = temp_dir();
    let a = dir.join("a.wav");
    write_wav(&a, RATE, 2, &sine(300.0, RATE, 2, 0, 96_000, 0.4));
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let (alone, _) = render_all(&mut mixer);
    let b = dir.join("b.wav");
    dc_file(&b, 48_000, 0, 1_000);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    let shape = FadeShape { k: 0.75, filter_strength: 0.7, ..Default::default() };
    mixer.plan_fade(1, 1.5, 12_000, next, Loudness::default(), shape);
    let (blended, _) = render_all(&mut mixer);
    let start = 72_000 + LATENCY;
    assert_eq!(alone[..start * 2], blended[..start * 2]);
}

#[test]
fn a_matched_tempo_eases_back_to_normal() {
    let fade = 48_000u64;
    let b_frames = RATE as usize * 9;
    let shape = FadeShape { k: 0.4, rate: Some(1.05), ..Default::default() };
    let (out, markers) = blend(shape, fade, 0.0, b_frames);
    let fade_start = 48_000 + LATENCY;
    // Through the blend B moves at 1.05, then eases back over 5 s on a
    // half cosine (1.025 on average), then plays on at its own rate.
    let b_out = 1.0 + 5.0 + (9.0 - 1.05 - 5.125);
    let expected = fade_start as f64 + b_out * RATE as f64;
    assert!((out.len() as f64 / 2.0 - expected).abs() < 1_500.0, "{} vs {expected}", out.len() / 2);
    // The clock follows B's own time throughout.
    for m in markers.iter().filter(|m| m.key == 2) {
        let t = (m.frame as f64 - fade_start as f64) / RATE as f64;
        let song = if t <= 1.0 {
            t * 1.05
        } else if t <= 6.0 {
            let e = t - 1.0;
            let pi = std::f64::consts::PI;
            1.05 + 1.025 * e + 0.125 / pi * (pi * e / 5.0).sin()
        } else {
            6.175 + (t - 6.0)
        };
        assert!((m.secs - song).abs() < 0.05, "{m:?}: want {song}");
    }
    // The ease lands softly, so the clock's last new marker comes at its
    // very end, already at B's own rate.
    let last = markers.iter().rfind(|m| m.key == 2).unwrap();
    let t = (last.frame as f64 - fade_start as f64) / RATE as f64;
    assert!(t > 5.9, "no marker near the end of the ease: {markers:?}");
    assert!((last.secs_per_frame - 1.0 / RATE as f64).abs() < 1e-11, "{last:?}");
}

#[test]
fn a_matched_rate_never_steps_the_sound_from_its_start_to_its_drop() {
    // From a silent song into a steady tone at a matched rate, held through
    // the 1 s blend, eased back to normal over 5 s, then dropped: at a rate
    // that repeats periods and at one that skips them, the tone never moves
    // further between two frames than it does on its own.
    let slope = 0.25 * 2.0 * std::f32::consts::PI * 220.0 / RATE as f32;
    let silent = vec![0i16; 96_000 * 2];
    let tone = sine(220.0, RATE, 2, 0, RATE as usize * 8, 0.25);
    for rate in [0.947, 1.036] {
        let shape = FadeShape { rate: Some(rate), ..Default::default() };
        let out = blend_of(&silent, &tone, 48_000, shape, &SoundSettings::default());
        let fade_start = 48_000 + LATENCY;
        // The tone plays on well past the ease, where the rate stage is gone;
        // its own end is left out.
        let until = out.len() / 2 - RATE as usize / 2;
        assert!(until > fade_start + RATE as usize * 7, "rate {rate}: {} frames", out.len() / 2);
        let step = (fade_start..until).map(|i| (out[i * 2] - out[(i - 1) * 2]).abs()).fold(0.0, f32::max);
        assert!(step < slope * 1.5, "rate {rate}: a step of {step} (the tone moves {slope} a frame)");
    }
}

// A with sound for its first `sound` frames and silence after, into B as
// a steady level on the right, blending from 1 s for 1 s.
fn blend_into_silence(shape: FadeShape, sound: usize) -> Vec<f32> {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    let samples: Vec<i16> = (0..96_000).flat_map(|n| [if n < sound { 8_192 } else { 0 }, 0]).collect();
    write_wav(&a, RATE, 2, &samples);
    dc_file(&b, 96_000, 0, 8_192);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, 48_000, next, Loudness::default(), shape);
    render_all(&mut mixer).0
}

#[test]
fn an_intro_holds_the_outgoing_song_and_brings_the_next_one_up() {
    use crate::automix::{TransitionKind, intro_in_gain, intro_out_gain};
    let fade = 48_000u64;
    let shape =
        FadeShape { kind: TransitionKind::Intro, hold_share: 0.6, rise_share: 0.25, ..Default::default() };
    let out = blend_into_silence(shape, 96_000);
    let fade_start = 48_000 + LATENCY;
    for i in (0..fade as usize).step_by(499) {
        let t = i as f64 / fade as f64;
        let (a, b) = (out[(fade_start + i) * 2] as f64, out[(fade_start + i) * 2 + 1] as f64);
        assert!((a - 0.25 * intro_out_gain(t, 0.6)).abs() < 1e-6, "A {a} at {i}");
        assert!((b - 0.25 * intro_in_gain(t, 0.25, 0.6, 1.0)).abs() < 1e-6, "B {b} at {i}");
    }
    // A at its own level through the hold, B at its own from the rise on.
    let held = fade_start + fade as usize / 2;
    assert_eq!((out[held * 2], out[held * 2 + 1]), (0.25, 0.25));
}

#[test]
fn a_lowered_intro_plays_the_next_song_at_its_level_through_the_hold() {
    use crate::automix::{TransitionKind, intro_in_gain, intro_out_gain};
    let fade = 48_000u64;
    let shape = FadeShape {
        kind: TransitionKind::Intro,
        hold_share: 0.6,
        rise_share: 0.25,
        intro_level: 0.5,
        ..Default::default()
    };
    let out = blend_into_silence(shape, 96_000);
    let fade_start = 48_000 + LATENCY;
    for i in (0..fade as usize).step_by(499) {
        let t = i as f64 / fade as f64;
        let (a, b) = (out[(fade_start + i) * 2] as f64, out[(fade_start + i) * 2 + 1] as f64);
        assert!((a - 0.25 * intro_out_gain(t, 0.6)).abs() < 1e-6, "A {a} at {i}");
        assert!((b - 0.25 * intro_in_gain(t, 0.25, 0.6, 0.5)).abs() < 1e-6, "B {b} at {i}");
    }
    // A at its own level through the hold, B at half its own; B back at its
    // own by the end.
    let held = fade_start + fade as usize / 2;
    assert_eq!((out[held * 2], out[held * 2 + 1]), (0.25, 0.125));
    let end = fade_start + fade as usize - 1;
    assert!((out[end * 2 + 1] as f64 - 0.25).abs() < 1e-3, "B {} at the end", out[end * 2 + 1]);
}

#[test]
fn a_blend_finishes_early_once_the_outgoing_song_falls_silent() {
    let k = 0.4;
    let gated = FadeShape { k, silence_gate_db: Some(-60.0), ..Default::default() };
    let plain = FadeShape { k, ..Default::default() };
    // A falls silent 0.2 s into the blend.
    let early = blend_into_silence(gated.clone(), 57_600);
    let late = blend_into_silence(plain, 57_600);
    // 0.3 s of quiet (to within a block), then 0.25 s to finish: by 0.8 s
    // into the blend B is at full level, where it would still be rising.
    let at = 48_000 + LATENCY + 38_400;
    assert!((early[at * 2 + 1] - 0.25).abs() < 1e-6, "{}", early[at * 2 + 1]);
    let (_, gin) = crate::automix::gains(0.8, k);
    let gin = gin as f32;
    assert!((late[at * 2 + 1] - 0.25 * gin).abs() < 1e-6);
    assert!(late[at * 2 + 1] < 0.249);
    // Before the quiet has lasted 0.3 s, both are the same.
    let before = 48_000 + LATENCY + 9_600 + 12_000;
    assert_eq!(early[before * 2 + 1], late[before * 2 + 1]);
    // A song that keeps sounding is blended all the way.
    let full = blend_into_silence(gated, 96_000);
    assert!((full[at * 2 + 1] - 0.25 * gin).abs() < 1e-6);
}

// A 14 s blend from A's first second whose outgoing song holds its level
// for the first 10 s.
const INTRO_FRAMES: u64 = RATE as u64 * 14;
const INTRO_HOLD: f64 = 10.0 / 14.0;

// An intro over that: A holds, B rises over 2 s.
fn intro_shape() -> FadeShape {
    FadeShape {
        kind: crate::automix::TransitionKind::Intro,
        hold_share: INTRO_HOLD,
        rise_share: 2.0 / 14.0,
        ..Default::default()
    }
}

// A blend over that, held while the next song's lead-in is silent (A at
// its own level, B at 0), then its curve over the rest.
fn lead_in_blend_shape() -> FadeShape {
    FadeShape {
        kind: crate::automix::TransitionKind::Blend,
        k: crate::automix::BLEND_CURVE,
        hold_share: INTRO_HOLD,
        ..Default::default()
    }
}

// A on the left at a steady level, silent over each of `breaks` (seconds
// into the blend), into B on the right through a blend of `shape`, with the
// silence gate on. Returns the sound.
fn held_with_breaks(shape: FadeShape, breaks: &[(f64, f64)]) -> Vec<f32> {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    let quiet = |n: usize| {
        let s = n as f64 / RATE as f64 - 1.0;
        breaks.iter().any(|&(from, to)| s >= from && s < to)
    };
    let samples: Vec<i16> =
        (0..RATE as usize * 16).flat_map(|n| [if quiet(n) { 0 } else { 8_192 }, 0]).collect();
    write_wav(&a, RATE, 2, &samples);
    dc_file(&b, RATE as usize * 16, 0, 8_192);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    let shape = FadeShape { silence_gate_db: Some(-60.0), ..shape };
    mixer.plan_fade(1, 1.0, INTRO_FRAMES, next, Loudness::default(), shape);
    render_all(&mut mixer).0
}

// The sample of A (left) or B (right) `secs` into the blend, and A's
// planned gain there through a blend of `shape`.
fn held_at(out: &[f32], secs: f64, side: usize) -> f32 {
    out[(48_000 + LATENCY + (secs * RATE as f64) as usize) * 2 + side]
}

fn held_out_at(shape: &FadeShape, secs: f64) -> f32 {
    let p = (secs * RATE as f64) as usize as f64 / INTRO_FRAMES as f64;
    let overlap_ms = INTRO_FRAMES as f64 * 1_000.0 / RATE as f64;
    let (s, hold, rise, level) = (shape, shape.hold_share, shape.rise_share, shape.intro_level);
    let (out, _) = crate::automix::transition_gains(s.kind, s.curve, s.k, hold, rise, level, p, overlap_ms);
    0.25 * out as f32
}

#[test]
fn a_break_in_the_outgoing_song_during_an_intros_hold_is_heard_as_the_song() {
    let intro = intro_shape();
    // A stop of half a second 40% into the hold: A holds its level on both
    // sides of it and fades as planned, never rushed to its end.
    let out = held_with_breaks(intro.clone(), &[(4.0, 4.5)]);
    for secs in [0.5, 3.9, 4.6, 6.0, 9.9] {
        assert_eq!(held_at(&out, secs, 0), 0.25, "A at {secs} s");
    }
    for secs in [11.0, 12.0, 13.0] {
        assert!((held_at(&out, secs, 0) - held_out_at(&intro, secs)).abs() < 1e-6, "A at {secs} s");
    }
    // A break that starts in the hold is counted only from the hold's end,
    // where too little of it is left to finish the blend.
    let out = held_with_breaks(intro.clone(), &[(9.8, 10.2)]);
    for secs in [10.3, 12.0] {
        assert!((held_at(&out, secs, 0) - held_out_at(&intro, secs)).abs() < 1e-6, "A at {secs} s");
    }
    // Once A fades, the gate acts as in any blend: the rest of the blend
    // runs over 0.25 s, and B plays on alone.
    let out = held_with_breaks(intro.clone(), &[(11.0, 11.5)]);
    assert!((held_at(&out, 10.9, 0) - held_out_at(&intro, 10.9)).abs() < 1e-6);
    assert_eq!((held_at(&out, 11.7, 0), held_at(&out, 11.7, 1)), (0.0, 0.25));
}

#[test]
fn a_break_in_the_outgoing_song_while_a_blend_holds_through_a_silent_lead_in_is_heard_as_the_song() {
    // A blend, not an intro, held for 10 s while the next song's lead-in is
    // silent: a stop of half a second 40% into the hold leaves A at its own
    // level on both sides of it, B silent, and A on its curve after the
    // hold, never rushed to its end.
    let blend = lead_in_blend_shape();
    let out = held_with_breaks(blend.clone(), &[(4.0, 4.5)]);
    for secs in [0.5, 3.9, 4.6, 6.0, 9.9] {
        assert_eq!((held_at(&out, secs, 0), held_at(&out, secs, 1)), (0.25, 0.0), "at {secs} s");
    }
    for secs in [10.5, 11.5, 12.5, 13.5] {
        assert!((held_at(&out, secs, 0) - held_out_at(&blend, secs)).abs() < 1e-6, "A at {secs} s");
    }
    // A break that starts in the hold is counted only from the hold's end.
    let out = held_with_breaks(blend.clone(), &[(9.8, 10.2)]);
    for secs in [10.3, 12.0] {
        assert!((held_at(&out, secs, 0) - held_out_at(&blend, secs)).abs() < 1e-6, "A at {secs} s");
    }
}

#[test]
fn the_shape_of_a_plan_gates_every_curve_but_plain_equal_power() {
    use crate::automix::{TransitionKind, TransitionPlan};
    let fixed = TransitionPlan::fixed_crossfade(200_000, 8_000, "test");
    let intro =
        TransitionPlan { kind: TransitionKind::Intro, hold_share: 0.7, rise_share: 0.1, ..fixed.clone() };
    let blend = TransitionPlan { kind: TransitionKind::Blend, k: 0.2, ..fixed.clone() };
    let shape = |plan: &TransitionPlan| FadeShape::for_plan(plan, Some(-14.0));
    assert_eq!(shape(&fixed).silence_gate_db, None);
    assert_eq!(shape(&blend).silence_gate_db, Some(-59.0));
    let intro = shape(&intro);
    assert_eq!(intro.silence_gate_db, Some(-59.0));
    assert_eq!((intro.kind, intro.hold_share, intro.rise_share), (TransitionKind::Intro, 0.7, 0.1));
    // Without a known level the gate sits at the floor.
    assert_eq!(FadeShape::for_plan(&blend, None).silence_gate_db, Some(-60.0));
}

#[test]
fn headroom_lowers_the_middle_of_a_blend() {
    let fade = 24_000u64;
    let k = 0.4;
    let (out, _) = blend(FadeShape { k, headroom_db: 1.0, ..Default::default() }, fade, 0.0, 96_000);
    let fade_start = 48_000 + LATENCY;
    let mid = fade_start + fade as usize / 2;
    let (gout, _) = crate::automix::gains(0.5, k);
    let down = 10f64.powf(-1.0 / 20.0);
    assert!((out[mid * 2] as f64 - 0.25 * gout * down).abs() < 1e-6, "{}", out[mid * 2]);
    // Coming in gradually: barely lowered at the very start.
    let (g0, _) = crate::automix::gains(0.01, k);
    let start = fade_start + fade as usize / 100;
    assert!(out[start * 2] as f64 > 0.25 * g0 * down);
    // Gone once the blend is over: B at its own level.
    let after = fade_start + fade as usize + 10;
    assert_eq!(place_of(out[after * 2 + 1]), (fade as usize + 10) % 15_000);
}

#[test]
fn the_live_tap_hears_the_playing_song_before_shaping() {
    let dir = temp_dir();
    let a = dir.join("a.wav");
    dc_file(&a, 48_000, 8_192, 0);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    render_all(&mut mixer);
    let live = mixer.live_analysis(1, None).expect("tapped");
    assert_eq!(live.heard_ms, 1_000);
    // Mono of 0.25 on one side is 0.125.
    let body = live.body_level_db.unwrap();
    assert!((body - 20.0 * 0.125f64.log10()).abs() < 1e-3, "{body}");
    assert!(mixer.live_analysis(2, None).is_none());
}

#[test]
fn the_blend_is_over_on_its_last_frame() {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    dc_file(&a, 96_000, 8_192, 0);
    dc_file(&b, 96_000, 0, 8_192);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, 12_000, next, Loudness::default(), FadeShape { k: 0.4, ..Default::default() });
    // Up to the blend's last frame, then one more.
    let mut markers = Vec::new();
    let mut made = 0;
    let until = Instant::now() + Duration::from_secs(5);
    for target in [48_000 + 12_000 - 1, 48_000 + 12_000] {
        while made < target {
            let mut block = vec![0.0; (target - made).min(512) * 2];
            let (n, _) = mixer.render(&mut block, &mut markers);
            made += n;
            assert!(Instant::now() < until);
        }
        // A is let go at the end of the blend although it had 0.75 s left.
        assert_eq!(mixer.is_fading(), target < 60_000, "at {made}");
    }
}

// Like `blend`, but the blend is ended at once (as a change of the
// crossfade setting does) once `cut_at` frames are out. Returns the sound
// and the frame count at the cut.
fn blend_cut_short(shape: FadeShape, frames: u64, cut_at: usize) -> (Vec<f32>, usize) {
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    dc_file(&a, 96_000, 8_192, 0);
    place_file(&b, 96_000);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = Deck::open(2, b.to_string_lossy().into(), 0.0, HttpOptions::default());
    wait_ready(&next);
    mixer.plan_fade(1, 1.0, frames, next, Loudness::default(), shape);
    let mut out = Vec::new();
    let mut markers = Vec::new();
    let mut block = vec![0.0; 256];
    let mut cut = None;
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        let (n, state) = mixer.render(&mut block, &mut markers);
        out.extend_from_slice(&block[..n * 2]);
        if mixer.is_fading()
            && let Some(lane) = mixer.lane_mut()
        {
            lane.last = true;
        }
        if cut.is_none() && out.len() / 2 >= cut_at && mixer.is_fading() {
            mixer.finish_fade();
            cut = Some(out.len() / 2);
        }
        if state == MixState::Ended && n == 0 {
            return (out, cut.expect("the blend was cut"));
        }
        if n == 0 {
            assert!(Instant::now() < until, "mixer stalled");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}

#[test]
fn a_blend_cut_short_still_hands_the_filter_over_gently() {
    let fade = 24_000u64;
    let cut_at = 48_000 + LATENCY + 12_000;
    let plain = FadeShape { k: 0.4, ..Default::default() };
    let swept = FadeShape { k: 0.4, filter_strength: 0.7, ..Default::default() };
    let (a, cut) = blend_cut_short(plain, fade, cut_at);
    let (b, cut_b) = blend_cut_short(swept, fade, cut_at);
    assert_eq!(cut, cut_b);
    // Right after the cut B's high-pass is still fading out...
    let differs = (cut + LATENCY + 8..cut + LATENCY + 200).any(|i| a[i * 2 + 1] != b[i * 2 + 1]);
    assert!(differs, "the filter came off at once");
    // ...and some 20 ms on (the blend's own last 10 ms, then the filter's)
    // the sound is B's own.
    let released = cut + LATENCY + RATE as usize / 50 + 256;
    let first = (released * 2..a.len().min(b.len())).find(|&i| a[i] != b[i]);
    assert_eq!(first, None, "{:?}", first.map(|i| (i, a[i], b[i])));
}

#[test]
fn a_song_still_easing_its_rate_blends_on_without_a_jump() {
    // B comes in at a matched rate, and its own blend into C starts while
    // its rate is still easing back to normal.
    let dir = temp_dir();
    let (a, b, c) = (dir.join("a.wav"), dir.join("b.wav"), dir.join("c.wav"));
    dc_file(&a, 96_000, 8_192, 8_192);
    write_wav(&b, RATE, 2, &sine(173.0, RATE, 2, 0, RATE as usize * 6, 0.25));
    dc_file(&c, 96_000, 0, 0);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    let matched = FadeShape { k: 0.4, rate: Some(1.05), ..Default::default() };
    mixer.plan_fade(1, 1.0, 12_000, next, Loudness::default(), matched);
    let mut out = Vec::new();
    let mut markers = Vec::new();
    let mut block = vec![0.0; 512];
    let mut planned = false;
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        let (n, state) = mixer.render(&mut block, &mut markers);
        out.extend_from_slice(&block[..n * 2]);
        if mixer.is_fading()
            && let Some(lane) = mixer.lane_mut()
        {
            lane.last = true;
        }
        // Once A is gone, B's blend into C goes in at 1.5 s of B.
        if !planned && out.len() / 2 > 48_000 + 12_000 + 1_000 {
            let next = deck(3, &c);
            wait_ready(&next);
            mixer.plan_fade(
                2,
                1.5,
                24_000,
                next,
                Loudness::default(),
                FadeShape { k: 0.4, ..Default::default() },
            );
            planned = true;
        }
        if state == MixState::Ended && n == 0 {
            break;
        }
        if n == 0 {
            assert!(Instant::now() < until, "mixer stalled");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    let b_to_c = markers.iter().find(|m| m.key == 3).expect("C came in").frame as usize;
    // B fades out smoothly: no step bigger than its own sine's.
    let slope = 0.25 * 2.0 * std::f32::consts::PI * 173.0 / RATE as f32;
    let step =
        (b_to_c - 2_000..b_to_c + 4_000).map(|i| (out[i * 2] - out[(i - 1) * 2]).abs()).fold(0.0, f32::max);
    assert!(step < slope * 2.0, "a step of {step} (the sine moves {slope} a frame)");
}

#[test]
fn a_song_that_ends_before_its_blend_still_hands_over() {
    // The blend was lined up at 5 s of a song whose sound stops at 1 s.
    let dir = temp_dir();
    let (a, b) = (dir.join("a.wav"), dir.join("b.wav"));
    dc_file(&a, 48_000, 8_192, 0);
    dc_file(&b, 48_000, 0, 8_192);
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let lane = Lane::new(deck(1, &a), Loudness::default(), &ReplayGainSettings::default(), RATE);
    mixer.start(lane, Transition::Start);
    let next = deck(2, &b);
    wait_ready(&next);
    mixer.plan_fade(1, 5.0, 12_000, next, Loudness::default(), FadeShape { k: 0.4, ..Default::default() });
    let mut out = Vec::new();
    let mut markers = Vec::new();
    let mut block = vec![0.0; 1024];
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        let (n, state) = mixer.render(&mut block, &mut markers);
        out.extend_from_slice(&block[..n * 2]);
        // Nothing follows B.
        if let Some(lane) = mixer.lane_mut()
            && lane.current().key() == 2
        {
            lane.last = true;
        }
        if state == MixState::Ended && n == 0 {
            break;
        }
        if n == 0 {
            assert!(Instant::now() < until, "mixer stalled");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    let b_from = markers.iter().find(|m| m.key == 2).expect("B came in").frame as usize;
    // As A runs out of sound (its last few milliseconds were already read).
    assert!((47_000..=48_000 + LATENCY).contains(&b_from), "{b_from}");
    // B plays to its end, at its own level once A is gone.
    assert_eq!(out.len() / 2, b_from + 48_000);
    assert!((out[(b_from + 1_000) * 2 + 1] - 0.25).abs() < 1e-6);
}

#[test]
fn a_blend_cut_short_glides_both_songs_to_their_ends() {
    let fade = 24_000u64;
    let cut_at = 48_000 + LATENCY + 12_000;
    let (out, cut) = blend_cut_short(FadeShape { k: 0.4, ..Default::default() }, fade, cut_at);
    // A (left) was at about 0.15 when the blend was cut: it glides out over
    // 10 ms instead of stepping to nothing.
    let at_cut = out[(cut + LATENCY) * 2];
    assert!(at_cut > 0.1, "{at_cut}");
    let step = (cut + LATENCY - 10..cut + LATENCY + 1_000)
        .map(|i| (out[i * 2] - out[(i - 1) * 2]).abs())
        .fold(0.0, f32::max);
    assert!(step < at_cut / 100.0, "a step of {step}");
    let gone = cut + LATENCY + RATE as usize / 100 + 256;
    assert_eq!(out[gone * 2], 0.0);
    // B (right, a ramp of its place) is at its own level by then.
    let place = place_of(out[gone * 2 + 1]);
    assert!(place > 0 && place < 15_000, "{place}");
    let b_now = out[gone * 2 + 1];
    let b_next = out[(gone + 1) * 2 + 1];
    assert!((b_next - b_now - 1.0 / 32_768.0).abs() < 1e-6, "{b_now} {b_next}");
}
