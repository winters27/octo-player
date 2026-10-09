//! Decodes a stretch of a queued song away from playback, through the same
//! files and streams playback uses, and hands its sound to an analysis as
//! mono floats. Runs on its own thread; playback never waits on it.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::automix::{SectionAnalysis, SectionAnalyzer, SectionKind};
use crate::decode::Decoder;
use crate::error::{ErrorKind, Failure};
use crate::source::{self, http::HttpOptions};

/// Which part of a song to decode.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum SectionPart {
    /// The first `secs` seconds.
    Head { secs: f64 },
    /// The last `secs` seconds. `len_secs` is the song's length when it is
    /// known already; otherwise the file's own length is used.
    Tail { secs: f64, len_secs: Option<f64> },
}

/// Takes a decoded section, a block at a time.
pub trait PcmSink {
    /// Called once, before any sound: where the section starts in the song
    /// and the rate of the sound to come.
    fn begin(&mut self, start_secs: f64, sample_rate: u32);
    /// The next frames, as the mean of the channels.
    fn pcm(&mut self, mono: &[f32]);
}

/// Decodes `part` of the song at `source` into `sink`. Stops early when
/// `cancel` is set. Fails when the song will not open, and for a tail when
/// the length is not known or the stream cannot jump ahead without being
/// read up to there (a server that ignores ranges).
pub fn scout_section(
    source: &str,
    http: HttpOptions,
    part: SectionPart,
    cancel: &AtomicBool,
    sink: &mut dyn PcmSink,
) -> Result<(), Failure> {
    let opened = source::open(source, http)?;
    if matches!(part, SectionPart::Tail { .. }) && !opened.seeks_cheaply {
        return Err(Failure::new(ErrorKind::Unsupported, "the stream cannot jump to its end"));
    }
    let mut decoder = Decoder::open(opened)?;
    let rate = decoder.sample_rate();
    let (from, secs) = match part {
        SectionPart::Head { secs } => (0.0, secs),
        SectionPart::Tail { secs, len_secs } => {
            let file_len = decoder.info().duration_ms.map(|ms| ms as f64 / 1_000.0);
            let Some(len) = len_secs.or(file_len) else {
                return Err(Failure::new(ErrorKind::Unsupported, "the song's length is not known"));
            };
            ((len - secs).max(0.0), secs.min(len))
        }
    };
    let start = if from > 0.0 { decoder.seek(from)? } else { 0.0 };
    sink.begin(start, rate);
    let wanted = (secs * rate as f64).round() as usize;
    let mut done = 0;
    let mut block = Vec::new();
    let mut mono = Vec::new();
    while done < wanted {
        if cancel.load(Ordering::Relaxed) {
            return Err(Failure::new(ErrorKind::Other, "cancelled"));
        }
        if !decoder.next_block(&mut block)? {
            break;
        }
        let frames = (block.len() / 2).min(wanted - done);
        mono.clear();
        mono.extend(block[..frames * 2].chunks_exact(2).map(|f| (f[0] + f[1]) * 0.5));
        sink.pcm(&mono);
        done += frames;
    }
    Ok(())
}

/// A section being scouted on its own thread. Dropping it stops the work.
pub struct ScoutJob {
    started: Instant,
    cancel: Arc<AtomicBool>,
    result: Arc<Mutex<Option<Result<SectionAnalysis, Failure>>>>,
}

impl ScoutJob {
    /// Starts decoding `part` of `source` and analyzing it. `tag_bpm` is the
    /// song's tempo from its tags, when it has one.
    pub fn start(source: String, mut http: HttpOptions, part: SectionPart, tag_bpm: Option<f64>) -> ScoutJob {
        let cancel = Arc::new(AtomicBool::new(false));
        http.cancel = Some(cancel.clone());
        let result = Arc::new(Mutex::new(None));
        let (c, r) = (cancel.clone(), result.clone());
        let spawned = thread::Builder::new().name("octo-scout".into()).spawn(move || {
            let kind = match part {
                SectionPart::Head { .. } => SectionKind::Head,
                SectionPart::Tail { .. } => SectionKind::Tail,
            };
            let mut analyzer = SectionAnalyzer::new(kind, tag_bpm);
            let outcome = scout_section(&source, http, part, &c, &mut analyzer).and_then(|_| {
                analyzer.finish().ok_or_else(|| Failure::new(ErrorKind::Other, "the section had no sound"))
            });
            if let Err(f) = &outcome
                && !c.load(Ordering::Relaxed)
            {
                log::info!("automix: could not scout {source}: {f}");
            }
            *r.lock().unwrap_or_else(|e| e.into_inner()) = Some(outcome);
        });
        if let Err(e) = spawned {
            *result.lock().unwrap_or_else(|e| e.into_inner()) =
                Some(Err(Failure::new(ErrorKind::Other, e.to_string())));
        }
        ScoutJob { started: Instant::now(), cancel, result }
    }

    /// The analysis, once the section is decoded.
    pub fn analysis(&self) -> Option<SectionAnalysis> {
        match &*self.result.lock().unwrap_or_else(|e| e.into_inner()) {
            Some(Ok(a)) => Some(a.clone()),
            _ => None,
        }
    }

    /// Finished, failed, or given up on after `limit`.
    pub fn is_settled(&self, limit: Duration) -> bool {
        self.result.lock().unwrap_or_else(|e| e.into_inner()).is_some() || self.started.elapsed() >= limit
    }
}

impl Drop for ScoutJob {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::fixtures::*;

    #[derive(Default)]
    struct Collect {
        start: f64,
        rate: u32,
        mono: Vec<f32>,
    }

    impl PcmSink for Collect {
        fn begin(&mut self, start_secs: f64, sample_rate: u32) {
            self.start = start_secs;
            self.rate = sample_rate;
        }
        fn pcm(&mut self, mono: &[f32]) {
            self.mono.extend_from_slice(mono);
        }
    }

    // A ramp that tells each frame's place: left n, right -n / 2.
    fn ramp(path: &std::path::Path, rate: u32, frames: usize) {
        let samples: Vec<i16> =
            (0..frames).flat_map(|n| [(n % 30_000) as i16, -((n % 30_000) as i16) / 2]).collect();
        write_flac(path, rate, 2, &samples, &[]);
    }

    #[test]
    fn decodes_the_head_and_tail_as_mono() {
        let dir = temp_dir();
        let path = dir.join("song.flac");
        let rate = 44_100;
        ramp(&path, rate, rate as usize * 5);
        let source = path.to_string_lossy().to_string();
        let never = AtomicBool::new(false);

        let mut head = Collect::default();
        scout_section(&source, HttpOptions::default(), SectionPart::Head { secs: 1.0 }, &never, &mut head)
            .unwrap();
        assert_eq!((head.start, head.rate, head.mono.len()), (0.0, rate, rate as usize));
        // The mean of n and -n/2.
        let n = 1_234usize;
        let expected = (n as f32 - (n as i16 / 2) as f32) / 2.0 / 32768.0;
        assert!((head.mono[n] - expected).abs() < 1e-6);

        let mut tail = Collect::default();
        let part = SectionPart::Tail { secs: 2.0, len_secs: None };
        scout_section(&source, HttpOptions::default(), part, &never, &mut tail).unwrap();
        assert!((tail.start - 3.0).abs() < 1e-9, "{}", tail.start);
        assert_eq!(tail.mono.len(), rate as usize * 2);
        let first = rate as usize * 3 % 30_000;
        let expected = (first as f32 - (first as i16 / 2) as f32) / 2.0 / 32768.0;
        assert!((tail.mono[0] - expected).abs() < 1e-6);
    }

    #[test]
    fn a_stream_tail_needs_a_server_that_answers_ranges() {
        use crate::testing::http_server::{Behaviour, TestServer};
        let rate = 44_100;
        // Noise, so the file stays big and its end is far from its start.
        let mut seed = 1u32;
        let samples: Vec<i16> = (0..rate as usize * 20 * 2)
            .map(|_| {
                seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                (seed >> 20) as i16 - 2_048
            })
            .collect();
        let body = flac_bytes(rate, 2, &samples, &[]);
        let http = || HttpOptions { read_ahead: 64 << 10, ..Default::default() };
        let len = body.len() as u64;
        let never = AtomicBool::new(false);
        let part = SectionPart::Tail { secs: 1.0, len_secs: Some(20.0) };

        let server = TestServer::start(body.clone(), Behaviour::default());
        let mut tail = Collect::default();
        scout_section(&server.url(), http(), part, &never, &mut tail).unwrap();
        assert!((tail.start - 19.0).abs() < 1e-9);
        assert_eq!(tail.mono.len(), rate as usize);
        // It jumped to the end with a range rather than reading through.
        assert!(server.ranges_asked().iter().any(|&r| r > len / 2), "{len} {:?}", server.ranges_asked());

        let plain = TestServer::start(body, Behaviour { ranges: false, ..Default::default() });
        let mut tail = Collect::default();
        let r = scout_section(&plain.url(), http(), part, &never, &mut tail);
        assert_eq!(r.unwrap_err().kind, ErrorKind::Unsupported);
        assert!(tail.mono.is_empty());
        assert_eq!(plain.requests(), 1);
    }

    #[test]
    fn a_job_hands_back_the_analysis_and_stops_when_dropped() {
        let dir = temp_dir();
        let path = dir.join("song.wav");
        write_wav(&path, 48_000, 2, &sine(440.0, 48_000, 2, 0, 96_000, 0.3));
        let job = ScoutJob::start(
            path.to_string_lossy().into(),
            HttpOptions::default(),
            SectionPart::Head { secs: 1.5 },
            None,
        );
        let until = Instant::now() + Duration::from_secs(5);
        while !job.is_settled(Duration::from_secs(20)) {
            assert!(Instant::now() < until);
            thread::sleep(Duration::from_millis(2));
        }
        let a = job.analysis().unwrap();
        assert_eq!((a.envelope.start_ms, a.envelope.size()), (0, 150));
        assert_eq!(a.features.sound_start_ms, Some(0));

        let cancel = AtomicBool::new(true);
        let mut sink = Collect::default();
        let source = path.to_string_lossy().to_string();
        let r = scout_section(
            &source,
            HttpOptions::default(),
            SectionPart::Head { secs: 1.0 },
            &cancel,
            &mut sink,
        );
        assert!(r.is_err());
        assert!(sink.mono.is_empty());
    }
}
