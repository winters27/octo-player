//! What can go wrong, in a form the app can act on.

use std::fmt;

/// The kind of failure, so the app can pick what to show or do.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum ErrorKind {
    /// The file or the server's song is not there.
    NotFound,
    /// The server said no, for example to a sign-in that has run out.
    Http,
    /// No connection, a timeout, or a connection that kept dropping.
    Network,
    /// A format or codec the engine cannot play.
    Unsupported,
    /// The file is damaged past playing.
    Decode,
    /// The sound device failed or is gone.
    Device,
    /// A value passed in that makes no sense.
    InvalidArgument,
    Other,
}

/// A failure with its kind and a message for logs.
#[derive(Clone, Debug, PartialEq)]
pub struct Failure {
    pub kind: ErrorKind,
    pub message: String,
    /// The HTTP status, when the server answered with one.
    pub status: Option<u16>,
}

impl Failure {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into(), status: None }
    }

    pub fn http(status: u16) -> Self {
        let kind = if status == 404 || status == 410 { ErrorKind::NotFound } else { ErrorKind::Http };
        Self { kind, message: format!("server answered {status}"), status: Some(status) }
    }
}

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}: {}", self.kind, self.message)
    }
}

impl std::error::Error for Failure {}

impl From<Failure> for std::io::Error {
    fn from(f: Failure) -> Self {
        std::io::Error::other(f)
    }
}

/// The error the app sees from engine calls.
#[derive(Clone, Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("{kind:?}: {message}")]
    Failed { kind: ErrorKind, message: String },
}

impl From<Failure> for EngineError {
    fn from(f: Failure) -> Self {
        EngineError::Failed { kind: f.kind, message: f.message }
    }
}
