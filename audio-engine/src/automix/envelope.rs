//! Loudness and onsets over time for one stretch of a song.

use std::f64::consts::PI;

use super::dsp::{Biquad, RealFft};

/// One envelope value per this many milliseconds of sound.
pub const ENVELOPE_HOP_MS: i32 = 10;

/// The level written for a hop with no sound at all, in dBFS.
pub const SILENT_DB: f32 = -100.0;

/// Where the bass band of the envelope ends: kick drums and bass lines.
pub const BASS_CUTOFF_HZ: f64 = 150.0;

/// Where the middle band of the onsets ends and the high band begins.
pub const TREBLE_FROM_HZ: f64 = 2_500.0;

/// The onsets look at the last this many samples at the end of every hop.
pub const ONSET_FFT_SIZE: usize = 1024;

/// Magnitudes are compressed as ln(1 + ONSET_COMPRESSION * magnitude), with
/// a full-scale sine reading magnitude 1 in its bin.
pub const ONSET_COMPRESSION: f64 = 100.0;

/// Loudness over time for one stretch of a song, one value per hop. `db` is
/// the whole band, `low_db` only the sound below [`BASS_CUTOFF_HZ`], both as
/// RMS in dBFS. `onset` is the spectral flux: for each of three bands (below
/// [`BASS_CUTOFF_HZ`], up to [`TREBLE_FROM_HZ`], and above), the mean rise of
/// the compressed FFT magnitudes since the hop before, rises only, added
/// up. `low_onset` is the bass band's part of it. Onsets peak on drum hits
/// and note starts. Times are song times: hop i covers
/// `start_ms + i * hop_ms` onwards.
#[derive(Clone, Debug, PartialEq)]
pub struct SectionEnvelope {
    pub start_ms: i64,
    pub hop_ms: i32,
    pub db: Vec<f32>,
    pub low_db: Vec<f32>,
    pub onset: Vec<f32>,
    pub low_onset: Vec<f32>,
}

impl SectionEnvelope {
    pub fn new(
        start_ms: i64,
        hop_ms: i32,
        db: Vec<f32>,
        low_db: Vec<f32>,
        onset: Vec<f32>,
        low_onset: Vec<f32>,
    ) -> Self {
        assert!(hop_ms > 0, "hop_ms must be positive");
        assert!(
            db.len() == low_db.len() && db.len() == onset.len() && db.len() == low_onset.len(),
            "the envelope's arrays must be the same length"
        );
        SectionEnvelope { start_ms, hop_ms, db, low_db, onset, low_onset }
    }

    pub fn size(&self) -> usize {
        self.db.len()
    }

    pub fn end_ms(&self) -> i64 {
        self.start_ms + self.size() as i64 * self.hop_ms as i64
    }

    pub fn time_of(&self, index: usize) -> i64 {
        self.start_ms + index as i64 * self.hop_ms as i64
    }

    /// The hop that holds a song time; below 0 or at size and above when the
    /// time lies outside the envelope.
    pub fn index_at(&self, ms: f64) -> i64 {
        ((ms - self.start_ms as f64) / self.hop_ms as f64).floor() as i64
    }
}

/// Builds a [`SectionEnvelope`] from decoded sound fed to it in blocks of
/// any size, so a decoder can hand over each block as it comes out. Samples
/// are interleaved when there is more than one channel and are summed to
/// mono. A block may end in the middle of a frame; the rest of that frame is
/// taken from the next block. Each hop's onsets come from a Hann-windowed
/// FFT of the last [`ONSET_FFT_SIZE`] mono samples up to the hop's end
/// (zeros before the first sample).
pub struct EnvelopeBuilder {
    sample_rate: u32,
    channels: u32,
    start_ms: i64,
    hop_ms: i32,
    bass: Biquad,
    channel: u32,
    frame_sum: f64,
    // Frames taken so far, and the frame at which the current hop ends:
    // hop k covers frames k * rate * hop / 1000 up to (k + 1) * rate * hop / 1000.
    frame: i64,
    hop: i64,
    hop_end: i64,
    hop_start: i64,
    sum_squares: f64,
    low_squares: f64,
    // The last ONSET_FFT_SIZE mono samples, oldest at `ring_at`.
    ring: Vec<f64>,
    ring_at: usize,
    window: Vec<f64>,
    fft: RealFft,
    frame_in: Vec<f64>,
    magnitudes: Vec<f64>,
    compressed: Vec<f64>,
    last_compressed: Vec<f64>,
    // Bins 1 until low_end are the bass band, low_end until high_from the
    // middle band, high_from up to size / 2 the high band.
    low_end: usize,
    high_from: usize,
    db: Vec<f32>,
    low_db: Vec<f32>,
    onset: Vec<f32>,
    low_onset: Vec<f32>,
}

impl EnvelopeBuilder {
    pub fn new(sample_rate: u32, channels: u32, start_ms: i64) -> Self {
        Self::with_hop(sample_rate, channels, start_ms, ENVELOPE_HOP_MS)
    }

    pub fn with_hop(sample_rate: u32, channels: u32, start_ms: i64, hop_ms: i32) -> Self {
        assert!(sample_rate > 0, "sample_rate must be positive");
        assert!(channels > 0, "channels must be positive");
        assert!(hop_ms > 0, "hop_ms must be positive");
        let bins = ONSET_FFT_SIZE / 2 + 1;
        let bin_hz = sample_rate as f64 / ONSET_FFT_SIZE as f64;
        let band_edge = |hz: f64| bins.min(1.max((hz / bin_hz).ceil() as usize));
        let low_end = band_edge(BASS_CUTOFF_HZ);
        let high_from = low_end.max(band_edge(TREBLE_FROM_HZ));
        let mut builder = EnvelopeBuilder {
            sample_rate,
            channels,
            start_ms,
            hop_ms,
            bass: Biquad::low_pass(sample_rate, BASS_CUTOFF_HZ),
            channel: 0,
            frame_sum: 0.0,
            frame: 0,
            hop: 0,
            hop_end: 0,
            hop_start: 0,
            sum_squares: 0.0,
            low_squares: 0.0,
            ring: vec![0.0; ONSET_FFT_SIZE],
            ring_at: 0,
            window: (0..ONSET_FFT_SIZE)
                .map(|i| 0.5 - 0.5 * (2.0 * PI * i as f64 / ONSET_FFT_SIZE as f64).cos())
                .collect(),
            fft: RealFft::new(ONSET_FFT_SIZE),
            frame_in: vec![0.0; ONSET_FFT_SIZE],
            magnitudes: vec![0.0; bins],
            compressed: vec![0.0; bins],
            last_compressed: vec![0.0; bins],
            low_end,
            high_from,
            db: Vec::with_capacity(1024),
            low_db: Vec::with_capacity(1024),
            onset: Vec::with_capacity(1024),
            low_onset: Vec::with_capacity(1024),
        };
        builder.hop_end = builder.hop_end_of(0);
        builder
    }

    fn hop_end_of(&self, k: i64) -> i64 {
        (k + 1) * self.sample_rate as i64 * self.hop_ms as i64 / 1000
    }

    /// Samples between -1 and 1.
    pub fn push(&mut self, samples: &[f32]) {
        for &s in samples {
            self.frame_sum += s as f64;
            self.channel += 1;
            if self.channel == self.channels {
                self.take_frame();
            }
        }
    }

    /// 16-bit samples, as most decoders give them.
    pub fn push_i16(&mut self, samples: &[i16]) {
        for &s in samples {
            self.frame_sum += s as f64 / 32768.0;
            self.channel += 1;
            if self.channel == self.channels {
                self.take_frame();
            }
        }
    }

    fn take_frame(&mut self) {
        let x = self.frame_sum / self.channels as f64;
        self.frame_sum = 0.0;
        self.channel = 0;
        let y = self.bass.process(x);
        self.sum_squares += x * x;
        self.low_squares += y * y;
        self.ring[self.ring_at] = x;
        self.ring_at = if self.ring_at == ONSET_FFT_SIZE - 1 { 0 } else { self.ring_at + 1 };
        self.frame += 1;
        if self.frame == self.hop_end {
            self.close_hop();
        }
    }

    fn close_hop(&mut self) {
        let frames = (self.frame - self.hop_start) as f64;
        for i in 0..ONSET_FFT_SIZE {
            let at = self.ring_at + i;
            let at = if at >= ONSET_FFT_SIZE { at - ONSET_FFT_SIZE } else { at };
            self.frame_in[i] = self.ring[at] * self.window[i];
        }
        self.fft.magnitudes(&self.frame_in, &mut self.magnitudes);
        let scale = 4.0 / ONSET_FFT_SIZE as f64;
        for (c, m) in self.compressed.iter_mut().zip(&self.magnitudes) {
            *c = (1.0 + ONSET_COMPRESSION * m * scale).ln();
        }
        let first = self.db.is_empty();
        let bins = ONSET_FFT_SIZE / 2 + 1;
        let bass_flux = if first { 0.0 } else { self.flux(1, self.low_end) };
        let all_flux = if first {
            0.0
        } else {
            bass_flux + self.flux(self.low_end, self.high_from) + self.flux(self.high_from, bins)
        };
        std::mem::swap(&mut self.compressed, &mut self.last_compressed);

        self.db.push(db_of(self.sum_squares / frames));
        self.low_db.push(db_of(self.low_squares / frames));
        self.onset.push(all_flux as f32);
        self.low_onset.push(bass_flux as f32);
        self.sum_squares = 0.0;
        self.low_squares = 0.0;
        self.hop_start = self.frame;
        self.hop += 1;
        self.hop_end = self.hop_end_of(self.hop);
    }

    // The mean rise of the compressed magnitudes over bins from until to.
    fn flux(&self, from: usize, to: usize) -> f64 {
        if to <= from {
            return 0.0;
        }
        let mut sum = 0.0;
        for k in from..to {
            let rise = self.compressed[k] - self.last_compressed[k];
            if rise > 0.0 {
                sum += rise;
            }
        }
        sum / (to - from) as f64
    }

    /// How much sound the whole hops fed so far cover, in milliseconds.
    pub fn heard_ms(&self) -> i64 {
        self.db.len() as i64 * self.hop_ms as i64
    }

    /// The envelope of every whole hop fed so far; a last partial hop is
    /// left out.
    pub fn build(&self) -> SectionEnvelope {
        SectionEnvelope::new(
            self.start_ms,
            self.hop_ms,
            self.db.clone(),
            self.low_db.clone(),
            self.onset.clone(),
            self.low_onset.clone(),
        )
    }

    /// The envelope, without copying.
    pub fn into_envelope(self) -> SectionEnvelope {
        SectionEnvelope::new(self.start_ms, self.hop_ms, self.db, self.low_db, self.onset, self.low_onset)
    }
}

/// RMS level in dBFS from a mean square, never below [`SILENT_DB`].
pub fn db_of(mean_square: f64) -> f32 {
    if mean_square <= 1e-10 { SILENT_DB } else { (SILENT_DB as f64).max(10.0 * mean_square.log10()) as f32 }
}
