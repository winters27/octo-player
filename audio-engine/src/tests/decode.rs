//! Decoding real files made from synthetic tones.

use crate::decode::Decoder;
use crate::error::ErrorKind;
use crate::source::{self, http::HttpOptions};
use crate::testing::fixtures::*;
use crate::testing::http_server::{Behaviour, TestServer};

fn open(path: &std::path::Path) -> Decoder {
    Decoder::open(source::open(path.to_str().unwrap(), HttpOptions::default()).unwrap()).unwrap()
}

fn decode_all(decoder: &mut Decoder) -> Vec<f32> {
    let mut all = Vec::new();
    let mut block = Vec::new();
    while decoder.next_block(&mut block).unwrap() {
        all.extend_from_slice(&block);
    }
    all
}

fn as_f32(samples: &[i16]) -> Vec<f32> {
    samples.iter().map(|&s| s as f32 / 32768.0).collect()
}

#[test]
fn wav_decodes_exactly() {
    let dir = temp_dir();
    let path = dir.join("tone.wav");
    let samples = sine(440.0, 44_100, 2, 0, 44_100, 0.5);
    write_wav(&path, 44_100, 2, &samples);
    let mut d = open(&path);
    let info = d.info().clone();
    assert_eq!(info.sample_rate, 44_100);
    assert_eq!(info.channels, 2);
    assert!(info.lossless);
    assert_eq!(info.duration_ms, Some(1_000));
    assert_eq!(decode_all(&mut d), as_f32(&samples));
}

#[test]
fn flac_decodes_exactly_and_mono_becomes_stereo() {
    let dir = temp_dir();
    let path = dir.join("mono.flac");
    let samples = sine(1_000.0, 48_000, 1, 0, 30_000, 0.8);
    write_flac(&path, 48_000, 1, &samples, &[]);
    let mut d = open(&path);
    assert_eq!(d.info().codec, "flac");
    assert_eq!(d.info().bits_per_sample, Some(16));
    let out = decode_all(&mut d);
    let expected: Vec<f32> = as_f32(&samples).into_iter().flat_map(|s| [s, s]).collect();
    assert_eq!(out, expected);
}

#[test]
fn seek_lands_on_the_exact_frame() {
    let dir = temp_dir();
    let path = dir.join("seek.flac");
    let rate = 44_100;
    // A ramp, so every frame has its own value.
    let samples: Vec<i16> =
        (0..rate * 3).flat_map(|n| [(n % 30_000) as i16, -((n % 30_000) as i16)]).collect();
    write_flac(&path, rate, 2, &samples, &[]);
    let mut d = open(&path);
    for target in [1.2345, 0.0, 2.5, 0.75, 2.999] {
        let landed = d.seek(target).unwrap();
        let frame = (target * rate as f64).round() as usize;
        assert!((landed - frame as f64 / rate as f64).abs() < 1.0 / rate as f64, "{target}: {landed}");
        let mut block = Vec::new();
        assert!(d.next_block(&mut block).unwrap());
        assert_eq!(block[0], samples[frame * 2] as f32 / 32768.0, "first frame after seeking to {target}");
        assert!((d.position_secs() - (frame as f64 + (block.len() / 2) as f64) / rate as f64).abs() < 1e-9);
    }
}

#[test]
fn reads_replaygain_tags() {
    let dir = temp_dir();
    let path = dir.join("tagged.flac");
    let samples = sine(440.0, 44_100, 2, 0, 4_096, 0.5);
    let tags = [
        ("REPLAYGAIN_TRACK_GAIN", "-6.50 dB"),
        ("REPLAYGAIN_TRACK_PEAK", "0.98"),
        ("R128_ALBUM_GAIN", "-512"),
    ];
    write_flac(&path, 44_100, 2, &samples, &tags);
    let d = open(&path);
    let rg = d.info().replay_gain.expect("tags read");
    assert_eq!(rg.track_gain, Some(-6.5));
    assert_eq!(rg.track_peak, Some(0.98));
    assert_eq!(rg.album_gain, Some(3.0)); // -2 dB R128 is +3 dB ReplayGain
}

#[test]
fn mp3_decodes_with_its_delay_and_padding_trimmed() {
    let path = fixture("tone-440-44k-mono.mp3");
    let mut d = open(&path);
    assert_eq!(d.info().codec, "mp3");
    assert!(!d.info().lossless);
    let out = decode_all(&mut d);
    // The fixture holds exactly 44100 frames of tone (see the README); the
    // encoder's delay and padding must not show up.
    assert_eq!(out.len() / 2, 44_100, "gapless length");
    let f = frequency(&out[8_000..80_000], 44_100);
    assert!((f - 440.0).abs() < 1.0, "{f}");
    // Seeking works too.
    let landed = d.seek(0.5).unwrap();
    assert!((landed - 0.5).abs() < 0.001);
}

#[cfg(feature = "opus")]
#[test]
fn opus_decodes_with_its_pre_skip_trimmed() {
    let path = fixture("tone-440-48k-stereo.opus");
    let mut d = open(&path);
    assert_eq!(d.info().codec, "opus");
    assert_eq!(d.info().sample_rate, 48_000);
    let out = decode_all(&mut d);
    assert_eq!(out.len() / 2, 48_000, "gapless length");
    let f = frequency(&out[10_000..90_000], 48_000);
    assert!((f - 440.0).abs() < 1.0, "{f}");
}

#[test]
fn streams_decode_like_files() {
    let rate = 44_100;
    let samples = sine(440.0, rate, 2, 0, rate as usize * 2, 0.5);
    let server = TestServer::start(flac_bytes(rate, 2, &samples, &[]), Behaviour::default());
    let mut d = Decoder::open(source::open(&server.url(), HttpOptions::default()).unwrap()).unwrap();
    assert_eq!(d.info().codec, "flac");
    d.seek(1.0).unwrap();
    let mut block = Vec::new();
    assert!(d.next_block(&mut block).unwrap());
    assert_eq!(block[0], samples[rate as usize * 2] as f32 / 32768.0);
}

#[test]
fn garbage_is_unsupported() {
    let dir = temp_dir();
    let path = dir.join("noise.flac");
    std::fs::write(&path, vec![7u8; 10_000]).unwrap();
    let err =
        Decoder::open(source::open(path.to_str().unwrap(), HttpOptions::default()).unwrap()).err().unwrap();
    assert_eq!(err.kind, ErrorKind::Unsupported);
}
