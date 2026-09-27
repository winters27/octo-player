//! A tiny HTTP server for tests, able to act like the servers a player
//! meets: with or without ranges, dropping or stalling mid-song.

use std::io::{BufRead, BufReader, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct Behaviour {
    /// Answers range requests with 206.
    pub ranges: bool,
    /// On the first request, closes the connection after this many bytes.
    pub drop_after: Option<usize>,
    /// On the first request, goes quiet after this many bytes.
    pub stall_after: Option<usize>,
    /// Answers every request with this status and no body.
    pub status: Option<u16>,
    pub content_type: &'static str,
}

impl Default for Behaviour {
    fn default() -> Self {
        Self { ranges: true, drop_after: None, stall_after: None, status: None, content_type: "audio/flac" }
    }
}

pub struct TestServer {
    port: u16,
    ranges: Arc<Mutex<Vec<u64>>>,
    requests: Arc<AtomicUsize>,
}

impl TestServer {
    pub fn start(body: Vec<u8>, behaviour: Behaviour) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let ranges = Arc::new(Mutex::new(Vec::new()));
        let requests = Arc::new(AtomicUsize::new(0));
        let body = Arc::new(body);
        let (log, count) = (ranges.clone(), requests.clone());
        thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let (body, behaviour, log, count) =
                    (body.clone(), behaviour.clone(), log.clone(), count.clone());
                thread::spawn(move || serve(stream, &body, &behaviour, &log, &count));
            }
        });
        Self { port, ranges, requests }
    }

    pub fn url(&self) -> String {
        format!("http://127.0.0.1:{}/song.flac", self.port)
    }

    /// The range starts asked for, in order.
    pub fn ranges_asked(&self) -> Vec<u64> {
        self.ranges.lock().unwrap().clone()
    }

    pub fn requests(&self) -> usize {
        self.requests.load(Ordering::SeqCst)
    }
}

fn serve(mut stream: TcpStream, body: &[u8], b: &Behaviour, log: &Mutex<Vec<u64>>, count: &AtomicUsize) {
    let mut range = None;
    {
        let mut reader = BufReader::new(&stream);
        let mut line = String::new();
        loop {
            line.clear();
            if reader.read_line(&mut line).unwrap_or(0) == 0 {
                return;
            }
            let trimmed = line.trim_end();
            if trimmed.is_empty() {
                break;
            }
            let lower = trimmed.to_ascii_lowercase();
            if let Some(value) = lower.strip_prefix("range:") {
                range = value
                    .trim()
                    .strip_prefix("bytes=")
                    .and_then(|v| v.split('-').next()?.parse::<u64>().ok());
            }
        }
    }
    let nth = count.fetch_add(1, Ordering::SeqCst);
    if let Some(start) = range {
        log.lock().unwrap().push(start);
    }
    if let Some(status) = b.status {
        let _ = write!(stream, "HTTP/1.1 {status} Nope\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return;
    }
    let len = body.len() as u64;
    let start = if b.ranges { range.unwrap_or(0) } else { 0 };
    if b.ranges && start >= len && len > 0 {
        let _ = write!(
            stream,
            "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        );
        return;
    }
    let head = if b.ranges && range.is_some() {
        format!(
            "HTTP/1.1 206 Partial Content\r\nContent-Type: {}\r\nContent-Range: bytes {}-{}/{}\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n",
            b.content_type,
            start,
            len.saturating_sub(1),
            len,
            len - start
        )
    } else {
        format!(
            "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            b.content_type, len
        )
    };
    if stream.write_all(head.as_bytes()).is_err() {
        return;
    }
    let rest = &body[start as usize..];
    let cut = if nth == 0 { b.drop_after.or(b.stall_after) } else { None };
    let mut sent = 0;
    for chunk in rest.chunks(16 << 10) {
        let chunk = match cut {
            Some(limit) if sent + chunk.len() > limit => &chunk[..limit - sent],
            _ => chunk,
        };
        if stream.write_all(chunk).is_err() {
            return;
        }
        sent += chunk.len();
        if cut.is_some_and(|limit| sent >= limit) {
            if nth == 0 && b.stall_after.is_some() {
                thread::sleep(Duration::from_secs(3));
            }
            let _ = stream.shutdown(Shutdown::Both);
            return;
        }
    }
}
