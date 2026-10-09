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
    // ...and some 10 ms on the sound is B's own.
    let released = cut + LATENCY + RATE as usize / 100 + 256;
    let first = (released * 2..a.len().min(b.len())).find(|&i| a[i] != b[i]);
    assert_eq!(first, None, "{:?}", first.map(|i| (i, a[i], b[i])));
}
