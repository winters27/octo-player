//! Gain curves, filter cutoffs and the tempo-matched rate through a blend.

use std::f64::consts::PI;

/// The curve weight of each kind of transition: how much of the S-shaped
/// curve is mixed into the equal-power one. Higher swaps the songs faster
/// around the middle.
pub const CUT_CURVE: f64 = 0.75;
pub const LIFT_CURVE: f64 = 0.55;
pub const BLEND_CURVE: f64 = 0.2;
pub const CROSSFADE_CURVE: f64 = 0.4;

/// How deep the filter sweeps go, 0 for none and 1 for the full range.
pub const FILTER_STRENGTH: f64 = 0.7;

/// The outgoing song's low-pass runs from `OPEN_LOW_PASS_HZ` down toward
/// `LOW_PASS_FLOOR_HZ`.
pub const OPEN_LOW_PASS_HZ: f64 = 18_000.0;
pub const LOW_PASS_FLOOR_HZ: f64 = 450.0;

/// The outgoing song's bass cut rises from `OPEN_HIGH_PASS_HZ` toward
/// `BASS_SWAP_HZ` by the middle of the blend.
pub const OPEN_HIGH_PASS_HZ: f64 = 10.0;
pub const BASS_SWAP_HZ: f64 = 300.0;
pub const BASS_SWAP_AT: f64 = 0.5;

/// The incoming song's high-pass starts at `INCOMING_HIGH_PASS_HZ` and opens
/// to `INCOMING_OPEN_HZ` by `INCOMING_OPEN_AT` through the blend.
pub const INCOMING_HIGH_PASS_HZ: f64 = 675.0;
pub const INCOMING_OPEN_HZ: f64 = 20.0;
pub const INCOMING_OPEN_AT: f64 = 0.6;

/// After the blend a tempo-matched incoming song eases back to its own
/// speed over this long.
pub const BEAT_MATCH_SETTLE_MS: f64 = 5_000.0;

fn sigmoid12(t: f64) -> f64 {
    1.0 / (1.0 + (-12.0 * (t - 0.5)).exp())
}

/// The S curve scaled to run exactly from 0 at the start to 1 at the end.
pub fn s_curve(t: f64) -> f64 {
    let (low, high) = (sigmoid12(0.0), sigmoid12(1.0));
    (sigmoid12(t.clamp(0.0, 1.0)) - low) / (high - low)
}

/// The outgoing song's volume at `t` through the blend (0 to 1): an
/// equal-power fade mixed with an S curve by weight `k`.
pub fn automix_out_gain(t: f64, k: f64) -> f64 {
    let p = t.clamp(0.0, 1.0);
    ((p * PI / 2.0).cos() * (1.0 - k) + (1.0 - s_curve(p)) * k).clamp(0.0, 1.0)
}

/// The incoming song's volume: whatever keeps the two volumes' squares at one.
pub fn automix_in_gain(t: f64, k: f64) -> f64 {
    let out = automix_out_gain(t, k);
    (1.0 - out * out).max(0.0).sqrt()
}

// Moves from `from` to `to` on a log scale as u goes from 0 to 1.
fn log_between(from: f64, to: f64, u: f64) -> f64 {
    from * (to / from).powf(u.clamp(0.0, 1.0))
}

/// The outgoing song's low-pass cutoff in Hz at `t`, falling smoothly.
pub fn outgoing_low_pass_hz(t: f64, strength: f64) -> f64 {
    let end = OPEN_LOW_PASS_HZ * (LOW_PASS_FLOOR_HZ / OPEN_LOW_PASS_HZ).powf(strength.clamp(0.0, 1.0));
    log_between(OPEN_LOW_PASS_HZ, end, t)
}

/// The same low-pass moving in steps on the beat: it holds each beat's
/// cutoff until `glide` before the next beat, then glides to the next one.
/// `beats` are the beats' places through the blend (0 to 1, rising) and
/// `glide` is in the same units.
pub fn stepped_outgoing_low_pass_hz(t: f64, strength: f64, beats: &[f64], glide: f64) -> f64 {
    let p = t.clamp(0.0, 1.0);
    let mut from = 0.0;
    let mut to = 1.0;
    for &beat in beats {
        if beat <= 0.0 || beat >= 1.0 {
            continue;
        }
        if beat <= p {
            from = beat;
        } else {
            to = beat;
            break;
        }
    }
    let g = glide.max(0.0).min(to - from);
    let glide_start = to - g;
    let at = if p < glide_start || g <= 0.0 { from } else { from + (p - glide_start) / g * (to - from) };
    outgoing_low_pass_hz(at, strength)
}

/// The outgoing song's bass cut in Hz at `t`: rises to its top by the
/// middle of the blend and holds there.
pub fn outgoing_high_pass_hz(t: f64, strength: f64) -> f64 {
    let top = OPEN_HIGH_PASS_HZ.max(BASS_SWAP_HZ * strength.clamp(0.0, 1.0));
    log_between(OPEN_HIGH_PASS_HZ, top, t / BASS_SWAP_AT)
}

/// The incoming song's high-pass cutoff in Hz at `t`: thin at first, fully
/// open by `INCOMING_OPEN_AT`.
pub fn incoming_high_pass_hz(t: f64, strength: f64) -> f64 {
    let start = INCOMING_OPEN_HZ.max(INCOMING_HIGH_PASS_HZ * strength.clamp(0.0, 1.0));
    log_between(start, INCOMING_OPEN_HZ, t / INCOMING_OPEN_AT)
}

/// The incoming song's playback rate `since_entry_ms` after it came in:
/// `rate` through the blend, then eased back to 1 over
/// [`BEAT_MATCH_SETTLE_MS`].
pub fn beat_match_rate_at(rate: f64, since_entry_ms: f64, overlap_ms: f64) -> f64 {
    if since_entry_ms <= overlap_ms {
        return rate;
    }
    let u = (since_entry_ms - overlap_ms) / BEAT_MATCH_SETTLE_MS;
    if u >= 1.0 {
        return 1.0;
    }
    rate + (1.0 - rate) * (1.0 - (PI * u).cos()) / 2.0
}
