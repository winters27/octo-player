//! Finds the tempo of every song in a folder the way the automix analysis
//! does, and compares it with the tempo in the song's tags.
//!
//! cargo run --release --example bpm_check -- <folder> [--limit <n>] [--use-tag] [--truth <file>]
//!
//! Each song is decoded whole. The tempo is found three ways: over the
//! whole song (as the live tap hears it), over its first 30 s (the next
//! song's start) and over its last 60 s (the playing song's end). A tempo
//! agrees with the tag when it is within 2 % of it after halving or
//! doubling. The summary gives that share for songs with a tag, over all of
//! them and over those whose tempo was trusted. --use-tag lets the tag pick
//! the octave, as the player does when a song has one. --truth reads
//! published tempos from a tab-separated file with a header holding `file`
//! (the song's file name) and `bpm` columns; a published tempo stands in
//! for the tag. The summary also counts the songs whose shown tempo (see
//! `Tempo::display_bpm`) is the published one, octave and all.

#[path = "common/tags.rs"]
mod tags;

use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;

use octo_audio::automix::{
    self, EnvelopeBuilder, SectionAnalysis, SectionEnvelope, Tempo, analyze_head, analyze_tail,
    fold_tempo_ratio,
};
use octo_audio::scout::{PcmSink, SectionPart, scout_section};
use octo_audio::source::http::HttpOptions;

const AUDIO: [&str; 10] = ["flac", "mp3", "m4a", "mp4", "aac", "ogg", "opus", "wav", "aif", "aiff"];

// Within this share of the tag after octave folding counts as agreeing.
const AGREES_WITHIN: f64 = 0.02;

fn fail(message: &str) -> ! {
    eprintln!("{message}");
    std::process::exit(2);
}

fn songs_in(dir: &Path, into: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let mut entries: Vec<_> = entries.filter_map(Result::ok).map(|e| e.path()).collect();
    entries.sort();
    for path in entries {
        if path.is_dir() {
            songs_in(&path, into);
        } else if path
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| AUDIO.contains(&e.to_ascii_lowercase().as_str()))
        {
            into.push(path);
        }
    }
}

// The whole song's envelope, as mono at the file's rate.
#[derive(Default)]
struct Whole {
    builder: Option<EnvelopeBuilder>,
}

impl PcmSink for Whole {
    fn begin(&mut self, _start_secs: f64, sample_rate: u32) {
        self.builder = Some(EnvelopeBuilder::new(sample_rate, 1, 0));
    }

    fn pcm(&mut self, mono: &[f32]) {
        if let Some(b) = &mut self.builder {
            b.push(mono);
        }
    }
}

// Hops from..to of an envelope, as an envelope of its own.
fn slice(e: &SectionEnvelope, from: usize, to: usize) -> SectionEnvelope {
    SectionEnvelope::new(
        e.time_of(from),
        e.hop_ms,
        e.db[from..to].to_vec(),
        e.low_db[from..to].to_vec(),
        e.onset[from..to].to_vec(),
        e.low_onset[from..to].to_vec(),
    )
}

// Published tempos by file name.
fn truth_from(path: &Path) -> std::collections::HashMap<String, f64> {
    let text = std::fs::read_to_string(path).unwrap_or_else(|e| fail(&format!("{}: {e}", path.display())));
    let mut lines = text.lines();
    let header: Vec<&str> = lines.next().unwrap_or_default().split('\t').collect();
    let column = |name: &str| {
        header.iter().position(|h| h.trim() == name).unwrap_or_else(|| fail(&format!("no {name} column")))
    };
    let (file, bpm) = (column("file"), column("bpm"));
    lines
        .filter_map(|l| {
            let cells: Vec<&str> = l.split('\t').collect();
            let value = cells.get(bpm)?.trim().parse::<f64>().ok()?;
            Some((cells.get(file)?.trim().to_string(), value))
        })
        .collect()
}

fn agrees(tempo: Option<Tempo>, tag: f64) -> bool {
    tempo.is_some_and(|t| (fold_tempo_ratio(t.bpm / tag) - 1.0).abs() <= AGREES_WITHIN)
}

fn show(tempo: Option<Tempo>) -> String {
    match tempo {
        None => "      -        ".to_string(),
        Some(t) => format!(
            "{:6.1}{} {:4.2}{}{:3.0}%",
            t.display_bpm(),
            if t.confident() { '*' } else { ' ' },
            t.confidence,
            if t.steady { '/' } else { '~' },
            t.consistency * 100.0
        ),
    }
}

#[derive(Default)]
struct Tally {
    tagged: usize,
    agree: usize,
    trusted: usize,
    trusted_agree: usize,
    shown: usize,
}

impl Tally {
    fn add(&mut self, tempo: Option<Tempo>, tag: f64) {
        self.tagged += 1;
        let ok = agrees(tempo, tag);
        self.agree += ok as usize;
        self.shown += tempo.is_some_and(|t| (t.display_bpm() / tag - 1.0).abs() <= AGREES_WITHIN) as usize;
        if tempo.is_some_and(|t| t.confident()) {
            self.trusted += 1;
            self.trusted_agree += ok as usize;
        }
    }

    fn line(&self, name: &str) -> String {
        let share = |a: usize, of: usize| if of == 0 { 0.0 } else { a as f64 * 100.0 / of as f64 };
        format!(
            "{name:<6} {}/{} agree ({:.0} %), {} shown in the same octave; trusted {}/{} ({:.0} %), of which {} agree ({:.0} %)",
            self.agree,
            self.tagged,
            share(self.agree, self.tagged),
            self.shown,
            self.trusted,
            self.tagged,
            share(self.trusted, self.tagged),
            self.trusted_agree,
            share(self.trusted_agree, self.trusted)
        )
    }
}

fn main() {
    let mut args = std::env::args().skip(1);
    let mut folder = None;
    let mut limit = usize::MAX;
    let mut use_tag = false;
    let mut truth = std::collections::HashMap::new();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--limit" => {
                limit =
                    args.next().and_then(|v| v.parse().ok()).unwrap_or_else(|| fail("--limit needs a number"))
            }
            "--use-tag" => use_tag = true,
            "--truth" => {
                let path = args.next().unwrap_or_else(|| fail("--truth needs a file"));
                truth = truth_from(Path::new(&path));
            }
            other if other.starts_with("--") => fail(&format!("unknown option {other}")),
            other => folder = Some(PathBuf::from(other)),
        }
    }
    let Some(folder) = folder else {
        fail("usage: bpm_check <folder> [--limit <n>] [--use-tag] [--truth <file>]")
    };
    let mut songs = Vec::new();
    songs_in(&folder, &mut songs);
    songs.truncate(limit);
    if songs.is_empty() {
        fail(&format!("no songs in {}", folder.display()));
    }

    println!("{:>6}  {:<15}  {:<15}  {:<15}  song", "tag", "whole", "first 30 s", "last 60 s");
    println!(
        "{:>6}  (BPM as shown, * trusted, period score, / steady or ~ not, share of beats on an onset)",
        ""
    );
    let (mut whole_tally, mut head_tally, mut tail_tally) =
        (Tally::default(), Tally::default(), Tally::default());
    let never = AtomicBool::new(false);
    for path in &songs {
        let source = path.to_string_lossy().to_string();
        let file_name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        let tag = truth.get(&file_name).copied().or(tags::read(&source).bpm);
        let mut whole = Whole::default();
        let part = SectionPart::Head { secs: 24.0 * 3_600.0 };
        if let Err(f) = scout_section(&source, HttpOptions::default(), part, &never, &mut whole) {
            println!("{:>6}  could not decode: {f}  {}", "", path.display());
            continue;
        }
        let Some(builder) = whole.builder else { continue };
        let envelope = builder.into_envelope();
        let hint = if use_tag { tag } else { None };
        let size = envelope.size();
        let hops = |secs: f64| ((secs * 1_000.0) as usize / envelope.hop_ms as usize).min(size);
        let whole_tempo =
            SectionAnalysis::of(envelope.clone(), hint, None, automix::features::HEAD_BODY_PERCENTILE)
                .features
                .tempo;
        let head_tempo = analyze_head(slice(&envelope, 0, hops(automix::HEAD_SECS)), hint).features.tempo;
        let tail_from = size - hops(automix::TAIL_SECS);
        let tail_tempo = analyze_tail(slice(&envelope, tail_from, size), hint, None).features.tempo;
        let tag_text = tag.map_or("-".to_string(), |t| format!("{t:.1}"));
        let name = path.strip_prefix(&folder).unwrap_or(path).display();
        let mark = match tag {
            Some(t) if agrees(whole_tempo, t) => "",
            Some(_) => "  (off)",
            None => "",
        };
        println!(
            "{tag_text:>6}  {}  {}  {}  {name}{mark}",
            show(whole_tempo),
            show(head_tempo),
            show(tail_tempo)
        );
        if let Some(t) = tag {
            whole_tally.add(whole_tempo, t);
            head_tally.add(head_tempo, t);
            tail_tally.add(tail_tempo, t);
        }
    }
    println!();
    println!(
        "{} songs, {} with a tag or published tempo; within 2 % of it after halving or doubling:",
        songs.len(),
        whole_tally.tagged
    );
    println!("{}", whole_tally.line("whole"));
    println!("{}", head_tally.line("first"));
    println!("{}", tail_tally.line("last"));
}
