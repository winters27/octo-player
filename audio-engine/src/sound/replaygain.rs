//! ReplayGain: reading a song's loudness tags and turning them into a
//! level, with the same rules as the Android app's `ReplayGain.kt`.

use super::model::{ReplayGainMode, ReplayGainSettings};

/// A song's loudness tags: how far to turn it up or down to reach the
/// common level, in decibels, and its highest sample, where 1 is full
/// scale. Any of them can be missing.
#[derive(Clone, Copy, Debug, Default, PartialEq, uniffi::Record)]
pub struct ReplayGainInfo {
    pub track_gain: Option<f32>,
    pub track_peak: Option<f32>,
    pub album_gain: Option<f32>,
    pub album_peak: Option<f32>,
}

impl ReplayGainInfo {
    pub fn has_gain(&self) -> bool {
        self.track_gain.is_some() || self.album_gain.is_some()
    }
}

/// What the level needs to know about a song: its tags, and whether it
/// follows a song from the same album.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SongLoudness {
    pub replay_gain: Option<ReplayGainInfo>,
    pub follows_same_album: bool,
}

/// The newer loudness tags aim 5 dB quieter than the older ones.
const R128_TO_REPLAY_GAIN_DB: f32 = 5.0;

/// Gains past this are a broken tag, not a real song.
const LARGEST_GAIN_DB: f32 = 64.0;

/// Reads the tags from name and value pairs, names in any case. The
/// standard tags win over the newer R128 ones when a song has both.
pub fn from_tags<'a>(tags: impl IntoIterator<Item = (&'a str, &'a str)>) -> Option<ReplayGainInfo> {
    let mut info = ReplayGainInfo::default();
    let mut r128_track = None;
    let mut r128_album = None;
    for (name, value) in tags {
        match name.trim().to_ascii_uppercase().as_str() {
            "REPLAYGAIN_TRACK_GAIN" => info.track_gain = info.track_gain.or_else(|| parse_gain(value)),
            "REPLAYGAIN_TRACK_PEAK" => info.track_peak = info.track_peak.or_else(|| parse_peak(value)),
            "REPLAYGAIN_ALBUM_GAIN" => info.album_gain = info.album_gain.or_else(|| parse_gain(value)),
            "REPLAYGAIN_ALBUM_PEAK" => info.album_peak = info.album_peak.or_else(|| parse_peak(value)),
            "R128_TRACK_GAIN" => r128_track = r128_track.or_else(|| parse_r128(value)),
            "R128_ALBUM_GAIN" => r128_album = r128_album.or_else(|| parse_r128(value)),
            _ => {}
        }
    }
    info.track_gain = info.track_gain.or(r128_track);
    info.album_gain = info.album_gain.or(r128_album);
    (info != ReplayGainInfo::default()).then_some(info)
}

/// A gain like "-6.54 dB", "+2.1dB" or "-6,54 dB".
pub fn parse_gain(value: &str) -> Option<f32> {
    let text = value.trim();
    let bare = if text.len() >= 2 && text[text.len() - 2..].eq_ignore_ascii_case("db") {
        &text[..text.len() - 2]
    } else {
        text
    };
    number(bare.trim()).filter(|g| g.abs() <= LARGEST_GAIN_DB)
}

/// A peak like "0.988251", where 1 is full scale.
pub fn parse_peak(value: &str) -> Option<f32> {
    number(value.trim()).filter(|p| *p > 0.0)
}

/// An R128 gain: whole 256ths of a decibel, measured against a level
/// 5 dB quieter than the standard tags use.
pub fn parse_r128(value: &str) -> Option<f32> {
    let whole: i32 = value.trim().parse().ok()?;
    Some(whole as f32 / 256.0 + R128_TO_REPLAY_GAIN_DB).filter(|g| g.abs() <= LARGEST_GAIN_DB)
}

fn number(text: &str) -> Option<f32> {
    let text = text.strip_prefix('+').unwrap_or(text).replace(',', ".");
    text.parse::<f32>().ok().filter(|v| v.is_finite())
}

/// How much to scale a song, as a multiplier. The gain is the chosen tag
/// plus the ReplayGain preamp, or the fallback for a song with no tags.
/// Album and track each fall back to the other when missing. Smart uses the
/// album gain only while songs from one album follow each other. With
/// clipping prevented, the song is never raised past its own peak.
pub fn replay_gain_factor(settings: &ReplayGainSettings, song: Option<&SongLoudness>) -> f32 {
    let use_album = match settings.mode {
        ReplayGainMode::Off => return 1.0,
        ReplayGainMode::Track => false,
        ReplayGainMode::Album => true,
        ReplayGainMode::Smart => song.is_some_and(|s| s.follows_same_album),
    };
    let info = song.and_then(|s| s.replay_gain);
    let (gain, peak) = match info {
        Some(i) if i.has_gain() => {
            if use_album && i.album_gain.is_some() {
                (i.album_gain, i.album_peak.or(i.track_peak))
            } else if use_album || i.track_gain.is_some() {
                (i.track_gain, i.track_peak.or(i.album_peak))
            } else {
                (i.album_gain, i.album_peak.or(i.track_peak))
            }
        }
        _ => (None, None),
    };
    let db = match gain {
        Some(g) => g + settings.preamp_db,
        None => settings.fallback_db,
    };
    let factor = 10f32.powf(db / 20.0);
    match peak {
        Some(p) if settings.prevent_clipping && p > 0.0 => factor.min(1.0 / p),
        _ => factor,
    }
}

/// The loudness a song plays at: its own tags when the sound carries them,
/// otherwise the values its source keeps, such as a server's for a stream
/// made smaller on the way, which loses its tags. Peaks alone say nothing
/// about how loud to play, so stored gains win over a stream with only those.
pub fn choose_replay_gain(
    stream: Option<ReplayGainInfo>,
    stored: Option<ReplayGainInfo>,
) -> Option<ReplayGainInfo> {
    if stream.is_some_and(|s| s.has_gain()) {
        stream
    } else if stored.is_some_and(|s| s.has_gain()) {
        stored
    } else {
        stream
    }
}

/// Loudness values a source kept, checked the way tags are: a gain past the
/// limit or a peak that is not above zero is left out. Nothing without a gain.
pub fn stored_replay_gain(values: &ReplayGainInfo) -> Option<ReplayGainInfo> {
    let gain = |v: Option<f32>| v.filter(|g| g.is_finite() && g.abs() <= LARGEST_GAIN_DB);
    let peak = |v: Option<f32>| v.filter(|p| p.is_finite() && *p > 0.0);
    let info = ReplayGainInfo {
        track_gain: gain(values.track_gain),
        track_peak: peak(values.track_peak),
        album_gain: gain(values.album_gain),
        album_peak: peak(values.album_peak),
    };
    info.has_gain().then_some(info)
}
