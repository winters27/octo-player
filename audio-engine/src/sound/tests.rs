use super::biquad::FilterBank;
use super::limiter::{LIMITER_CEILING_DB, Limiter};
use super::model::*;
use super::replaygain::*;
use super::shaper::SoundShaper;
use std::f64::consts::PI;

// The response of one biquad at a frequency, straight from its transfer
// function H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2).
fn textbook_db(k: &Coefficients, frequency: f64, rate: f64) -> f64 {
    let w = 2.0 * PI * frequency / rate;
    let (c1, s1, c2, s2) = (w.cos(), w.sin(), (2.0 * w).cos(), (2.0 * w).sin());
    let num_re = k.b0 + k.b1 * c1 + k.b2 * c2;
    let num_im = -(k.b1 * s1 + k.b2 * s2);
    let den_re = 1.0 + k.a1 * c1 + k.a2 * c2;
    let den_im = -(k.a1 * s1 + k.a2 * s2);
    10.0 * ((num_re * num_re + num_im * num_im) / (den_re * den_re + den_im * den_im)).log10()
}

// Plays a sine through a bank and measures the gain once it has settled.
fn measured_db(bank: &mut FilterBank, frequency: f64, rate: u32) -> f64 {
    let frames = rate as usize; // one second
    let mut samples: Vec<f32> =
        (0..frames).map(|n| (2.0 * PI * frequency * n as f64 / rate as f64).sin() as f32 * 0.25).collect();
    bank.process(&mut samples, frames);
    let tail = &samples[frames / 2..];
    let rms = (tail.iter().map(|s| (*s as f64).powi(2)).sum::<f64>() / tail.len() as f64).sqrt();
    let input_rms = 0.25 / 2f64.sqrt();
    20.0 * (rms / input_rms).log10()
}

#[test]
fn cookbook_coefficients_match_the_transfer_function() {
    let rate = 48_000;
    let filters = [
        EqFilter { filter_type: FilterType::Peak, frequency: 1_000.0, gain_db: 6.0, q: 1.41, enabled: true },
        EqFilter { filter_type: FilterType::Peak, frequency: 100.0, gain_db: -9.0, q: 0.7, enabled: true },
        EqFilter { filter_type: FilterType::LowShelf, frequency: 105.0, gain_db: 6.4, q: 0.7, enabled: true },
        EqFilter {
            filter_type: FilterType::HighShelf,
            frequency: 8_000.0,
            gain_db: -4.0,
            q: 0.7,
            enabled: true,
        },
    ];
    for f in &filters {
        let k = coefficients(f, rate);
        for probe in [30.0, 100.0, 440.0, 1_000.0, 5_000.0, 12_000.0] {
            let fast = magnitude_db(&k, probe as f32, rate);
            let exact = textbook_db(&k, probe, rate as f64);
            assert!((fast - exact).abs() < 1e-6, "{f:?} at {probe}: {fast} vs {exact}");
        }
    }
    // A peak filter reaches exactly its gain at its own frequency.
    let k = coefficients(&filters[0], rate);
    assert!((textbook_db(&k, 1_000.0, rate as f64) - 6.0).abs() < 1e-9);
    // A shelf reaches its gain far from its corner and half of it at the corner.
    let k = coefficients(&filters[2], rate);
    assert!((textbook_db(&k, 10.0, rate as f64) - 6.4).abs() < 0.05);
    assert!((textbook_db(&k, 105.0, rate as f64) - 3.2).abs() < 0.05);
}

#[test]
fn filter_bank_output_matches_the_formula() {
    let rate = 44_100;
    let filters = vec![
        EqFilter::peak(1_000.0, 6.0, 1.41),
        EqFilter::peak(4_000.0, -3.0, 2.0),
        EqFilter::peak(62.0, 4.0, 1.41),
    ];
    for probe in [62.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 10_000.0] {
        let mut bank = FilterBank::new(1, band_coefficients(&filters, rate));
        let measured = measured_db(&mut bank, probe, rate);
        let expected = response_db(&filters, probe as f32, rate) as f64;
        assert!((measured - expected).abs() < 0.05, "{probe} Hz: measured {measured}, expected {expected}");
    }
}

#[test]
fn filters_near_the_top_of_the_range_are_left_out() {
    let filters = vec![EqFilter::peak(16_000.0, 6.0, 1.41), EqFilter::peak(1_000.0, 3.0, 1.41)];
    assert_eq!(band_coefficients(&filters, 32_000).len(), 1);
    assert_eq!(band_coefficients(&filters, 48_000).len(), 2);
}

#[test]
fn auto_preamp_takes_back_the_highest_boost() {
    let mut settings = SoundSettings::default();
    settings.eq.enabled = true;
    settings.eq.graphic_gains = eq_presets().into_iter().find(|p| p.name == "Bass Boost").unwrap().gains;
    let preamp = settings.effective_preamp_db(48_000);
    let peak = peak_db(&settings.active_filters(), 48_000);
    assert!(peak > 6.0, "stacked bass bands boost more than one band: {peak}");
    assert!((preamp + peak).abs() < 1e-6);
    settings.eq.auto_preamp = false;
    settings.eq.preamp_db = -2.0;
    assert_eq!(settings.effective_preamp_db(48_000), -2.0);
    settings.eq.enabled = false;
    assert_eq!(settings.effective_preamp_db(48_000), 0.0);
}

#[test]
fn every_preset_has_ten_bands() {
    let presets = eq_presets();
    assert_eq!(presets.len(), 19);
    assert!(presets.iter().all(|p| p.gains.len() == GRAPHIC_BANDS.len()));
}

#[test]
fn limiter_holds_peaks_at_the_ceiling() {
    let rate = 48_000;
    let mut limiter = Limiter::new(2, rate);
    let ceiling = 10f32.powf(LIMITER_CEILING_DB / 20.0);
    // A loud sine that runs over full scale, then a steady level with one
    // sudden spike.
    let frames = rate as usize;
    // Late enough for the level to come back after the loud part.
    let spike = frames - 2_000;
    let mut input = Vec::with_capacity(frames * 2);
    for n in 0..frames {
        let mut s = if n < frames / 2 {
            (2.0 * std::f32::consts::PI * 220.0 * n as f32 / rate as f32).sin() * 1.6
        } else {
            0.5
        };
        if n == spike {
            s = 4.0;
        }
        input.push(s);
        input.push(-s);
    }
    let mut output = vec![0.0; frames * 2];
    let written = limiter.process(&input, frames, &mut output);
    assert_eq!(written, frames - limiter.latency());
    let peak = output[..written * 2].iter().fold(0f32, |p, s| p.max(s.abs()));
    assert!(peak <= ceiling + 1e-6, "peak {peak} over ceiling {ceiling}");
    // The look-ahead turns the level down before the spike instead of
    // clipping it: the frame just before it is already down, while the
    // frame just outside the look-ahead window is not.
    let before = output[(spike - 1) * 2].abs();
    assert!((before - 0.5 * ceiling / 4.0).abs() < 1e-6, "turned down ahead of the spike: {before}");
    let outside = output[(spike - limiter.latency() - 1) * 2].abs();
    assert!((outside - 0.5).abs() < 0.01, "untouched before the window: {outside}");
    // Delay only: drain hands back the held frames.
    let mut tail = vec![0.0; limiter.latency() * 2];
    assert_eq!(limiter.drain(&mut tail), limiter.latency());
}

#[test]
fn limiter_leaves_quiet_sound_alone() {
    let mut limiter = Limiter::new(1, 44_100);
    let input: Vec<f32> = (0..2_000).map(|n| (n as f32 * 0.01).sin() * 0.5).collect();
    let mut output = vec![0.0; 2_000];
    let written = limiter.process(&input, 2_000, &mut output);
    let lat = limiter.latency();
    for i in 0..written {
        assert_eq!(output[i], input[i], "frame {i} unchanged, only delayed by {lat}");
    }
}

#[test]
fn limiter_timing_matches_android() {
    let limiter = Limiter::new(2, 48_000);
    assert_eq!(limiter.latency(), 72); // 1.5 ms
    let limiter = Limiter::new(2, 44_100);
    assert_eq!(limiter.latency(), 66);
}

// Two songs at 12 kHz, a quarter of the sample rate, whose samples fall
// halfway between the waveform's peaks: every sample of their sum stays at
// -2 dBFS, a dB under the ceiling, while the sound between samples reaches
// +1 dBFS. The two channels are opposite.
fn quarter_rate_sum(frames: usize) -> Vec<f32> {
    (0..frames)
        .flat_map(|n| {
            let wave = (PI / 2.0 * n as f64 + PI / 4.0).sin();
            let sum = (0.6 * wave + 0.52 * wave) as f32;
            [sum, -sum]
        })
        .collect()
}

// Every frame through `limiter`, the delay drained at the end, so output
// frame `i` is input frame `i`.
fn limited(limiter: &mut Limiter, input: &[f32]) -> Vec<f32> {
    let frames = input.len() / 2;
    let mut out = vec![0.0; input.len()];
    let written = limiter.process(input, frames, &mut out);
    limiter.drain(&mut out[written * 2..]);
    out
}

fn db(level: f64) -> f64 {
    20.0 * level.log10()
}

#[test]
fn the_mix_limiter_holds_the_sound_between_samples_under_the_ceiling() {
    let ceiling = 10f32.powf(LIMITER_CEILING_DB / 20.0);
    let sum = quarter_rate_sum(9_600);
    assert!(sum.iter().all(|s| s.abs() < ceiling * 0.9), "every sample a dB under the ceiling");
    assert!(db(crate::testing::fixtures::true_peak(&sum, 0, 9_600)) > 0.9);
    for ramped in [false, true] {
        let mut limiter = Limiter::new(2, 48_000);
        limiter.true_peak = true;
        limiter.ramped_attack = ramped;
        let out = limited(&mut limiter, &sum);
        let peak = db(crate::testing::fixtures::true_peak(&out, 2_400, 7_200));
        assert!(peak <= LIMITER_CEILING_DB as f64 + 0.02, "ramped {ramped}: true peak {peak} dBTP");
        // Turned down by what the sound between samples needs, no further.
        assert!(peak > LIMITER_CEILING_DB as f64 - 0.05, "ramped {ramped}: true peak {peak} dBTP");
    }
}

// A limiter set the way the shaper sets it while two songs blend.
fn blend_limiter() -> Limiter {
    let mut limiter = Limiter::new(2, 48_000);
    limiter.true_peak = true;
    limiter.ramped_attack = true;
    limiter
}

// Every frame through `limiter` one at a time, keeping the level after
// each, then the delay drained, so output frame `i` is input frame `i`.
// From frame `off_at`, the limiter is off.
fn levels_of(limiter: &mut Limiter, input: &[f32], off_at: Option<usize>) -> (Vec<f32>, Vec<f32>) {
    let mut levels = Vec::with_capacity(input.len() / 2);
    let mut out = vec![0.0; input.len()];
    let mut written = 0;
    for (i, frame) in input.chunks_exact(2).enumerate() {
        if off_at == Some(i) {
            limiter.enabled = false;
        }
        written += limiter.process(frame, 1, &mut out[written * 2..written * 2 + 2]);
        levels.push(limiter.gain());
    }
    limiter.drain(&mut out[written * 2..]);
    (levels, out)
}

// The most the level falls from one frame to the next.
fn largest_drop(levels: &[f32]) -> f32 {
    levels.windows(2).fold(0f32, |d, w| d.max(w[0] - w[1]))
}

// `java.util.Random`, so a test reads the same noise as its Kotlin twin.
struct JavaRandom(u64);

impl JavaRandom {
    const MASK: u64 = (1 << 48) - 1;

    fn new(seed: u64) -> Self {
        Self((seed ^ 0x5_DEEC_E66D) & Self::MASK)
    }

    fn next_float(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(0x5_DEEC_E66D).wrapping_add(0xB) & Self::MASK;
        (self.0 >> (48 - 24)) as f32 / (1u32 << 24) as f32
    }
}

// `LimiterMixTest.loud`: a quiet tone, then stereo noise at twice full
// scale.
fn loud_mix() -> Vec<f32> {
    let mut noise = JavaRandom::new(7);
    (0..9_600 * 2)
        .map(|i| {
            let frame = i / 2;
            if frame < 4_800 {
                (0.3 * (2.0 * PI * 440.0 * frame as f64 / 48_000.0).sin()) as f32
            } else {
                2.0 * (noise.next_float() * 2.0 - 1.0)
            }
        })
        .collect()
}

// Kotlin twin: `LimiterMixTest.theRampedAttackNeverStepsTheLevelAndHoldsTheCeiling`.
#[test]
fn the_ramped_attack_never_steps_the_level_and_holds_the_ceiling() {
    let ceiling = 10f32.powf(LIMITER_CEILING_DB / 20.0);
    let loud = loud_mix();
    let mut ramped = Limiter::new(2, 48_000);
    ramped.ramped_attack = true;
    let (ramped_levels, ramped_out) = levels_of(&mut ramped, &loud, None);
    let (instant_levels, _) = levels_of(&mut Limiter::new(2, 48_000), &loud, None);
    // The deepest need is the ceiling over twice full scale; its straight
    // line down across the look-ahead falls by this much a frame at most.
    let slope = (1.0 - ceiling / 2.0) / ramped.latency() as f32;
    let drop = largest_drop(&ramped_levels);
    assert!(drop <= slope + 1e-6, "ramped drop {drop}, line {slope}");
    assert!(largest_drop(&instant_levels) > 0.3, "the instant attack does step");
    assert!(ramped_out.iter().all(|s| s.abs() <= ceiling));
    let loudest = ramped_out.iter().fold(0f32, |m, s| m.max(s.abs()));
    assert!(loudest > ceiling * 0.95, "held near the ceiling: {loudest}");
}

// Kotlin twin: `LimiterMixTest.turningOffMidReductionNeverStepsTheLevel`.
#[test]
fn turning_the_ramped_limiter_off_mid_reduction_never_steps_the_level() {
    let ceiling = 10f32.powf(LIMITER_CEILING_DB / 20.0);
    let mut limiter = Limiter::new(2, 48_000);
    limiter.ramped_attack = true;
    let (levels, _) = levels_of(&mut limiter, &loud_mix(), Some(7_000));
    assert!(levels[6_999] < 0.9, "{}", levels[6_999]);
    let rise = levels[7_000..].windows(2).fold(0f32, |r, w| r.max(w[1] - w[0]));
    assert!(rise < 1e-3, "rise {rise}");
    assert!(largest_drop(&levels) <= (1.0 - ceiling / 2.0) / limiter.latency() as f32 + 1e-6);
}

// Kotlin twin: `LimiterTruePeakTest.theRampedAttackStillNeverStepsTheLevel`.
#[test]
fn the_ramped_attack_still_never_steps_the_level_with_the_true_peak() {
    // Quiet, then the quarter-rate sum: only the sound between samples
    // needs limiting, and it is seen 31 frames late.
    let mut input = vec![0.0; 4_800 * 2];
    input.extend(quarter_rate_sum(4_800));
    let mut limiter = blend_limiter();
    let (levels, _) = levels_of(&mut limiter, &input, None);
    // Each need's line runs from unity across the look-ahead less the
    // reading's span, so none falls faster than the line to the deepest
    // need, which the level reaches (where the tone starts, the sound
    // between samples overshoots the steady sum a little).
    let deepest = levels.iter().fold(1f32, |m, &l| m.min(l));
    let slope = (1.0 - deepest) / (limiter.latency() - 31) as f32;
    let drop = largest_drop(&levels);
    assert!(drop <= slope + 1e-6, "largest drop {drop}, line {slope}");
    assert!(deepest < 0.8, "deepest {deepest}");
    assert!(levels[levels.len() - 1] < 0.81);
}

#[test]
fn a_blend_turns_a_full_scale_step_down_along_a_line_with_the_limiter_off() {
    let ceiling = 10f32.powf(LIMITER_CEILING_DB / 20.0);
    let mut shaper = SoundShaper::new(48_000, 2);
    let mut settings = SoundSettings::default();
    settings.dsp.limiter = false;
    shaper.apply(&settings, 1.0, true);
    assert!(!shaper.limiter_enabled());
    // A quarter of full scale alone, then two songs blending whose sum
    // steps to full scale.
    let level = |n: usize| if n < 9_600 { 0.25f32 } else { 1.0 };
    let input: Vec<f32> = (0..14_400).flat_map(|n| [level(n), -level(n)]).collect();
    let mut out = input.clone();
    shaper.process(&mut out[..4_800 * 2], 4_800);
    shaper.set_blending(true);
    shaper.process(&mut out[4_800 * 2..], 9_600);
    let lat = shaper.latency();
    // The level each frame came out at.
    let levels: Vec<f32> = (lat..14_400).map(|i| out[i * 2] / input[(i - lat) * 2]).collect();
    let deepest = levels.iter().fold(1f32, |m, &l| m.min(l));
    // The step's samples need the ceiling, and the sound between its
    // samples a little more: no line falls faster than the one to the
    // deepest level over the reading's shorter span.
    assert!(deepest < ceiling, "deepest {deepest}");
    let drop = largest_drop(&levels);
    let slope = (1.0 - deepest) / (lat - 31) as f32;
    assert!(drop <= slope + 1e-6, "largest drop {drop}, line {slope}");
    assert!(out.iter().all(|s| s.abs() <= ceiling), "held at the ceiling");
}

#[test]
fn a_frame_that_came_in_with_the_limiter_off_comes_out_as_it_went_in() {
    let ceiling = 10f32.powf(LIMITER_CEILING_DB / 20.0);
    // A hot song alone with the Limiter off, its samples and the sound
    // between them over the ceiling; then a blend whose sum needs nothing,
    // with the limiter switched on the way the shaper does for a blend.
    let blend_at = 4_800;
    let level = |n: usize| if n < blend_at { 0.99f32 } else { 0.2 };
    let input: Vec<f32> = (0..9_600).flat_map(|n| [level(n), -level(n)]).collect();
    let mut limiter = Limiter::new(2, 48_000);
    limiter.enabled = false;
    let mut out = vec![0.0; input.len()];
    let first = limiter.process(&input[..blend_at * 2], blend_at, &mut out);
    (limiter.enabled, limiter.true_peak, limiter.ramped_attack) = (true, true, true);
    let rest = limiter.process(&input[blend_at * 2..], 9_600 - blend_at, &mut out[first * 2..]);
    limiter.drain(&mut out[(first + rest) * 2..]);
    // Up to the frame just before the blend, the song alone comes out as it
    // went in: never held at the ceiling, and its sound between samples
    // never read.
    assert!(out[(blend_at - 1) * 2] > ceiling);
    assert_eq!(&out[..blend_at * 2], &input[..blend_at * 2]);
    assert_eq!(&out[blend_at * 2..], &input[blend_at * 2..]);
}

#[test]
fn after_a_peak_the_level_comes_back_to_exactly_unity() {
    // A burst at twice full scale, then a quiet tone for 1.9 s: once the
    // level is back, the tone passes bit for bit.
    let input: Vec<f32> = (0..96_000)
        .flat_map(|n| {
            let s = if n < 4_800 { 2.0 } else { 0.5 * (n as f64 * 0.05).sin() as f32 };
            [s, -s]
        })
        .collect();
    for ramped in [false, true] {
        let mut limiter = Limiter::new(2, 48_000);
        limiter.ramped_attack = ramped;
        let out = limited(&mut limiter, &input);
        assert_eq!(limiter.gain(), 1.0, "ramped {ramped}");
        assert_eq!(&out[72_000 * 2..], &input[72_000 * 2..], "ramped {ramped}");
    }
}

// The sample limiter written out plainly: each frame turned down as far as
// any frame from it to `latency` ahead needs, down at once, back up slowly
// and onto unity once the step is lost in rounding, then held at the
// ceiling.
fn sample_limited(input: &[f32], rate: u32) -> Vec<f32> {
    let latency = (rate as f32 * super::limiter::LIMITER_LOOKAHEAD_MS / 1_000.0).round() as usize;
    let ceiling = 10f32.powf(LIMITER_CEILING_DB / 20.0);
    let release = 1.0 - (-1.0 / (rate as f32 * super::limiter::LIMITER_RELEASE_MS / 1_000.0)).exp();
    let frames = input.len() / 2;
    let need: Vec<f32> = (0..frames)
        .map(|i| {
            let peak = 0f32.max(input[i * 2].abs()).max(input[i * 2 + 1].abs());
            if peak > ceiling { ceiling / peak } else { 1.0 }
        })
        .collect();
    let mut gain = 1.0f32;
    let mut out = Vec::with_capacity(input.len());
    for i in 0..frames {
        let target = need[i..(i + latency + 1).min(frames)].iter().fold(1.0f32, |m, &n| m.min(n));
        gain = if target < gain {
            target
        } else {
            let next = gain + (target - gain) * release;
            if next == gain { target } else { next }
        };
        for ch in 0..2 {
            out.push((input[i * 2 + ch] * gain).clamp(-ceiling, ceiling));
        }
    }
    out
}

#[test]
fn without_the_true_peak_the_limiter_holds_samples_as_it_always_has() {
    // A song alone: noise bursts far over full scale between quiet stretches,
    // then the quarter-rate sum, whose samples need nothing.
    let mut state = 12_345u64;
    let mut input: Vec<f32> = (0..24_000 * 2)
        .map(|i| {
            state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            let noise = ((state >> 40) as f32 / (1u64 << 24) as f32) * 2.0 - 1.0;
            if (i / 2 / 3_000) % 2 == 0 { noise * 2.5 } else { noise * 0.2 }
        })
        .collect();
    input.extend(quarter_rate_sum(9_600));
    for rate in [48_000, 44_100] {
        let out = limited(&mut Limiter::new(2, rate), &input);
        assert_eq!(out, sample_limited(&input, rate), "at {rate} Hz");
    }
    // Plain and ramped alike, its sound between samples passes over the
    // ceiling untouched.
    for ramped in [false, true] {
        let mut limiter = Limiter::new(2, 48_000);
        limiter.ramped_attack = ramped;
        let alone = limited(&mut limiter, &quarter_rate_sum(9_600));
        assert_eq!(alone, quarter_rate_sum(9_600), "ramped {ramped}");
    }
}

#[test]
fn the_shaper_reads_the_true_peak_only_while_two_songs_blend() {
    let mut shaper = SoundShaper::new(48_000, 2);
    // ReplayGain on the song playing, so the limiter runs on it alone too.
    shaper.apply(&SoundSettings::default(), 0.5, true);
    assert!(shaper.limiter_enabled());
    let lat = shaper.latency();
    let mut alone = quarter_rate_sum(4_800);
    shaper.process(&mut alone, 4_800);
    // A song alone keeps its sound between samples, as before.
    assert_eq!(&alone[lat * 2..], &quarter_rate_sum(4_800)[..(4_800 - lat) * 2]);
    shaper.set_blending(true);
    let mut blend = quarter_rate_sum(9_600);
    shaper.process(&mut blend, 9_600);
    let peak = db(crate::testing::fixtures::true_peak(&blend, 2_400, 7_200));
    assert!(peak <= LIMITER_CEILING_DB as f64 + 0.02, "true peak {peak} dBTP while blending");
}

#[test]
fn shaper_keeps_the_frame_count_and_delays_by_its_latency() {
    let mut shaper = SoundShaper::new(48_000, 2);
    let settings = SoundSettings::default();
    shaper.apply(&settings, 1.0, true);
    let mut block: Vec<f32> = (0..256).map(|n| n as f32 / 1000.0).collect();
    let original = block.clone();
    shaper.process(&mut block, 128);
    let lat = shaper.latency();
    assert!(block[..lat * 2].iter().all(|s| *s == 0.0));
    assert_eq!(&block[lat * 2..], &original[..(128 - lat) * 2]);
}

#[test]
fn balance_and_mono() {
    let mut shaper = SoundShaper::new(48_000, 2);
    let mut settings = SoundSettings::default();
    settings.dsp.limiter = false;
    settings.dsp.balance = 0.5;
    shaper.apply(&settings, 1.0, true);
    let mut block = vec![0.5f32; 400];
    shaper.process(&mut block, 200);
    let last = &block[398..];
    assert!((last[0] - 0.25).abs() < 1e-6 && (last[1] - 0.5).abs() < 1e-6, "{last:?}");

    let mut shaper = SoundShaper::new(48_000, 2);
    settings.dsp.balance = 0.0;
    settings.dsp.mono = true;
    shaper.apply(&settings, 1.0, true);
    let mut block: Vec<f32> = (0..200).flat_map(|_| [1.0f32, 0.0]).collect();
    shaper.process(&mut block, 200);
    let last = &block[398..];
    assert!((last[0] - 0.5).abs() < 1e-6 && (last[1] - 0.5).abs() < 1e-6, "{last:?}");
}

#[test]
fn replaygain_parsing() {
    assert_eq!(parse_gain("-6.54 dB"), Some(-6.54));
    assert_eq!(parse_gain("+2.1dB"), Some(2.1));
    assert_eq!(parse_gain("-6,54 dB"), Some(-6.54));
    assert_eq!(parse_gain("99 dB"), None);
    assert_eq!(parse_peak("0.988251"), Some(0.988251));
    assert_eq!(parse_peak("0"), None);
    assert_eq!(parse_r128("-2560"), Some(-5.0));
    let info = from_tags([
        ("replaygain_track_gain", "-7.00 dB"),
        ("R128_TRACK_GAIN", "256"),
        ("R128_ALBUM_GAIN", "512"),
        ("REPLAYGAIN_TRACK_PEAK", "0.9"),
    ])
    .unwrap();
    assert_eq!(info.track_gain, Some(-7.0));
    assert_eq!(info.album_gain, Some(7.0));
    assert_eq!(info.track_peak, Some(0.9));
    assert_eq!(from_tags([("TITLE", "x")]), None);
}

#[test]
fn replaygain_factor_rules() {
    let info = ReplayGainInfo {
        track_gain: Some(-6.0),
        track_peak: Some(0.5),
        album_gain: Some(-3.0),
        album_peak: None,
    };
    let song = SongLoudness { replay_gain: Some(info), follows_same_album: false };
    let mut settings = ReplayGainSettings { mode: ReplayGainMode::Off, ..Default::default() };
    assert_eq!(replay_gain_factor(&settings, Some(&song)), 1.0);

    settings.mode = ReplayGainMode::Track;
    let expected = 10f32.powf(-6.0 / 20.0);
    assert!((replay_gain_factor(&settings, Some(&song)) - expected).abs() < 1e-6);

    settings.mode = ReplayGainMode::Album;
    let expected = 10f32.powf(-3.0 / 20.0);
    assert!((replay_gain_factor(&settings, Some(&song)) - expected).abs() < 1e-6);

    // Smart takes the album gain only when the song follows its album.
    settings.mode = ReplayGainMode::Smart;
    assert!((replay_gain_factor(&settings, Some(&song)) - 10f32.powf(-6.0 / 20.0)).abs() < 1e-6);
    let following = SongLoudness { follows_same_album: true, ..song };
    assert!((replay_gain_factor(&settings, Some(&following)) - 10f32.powf(-3.0 / 20.0)).abs() < 1e-6);

    // Preamp adds; clipping prevention caps at the peak.
    settings.mode = ReplayGainMode::Track;
    settings.preamp_db = 12.0;
    let raised = 10f32.powf(6.0 / 20.0);
    assert!(raised < 2.0);
    assert!((replay_gain_factor(&settings, Some(&song)) - raised).abs() < 1e-5);
    settings.preamp_db = 20.0;
    assert_eq!(replay_gain_factor(&settings, Some(&song)), 2.0); // 1 / 0.5
    settings.prevent_clipping = false;
    assert!(replay_gain_factor(&settings, Some(&song)) > 2.0);

    // No tags: the fallback.
    settings.fallback_db = -6.0;
    let untagged = SongLoudness::default();
    assert!((replay_gain_factor(&settings, Some(&untagged)) - 10f32.powf(-6.0 / 20.0)).abs() < 1e-6);
}

#[test]
fn stored_loudness_fills_in_for_a_stream_without_tags() {
    let stored = stored_replay_gain(&ReplayGainInfo { track_gain: Some(-4.0), ..Default::default() });
    let peaks_only = Some(ReplayGainInfo { track_peak: Some(0.9), ..Default::default() });
    assert_eq!(choose_replay_gain(peaks_only, stored), stored);
    assert_eq!(choose_replay_gain(None, None), None);
    assert_eq!(stored_replay_gain(&ReplayGainInfo { track_gain: Some(100.0), ..Default::default() }), None);
}

// Runs `left` and `right` through a crossfeed that is fully on.
fn crossfed(
    c: crate::sound::crossfeed::CrossfeedCoefficients,
    left: &[f32],
    right: &[f32],
) -> (Vec<f32>, Vec<f32>) {
    let mut filter = crate::sound::crossfeed::CrossfeedFilter::new();
    filter.set(c, true, 0);
    let mut samples: Vec<f32> = left.iter().zip(right).flat_map(|(l, r)| [*l, *r]).collect();
    filter.process(&mut samples, left.len(), 2);
    (samples.iter().step_by(2).copied().collect(), samples.iter().skip(1).step_by(2).copied().collect())
}

#[test]
fn every_crossfeed_vector_matches() {
    use crate::sound::crossfeed::CrossfeedCoefficients;
    let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../shared/core/src/commonTest/resources/crossfeed-vectors.json");
    let vectors: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
    let tolerance = |name: &str| vectors["tolerances"][name].as_f64().unwrap();
    let mut failures = Vec::new();
    let mut near = |what: String, got: f64, want: f64, within: f64| {
        if (got - want).abs() > within {
            failures.push(format!("{what}: {got}, want {want}"));
        }
    };
    for case in vectors["cases"].as_array().unwrap() {
        let rate = case["rate"].as_u64().unwrap() as u32;
        let name = format!("{} at {rate}", case["preset"].as_str().unwrap());
        let c = CrossfeedCoefficients::new(
            case["cutHz"].as_f64().unwrap(),
            case["feedDb"].as_f64().unwrap(),
            rate,
        );
        near(format!("{name} gain"), c.gain, case["gain"].as_f64().unwrap(), tolerance("coefficient"));
        near(format!("{name} pole"), c.pole, case["pole"].as_f64().unwrap(), tolerance("coefficient"));
        let own: Vec<f64> =
            case["impulse"]["own"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        let other: Vec<f64> =
            case["impulse"]["other"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        let mut left = vec![0f32; own.len()];
        left[0] = 1.0;
        let (got_own, got_other) = crossfed(c, &left, &vec![0f32; own.len()]);
        for n in 0..own.len() {
            near(format!("{name} own[{n}]"), got_own[n] as f64, own[n], tolerance("floatSample"));
            near(format!("{name} other[{n}]"), got_other[n] as f64, other[n], tolerance("floatSample"));
        }
        for point in case["response"].as_array().unwrap() {
            let hz = point["hz"].as_f64().unwrap();
            let (own_db, other_db) = c.response_db(hz, rate);
            near(format!("{name} own dB at {hz}"), own_db, point["ownDb"].as_f64().unwrap(), tolerance("db"));
            near(
                format!("{name} other dB at {hz}"),
                other_db,
                point["otherDb"].as_f64().unwrap(),
                tolerance("db"),
            );
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn crossfeed_leaves_sound_in_the_middle_exactly_as_it_was() {
    let c = crate::sound::crossfeed::CrossfeedCoefficients::new(650.0, 9.5, 44_100);
    let tone: Vec<f32> = (0..2_000).map(|n| if n % 7 < 3 { 1.0 } else { -1.0 }).collect();
    let (left, right) = crossfed(c, &tone, &tone);
    assert_eq!(left, tone);
    assert_eq!(right, tone);
}

#[test]
fn crossfeed_sends_the_bass_to_the_other_ear_at_the_feed_level() {
    for (cut, feed) in [(700.0, 4.5), (700.0, 6.0), (650.0, 9.5), (1_200.0, 3.0)] {
        let c = crate::sound::crossfeed::CrossfeedCoefficients::new(cut, feed, 48_000);
        let (own, other) = c.response_db(5.0, 48_000);
        assert!((own - other - feed).abs() < 0.05, "{cut} Hz {feed} dB: {own} {other}");
        let (_, at_cut) = c.response_db(cut, 48_000);
        assert!((other - 3.0 - at_cut).abs() < 0.2, "{cut} Hz {feed} dB at the cut");
    }
}

#[test]
fn the_shaper_runs_crossfeed_after_the_filters_and_skips_it_in_mono() {
    let hard_left = |shaper: &mut SoundShaper| {
        let mut samples: Vec<f32> = (0..4_800).flat_map(|_| [0.5f32, 0.0]).collect();
        shaper.process(&mut samples, 4_800);
        samples[4_000 * 2 + 1]
    };
    let mut settings = SoundSettings::default();
    settings.dsp.limiter = false;
    settings.dsp.crossfeed.on = true;
    let mut shaper = SoundShaper::new(48_000, 2);
    shaper.apply(&settings, 1.0, true);
    // The right ear hears the left side's bass at the feed level.
    let right = hard_left(&mut shaper);
    let expected = 0.5 * crate::sound::crossfeed::CrossfeedCoefficients::new(700.0, 4.5, 48_000).low_share();
    assert!((right as f64 - expected).abs() < 1e-4, "{right} vs {expected}");
    settings.dsp.mono = true;
    let mut mono = SoundShaper::new(48_000, 2);
    mono.apply(&settings, 1.0, true);
    assert_eq!(hard_left(&mut mono), 0.25);
}
