//! Makes the small lossy test files the engine's tests decode, from pure
//! sine tones, so no recorded music is ever checked in. Run from this
//! folder with `cargo run --release`; the files land in `../../tests/fixtures`.

use std::f64::consts::PI;
use std::fs;
use std::path::Path;

fn tone(freq: f64, rate: u32, frames: usize, amplitude: f64) -> Vec<f64> {
    (0..frames).map(|n| (2.0 * PI * freq * n as f64 / rate as f64).sin() * amplitude).collect()
}

// One second of 440 Hz at 44.1 kHz, mono, constant 96 kbps, with the LAME
// header that tells players the encoder's delay and padding.
fn mp3(out: &Path) {
    use mp3lame_encoder::{Bitrate, Builder, FlushGap, MonoPcm, Quality};
    let rate = 44_100;
    let samples: Vec<i16> =
        tone(440.0, rate, rate as usize, 0.5).iter().map(|s| (s * 32767.0).round() as i16).collect();
    let mut builder = Builder::new().expect("lame");
    builder.set_num_channels(1).unwrap();
    builder.set_sample_rate(rate).unwrap();
    builder.set_brate(Bitrate::Kbps96).unwrap();
    builder.set_quality(Quality::Best).unwrap();
    builder.set_to_write_vbr_tag(true).unwrap();
    let mut encoder = builder.build().expect("lame build");
    let mut bytes = Vec::with_capacity(64 << 10);
    encoder.encode_to_vec(MonoPcm(&samples), &mut bytes).expect("encode");
    encoder.flush_to_vec::<FlushGap>(&mut bytes).expect("flush");
    // The header frame goes over the empty one the encoder left at the start.
    let mut tag = Vec::with_capacity(encoder.lame_tag_size());
    encoder.lame_tag_encode_to_vec(&mut tag).expect("lame tag");
    bytes[..tag.len()].copy_from_slice(&tag);
    fs::write(out.join("tone-440-44k-mono.mp3"), bytes).unwrap();
}

// One second of 440 Hz at 48 kHz, stereo, in Ogg Opus.
fn opus(out: &Path) {
    use ogg::writing::{PacketWriteEndInfo, PacketWriter};
    use opus_rs::{Application, OpusEncoder};
    const PRE_SKIP: usize = 312; // the encoder's look-ahead at 48 kHz
    const FRAME: usize = 960; // 20 ms
    let rate = 48_000u32;
    let frames = rate as usize;
    let mono = tone(440.0, rate, frames, 0.5);
    let mut encoder = OpusEncoder::new(48_000, 2, Application::Audio).expect("opus");
    encoder.bitrate_bps = 128_000;
    encoder.complexity = 10;

    let serial = 0x0c70_a0d1;
    let mut file = Vec::new();
    let mut writer = PacketWriter::new(&mut file);
    let mut head = Vec::new();
    head.extend_from_slice(b"OpusHead");
    head.push(1);
    head.push(2);
    head.extend_from_slice(&(PRE_SKIP as u16).to_le_bytes());
    head.extend_from_slice(&rate.to_le_bytes());
    head.extend_from_slice(&0i16.to_le_bytes());
    head.push(0);
    writer.write_packet(head, serial, PacketWriteEndInfo::EndPage, 0).unwrap();
    let mut tags = Vec::new();
    tags.extend_from_slice(b"OpusTags");
    let vendor = b"octo-audio fixtures";
    tags.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
    tags.extend_from_slice(vendor);
    tags.extend_from_slice(&0u32.to_le_bytes());
    writer.write_packet(tags, serial, PacketWriteEndInfo::EndPage, 0).unwrap();

    let total = PRE_SKIP + frames;
    let packets = total.div_ceil(FRAME);
    let mut input = vec![0f32; FRAME * 2];
    let mut packet = vec![0u8; 1500];
    for p in 0..packets {
        for i in 0..FRAME {
            let n = p * FRAME + i;
            let s = mono.get(n).copied().unwrap_or(0.0) as f32;
            input[i * 2] = s;
            input[i * 2 + 1] = s;
        }
        let len = encoder.encode(&input, FRAME, &mut packet).expect("encode");
        let granule = ((p + 1) * FRAME).min(total) as u64;
        let end = if p + 1 == packets {
            PacketWriteEndInfo::EndStream
        } else if (p + 1) % 10 == 0 {
            PacketWriteEndInfo::EndPage
        } else {
            PacketWriteEndInfo::NormalPacket
        };
        writer.write_packet(packet[..len].to_vec(), serial, end, granule).unwrap();
    }
    drop(writer);
    fs::write(out.join("tone-440-48k-stereo.opus"), file).unwrap();
}

fn main() {
    let out = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../tests/fixtures");
    fs::create_dir_all(&out).unwrap();
    mp3(&out);
    opus(&out);
    println!("wrote fixtures to {}", out.display());
}
