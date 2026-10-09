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
    assert_eq!((pb.head.start_ms, pb.head.levels.len()), (0, 300));
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
