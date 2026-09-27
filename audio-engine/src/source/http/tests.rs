use super::*;
use crate::testing::http_server::{Behaviour, TestServer};
use std::io::{Read, Seek, SeekFrom};

fn data(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 7 + i / 251) as u8).collect()
}

fn quick() -> HttpOptions {
    HttpOptions {
        stall_timeout: Duration::from_millis(600),
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
    let mut source = HttpSource::open(&server.url(), quick()).unwrap();
    let started = Instant::now();
    assert_eq!(read_all(&mut source), body);
    assert!(started.elapsed() < Duration::from_secs(10));
    assert!(server.requests() >= 2);
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
    // A port nothing listens on.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let err = HttpSource::open(&format!("http://127.0.0.1:{port}/x"), quick()).err().unwrap();
    assert_eq!(err.kind, ErrorKind::Network);
}

#[test]
fn content_range_parsing() {
    assert_eq!(range_total("bytes 0-99/1234"), Some(1234));
    assert_eq!(range_start("bytes 100-199/1234"), Some(100));
    assert_eq!(range_total("bytes 0-99/*"), None);
}
