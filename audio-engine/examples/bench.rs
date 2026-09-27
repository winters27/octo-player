//! Measures how much processor time playback takes, stage by stage, for a
//! 44.1 kHz 16-bit FLAC going to a 48 kHz device with the equalizer and
//! the limiter on. Also writes the test file for a real-time run:
//!
//! cargo run --release --example bench
//! cargo run --release --example play -- target/bench/music.flac --eq rock --volume 0 --seconds 30

use std::path::Path;
use std::time::{Duration, Instant};

use flacenc::component::BitRepr;
use flacenc::error::Verify;
use octo_audio::deck::Deck;
use octo_audio::decode::Decoder;
use octo_audio::lane::Lane;
use octo_audio::mixer::{MixState, Mixer, Transition};
use octo_audio::pace::Pace;
use octo_audio::sound::model::{EqMode, EqSettings, SoundSettings, eq_presets};
use octo_audio::sound::replaygain::Loudness;
use octo_audio::sound::shaper::SoundShaper;
use octo_audio::source::{self, http::HttpOptions};

const SECONDS: usize = 60;
const RATE: u32 = 44_100;

// Something shaped like music for the encoder: chords with vibrato, a
// beat-like envelope and a little noise, so FLAC's prediction works as hard
// as on a record.
fn music() -> Vec<i32> {
    let frames = RATE as usize * SECONDS;
    let mut seed = 0x1234_5678u32;
    let mut out = Vec::with_capacity(frames * 2);
    let notes = [110.0, 164.8, 220.0, 277.2, 329.6, 440.0, 659.3];
    for n in 0..frames {
        let t = n as f64 / RATE as f64;
        let beat = 0.6 + 0.4 * (-(t * 2.0).fract() * 6.0).exp();
        let mut l = 0.0;
        let mut r = 0.0;
        for (i, f) in notes.iter().enumerate() {
            let vibrato = 1.0 + 0.003 * (t * (4.0 + i as f64)).sin();
            let s = (2.0 * std::f64::consts::PI * f * vibrato * t).sin() / (i + 2) as f64;
            l += s * (0.6 + 0.1 * i as f64);
            r += s * (1.2 - 0.1 * i as f64);
        }
        seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        let noise = (seed >> 16) as f64 / 65_536.0 - 0.5;
        out.push(((l * beat * 0.35 + noise * 0.01) * 32_767.0) as i32);
        out.push(((r * beat * 0.35 + noise * 0.01) * 32_767.0) as i32);
    }
    out
}

fn write_flac(path: &Path) {
    let samples = music();
    let config = flacenc::config::Encoder::default().into_verified().expect("config");
    let source = flacenc::source::MemSource::from_samples(&samples, 2, 16, RATE as usize);
    let stream = flacenc::encode_with_fixed_block_size(&config, source, config.block_size).expect("encode");
    let mut sink = flacenc::bitsink::ByteSink::new();
    stream.write(&mut sink).expect("write");
    let mut bytes = sink.as_slice().to_vec();
    // Fixed-size blocks, as real encoders mark them.
    let max_block = [bytes[10], bytes[11]];
    bytes[8..10].copy_from_slice(&max_block);
    std::fs::write(path, bytes).unwrap();
}

fn settings() -> SoundSettings {
    let rock = eq_presets().into_iter().find(|p| p.name == "Rock").unwrap();
    let mut s = SoundSettings {
        eq: EqSettings {
            enabled: true,
            mode: EqMode::Graphic,
            graphic_gains: rock.gains,
            ..Default::default()
        },
        ..Default::default()
    };
    s.dsp.limiter = true;
    s
}

fn report(what: &str, took: Duration) {
    let per_second = took.as_secs_f64() * 1_000.0 / SECONDS as f64;
    println!("{what:<44} {per_second:>7.3} ms per second of audio  ({:.3} % of one core)", per_second / 10.0);
}

fn main() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("target").join("bench");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("music.flac");
    if !path.exists() {
        println!("writing {} ...", path.display());
        write_flac(&path);
    }
    let size = std::fs::metadata(&path).unwrap().len();
    println!(
        "{} s of 44.1 kHz 16-bit stereo FLAC, {:.1} MB ({:.0} kbps)\n",
        SECONDS,
        size as f64 / 1e6,
        size as f64 * 8.0 / SECONDS as f64 / 1e3
    );
    let source_path = path.to_string_lossy().to_string();

    // Decoding alone.
    let mut best = Duration::MAX;
    for _ in 0..3 {
        let started = Instant::now();
        let mut d = Decoder::open(source::open(&source_path, HttpOptions::default()).unwrap()).unwrap();
        let mut block = Vec::new();
        while d.next_block(&mut block).unwrap() {}
        best = best.min(started.elapsed());
    }
    report("FLAC decode", best);

    // The shaping alone: EQ (10 bands) and the limiter, on 48 kHz stereo.
    let s = settings();
    let mut shaper = SoundShaper::new(48_000, 2);
    shaper.apply(&s, 1.0, true);
    let mut buf: Vec<f32> = (0..48_000 * 2).map(|i| ((i as f32) * 0.001).sin() * 0.5).collect();
    let started = Instant::now();
    for _ in 0..SECONDS {
        for block in buf.chunks_mut(512) {
            let frames = block.len() / 2;
            shaper.process(block, frames);
        }
    }
    report("EQ (10 bands) + preamp + limiter", started.elapsed());

    // The whole chain, as the player thread runs it: decode on the deck's
    // thread, resample 44.1 to 48 kHz, mix, shape.
    let mut best = Duration::MAX;
    for _ in 0..3 {
        let mut mixer = Mixer::new(48_000, 0, &s, Pace::default());
        let deck = Deck::open(1, source_path.clone(), 0.0, HttpOptions::default());
        let mut lane = Lane::new(deck, Loudness::default(), &s.replay_gain, 48_000);
        lane.last = true;
        mixer.start(lane, Transition::Start);
        let mut out = vec![0f32; 1024 * 2];
        let mut markers = Vec::new();
        let started = Instant::now();
        loop {
            let (n, state) = mixer.render(&mut out, &mut markers);
            markers.clear();
            if n == 0 && state == MixState::Ended {
                break;
            }
            if n == 0 {
                std::thread::yield_now();
            }
        }
        best = best.min(started.elapsed());
    }
    report("whole chain (decode + resample + EQ + limiter)", best);

    // The same with speed at 1.25x.
    let mut mixer = Mixer::new(48_000, 0, &s, Pace { speed: 1.25, pitch: 1.0 });
    let deck = Deck::open(1, source_path.clone(), 0.0, HttpOptions::default());
    let mut lane = Lane::new(deck, Loudness::default(), &s.replay_gain, 48_000);
    lane.last = true;
    mixer.start(lane, Transition::Start);
    let mut out = vec![0f32; 1024 * 2];
    let mut markers = Vec::new();
    let started = Instant::now();
    loop {
        let (n, state) = mixer.render(&mut out, &mut markers);
        markers.clear();
        if n == 0 && state == MixState::Ended {
            break;
        }
        if n == 0 {
            std::thread::yield_now();
        }
    }
    report("whole chain at 1.25x speed (per song second)", started.elapsed());
}
