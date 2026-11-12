use super::synthetic::{SongLayer, SyntheticSong};
use super::*;
use crate::crossfade::FadeSong;

fn tone(from_ms: f64, to_ms: f64, hz: f64, db: f64) -> SongLayer {
    SongLayer {
        kind: "tone".into(),
        from_ms,
        to_ms,
        hz,
        db,
        end_db: db,
        bpm: 0.0,
        first_ms: 0.0,
        every: 1,
        decay_ms: 0.0,
    }
}

fn click(length_ms: f64, bpm: f64) -> Vec<SongLayer> {
    let pulse = |hz, db, every, decay_ms| SongLayer {
        kind: "pulse".into(),
        from_ms: 0.0,
        to_ms: length_ms,
        hz,
        db,
        end_db: db,
        bpm,
        first_ms: 0.0,
        every,
        decay_ms,
    };
    vec![tone(0.0, length_ms, 300.0, -26.0), pulse(60.0, -6.0, 4, 80.0), pulse(2_000.0, -16.0, 1, 10.0)]
}

#[test]
fn curve_ends_and_middle() {
    for k in [0.0, 0.2, 0.4, 0.55, 0.75, 1.0] {
        let (o0, i0) = gains(0.0, k);
        let (o1, i1) = gains(1.0, k);
        assert!((o0 - 1.0).abs() < 1e-9 && i0.abs() < 1e-6, "k {k}: {o0} {i0}");
        assert!(o1.abs() < 1e-9 && (i1 - 1.0).abs() < 1e-9, "k {k}: {o1} {i1}");
        let (o, i) = gains(0.5, k);
        let expected = std::f64::consts::FRAC_1_SQRT_2 * (1.0 - k) + 0.5 * k;
        assert!((o - expected).abs() < 1e-9, "k {k}: {o} vs {expected}");
        assert!((o * o + i * i - 1.0).abs() < 1e-9);
    }
    // A quarter of the way the S-curve has barely moved: 0.0452.
    assert!((s_curve(0.25) - 0.045_18).abs() < 1e-4, "{}", s_curve(0.25));
    // k = 0 is the equal-power crossfade.
    for i in 0..=100 {
        let t = i as f64 / 100.0;
        let (o, n) = gains(t, 0.0);
        assert!((o - (t * std::f64::consts::FRAC_PI_2).cos()).abs() < 1e-12);
        assert!((n - (t * std::f64::consts::FRAC_PI_2).sin()).abs() < 1e-7);
    }
}

#[test]
fn the_out_gain_only_falls() {
    for k in [0.2, 0.4, 0.75] {
        let mut last = 1.0;
        for i in 0..=200 {
            let (o, _) = gains(i as f64 / 200.0, k);
            assert!(o <= last + 1e-12);
            last = o;
        }
    }
}

// A 14 s intro at 120 BPM: B is at its full level 12 s in, and A fades over
// the two bars around that point.
const INTRO_OVERLAP_MS: f64 = 14_000.0;
const INTRO_FULL_AT_MS: f64 = 12_000.0;

fn intro_plan() -> TransitionPlan {
    let (fade, rise) = (4_000.0, 2_000.0);
    TransitionPlan {
        kind: TransitionKind::Intro,
        k: 0.0,
        hold_share: (INTRO_FULL_AT_MS - fade / 2.0) / INTRO_OVERLAP_MS,
        rise_share: rise / INTRO_OVERLAP_MS,
        headroom_db: planner::OVERLAP_HEADROOM_DB,
        ..TransitionPlan::fixed_crossfade(200_000, INTRO_OVERLAP_MS as i64, "intro")
    }
}

#[test]
fn an_intro_holds_then_fades_and_rises_then_holds() {
    let plan = intro_plan();
    let (hold, rise) = (plan.hold_share, plan.rise_share);
    // A at its own level until the hold ends, then an equal-power fade whose
    // middle is B's full point.
    assert_eq!(plan.out_gain(0.0), 1.0);
    assert_eq!(plan.out_gain(hold), 1.0);
    let middle = plan.out_gain(INTRO_FULL_AT_MS / INTRO_OVERLAP_MS);
    assert!((middle - std::f64::consts::FRAC_1_SQRT_2).abs() < 1e-12, "{middle}");
    assert!(plan.out_gain(1.0) < 1e-12);
    // B up on a quarter sine over the rise, then at its own level.
    assert_eq!(plan.in_gain(0.0), 0.0);
    assert!((plan.in_gain(rise / 2.0) - std::f64::consts::FRAC_1_SQRT_2).abs() < 1e-12);
    assert_eq!((plan.in_gain(rise), plan.in_gain(hold), plan.in_gain(1.0)), (1.0, 1.0, 1.0));
    let (mut last_out, mut last_in) = (1.0, 0.0);
    for i in 0..=1_000 {
        let (out, gain_in) = plan.gains(i as f64 / 1_000.0);
        assert!(out <= last_out + 1e-12 && gain_in >= last_in - 1e-12, "at {i}");
        assert!((0.0..=1.0).contains(&out) && (0.0..=1.0).contains(&gain_in), "at {i}");
        (last_out, last_in) = (out, gain_in);
    }
    assert_eq!(intro_gains(0.3, hold, rise), plan.gains(0.3));
    // Shares at their edges: no hold fades from the start, no rise is full at once.
    assert_eq!((intro_out_gain(0.0, 0.0), intro_in_gain(0.0, 0.0)), (1.0, 1.0));
    assert!((intro_out_gain(0.5, 0.0) - std::f64::consts::FRAC_1_SQRT_2).abs() < 1e-12);
    assert_eq!(intro_out_gain(1.0, 1.0), 1.0);
    // Every other kind keeps its curve, whatever the shares say.
    let blend = TransitionPlan { kind: TransitionKind::Blend, k: BLEND_CURVE, ..plan.clone() };
    assert_eq!(blend.gains(0.5), gains(0.5, BLEND_CURVE));
}

// The mixer's headroom at `p`: `db` lower through the blend, coming and
// going over its first and last tenth.
fn mixer_headroom(db: f64, p: f64) -> f64 {
    let w = (p / 0.1).min((1.0 - p) / 0.1).clamp(0.0, 1.0);
    10f64.powf(-db * w / 20.0)
}

// The level of the two songs summed through `plan`, in dB against their
// bodies (both at the same level), every 10 ms: A at its body throughout;
// B's quiet intro rising from 24 dB to 6 dB under its body by its full
// point (where the intro rule has the intro end), at its body from there.
fn summed_levels(plan: &TransitionPlan) -> Vec<(f64, f64)> {
    let b_db = |ms: f64| if ms < INTRO_FULL_AT_MS { -24.0 + 18.0 * ms / INTRO_FULL_AT_MS } else { 0.0 };
    (0..=1_400)
        .map(|i| {
            let ms = i as f64 * 10.0;
            let t = ms / INTRO_OVERLAP_MS;
            let (a, b) = plan.gains(t);
            let h = mixer_headroom(plan.headroom_db, t);
            let power = (a * h).powi(2) + (b * h).powi(2) * 10f64.powf(b_db(ms) / 10.0);
            (ms, 10.0 * power.log10())
        })
        .collect()
}

#[test]
fn an_intro_keeps_the_level_across_the_next_songs_full_point() {
    let plan = intro_plan();
    for (ms, db) in summed_levels(&plan) {
        assert!(db.abs() <= 3.0, "{db:.2} dB at {ms} ms");
    }
    // A blend's curve over the same overlap lets the level fall into B's
    // quiet intro.
    let blend = TransitionPlan { kind: TransitionKind::Blend, k: BLEND_CURVE, ..plan };
    let lowest = summed_levels(&blend).into_iter().map(|(_, db)| db).fold(f64::INFINITY, f64::min);
    assert!(lowest < -6.0, "{lowest:.2} dB");
}

#[test]
fn sweeps_follow_their_paths() {
    let s = FILTER_STRENGTH;
    assert!((outgoing_low_pass_hz(0.0, s) - 18_000.0).abs() < 1e-9);
    assert!((outgoing_low_pass_hz(1.0, s) - 18_000.0 * (450.0f64 / 18_000.0).powf(s)).abs() < 1e-9);
    assert!((outgoing_high_pass_hz(0.5, s) - 210.0).abs() < 1e-9);
    assert!((outgoing_high_pass_hz(0.9, s) - 210.0).abs() < 1e-9);
    assert!((incoming_high_pass_hz(0.0, s) - 472.5).abs() < 1e-9);
    assert!((incoming_high_pass_hz(0.6, s) - 20.0).abs() < 1e-9);
    // Stepped: holds the last beat's cutoff, then glides into the next beat.
    let beats = [0.25, 0.5, 0.75];
    let at = |t| stepped_outgoing_low_pass_hz(t, s, &beats, 0.0625);
    assert_eq!(at(0.1), outgoing_low_pass_hz(0.0, s));
    assert_eq!(at(0.3), outgoing_low_pass_hz(0.25, s));
    assert_eq!(at(0.5), outgoing_low_pass_hz(0.5, s));
    let gliding = at(0.5 - 0.03125);
    assert!(gliding < outgoing_low_pass_hz(0.25, s) && gliding > outgoing_low_pass_hz(0.5, s));
}

#[test]
fn the_planner_gets_its_lengths_in_song_time() {
    let settings = AutomixSettings {
        smart_transitions: true,
        filter_sweeps: false,
        match_tempo: true,
        max_overlap_ms: 8_000,
        fade_curve: FadeCurve::Linear,
        longest_intro_ms: 16_000,
    };
    // At half speed a second heard is half a second of the song.
    let half = settings.plan_settings(8_000, 0.5);
    assert_eq!((half.max_overlap_ms, half.longest_intro_ms), (4_000, 8_000));
    assert_eq!(settings.plan_settings(8_000, 1.0).longest_intro_ms, 16_000);
    assert_eq!(settings.plan_settings(8_000, 1.5).longest_intro_ms, 24_000);
    assert!(half.smart && !half.filter_sweeps && half.beat_match && half.fade_curve == FadeCurve::Linear);
    // No longest blend of its own: the crossfade length.
    let plain = AutomixSettings { max_overlap_ms: 0, ..settings };
    assert_eq!(plain.plan_settings(6_000, 0.5).max_overlap_ms, 3_000);
    assert_eq!(AutomixSettings::default().longest_intro_ms as i64, AUTOMIX_LONGEST_INTRO_MS);
}

#[test]
fn a_late_plan_keeps_its_end_and_drops_the_bar_lock() {
    let mut plan = TransitionPlan::fixed_crossfade(200_000, 6_000, "test");
    plan.start_ms = 150_000;
    plan.entry_ms = 1_200;
    plan.k = 0.55;
    plan.filter_strength = 0.7;
    plan.beat_ms = Some(500.0);
    plan.beat_anchor_ms = Some(100.0);
    plan.beat_match_rate = Some(1.02);
    let late = plan.late_from(152_500).unwrap();
    assert_eq!((late.start_ms, late.entry_ms, late.overlap_ms), (152_500, 1_200, 3_500));
    assert_eq!((late.k, late.filter_strength), (0.55, 0.7));
    assert!(late.late && late.beat_ms.is_none() && late.beat_match_rate.is_none());
    assert!(late.reason.starts_with("late crossfade at 152.50 s over 3.50 s"), "{}", late.reason);
    // Not yet passed: unchanged times.
    assert_eq!(plan.late_from(100_000).unwrap().start_ms, 150_000);
    assert!(plan.late_from(155_600).is_none());
    assert_eq!(plan.describe(), "automix: crossfade at 194.00 s over 6.00 s: test");
}

#[test]
fn the_section_analyzer_finds_a_click_tracks_tempo() {
    let song = SyntheticSong { rate: 44_100, channels: 1, length_ms: 40_000, layers: click(40_000.0, 120.0) };
    let mono = song.render_mono(0.0, 30_000.0);
    let mut analyzer = SectionAnalyzer::new(SectionKind::Head, None);
    analyzer.begin(0.0, 44_100);
    for block in mono.chunks(1_000) {
        analyzer.pcm(block);
    }
    let analysis = analyzer.finish().unwrap();
    let tempo = analysis.features.tempo.unwrap();
    assert!(tempo.confident(), "{tempo:?}");
    assert!((tempo.bpm - 120.0).abs() < 0.5, "{tempo:?}");
    assert_eq!(analysis.envelope.size(), 3_000);
    assert!(SectionAnalyzer::new(SectionKind::Tail, None).finish().is_none());
}

fn head_tempo(bpm: f64, tag_bpm: Option<f64>) -> Tempo {
    let song = SyntheticSong { rate: 44_100, channels: 1, length_ms: 30_000, layers: click(30_000.0, bpm) };
    let mut analyzer = SectionAnalyzer::new(SectionKind::Head, tag_bpm);
    analyzer.begin(0.0, 44_100);
    analyzer.pcm(&song.render_mono(0.0, 30_000.0));
    analyzer.finish().unwrap().features.tempo.unwrap()
}

#[test]
fn a_tag_tempo_picks_only_among_the_found_octaves() {
    let found = head_tempo(120.0, None);
    assert!((found.bpm - 120.0).abs() < 0.5, "{found:?}");
    assert!((head_tempo(120.0, Some(61.0)).bpm - 60.0).abs() < 0.5);
    assert!((head_tempo(120.0, Some(238.0)).bpm - 240.0).abs() < 1.0);
    assert_eq!(head_tempo(120.0, Some(121.0)), found);
    // A tag near no octave of the tempo found is ignored.
    for tag in [125.0, 62.0, 0.0, -120.0, 90.0, f64::NAN] {
        assert_eq!(head_tempo(120.0, Some(tag)), found, "tag {tag}");
    }
    let fast = head_tempo(128.0, None);
    assert!((fast.bpm - 128.0).abs() < 0.5, "{fast:?}");
    assert_eq!(head_tempo(128.0, Some(70.0)), fast);
}

// A half-time beat at 80 BPM whose kicks fall on the 1st, 4th and 7th
// sixteenths of every two beats, with a snare on the third beat of each
// bar: the kicks repeat every three sixteenths more often than every beat.
fn three_three_two(length_ms: f64) -> Vec<SongLayer> {
    let hit = |hz, db, bpm, first_ms, decay_ms| SongLayer {
        kind: "pulse".into(),
        from_ms: 0.0,
        to_ms: length_ms,
        hz,
        db,
        end_db: db,
        bpm,
        first_ms,
        every: 1,
        decay_ms,
    };
    vec![
        tone(0.0, length_ms, 300.0, -30.0),
        hit(60.0, -6.0, 40.0, 0.0, 80.0),
        hit(60.0, -6.0, 40.0, 562.5, 80.0),
        hit(60.0, -6.0, 40.0, 1_125.0, 80.0),
        hit(2_000.0, -12.0, 20.0, 1_500.0, 30.0),
    ]
}

#[test]
fn a_three_three_two_kick_pattern_keeps_its_beat() {
    let song =
        SyntheticSong { rate: 44_100, channels: 1, length_ms: 60_000, layers: three_three_two(60_000.0) };
    let tempo = song.head().features.tempo.expect("a beat");
    // 80 BPM or its double, never the 107 BPM of the kicks' three sixteenths.
    let folded = fold_tempo_ratio(tempo.bpm / 80.0);
    assert!((folded - 1.0).abs() < 0.01, "{tempo:?}");
    assert!(tempo.confident(), "{tempo:?}");
}

#[test]
fn the_live_analyzer_hears_the_level_and_the_tempo() {
    let song = SyntheticSong { rate: 48_000, channels: 1, length_ms: 30_000, layers: click(30_000.0, 100.0) };
    let mono = song.render_mono(0.0, 30_000.0);
    let mut live = LiveAnalyzer::new();
    assert_eq!(live.summary(None), LiveAnalysis::default());
    live.begin(0.0, 48_000);
    live.pcm(&mono);
    let heard = live.summary(None);
    assert_eq!(heard.heard_ms, 30_000);
    assert!(heard.body_level_db.unwrap() > -30.0, "{heard:?}");
    assert!((heard.tempo_prior.unwrap() - 100.0).abs() < 0.5, "{heard:?}");
    // A quiet pad alone has no beat.
    let pad = SyntheticSong {
        rate: 48_000,
        channels: 1,
        length_ms: 20_000,
        layers: vec![tone(0.0, 20_000.0, 300.0, -14.0)],
    };
    let mut live = LiveAnalyzer::new();
    live.begin(0.0, 48_000);
    live.pcm(&pad.render_mono(0.0, 20_000.0));
    assert_eq!(live.summary(None).tempo_prior, None);
}

#[test]
fn genres_that_are_never_mixed() {
    assert!(is_plain_genre("Classical; Baroque"));
    assert!(is_plain_genre("SPOKEN word"));
    assert!(!is_plain_genre("Hip-Hop"));
    // Case is folded for plain letters only.
    assert!(!is_plain_genre("spo\u{212A}en"));
}

#[test]
fn a_tempo_is_trusted_only_when_strong_and_steady() {
    let tempo = |confidence, steady| Tempo {
        bpm: 120.0,
        confidence,
        consistency: 0.5,
        steady,
        beat_ms: 500.0,
        first_beat_ms: 0.0,
        downbeat_ms: 0.0,
    };
    assert!(tempo(0.4, true).confident());
    assert!(!tempo(0.39, true).confident());
    assert!(!tempo(3.0, false).confident());
}

#[test]
fn a_long_fade_out_hands_over_where_the_outro_starts() {
    let pad = |from_ms: f64, to_ms: f64, db: f64, end_db: f64| {
        vec![
            SongLayer { end_db, ..tone(from_ms, to_ms, 300.0, db) },
            SongLayer { end_db: end_db - 3.0, ..tone(from_ms, to_ms, 100.0, db - 3.0) },
        ]
    };
    let mut layers = pad(0.0, 180_000.0, -14.0, -14.0);
    layers.extend(pad(180_000.0, 220_000.0, -14.0, -74.0));
    let a = SyntheticSong { rate: 22_050, channels: 1, length_ms: 220_000, layers };
    let b = SyntheticSong {
        rate: 22_050,
        channels: 1,
        length_ms: 180_000,
        layers: pad(0.0, 180_000.0, -20.0, -20.0),
    };
    let song = |len| FadeSong { album_id: None, album_order: None, duration_ms: len };
    let plan = plan_transition(
        &song(220_000),
        Some(&song(180_000)),
        Some(&a.tail()),
        Some(&b.head()),
        &PlanSettings::new(8_000),
        &TransitionContext::default(),
    );
    let outro = a.tail().features.outro_start_ms.unwrap();
    assert!((outro - 184_000).abs() < 500, "{outro}");
    assert!((plan.start_ms - outro).abs() < 500, "{plan:?}");
    assert_eq!((plan.overlap_ms, plan.late), (8_000, false), "{plan:?}");
    assert!(plan.start_ms < 200_000);
}

#[test]
fn a_quiet_intro_plays_under_the_end_of_a_hot_song() {
    let pad = |from_ms: f64, to_ms: f64, db: f64| {
        vec![tone(from_ms, to_ms, 300.0, db), tone(from_ms, to_ms, 100.0, db - 3.0)]
    };
    let a =
        SyntheticSong { rate: 22_050, channels: 1, length_ms: 200_000, layers: pad(0.0, 200_000.0, -14.0) };
    let mut layers = pad(0.0, 6_000.0, -38.0);
    layers.extend(pad(6_000.0, 180_000.0, -14.0));
    let b = SyntheticSong { rate: 22_050, channels: 1, length_ms: 180_000, layers };
    let head = b.head();
    let intro = head.features.intro_end_ms.unwrap();
    assert!((intro - 5_750).abs() <= 20, "{intro}");
    let song = |len| FadeSong { album_id: None, album_order: None, duration_ms: len };
    let plan_with = |max_overlap_ms| {
        plan_transition(
            &song(200_000),
            Some(&song(180_000)),
            Some(&a.tail()),
            Some(&head),
            &PlanSettings::new(max_overlap_ms),
            &TransitionContext::default(),
        )
    };
    let plan = plan_with(8_000);
    // The blend lasts until the next song is near its full level, ending
    // where the hot song does.
    assert!(plan.overlap_ms >= intro - plan.entry_ms, "{plan:?}");
    assert_eq!(plan.start_ms + plan.overlap_ms, 199_750, "{plan:?}");
    assert!(plan.reason.contains("(full from 5."), "{}", plan.reason);
    // Never longer than the slider allows.
    assert_eq!(plan_with(4_000).overlap_ms, 4_000);
}

#[test]
fn every_plan_says_where_it_may_end_at_the_latest() {
    let pad = |from_ms: f64, to_ms: f64, db: f64, end_db: f64| {
        vec![
            SongLayer { end_db, ..tone(from_ms, to_ms, 300.0, db) },
            SongLayer { end_db: end_db - 3.0, ..tone(from_ms, to_ms, 100.0, db - 3.0) },
        ]
    };
    // A fades out from 180 s and is silent for its last seconds, so its
    // sound ends well before its length.
    let mut layers = pad(0.0, 180_000.0, -14.0, -14.0);
    layers.extend(pad(180_000.0, 196_000.0, -14.0, -74.0));
    let a = SyntheticSong { rate: 22_050, channels: 1, length_ms: 200_000, layers };
    let b = SyntheticSong {
        rate: 22_050,
        channels: 1,
        length_ms: 180_000,
        layers: pad(0.0, 180_000.0, -20.0, -20.0),
    };
    let (tail, head) = (a.tail(), b.head());
    let sound_end = tail.features.sound_end_ms.unwrap();
    assert!((185_000..196_000).contains(&sound_end), "{sound_end}");
    let song = |len| FadeSong { album_id: None, album_order: None, duration_ms: len };
    let plan =
        |settings: &PlanSettings, context: &TransitionContext, tail: Option<&SectionAnalysis>, len_b| {
            plan_transition(&song(200_000), Some(&song(len_b)), tail, Some(&head), settings, context)
        };
    let smart = PlanSettings::new(8_000);
    let start = TransitionContext::default();
    let near_the_end = TransitionContext { now_ms: 188_000, played_ms: 188_000, ..start.clone() };
    let opera = TransitionContext { next_genre: Some("Opera".into()), ..start.clone() };
    let fixed = PlanSettings { smart: false, ..smart.clone() };
    let len_a = 200_000;
    let cases = [
        ("planned", plan(&smart, &start, Some(&tail), 180_000), None, false, sound_end),
        // No start left: the blend at the end, by the same latest end.
        ("late", plan(&smart, &near_the_end, Some(&tail), 180_000), None, true, sound_end),
        // Without the end's analysis the late plan ends short of the length.
        ("unscouted", plan(&smart, &start, None, 180_000), None, true, len_a - planner::END_MARGIN_MS),
        ("fixed", plan(&fixed, &start, Some(&tail), 180_000), Some(TransitionKind::Crossfade), false, len_a),
        ("genre", plan(&smart, &opera, Some(&tail), 180_000), Some(TransitionKind::Crossfade), false, len_a),
        (
            "short song",
            plan(&smart, &start, Some(&tail), 20_000),
            Some(TransitionKind::Crossfade),
            false,
            len_a,
        ),
        (
            "gapless",
            plan(&PlanSettings::new(0), &start, Some(&tail), 180_000),
            Some(TransitionKind::Gapless),
            false,
            len_a,
        ),
    ];
    for (what, plan, kind, late, latest_end) in cases {
        if let Some(kind) = kind {
            assert_eq!(plan.kind, kind, "{what}: {plan:?}");
        } else {
            assert!(
                !matches!(plan.kind, TransitionKind::Gapless | TransitionKind::Crossfade),
                "{what}: {plan:?}"
            );
        }
        assert_eq!(plan.late, late, "{what}: {plan:?}");
        assert_eq!(plan.latest_end_ms, latest_end, "{what}: {plan:?}");
        assert_eq!((plan.hold_share, plan.rise_share), (0.0, 0.0), "{what}");
        // None of these has a bar grid to keep, so each may move any number
        // of bars.
        assert!(!plan.bar_locked(), "{what}: {plan:?}");
        assert_eq!(plan.late_move_bars, LATE_MOVE_UNBOUNDED, "{what}");
        assert!(plan.start_ms + plan.overlap_ms <= plan.latest_end_ms, "{what}: {plan:?}");
    }
    // The engine's own late start keeps the end it may reach.
    let fixed = TransitionPlan::fixed_crossfade(200_000, 8_000, "test");
    assert_eq!((fixed.latest_end_ms, fixed.late_move_bars), (200_000, LATE_MOVE_UNBOUNDED));
    let mut locked = fixed.clone();
    (locked.beat_ms, locked.beat_anchor_ms, locked.late_move_bars) = (Some(500.0), Some(0.0), 0);
    let late = locked.late_from(195_000).unwrap();
    assert_eq!((late.latest_end_ms, late.late_move_bars), (200_000, LATE_MOVE_UNBOUNDED));
}

#[test]
fn a_locked_blend_moves_late_only_when_its_beats_stay_together() {
    let song = |bpm: f64, length_ms: i64| SyntheticSong {
        rate: 22_050,
        channels: 1,
        length_ms,
        layers: click(length_ms as f64, bpm),
    };
    let a = song(120.0, 200_000);
    let tail = a.tail();
    let fade_song = |len| FadeSong { album_id: None, album_order: None, duration_ms: len };
    let plan = |head: &SectionAnalysis, beat_match: bool| {
        let settings = PlanSettings { beat_match, ..PlanSettings::new(8_000) };
        let (a, b) = (fade_song(200_000), fade_song(180_000));
        plan_transition(&a, Some(&b), Some(&tail), Some(head), &settings, &TransitionContext::default())
    };
    let near = song(118.0, 180_000).head();
    // 120 against 118 BPM, unmatched: the two drift apart, so it stays put.
    let unmatched = plan(&near, false);
    assert!(unmatched.bar_locked() && unmatched.beat_match_rate.is_none(), "{unmatched:?}");
    assert_eq!(unmatched.late_move_bars, 0);
    // Matched, or between equal tempos, the beats stay together anywhere.
    let matched = plan(&near, true);
    assert!(matched.bar_locked() && matched.beat_match_rate.is_some(), "{matched:?}");
    assert_eq!(matched.late_move_bars, LATE_MOVE_UNBOUNDED);
    let mut same = song(120.0, 180_000).head();
    let (a_tempo, b_tempo) = (tail.features.tempo.unwrap(), same.features.tempo.unwrap());
    same.features.tempo = Some(Tempo { bpm: a_tempo.bpm, beat_ms: a_tempo.beat_ms, ..b_tempo });
    let equal = plan(&same, false);
    assert!(equal.bar_locked(), "{equal:?}");
    assert_eq!(equal.late_move_bars, LATE_MOVE_UNBOUNDED);
}

fn whole_song_profile(song: &SyntheticSong) -> TransitionProfile {
    let mono = song.render_mono(0.0, song.length_ms as f64);
    TransitionProfile::analyze(&mono, song.rate, 1)
}

#[test]
fn a_profile_plans_the_blend_a_scout_would() {
    let fade = |from_ms: f64, to_ms: f64, db: f64, end_db: f64| {
        vec![
            SongLayer { end_db, ..tone(from_ms, to_ms, 300.0, db) },
            SongLayer { end_db: end_db - 3.0, ..tone(from_ms, to_ms, 100.0, db - 3.0) },
        ]
    };
    let mut layers = fade(0.0, 180_000.0, -14.0, -14.0);
    layers.extend(fade(180_000.0, 220_000.0, -14.0, -74.0));
    let a = SyntheticSong { rate: 22_050, channels: 1, length_ms: 220_000, layers };
    let mut layers = fade(0.0, 6_000.0, -38.0, -38.0);
    layers.extend(fade(6_000.0, 180_000.0, -20.0, -20.0));
    let b = SyntheticSong { rate: 22_050, channels: 1, length_ms: 180_000, layers };
    let (pa, pb) = (whole_song_profile(&a).rounded(), whole_song_profile(&b).rounded());
    assert_eq!((pa.duration_ms, pb.duration_ms), (220_000, 180_000));
    assert_eq!(pa.tail.start_ms, 160_000);
    assert_eq!((pa.tail.hop_ms, pa.tail.levels.len()), (100, 600));
    assert_eq!((pb.head.start_ms, pb.head.levels.len()), (0, 450));
    let body = pa.body_db.unwrap();
    assert!((body - a.tail().with_body_level(body).features.body_db).abs() < 1e-9);

    let song = |len| FadeSong { album_id: None, album_order: None, duration_ms: len };
    let plan = |tail: &SectionAnalysis, head: &SectionAnalysis, context: &TransitionContext| {
        plan_transition(
            &song(220_000),
            Some(&song(180_000)),
            Some(tail),
            Some(head),
            &PlanSettings::new(8_000),
            context,
        )
    };
    let heard = TransitionContext { body_level_db: Some(body), ..Default::default() };
    let scouted = plan(&a.tail(), &b.head(), &heard);
    let profiled = plan(&pa.tail_analysis(), &pb.head_analysis(), &TransitionContext::default());
    assert_eq!(profiled.kind, scouted.kind, "{profiled:?} / {scouted:?}");
    assert!((profiled.start_ms - scouted.start_ms).abs() <= 300, "{profiled:?} / {scouted:?}");
    assert!((profiled.overlap_ms - scouted.overlap_ms).abs() <= 300, "{profiled:?} / {scouted:?}");
    assert!((profiled.entry_ms - scouted.entry_ms).abs() <= 20, "{profiled:?} / {scouted:?}");
    assert!(!profiled.late);
}

#[test]
fn a_profile_carries_the_whole_songs_tempo() {
    let song = SyntheticSong { rate: 22_050, channels: 1, length_ms: 90_000, layers: click(90_000.0, 120.0) };
    let profile = whole_song_profile(&song);
    assert!((profile.tempo_prior().unwrap() - 120.0).abs() < 0.5, "{:?}", profile.tempo);
    let tail = profile.tail.features.tempo.unwrap();
    assert!(tail.confident() && (tail.bpm - 120.0).abs() < 0.5, "{tail:?}");
    assert_eq!(profile.tail.start_ms, 30_000);
    // A song shorter than the end a profile covers starts its end at 0.
    let short =
        SyntheticSong { rate: 22_050, channels: 1, length_ms: 20_000, layers: click(20_000.0, 120.0) };
    let short = whole_song_profile(&short);
    assert_eq!((short.head.start_ms, short.tail.start_ms, short.tail.levels.len()), (0, 0, 200));
}

// A song whose first `intro_ms` are a quiet pad 20 dB under the rest.
fn quiet_start(intro_ms: f64, length_ms: i64) -> SyntheticSong {
    let pad = |from_ms: f64, to_ms: f64, db: f64| {
        vec![tone(from_ms, to_ms, 300.0, db), tone(from_ms, to_ms, 100.0, db - 3.0)]
    };
    let mut layers = pad(0.0, intro_ms, -34.0);
    layers.extend(pad(intro_ms, length_ms as f64, -14.0));
    SyntheticSong { rate: 22_050, channels: 1, length_ms, layers }
}

#[test]
fn heads_cover_45_s() {
    assert_eq!(HEAD_SECS, 45.0);
    assert_eq!(profile::PROFILE_HEAD_MS, 45_000);
    let song = quiet_start(6_000.0, 180_000);
    let head = song.head();
    assert_eq!((head.envelope.start_ms, head.envelope.end_ms()), (0, 45_000));
    let profile = whole_song_profile(&song);
    assert_eq!((profile.head.start_ms, profile.head.levels.len()), (0, 450));
}

#[test]
fn a_profiles_start_is_measured_against_the_whole_song() {
    // 30 s of quiet pad: on its own the start would read the pad as its
    // body and call the intro over at once.
    let song = quiet_start(30_000.0, 180_000);
    let profile = whole_song_profile(&song);
    let body = profile.body_db.unwrap();
    assert!((profile.head.features.body_db - body).abs() < 1e-9, "{:?}", profile.head.features);
    let intro = profile.head.features.intro_end_ms.unwrap();
    assert!((intro - 29_750).abs() <= 300, "{intro}");
    // The planner reads the same from the profile.
    assert_eq!(profile.head_analysis().features.intro_end_ms, Some(intro));
}

#[test]
fn a_quiet_start_longer_than_the_head_ends_at_the_heads_end() {
    let song = quiet_start(50_000.0, 180_000);
    let profile = whole_song_profile(&song);
    assert_eq!(profile.head.features.sound_start_ms, Some(0));
    assert_eq!(profile.head.features.intro_end_ms, Some(45_000));
    // Measured against its own quiet level, the scouted start is loud at once.
    assert_eq!(song.head().features.intro_end_ms, Some(0));
    // The same section against the song's body, as the planner gets it.
    let quiet = song.envelope(0.0, 45_000.0, 4_093);
    let against = analyze_head(quiet.clone(), None, profile.body_db);
    assert_eq!(against.features.intro_end_ms, Some(quiet.end_ms()));
    // A silent start has no intro at all.
    let silent = SyntheticSong { rate: 22_050, channels: 1, length_ms: 45_000, layers: Vec::new() };
    let silent = analyze_head(silent.envelope(0.0, 45_000.0, 4_093), None, Some(-14.0));
    assert_eq!((silent.features.sound_start_ms, silent.features.intro_end_ms), (None, None));
}

#[test]
fn a_profile_says_which_analysis_pass_made_it() {
    let profile = whole_song_profile(&quiet_start(6_000.0, 60_000));
    assert_eq!((profile.version, profile.revision), (PROFILE_VERSION, PROFILE_REVISION));
    assert_eq!((PROFILE_VERSION, PROFILE_REVISION), (1, 2));
    let handed = SongProfile::from(&profile);
    assert_eq!(handed.revision, 2);
    assert_eq!(handed.to_profile().unwrap().revision, 2);
    // A profile from before revisions, which an app hands over as 1, reads
    // the same way.
    let older = SongProfile { revision: 1, ..handed };
    let read = older.to_profile().expect("a revision 1 profile is read");
    assert_eq!((read.version, read.revision), (1, 1));
    assert_eq!(read.head, profile.head);
}

#[test]
fn levels_go_out_in_half_decibel_steps() {
    assert_eq!(level_code(SILENT_DB), 0);
    assert_eq!(level_code(-120.0), 0);
    assert_eq!(level_code(-14.26), 171);
    assert_eq!(level_of_code(171), -14.5);
    assert_eq!(level_code(40.0), 255);
    for code in 0..=255u8 {
        assert_eq!(level_code(level_of_code(code)), code);
    }
}

#[test]
fn a_silent_song_has_a_profile_without_sound() {
    let profile = TransitionProfile::analyze(&vec![0.0; 22_050 * 40], 22_050, 1);
    assert_eq!(profile.body_db, None);
    assert_eq!(profile.tempo, None);
    assert_eq!(profile.tail.features.sound_start_ms, None);
    assert!(profile.tail.levels.iter().all(|&l| l == SILENT_DB));
}
