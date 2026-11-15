//! A song streamed over HTTP(S), read like a file.
//!
//! A background thread fetches ahead of the reader into a window of memory.
//! Seeking inside the window is free; further away it asks the server for a
//! range, or, for a server that ignores ranges, fetches from the start again
//! and skips. A dropped connection is picked up where it broke off, and a
//! connection that goes quiet for too long is replaced.
//!
//! A live stream (live radio) has no length and no ranges: it is never
//! sought, a new connection carries on from wherever the server is now, and
//! its in-stream titles are taken out of the audio.

use std::collections::VecDeque;
use std::fmt;
use std::io::{self, Read, Seek, SeekFrom};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use symphonia::core::io::MediaSource;

use crate::error::{ErrorKind, Failure};
use crate::live::Live;
use crate::source::hls;
use crate::source::icy::{IcySplitter, IcyStatus, StreamMeta};
use crate::source::trust::{PinnedTls, Trust};
use ureq::unversioned::transport::{Buffers, ConnectionDetails, Connector, NextTimeout, Transport};

/// How a stream is fetched.
#[derive(Clone)]
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
    /// Extra request headers, such as a server's own. They go only to the
    /// origin (scheme, host and port) of the address opened, or to the same
    /// host over https when that address is http, never to another one a
    /// redirect names, and never show in `Debug`.
    pub headers: Vec<(String, String)>,
    /// More request headers, such as a gateway's sign-in, sent on top of
    /// `headers` but only over https to the same server: never over plain
    /// http, the address opened included, never to another server, and
    /// never shown in `Debug`.
    pub secure_headers: Vec<(String, String)>,
    /// Set while the reader is waiting on the network.
    pub starved: Option<Arc<AtomicBool>>,
    /// Set by the owner to make a waiting read give up at once.
    pub cancel: Option<Arc<AtomicBool>>,
    /// A live stream: no length, never sought, and each new connection
    /// asks for no range and carries on from wherever the server is.
    pub live: bool,
    /// The first wait before connecting to an ended live stream again. It
    /// doubles each time, four times, before the stream counts as gone.
    pub live_backoff: Duration,
    /// A stream made on the way (asked for with a `format` other than `raw`),
    /// which the deck seeks by opening it again at a time. A break, a stall,
    /// an answer other than 2xx to a later request, or a later answer of
    /// another type ends it with a failure to reopen, never another request
    /// for its bytes.
    pub by_time: bool,
    /// How long the deck waits before opening a stream sought by time again
    /// after its server answered busy.
    pub reopen_wait: Duration,
    /// Where a live stream's titles go once the reader reaches them.
    pub titles: Option<Arc<TitleSlot>>,
    /// The address is an HLS playlist, whatever it looks like.
    pub hls: bool,
    /// Counts the streams open on these options until their last fetch
    /// thread lets go.
    pub stream_count: &'static AtomicI64,
    /// The same for HLS streams.
    pub hls_count: &'static AtomicI64,
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
            secure_headers: Vec::new(),
            starved: None,
            cancel: None,
            live: false,
            live_backoff: Duration::from_secs(1),
            by_time: false,
            reopen_wait: Duration::from_secs(1),
            titles: None,
            hls: false,
            stream_count: &crate::live::HTTP_STREAMS,
            hls_count: &crate::live::HLS_STREAMS,
        }
    }
}

// By hand, so header values, which can be passwords, never reach a log:
// only their names show.
impl fmt::Debug for HttpOptions {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let names: Vec<&str> = self.headers.iter().map(|(name, _)| name.as_str()).collect();
        let secure: Vec<&str> = self.secure_headers.iter().map(|(name, _)| name.as_str()).collect();
        f.debug_struct("HttpOptions")
            .field("stall_timeout", &self.stall_timeout)
            .field("read_ahead", &self.read_ahead)
            .field("keep_behind", &self.keep_behind)
            .field("max_retries", &self.max_retries)
            .field("headers", &names)
            .field("secure_headers", &secure)
            .field("live", &self.live)
            .field("live_backoff", &self.live_backoff)
            .field("hls", &self.hls)
            .field("by_time", &self.by_time)
            .field("reopen_wait", &self.reopen_wait)
            .finish_non_exhaustive()
    }
}

/// The newest title a live stream reached, until it is picked up.
#[derive(Debug, Default)]
pub struct TitleSlot(Mutex<Option<StreamMeta>>);

impl TitleSlot {
    pub fn put(&self, title: StreamMeta) {
        *self.0.lock().unwrap_or_else(|e| e.into_inner()) = Some(title);
    }

    pub fn take(&self) -> Option<StreamMeta> {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).take()
    }
}

// A jump forward further than this past what is fetched asks the server
// for a range instead of reading through.
const RANGE_JUMP: u64 = 512 << 10;

const CHUNK: usize = 64 << 10;

// Connections in a row to an ended live stream before it counts as gone.
const LIVE_RETRIES: u32 = 4;

// A live connection counts as working, so the count of failures in a row
// starts again, only once it has given this much or lasted this long: a
// server that sends a little and hangs up each time is gone all the same.
const LIVE_GOOD_BYTES: u64 = 64 << 10;
const LIVE_GOOD_TIME: Duration = Duration::from_secs(10);

// The most one read of a live stream gives, so the decoder's read-ahead
// stays short and a title is reached close to when its sound is decoded.
const LIVE_READ: usize = 4 << 10;

/// A client for streams, trusting what the system trusts and the
/// certificates pinned in `trust`. One per engine, shared by every stream,
/// so connections are reused.
pub fn agent(trust: Arc<Trust>) -> ureq::Agent {
    agent_with_idle_limit(trust, IDLE_LIMIT)
}

/// A client whose reads give up after `idle` with nothing arriving.
fn agent_with_idle_limit(trust: Arc<Trust>, idle: Duration) -> ureq::Agent {
    agent_with_parts(trust, idle, ureq::unversioned::resolver::DefaultResolver::default())
}

/// The same, finding where each host is with `resolver`.
fn agent_with_parts(
    trust: Arc<Trust>,
    idle: Duration,
    resolver: impl ureq::unversioned::resolver::Resolver,
) -> ureq::Agent {
    use ureq::unversioned::transport::{ConnectProxyConnector, Connector, TcpConnector};
    let config = ureq::Agent::config_builder()
        .http_status_as_error(false)
        // A redirect comes back as an answer: `follow` takes each hop, and
        // decides what it is sent with.
        .max_redirects(0)
        .timeout_connect(Some(Duration::from_secs(10)))
        .timeout_send_request(Some(Duration::from_secs(10)))
        .timeout_recv_response(Some(Duration::from_secs(15)))
        .user_agent("Octo")
        .build();
    // ureq's usual chain, with TLS that also knows the pins, old SHOUTcast
    // servers' ICY answers read as HTTP, and a limit on a quiet read.
    let connector =
        ().chain(ConnectProxyConnector::default())
            .chain(TcpConnector::default())
            .chain(PinnedTls::new(trust))
            .chain(IcyStatus)
            .chain(IdleLimit(idle));
    ureq::Agent::with_parts(config, connector, resolver)
}

/// The longest a connection may give nothing while it is read. With no
/// deadline for a body, a read from a server that went quiet without
/// closing waited for ever, and its fetch thread with it, holding the
/// stream's whole window of memory after the stream itself was let go.
const IDLE_LIMIT: Duration = Duration::from_secs(30);

/// A link in the connection chain that gives up a read waiting longer than
/// IDLE_LIMIT for its next bytes, as a timeout.
#[derive(Debug)]
struct IdleLimit(Duration);

impl<In: Transport> Connector<In> for IdleLimit {
    type Out = IdleTransport<In>;

    fn connect(
        &self,
        _details: &ConnectionDetails,
        chained: Option<In>,
    ) -> Result<Option<Self::Out>, ureq::Error> {
        Ok(chained.map(|inner| IdleTransport { inner, limit: self.0 }))
    }
}

#[derive(Debug)]
struct IdleTransport<In> {
    inner: In,
    limit: Duration,
}

impl<In: Transport> Transport for IdleTransport<In> {
    fn buffers(&mut self) -> &mut dyn Buffers {
        self.inner.buffers()
    }

    fn transmit_output(&mut self, amount: usize, timeout: NextTimeout) -> Result<(), ureq::Error> {
        self.inner.transmit_output(amount, timeout)
    }

    fn await_input(&mut self, timeout: NextTimeout) -> Result<bool, ureq::Error> {
        self.inner.await_input(idle_limited(timeout, self.limit))
    }

    fn is_open(&mut self) -> bool {
        self.inner.is_open()
    }

    fn is_tls(&self) -> bool {
        self.inner.is_tls()
    }
}

/// The wait for input, at most `limit`.
fn idle_limited(timeout: NextTimeout, limit: Duration) -> NextTimeout {
    if timeout.after.is_not_happening() || *timeout.after > limit {
        NextTimeout {
            after: ureq::unversioned::transport::time::Duration::Exact(limit),
            reason: timeout.reason,
        }
    } else {
        timeout
    }
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
    // Held while the reader or any fetch thread still has this.
    _live: Live,
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
    // A live stream's titles the reader has not reached, by where they start.
    titles: VecDeque<(u64, StreamMeta)>,
    last_title: Option<StreamMeta>,
}

impl State {
    fn buf_end(&self) -> u64 {
        self.buf_start + self.buf.len() as u64
    }
}

// A response's body, and how many audio bytes its server puts between
// in-stream titles.
type Body = (ureq::BodyReader<'static>, Option<usize>);

/// A server's answer, its body not read yet.
pub(crate) type Answer = ureq::http::Response<ureq::Body>;

// What asking for bytes from an offset gave.
enum Opened {
    // The body, and how many bytes of it to skip to reach the offset.
    Body(Body, u64),
    // Nothing left at that offset.
    End,
}

impl HttpSource {
    /// Opens a stream, failing fast when the server says no.
    pub fn open(url: &str, opts: HttpOptions) -> Result<Self, Failure> {
        let live = opts.live;
        let response = request(url, &opts, 0)?;
        let status = response.status().as_u16();
        let header = |name: &str| {
            response.headers().get(name).and_then(|v| v.to_str().ok()).map(|s| s.trim().to_string())
        };
        let content_type = header("content-type");
        // No song is a web page, and no server refuses one the way a gateway
        // does: either is a sign-in page that answered in the server's place.
        // Live radio plays whatever its station sends.
        let html = !live && is_html(content_type.as_deref());
        let login = sends_login(url, &opts);
        let (ranges, len) = match status {
            200..300 if live => (false, None),
            401 | 403 if !live && refusal_is_sign_in_page(status, response.headers(), login) => {
                return Err(Failure::sign_in_page(status));
            }
            206 => (true, header("content-range").as_deref().and_then(range_total)),
            200 => (
                header("accept-ranges").is_some_and(|v| v.eq_ignore_ascii_case("bytes")),
                header("content-length").and_then(|v| v.parse().ok()),
            ),
            other => return Err(Failure::http(other)),
        };
        if html {
            return Err(Failure::sign_in_page(status));
        }
        let metaint = if live { metaint(&response) } else { None };
        let counted = Live::new(opts.stream_count);
        let shared = Arc::new(Shared {
            url: url.to_string(),
            opts,
            len,
            content_type,
            partial: status == 206 && !live,
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
                titles: VecDeque::new(),
                last_title: None,
            }),
            wake: Condvar::new(),
            _live: counted,
        });
        let body = response.into_body().into_reader();
        spawn_fetch(shared.clone(), 0, 0, Some((body, metaint)));
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
        let most = if self.shared.opts.live { LIVE_READ } else { out.len() };
        let len = most.min(out.len());
        let out = &mut out[..len];
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
                self.reach_titles(&mut state);
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
            let away = self.pos < state.buf_start || self.pos > end.saturating_add(jump);
            if away && !shared.opts.live {
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
                if shared.opts.by_time {
                    // Made on the way: its bytes from here may not be the ones
                    // a new request sends, so the deck opens it at its time.
                    log::warn!("a stream made on the way stalled");
                    state.generation += 1;
                    state.failure = Some(Failure::reopen_at_time("the stream stopped arriving"));
                } else if state.stalls > shared.opts.max_retries {
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
    // Hands on the titles the reader has now reached.
    fn reach_titles(&self, state: &mut State) {
        while state.titles.front().is_some_and(|(at, _)| *at <= self.pos) {
            let Some((_, title)) = state.titles.pop_front() else { break };
            if let Some(slot) = &self.shared.opts.titles {
                slot.put(title);
            }
        }
    }

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
        let shared = self.shared.clone();
        let mut state = shared.lock();
        if shared.opts.live && target < state.buf_start {
            // Only what is held, or ahead by reading on.
            return Err(io::Error::new(io::ErrorKind::Unsupported, "a live stream cannot go back"));
        }
        self.pos = target;
        state.reader_pos = target;
        let jump = if state.ranges { RANGE_JUMP } else { u64::MAX };
        let within = target >= state.buf_start && target <= state.buf_end().saturating_add(jump);
        let past_end = shared.len.is_some_and(|len| target >= len);
        if !within && !past_end && !shared.opts.live {
            restart(&shared, &mut state, target);
        }
        shared.wake.notify_all();
        Ok(target)
    }
}

impl MediaSource for HttpSource {
    fn is_seekable(&self) -> bool {
        // Always, by asking again from the start at worst; never live.
        !self.shared.opts.live
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

    // Adds fetched bytes, and the titles found among them by where in
    // `bytes` each starts. A title the same as the one before is dropped;
    // an empty one, once there was another, says there is none now.
    fn append(&self, generation: u64, bytes: &[u8], titles: &mut Vec<(usize, StreamMeta)>) -> bool {
        let mut state = self.lock();
        if state.generation != generation || state.closed {
            return false;
        }
        let end = state.buf_end();
        for (at, title) in titles.drain(..) {
            let unchanged = state.last_title.as_ref().map_or(title.is_empty(), |last| *last == title);
            if !unchanged {
                state.last_title = Some(title.clone());
                state.titles.push_back((end + at as u64, title));
            }
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
        if self.opts.live {
            // Wherever the server is now, from the first byte it sends.
            return match status {
                200..300 => {
                    let metaint = metaint(&response);
                    Ok(Opened::Body((response.into_body().into_reader(), metaint), 0))
                }
                other => Err(Failure::http(other)),
            };
        }
        // A sign-in page met on a later request, once a gateway's session has
        // run out, ends the song as it would have at the start.
        let html = is_html(response.headers().get("content-type").and_then(|v| v.to_str().ok()));
        let login = sends_login(&self.url, &self.opts);
        if self.opts.by_time && !html && !matches!(status, 401 | 403) {
            // A stream made on the way that its server now refuses, or sends
            // as another file, cannot go on by bytes.
            if !matches!(status, 200 | 206 | 416) {
                return Err(Failure::reopen_at_time(format!("server answered {status}")));
            }
            let answered =
                response.headers().get("content-type").and_then(|v| v.to_str().ok()).map(plain_type);
            let first = self.content_type.as_deref().map(plain_type);
            if answered.is_some() && first.is_some() && answered != first {
                return Err(Failure::reopen_at_time("the stream changed"));
            }
        }
        if !self.opts.by_time && status == 503 && offset > 0 {
            // A later part of a song refused as busy: the server let go of the
            // file the song was read from (Octo does, when a kept copy is
            // gone). Asking on for its bytes would mix two files, so the deck
            // opens the song again from its first byte.
            return Err(Failure::reopen_at_time("server answered 503 for a later part"));
        }
        match status {
            200 | 206 if html => Err(Failure::sign_in_page(status)),
            401 | 403 if refusal_is_sign_in_page(status, response.headers(), login) => {
                Err(Failure::sign_in_page(status))
            }
            206 => {
                let range = response.headers().get("content-range").and_then(|v| v.to_str().ok());
                // A part of a file of another whole length is another file (the
                // server began sending another version of the song): reading on
                // would join the bytes of two files.
                if let (Some(first), Some(total)) = (self.len, range.and_then(range_total))
                    && total != first
                {
                    let message =
                        format!("the server sent a part of another file ({total} bytes, not {first})");
                    return Err(Failure::new(ErrorKind::FileChanged, message));
                }
                let start = range.and_then(range_start).unwrap_or(offset);
                if start > offset {
                    return Err(Failure::new(ErrorKind::Network, "server sent the wrong range"));
                }
                Ok(Opened::Body((response.into_body().into_reader(), None), offset - start))
            }
            200 => {
                if offset > 0 {
                    self.lock().ranges = false;
                }
                Ok(Opened::Body((response.into_body().into_reader(), None), offset))
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

fn spawn_fetch(shared: Arc<Shared>, generation: u64, offset: u64, body: Option<Body>) {
    let spawned = thread::Builder::new()
        .name("octo-http".into())
        .spawn(move || fetch(shared, generation, offset, body));
    if let Err(e) = spawned {
        log::error!("could not start a fetch thread: {e}");
    }
}

fn fetch(shared: Arc<Shared>, generation: u64, mut offset: u64, mut body: Option<Body>) {
    let live = shared.opts.live;
    let mut failures = 0u32;
    let mut chunk = vec![0u8; CHUNK];
    let mut audio = Vec::with_capacity(CHUNK);
    let mut titles = Vec::new();
    // Where the last broken connection ended, to spot a stream that always
    // ends early (a server's estimated length for a stream made smaller).
    let mut broke_at: Option<u64> = None;
    loop {
        if shared.stale(generation) {
            return;
        }
        let (mut reader, metaint) = match body.take() {
            Some(body) => body,
            None => match shared.open_at(offset) {
                Ok(Opened::Body((mut reader, metaint), skip)) => {
                    if !skip_bytes(&shared, generation, &mut reader, skip, &mut chunk) {
                        failures += 1;
                        if !backoff(&shared, generation, failures) {
                            return;
                        }
                        continue;
                    }
                    (reader, metaint)
                }
                Ok(Opened::End) => {
                    shared.finish(generation);
                    return;
                }
                Err(failure) if is_final(&failure) => {
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
        // Each connection counts to its first title from its own start.
        let mut icy = metaint.map(IcySplitter::new);
        let (connected, mut given) = (Instant::now(), 0u64);
        loop {
            if !shared.wait_for_room(generation) {
                return;
            }
            match reader.read(&mut chunk) {
                Ok(0) if live => {
                    log::warn!("the live stream ended, connecting again");
                    failures += 1;
                    if !backoff(&shared, generation, failures) {
                        return;
                    }
                    break;
                }
                Ok(0) => {
                    shared.finish(generation);
                    return;
                }
                Ok(n) => {
                    let bytes = match &mut icy {
                        Some(icy) => {
                            audio.clear();
                            icy.feed(&chunk[..n], &mut audio, &mut titles);
                            &audio[..]
                        }
                        None => &chunk[..n],
                    };
                    if !shared.append(generation, bytes, &mut titles) {
                        return;
                    }
                    offset += bytes.len() as u64;
                    given += n as u64;
                    if !live || given >= LIVE_GOOD_BYTES || connected.elapsed() >= LIVE_GOOD_TIME {
                        failures = 0;
                    }
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) if live => {
                    log::warn!("the live stream broke off: {e}");
                    failures += 1;
                    if !backoff(&shared, generation, failures) {
                        return;
                    }
                    break;
                }
                Err(e) if shared.opts.by_time => {
                    log::warn!("a stream made on the way broke off at {offset}: {e}");
                    shared.fail(generation, Failure::reopen_at_time("the stream broke off"));
                    return;
                }
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
    let live = shared.opts.live;
    if live && failures > LIVE_RETRIES {
        let gone = "the live stream stopped and did not come back";
        shared.fail(generation, Failure::new(ErrorKind::Network, gone));
        return false;
    }
    if !live && failures > shared.opts.max_retries {
        shared.fail(generation, Failure::new(ErrorKind::Network, "the connection kept failing"));
        return false;
    }
    let wait = if live {
        // 1, 2, 4 and 8 times the first wait.
        shared.opts.live_backoff * (1 << (failures - 1))
    } else {
        Duration::from_millis(250u64 << failures.min(4))
    };
    let until = Instant::now() + wait;
    while Instant::now() < until {
        if shared.stale(generation) {
            return false;
        }
        thread::sleep(Duration::from_millis(50).min(wait));
    }
    true
}

// Asks for the stream from byte `from`, at the stream's own address each
// time: an address a redirect gave before is never asked straight away.
fn request(url: &str, opts: &HttpOptions, from: u64) -> Result<Answer, Failure> {
    let extra = if opts.live {
        // A live stream has no ranges; its server may send titles.
        ("Icy-MetaData", "1".to_string())
    } else {
        // Always ask for a range: a server that supports them says so with 206.
        ("Range", format!("bytes={from}-"))
    };
    follow(&opts.agent, url, &opts.headers, &opts.secure_headers, &[extra], None)
        .map(|(response, _)| response)
}

// Redirects followed in a row before the answer counts as a refusal.
const MOST_REDIRECTS: u32 = 10;

/// Asks for `url`, following redirects here rather than in the client, at
/// most MOST_REDIRECTS in a row. `headers` go only to hops on the server of
/// `url` itself (see `same_server`), never to another one a redirect names;
/// `secure` go on top of them, but only to those hops that are https;
/// `extra`, such as the range, goes on every hop. `limit` bounds each hop.
/// Gives the first answer that is not a redirect, and the address that
/// gave it.
pub(crate) fn follow(
    agent: &ureq::Agent,
    url: &str,
    headers: &[(String, String)],
    secure: &[(String, String)],
    extra: &[(&str, String)],
    limit: Option<Duration>,
) -> Result<(Answer, String), Failure> {
    let home = origin(url);
    let mut at = url.to_string();
    let mut hops = 0;
    loop {
        let mut req = agent.get(at.as_str());
        let own = home.as_ref().zip(origin(&at)).filter(|(home, to)| same_server(home, to));
        if let Some((_, to)) = own {
            for (name, value) in headers {
                req = req.header(name, value);
            }
            // Never over plain http, the address asked for included.
            if to.0 == "https" {
                for (name, value) in secure {
                    req = req.header(name, value);
                }
            }
        }
        for (name, value) in extra {
            req = req.header(*name, value);
        }
        let called = match limit {
            Some(limit) => req.config().timeout_global(Some(limit)).build().call(),
            None => req.call(),
        };
        let response = called.map_err(|e| match e {
            ureq::Error::StatusCode(status) => Failure::http(status),
            other => Failure::new(ErrorKind::Network, other.to_string()),
        })?;
        if !response.status().is_redirection() {
            return Ok((response, at));
        }
        if hops == MOST_REDIRECTS {
            let status = response.status().as_u16();
            return Err(refused_redirect(status, "after too many redirects in a row"));
        }
        at = redirect_target(&at, &response)?;
        hops += 1;
    }
}

// Where a redirect from `from` sends the stream on to, read against `from`
// when it is relative. Only an http or https address is followed.
fn redirect_target(from: &str, response: &Answer) -> Result<String, Failure> {
    let status = response.status().as_u16();
    let location = response.headers().get("location").and_then(|v| v.to_str().ok()).map(str::trim);
    let Some(location) = location.filter(|l| !l.is_empty()) else {
        return Err(refused_redirect(status, "without saying where to"));
    };
    let to = hls::resolve(from, location);
    if !super::is_remote(&to) {
        return Err(refused_redirect(status, "sending the stream away from http"));
    }
    Ok(to)
}

// A redirect that is not followed: the server's answer, and why.
fn refused_redirect(status: u16, why: &str) -> Failure {
    let message = format!("server answered {status} {why}");
    Failure { kind: ErrorKind::Http, message, status: Some(status), reopen: false }
}

// An address's scheme, host and port, as `origin` gives them.
type Origin = (String, String, u16);

// The scheme, host and port of an address, by which a hop is the same
// server or another; the port filled in where the scheme implies it.
fn origin(url: &str) -> Option<Origin> {
    let uri: ureq::http::Uri = url.parse().ok()?;
    let scheme = uri.scheme_str()?.to_ascii_lowercase();
    let implied = match scheme.as_str() {
        "http" => 80,
        "https" => 443,
        _ => return None,
    };
    let host = uri.host()?.to_ascii_lowercase();
    Some((scheme, host, uri.port_u16().unwrap_or(implied)))
}

// Whether a hop at `to` is still the server at `home`: the same origin, or
// the same host moved up from http to https at https's own port 443, the
// one address the apps add for an http server too.
fn same_server(home: &Origin, to: &Origin) -> bool {
    let ((scheme, host, _), (to_scheme, to_host, to_port)) = (home, to);
    let upgraded = scheme == "http" && to_scheme == "https" && *to_port == 443;
    home == to || (upgraded && host == to_host)
}

// Whether a content type is a web page, whatever its case and parameters
// ("text/html; charset=utf-8" is one).
fn is_html(content_type: Option<&str>) -> bool {
    let bare = content_type.and_then(|t| t.split(';').next()).map(str::trim);
    bare.is_some_and(|t| t.eq_ignore_ascii_case("text/html"))
}

// Whether a content type is JSON ("application/json", "text/json", or any
// "+json" type).
fn is_json(content_type: Option<&str>) -> bool {
    let bare = content_type.and_then(|t| t.split(';').next()).map(|t| t.trim().to_ascii_lowercase());
    bare.is_some_and(|t| t == "application/json" || t == "text/json" || t.ends_with("+json"))
}

// The values a header has, trimmed, leaving out any that are not text.
fn header_values<'a>(headers: &'a ureq::http::HeaderMap, name: &str) -> impl Iterator<Item = &'a str> {
    headers.get_all(name).iter().filter_map(|v| v.to_str().ok()).map(str::trim)
}

// Whether a 401 or 403 is a gateway refusing in the server's place, by what
// only gateways send: a web page, a Basic challenge, Cloudflare Access's own
// headers, an address to sign in at, a gateway's session cookie, or, to a
// request that carried a sign-in, anything at all. A JSON answer is the
// server's own, whatever else it carries.
fn refusal_is_sign_in_page(status: u16, headers: &ureq::http::HeaderMap, login_sent: bool) -> bool {
    let media = header_values(headers, "content-type").next();
    if is_json(media) {
        return false;
    }
    let challenge = |c: &str| c.get(..5).is_some_and(|s| s.eq_ignore_ascii_case("basic"));
    let basic = status == 401 && header_values(headers, "www-authenticate").any(challenge);
    let access = headers.contains_key("cf-access-aud") || headers.contains_key("cf-access-domain");
    let sent_on = header_values(headers, "location").any(sign_in_address);
    let mut cookies = header_values(headers, "set-cookie").filter_map(|c| c.split('=').next());
    let cookie = cookies.any(|name| is_gateway_cookie(name.trim()));
    is_html(media) || basic || access || sent_on || cookie || login_sent
}

// Whether a Location sends the request on to a gateway's sign-in: one that
// comes back after it (`rd` and `rm` in the query), an Authentik outpost,
// or Cloudflare Access.
fn sign_in_address(location: &str) -> bool {
    let Ok(uri) = location.parse::<ureq::http::Uri>() else { return false };
    let query = uri.query().unwrap_or_default();
    let names: Vec<&str> = query.split('&').filter_map(|pair| pair.split('=').next()).collect();
    let back = names.contains(&"rd") && names.contains(&"rm");
    let path = uri.path();
    let outpost = path.contains("/outpost.goauthentik.io/") || path.contains("/cdn-cgi/access/");
    let access = uri.host().is_some_and(|h| h.to_ascii_lowercase().ends_with(".cloudflareaccess.com"));
    back || outpost || access
}

// Whether a cookie's name is one a gateway keeps its session in.
fn is_gateway_cookie(name: &str) -> bool {
    name.starts_with("authentik_proxy") || name == "CF_Authorization" || name == "authelia_session"
}

// Whether the first request for `url` carries a sign-in: a header, among
// those `follow` sends there, with a name gateways read one from.
fn sends_login(url: &str, opts: &HttpOptions) -> bool {
    const LOGIN: [&str; 3] = ["Authorization", "CF-Access-Client-Id", "CF-Access-Client-Secret"];
    let secure = origin(url).is_some_and(|(scheme, _, _)| scheme == "https");
    let mut sent = opts.headers.iter().chain(opts.secure_headers.iter().filter(|_| secure));
    sent.any(|(name, _)| LOGIN.iter().any(|login| name.eq_ignore_ascii_case(login)))
}

// A failure that asking again would only repeat: the server said no, a
// sign-in page answered in its place, or it sends another file now.
fn is_final(failure: &Failure) -> bool {
    failure.reopen
        || matches!(failure.kind, ErrorKind::SignInPage | ErrorKind::FileChanged)
        || failure.status.is_some_and(|s| (400..500).contains(&s))
}

// A content type without its parameters, in lowercase.
fn plain_type(content_type: &str) -> String {
    content_type.split(';').next().unwrap_or("").trim().to_ascii_lowercase()
}

// How many audio bytes a live server puts between its in-stream titles.
fn metaint(response: &ureq::http::Response<ureq::Body>) -> Option<usize> {
    let value = response.headers().get("icy-metaint")?.to_str().ok()?;
    value.trim().parse().ok().filter(|&n| n > 0)
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
