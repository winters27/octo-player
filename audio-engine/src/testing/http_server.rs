//! A tiny HTTP server for tests, able to act like the servers a player
//! meets: with or without ranges, dropping or stalling mid-song, and over
//! HTTPS with a self-signed certificate.

use std::io::{BufRead, BufReader, Read, Write};
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

/// A self-signed certificate for the HTTPS server, and its key.
pub struct SelfSigned {
    /// The certificate's DER bytes, whose SHA-256 is its fingerprint.
    pub der: Vec<u8>,
    key: Vec<u8>,
}

impl SelfSigned {
    /// A certificate naming these hosts, trusted by no system.
    pub fn new(names: &[&str]) -> Self {
        let made =
            rcgen::generate_simple_self_signed(names.iter().map(|n| n.to_string()).collect::<Vec<_>>())
                .unwrap();
        Self { der: made.cert.der().to_vec(), key: made.signing_key.serialize_der() }
    }

    /// The SHA-256 fingerprint in lowercase hex, as the app keeps it.
    pub fn fingerprint(&self) -> String {
        ring::digest::digest(&ring::digest::SHA256, &self.der)
            .as_ref()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    }
}

pub struct TestServer {
    scheme: &'static str,
    port: u16,
    ranges: Arc<Mutex<Vec<u64>>>,
    requests: Arc<AtomicUsize>,
}

impl TestServer {
    pub fn start(body: Vec<u8>, behaviour: Behaviour) -> Self {
        Self::start_with(body, behaviour, None)
    }

    /// The same over HTTPS, showing this certificate.
    pub fn start_tls(body: Vec<u8>, behaviour: Behaviour, certificate: &SelfSigned) -> Self {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let key = rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.key.clone());
        let config = rustls::ServerConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![certificate.der.clone().into()], key.into())
            .unwrap();
        Self::start_with(body, behaviour, Some(Arc::new(config)))
    }

    fn start_with(body: Vec<u8>, behaviour: Behaviour, tls: Option<Arc<rustls::ServerConfig>>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let ranges = Arc::new(Mutex::new(Vec::new()));
        let requests = Arc::new(AtomicUsize::new(0));
        let body = Arc::new(body);
        let (log, count) = (ranges.clone(), requests.clone());
        let scheme = if tls.is_some() { "https" } else { "http" };
        thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let (body, behaviour, log, count, tls) =
                    (body.clone(), behaviour.clone(), log.clone(), count.clone(), tls.clone());
                thread::spawn(move || match tls {
                    None => serve(stream, &body, &behaviour, &log, &count),
                    Some(config) => {
                        let Ok(conn) = rustls::ServerConnection::new(config) else { return };
                        serve(rustls::StreamOwned::new(conn, stream), &body, &behaviour, &log, &count)
                    }
                });
            }
        });
        Self { scheme, port, ranges, requests }
    }

    pub fn url(&self) -> String {
        format!("{}://127.0.0.1:{}/song.flac", self.scheme, self.port)
    }

    /// The range starts asked for, in order.
    pub fn ranges_asked(&self) -> Vec<u64> {
        self.ranges.lock().unwrap().clone()
    }

    pub fn requests(&self) -> usize {
        self.requests.load(Ordering::SeqCst)
    }
}

// A connection, plain or with TLS on it.
trait Conn: Read + Write {
    fn close(&mut self);
}

impl Conn for TcpStream {
    fn close(&mut self) {
        let _ = self.shutdown(Shutdown::Both);
    }
}

impl Conn for rustls::StreamOwned<rustls::ServerConnection, TcpStream> {
    fn close(&mut self) {
        self.conn.send_close_notify();
        let _ = self.flush();
        let _ = self.sock.shutdown(Shutdown::Both);
    }
}

fn serve(mut stream: impl Conn, body: &[u8], b: &Behaviour, log: &Mutex<Vec<u64>>, count: &AtomicUsize) {
    let mut range = None;
    {
        let mut reader = BufReader::new(&mut stream);
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
            stream.close();
            return;
        }
    }
    stream.close();
}
