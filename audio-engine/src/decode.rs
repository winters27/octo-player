//! Turns a song into stereo floats at its own sample rate: format guess,
//! decoding with the encoder's delay and padding trimmed (gapless),
//! sample-accurate seeking, and the loudness tags.

use std::sync::OnceLock;

use symphonia::core::codecs::CodecParameters;
use symphonia::core::codecs::audio::{AudioDecoder, AudioDecoderOptions};
use symphonia::core::codecs::registry::CodecRegistry;
use symphonia::core::errors::Error as DecodeError;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo, TrackType};
use symphonia::core::io::{MediaSourceStream, MediaSourceStreamOptions};
use symphonia::core::meta::{MetadataOptions, StandardTag};
use symphonia::core::units::{Time, TimeBase};

use crate::error::{ErrorKind, Failure};
use crate::sound::replaygain::{self, ReplayGainInfo};
use crate::source::Opened;

/// What the decoder found out about a song.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct TrackInfo {
    /// Like "flac", "mp3", "aac", "opus".
    pub codec: String,
    pub lossless: bool,
    pub sample_rate: u32,
    pub channels: u32,
    pub bits_per_sample: Option<u32>,
    /// The length, when the file says.
    pub duration_ms: Option<u64>,
    /// The loudness tags in the file.
    pub replay_gain: Option<ReplayGainInfo>,
}

fn codecs() -> &'static CodecRegistry {
    static REGISTRY: OnceLock<CodecRegistry> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        let mut registry = CodecRegistry::new();
        symphonia::default::register_enabled_codecs(&mut registry);
        #[cfg(feature = "opus")]
        registry.register_audio_decoder::<crate::opus::OpusDecoder>();
        registry
    })
}

/// A song being decoded.
pub struct Decoder {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn AudioDecoder>,
    track_id: u32,
    time_base: Option<TimeBase>,
    info: TrackInfo,
    source_channels: usize,
    // Where the next frame handed out sits, in frames from the start.
    position: u64,
    // After a seek: the first frame wanted; earlier ones are dropped.
    skip_until: Option<u64>,
    interleaved: Vec<f32>,
}

impl Decoder {
    /// Reads the song's header and gets ready to decode.
    pub fn open(opened: Opened) -> Result<Self, Failure> {
        let mut hint = Hint::new();
        if let Some(ext) = &opened.hint {
            hint.with_extension(ext);
        }
        // Streams read in bigger steps, so fewer small reads wait on the network.
        let buffer_len = if opened.remote { 256 << 10 } else { 64 << 10 };
        let mss = MediaSourceStream::new(opened.source, MediaSourceStreamOptions { buffer_len });
        let format = symphonia::default::get_probe()
            .probe(&hint, mss, FormatOptions::default(), MetadataOptions::default())
            .map_err(|e| failure("could not read the format", e))?;
        Self::with_format(format)
    }

    fn with_format(mut format: Box<dyn FormatReader>) -> Result<Self, Failure> {
        let track = format
            .default_track(TrackType::Audio)
            .ok_or_else(|| Failure::new(ErrorKind::Unsupported, "no audio track"))?
            .clone();
        let Some(CodecParameters::Audio(params)) = track.codec_params.as_ref() else {
            return Err(Failure::new(ErrorKind::Unsupported, "the audio track has no codec"));
        };
        let decoder = codecs()
            .make_audio_decoder(params, &AudioDecoderOptions::default().gapless(true))
            .map_err(|e| failure("no decoder for this codec", e))?;
        let sample_rate = params.sample_rate.unwrap_or(44_100);
        let source_channels = params.channels.as_ref().map(|c| c.count()).unwrap_or(2).max(1);
        let short_name = decoder.codec_info().short_name.to_string();
        let lossless = ["flac", "alac", "pcm", "wav"].iter().any(|n| short_name.starts_with(n));
        let duration_ms = track.num_frames.map(|frames| frames * 1_000 / sample_rate as u64).or_else(|| {
            let (tb, dur) = (track.time_base?, track.duration?);
            tb.calc_duration(dur).map(|t| (t.as_secs_f64() * 1_000.0) as u64)
        });
        let info = TrackInfo {
            codec: short_name,
            lossless,
            sample_rate,
            channels: source_channels as u32,
            bits_per_sample: params.bits_per_sample,
            duration_ms,
            replay_gain: read_replay_gain(format.as_mut()),
        };
        Ok(Self {
            format,
            decoder,
            track_id: track.id,
            time_base: track.time_base,
            info,
            source_channels,
            position: 0,
            skip_until: None,
            interleaved: Vec::new(),
        })
    }

    pub fn info(&self) -> &TrackInfo {
        &self.info
    }

    pub fn sample_rate(&self) -> u32 {
        self.info.sample_rate
    }

    /// Where the next frame handed out sits, in seconds.
    pub fn position_secs(&self) -> f64 {
        self.position as f64 / self.info.sample_rate as f64
    }

    /// Decodes the next part of the song into `out` as interleaved stereo.
    /// False at the end of the song.
    pub fn next_block(&mut self, out: &mut Vec<f32>) -> Result<bool, Failure> {
        out.clear();
        loop {
            let packet = match self.format.next_packet() {
                Ok(Some(packet)) => packet,
                Ok(None) => return Ok(false),
                Err(DecodeError::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                    return Ok(false);
                }
                Err(DecodeError::ResetRequired) => return Ok(false),
                Err(e) => return Err(failure("could not read the next part", e)),
            };
            if packet.track_id != self.track_id {
                continue;
            }
            let first = self.frames_at(packet.pts.get() + packet.trim_start.get() as i64);
            let decoded = match self.decoder.decode(&packet) {
                Ok(decoded) => decoded,
                // A damaged packet is skipped, as players do.
                Err(DecodeError::DecodeError(e)) => {
                    log::warn!("skipping a damaged packet: {e}");
                    continue;
                }
                Err(e) => return Err(failure("decoding failed", e)),
            };
            let frames = decoded.frames();
            if frames == 0 {
                continue;
            }
            decoded.copy_to_vec_interleaved(&mut self.interleaved);
            let mut from = 0;
            if let Some(wanted) = self.skip_until {
                let start = first.unwrap_or(self.position);
                if start + frames as u64 <= wanted {
                    continue;
                }
                from = wanted.saturating_sub(start) as usize;
                self.position = start + from as u64;
                self.skip_until = None;
            }
            to_stereo(&self.interleaved[from * self.source_channels..], self.source_channels, out);
            self.position += (frames - from) as u64;
            return Ok(true);
        }
    }

    /// Jumps to `secs` and returns where playing will carry on, which is
    /// exactly there unless that is past the end.
    pub fn seek(&mut self, secs: f64) -> Result<f64, Failure> {
        let time = Time::try_from_secs_f64(secs.max(0.0))
            .ok_or_else(|| Failure::new(ErrorKind::InvalidArgument, "bad seek position"))?;
        let seeked = self
            .format
            .seek(SeekMode::Accurate, SeekTo::Time { time, track_id: Some(self.track_id) })
            .map_err(|e| failure("could not seek", e))?;
        self.decoder.reset();
        // The reader rounds the time down to a frame; round to the nearest
        // instead, never before where the reader says it can start.
        let required = self.frames_at(seeked.required_ts.get()).unwrap_or(0);
        let nearest = (secs.max(0.0) * self.info.sample_rate as f64).round() as u64;
        let wanted = if nearest.abs_diff(required) <= 1 { nearest.max(required) } else { required };
        self.skip_until = Some(wanted);
        self.position = wanted;
        Ok(wanted as f64 / self.info.sample_rate as f64)
    }

    // A timestamp in the track's time base, as a frame count.
    fn frames_at(&self, ts: i64) -> Option<u64> {
        let ts = ts.max(0) as u64;
        match self.time_base {
            None => Some(ts),
            Some(tb) => {
                let secs = f64::from(tb) * ts as f64;
                let frames = secs * self.info.sample_rate as f64;
                // A time base of one over the rate is the usual case: exact.
                if (f64::from(tb) * self.info.sample_rate as f64 - 1.0).abs() < 1e-9 {
                    Some(ts)
                } else {
                    Some(frames.round() as u64)
                }
            }
        }
    }
}

// Any channel count to stereo. Mono is doubled; surround is folded down
// with the usual weights, leaving out the bass channel.
fn to_stereo(src: &[f32], channels: usize, out: &mut Vec<f32>) {
    match channels {
        2 => out.extend_from_slice(src),
        1 => out.extend(src.iter().flat_map(|&s| [s, s])),
        6 => {
            const C: f32 = std::f32::consts::FRAC_1_SQRT_2;
            let norm = 1.0 / (1.0 + 2.0 * C);
            for f in src.chunks_exact(6) {
                out.push((f[0] + C * f[2] + C * f[4]) * norm);
                out.push((f[1] + C * f[2] + C * f[5]) * norm);
            }
        }
        n => {
            for f in src.chunks_exact(n) {
                out.push(f[0]);
                out.push(f[1]);
            }
        }
    }
}

// The loudness tags from every set of metadata the file has.
fn read_replay_gain(format: &mut dyn FormatReader) -> Option<ReplayGainInfo> {
    let mut pairs: Vec<(String, String)> = Vec::new();
    let mut metadata = format.metadata();
    loop {
        if let Some(rev) = metadata.current() {
            let tracks = rev.per_track.iter().flat_map(|t| t.metadata.tags.iter());
            for tag in rev.media.tags.iter().chain(tracks) {
                let named = match &tag.std {
                    Some(StandardTag::ReplayGainTrackGain(v)) => {
                        Some(("REPLAYGAIN_TRACK_GAIN", v.to_string()))
                    }
                    Some(StandardTag::ReplayGainTrackPeak(v)) => {
                        Some(("REPLAYGAIN_TRACK_PEAK", v.to_string()))
                    }
                    Some(StandardTag::ReplayGainAlbumGain(v)) => {
                        Some(("REPLAYGAIN_ALBUM_GAIN", v.to_string()))
                    }
                    Some(StandardTag::ReplayGainAlbumPeak(v)) => {
                        Some(("REPLAYGAIN_ALBUM_PEAK", v.to_string()))
                    }
                    _ => None,
                };
                match named {
                    Some((name, value)) => pairs.push((name.to_string(), value)),
                    None => {
                        // Free-form keys like "TXXX:R128_TRACK_GAIN" or
                        // "----:com.apple.iTunes:replaygain_track_gain".
                        let key = tag.raw.key.rsplit(':').next().unwrap_or(&tag.raw.key);
                        pairs.push((key.to_string(), tag.raw.value.to_string()));
                    }
                }
            }
        }
        if metadata.pop().is_none() {
            break;
        }
    }
    replaygain::from_tags(pairs.iter().map(|(k, v)| (k.as_str(), v.as_str())))
}

fn failure(what: &str, e: DecodeError) -> Failure {
    let kind = match &e {
        DecodeError::Unsupported(_) => ErrorKind::Unsupported,
        DecodeError::IoError(io) => {
            // A network failure from a stream arrives as an I/O error.
            match io.get_ref().and_then(|inner| inner.downcast_ref::<Failure>()) {
                Some(f) => return Failure { message: format!("{what}: {}", f.message), ..f.clone() },
                None => ErrorKind::Other,
            }
        }
        DecodeError::DecodeError(_) => ErrorKind::Decode,
        _ => ErrorKind::Decode,
    };
    Failure::new(kind, format!("{what}: {e}"))
}
