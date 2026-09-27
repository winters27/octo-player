//! Which song, and which moment of it, each ring frame holds. Filled by
//! the mixing thread from the mixer's markers; read with the audio clock to
//! answer "where are we" to within a few milliseconds.

use std::collections::VecDeque;
use std::sync::Mutex;

use crate::mixer::Marker;

/// A moment in a song.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Moment {
    pub key: u64,
    pub secs: f64,
}

#[derive(Default)]
struct Inner {
    markers: VecDeque<Marker>,
    // After a jump: show the target until the listener hears new sound.
    hold: Option<(u64, Moment)>,
    // The last answer, so the clock never runs backwards within a song.
    last: Option<Moment>,
}

#[derive(Default)]
pub struct Timeline {
    inner: Mutex<Inner>,
}

impl Timeline {
    pub fn new() -> Self {
        Self::default()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn push(&self, markers: &[Marker]) {
        if markers.is_empty() {
            return;
        }
        let mut inner = self.lock();
        inner.markers.extend(markers.iter().copied());
    }

    /// A jump: until ring frame `from` is heard, the position is `moment`.
    pub fn hold(&self, from: u64, moment: Moment) {
        let mut inner = self.lock();
        inner.hold = Some((from, moment));
        inner.last = Some(moment);
    }

    /// Drops markers for frames that will never be heard: written after
    /// `heard` but before `upto`, where a jump cut them off.
    pub fn discard_unheard(&self, heard: f64, upto: u64) {
        let mut inner = self.lock();
        inner.markers.retain(|m| (m.frame as f64) <= heard || m.frame >= upto);
    }

    /// Forgets everything, as after a stop.
    pub fn clear(&self) {
        let mut inner = self.lock();
        inner.markers.clear();
        inner.hold = None;
        inner.last = None;
    }

    /// The markers from `after` (exclusive) up to `upto` (inclusive), for
    /// the events of songs starting.
    pub fn crossed(&self, after: f64, upto: f64, out: &mut Vec<Marker>) {
        let inner = self.lock();
        out.extend(
            inner.markers.iter().filter(|m| (m.frame as f64) > after && (m.frame as f64) <= upto).copied(),
        );
    }

    /// The moment heard at ring frame `frame`, or `None` with nothing heard.
    pub fn at(&self, frame: Option<f64>) -> Option<Moment> {
        let mut inner = self.lock();
        if let Some((from, moment)) = inner.hold {
            match frame {
                Some(f) if f >= from as f64 => inner.hold = None,
                _ => return Some(moment),
            }
        }
        let frame = frame?;
        let marker = *inner.markers.iter().rev().find(|m| (m.frame as f64) <= frame)?;
        let secs = marker.secs + (frame - marker.frame as f64) * marker.secs_per_frame;
        let mut moment = Moment { key: marker.key, secs: secs.max(0.0) };
        // Small backward steps from clock jitter are held; real jumps
        // (another song, a seek) come with their own marker.
        if let Some(last) = inner.last
            && last.key == moment.key
            && moment.secs < last.secs
            && last.secs - moment.secs < 0.05
        {
            moment.secs = last.secs;
        }
        inner.last = Some(moment);
        // Markers well behind what is heard are no longer needed.
        while inner.markers.len() > 1 && (inner.markers[1].frame as f64) < frame - 48_000.0 {
            inner.markers.pop_front();
        }
        Some(moment)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn marker(frame: u64, key: u64, secs: f64) -> Marker {
        Marker { frame, key, secs, secs_per_frame: 1.0 / 48_000.0, transition: None }
    }

    #[test]
    fn interpolates_and_never_runs_backwards() {
        let t = Timeline::new();
        t.push(&[marker(1_000, 1, 0.0), marker(49_000, 2, 0.0)]);
        assert_eq!(t.at(Some(500.0)), None);
        let a = t.at(Some(25_000.0)).unwrap();
        assert_eq!(a.key, 1);
        assert!((a.secs - 0.5).abs() < 1e-9);
        // Jitter back by 1 ms holds.
        let b = t.at(Some(24_952.0)).unwrap();
        assert_eq!(b.secs, a.secs);
        let c = t.at(Some(49_480.0)).unwrap();
        assert_eq!(c.key, 2);
        assert!((c.secs - 0.01).abs() < 1e-9);
    }

    #[test]
    fn a_hold_shows_the_target_until_it_is_heard() {
        let t = Timeline::new();
        t.push(&[marker(0, 1, 10.0)]);
        t.hold(10_000, Moment { key: 1, secs: 42.0 });
        assert_eq!(t.at(Some(5_000.0)).unwrap().secs, 42.0);
        t.push(&[marker(10_000, 1, 42.0)]);
        assert!((t.at(Some(10_480.0)).unwrap().secs - 42.01).abs() < 1e-9);
    }
}
