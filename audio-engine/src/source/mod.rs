//! Where a song's bytes come from: a file on this computer, or a server.

pub mod http;
pub mod trust;

use std::fs::File;
use std::path::Path;

use symphonia::core::io::MediaSource;

use crate::error::{ErrorKind, Failure};
use http::{HttpOptions, HttpSource};

/// An opened song, and what is known about its format before decoding.
pub struct Opened {
    pub source: Box<dyn MediaSource>,
    /// The file extension or type, for the format guess.
    pub hint: Option<String>,
    pub remote: bool,
    /// A jump far into the song is cheap: a file, or a stream whose server
    /// answers with ranges.
    pub seeks_cheaply: bool,
}

/// Whether a source string names a stream rather than a file.
pub fn is_remote(source: &str) -> bool {
    let lower = source.get(..8).unwrap_or(source).to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://")
}

/// Opens a file path, a `file://` address, or an HTTP(S) address.
pub fn open(source: &str, http: HttpOptions) -> Result<Opened, Failure> {
    if is_remote(source) {
        let stream = HttpSource::open(source, http)?;
        let hint = stream.content_type().and_then(extension_for_type).or_else(|| url_extension(source));
        let seeks_cheaply = stream.answered_with_range();
        return Ok(Opened { source: Box::new(stream), hint, remote: true, seeks_cheaply });
    }
    let path = source.strip_prefix("file://").unwrap_or(source);
    let file = File::open(path).map_err(|e| {
        let kind =
            if e.kind() == std::io::ErrorKind::NotFound { ErrorKind::NotFound } else { ErrorKind::Other };
        Failure::new(kind, format!("{path}: {e}"))
    })?;
    let hint = Path::new(path).extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase);
    Ok(Opened { source: Box::new(file), hint, remote: false, seeks_cheaply: true })
}

/// Whether a stream is made on the way (a server's transcode, asked for with
/// a `format` other than `raw`): it has no length and cannot jump by bytes,
/// so it is opened again at a time instead, with `timeOffset`.
pub fn seeks_by_time(source: &str) -> bool {
    if !is_remote(source) {
        return false;
    }
    let Some((_, query)) = source.split_once('?') else { return false };
    let query = query.split('#').next().unwrap_or(query);
    query.split('&').any(|pair| {
        let (name, value) = pair.split_once('=').unwrap_or((pair, ""));
        name.eq_ignore_ascii_case("format") && !value.is_empty() && !value.eq_ignore_ascii_case("raw")
    })
}

/// The address of a time-seeking stream starting `secs` in, in whole
/// seconds as servers take it, and where that lands.
pub fn at_time(source: &str, secs: f64) -> (String, f64) {
    let whole = secs.max(0.0).floor();
    if whole <= 0.0 {
        return (source.to_string(), 0.0);
    }
    let (base, fragment) = match source.split_once('#') {
        Some((b, f)) => (b, Some(f)),
        None => (source, None),
    };
    let kept: Vec<&str> = match base.split_once('?') {
        Some((_, q)) => q.split('&').filter(|p| !p.to_ascii_lowercase().starts_with("timeoffset=")).collect(),
        None => Vec::new(),
    };
    let path = base.split('?').next().unwrap_or(base);
    let mut query = kept.join("&");
    if !query.is_empty() {
        query.push('&');
    }
    query.push_str(&format!("timeOffset={}", whole as u64));
    let mut out = format!("{path}?{query}");
    if let Some(f) = fragment {
        out.push('#');
        out.push_str(f);
    }
    (out, whole)
}

// The extension a server's content type stands for.
fn extension_for_type(content_type: &str) -> Option<String> {
    let bare = content_type.split(';').next()?.trim().to_ascii_lowercase();
    let ext = match bare.as_str() {
        "audio/flac" | "audio/x-flac" => "flac",
        "audio/mpeg" | "audio/mp3" => "mp3",
        "audio/mp4" | "audio/x-m4a" | "audio/m4a" | "audio/aac" | "audio/x-aac" => "m4a",
        "audio/ogg" | "application/ogg" | "audio/vorbis" => "ogg",
        "audio/opus" => "opus",
        "audio/wav" | "audio/x-wav" | "audio/wave" => "wav",
        "audio/aiff" | "audio/x-aiff" => "aiff",
        _ => return None,
    };
    Some(ext.to_string())
}

// The extension in an address's path, ignoring the query.
fn url_extension(url: &str) -> Option<String> {
    let path = url.split(['?', '#']).next()?;
    let last = path.rsplit('/').next()?;
    let (_, ext) = last.rsplit_once('.')?;
    (!ext.is_empty() && ext.len() <= 5).then(|| ext.to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hints() {
        assert!(is_remote("HTTPS://x/y"));
        assert!(!is_remote("C:\\music\\a.flac"));
        assert_eq!(extension_for_type("audio/flac; charset=binary").as_deref(), Some("flac"));
        assert_eq!(url_extension("http://h/rest/stream.view?id=1&f=mp3").as_deref(), Some("view"));
        assert_eq!(url_extension("http://h/a/song.opus").as_deref(), Some("opus"));
    }

    #[test]
    fn a_transcode_seeks_by_time() {
        assert!(seeks_by_time("http://h/rest/stream?id=1&format=mp3&maxBitRate=192"));
        assert!(!seeks_by_time("http://h/rest/stream?id=1&format=raw"));
        assert!(!seeks_by_time("http://h/rest/stream?id=1"));
        assert!(!seeks_by_time("C:\\music\\a.flac"));
        assert_eq!(
            at_time("http://h/rest/stream?id=1&format=mp3", 61.7),
            ("http://h/rest/stream?id=1&format=mp3&timeOffset=61".to_string(), 61.0)
        );
        assert_eq!(
            at_time("http://h/rest/stream?id=1&format=mp3&timeOffset=61", 5.0).0,
            "http://h/rest/stream?id=1&format=mp3&timeOffset=5"
        );
        assert_eq!(at_time("http://h/rest/stream?id=1&format=mp3", 0.4).1, 0.0);
    }

    #[test]
    fn missing_file() {
        let err = open("Z:/no/such/file.flac", HttpOptions::default()).err().unwrap();
        assert_eq!(err.kind, ErrorKind::NotFound);
    }
}
