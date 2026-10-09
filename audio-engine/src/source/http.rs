//! A song streamed over HTTP(S), read like a file.
//!
//! A background thread fetches ahead of the reader into a window of memory.
//! Seeking inside the window is free; further away it asks the server for a
//! range, or, for a server that ignores ranges, fetches from the start again
//! and skips. A dropped connection is picked up where it broke off, and a
//! connection that goes quiet for too long is replaced.

use std::collections::VecDeque;
use std::io::{self, Read, Seek, SeekFrom};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use symphonia::core::io::MediaSource;

use crate::error::{ErrorKind, Failure};
use crate::source::trust::{PinnedTls, Trust};

/// How a stream is fetched.
#[derive(Clone, Debug)]
pub struct HttpOptions {
    /// The client that fetches, with the certificates the engine trusts.
    pub agent: ureq::Agent,
    /// With no new bytes for this long while the reader waits, the
    /// connection is dropped and made again.
    pub stall_timeout: Duration,
    /// How far ahead of the reader to fetch.
    pub read_ahead: usize,
    /// How much already read to keep, for small jumps back.
    pub keep_behind: usize,
    /// Attempts in a row before giving up on a stream.
    pub max_retries: u32,
    /// Extra request headers, such as a server's own.
    pub headers: Vec<(String, String)>,
    /// Set while the reader is waiting on the network.
    pub starved: Option<Arc<AtomicBool>>,
    /// Set by the owner to make a waiting read give up at once.
    pub cancel: Option<Arc<AtomicBool>>,
}

impl Default for HttpOptions {
    fn default() -> Self {
        Self {
            agent: untrusting_agent().clone(),
            stall_timeout: Duration::from_secs(20),
            read_ahead: 4 << 20,
            keep_behind: 1 << 20,
            max_retries: 6,
            headers: Vec::new(),
            starved: None,
            cancel: None,
        }
    }
}

// A jump forward further than this past what is fetched asks the server
// for a range instead of reading through.
const RANGE_JUMP: u64 = 512 << 10;

const CHUNK: usize = 64 << 10;

/// A client for streams, trusting what the system trusts and the
/// certificates pinned in `trust`. One per engine, shared by every stream,
/// so connections are reused.
pub fn agent(trust: Arc<Trust>) -> ureq::Agent {
    use ureq::unversioned::resolver::DefaultResolver;
    use ureq::unversioned::transport::{ConnectProxyConnector, Connector, TcpConnector};
    let config = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_connect(Some(Duration::from_secs(10)))
        .timeout_send_request(Some(Duration::from_secs(10)))
        .timeout_recv_response(Some(Duration::from_secs(15)))
        .user_agent("Octo")
        .build();
    // ureq's usual chain, with TLS that also knows the pins.
    let connector =
        ().chain(ConnectProxyConnector::default())
            .chain(TcpConnector::default())
            .chain(PinnedTls::new(trust));
    ureq::Agent::with_parts(config, connector, DefaultResolver::default())
}

// A client with no pins, for streams opened outside an engine.
fn untrusting_agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| agent(Arc::new(Trust::default())))
}

/// A stream being read.
pub struct HttpSource {
    shared: Arc<Shared>,
    pos: u64,
}

struct Shared {
    url: String,
    opts: HttpOptions,
    len: Option<u64>,
    content_type: Option<String>,
    // The first answer was a range (206 with Content-Range).
    partial: bool,
    state: Mutex<State>,
    wake: Condvar,
}

struct State {
    buf: VecDeque<u8>,
    buf_start: u64,
    reader_pos: u64,
    // Each fetch thread writes only while its generation is current.
    generation: u64,
    ranges: bool,
    eof: bool,
    failure: Option<Failure>,
    closed: bool,
    last_progress: Instant,
    stalls: u32,
}

impl State {
    fn buf_end(&self) -> u64 {
        self.buf_start + self.buf.len() as u64
    }
}

// What asking for bytes from an offset gave.
enum Opened {
    // The body, and how many bytes of it to skip to reach the offset.
    Body(ureq::BodyReader<'static>, u64),
    // Nothing left at that offset.
    End,
}

impl HttpSource {
    /// Opens a stream, failing fast when the server says no.
    pub fn open(url: &str, opts: HttpOptions) -> Result<Self, Failure> {
        let response = request(url, &opts, 0)?;
        let status = response.status().as_u16();
        let header = |name: &str| {
            response.headers().get(name).and_then(|v| v.to_str().ok()).map(|s| s.trim().to_string())
        };
        let (ranges, len) = match status {
            206 => (true, header("content-range").as_deref().and_then(range_total)),
            200 => (
                header("accept-ranges").is_some_and(|v| v.eq_ignore_ascii_case("bytes")),
                header("content-length").and_then(|v| v.parse().ok()),
            ),
            other => return Err(Failure::http(other)),
        };
        let content_type = header("content-type");
        let shared = Arc::new(Shared {
            url: url.to_string(),
            opts,
            len,
            content_type,
            partial: status == 206,
            state: Mutex::new(State {
                buf: VecDeque::new(),
                buf_start: 0,
                reader_pos: 0,
                generation: 0,
                ranges,
                eof: false,
                failure: None,
                closed: false,
                last_progress: Instant::now(),
                stalls: 0,
            }),
            wake: Condvar::new(),
        });
        let body = response.into_body().into_reader();
        spawn_fetch(shared.clone(), 0, 0, Some(body));
        Ok(Self { shared, pos: 0 })
    }

    /// The type the server gave, like "audio/flac".
    pub fn content_type(&self) -> Option<&str> {
        self.shared.content_type.as_deref()
    }

    /// Whether the server answers range requests.
    pub fn supports_ranges(&self) -> bool {
        self.shared.lock().ranges
    }

    /// Whether the server answered the first request with a range, so a
    /// jump far ahead costs one request rather than reading up to it.
    pub fn answered_with_range(&self) -> bool {
        self.shared.partial
    }
}

impl Drop for HttpSource {
    fn drop(&mut self) {
        let mut state = self.shared.lock();
        state.closed = true;
        state.generation += 1;
        self.shared.wake.notify_all();
    }
}

impl Read for HttpSource {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() {
            return Ok(0);
        }
        let shared = self.shared.clone();
        let mut state = shared.lock();
        loop {
            let cancelled = shared.opts.cancel.as_ref().is_some_and(|c| c.load(Ordering::Relaxed));
            if state.closed || cancelled {
                return Err(io::Error::other("stream closed"));
            }
            let end = state.buf_end();
            if self.pos >= state.buf_start && self.pos < end {
                let from = (self.pos - state.buf_start) as usize;
                let (a, b) = state.buf.as_slices();
                let n = if from < a.len() {
                    let n = out.len().min(a.len() - from);
                    out[..n].copy_from_slice(&a[from..from + n]);
                    n
                } else {
                    let from = from - a.len();
                    let n = out.len().min(b.len() - from);
                    out[..n].copy_from_slice(&b[from..from + n]);
                    n
                };
                self.pos += n as u64;
                state.reader_pos = self.pos;
                self.set_starved(false);
                shared.wake.notify_all();
                return Ok(n);
            }
            if self.shared.len.is_some_and(|len| self.pos >= len) || (state.eof && self.pos >= end) {
                self.set_starved(false);
                return Ok(0);
            }
            if let Some(failure) = &state.failure {
                self.set_starved(false);
                return Err(failure.clone().into());
            }
            let jump = if state.ranges { RANGE_JUMP } else { u64::MAX };
            if self.pos < state.buf_start || self.pos > end.saturating_add(jump) {
                restart(&shared, &mut state, self.pos);
            }
            state.reader_pos = self.pos;
            self.set_starved(true);
            let (next, _) = shared
                .wake
                .wait_timeout(state, Duration::from_millis(250))
                .unwrap_or_else(|e| e.into_inner());
            state = next;
            if !state.eof
                && state.failure.is_none()
                && state.last_progress.elapsed() > shared.opts.stall_timeout
            {
                state.stalls += 1;
                if state.stalls > shared.opts.max_retries {
                    state.failure = Some(Failure::new(ErrorKind::Network, "the stream stopped arriving"));
                } else {
                    log::warn!("stream stalled, connecting again");
                    let at = state.buf_end();
                    resume(&shared, &mut state, at);
                }
            }
        }
    }
}

impl HttpSource {
    fn set_starved(&self, on: bool) {
        if let Some(flag) = &self.shared.opts.starved {
            flag.store(on, Ordering::Relaxed);
        }
    }
}

impl Seek for HttpSource {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        let target = match to {
            SeekFrom::Start(p) => Some(p),
            SeekFrom::Current(d) => self.pos.checked_add_signed(d),
            SeekFrom::End(d) => match self.shared.len {
                Some(len) => len.checked_add_signed(d),
                None => {
                    return Err(io::Error::new(
                        io::ErrorKind::Unsupported,
                        "the stream's length is not known",
                    ));
                }
            },
        };
        let target =
            target.ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "seek before the start"))?;
        self.pos = target;
        let shared = self.shared.clone();
        let mut state = shared.lock();
        state.reader_pos = target;
        let jump = if state.ranges { RANGE_JUMP } else { u64::MAX };
        let within = target >= state.buf_start && target <= state.buf_end().saturating_add(jump);
        let past_end = shared.len.is_some_and(|len| target >= len);
        if !within && !past_end {
            restart(&shared, &mut state, target);
        }
        shared.wake.notify_all();
        Ok(target)
    }
}

impl MediaSource for HttpSource {
    fn is_seekable(&self) -> bool {
        // Always, by asking again from the start at worst.
        true
    }

    fn byte_len(&self) -> Option<u64> {
        self.shared.len
    }
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    // Waits until the reader has used up enough to make room. False once
    // this fetch is no longer wanted.
    fn wait_for_room(&self, generation: u64) -> bool {
        let mut state = self.lock();
        loop {
            if state.generation != generation || state.closed {
                return false;
            }
            let ahead = state.buf_end().saturating_sub(state.reader_pos);
            if ahead < self.opts.read_ahead as u64 {
                return true;
            }
            state = self
                .wake
                .wait_timeout(state, Duration::from_millis(500))
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }

    fn append(&self, generation: u64, bytes: &[u8]) -> bool {
        let mut state = self.lock();
        if state.generation != generation || state.closed {
            return false;
        }
        state.buf.extend(bytes);
        // Drop what the reader has left well behind.
        let behind = state.reader_pos.saturating_sub(state.buf_start);
        if behind > self.opts.keep_behind as u64 {
            let drop = ((behind - self.opts.keep_behind as u64) as usize).min(state.buf.len());
            state.buf.drain(..drop);
            state.buf_start += drop as u64;
        }
        state.last_progress = Instant::now();
        state.stalls = 0;
        self.wake.notify_all();
        true
    }

    fn finish(&self, generation: u64) {
        let mut state = self.lock();
        if state.generation == generation {
            state.eof = true;
            self.wake.notify_all();
        }
    }

    fn fail(&self, generation: u64, failure: Failure) {
        let mut state = self.lock();
        if state.generation == generation {
            state.failure = Some(failure);
            self.wake.notify_all();
        }
    }

    fn stale(&self, generation: u64) -> bool {
        let state = self.lock();
        state.generation != generation || state.closed
    }

    // Asks for the bytes from `offset` on.
    fn open_at(&self, offset: u64) -> Result<Opened, Failure> {
        if self.len.is_some_and(|len| offset >= len) {
            return Ok(Opened::End);
        }
        let response = request(&self.url, &self.opts, offset)?;
        let status = response.status().as_u16();
        match status {
            206 => {
                let start = response
                    .headers()
                    .get("content-range")
                    .and_then(|v| v.to_str().ok())
                    .and_then(range_start)
                    .unwrap_or(offset);
                if start > offset {
                    return Err(Failure::new(ErrorKind::Network, "server sent the wrong range"));
                }
                Ok(Opened::Body(response.into_body().into_reader(), offset - start))
            }
            200 => {
                if offset > 0 {
                    self.lock().ranges = false;
                }
                Ok(Opened::Body(response.into_body().into_reader(), offset))
            }
            416 => Ok(Opened::End),
            other => Err(Failure::http(other)),
        }
    }
}

// Starts over at `pos`, dropping what is held.
fn restart(shared: &Arc<Shared>, state: &mut State, pos: u64) {
    state.generation += 1;
    state.buf.clear();
    state.buf_start = pos;
    state.eof = false;
    state.failure = None;
    state.last_progress = Instant::now();
    spawn_fetch(shared.clone(), state.generation, pos, None);
}

// Carries on from `pos`, the end of what is held.
fn resume(shared: &Arc<Shared>, state: &mut State, pos: u64) {
    state.generation += 1;
    state.last_progress = Instant::now();
    spawn_fetch(shared.clone(), state.generation, pos, None);
}

fn spawn_fetch(shared: Arc<Shared>, generation: u64, offset: u64, body: Option<ureq::BodyReader<'static>>) {
    let spawned = thread::Builder::new()
        .name("octo-http".into())
        .spawn(move || fetch(shared, generation, offset, body));
    if let Err(e) = spawned {
        log::error!("could not start a fetch thread: {e}");
    }
}

fn fetch(shared: Arc<Shared>, generation: u64, mut offset: u64, mut body: Option<ureq::BodyReader<'static>>) {
    let mut failures = 0u32;
    let mut chunk = vec![0u8; CHUNK];
    // Where the last broken connection ended, to spot a stream that always
    // ends early (a server's estimated length for a stream made smaller).
    let mut broke_at: Option<u64> = None;
    loop {
        if shared.stale(generation) {
            return;
        }
        let mut reader = match body.take() {
            Some(reader) => reader,
            None => match shared.open_at(offset) {
                Ok(Opened::Body(mut reader, skip)) => {
                    if !skip_bytes(&shared, generation, &mut reader, skip, &mut chunk) {
                        failures += 1;
                        if !backoff(&shared, generation, failures) {
                            return;
                        }
                        continue;
                    }
                    reader
                }
                Ok(Opened::End) => {
                    shared.finish(generation);
                    return;
                }
                Err(failure) if failure.status.is_some_and(|s| (400..500).contains(&s)) => {
                    shared.fail(generation, failure);
                    return;
                }
                Err(failure) => {
                    log::warn!("stream request failed: {failure}");
                    failures += 1;
                    if !backoff(&shared, generation, failures) {
                        return;
                    }
                    continue;
                }
            },
        };
        loop {
            if !shared.wait_for_room(generation) {
                return;
            }
            match reader.read(&mut chunk) {
                Ok(0) => {
                    shared.finish(generation);
                    return;
                }
                Ok(n) => {
                    if !shared.append(generation, &chunk[..n]) {
                        return;
                    }
                    offset += n as u64;
                    failures = 0;
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => {
                    let ranges = shared.lock().ranges;
                    if !ranges && broke_at == Some(offset) {
                        // The same early end twice: that is where it ends.
                        shared.finish(generation);
                        return;
                    }
                    broke_at = Some(offset);
                    log::warn!("stream broke off at {offset}: {e}");
                    failures += 1;
                    if !backoff(&shared, generation, failures) {
                        return;
                    }
                    break;
                }
            }
        }
    }
}

// Reads and drops `skip` bytes. False if the connection broke first.
fn skip_bytes(
    shared: &Shared,
    generation: u64,
    reader: &mut ureq::BodyReader<'static>,
    mut skip: u64,
    chunk: &mut [u8],
) -> bool {
    while skip > 0 {
        if shared.stale(generation) {
            return false;
        }
        let want = (skip as usize).min(chunk.len());
        match reader.read(&mut chunk[..want]) {
            Ok(0) | Err(_) => return false,
            Ok(n) => skip -= n as u64,
        }
    }
    true
}

// Waits a little longer after each failure in a row. False when it is time
// to give up, or the fetch is no longer wanted.
fn backoff(shared: &Shared, generation: u64, failures: u32) -> bool {
    if failures > shared.opts.max_retries {
        shared.fail(generation, Failure::new(ErrorKind::Network, "the connection kept failing"));
        return false;
    }
    let wait = Duration::from_millis(250u64 << failures.min(4));
    let until = Instant::now() + wait;
    while Instant::now() < until {
        if shared.stale(generation) {
            return false;
        }
        thread::sleep(Duration::from_millis(50));
    }
    true
}

fn request(url: &str, opts: &HttpOptions, from: u64) -> Result<ureq::http::Response<ureq::Body>, Failure> {
    let mut req = opts.agent.get(url);
    for (name, value) in &opts.headers {
        req = req.header(name, value);
    }
    // Always ask for a range: a server that supports them says so with 206.
    req.header("Range", format!("bytes={from}-")).call().map_err(|e| match e {
        ureq::Error::StatusCode(status) => Failure::http(status),
        other => Failure::new(ErrorKind::Network, other.to_string()),
    })
}

// "bytes 100-199/1234" gives 1234.
fn range_total(value: &str) -> Option<u64> {
    value.rsplit('/').next()?.trim().parse().ok()
}

// "bytes 100-199/1234" gives 100.
fn range_start(value: &str) -> Option<u64> {
    let spec = value.trim().strip_prefix("bytes")?.trim();
    spec.split('-').next()?.trim().parse().ok()
}

#[cfg(test)]
mod tests;
