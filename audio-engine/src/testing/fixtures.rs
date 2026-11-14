//! Audio files made on the spot from synthetic tones, and the few small
//! checked-in ones (see `tests/fixtures/README.md`).

use std::path::{Path, PathBuf};
use std::sync::Once;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, SystemTime};

/// How long a test's folder is kept. Tests run in seconds, so a folder this
/// old belongs to no test still running.
const KEPT_FOR: Duration = Duration::from_secs(60 * 60);

/// A fresh folder for one test's files. The first call in a process clears
/// away the folders earlier runs left, once they are `KEPT_FOR` old.
pub fn temp_dir() -> PathBuf {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    static PRUNED: Once = Once::new();
    let root = std::env::temp_dir().join("octo-audio-tests");
    PRUNED.call_once(|| {
        if let Some(cutoff) = SystemTime::now().checked_sub(KEPT_FOR) {
            prune(&root, cutoff);
        }
    });
    let n = NEXT.fetch_add(1, Ordering::SeqCst);
    let dir = root.join(format!("{}-{n}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// Removes the folders in `root` last changed before `cutoff`, with all
/// they hold, and returns how many went. A folder that cannot be read or
/// removed (one a test elsewhere still has open) is left, and a missing
/// `root` is nothing to do.
pub fn prune(root: &Path, cutoff: SystemTime) -> usize {
    let Ok(entries) = std::fs::read_dir(root) else { return 0 };
    let mut removed = 0;
    for entry in entries.flatten() {
        let Ok(meta) = entry.metadata() else { continue };
        let old = meta.is_dir() && meta.modified().is_ok_and(|changed| changed < cutoff);
        if old && std::fs::remove_dir_all(entry.path()).is_ok() {
            removed += 1;
        }
    }
    removed
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

/// The true peak of interleaved stereo from frame `from` to `to`, as a level
/// (1 is full scale): the samples, and the sound read at 7 points between
/// each two, each through a windowed sinc (Blackman) 48 samples either side.
/// Finer and longer than the limiter's own reading, so it can check it.
pub fn true_peak(stereo: &[f32], from: usize, to: usize) -> f64 {
    use std::f64::consts::PI;
    const STEPS: usize = 8;
    const HALF: isize = 48;
    let frames = stereo.len() / 2;
    let taps: Vec<Vec<f64>> = (1..STEPS)
        .map(|p| {
            let along = p as f64 / STEPS as f64;
            let raw: Vec<f64> = (-HALF + 1..=HALF)
                .map(|n| {
                    let d = along - n as f64;
                    let span = HALF as f64;
                    let window = 0.42 + 0.5 * (PI * d / span).cos() + 0.08 * (2.0 * PI * d / span).cos();
                    (PI * d).sin() / (PI * d) * window
                })
                .collect();
            let sum: f64 = raw.iter().sum();
            raw.into_iter().map(|h| h / sum).collect()
        })
        .collect();
    let mut peak = 0f64;
    for ch in 0..2 {
        for i in from..to.min(frames) {
            peak = peak.max((stereo[i * 2 + ch] as f64).abs());
            for phase in &taps {
                let mut y = 0.0;
                for (j, h) in phase.iter().enumerate() {
                    let at = i as isize + j as isize - HALF + 1;
                    if at >= 0 && (at as usize) < frames {
                        y += stereo[at as usize * 2 + ch] as f64 * h;
                    }
                }
                peak = peak.max(y.abs());
            }
        }
    }
    peak
}

/// `audio` with an ID3v2.3 tag in front holding `text` frames (like
/// "TBPM") and `user` TXXX frames (description, value), as taggers write
/// them into MP3 files.
pub fn with_id3v2(audio: &[u8], text: &[(&str, &str)], user: &[(&str, &str)]) -> Vec<u8> {
    let mut frames = Vec::new();
    let mut frame = |id: &str, body: Vec<u8>| {
        frames.extend_from_slice(id.as_bytes());
        frames.extend_from_slice(&(body.len() as u32).to_be_bytes());
        frames.extend_from_slice(&[0, 0]);
        frames.extend_from_slice(&body);
    };
    for (id, value) in text {
        let mut body = vec![0u8];
        body.extend_from_slice(value.as_bytes());
        frame(id, body);
    }
    for (description, value) in user {
        let mut body = vec![0u8];
        body.extend_from_slice(description.as_bytes());
        body.push(0);
        body.extend_from_slice(value.as_bytes());
        frame("TXXX", body);
    }
    let size = frames.len() as u32;
    let synchsafe =
        [(size >> 21) as u8 & 0x7f, (size >> 14) as u8 & 0x7f, (size >> 7) as u8 & 0x7f, size as u8 & 0x7f];
    let mut out = b"ID3".to_vec();
    out.extend_from_slice(&[3, 0, 0]);
    out.extend_from_slice(&synchsafe);
    out.extend_from_slice(&frames);
    out.extend_from_slice(audio);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_test_folder_is_pruned_only_once_older_than_the_cutoff() {
        // A root of its own, so no other test's folder is touched.
        let root = temp_dir().join("pruned");
        let folder = root.join("left");
        std::fs::create_dir_all(folder.join("inner")).unwrap();
        std::fs::write(folder.join("inner").join("a.wav"), b"RIFF").unwrap();
        // Changed just now, after a cutoff an hour back: kept.
        assert_eq!(prune(&root, SystemTime::now() - Duration::from_secs(60 * 60)), 0);
        assert!(folder.exists());
        // Before a cutoff in the future: removed, with all it holds.
        assert_eq!(prune(&root, SystemTime::now() + Duration::from_secs(60)), 1);
        assert!(!folder.exists());
        // A root that is not there is no error.
        assert_eq!(prune(&root.join("missing"), SystemTime::now()), 0);
    }
}
