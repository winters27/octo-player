use super::*;
use crate::source::trust::{Identity, Trust, identity_problem};
use crate::testing::http_server::{
    Behaviour, ClientAuthServer, ClientCa, ClientIdentity, SelfSigned, TestServer,
};
use std::io::{Read, Seek, SeekFrom, Write};
use ureq::unversioned::resolver::{ResolvedSocketAddrs, Resolver};

fn data(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 7 + i / 251) as u8).collect()
}

// Small buffers; a stall timeout no pause of a busy machine reaches.
fn quick() -> HttpOptions {
    HttpOptions {
        stall_timeout: Duration::from_secs(60),
        read_ahead: 256 << 10,
        keep_behind: 64 << 10,
        ..Default::default()
    }
}

fn read_all(source: &mut HttpSource) -> Vec<u8> {
    let mut out = Vec::new();
    source.read_to_end(&mut out).unwrap();
    out
}

#[test]
fn reads_a_whole_stream() {
    let body = data(3_000_000);
    let server = TestServer::start(body.clone(), Behaviour::default());
    let mut source = HttpSource::open(&server.url(), quick()).unwrap();
    assert!(source.supports_ranges());
    assert_eq!(source.byte_len(), Some(body.len() as u64));
    assert_eq!(source.content_type(), Some("audio/flac"));
    assert_eq!(read_all(&mut source), body);
}

#[test]
fn an_open_stream_is_counted_until_its_fetch_ends() {
    static OPEN: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
    let body = data(3_000_000);
    let server = TestServer::start(body.clone(), Behaviour::default());
    let mut source = HttpSource::open(&server.url(), HttpOptions { stream_count: &OPEN, ..quick() }).unwrap();
    let mut buf = vec![0u8; 1000];
    source.read_exact(&mut buf).unwrap();
    assert_eq!(OPEN.load(Ordering::Relaxed), 1);
    // What a fetch thread holds: the stream's shared state, buffers and all.
    let held = source.shared.clone();
    drop(source);
    // Still counted while anything holds that state, as a fetch thread
    // stuck in a read would.
    assert_eq!(OPEN.load(Ordering::Relaxed), 1);
    drop(held);
    let until = Instant::now() + Duration::from_secs(10);
    while OPEN.load(Ordering::Relaxed) != 0 {
        assert!(Instant::now() < until, "still counted: {}", OPEN.load(Ordering::Relaxed));
        std::thread::sleep(Duration::from_millis(5));
    }
    // A refused open counts nothing.
    let refused = TestServer::start(body, Behaviour { status: Some(404), ..Behaviour::default() });
    assert!(HttpSource::open(&refused.url(), HttpOptions { stream_count: &OPEN, ..quick() }).is_err());
    assert_eq!(OPEN.load(Ordering::Relaxed), 0);
}

#[test]
fn seeks_with_ranges() {
    let body = data(4_000_000);
    let server = TestServer::start(body.clone(), Behaviour::default());
    let mut source = HttpSource::open(&server.url(), quick()).unwrap();
    let mut buf = vec![0u8; 1000];

    // Far ahead: a new range request.
    source.seek(SeekFrom::Start(3_000_000)).unwrap();
    source.read_exact(&mut buf).unwrap();
    assert_eq!(buf, body[3_000_000..3_001_000]);
    assert!(server.ranges_asked().contains(&3_000_000), "{:?}", server.ranges_asked());

    // Back to the start, long dropped from memory: another range.
    source.seek(SeekFrom::Start(10)).unwrap();
    source.read_exact(&mut buf).unwrap();
    assert_eq!(buf, body[10..1010]);

    // From the end.
    source.seek(SeekFrom::End(-500)).unwrap();
    let mut tail = Vec::new();
    source.read_to_end(&mut tail).unwrap();
    assert_eq!(tail, body[body.len() - 500..]);
}

#[test]
fn seeks_on_a_server_without_ranges() {
    let body = data(2_000_000);
    let server = TestServer::start(body.clone(), Behaviour { ranges: false, ..Default::default() });
    let mut source = HttpSource::open(&server.url(), quick()).unwrap();
    assert!(!source.supports_ranges());
    let mut buf = vec![0u8; 1000];
    source.seek(SeekFrom::Start(1_500_000)).unwrap();
    source.read_exact(&mut buf).unwrap();
    assert_eq!(buf, body[1_500_000..1_501_000]);
    // Back again: fetched from the start and skipped.
    source.seek(SeekFrom::Start(100)).unwrap();
    source.read_exact(&mut buf).unwrap();
    assert_eq!(buf, body[100..1100]);
    assert!(server.requests() >= 2);
}

#[test]
fn picks_up_after_a_dropped_connection() {
    let body = data(2_000_000);
    let server =
        TestServer::start(body.clone(), Behaviour { drop_after: Some(700_000), ..Default::default() });
    let mut source = HttpSource::open(&server.url(), quick()).unwrap();
    assert_eq!(read_all(&mut source), body);
    // The second request carried on from where the first broke off.
    assert!(server.ranges_asked().iter().any(|&r| r > 0), "{:?}", server.ranges_asked());
}

#[test]
fn picks_up_after_a_drop_without_ranges() {
    let body = data(1_000_000);
    let server = TestServer::start(
        body.clone(),
        Behaviour { ranges: false, drop_after: Some(300_000), ..Default::default() },
    );
    let mut source = HttpSource::open(&server.url(), quick()).unwrap();
    assert_eq!(read_all(&mut source), body);
    assert_eq!(server.requests(), 2);
}

#[test]
fn replaces_a_connection_that_goes_quiet() {
    let body = data(1_000_000);
    let server =
        TestServer::start(body.clone(), Behaviour { stall_after: Some(200_000), ..Default::default() });
    // A pause of a busy machine can look like a stall too: that only costs
    // another request, never the stream.
    let opts = HttpOptions { stall_timeout: Duration::from_millis(600), max_retries: 1_000, ..quick() };
    let mut source = HttpSource::open(&server.url(), opts).unwrap();
    // The quiet connection stays quiet for as long as the server runs, so
    // the stream only ends whole over a new one.
    assert_eq!(read_all(&mut source), body);
    assert!(server.requests() >= 2);
}

// A stream made on the way is never picked up by bytes where it broke off:
// a new request could send another file. Red when a stream sought by time
// resumes like any other.
#[test]
fn a_stream_sought_by_time_that_breaks_off_asks_to_be_reopened() {
    let body = data(1_000_000);
    let server = TestServer::start(body, Behaviour { drop_after: Some(200_000), ..Default::default() });
    let mut source = HttpSource::open(&server.url(), HttpOptions { by_time: true, ..quick() }).unwrap();
    let mut out = Vec::new();
    let error = source.read_to_end(&mut out).unwrap_err();
    let failure = error.get_ref().and_then(|inner| inner.downcast_ref::<Failure>()).cloned().unwrap();
    assert!(failure.reopen, "{failure:?}");
    assert_eq!(failure.kind, ErrorKind::Network);
    assert!(out.len() <= 200_000);
    assert_eq!(server.requests(), 1);
}

#[test]
fn a_quiet_connection_lets_go_of_the_stream_once_replaced() {
    let body = data(1_000_000);
    let server =
        TestServer::start(body.clone(), Behaviour { stall_after: Some(200_000), ..Default::default() });
    // The idle limit also bounds the wait for each answer, so it is well
    // past the time a busy machine takes to answer, and short of the wait
    // below for the fetch threads to end.
    let opts = HttpOptions {
        agent: agent_with_idle_limit(Arc::new(Trust::default()), Duration::from_secs(3)),
        stall_timeout: Duration::from_millis(400),
        max_retries: 1_000,
        ..quick()
    };
    let mut source = HttpSource::open(&server.url(), opts).unwrap();
    assert_eq!(read_all(&mut source), body);
    // The fetch threads hold the stream's memory; the one on the quiet
    // connection gives up its read and ends, as do the rest.
    let held = Arc::downgrade(&source.shared);
    drop(source);
    let until = Instant::now() + Duration::from_secs(10);
    while held.strong_count() > 0 && Instant::now() < until {
        thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(held.strong_count(), 0, "a fetch thread still holds the stream");
}

#[test]
fn a_wait_for_input_is_limited_and_a_shorter_one_kept() {
    use ureq::unversioned::transport::time::Duration as Wait;
    let limit = Duration::from_secs(30);
    let endless = NextTimeout { after: Wait::NotHappening, reason: ureq::Timeout::RecvBody };
    assert_eq!(*idle_limited(endless, limit).after, limit);
    let long = NextTimeout { after: Wait::Exact(Duration::from_secs(90)), reason: ureq::Timeout::RecvBody };
    assert_eq!(*idle_limited(long, limit).after, limit);
    let short =
        NextTimeout { after: Wait::Exact(Duration::from_secs(5)), reason: ureq::Timeout::RecvResponse };
    let kept = idle_limited(short, limit);
    assert_eq!(*kept.after, Duration::from_secs(5));
    assert_eq!(kept.reason, ureq::Timeout::RecvResponse);
}

#[test]
fn missing_song_fails_at_once() {
    let server = TestServer::start(Vec::new(), Behaviour { status: Some(404), ..Default::default() });
    let err = HttpSource::open(&server.url(), quick()).err().unwrap();
    assert_eq!(err.kind, ErrorKind::NotFound);
    assert_eq!(err.status, Some(404));
}

#[test]
fn no_server_is_a_network_failure() {
    // A port nothing listens on. Port 1 is below the range a port asked for
    // as 0 comes from, so no other test's server can be given it.
    let err = HttpSource::open("http://127.0.0.1:1/x", quick()).err().unwrap();
    assert_eq!(err.kind, ErrorKind::Network);
}

// Options fetching with its own client, trusting these pins.
fn trusting(pins: &[(&str, &str)]) -> HttpOptions {
    let trust = Arc::new(Trust::default());
    trust.replace(pins.iter().map(|(host, sha)| (host.to_string(), sha.to_string())));
    HttpOptions { agent: agent(trust), ..quick() }
}

#[test]
fn a_self_signed_certificate_is_refused_without_a_pin() {
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let server = TestServer::start_tls(data(10_000), Behaviour::default(), &certificate);
    let err = HttpSource::open(&server.url(), trusting(&[])).err().unwrap();
    assert_eq!(err.kind, ErrorKind::Network);
    assert!(err.message.to_lowercase().contains("certificate"), "{err}");
    assert_eq!(server.requests(), 0, "nothing was asked over the refused connection");
}

#[test]
fn a_pinned_certificate_streams_for_its_host() {
    let body = data(3_000_000);
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let server = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
    // Marked up the way people read it, as the app may pass it on.
    let shown: String = certificate
        .fingerprint()
        .to_uppercase()
        .as_bytes()
        .chunks(2)
        .map(|pair| std::str::from_utf8(pair).unwrap())
        .collect::<Vec<_>>()
        .join(":");
    let mut source = HttpSource::open(&server.url(), trusting(&[("127.0.0.1", &shown)])).unwrap();
    assert_eq!(read_all(&mut source), body);
    // Seeking makes new connections, each checked the same way.
    source.seek(SeekFrom::Start(100)).unwrap();
    let mut buf = vec![0u8; 1000];
    source.read_exact(&mut buf).unwrap();
    assert_eq!(buf, body[100..1100]);
}

#[test]
fn a_pin_passes_a_certificate_naming_another_host() {
    // Self-made certificates often name something else; the pin is the
    // host's identity by itself.
    let body = data(100_000);
    let certificate = SelfSigned::new(&["octo.test"]);
    let server = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
    let mut source =
        HttpSource::open(&server.url(), trusting(&[("127.0.0.1", &certificate.fingerprint())])).unwrap();
    assert_eq!(read_all(&mut source), body);
}

#[test]
fn a_pin_for_another_host_or_certificate_is_no_use() {
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let other = SelfSigned::new(&["127.0.0.1"]);
    let server = TestServer::start_tls(data(10_000), Behaviour::default(), &certificate);
    let elsewhere =
        trusting(&[("localhost", &certificate.fingerprint()), ("127.0.0.2", &certificate.fingerprint())]);
    let err = HttpSource::open(&server.url(), elsewhere).err().unwrap();
    assert_eq!(err.kind, ErrorKind::Network);
    let wrong = trusting(&[("127.0.0.1", &other.fingerprint())]);
    let err = HttpSource::open(&server.url(), wrong).err().unwrap();
    assert_eq!(err.kind, ErrorKind::Network);
}

#[test]
fn a_pin_given_later_applies_to_the_next_connection() {
    let body = data(10_000);
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let server = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
    let trust = Arc::new(Trust::default());
    let opts = HttpOptions { agent: agent(trust.clone()), ..quick() };
    assert!(HttpSource::open(&server.url(), opts.clone()).is_err());
    trust.replace([("127.0.0.1".to_string(), certificate.fingerprint())]);
    let mut source = HttpSource::open(&server.url(), opts.clone()).unwrap();
    assert_eq!(read_all(&mut source), body);
    // Taken away again, it no longer lets a new connection in.
    trust.replace([]);
    assert!(HttpSource::open(&server.url(), opts).is_err());
}

#[test]
fn content_range_parsing() {
    assert_eq!(range_total("bytes 0-99/1234"), Some(1234));
    assert_eq!(range_start("bytes 100-199/1234"), Some(100));
    assert_eq!(range_total("bytes 0-99/*"), None);
}

// Byte `p` of an endless broadcast.
fn on_air(p: u64) -> u8 {
    (p * 13 + p / 977) as u8
}

fn live() -> HttpOptions {
    HttpOptions { live: true, live_backoff: Duration::from_millis(10), ..quick() }
}

// A metadata block holding `text`, as a server sends it.
fn icy_block(text: &str) -> Vec<u8> {
    let mut block = text.as_bytes().to_vec();
    block.resize(block.len().div_ceil(16) * 16, 0);
    let mut out = vec![(block.len() / 16) as u8];
    out.extend_from_slice(&block);
    out
}

// Reads `len` bytes in small pieces, taking the titles reached on the way.
fn read_live(source: &mut HttpSource, slot: &TitleSlot, len: usize) -> (Vec<u8>, Vec<String>) {
    let (mut out, mut titles) = (Vec::new(), Vec::new());
    let mut buf = vec![0u8; 1000];
    while out.len() < len {
        let n = source.read(&mut buf).unwrap();
        assert!(n > 0, "a live stream never ends");
        out.extend_from_slice(&buf[..n]);
        titles.extend(slot.take().map(|meta| meta.title));
    }
    out.truncate(len);
    (out, titles)
}

#[test]
fn an_endless_icy_stream_gives_its_titles_and_clean_audio() {
    use crate::testing::http_server::ScriptServer;
    const METAINT: u64 = 8000;
    let server = ScriptServer::start(|_, conn, stopped| {
        let head =
            "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nicy-metaint: 8000\r\nConnection: close\r\n\r\n";
        if conn.write_all(head.as_bytes()).is_err() {
            return;
        }
        for i in 0u64.. {
            let audio: Vec<u8> = (i * METAINT..(i + 1) * METAINT).map(on_air).collect();
            // Each title comes three times, with an empty one and an empty
            // block among them.
            // Each title comes twice, with an empty block and blocks
            // naming no title among them, and once an empty title.
            let block = match (i, i % 3) {
                (4, _) => vec![0],
                (10, _) => icy_block("StreamTitle='';"),
                (_, 1) => icy_block("StreamUrl='http://example.com';"),
                _ => icy_block(&format!("StreamTitle='Song {}';StreamUrl='';", i / 3)),
            };
            if stopped.load(Ordering::Acquire)
                || conn.write_all(&audio).is_err()
                || conn.write_all(&block).is_err()
            {
                return;
            }
        }
    });
    let slot = Arc::new(TitleSlot::default());
    let opts = HttpOptions { titles: Some(slot.clone()), ..live() };
    let mut source = HttpSource::open(&server.url("/radio"), opts).unwrap();
    assert!(!source.is_seekable());
    assert_eq!(source.byte_len(), None);
    let (audio, titles) = read_live(&mut source, &slot, 160_000);
    let expected: Vec<u8> = (0..160_000).map(on_air).collect();
    assert!(audio == expected, "the audio came through changed");
    // The empty title clears the one before, so the same one counts again.
    assert_eq!(titles, ["Song 0", "Song 1", "Song 2", "Song 3", "", "Song 3", "Song 4", "Song 5", "Song 6"]);
    let asked = &server.asked()[0];
    assert_eq!(asked.header("icy-metadata"), Some("1"));
    assert_eq!(asked.header("range"), None, "a live stream asks for no range");
}

#[test]
fn a_live_stream_that_drops_reconnects_and_skips_nothing() {
    use crate::testing::http_server::ScriptServer;
    use std::sync::atomic::AtomicU64;
    // Every connection gets 50 000 bytes from wherever the broadcast is,
    // then the server hangs up.
    let air = Arc::new(AtomicU64::new(0));
    let clock = air.clone();
    let server = ScriptServer::start(move |_, conn, _| {
        let head = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 999999999\r\nConnection: close\r\n\r\n";
        if conn.write_all(head.as_bytes()).is_err() {
            return;
        }
        let from = clock.fetch_add(50_000, Ordering::SeqCst);
        let bytes: Vec<u8> = (from..from + 50_000).map(on_air).collect();
        let _ = conn.write_all(&bytes);
    });
    let slot = TitleSlot::default();
    let mut source = HttpSource::open(&server.url("/radio"), live()).unwrap();
    // The length the server claims is no length for a live stream.
    assert_eq!(source.byte_len(), None);
    let (audio, _) = read_live(&mut source, &slot, 180_000);
    let expected: Vec<u8> = (0..180_000).map(on_air).collect();
    assert!(audio == expected, "bytes were lost or doubled across the reconnections");
    let asked = server.asked();
    assert!(asked.len() >= 4, "{}", asked.len());
    assert!(asked.iter().all(|a| a.header("range").is_none()));
}

#[test]
fn a_live_server_answering_200_after_a_stall_is_read_from_its_first_byte() {
    // A server that ignores ranges and stalls on the first connection.
    let body = data(300_000);
    let server = TestServer::start(
        body.clone(),
        Behaviour {
            ranges: false,
            stall_after: Some(100_000),
            content_type: "audio/mpeg",
            ..Default::default()
        },
    );
    // A stall is judged by time. A busy machine can take longer than a short
    // stall timeout to answer a new connection, which then counts as a
    // stall too and is dropped for another; and a live stream asked again
    // starts over, so the bytes would no longer follow on. The first
    // connection stays quiet until the server is dropped, so any timeout
    // finds it; this one is well past a busy machine's pause.
    let opts = HttpOptions { stall_timeout: Duration::from_secs(2), ..live() };
    let mut source = HttpSource::open(&server.url(), opts).unwrap();
    let slot = TitleSlot::default();
    let (audio, _) = read_live(&mut source, &slot, 400_000);
    // The new connection's bytes follow straight on, none thrown away.
    let mut expected = body[..100_000].to_vec();
    expected.extend_from_slice(&body);
    assert!(audio == expected, "the new connection's start was skipped");
    assert!(server.ranges_asked().is_empty(), "{:?}", server.ranges_asked());
}

#[test]
fn a_live_stream_that_stays_gone_fails_clearly() {
    use crate::testing::http_server::ScriptServer;
    let answers = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let server = ScriptServer::start(move |_, conn, _| {
        // The first answer has a little sound; every later one is empty.
        let first = answers.fetch_add(1, Ordering::SeqCst) == 0;
        let body = if first { vec![1u8; 1000] } else { Vec::new() };
        let _ = conn.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\n\r\n");
        let _ = conn.write_all(&body);
    });
    let started = Instant::now();
    let mut source = HttpSource::open(&server.url("/radio"), live()).unwrap();
    let mut out = Vec::new();
    let err = source.read_to_end(&mut out).unwrap_err();
    assert_eq!(out.len(), 1000);
    assert!(err.to_string().contains("did not come back"), "{err}");
    // Four tries after the first, waiting 10, 20, 40 and 80 ms.
    assert_eq!(server.asked().len(), 5);
    assert!(started.elapsed() >= Duration::from_millis(150));
}

#[test]
fn an_old_shoutcast_server_answering_icy_200_ok_plays() {
    use crate::testing::http_server::ScriptServer;
    let server = ScriptServer::start(|_, conn, stopped| {
        // The status line in pieces, the way a slow server may send it.
        for piece in [&b"IC"[..], b"Y 2", b"00 OK\r\n"] {
            if conn.write_all(piece).is_err() || conn.flush().is_err() {
                return;
            }
            thread::sleep(Duration::from_millis(20));
        }
        let head = "icy-notice1:This stream requires Winamp\r\nicy-name:Old FM\r\ncontent-type:audio/mpeg\r\nicy-metaint:8000\r\n\r\n";
        if conn.write_all(head.as_bytes()).is_err() {
            return;
        }
        for i in 0u64.. {
            let audio: Vec<u8> = (i * 8000..(i + 1) * 8000).map(on_air).collect();
            let block = icy_block(&format!("StreamTitle='Old FM {}';", i / 4));
            if stopped.load(Ordering::Acquire)
                || conn.write_all(&audio).is_err()
                || conn.write_all(&block).is_err()
            {
                return;
            }
        }
    });
    let slot = Arc::new(TitleSlot::default());
    let opts = HttpOptions { titles: Some(slot.clone()), ..live() };
    let mut source = HttpSource::open(&server.url("/;stream.mp3"), opts).unwrap();
    assert_eq!(source.content_type(), Some("audio/mpeg"));
    let (audio, titles) = read_live(&mut source, &slot, 100_000);
    let expected: Vec<u8> = (0..100_000).map(on_air).collect();
    assert!(audio == expected, "the audio came through changed");
    assert_eq!(titles, ["Old FM 0", "Old FM 1", "Old FM 2"]);
}

#[test]
fn a_live_server_that_hangs_up_after_a_little_each_time_counts_as_gone() {
    use crate::testing::http_server::ScriptServer;
    // Every connection gives 1 000 bytes and hangs up.
    let server = ScriptServer::start(|_, conn, _| {
        let _ = conn.write_all(
            b"HTTP/1.1 200 OK
Content-Type: audio/mpeg

",
        );
        let _ = conn.write_all(&[7u8; 1000]);
    });
    let mut source = HttpSource::open(&server.url("/radio"), live()).unwrap();
    let mut out = Vec::new();
    let err = source.read_to_end(&mut out).unwrap_err();
    assert!(err.to_string().contains("did not come back"), "{err}");
    // The first connection and four more, none of them counted as working.
    assert_eq!(server.asked().len(), 5);
    assert_eq!(out.len(), 5_000);
}

// What a song behind a gateway is asked with: the gateway's sign-in, a
// Cloudflare service token's id and a header of the server's own.
fn gateway_headers() -> Vec<(String, String)> {
    let pairs = [
        ("Authorization", "Basic YWxpY2U6cGFzcw=="),
        ("CF-Access-Client-Id", "id.access"),
        ("X-Octo-Key", "key"),
    ];
    pairs.into_iter().map(|(name, value)| (name.to_string(), value.to_string())).collect()
}

// Sends the request on to `to`. Like every scripted answer with a length it
// says `Connection: close` (see `ScriptServer`).
fn redirect(conn: &mut std::net::TcpStream, to: &str) {
    let head =
        format!("HTTP/1.1 302 Found\r\nLocation: {to}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    let _ = conn.write_all(head.as_bytes());
}

// Answers with `body` from where the request's range starts, as a server
// with ranges does.
fn song(conn: &mut std::net::TcpStream, asked: &crate::testing::http_server::Asked, body: &[u8]) {
    let range = asked.header("range").and_then(|r| r.strip_prefix("bytes=")?.strip_suffix('-'));
    let from = range.and_then(|r| r.parse::<usize>().ok()).unwrap_or(0).min(body.len());
    let (len, last, sent) = (body.len(), body.len() - 1, body.len() - from);
    let _ = write!(conn, "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {from}-{last}/{len}\r\n");
    let _ = write!(conn, "Content-Length: {sent}\r\nConnection: close\r\n\r\n");
    let _ = conn.write_all(&body[from..]);
}

// A gateway's sign-in page, sent with this content type.
fn login_page(conn: &mut std::net::TcpStream, content_type: &str) {
    let body = "<!doctype html><title>Sign in</title><form method=post></form>";
    let len = body.len();
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {len}\r\nConnection: close\r\n\r\n"
    );
    let _ = conn.write_all(head.as_bytes());
    let _ = conn.write_all(body.as_bytes());
}

// A server that sends "/hop/0" on through `hops` redirects, one at a time,
// before it answers with the song.
fn hops_server(hops: u32, body: Vec<u8>) -> crate::testing::http_server::ScriptServer {
    crate::testing::http_server::ScriptServer::start(move |asked, conn, _| {
        let n = asked.target.strip_prefix("/hop/").and_then(|n| n.parse::<u32>().ok());
        match n {
            Some(n) if n < hops => redirect(conn, &format!("/hop/{}", n + 1)),
            _ => song(conn, asked, &body),
        }
    })
}

// Red when: `.max_redirects(0)` is removed (ureq follows and drops
// Authorization on the hop), or `follow` sends `headers` on the first hop only.
#[test]
fn a_redirect_on_the_same_server_keeps_every_header_and_a_seek_starts_at_the_song_again() {
    use crate::testing::http_server::ScriptServer;
    let body = data(3_000_000);
    let file = body.clone();
    let server = ScriptServer::start(move |asked, conn, _| match asked.target.as_str() {
        "/rest/stream?id=1" => redirect(conn, "/files/song.flac"),
        "/files/song.flac" => song(conn, asked, &file),
        _ => {
            let _ =
                conn.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }
    });
    let opts = HttpOptions { headers: gateway_headers(), ..quick() };
    let mut source = HttpSource::open(&server.url("/rest/stream?id=1"), opts).unwrap();
    let mut buf = vec![0u8; 1000];
    source.read_exact(&mut buf).unwrap();
    // Far ahead: a new request, which starts at the song's own address.
    source.seek(SeekFrom::Start(2_500_000)).unwrap();
    source.read_exact(&mut buf).unwrap();
    assert_eq!(buf, body[2_500_000..2_501_000]);
    let asked = server.asked();
    for hop in &asked {
        for (name, value) in gateway_headers() {
            assert_eq!(hop.header(&name), Some(value.as_str()), "{name} left off {}", hop.target);
        }
        assert!(hop.header("range").is_some(), "no range asked of {}", hop.target);
    }
    let seek = asked.iter().filter(|a| a.header("range") == Some("bytes=2500000-"));
    let again: Vec<&str> = seek.map(|a| a.target.as_str()).collect();
    // A reconnect on a busy machine asks again, the same way.
    assert!(!again.is_empty() && again.len().is_multiple_of(2), "{again:?}");
    for pair in again.chunks(2) {
        assert_eq!(pair, ["/rest/stream?id=1", "/files/song.flac"]);
    }
}

// Red when: `.max_redirects(0)` is removed (ureq keeps every header but
// Authorization across hosts), or `origin` leaves out the port.
#[test]
fn a_redirect_to_another_server_takes_none_of_the_headers_but_the_range() {
    use crate::testing::http_server::ScriptServer;
    let body = data(100_000);
    let file = body.clone();
    let other = ScriptServer::start(move |asked, conn, _| song(conn, asked, &file));
    let there = other.url("/song.flac");
    let server = ScriptServer::start(move |_, conn, _| redirect(conn, &there));
    let opts = HttpOptions { headers: gateway_headers(), ..quick() };
    let mut source = HttpSource::open(&server.url("/rest/stream"), opts).unwrap();
    assert!(read_all(&mut source) == body, "the song came through changed");
    let first = &server.asked()[0];
    assert_eq!(first.header("authorization"), Some("Basic YWxpY2U6cGFzcw=="));
    // The same host on another port is another server.
    let hop = &other.asked()[0];
    for (name, _) in gateway_headers() {
        assert_eq!(hop.header(&name), None, "{name} went to another server");
    }
    assert_eq!(hop.header("range"), Some("bytes=0-"));
}

// Red when: `redirect_target` follows only plain http (`to.starts_with("http://")`
// in place of `is_remote`).
#[test]
fn a_redirect_from_http_to_https_still_plays() {
    use crate::testing::http_server::ScriptServer;
    let body = data(100_000);
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let secure = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
    let there = secure.url();
    let server = ScriptServer::start(move |_, conn, _| redirect(conn, &there));
    let opts = trusting(&[("127.0.0.1", &certificate.fingerprint())]);
    let mut source = HttpSource::open(&server.url("/rest/stream"), opts).unwrap();
    assert!(read_all(&mut source) == body, "the song came through changed");
    assert_eq!(secure.ranges_asked(), [0u64]);
}

// Red when: MOST_REDIRECTS is 9 or 11, or the check reads `hops > MOST_REDIRECTS`.
#[test]
fn ten_redirects_are_followed_and_an_eleventh_is_refused() {
    let body = data(10_000);
    let ten = hops_server(10, body.clone());
    let mut source = HttpSource::open(&ten.url("/hop/0"), quick()).unwrap();
    assert!(read_all(&mut source) == body, "the song came through changed");
    assert_eq!(ten.asked().len(), 11);
    let eleven = hops_server(11, body);
    let err = HttpSource::open(&eleven.url("/hop/0"), quick()).err().unwrap();
    assert_eq!((err.kind, err.status), (ErrorKind::Http, Some(302)));
    assert!(err.message.contains("too many redirects"), "{err}");
    assert_eq!(eleven.asked().len(), 11, "the eleventh redirect was followed");
}

// Red when: the `is_remote` check in `redirect_target` is removed (ftp and
// javascript: are tried), `has_scheme` goes back to `contains("://")`
// (javascript: is read as a path and followed), or a missing Location
// falls back to "/".
#[test]
fn a_redirect_to_nowhere_or_away_from_http_is_refused() {
    use crate::testing::http_server::ScriptServer;
    for location in [None, Some("ftp://files.example.com/song.flac"), Some("javascript:alert(1)")] {
        let server = ScriptServer::start(move |_, conn, _| match location {
            Some(to) => redirect(conn, to),
            None => {
                let _ =
                    conn.write_all(b"HTTP/1.1 302 Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            }
        });
        let err = HttpSource::open(&server.url("/rest/stream"), quick()).err().unwrap();
        assert_eq!((err.kind, err.status), (ErrorKind::Http, Some(302)), "{location:?}");
        assert_eq!(server.asked().len(), 1, "{location:?}");
    }
}

// Red when: the `!live && is_html` check in `HttpSource::open` is removed, or
// `is_html` compares the whole header (the charset case then passes as audio).
#[test]
fn a_sign_in_page_in_place_of_a_song_fails_at_once() {
    use crate::testing::http_server::ScriptServer;
    // A gateway sends the song's address on to its sign-in page.
    let server = ScriptServer::start(|asked, conn, _| match asked.target.as_str() {
        "/rest/stream" => redirect(conn, "/if/flow/default-authentication-flow/"),
        _ => login_page(conn, "text/html; charset=utf-8"),
    });
    let err = HttpSource::open(&server.url("/rest/stream"), quick()).err().unwrap();
    assert_eq!((err.kind, err.status), (ErrorKind::SignInPage, Some(200)));
    assert!(err.message.contains("200"), "{err}");
    // The redirect and the page, and nothing asked again.
    assert_eq!(server.asked().len(), 2);
    // Answered straight away, in any case, through the way songs open.
    let direct = ScriptServer::start(|_, conn, _| login_page(conn, "Text/HTML"));
    let err = crate::source::open(&direct.url("/rest/stream"), quick()).err().unwrap();
    assert_eq!(err.kind, ErrorKind::SignInPage);
    assert_eq!(direct.asked().len(), 1);
}

// Red when: the HTML check in `HttpSource::open` drops its `!live &&`.
#[test]
fn a_live_station_answering_as_a_web_page_still_plays() {
    use crate::testing::http_server::ScriptServer;
    // Some stations label their sound text/html.
    let server = ScriptServer::start(|_, conn, _| {
        let _ = conn.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n");
        let _ = conn.write_all(&[7u8; 20_000]);
    });
    let mut source = HttpSource::open(&server.url("/radio"), live()).unwrap();
    let (audio, _) = read_live(&mut source, &TitleSlot::default(), 10_000);
    assert!(audio == [7u8; 10_000], "the station's sound came through changed");
}

// Red when: the `200 | 206 if html` arm in `open_at` is removed (the page is
// taken for the song's bytes), or `is_final` leaves out SignInPage; either
// way the stream ends as a network failure instead.
#[test]
fn a_sign_in_page_met_on_a_later_request_ends_the_song() {
    use crate::testing::http_server::ScriptServer;
    // The first answer breaks off; by the time the stream asks again, a
    // gateway wants a sign-in.
    let body = data(200_000);
    let first = body[..50_000].to_vec();
    let answers = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let server = ScriptServer::start(move |_, conn, _| {
        if answers.fetch_add(1, Ordering::SeqCst) == 0 {
            let _ = conn.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 200000\r\nConnection: close\r\n\r\n");
            let _ = conn.write_all(&first);
        } else {
            login_page(conn, "text/html; charset=utf-8");
        }
    });
    let opts = HttpOptions { max_retries: 1, ..quick() };
    let mut source = HttpSource::open(&server.url("/rest/stream"), opts).unwrap();
    let mut out = Vec::new();
    let err = source.read_to_end(&mut out).unwrap_err();
    assert!(out == body[..50_000], "the sound before the break came through changed");
    let failure = err.get_ref().and_then(|e| e.downcast_ref::<Failure>()).unwrap();
    assert_eq!((failure.kind, failure.status), (ErrorKind::SignInPage, Some(200)));
    assert_eq!(server.asked().len(), 2, "the sign-in page was asked again");
}

// Red when: `origin` leaves out the port or the scheme, or `is_html` stops
// trimming or splitting off parameters.
#[test]
fn origins_and_web_pages_are_told_apart() {
    assert_eq!(origin("https://Music.Example.com/rest"), origin("https://music.example.com:443/x"));
    assert_ne!(origin("http://music.example.com/rest"), origin("https://music.example.com/rest"));
    assert_ne!(origin("http://127.0.0.1:4533/a"), origin("http://127.0.0.1:4534/a"));
    assert!(is_html(Some("text/html; charset=utf-8")) && is_html(Some(" TEXT/HTML")));
    assert!(!is_html(Some("audio/flac")) && !is_html(Some("application/xhtml+xml")) && !is_html(None));
}

// Red when: `HttpHeader` or `HttpOptions` derives `Debug` again.
#[test]
fn header_values_never_show_in_debug_output() {
    let header = crate::api::HttpHeader { name: "Authorization".into(), value: "Basic c2VjcmV0".into() };
    let shown = format!("{header:?}");
    assert!(shown.contains("Authorization") && !shown.contains("c2VjcmV0"), "{shown}");
    let opts = HttpOptions { headers: vec![("CF-Access-Client-Secret".into(), "s3cret".into())], ..quick() };
    let shown = format!("{opts:?}");
    assert!(shown.contains("CF-Access-Client-Secret") && !shown.contains("s3cret"), "{shown}");
}

// Red when: any log line in the engine formats a value with Debug, as
// `log::debug!("{item:?}")` would (a queue item's Debug holds its signed
// address).
#[test]
fn no_log_line_formats_a_value_with_debug() {
    const LEVELS: [&str; 5] = ["trace", "debug", "info", "warn", "error"];
    let mut dirs = vec![std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src")];
    let mut found = Vec::new();
    while let Some(dir) = dirs.pop() {
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                dirs.push(path);
                continue;
            }
            if path.extension().and_then(|e| e.to_str()) != Some("rs") {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            for (at, _) in text.match_indices("log::") {
                let line_start = text[..at].rfind('\n').map_or(0, |i| i + 1);
                if text[line_start..at].trim_start().starts_with("//") {
                    continue;
                }
                let call = &text[at..];
                let Some(open) = call.find("!(") else { continue };
                if !LEVELS.contains(&&call[5..open]) {
                    continue;
                }
                let line = &call[..call.find(");").unwrap_or(call.len())];
                if line.contains(":?") {
                    found.push(format!("{}: {line}", path.display()));
                }
            }
        }
    }
    assert!(found.is_empty(), "log lines formatting a value with Debug:\n{}", found.join("\n"));
}

// Every http address goes to `plain` and every https one to `secure`, on
// this machine, whatever host and port it names: the test servers then
// stand in at the ports a real server uses, 80 and 443.
#[derive(Debug)]
struct Ports {
    plain: u16,
    secure: u16,
}

impl Resolver for Ports {
    fn resolve(
        &self,
        uri: &ureq::http::Uri,
        _config: &ureq::config::Config,
        _timeout: NextTimeout,
    ) -> Result<ResolvedSocketAddrs, ureq::Error> {
        let port = if uri.scheme_str() == Some("https") { self.secure } else { self.plain };
        let mut found = self.empty();
        found.push(std::net::SocketAddr::from(([127, 0, 0, 1], port)));
        Ok(found)
    }
}

// The port a test server listens on, from its address.
fn port_of(url: &str) -> u16 {
    url.parse::<ureq::http::Uri>().unwrap().port_u16().unwrap()
}

// Options sending the gateway's headers through a client that finds every
// address with `ports` and trusts `certificate` for these hosts.
fn through(ports: Ports, certificate: &SelfSigned, hosts: &[&str]) -> HttpOptions {
    let trust = Arc::new(Trust::default());
    trust.replace(hosts.iter().map(|host| (host.to_string(), certificate.fingerprint())));
    HttpOptions { agent: agent_with_parts(trust, IDLE_LIMIT, ports), headers: gateway_headers(), ..quick() }
}

// A refusal with this status, these header lines and this body.
fn refused_with(conn: &mut std::net::TcpStream, status: u16, lines: &[&str], body: &str) {
    let _ = write!(conn, "HTTP/1.1 {status} Refused\r\n");
    for line in lines {
        let _ = write!(conn, "{line}\r\n");
    }
    let _ = write!(conn, "Content-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
}

// A refusal with this status, content type and body.
fn refusal(conn: &mut std::net::TcpStream, status: u16, content_type: &str, body: &str) {
    refused_with(conn, status, &[format!("Content-Type: {content_type}").as_str()], body);
}

// A gateway's sign-in page.
const SIGN_IN: &str = "<!doctype html><title>Sign in</title><form method=post></form>";

// What opening a song refused this way fails as, and how many requests it
// took.
fn refused_as(
    status: u16,
    lines: &'static [&'static str],
    body: &'static str,
    opts: HttpOptions,
) -> (ErrorKind, Option<u16>, usize) {
    use crate::testing::http_server::ScriptServer;
    let server = ScriptServer::start(move |_, conn, _| refused_with(conn, status, lines, body));
    let err = HttpSource::open(&server.url("/rest/stream"), opts).err().unwrap();
    (err.kind, err.status, server.asked().len())
}

// Red when: `follow` goes back to comparing origins only
// (`home.is_some() && origin(&at) == home`), `same_server` becomes
// `home == to`, or its port rule is dropped.
#[test]
fn a_move_up_to_https_on_the_same_host_keeps_every_header() {
    use crate::testing::http_server::ScriptServer;
    let body = data(100_000);
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    for (from, to) in [
        // From http's own port to https's own port.
        ("http://127.0.0.1/rest/stream", "https://127.0.0.1/song.flac"),
        // From a port of the server's own to https's own port.
        ("http://127.0.0.1:4533/rest/stream", "https://127.0.0.1/song.flac"),
    ] {
        let secure = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
        let server = ScriptServer::start(move |_, conn, _| redirect(conn, to));
        let ports = Ports { plain: port_of(&server.url("/")), secure: port_of(&secure.url()) };
        let mut source = HttpSource::open(from, through(ports, &certificate, &["127.0.0.1"])).unwrap();
        assert!(read_all(&mut source) == body, "the song came through changed");
        let hop = &secure.asked()[0];
        for (name, value) in gateway_headers() {
            assert_eq!(hop.header(&name), Some(value.as_str()), "{name} left off {to} from {from}");
        }
        assert_eq!(hop.header("range"), Some("bytes=0-"));
    }
}

// Red when: `same_server` drops `host == to_host` (another host over https
// is taken for the server), or drops its port rule (`*to_port == 443`).
#[test]
fn a_move_to_https_on_another_host_or_port_takes_none_of_the_headers() {
    use crate::testing::http_server::ScriptServer;
    let body = data(100_000);
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    for (from, to) in [
        // Another host at https's own port; `Ports` sends it to this machine too.
        ("http://127.0.0.1/rest/stream", "https://127.0.0.2/song.flac"),
        // The same host, but neither port is its scheme's own.
        ("http://127.0.0.1:4533/rest/stream", "https://127.0.0.1:8443/song.flac"),
        // The same host from http's own port, but not to https's own port.
        ("http://127.0.0.1/rest/stream", "https://127.0.0.1:8443/song.flac"),
    ] {
        let secure = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
        let server = ScriptServer::start(move |_, conn, _| redirect(conn, to));
        let ports = Ports { plain: port_of(&server.url("/")), secure: port_of(&secure.url()) };
        let opts = through(ports, &certificate, &["127.0.0.1", "127.0.0.2"]);
        let mut source = HttpSource::open(from, opts).unwrap();
        assert!(read_all(&mut source) == body, "the song came through changed");
        assert_eq!(server.asked()[0].header("authorization"), Some("Basic YWxpY2U6cGFzcw=="));
        let hop = &secure.asked()[0];
        for (name, _) in gateway_headers() {
            assert_eq!(hop.header(&name), None, "{name} went to {to} from {from}");
        }
        assert_eq!(hop.header("range"), Some("bytes=0-"));
    }
}

// Red when: `same_server` loses `scheme == "http"` (https on another port
// moving to 443 keeps the headers), `to_scheme == "https"` (http on port 80
// moving to another http port keeps them), the host check or the port
// check.
#[test]
fn the_same_server_is_its_origin_or_its_host_moved_up_to_https() {
    let same = |a: &str, b: &str| same_server(&origin(a).unwrap(), &origin(b).unwrap());
    assert!(same("http://Music.Example.com/rest", "http://music.example.com:80/x"));
    assert!(same("https://music.example.com/rest", "https://music.example.com/x"));
    assert!(same("http://music.example.com/rest", "https://music.example.com/x"));
    assert!(!same("http://music.example.com/rest", "https://music.example.com:8443/x"));
    assert!(same("http://music.example.com:4533/rest", "https://music.example.com:443/x"));
    assert!(!same("http://music.example.com:4533/rest", "https://music.example.com:8443/x"));
    assert!(!same("http://music.example.com/rest", "https://cdn.example.com/x"));
    assert!(!same("http://music.example.com/rest", "http://music.example.com:8080/x"));
    assert!(!same("https://music.example.com/rest", "http://music.example.com/x"));
    assert!(!same("https://music.example.com:8443/rest", "https://music.example.com/x"));
}

// Red when: `follow` sends `secure` without the `to.0 == "https"` check (the
// item's own http address then carries the sign-in in the clear), sends it
// outside `if let Some((_, to)) = own` (another host gets it), or never sends
// it (the https hop of the server's own goes without).
#[test]
fn secure_headers_go_only_over_https_to_the_same_server() {
    use crate::testing::http_server::ScriptServer;
    let body = data(100_000);
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let own = vec![("X-Octo-Key".to_string(), "key".to_string())];
    let login = vec![("Authorization".to_string(), "Basic YWxpY2U6cGFzcw==".to_string())];
    for (to, kept) in [("https://127.0.0.1/song.flac", true), ("https://127.0.0.2/song.flac", false)] {
        let secure = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
        let server = ScriptServer::start(move |_, conn, _| redirect(conn, to));
        let ports = Ports { plain: port_of(&server.url("/")), secure: port_of(&secure.url()) };
        let opts = HttpOptions {
            headers: own.clone(),
            secure_headers: login.clone(),
            ..through(ports, &certificate, &["127.0.0.1", "127.0.0.2"])
        };
        let mut source = HttpSource::open("http://127.0.0.1/rest/stream", opts).unwrap();
        assert!(read_all(&mut source) == body, "the song came through changed");
        // The item's own http address: its own header, never the sign-in.
        let first = &server.asked()[0];
        assert_eq!(first.header("x-octo-key"), Some("key"));
        assert_eq!(first.header("authorization"), None, "the sign-in crossed plain http");
        // Its https twin gets both; another host neither.
        let hop = &secure.asked()[0];
        assert_eq!(hop.header("authorization"), kept.then_some("Basic YWxpY2U6cGFzcw=="), "{to}");
        assert_eq!(hop.header("x-octo-key"), kept.then_some("key"), "{to}");
    }
    // An https address of the server's own carries it from the first request.
    let secure = TestServer::start_tls(body.clone(), Behaviour::default(), &certificate);
    let ports = Ports { plain: 1, secure: port_of(&secure.url()) };
    let opts = HttpOptions {
        headers: Vec::new(),
        secure_headers: login,
        ..through(ports, &certificate, &["127.0.0.1"])
    };
    let mut source = HttpSource::open("https://127.0.0.1/song.flac", opts).unwrap();
    assert!(read_all(&mut source) == body, "the song came through changed");
    assert_eq!(secure.asked()[0].header("authorization"), Some("Basic YWxpY2U6cGFzcw=="));
}

// Red when: the `Debug` of `HttpOptions` shows `secure_headers` as they are,
// or leaves them out.
#[test]
fn secure_header_values_never_show_in_debug_output() {
    let login = vec![("Authorization".to_string(), "Basic c2VjcmV0".to_string())];
    let opts = HttpOptions { secure_headers: login, ..quick() };
    let shown = format!("{opts:?}");
    assert!(shown.contains("Authorization") && !shown.contains("c2VjcmV0"), "{shown}");
}

// Red when: the 401/403 arm in `HttpSource::open` is removed, or
// `refusal_is_sign_in_page` loses `is_html(media)` (the page fails as a
// plain refusal, `Http`).
#[test]
fn a_sign_in_page_refusing_the_song_fails_at_once() {
    use crate::testing::http_server::ScriptServer;
    let server = ScriptServer::start(|_, conn, _| refusal(conn, 401, "text/html; charset=utf-8", SIGN_IN));
    let err = HttpSource::open(&server.url("/rest/stream"), quick()).err().unwrap();
    assert_eq!((err.kind, err.status), (ErrorKind::SignInPage, Some(401)));
    assert!(err.message.contains("401"), "{err}");
    assert_eq!(server.asked().len(), 1, "the sign-in page was asked again");
    // The same through the way songs open, for a 403 in any case.
    let forbidden = ScriptServer::start(|_, conn, _| refusal(conn, 403, "Text/HTML", SIGN_IN));
    let err = crate::source::open(&forbidden.url("/rest/stream"), quick()).err().unwrap();
    assert_eq!((err.kind, err.status), (ErrorKind::SignInPage, Some(403)));
    assert_eq!(forbidden.asked().len(), 1, "the sign-in page was asked again");
}

// Red when: the 401/403 arm in `HttpSource::open` loses its guard, or
// `login_sent` becomes `true` (every refusal is taken for a sign-in page).
#[test]
fn a_refusal_that_is_not_a_web_page_stays_a_refusal() {
    use crate::testing::http_server::ScriptServer;
    let server = ScriptServer::start(|_, conn, _| refusal(conn, 403, "text/plain", "Forbidden"));
    let err = HttpSource::open(&server.url("/rest/stream"), quick()).err().unwrap();
    assert_eq!((err.kind, err.status), (ErrorKind::Http, Some(403)));
    assert_eq!(server.asked().len(), 1);
}

// Red when: the 401/403 guard in `HttpSource::open` loses `!live &&` (a
// station's own refusal is named a sign-in page).
#[test]
fn a_live_station_refusing_with_a_web_page_stays_a_refusal() {
    use crate::testing::http_server::ScriptServer;
    let server = ScriptServer::start(|_, conn, _| refusal(conn, 401, "text/html", SIGN_IN));
    let err = HttpSource::open(&server.url("/radio"), live()).err().unwrap();
    assert_eq!((err.kind, err.status), (ErrorKind::Http, Some(401)));
}

// Red when: `basic` is left out of `refusal_is_sign_in_page`, loses its
// `status == 401`, or reads the challenge's case as it is.
#[test]
fn a_basic_challenge_on_a_401_is_a_sign_in_page() {
    let basic = &["WWW-Authenticate: BASIC realm=\"Sign in\""];
    assert_eq!(refused_as(401, basic, "", quick()), (ErrorKind::SignInPage, Some(401), 1));
    assert_eq!(refused_as(403, basic, "", quick()), (ErrorKind::Http, Some(403), 1));
    let bearer = &["WWW-Authenticate: Bearer realm=\"api\""];
    assert_eq!(refused_as(401, bearer, "", quick()), (ErrorKind::Http, Some(401), 1));
}

// Red when: `headers.contains_key("cf-access-aud")` is left out.
#[test]
fn a_cloudflare_access_audience_header_is_a_sign_in_page() {
    let aud = &["CF-Access-Aud: 5a1b2c3d"];
    assert_eq!(refused_as(403, aud, "", quick()), (ErrorKind::SignInPage, Some(403), 1));
}

// Red when: `headers.contains_key("cf-access-domain")` is left out.
#[test]
fn a_cloudflare_access_domain_header_is_a_sign_in_page() {
    let domain = &["Cf-Access-Domain: team.cloudflareaccess.com"];
    assert_eq!(refused_as(401, domain, "", quick()), (ErrorKind::SignInPage, Some(401), 1));
}

// Red when: `back` is left out of `sign_in_address`, or needs only one of
// `rd` and `rm`.
#[test]
fn an_address_that_comes_back_after_a_sign_in_is_a_sign_in_page() {
    let back = &["Location: https://auth.example.com/?rd=https%3A%2F%2Fmusic.example.com%2F&rm=GET"];
    assert_eq!(refused_as(401, back, "", quick()), (ErrorKind::SignInPage, Some(401), 1));
    let half = &["Location: https://music.example.com/rest/stream?rd=1"];
    assert_eq!(refused_as(401, half, "", quick()), (ErrorKind::Http, Some(401), 1));
}

// Red when: `sign_in_address` stops looking for `/outpost.goauthentik.io/`.
#[test]
fn an_authentik_outpost_address_is_a_sign_in_page() {
    let outpost = &["Location: /outpost.goauthentik.io/start?rd=%2Frest%2Fstream"];
    assert_eq!(refused_as(401, outpost, "", quick()), (ErrorKind::SignInPage, Some(401), 1));
}

// Red when: `sign_in_address` stops looking for `/cdn-cgi/access/`.
#[test]
fn a_cloudflare_access_sign_in_path_is_a_sign_in_page() {
    let path = &["Location: /cdn-cgi/access/login/music.example.com"];
    assert_eq!(refused_as(403, path, "", quick()), (ErrorKind::SignInPage, Some(403), 1));
}

// Red when: `sign_in_address` stops reading the host, reads its case as it
// is, or checks `contains` in place of `ends_with`.
#[test]
fn a_cloudflare_access_host_is_a_sign_in_page() {
    let host = &["Location: https://team.CloudflareAccess.com/login"];
    assert_eq!(refused_as(403, host, "", quick()), (ErrorKind::SignInPage, Some(403), 1));
    let other = &["Location: https://team.cloudflareaccess.com.example.net/login"];
    assert_eq!(refused_as(403, other, "", quick()), (ErrorKind::Http, Some(403), 1));
}

// Red when: `is_gateway_cookie` stops matching names that start
// `authentik_proxy`.
#[test]
fn an_authentik_session_cookie_is_a_sign_in_page() {
    let cookie = &["Set-Cookie: authentik_proxy_5f2e=abc; Path=/; HttpOnly"];
    assert_eq!(refused_as(401, cookie, "", quick()), (ErrorKind::SignInPage, Some(401), 1));
}

// Red when: `is_gateway_cookie` stops matching `CF_Authorization`.
#[test]
fn a_cloudflare_access_session_cookie_is_a_sign_in_page() {
    let cookie = &["Set-Cookie: CF_Authorization=eyJhbGciOi; Path=/; Secure"];
    assert_eq!(refused_as(403, cookie, "", quick()), (ErrorKind::SignInPage, Some(403), 1));
}

// Red when: `is_gateway_cookie` stops matching `authelia_session`, or the
// cookie check takes any cookie at all.
#[test]
fn an_authelia_session_cookie_is_a_sign_in_page() {
    let cookie = &["Set-Cookie: authelia_session=abc; Path=/"];
    assert_eq!(refused_as(401, cookie, "", quick()), (ErrorKind::SignInPage, Some(401), 1));
    let own = &["Set-Cookie: navidrome_session=abc; Path=/"];
    assert_eq!(refused_as(401, own, "", quick()), (ErrorKind::Http, Some(401), 1));
}

// Red when: `login_sent` is left out of `refusal_is_sign_in_page`,
// `sends_login` misses one of its names or reads their case as it is, or
// `is_json` stops dropping the type's parameters (the JSON refusal is then
// named a sign-in page).
#[test]
fn a_refusal_of_a_sign_in_that_was_sent_is_a_sign_in_page_unless_it_is_json() {
    for name in ["authorization", "CF-Access-Client-Id", "cf-access-client-secret"] {
        let opts = HttpOptions { headers: vec![(name.to_string(), "x".to_string())], ..quick() };
        let plain = refused_as(401, &["Content-Type: text/plain"], "Unauthorized", opts.clone());
        assert_eq!(plain, (ErrorKind::SignInPage, Some(401), 1), "{name}");
        let json = &["Content-Type: application/json; charset=utf-8"];
        let refused = refused_as(403, json, "{\"error\":\"denied\"}", opts);
        assert_eq!(refused, (ErrorKind::Http, Some(403), 1), "{name}");
    }
    // The server's own refusal of a request that carried no sign-in.
    let plain = refused_as(401, &["Content-Type: text/plain"], "Unauthorized", quick());
    assert_eq!(plain, (ErrorKind::Http, Some(401), 1));
}

// Red when: the `is_json(media)` early return in `refusal_is_sign_in_page`
// is left out or moved after a marker, or `is_json` misses `text/json` or
// "+json" types (a JSON refusal with a gateway's cookie is then named a
// sign-in page).
#[test]
fn a_json_refusal_is_the_servers_own_whatever_markers_it_carries() {
    let login = HttpOptions { headers: vec![("Authorization".into(), "x".into())], ..quick() };
    let body = "{\"error\":\"denied\"}";
    let cookie = &["Content-Type: application/json", "Set-Cookie: authentik_proxy_x=1"];
    assert_eq!(refused_as(403, cookie, body, login.clone()), (ErrorKind::Http, Some(403), 1));
    let text = &["Content-Type: Text/JSON; charset=utf-8", "CF-Access-Aud: 5a1b2c3d"];
    assert_eq!(refused_as(403, text, body, login.clone()), (ErrorKind::Http, Some(403), 1));
    let problem = &["Content-Type: application/problem+json", "WWW-Authenticate: Basic realm=\"x\""];
    assert_eq!(refused_as(401, problem, body, login), (ErrorKind::Http, Some(401), 1));
    // The same cookie on a web page is still a gateway's.
    let page = &["Content-Type: text/html", "Set-Cookie: authentik_proxy_x=1"];
    assert_eq!(refused_as(403, page, SIGN_IN, quick()), (ErrorKind::SignInPage, Some(403), 1));
}

// Red when: `sends_login` reads the secure headers on an http address (they
// never go there) or leaves them out on an https one.
#[test]
fn a_sign_in_counts_as_sent_where_it_goes_on_the_first_request() {
    let login = vec![("Authorization".to_string(), "Basic YWxpY2U6cGFzcw==".to_string())];
    let secure = HttpOptions { secure_headers: login.clone(), ..quick() };
    assert!(sends_login("https://music.example.com/rest/stream", &secure));
    assert!(!sends_login("http://music.example.com/rest/stream", &secure));
    let own = HttpOptions { headers: login, ..quick() };
    assert!(sends_login("http://music.example.com/rest/stream", &own));
    let key = HttpOptions { headers: vec![("X-Octo-Key".into(), "key".into())], ..quick() };
    assert!(!sends_login("https://music.example.com/rest/stream", &key));
}

// Red when: the 401/403 arm in `open_at` is removed, or judges by `html`
// alone (the Basic challenge then ends the song as `Http`).
#[test]
fn a_sign_in_page_refusing_a_later_request_ends_the_song() {
    use crate::testing::http_server::ScriptServer;
    type Refuse = fn(&mut std::net::TcpStream);
    let refusals: [(u16, Refuse); 2] = [
        (403, |conn| refusal(conn, 403, "text/html; charset=utf-8", SIGN_IN)),
        (401, |conn| refused_with(conn, 401, &["WWW-Authenticate: Basic realm=\"Sign in\""], "")),
    ];
    for (status, refuse) in refusals {
        // The first answer breaks off; by the time the stream asks again, a
        // gateway's session has run out and it refuses.
        let body = data(200_000);
        let first = body[..50_000].to_vec();
        let answers = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let server = ScriptServer::start(move |_, conn, _| {
            if answers.fetch_add(1, Ordering::SeqCst) == 0 {
                let _ =
                    conn.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 200000\r\nConnection: close\r\n\r\n");
                let _ = conn.write_all(&first);
            } else {
                refuse(conn);
            }
        });
        let opts = HttpOptions { max_retries: 1, ..quick() };
        let mut source = HttpSource::open(&server.url("/rest/stream"), opts).unwrap();
        let mut out = Vec::new();
        let err = source.read_to_end(&mut out).unwrap_err();
        assert!(out == body[..50_000], "the sound before the break came through changed");
        let failure = err.get_ref().and_then(|e| e.downcast_ref::<Failure>()).unwrap();
        assert_eq!((failure.kind, failure.status), (ErrorKind::SignInPage, Some(status)));
        assert_eq!(server.asked().len(), 2, "the sign-in page was asked again");
    }
}

// Trust in `certificate` for 127.0.0.1, as a pin.
fn pinned(certificate: &SelfSigned) -> Arc<Trust> {
    let trust = Arc::new(Trust::default());
    trust.replace([("127.0.0.1".to_string(), certificate.fingerprint())]);
    trust
}

// Asks for `url` and reads the whole answer, so the connection goes back to
// the client's pool; whether an answer came.
fn fetched(agent: &ureq::Agent, url: &str) -> bool {
    match agent.get(url).call() {
        Ok(mut answer) => answer.body_mut().read_to_vec().is_ok(),
        Err(_) => false,
    }
}

// `client`'s certificate, offered to these origins.
fn identity_for(client: &ClientIdentity, origins: &[(&str, &str, u16)]) -> Identity {
    let origins = origins.iter().map(|(scheme, host, port)| (scheme.to_string(), host.to_string(), *port));
    Identity::new(origins, client.chain.clone(), client.key.clone()).unwrap()
}

// Red when: `connect` offers the identity without matching the origin (the
// other port, host or scheme then gets it), `tls_origin` leaves out https's
// own port (the address with no port is refused), or the identified
// settings are made without the identity. On TLS 1.2 and 1.3.
#[test]
fn a_client_certificate_goes_only_to_its_own_origins() {
    let ca = ClientCa::generate();
    let client = ca.issue("listener", &rcgen::PKCS_ECDSA_P256_SHA256);
    let leaf = Some(client.chain[0].clone());
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let clients = [ca.der.clone()];
    for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
        let server = ClientAuthServer::start(b"song".to_vec(), &certificate, Some(&clients), version);
        let (port, url) = (server.port(), server.url());
        let other = port.wrapping_add(1);
        let trust = pinned(&certificate);
        let direct = agent(trust.clone());
        // Every https address reaches the server, whatever port it names.
        let ported = agent_with_parts(trust.clone(), IDLE_LIMIT, Ports { plain: 1, secure: port });
        let offered = |agent: &ureq::Agent, url: &str, origin: (&str, &str, u16)| {
            trust.set_identity(Some(identity_for(&client, &[origin])));
            fetched(agent, url)
        };
        assert!(!fetched(&direct, &url), "none set {version:?}");
        assert!(offered(&direct, &url, ("https", "127.0.0.1", port)), "own origin {version:?}");
        assert!(!offered(&direct, &url, ("https", "127.0.0.1", other)), "another port {version:?}");
        assert!(!offered(&direct, &url, ("https", "localhost", port)), "another host {version:?}");
        assert!(!offered(&direct, &url, ("http", "127.0.0.1", port)), "another scheme {version:?}");
        let unported = "https://127.0.0.1/song.flac";
        assert!(offered(&ported, unported, ("https", "127.0.0.1", 443)), "port 443 {version:?}");
        assert!(!offered(&ported, unported, ("https", "127.0.0.1", port)), "port reached {version:?}");
        let leaves: Vec<_> = server.seen().into_iter().map(|seen| seen.leaf).collect();
        assert_eq!(leaves, [leaf.clone(), leaf.clone()], "{version:?}");
    }
}

// Red when: `TlsTransport::is_open` stops checking the trust's generation,
// or `set_identity` stops moving it: the kept connection then carries the
// old certificate on. On TLS 1.2 and 1.3.
#[test]
fn a_changed_client_certificate_is_shown_on_the_next_request() {
    let ca = ClientCa::generate();
    let a = ca.issue("a", &rcgen::PKCS_ECDSA_P256_SHA256);
    let b = ca.issue("b", &rcgen::PKCS_ECDSA_P384_SHA384);
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let clients = [ca.der.clone()];
    for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
        let server = ClientAuthServer::start(b"song".to_vec(), &certificate, Some(&clients), version);
        let mine = [("https", "127.0.0.1", server.port())];
        let trust = pinned(&certificate);
        let fetcher = agent(trust.clone());
        trust.set_identity(Some(identity_for(&a, &mine)));
        // Nothing changed: the second request goes on the first's connection.
        assert!(fetched(&fetcher, &server.url()) && fetched(&fetcher, &server.url()), "{version:?}");
        // Changed: the very next request goes on a new connection, with b.
        trust.set_identity(Some(identity_for(&b, &mine)));
        assert!(fetched(&fetcher, &server.url()), "{version:?}");
        let seen = server.seen();
        let connections: Vec<usize> = seen.iter().map(|s| s.connection).collect();
        assert_eq!(connections, [0, 0, 1], "{version:?}");
        let leaves: Vec<_> = seen.into_iter().map(|s| s.leaf).collect();
        let (shown_a, shown_b) = (Some(a.chain[0].clone()), Some(b.chain[0].clone()));
        assert_eq!(leaves, [shown_a.clone(), shown_a, shown_b], "{version:?}");
        // Taken away: none is shown, and the server refuses.
        trust.set_identity(None);
        assert!(!fetched(&fetcher, &server.url()), "{version:?}");
        assert_eq!(server.seen().len(), 3, "{version:?}");
    }
}

// Red when: `TlsTransport::is_open` stops checking the trust's generation:
// a connection kept from before a pin was taken away is used again.
#[test]
fn a_kept_connection_is_not_used_again_once_its_pin_is_taken_away() {
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    let server = ClientAuthServer::start(b"song".to_vec(), &certificate, None, &rustls::version::TLS13);
    let trust = pinned(&certificate);
    let fetcher = agent(trust.clone());
    assert!(fetched(&fetcher, &server.url()) && fetched(&fetcher, &server.url()));
    let connections: Vec<usize> = server.seen().iter().map(|s| s.connection).collect();
    assert_eq!(connections, [0, 0], "the connection was not kept");
    trust.replace([]);
    assert!(!fetched(&fetcher, &server.url()), "a connection the pin let in was used again");
    assert_eq!(server.seen().len(), 2);
}

// Red when: `ClientCertificate` or `Identity` derives `Debug`, or the one
// written by hand shows the key.
#[test]
fn a_client_certificates_key_never_shows_in_debug_output() {
    let client = ClientCa::generate().issue("listener", &rcgen::PKCS_ECDSA_P256_SHA256);
    // Six bytes from the private scalar, as `Debug` would list them.
    let secret: Vec<String> = client.key[40..46].iter().map(u8::to_string).collect();
    let secret = secret.join(", ");
    let origin = crate::api::Origin { scheme: "https".into(), host: "music.example.com".into(), port: 443 };
    let given = crate::api::ClientCertificate {
        origins: vec![origin],
        chain: client.chain.clone(),
        key: client.key.clone(),
    };
    let shown = format!("{given:?}");
    assert!(shown.contains("music.example.com") && !shown.contains(&secret), "{shown}");
    let trust = Trust::default();
    trust.set_identity(Some(identity_for(&client, &[("https", "music.example.com", 443)])));
    let shown = format!("{trust:?}");
    assert!(shown.contains("music.example.com") && !shown.contains(&secret), "{shown}");
}

// The keys the desktop app makes from keys the JDK opened (its engineKeyOf,
// checked by EngineKeyTest), with their certificates, as golden files.
fn golden(name: &str) -> (Vec<u8>, Vec<u8>) {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../fixtures/gateway/certs/engine");
    let read = |file: String| std::fs::read(dir.join(&file)).unwrap_or_else(|e| panic!("{file}: {e}"));
    (read(format!("{name}.leaf.der")), read(format!("{name}.key.der")))
}

// Red when: the engine stops taking a key in the form the desktop app makes
// it (`identity_problem` refuses it, or no certificate is shown in the
// handshake), for any of the four kinds, on TLS 1.2 or 1.3.
#[test]
fn keys_the_desktop_app_makes_from_jdk_keys_are_shown_to_their_server() {
    let certificate = SelfSigned::new(&["127.0.0.1"]);
    for name in ["rsa-2048", "ec-p256", "ec-p384", "ed25519"] {
        let (leaf, key) = golden(name);
        assert_eq!(identity_problem(std::slice::from_ref(&leaf), &key), None, "{name}");
        let clients = [leaf.clone()];
        for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
            let server = ClientAuthServer::start(b"song".to_vec(), &certificate, Some(&clients), version);
            let trust = pinned(&certificate);
            let origins = [("https".to_string(), "127.0.0.1".to_string(), server.port())];
            trust.set_identity(Some(Identity::new(origins, vec![leaf.clone()], key.clone()).unwrap()));
            assert!(fetched(&agent(trust), &server.url()), "{name} {version:?}");
            assert_eq!(server.seen()[0].leaf.as_ref(), Some(&leaf), "{name} {version:?}");
        }
    }
}

// Red when: the whole-length check in `open_at`'s 206 arm is removed (the other
// file's bytes are read on after the first's), or `is_final` leaves out
// FileChanged (it is asked again until the retries run out, as a network failure).
#[test]
fn a_part_of_another_file_ends_the_read_as_changed() {
    use crate::testing::http_server::ScriptServer;
    // The first answer breaks off; asked again, the server sends a part of a
    // file of another length.
    let body = data(200_000);
    let first = body[..50_000].to_vec();
    let answers = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let server = ScriptServer::start(move |asked, conn, _| {
        if answers.fetch_add(1, Ordering::SeqCst) == 0 {
            let _ = conn.write_all(
                b"HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-199999/200000\r\nContent-Length: 200000\r\nConnection: close\r\n\r\n",
            );
            let _ = conn.write_all(&first);
        } else {
            let range = asked.header("range").and_then(|r| r.strip_prefix("bytes=")?.strip_suffix('-'));
            let from = range.and_then(|r| r.parse::<usize>().ok()).unwrap_or(0);
            let other = data(300_000);
            let sent = other.len() - from;
            let _ =
                write!(conn, "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {from}-299999/300000\r\n");
            let _ = write!(conn, "Content-Length: {sent}\r\nConnection: close\r\n\r\n");
            let _ = conn.write_all(&other[from..]);
        }
    });
    let mut source = HttpSource::open(&server.url("/rest/stream"), quick()).unwrap();
    let mut out = Vec::new();
    let err = source.read_to_end(&mut out).unwrap_err();
    assert!(out == body[..50_000], "bytes of the other file were read on");
    let failure = err.get_ref().and_then(|e| e.downcast_ref::<Failure>()).unwrap();
    assert_eq!(failure.kind, ErrorKind::FileChanged);
    assert_eq!(server.asked().len(), 2, "the other file was asked for again");
}
