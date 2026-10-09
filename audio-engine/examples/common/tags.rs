//! A song file's genre and tempo tags, read through symphonia.

use std::fs::File;
use std::path::Path;

use symphonia::core::formats::FormatOptions;
use symphonia::core::formats::probe::Hint;
use symphonia::core::io::{MediaSourceStream, MediaSourceStreamOptions};
use symphonia::core::meta::{MetadataOptions, RawValue, StandardTag};

/// What the tags say about a song, when they say it.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Tags {
    pub genre: Option<String>,
    pub bpm: Option<f64>,
}

/// The tags of a local file; nothing for a stream or a file that will not open.
pub fn read(path: &str) -> Tags {
    let mut tags = Tags::default();
    let Ok(file) = File::open(path) else { return tags };
    let mut hint = Hint::new();
    if let Some(ext) = Path::new(path).extension().and_then(|e| e.to_str()) {
        hint.with_extension(ext);
    }
    let mss = MediaSourceStream::new(Box::new(file), MediaSourceStreamOptions::default());
    let Ok(mut format) = symphonia::default::get_probe().probe(
        &hint,
        mss,
        FormatOptions::default(),
        MetadataOptions::default(),
    ) else {
        return tags;
    };
    let mut metadata = format.metadata();
    loop {
        if let Some(rev) = metadata.current() {
            let tracks = rev.per_track.iter().flat_map(|t| t.metadata.tags.iter());
            for tag in rev.media.tags.iter().chain(tracks) {
                match &tag.std {
                    Some(StandardTag::Genre(g)) if tags.genre.is_none() => tags.genre = Some(g.to_string()),
                    Some(StandardTag::Bpm(b)) if tags.bpm.is_none() && *b > 0 => tags.bpm = Some(*b as f64),
                    _ => {
                        // Tempos written with a fraction, which have no standard tag.
                        let key = tag.raw.key.rsplit(':').next().unwrap_or(&tag.raw.key).to_ascii_lowercase();
                        if tags.bpm.is_none() && (key == "bpm" || key == "tbpm" || key == "tmpo") {
                            tags.bpm = number(&tag.raw.value).filter(|&b| b > 0.0);
                        }
                    }
                }
            }
        }
        if metadata.pop().is_none() {
            break;
        }
    }
    tags
}

fn number(value: &RawValue) -> Option<f64> {
    match value {
        RawValue::Float(f) => Some(*f),
        RawValue::SignedInt(i) => Some(*i as f64),
        RawValue::UnsignedInt(u) => Some(*u as f64),
        RawValue::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}
