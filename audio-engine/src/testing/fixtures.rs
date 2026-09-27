//! Audio files made on the spot from synthetic tones, and the few small
//! checked-in ones (see `tests/fixtures/README.md`).

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};

/// A fresh folder for one test's files.
pub fn temp_dir() -> PathBuf {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let n = NEXT.fetch_add(1, Ordering::SeqCst);
    let dir = std::env::temp_dir().join("octo-audio-tests").join(format!("{}-{n}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// A checked-in fixture.
pub fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests").join("fixtures").join(name)
}

/// A sine as 16-bit samples, interleaved, with the same value on every
/// channel. `start` is the frame the tone starts counting from, so two
/// pieces made with consecutive starts join into one unbroken tone.
pub fn sine(freq: f64, rate: u32, channels: usize, start: usize, frames: usize, amplitude: f64) -> Vec<i16> {
    (start..start + frames)
        .flat_map(|n| {
            let v = (2.0 * std::f64::consts::PI * freq * n as f64 / rate as f64).sin() * amplitude * 32767.0;
            std::iter::repeat_n(v.round() as i16, channels)
        })
        .collect()
}

/// A 16-bit PCM WAV file.
pub fn write_wav(path: &Path, rate: u32, channels: u16, samples: &[i16]) {
    let data_len = (samples.len() * 2) as u32;
    let mut out = Vec::with_capacity(44 + data_len as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data_len).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&channels.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * channels as u32 * 2).to_le_bytes());
    out.extend_from_slice(&(channels * 2).to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data_len.to_le_bytes());
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
    std::fs::write(path, out).unwrap();
}

/// A 16-bit FLAC file, with optional tags.
pub fn write_flac(path: &Path, rate: u32, channels: usize, samples: &[i16], tags: &[(&str, &str)]) {
    std::fs::write(path, flac_bytes(rate, channels, samples, tags)).unwrap();
}

pub fn flac_bytes(rate: u32, channels: usize, samples: &[i16], tags: &[(&str, &str)]) -> Vec<u8> {
    use flacenc::component::BitRepr;
    use flacenc::error::Verify;
    let wide: Vec<i32> = samples.iter().map(|&s| s as i32).collect();
    let config = flacenc::config::Encoder::default().into_verified().expect("encoder config");
    let source = flacenc::source::MemSource::from_samples(&wide, channels, 16, rate as usize);
    let stream =
        flacenc::encode_with_fixed_block_size(&config, source, config.block_size).expect("flac encode");
    let mut sink = flacenc::bitsink::ByteSink::new();
    stream.write(&mut sink).expect("flac write");
    let mut bytes = sink.as_slice().to_vec();
    // The encoder counts the short last block as the smallest, which the
    // format says not to; real encoders write the fixed size twice, and
    // readers take a difference to mean variable-size blocks.
    let max_block = [bytes[10], bytes[11]];
    bytes[8..10].copy_from_slice(&max_block);
    if tags.is_empty() { bytes } else { with_vorbis_comments(bytes, tags) }
}

// Adds a tag block after the FLAC file's last metadata block.
fn with_vorbis_comments(bytes: Vec<u8>, tags: &[(&str, &str)]) -> Vec<u8> {
    assert_eq!(&bytes[..4], b"fLaC");
    let mut at = 4;
    loop {
        let last = bytes[at] & 0x80 != 0;
        let len = u32::from_be_bytes([0, bytes[at + 1], bytes[at + 2], bytes[at + 3]]) as usize;
        if last {
            break;
        }
        at += 4 + len;
    }
    let block_end = at + 4 + u32::from_be_bytes([0, bytes[at + 1], bytes[at + 2], bytes[at + 3]]) as usize;
    let mut body = Vec::new();
    let vendor = b"octo-audio tests";
    body.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
    body.extend_from_slice(vendor);
    body.extend_from_slice(&(tags.len() as u32).to_le_bytes());
    for (k, v) in tags {
        let entry = format!("{k}={v}");
        body.extend_from_slice(&(entry.len() as u32).to_le_bytes());
        body.extend_from_slice(entry.as_bytes());
    }
    let mut out = bytes[..block_end].to_vec();
    out[at] &= 0x7f;
    out.push(0x80 | 4);
    out.extend_from_slice(&(body.len() as u32).to_be_bytes()[1..]);
    out.extend_from_slice(&body);
    out.extend_from_slice(&bytes[block_end..]);
    out
}

/// Frequency of the left channel of interleaved stereo, from its upward
/// zero crossings.
pub fn frequency(stereo: &[f32], rate: u32) -> f64 {
    let left: Vec<f32> = stereo.chunks_exact(2).map(|f| f[0]).collect();
    let crossings: Vec<usize> = (1..left.len()).filter(|&i| left[i - 1] < 0.0 && left[i] >= 0.0).collect();
    let span = (crossings[crossings.len() - 1] - crossings[0]) as f64;
    (crossings.len() - 1) as f64 * rate as f64 / span
}
