//! A chain of equalizer filters, as in the Android app's `Biquad.kt`.

use super::model::Coefficients;

/// State this small is set to zero, so a fading tail never slows the
/// processor down with tiny numbers.
const TINY: f64 = 1e-25;

/// A chain of filters run one after another over interleaved audio. Each
/// channel keeps its own filter memory, in doubles, so deep bass and quiet
/// passages stay clean. The filters use the transposed direct form II.
#[derive(Clone, Debug)]
pub struct FilterBank {
    channels: usize,
    coefficients: Vec<Coefficients>,
    // Two memory slots per filter per channel.
    z1: Vec<f64>,
    z2: Vec<f64>,
}

impl FilterBank {
    pub fn new(channels: usize, coefficients: Vec<Coefficients>) -> Self {
        let slots = coefficients.len() * channels;
        Self { channels, coefficients, z1: vec![0.0; slots], z2: vec![0.0; slots] }
    }

    pub fn coefficients(&self) -> &[Coefficients] {
        &self.coefficients
    }

    pub fn is_empty(&self) -> bool {
        self.coefficients.is_empty()
    }

    /// Replaces filter `index`'s coefficients, keeping its memory, so a
    /// curve can move a little at a time while sound runs through it.
    pub fn set(&mut self, index: usize, coefficients: Coefficients) {
        self.coefficients[index] = coefficients;
    }

    /// Filters `frames` frames of `samples` in place.
    pub fn process(&mut self, samples: &mut [f32], frames: usize) {
        let count = self.coefficients.len();
        if count == 0 {
            return;
        }
        let channels = self.channels;
        for frame in samples[..frames * channels].chunks_exact_mut(channels) {
            for (ch, sample) in frame.iter_mut().enumerate() {
                let mut x = *sample as f64;
                let base = ch * count;
                for (k, c) in self.coefficients.iter().enumerate() {
                    let slot = base + k;
                    let y = c.b0 * x + self.z1[slot];
                    self.z1[slot] = c.b1 * x - c.a1 * y + self.z2[slot];
                    self.z2[slot] = c.b2 * x - c.a2 * y;
                    x = y;
                }
                *sample = x as f32;
            }
        }
        for s in self.z1.iter_mut().chain(self.z2.iter_mut()) {
            if s.abs() < TINY {
                *s = 0.0;
            }
        }
    }

    /// Carries on from another bank's memory when the filters line up, so a
    /// small change to the curve does not restart them from silence.
    pub fn continue_from(&mut self, other: &FilterBank) {
        if other.coefficients.len() != self.coefficients.len() || other.channels != self.channels {
            return;
        }
        self.z1.copy_from_slice(&other.z1);
        self.z2.copy_from_slice(&other.z2);
    }

    pub fn clear(&mut self) {
        self.z1.fill(0.0);
        self.z2.fill(0.0);
    }
}
