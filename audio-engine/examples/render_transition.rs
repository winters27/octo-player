//! Renders the transition from one song into another through the real
//! mixer, with no sound device, to a WAV file to listen to. Prints the plan.
//!
//! cargo run --release --example render_transition -- <A> <B> <out.wav> [options]
//!
//! A and B are paths or stream addresses. The file holds the 10 s before
//! the blend, the blend, and the 10 s after it, as 32-bit float stereo.
//!
//! Options (each overrides the planner's choice):
//!   --crossfade <ms>   the crossfade length the rules start from (8000)
//!   --start <s>        when in A the blend starts
//!   --entry <s>        where B comes in
//!   --overlap <s>      how long both sound
//!   --k <0..1>         gain curve parameter (0 is equal power)
//!   --filters <0..1>   filter sweep strength
//!   --rate <factor>    B's rate through the blend
//!   --headroom <dB>    both songs lowered this much through the blend
//!   --plain            the fixed crossfade, without scouting

use std::io::Write;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

use octo_audio::automix::{
    self, PlanSettings, SectionAnalyzer, SectionKind, TransitionContext, TransitionPlan,
};
use octo_audio::crossfade::FadeSong;
use octo_audio::deck::Deck;
use octo_audio::lane::Lane;
use octo_audio::mixer::{FadeShape, LowPassSteps, MixState, Mixer, Transition};
use octo_audio::pace::Pace;
use octo_audio::scout::{SectionPart, scout_section};
use octo_audio::sound::model::{ReplayGainSettings, SoundSettings};
use octo_audio::sound::replaygain::Loudness;
use octo_audio::source::http::HttpOptions;

const RATE: u32 = 48_000;
const AROUND_SECS: f64 = 10.0;

fn value<T: std::str::FromStr>(args: &mut impl Iterator<Item = String>, name: &str) -> T {
    let raw = args.next().unwrap_or_else(|| fail(&format!("{name} needs a value")));
    raw.parse().unwrap_or_else(|_| fail(&format!("{name}: cannot read {raw}")))
}

fn fail(message: &str) -> ! {
    eprintln!("{message}");
    std::process::exit(2);
}

// The song's length in seconds, from its decoder.
fn length_of(source: &str) -> f64 {
    let deck = Deck::open(0, source.to_string(), 0.0, HttpOptions::default());
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        if let Some(info) = deck.info() {
            let ms = info.duration_ms.unwrap_or_else(|| fail(&format!("{source}: length unknown")));
            return ms as f64 / 1_000.0;
        }
        if let octo_audio::deck::DeckStatus::Failed(f) = deck.status() {
            fail(&format!("{source}: {f}"));
        }
        if Instant::now() > until {
            fail(&format!("{source}: did not open"));
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn scout(source: &str, part: SectionPart) -> Option<automix::SectionAnalysis> {
    let kind = if matches!(part, SectionPart::Head { .. }) { SectionKind::Head } else { SectionKind::Tail };
    let mut analyzer = SectionAnalyzer::new(kind, None);
    let never = AtomicBool::new(false);
    match scout_section(source, HttpOptions::default(), part, &never, &mut analyzer) {
        Ok(()) => analyzer.finish(),
        Err(f) => {
            eprintln!("could not scout {source}: {f}");
            None
        }
    }
}

fn write_wav(path: &str, samples: &[f32]) -> std::io::Result<()> {
    let data = (samples.len() * 4) as u32;
    let mut out = Vec::with_capacity(44 + data as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&3u16.to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&RATE.to_le_bytes());
    out.extend_from_slice(&(RATE * 8).to_le_bytes());
    out.extend_from_slice(&8u16.to_le_bytes());
    out.extend_from_slice(&32u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data.to_le_bytes());
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
    std::fs::File::create(path)?.write_all(&out)
}

fn main() {
    let mut args = std::env::args().skip(1);
    let mut files = Vec::new();
    let mut crossfade_ms = 8_000u32;
    let mut plain = false;
    let (mut start, mut entry, mut overlap) = (None::<f64>, None::<f64>, None::<f64>);
    let (mut k, mut filters, mut rate, mut headroom) = (None::<f64>, None::<f64>, None::<f64>, None::<f64>);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--crossfade" => crossfade_ms = value(&mut args, &arg),
            "--start" => start = Some(value(&mut args, &arg)),
            "--entry" => entry = Some(value(&mut args, &arg)),
            "--overlap" => overlap = Some(value(&mut args, &arg)),
            "--k" => k = Some(value(&mut args, &arg)),
            "--filters" => filters = Some(value(&mut args, &arg)),
            "--rate" => rate = Some(value(&mut args, &arg)),
            "--headroom" => headroom = Some(value(&mut args, &arg)),
            "--plain" => plain = true,
            other if other.starts_with("--") => fail(&format!("unknown option {other}")),
            other => files.push(other.to_string()),
        }
    }
    let [a, b, out] = files.as_slice() else {
        fail("usage: render_transition <A> <B> <out.wav> [options]");
    };
    let (a_len, b_len) = (length_of(a), length_of(b));
    let blend_ms = (crossfade_ms as f64).min(a_len.min(b_len) / 2.0 * 1_000.0) as i64;
    let mut plan = if plain {
        TransitionPlan::fixed_crossfade((a_len * 1_000.0) as i64, blend_ms, "fixed crossfade")
    } else {
        let tail = scout(a, SectionPart::Tail { secs: automix::TAIL_SECS, len_secs: Some(a_len) });
        let head = scout(b, SectionPart::Head { secs: automix::HEAD_SECS });
        let song = |len: f64, album: &str| FadeSong {
            album_id: Some(album.into()),
            album_order: None,
            duration_ms: (len * 1_000.0) as u64,
        };
        let settings = PlanSettings { beat_match: true, ..PlanSettings::new(crossfade_ms as i64) };
        automix::plan_transition(
            &song(a_len, "a"),
            Some(&song(b_len, "b")),
            tail.as_ref(),
            head.as_ref(),
            &settings,
            &TransitionContext { played_ms: (a_len * 1_000.0) as i64, ..Default::default() },
        )
    };
    if let Some(v) = start {
        plan.start_ms = (v * 1_000.0) as i64;
    }
    if let Some(v) = entry {
        plan.entry_ms = (v * 1_000.0) as i64;
    }
    if let Some(v) = overlap {
        plan.overlap_ms = (v * 1_000.0) as i64;
    }
    if let Some(v) = k {
        plan.k = v;
    }
    if let Some(v) = filters {
        plan.filter_strength = v;
    }
    if rate.is_some() {
        plan.beat_match_rate = rate;
    }
    if let Some(v) = headroom {
        plan.headroom_db = v;
    }
    println!("{}", plan.describe());

    let from = (plan.start_secs() - AROUND_SECS).max(0.0);
    let a_deck = Deck::open(1, a.clone(), from, HttpOptions::default());
    let b_deck = Deck::open(2, b.clone(), plan.entry_secs(), HttpOptions::default());
    let until = Instant::now() + Duration::from_secs(30);
    while !(a_deck.is_ready() && b_deck.is_ready()) {
        if Instant::now() > until {
            fail("the songs did not open");
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    let mut mixer = Mixer::new(RATE, 0, &SoundSettings::default(), Pace::default());
    let mut lane = Lane::new(a_deck, Loudness::default(), &ReplayGainSettings::default(), RATE);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let overlap = plan.overlap_secs();
    let shape = FadeShape {
        k: plan.k,
        filter_strength: plan.filter_strength,
        steps: plan.low_pass_glide().map(|glide| LowPassSteps { beats: plan.beat_progress(), glide }),
        rate: plan.beat_match_rate,
        silence_gate_db: (plan.k != 0.0).then_some(automix::SILENCE_FLOOR_DB as f32),
        headroom_db: plan.headroom_db as f32,
    };
    mixer.plan_fade(1, plan.start_secs(), (overlap * RATE as f64) as u64, b_deck, Loudness::default(), shape);

    let wanted = ((plan.start_secs() - from + overlap + AROUND_SECS) * RATE as f64) as usize;
    let mut samples = Vec::with_capacity(wanted * 2);
    let mut block = vec![0.0f32; 2_048];
    let mut markers = Vec::new();
    let until = Instant::now() + Duration::from_secs(120);
    while samples.len() / 2 < wanted {
        let (made, state) = mixer.render(&mut block, &mut markers);
        samples.extend_from_slice(&block[..made * 2]);
        if made == 0 {
            if state == MixState::Ended || Instant::now() > until {
                break;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    samples.truncate(wanted * 2);
    let peak = samples.iter().fold(0.0f32, |m, s| m.max(s.abs()));
    write_wav(out, &samples).unwrap_or_else(|e| fail(&format!("{out}: {e}")));
    println!(
        "wrote {out}: {:.1} s, the blend from {:.1} s in, peak {:.2} dBFS",
        samples.len() as f64 / 2.0 / RATE as f64,
        plan.start_secs() - from,
        20.0 * peak.max(1e-10).log10()
    );
}
