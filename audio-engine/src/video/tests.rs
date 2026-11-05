use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use super::*;

/// A decoder of `frames` frames `gap` apart, each filled with its number
/// (round * 16 + index), counting rewinds.
struct Counting {
    frames: u32,
    gap: Duration,
    at: u32,
    round: u32,
    rewinds: Arc<AtomicU32>,
    lengths: bool,
}

impl FrameSource for Counting {
    fn size(&self) -> (u32, u32) {
        (4, 2)
    }

    fn next_frame(&mut self, pixels: &mut [u8]) -> Result<Option<FrameTime>, Failure> {
        if self.at >= self.frames {
            return Ok(None);
        }
        let mark = (self.round * 16 + self.at) as u8;
        pixels.fill(mark);
        // Starts at a timestamp other than zero, as some files do.
        let at = Duration::from_millis(500) + self.gap * self.at;
        self.at += 1;
        Ok(Some(FrameTime { at, length: self.lengths.then_some(self.gap) }))
    }

    fn rewind(&mut self) -> Result<(), Failure> {
        self.at = 0;
        self.round += 1;
        self.rewinds.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
}

fn counting(frames: u32, gap_ms: u64) -> (Arc<LoopVideo>, Arc<AtomicU32>) {
    let rewinds = Arc::new(AtomicU32::new(0));
    let source = Counting {
        frames,
        gap: Duration::from_millis(gap_ms),
        at: 0,
        round: 0,
        rewinds: rewinds.clone(),
        lengths: true,
    };
    let video = LoopVideo::start(Box::new(move || Ok(Box::new(source) as Box<dyn FrameSource>))).unwrap();
    (video, rewinds)
}

fn frame_of(video: &LoopVideo) -> Vec<u8> {
    let mut pixels = vec![0u8; 32];
    let address = pixels.as_mut_ptr() as u64;
    video.copy_frame(address, 16, 32, 0).unwrap();
    pixels
}

#[test]
fn a_video_starts_held_and_shows_nothing_until_played() {
    let (video, _) = counting(4, 10);
    assert_eq!((video.width(), video.height()), (4, 2));
    std::thread::sleep(Duration::from_millis(60));
    assert_eq!(video.frames(), 0);
    // At most the first frame is decoded ahead while held.
    assert!(video.decoded() <= 1);
    video.stop();
}

#[test]
fn frames_are_shown_at_their_times() {
    let (video, _) = counting(100, 20);
    let start = Instant::now();
    video.play();
    let mut seen = 0;
    while seen < 6 {
        seen = video.wait_frame(seen, 1000);
    }
    let took = start.elapsed();
    // Six frames 20 ms apart: the first at once, the sixth 100 ms later.
    assert!(took >= Duration::from_millis(95), "too quick: {took:?}");
    assert!(took < Duration::from_millis(400), "too slow: {took:?}");
    video.stop();
}

/// A clock that moves only when the loop waits: each wait jumps it to the
/// moment waited for, and the moment is logged.
struct SetClock {
    base: Instant,
    elapsed: Mutex<Duration>,
    waits: Mutex<Vec<Duration>>,
}

impl Clock for SetClock {
    fn now(&self) -> Instant {
        self.base + *self.elapsed.lock().unwrap()
    }

    fn wait<'a>(
        &self,
        _wake: &Condvar,
        guard: MutexGuard<'a, Control>,
        timeout: Duration,
    ) -> MutexGuard<'a, Control> {
        let mut elapsed = self.elapsed.lock().unwrap();
        *elapsed += timeout;
        self.waits.lock().unwrap().push(*elapsed);
        guard
    }
}

#[test]
fn the_loop_starts_over_without_a_pause() {
    let rewinds = Arc::new(AtomicU32::new(0));
    let source = Counting {
        frames: 3,
        gap: Duration::from_millis(15),
        at: 0,
        round: 0,
        rewinds: rewinds.clone(),
        lengths: true,
    };
    let clock = Arc::new(SetClock {
        base: Instant::now(),
        elapsed: Mutex::new(Duration::ZERO),
        waits: Mutex::new(Vec::new()),
    });
    static OPEN: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
    let video = LoopVideo::start_on(
        Box::new(move || Ok(Box::new(source) as Box<dyn FrameSource>)),
        clock.clone(),
        &OPEN,
    )
    .unwrap();
    video.play();
    let mut seen = 0;
    while seen < 10 {
        seen = video.wait_frame(seen, 1000);
    }
    video.stop();
    assert!(rewinds.load(Ordering::Relaxed) >= 3);
    // Frame 0 is shown at once; each later frame is waited for until its
    // place, 15 ms after the one before, across the three starts over.
    let waits = clock.waits.lock().unwrap();
    assert!(waits.len() >= 9, "only {} waits", waits.len());
    for (i, at) in waits.iter().take(9).enumerate() {
        assert_eq!(*at, Duration::from_millis(15) * (i as u32 + 1), "frame {} off its place", i + 1);
    }
}

#[test]
fn an_open_loop_is_counted_until_dropped() {
    static OPEN: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
    let source = Counting {
        frames: 3,
        gap: Duration::from_millis(15),
        at: 0,
        round: 0,
        rewinds: Arc::new(AtomicU32::new(0)),
        lengths: true,
    };
    let video = LoopVideo::start_on(
        Box::new(move || Ok(Box::new(source) as Box<dyn FrameSource>)),
        Arc::new(RealClock),
        &OPEN,
    )
    .unwrap();
    assert_eq!(OPEN.load(Ordering::Relaxed), 1);
    drop(video);
    assert_eq!(OPEN.load(Ordering::Relaxed), 0);
    let failed = LoopVideo::start_on(
        Box::new(|| -> Result<Box<dyn FrameSource>, Failure> {
            Err(Failure::new(ErrorKind::Decode, "no such video"))
        }),
        Arc::new(RealClock),
        &OPEN,
    );
    assert!(failed.is_err());
    assert_eq!(OPEN.load(Ordering::Relaxed), 0);
}

#[test]
fn a_held_loop_decodes_nothing() {
    let (video, _) = counting(1000, 10);
    video.play();
    let mut seen = 0;
    while seen < 3 {
        seen = video.wait_frame(seen, 1000);
    }
    video.hold();
    std::thread::sleep(Duration::from_millis(30));
    let decoded = video.decoded();
    let shown = video.frames();
    std::thread::sleep(Duration::from_millis(150));
    assert_eq!(video.decoded(), decoded);
    assert_eq!(video.frames(), shown);
    // Played again, it carries on from the frame it held on.
    let before = frame_of(&video)[0];
    video.play();
    let next = video.wait_frame(shown, 1000);
    assert_eq!(next, shown + 1);
    assert_eq!(frame_of(&video)[0], before + 1);
    video.stop();
}

#[test]
fn hold_and_stop_wake_a_waiting_reader() {
    let (video, _) = counting(1000, 500);
    video.play();
    let first = video.wait_frame(0, 1000);
    assert_eq!(first, 1);
    let waiter = {
        let video = video.clone();
        std::thread::spawn(move || {
            let start = Instant::now();
            video.wait_frame(1, 5000);
            start.elapsed()
        })
    };
    std::thread::sleep(Duration::from_millis(50));
    video.hold();
    let waited = waiter.join().unwrap();
    assert!(waited < Duration::from_millis(400), "waited {waited:?}");
    video.stop();
}

#[test]
fn a_frame_is_copied_only_when_newer() {
    let (video, _) = counting(1000, 10);
    video.play();
    let shown = video.wait_frame(0, 1000);
    video.hold();
    std::thread::sleep(Duration::from_millis(30));
    let shown = video.frames().max(shown);
    let mut pixels = vec![7u8; 32];
    let address = pixels.as_mut_ptr() as u64;
    assert_eq!(video.copy_frame(address, 16, 32, shown).unwrap(), shown);
    assert!(pixels.iter().all(|&b| b == 7), "copied a frame already had");
    assert_eq!(video.copy_frame(address, 16, 32, 0).unwrap(), shown);
    assert!(pixels.iter().all(|&b| b != 7));
    video.stop();
}

#[test]
fn rows_are_copied_to_the_bitmaps_own_spacing() {
    let (video, _) = counting(1000, 10);
    video.play();
    video.wait_frame(0, 1000);
    video.hold();
    // Rows 24 bytes apart: 16 of pixels, 8 left alone.
    let mut pixels = vec![200u8; 40];
    let address = pixels.as_mut_ptr() as u64;
    video.copy_frame(address, 24, 40, 0).unwrap();
    let mark = pixels[0];
    assert!(pixels[..16].iter().all(|&b| b == mark));
    assert!(pixels[16..24].iter().all(|&b| b == 200));
    assert!(pixels[24..40].iter().all(|&b| b == mark));
    video.stop();
}

#[test]
fn memory_too_small_for_a_frame_is_refused() {
    let (video, _) = counting(10, 10);
    let mut pixels = vec![0u8; 31];
    let address = pixels.as_mut_ptr() as u64;
    assert!(video.copy_frame(address, 16, 31, 0).is_err());
    // Rows closer than a row's width.
    assert!(video.copy_frame(address, 12, 31, 0).is_err());
    assert!(video.copy_frame(0, 16, 32, 0).is_err());
    video.stop();
}

#[test]
fn a_file_with_no_frames_fails_rather_than_spinning() {
    let (video, _) = counting(0, 10);
    video.play();
    video.wait_frame(0, 1000);
    std::thread::sleep(Duration::from_millis(20));
    assert!(video.failure().is_some());
    video.stop();
}

#[test]
fn a_decoder_that_cannot_open_fails_the_open() {
    let result = LoopVideo::start(Box::new(|| Err(Failure::new(ErrorKind::Unsupported, "no decoder"))));
    assert!(matches!(result, Err(Failure { kind: ErrorKind::Unsupported, .. })));
}

#[test]
fn a_missing_file_is_not_found() {
    let result = LoopVideo::open("no/such/loop.mp4".into(), 0, 0);
    assert!(matches!(result, Err(EngineError::Failed { kind: ErrorKind::NotFound, .. })));
}

#[test]
fn the_timeline_runs_on_across_rounds() {
    let gap = Duration::from_millis(40);
    let mut timeline = Timeline::default();
    let frame = |i: u32| FrameTime { at: Duration::from_millis(1000) + gap * i, length: None };
    assert_eq!(timeline.place(frame(0)), Duration::ZERO);
    assert_eq!(timeline.place(frame(1)), gap);
    assert_eq!(timeline.place(frame(2)), gap * 2);
    timeline.wrap();
    // The last frame's length is the gap seen, so the next round starts
    // one gap after it.
    assert_eq!(timeline.place(frame(0)), gap * 3);
    assert_eq!(timeline.place(frame(1)), gap * 4);
}

#[test]
fn the_timeline_uses_the_frames_own_lengths() {
    let mut timeline = Timeline::default();
    let length = Some(Duration::from_millis(50));
    timeline.place(FrameTime { at: Duration::ZERO, length });
    timeline.place(FrameTime { at: Duration::from_millis(50), length });
    timeline.wrap();
    assert_eq!(timeline.place(FrameTime { at: Duration::ZERO, length }), Duration::from_millis(100));
    // A round with nothing in it still moves on.
    timeline.wrap();
    timeline.wrap();
    assert!(timeline.place(FrameTime { at: Duration::ZERO, length }) > Duration::from_millis(100));
}

#[test]
fn scaled_sizes_cover_the_space_and_never_enlarge() {
    assert_eq!(scaled_size((1080, 1080), (486, 486)), (486, 486));
    assert_eq!(scaled_size((1080, 1080), (485, 485)), (486, 486));
    assert_eq!(scaled_size((486, 486), (1000, 1000)), (486, 486));
    assert_eq!(scaled_size((1440, 1920), (600, 600)), (600, 800));
    assert_eq!(scaled_size((1440, 1920), (0, 0)), (1440, 1920));
    // Covering a wide space with a tall frame takes the width.
    assert_eq!(scaled_size((810, 1080), (700, 400)), (700, 934));
}

/// The fixture: 1 s of H.264 High at 24 frames a second, 90x90 (coded
/// 96x96), red over blue.
fn fixture() -> String {
    format!("{}/tests/fixtures/loop-red-blue.mp4", env!("CARGO_MANIFEST_DIR"))
}

fn near(pixel: &[u8], bgra: [u8; 4]) -> bool {
    pixel.iter().zip(bgra).all(|(&a, b)| a.abs_diff(b) <= 40)
}

#[test]
fn the_systems_decoder_plays_and_loops_the_fixture() {
    if !loop_video_available() {
        eprintln!("no system video decoder here; skipped");
        return;
    }
    let video = LoopVideo::open(fixture(), 0, 0).unwrap();
    assert_eq!((video.width(), video.height()), (90, 90));
    video.play();
    let mut seen = 0;
    let start = Instant::now();
    // 30 frames is past the end of the 24: the loop started over.
    while seen < 30 && start.elapsed() < Duration::from_secs(5) {
        seen = video.wait_frame(seen, 1000);
    }
    assert!(seen >= 30, "only {seen} frames, failure {:?}", video.failure());
    let took = start.elapsed();
    assert!(took >= Duration::from_millis(1100), "faster than the frames' times: {took:?}");
    let mut pixels = vec![0u8; 90 * 90 * 4];
    video.copy_frame(pixels.as_mut_ptr() as u64, 360, pixels.len() as u64, 0).unwrap();
    // Top row first, BGRA: red at the top, blue at the bottom, opaque.
    let top = &pixels[(10 * 90 + 45) * 4..][..4];
    let bottom = &pixels[(80 * 90 + 45) * 4..][..4];
    assert!(near(top, [0, 0, 255, 255]), "top {top:?}");
    assert!(near(bottom, [255, 0, 0, 255]), "bottom {bottom:?}");
    video.stop();
}

#[test]
fn the_systems_decoder_never_enlarges() {
    if !loop_video_available() {
        return;
    }
    let (frames, _, size) = decode_speed(&fixture(), (400, 400)).unwrap();
    assert_eq!(frames, 24);
    assert_eq!(size, (90, 90));
    let (_, _, smaller) = decode_speed(&fixture(), (40, 40)).unwrap();
    assert!(smaller.0 <= 90 && smaller.1 <= 90 && smaller.0 == smaller.1, "{smaller:?}");
}

#[cfg(windows)]
#[test]
fn loops_on_the_graphics_card_share_one_device() {
    let first = media_foundation::MfSource::open(&fixture(), (0, 0)).unwrap();
    let second = media_foundation::MfSource::open(&fixture(), (0, 0)).unwrap();
    if !first.on_gpu() {
        eprintln!("no graphics card for video here; skipped");
        return;
    }
    assert!(second.on_gpu());
    assert!(first.shares_device_with(&second));
    drop(first);
    // The device outlives the loop that made it.
    let third = media_foundation::MfSource::open(&fixture(), (0, 0)).unwrap();
    assert!(second.shares_device_with(&third));
}

/// The process counters once they hold still: the same handle count read
/// twice in a row. Media Foundation finishes closing a reader on its own
/// threads a moment after the drop, so a reading taken at once still sees
/// that reader's handles (about 25) open.
#[cfg(windows)]
fn settled_stats() -> crate::process_stats::ProcessStats {
    use crate::process_stats::process_stats;

    let mut last = process_stats();
    for _ in 0..100 {
        let now = process_stats();
        if now.handles == last.handles {
            return now;
        }
        last = now;
    }
    last
}

/// 1,000 software-path opens and closes of the fixture, four times over,
/// each batch measured from settled counters. The 20 opens before and the
/// first batch warm up what stays for the process: Media Foundation's work
/// queue (one more thread, with its Event, IoCompletion and
/// WaitCompletionPacket handles) and the heap the readers are made in. The
/// last batch must keep nothing.
///
/// Run alone: `cargo test --release --lib opening_the_fixture_many_times_keeps_nothing -- --ignored --nocapture --test-threads=1`.
#[cfg(windows)]
#[test]
#[ignore]
fn opening_the_fixture_many_times_keeps_nothing() {
    // SAFETY: this test runs alone, so no other thread reads the variable.
    unsafe { std::env::set_var("OCTO_VIDEO_SOFTWARE", "1") };
    let path = fixture();
    for _ in 0..20 {
        drop(media_foundation::MfSource::open(&path, (0, 0)).unwrap());
    }
    let types_first = handle_types::handle_types();
    println!("handles by type after warm-up: {types_first:?}");
    let mut last = (0, 0);
    for number in 1..=4 {
        let before = settled_stats();
        for _ in 0..1000 {
            drop(std::hint::black_box(media_foundation::MfSource::open(&path, (0, 0)).unwrap()));
        }
        let after = settled_stats();
        let bytes = after.private_bytes.unwrap().saturating_sub(before.private_bytes.unwrap());
        let handles = after.handles.unwrap().saturating_sub(before.handles.unwrap());
        let threads = i64::from(after.threads.unwrap()) - i64::from(before.threads.unwrap());
        println!("batch {number}: private bytes +{bytes}, handles +{handles}, threads {threads:+}");
        last = (bytes, handles);
    }
    let types_last = handle_types::handle_types();
    println!("handle types changed over the batches: {:?}", handle_types::changed(&types_first, &types_last));
    let (bytes, handles) = last;
    assert!(handles < 5, "handles grew by {handles}");
    assert!(bytes < 1 << 20, "private bytes grew by {bytes}");
}

#[test]
fn rows_copy_upwards_and_set_alpha() {
    // Two rows of one pixel, stored bottom row first, alpha 0.
    let from = [1, 2, 3, 0, 4, 5, 6, 0];
    let mut into = [0u8; 8];
    copy_rows(&from, 4, -4, 1, 2, &mut into);
    assert_eq!(into, [4, 5, 6, 255, 1, 2, 3, 255]);
    let mut into = [0u8; 8];
    copy_rows(&from, 0, 4, 1, 2, &mut into);
    assert_eq!(into, [1, 2, 3, 255, 4, 5, 6, 255]);
}
