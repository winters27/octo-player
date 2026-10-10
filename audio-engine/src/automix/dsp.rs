//! The filter section and the FFT the analysis runs on.

use std::f64::consts::PI;

/// The Q of a Butterworth second-order section: flat, no bump at the cutoff.
pub const BUTTERWORTH_Q: f64 = std::f64::consts::FRAC_1_SQRT_2;

/// A cutoff is kept below this share of the sample rate so the filter stays
/// stable at any rate.
pub const HIGHEST_CUTOFF_SHARE: f64 = 0.45;

/// A second-order filter section (the common "cookbook" low-pass and
/// high-pass), run one sample at a time. Coefficients are normalized so a0
/// is 1: `y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2`.
#[derive(Clone, Debug, PartialEq)]
pub struct Biquad {
    pub b0: f64,
    pub b1: f64,
    pub b2: f64,
    pub a1: f64,
    pub a2: f64,
    x1: f64,
    x2: f64,
    y1: f64,
    y2: f64,
}

impl Biquad {
    pub fn new(b0: f64, b1: f64, b2: f64, a1: f64, a2: f64) -> Self {
        Biquad { b0, b1, b2, a1, a2, x1: 0.0, x2: 0.0, y1: 0.0, y2: 0.0 }
    }

    pub fn low_pass(sample_rate: u32, hz: f64) -> Self {
        Self::low_pass_q(sample_rate, hz, BUTTERWORTH_Q)
    }

    pub fn high_pass(sample_rate: u32, hz: f64) -> Self {
        Self::high_pass_q(sample_rate, hz, BUTTERWORTH_Q)
    }

    pub fn low_pass_q(sample_rate: u32, hz: f64, q: f64) -> Self {
        let (c, alpha) = shape(sample_rate, hz, q);
        let a0 = 1.0 + alpha;
        Biquad::new(
            (1.0 - c) / 2.0 / a0,
            (1.0 - c) / a0,
            (1.0 - c) / 2.0 / a0,
            -2.0 * c / a0,
            (1.0 - alpha) / a0,
        )
    }

    pub fn high_pass_q(sample_rate: u32, hz: f64, q: f64) -> Self {
        let (c, alpha) = shape(sample_rate, hz, q);
        let a0 = 1.0 + alpha;
        Biquad::new(
            (1.0 + c) / 2.0 / a0,
            -(1.0 + c) / a0,
            (1.0 + c) / 2.0 / a0,
            -2.0 * c / a0,
            (1.0 - alpha) / a0,
        )
    }

    pub fn process(&mut self, x: f64) -> f64 {
        let y = self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2 - self.a1 * self.y1 - self.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }

    pub fn reset(&mut self) {
        self.x1 = 0.0;
        self.x2 = 0.0;
        self.y1 = 0.0;
        self.y2 = 0.0;
    }
}

// cos(w0) and alpha for a cutoff, with the cutoff held between 1 Hz and
// HIGHEST_CUTOFF_SHARE of the rate.
fn shape(sample_rate: u32, hz: f64, q: f64) -> (f64, f64) {
    let cutoff = hz.clamp(1.0, sample_rate as f64 * HIGHEST_CUTOFF_SHARE);
    let w0 = 2.0 * PI * cutoff / sample_rate as f64;
    (w0.cos(), w0.sin() / (2.0 * q))
}

/// A real-input FFT of a power-of-two size, done as a complex FFT of half
/// the size. Only the magnitudes of bins 0 to size / 2 are given.
pub struct RealFft {
    half: usize,
    re: Vec<f64>,
    im: Vec<f64>,
    cos_half: Vec<f64>,
    sin_half: Vec<f64>,
    cos_full: Vec<f64>,
    sin_full: Vec<f64>,
    reversed: Vec<usize>,
}

impl RealFft {
    pub fn new(size: usize) -> Self {
        assert!(size >= 4 && size.is_power_of_two(), "size must be a power of two");
        let half = size / 2;
        let mut bits = 0;
        while 1 << bits < half {
            bits += 1;
        }
        let reversed = (0..half)
            .map(|i| {
                let mut r = 0;
                for b in 0..bits {
                    if (i >> b) & 1 == 1 {
                        r |= 1 << (bits - 1 - b);
                    }
                }
                r
            })
            .collect();
        let turn = |n: usize, of: usize| 2.0 * PI * n as f64 / of as f64;
        RealFft {
            half,
            re: vec![0.0; half],
            im: vec![0.0; half],
            cos_half: (0..half / 2).map(|i| turn(i, half).cos()).collect(),
            sin_half: (0..half / 2).map(|i| turn(i, half).sin()).collect(),
            cos_full: (0..=half).map(|i| turn(i, size).cos()).collect(),
            sin_full: (0..=half).map(|i| turn(i, size).sin()).collect(),
            reversed,
        }
    }

    /// `out` gets |X\[k\]| for k = 0 ..= size / 2.
    pub fn magnitudes(&mut self, input: &[f64], out: &mut [f64]) {
        let half = self.half;
        let (re, im) = (&mut self.re, &mut self.im);
        for i in 0..half {
            let j = self.reversed[i];
            re[j] = input[2 * i];
            im[j] = input[2 * i + 1];
        }
        let mut length = 2;
        while length <= half {
            let step = half / length;
            let mid = length / 2;
            for j in 0..mid {
                let wr = self.cos_half[j * step];
                let wi = -self.sin_half[j * step];
                let mut a = j;
                while a < half {
                    let b = a + mid;
                    let (br, bi) = (re[b], im[b]);
                    let tr = br * wr - bi * wi;
                    let ti = br * wi + bi * wr;
                    let (ar, ai) = (re[a], im[a]);
                    re[b] = ar - tr;
                    im[b] = ai - ti;
                    re[a] = ar + tr;
                    im[a] = ai + ti;
                    a += length;
                }
            }
            length *= 2;
        }
        for (k, slot) in out.iter_mut().enumerate().take(half + 1) {
            let front = if k == half { 0 } else { k };
            let back = if k == 0 { 0 } else { half - k };
            let (a, b, c, d) = (re[front], im[front], re[back], im[back]);
            let even_re = (a + c) / 2.0;
            let even_im = (b - d) / 2.0;
            let odd_re = (b + d) / 2.0;
            let odd_im = -(a - c) / 2.0;
            let (wr, ws) = (self.cos_full[k], self.sin_full[k]);
            let xr = even_re + wr * odd_re + ws * odd_im;
            let xi = even_im + wr * odd_im - ws * odd_re;
            *slot = (xr * xr + xi * xi).sqrt();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_fft_matches_a_plain_dft() {
        let size = 64;
        let input: Vec<f64> = (0..size).map(|n| ((n * 7 % 13) as f64 - 6.0) / 7.0).collect();
        let mut fft = RealFft::new(size);
        let mut out = vec![0.0; size / 2 + 1];
        fft.magnitudes(&input, &mut out);
        for (k, got) in out.iter().enumerate() {
            let (mut re, mut im) = (0.0, 0.0);
            for (n, x) in input.iter().enumerate() {
                let w = 2.0 * PI * (k * n) as f64 / size as f64;
                re += x * w.cos();
                im -= x * w.sin();
            }
            assert!((got - (re * re + im * im).sqrt()).abs() < 1e-9, "bin {k}");
        }
    }

    #[test]
    fn the_cookbook_low_pass_passes_dc() {
        let f = Biquad::low_pass(48_000, 1_000.0);
        assert!(((f.b0 + f.b1 + f.b2) / (1.0 + f.a1 + f.a2) - 1.0).abs() < 1e-12);
        let h = Biquad::high_pass(48_000, 1_000.0);
        assert!((h.b0 + h.b1 + h.b2).abs() < 1e-12);
    }
}
