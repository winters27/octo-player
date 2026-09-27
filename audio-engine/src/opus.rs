//! Opus decoding for the Ogg reader, through a pure Rust decoder that
//! passes the official conformance vectors. The decoder is plugged into the
//! same codec registry as the others.

use std::fmt;

use opus_decoder::OpusDecoder as Inner;
use symphonia::core::audio::{
    AsGenericAudioBufferRef, AudioBuffer, AudioMut, AudioSpec, GenericAudioBufferRef, layouts,
};
use symphonia::core::codecs::CodecInfo;
use symphonia::core::codecs::audio::well_known::CODEC_ID_OPUS;
use symphonia::core::codecs::audio::{
    AudioCodecParameters, AudioDecoder, AudioDecoderOptions, FinalizeResult,
};
use symphonia::core::codecs::registry::{RegisterableAudioDecoder, SupportedAudioCodec};
use symphonia::core::errors::{Error, Result, decode_error, unsupported_error};
use symphonia::core::packet::PacketRef;
use symphonia::core::support_audio_codec;

// Opus always decodes at 48 kHz here, the rate its timestamps count in.
const RATE: u32 = 48_000;

// The longest packet: 120 ms.
const MAX_FRAMES: usize = RATE as usize * 120 / 1_000;

/// Opus as a codec the format readers can hand packets to.
pub struct OpusDecoder {
    params: AudioCodecParameters,
    inner: Inner,
    channels: usize,
    pcm: Vec<f32>,
    buf: AudioBuffer<f32>,
    // Frames at the very start the encoder asks players to drop.
    pre_skip: u64,
    // The level the header asks for, as a multiplier.
    output_gain: f32,
    gapless: bool,
}

impl fmt::Debug for OpusDecoder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OpusDecoder")
            .field("channels", &self.channels)
            .field("pre_skip", &self.pre_skip)
            .finish()
    }
}

// The parts of the Ogg Opus identification header used here.
struct Head {
    pre_skip: u16,
    output_gain_q8: i16,
}

fn read_head(extra: &[u8]) -> Option<Head> {
    if extra.len() < 19 || &extra[..8] != b"OpusHead" {
        return None;
    }
    Some(Head {
        pre_skip: u16::from_le_bytes([extra[10], extra[11]]),
        output_gain_q8: i16::from_le_bytes([extra[16], extra[17]]),
    })
}

impl OpusDecoder {
    fn try_new(params: &AudioCodecParameters, opts: &AudioDecoderOptions) -> Result<Self> {
        let channels = params.channels.as_ref().map(|c| c.count()).unwrap_or(0);
        if !(1..=2).contains(&channels) {
            return unsupported_error("opus: only mono and stereo are supported");
        }
        let head = params.extra_data.as_deref().and_then(read_head);
        let inner = Inner::new(RATE, channels)
            .map_err(|_| Error::DecodeError("opus: could not start the decoder"))?;
        let layout =
            if channels == 1 { layouts::CHANNEL_LAYOUT_MONO } else { layouts::CHANNEL_LAYOUT_STEREO };
        Ok(Self {
            params: params.clone(),
            inner,
            channels,
            pcm: vec![0.0; MAX_FRAMES * channels],
            buf: AudioBuffer::new(AudioSpec::new(RATE, layout), MAX_FRAMES),
            pre_skip: head.as_ref().map(|h| h.pre_skip as u64).unwrap_or(0),
            output_gain: head.map(|h| 10f32.powf(h.output_gain_q8 as f32 / 256.0 / 20.0)).unwrap_or(1.0),
            gapless: opts.gapless,
        })
    }
}

impl AudioDecoder for OpusDecoder {
    fn reset(&mut self) {
        self.inner.reset();
    }

    fn codec_info(&self) -> &CodecInfo {
        &Self::supported_codecs()[0].info
    }

    fn codec_params(&self) -> &AudioCodecParameters {
        &self.params
    }

    fn decode_ref(&mut self, packet: &PacketRef<'_>) -> Result<GenericAudioBufferRef<'_>> {
        self.buf.clear();
        let frames = match self.inner.decode_float(packet.data, &mut self.pcm, false) {
            Ok(n) => n,
            Err(_) => return decode_error("opus: damaged packet"),
        };
        let samples = frames * self.channels;
        if self.output_gain != 1.0 {
            for s in &mut self.pcm[..samples] {
                *s *= self.output_gain;
            }
        }
        self.buf.render_uninit(Some(frames));
        self.buf.copy_from_slice_interleaved(&&self.pcm[..samples]);
        if self.gapless {
            // The Ogg reader counts time from the first encoded frame, so
            // the header's pre-skip is dropped here, from packets that
            // start inside it.
            let pts = packet.pts.get();
            let start_trim = packet.trim_start.get()
                + if pts < self.pre_skip as i64 { (self.pre_skip as i64 - pts.max(0)) as u64 } else { 0 };
            let start = (start_trim as usize).min(frames);
            let end = (packet.trim_end.get() as usize).min(frames - start);
            self.buf.trim(start, end);
        }
        Ok(self.buf.as_generic_audio_buffer_ref())
    }

    fn finalize(&mut self) -> FinalizeResult {
        FinalizeResult::default()
    }

    fn last_decoded(&self) -> GenericAudioBufferRef<'_> {
        self.buf.as_generic_audio_buffer_ref()
    }
}

impl RegisterableAudioDecoder for OpusDecoder {
    fn try_registry_new(
        params: &AudioCodecParameters,
        opts: &AudioDecoderOptions,
    ) -> Result<Box<dyn AudioDecoder>>
    where
        Self: Sized,
    {
        Ok(Box::new(OpusDecoder::try_new(params, opts)?))
    }

    fn supported_codecs() -> &'static [SupportedAudioCodec] {
        &[support_audio_codec!(CODEC_ID_OPUS, "opus", "Opus")]
    }
}
