//! The audio clock: which ring frame the listener hears right now, from what
//! the device callback last played and when that is heard. The callback
//! writes it without locking; any thread can read it.

use std::sync::atomic::{AtomicU64, Ordering, fence};
use std::time::Instant;

pub struct Clock {
    rate: u32,
    epoch: Instant,
    // Odd while the callback is writing.
    seq: AtomicU64,
    // The ring frame just after the last one played in the last buffer
    // that played anything, and where in that buffer it came.
    index: AtomicU64,
    offset: AtomicU64,
    // When that buffer's first frame is heard, in nanoseconds from `epoch`.
    heard_at: AtomicU64,
}

impl Clock {
    pub fn new(rate: u32) -> Self {
        Self {
            rate,
            epoch: Instant::now(),
            seq: AtomicU64::new(0),
            index: AtomicU64::new(0),
            offset: AtomicU64::new(0),
            heard_at: AtomicU64::new(0),
        }
    }

    /// From the callback: the buffer just filled ends its real sound with
    /// ring frame `index - 1` at buffer frame `offset - 1`, and the buffer
    /// starts to be heard at `heard_at`.
    pub fn record(&self, index: u64, offset: usize, heard_at: Instant) {
        let nanos = heard_at.saturating_duration_since(self.epoch).as_nanos() as u64;
        let seq = self.seq.load(Ordering::Relaxed);
        self.seq.store(seq.wrapping_add(1), Ordering::Relaxed);
        fence(Ordering::Release);
        self.index.store(index, Ordering::Relaxed);
        self.offset.store(offset as u64, Ordering::Relaxed);
        self.heard_at.store(nanos, Ordering::Relaxed);
        self.seq.store(seq.wrapping_add(2), Ordering::Release);
    }

    /// The ring frame being heard at `now`, as a fraction for smooth
    /// reading between callbacks. `None` before anything has played.
    pub fn heard(&self, now: Instant) -> Option<f64> {
        let (index, offset, heard_at) = loop {
            let before = self.seq.load(Ordering::Acquire);
            if before % 2 == 1 {
                std::hint::spin_loop();
                continue;
            }
            let index = self.index.load(Ordering::Relaxed);
            let offset = self.offset.load(Ordering::Relaxed);
            let heard_at = self.heard_at.load(Ordering::Relaxed);
            fence(Ordering::Acquire);
            if self.seq.load(Ordering::Relaxed) == before {
                break (index, offset, heard_at);
            }
        };
        if heard_at == 0 && index == 0 {
            return None;
        }
        let now = now.saturating_duration_since(self.epoch).as_nanos() as f64;
        // How far into that buffer the listener is now, in frames.
        let into = (now - heard_at as f64) * self.rate as f64 / 1e9;
        let behind = offset as f64 - into;
        // Past the last real frame: silence since, so the clock holds.
        Some(if behind <= 0.0 { index as f64 } else { index as f64 - behind })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn reads_between_callbacks_and_holds_after_the_last_frame() {
        let clock = Clock::new(48_000);
        let t0 = Instant::now();
        assert_eq!(clock.heard(t0), None);
        // A 480-frame buffer ending at ring frame 1000, heard 20 ms from now.
        clock.record(1_000, 480, t0 + Duration::from_millis(20));
        // 5 ms into it: frame 520 + 240.
        let at = clock.heard(t0 + Duration::from_millis(25)).unwrap();
        assert!((at - 760.0).abs() < 0.01, "{at}");
        // Before it starts: still in earlier buffers.
        let before = clock.heard(t0 + Duration::from_millis(10)).unwrap();
        assert!((before - 40.0).abs() < 0.01, "{before}");
        // Long after: holds at the end of what played.
        assert_eq!(clock.heard(t0 + Duration::from_secs(5)), Some(1_000.0));
    }
}
