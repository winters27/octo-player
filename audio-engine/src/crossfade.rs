//! When and how two songs blend, with the Android app's rules
//! (`Crossfade.kt`).

use std::f32::consts::FRAC_PI_2;

/// The longest crossfade the settings allow.
pub const LONGEST_FADE_MS: u32 = 12_000;

/// The shortest blend worth doing; anything shorter just sounds like a cut.
pub const SHORTEST_FADE_MS: u64 = 500;

/// What the crossfade needs to know about a song.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct FadeSong {
    pub album_id: Option<String>,
    pub album_order: Option<i32>,
    pub duration_ms: u64,
}

/// How long the next song should fade in over the end of this one, or 0 for
/// no crossfade. The fade shrinks to half of either song, so a short song is
/// never mostly fade.
pub fn crossfade_length(
    current: &FadeSong,
    next: Option<&FadeSong>,
    fade_ms: u64,
    repeat_one: bool,
    stop_at_end_of_song: bool,
) -> u64 {
    let Some(next) = next else { return 0 };
    if fade_ms == 0 || repeat_one || stop_at_end_of_song {
        return 0;
    }
    if current.duration_ms == 0 || next.duration_ms == 0 {
        return 0;
    }
    // An album played in order stays gapless: songs meant to run into each
    // other should not be blended.
    if let (Some(a), Some(b), Some(order), Some(next_order)) =
        (&current.album_id, &next.album_id, current.album_order, next.album_order)
        && a == b
        && next_order == order + 1
    {
        return 0;
    }
    let length = fade_ms.min(current.duration_ms.min(next.duration_ms) / 2);
    if length < SHORTEST_FADE_MS { 0 } else { length }
}

/// Equal-power fade: the two volumes' squares always add up to one, so the
/// blend never dips or swells in loudness. `progress` runs from 0 to 1.
pub fn fade_out_volume(progress: f32) -> f32 {
    (progress.clamp(0.0, 1.0) * FRAC_PI_2).cos()
}

pub fn fade_in_volume(progress: f32) -> f32 {
    (progress.clamp(0.0, 1.0) * FRAC_PI_2).sin()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn song(album: &str, order: i32, ms: u64) -> FadeSong {
        FadeSong { album_id: Some(album.into()), album_order: Some(order), duration_ms: ms }
    }

    #[test]
    fn equal_power_curve_keeps_the_total_level() {
        for i in 0..=100 {
            let p = i as f32 / 100.0;
            let (a, b) = (fade_out_volume(p), fade_in_volume(p));
            assert!((a * a + b * b - 1.0).abs() < 1e-6);
        }
        assert_eq!(fade_out_volume(0.0), 1.0);
        assert!(fade_out_volume(1.0).abs() < 1e-6);
        assert!((fade_in_volume(0.5) - std::f32::consts::FRAC_1_SQRT_2).abs() < 1e-6);
        assert_eq!(fade_in_volume(-1.0), 0.0);
        assert_eq!(fade_in_volume(2.0), 1.0);
    }

    #[test]
    fn albums_in_order_stay_gapless() {
        let a1 = song("a", 1, 200_000);
        let a2 = song("a", 2, 200_000);
        let a4 = song("a", 4, 200_000);
        let b1 = song("b", 1, 200_000);
        assert_eq!(crossfade_length(&a1, Some(&a2), 6_000, false, false), 0);
        assert_eq!(crossfade_length(&a1, Some(&a4), 6_000, false, false), 6_000);
        assert_eq!(crossfade_length(&a1, Some(&b1), 6_000, false, false), 6_000);
    }

    #[test]
    fn fade_shrinks_for_short_songs_and_turns_off() {
        let long = song("a", 1, 200_000);
        let short = song("b", 1, 5_000);
        assert_eq!(crossfade_length(&long, Some(&short), 6_000, false, false), 2_500);
        let tiny = song("c", 1, 800);
        assert_eq!(crossfade_length(&long, Some(&tiny), 6_000, false, false), 0);
        assert_eq!(crossfade_length(&long, None, 6_000, false, false), 0);
        assert_eq!(crossfade_length(&long, Some(&short), 6_000, true, false), 0);
        assert_eq!(crossfade_length(&long, Some(&short), 6_000, false, true), 0);
        assert_eq!(crossfade_length(&long, Some(&short), 0, false, false), 0);
        let unknown = FadeSong { duration_ms: 0, ..long.clone() };
        assert_eq!(crossfade_length(&unknown, Some(&short), 6_000, false, false), 0);
    }
}
