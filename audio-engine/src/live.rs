//! Live counts of what holds memory in the engine (open streams, loops and
//! track infos), for the app's memory log. Each count is signed, so a
//! mismatch shows as a negative number rather than wrapping.

use std::sync::atomic::{AtomicI64, Ordering};

/// HTTP streams, counted until their last fetch thread lets go.
pub static HTTP_STREAMS: AtomicI64 = AtomicI64::new(0);
/// HLS streams, counted until their fetch thread lets go.
pub static HLS_STREAMS: AtomicI64 = AtomicI64::new(0);
/// Loop videos that opened.
pub static LOOP_VIDEOS: AtomicI64 = AtomicI64::new(0);
/// Track infos the players hold.
pub static TRACK_INFOS: AtomicI64 = AtomicI64::new(0);

/// One live thing in a count: one more while it is held, one less once it
/// is dropped.
#[derive(Debug)]
pub struct Live(&'static AtomicI64);

impl Live {
    pub fn new(count: &'static AtomicI64) -> Self {
        count.fetch_add(1, Ordering::Relaxed);
        Self(count)
    }
}

impl Drop for Live {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

/// A count that follows a collection's length: each `set` moves the count by
/// the change, and dropping it takes the last length back.
#[derive(Debug)]
pub struct LenCount {
    count: &'static AtomicI64,
    last: i64,
}

impl LenCount {
    pub fn new(count: &'static AtomicI64) -> Self {
        Self { count, last: 0 }
    }

    pub fn set(&mut self, len: usize) {
        let len = len as i64;
        self.count.fetch_add(len - self.last, Ordering::Relaxed);
        self.last = len;
    }
}

impl Drop for LenCount {
    fn drop(&mut self) {
        self.count.fetch_sub(self.last, Ordering::Relaxed);
    }
}

/// The engine's live counts now.
#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct EngineCounters {
    pub loop_videos: i64,
    pub http_streams: i64,
    pub hls_streams: i64,
    pub track_infos: i64,
}

#[uniffi::export]
pub fn engine_counters() -> EngineCounters {
    EngineCounters {
        loop_videos: LOOP_VIDEOS.load(Ordering::Relaxed),
        http_streams: HTTP_STREAMS.load(Ordering::Relaxed),
        hls_streams: HLS_STREAMS.load(Ordering::Relaxed),
        track_infos: TRACK_INFOS.load(Ordering::Relaxed),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn live_counts_while_held() {
        static COUNT: AtomicI64 = AtomicI64::new(0);
        let a = Live::new(&COUNT);
        let b = Live::new(&COUNT);
        assert_eq!(COUNT.load(Ordering::Relaxed), 2);
        drop(a);
        assert_eq!(COUNT.load(Ordering::Relaxed), 1);
        drop(b);
        assert_eq!(COUNT.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn a_failed_build_counts_nothing() {
        static COUNT: AtomicI64 = AtomicI64::new(0);
        struct Held {
            _live: Live,
        }
        fn build(ok: bool) -> Result<Held, ()> {
            if !ok {
                return Err(());
            }
            Ok(Held { _live: Live::new(&COUNT) })
        }
        assert!(build(false).is_err());
        assert_eq!(COUNT.load(Ordering::Relaxed), 0);
        let held = build(true).unwrap();
        assert_eq!(COUNT.load(Ordering::Relaxed), 1);
        drop(held);
        assert_eq!(COUNT.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn len_count_follows_and_gives_back() {
        static COUNT: AtomicI64 = AtomicI64::new(0);
        let mut a = LenCount::new(&COUNT);
        let mut b = LenCount::new(&COUNT);
        a.set(3);
        b.set(2);
        assert_eq!(COUNT.load(Ordering::Relaxed), 5);
        a.set(1);
        assert_eq!(COUNT.load(Ordering::Relaxed), 3);
        drop(b);
        assert_eq!(COUNT.load(Ordering::Relaxed), 1);
        drop(a);
        assert_eq!(COUNT.load(Ordering::Relaxed), 0);
    }
}
