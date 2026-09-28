//! Certificates the listener chose to trust although the system does not,
//! each for its own host, and the TLS connection that uses them.
//!
//! Every certificate is first checked the system's way. Only when that
//! fails is a pin looked at: the connection goes ahead when the host has a
//! pin equal to the SHA-256 of the certificate the server showed. A pin
//! never applies to any other host, and nothing else is ever let through.
//! The same rule as the app's own HTTP client.

use std::collections::HashMap;
use std::fmt;
use std::io::{Read, Write};
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme, StreamOwned};
use ureq::unversioned::transport::{
    Buffers, ConnectionDetails, Connector, Either, LazyBuffers, NextTimeout, Transport, TransportAdapter,
};

/// The pinned certificates, shared by every connection an engine makes.
#[derive(Debug, Default)]
pub struct Trust {
    // SHA-256 fingerprints by host.
    pins: RwLock<HashMap<String, Vec<[u8; 32]>>>,
    // Moves on with every change, so TLS settings made before it are made again.
    generation: AtomicU64,
}

impl Trust {
    /// Replaces every pin with these, as (host, fingerprint) pairs. A host
    /// is matched in any case; a fingerprint is hex, and anything in it
    /// that is not hex (colons, spaces) is ignored.
    pub fn replace(&self, pins: impl IntoIterator<Item = (String, String)>) {
        let mut by_host: HashMap<String, Vec<[u8; 32]>> = HashMap::new();
        for (host, fingerprint) in pins {
            match parse_fingerprint(&fingerprint) {
                Some(digest) => by_host.entry(host_key(&host)).or_default().push(digest),
                None => log::warn!("ignoring a trusted certificate for {host}: not a SHA-256 fingerprint"),
            }
        }
        *self.pins.write().unwrap_or_else(|e| e.into_inner()) = by_host;
        self.generation.fetch_add(1, Ordering::SeqCst);
    }

    /// Whether this host has a pin for this certificate (its DER bytes).
    pub fn allows(&self, host: &str, certificate: &[u8]) -> bool {
        let pins = self.pins.read().unwrap_or_else(|e| e.into_inner());
        let Some(pinned) = pins.get(&host_key(host)) else {
            return false;
        };
        let digest = ring::digest::digest(&ring::digest::SHA256, certificate);
        pinned.iter().any(|pin| pin[..] == *digest.as_ref())
    }

    fn generation(&self) -> u64 {
        self.generation.load(Ordering::SeqCst)
    }
}

// Hosts are kept in lowercase, and addresses in their usual written form,
// so "[::1]" and "0:0::1" are the same host.
fn host_key(host: &str) -> String {
    let bare = host.trim().trim_start_matches('[').trim_end_matches(']');
    match bare.parse::<IpAddr>() {
        Ok(ip) => ip.to_string(),
        Err(_) => bare.to_ascii_lowercase(),
    }
}

// Lowercase hex with everything else dropped, as the app keeps it: exactly
// 32 bytes, or nothing.
fn parse_fingerprint(text: &str) -> Option<[u8; 32]> {
    let digits: Vec<u8> =
        text.chars().filter_map(|c| c.to_ascii_lowercase().to_digit(16)).map(|d| d as u8).collect();
    if digits.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (byte, pair) in out.iter_mut().zip(digits.chunks(2)) {
        *byte = pair[0] << 4 | pair[1];
    }
    Some(out)
}

// The system's check, with a pinned certificate let through for its own
// host when the system says no. The handshake's signatures are always
// checked the system's way, so a server has to hold the pinned
// certificate's key.
#[derive(Debug)]
struct PinnedVerifier {
    system: Arc<dyn ServerCertVerifier>,
    trust: Arc<Trust>,
}

impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        ocsp_response: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        match self.system.verify_server_cert(end_entity, intermediates, server_name, ocsp_response, now) {
            Ok(verified) => Ok(verified),
            Err(e) if self.trust.allows(&server_name.to_str(), end_entity.as_ref()) => {
                log::debug!("{} showed a certificate the listener trusted ({e})", server_name.to_str());
                Ok(ServerCertVerified::assertion())
            }
            Err(e) => Err(e),
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.system.verify_tls12_signature(message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.system.verify_tls13_signature(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.system.supported_verify_schemes()
    }

    fn root_hint_subjects(&self) -> Option<&[rustls::DistinguishedName]> {
        self.system.root_hint_subjects()
    }
}

/// Puts TLS on HTTPS connections, checked as above.
pub struct PinnedTls {
    trust: Arc<Trust>,
    // The settings made for the trust's generation, reused until it changes.
    config: Mutex<Option<(u64, Arc<ClientConfig>)>>,
}

impl PinnedTls {
    pub fn new(trust: Arc<Trust>) -> Self {
        Self { trust, config: Mutex::new(None) }
    }

    // Made again after the pins change, which also forgets sessions a
    // removed pin let in, so they cannot be picked up again.
    fn config(&self, sni: bool) -> Result<Arc<ClientConfig>, rustls::Error> {
        let generation = self.trust.generation();
        let mut cached = self.config.lock().unwrap_or_else(|e| e.into_inner());
        if let Some((made_for, config)) = cached.as_ref()
            && *made_for == generation
        {
            return Ok(config.clone());
        }
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let system = Arc::new(rustls_platform_verifier::Verifier::new(provider.clone())?);
        let verifier = Arc::new(PinnedVerifier { system, trust: self.trust.clone() });
        let mut config = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()?
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        config.enable_sni = sni;
        let config = Arc::new(config);
        *cached = Some((generation, config.clone()));
        Ok(config)
    }
}

impl fmt::Debug for PinnedTls {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PinnedTls").finish()
    }
}

impl<In: Transport> Connector<In> for PinnedTls {
    type Out = Either<In, TlsTransport>;

    fn connect(
        &self,
        details: &ConnectionDetails,
        chained: Option<In>,
    ) -> Result<Option<Self::Out>, ureq::Error> {
        let Some(transport) = chained else {
            return Ok(None);
        };
        if !details.needs_tls() || transport.is_tls() {
            return Ok(Some(Either::A(transport)));
        }
        let config = self.config(details.config.tls_config().use_sni())?;
        let host = details.uri.host().unwrap_or_default();
        let bare = host.trim_start_matches('[').trim_end_matches(']');
        let name =
            ServerName::try_from(bare).map_err(|_| ureq::Error::Tls("not a valid host name"))?.to_owned();
        let mut conn = ClientConnection::new(config, name)?;
        let mut sock = TransportAdapter::new(Box::new(transport) as Box<dyn Transport>);
        sock.set_timeout(details.timeout);
        conn.complete_io(&mut sock)?;
        let buffers =
            LazyBuffers::new(details.config.input_buffer_size(), details.config.output_buffer_size());
        Ok(Some(Either::B(TlsTransport { buffers, stream: StreamOwned { conn, sock } })))
    }
}

/// A connection with TLS on it.
pub struct TlsTransport {
    buffers: LazyBuffers,
    stream: StreamOwned<ClientConnection, TransportAdapter>,
}

impl Transport for TlsTransport {
    fn buffers(&mut self) -> &mut dyn Buffers {
        &mut self.buffers
    }

    fn transmit_output(&mut self, amount: usize, timeout: NextTimeout) -> Result<(), ureq::Error> {
        self.stream.get_mut().set_timeout(timeout);
        let output = &self.buffers.output()[..amount];
        self.stream.write_all(output)?;
        Ok(())
    }

    fn await_input(&mut self, timeout: NextTimeout) -> Result<bool, ureq::Error> {
        self.stream.get_mut().set_timeout(timeout);
        let input = self.buffers.input_append_buf();
        let amount = self.stream.read(input)?;
        self.buffers.input_appended(amount);
        Ok(amount > 0)
    }

    fn is_open(&mut self) -> bool {
        self.stream.get_mut().get_mut().is_open()
    }

    fn is_tls(&self) -> bool {
        true
    }
}

impl fmt::Debug for TlsTransport {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TlsTransport").finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DIGEST: &str = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    #[test]
    fn fingerprints_read_like_the_app_keeps_them() {
        let plain = parse_fingerprint(DIGEST).unwrap();
        let marked = DIGEST
            .to_uppercase()
            .as_bytes()
            .chunks(2)
            .map(|p| std::str::from_utf8(p).unwrap())
            .collect::<Vec<_>>()
            .join(":");
        assert_eq!(parse_fingerprint(&marked), Some(plain));
        assert_eq!(parse_fingerprint(&format!(" {DIGEST} ")), Some(plain));
        assert_eq!(parse_fingerprint(&DIGEST[2..]), None, "too short");
        assert_eq!(parse_fingerprint(&format!("{DIGEST}00")), None, "too long");
        assert_eq!(parse_fingerprint(""), None);
    }

    #[test]
    fn hosts_match_in_any_case_and_address_form() {
        assert_eq!(host_key("Music.Example.COM"), "music.example.com");
        assert_eq!(host_key("[::1]"), host_key("0:0:0::1"));
        assert_eq!(host_key("127.0.0.1"), "127.0.0.1");
    }

    #[test]
    fn a_pin_is_for_its_own_host_and_certificate_only() {
        let certificate = b"a certificate";
        let digest = ring::digest::digest(&ring::digest::SHA256, certificate);
        let hex: String = digest.as_ref().iter().map(|b| format!("{b:02X}")).collect();
        let trust = Trust::default();
        assert!(!trust.allows("music.example.com", certificate));
        trust.replace([("Music.Example.com".to_string(), hex.clone())]);
        assert!(trust.allows("music.example.com", certificate));
        assert!(trust.allows("MUSIC.example.com", certificate));
        assert!(!trust.allows("other.example.com", certificate));
        assert!(!trust.allows("music.example.com", b"another certificate"));
        // Replacing the set forgets what was there.
        trust.replace([("other.example.com".to_string(), hex)]);
        assert!(!trust.allows("music.example.com", certificate));
        assert!(trust.allows("other.example.com", certificate));
        trust.replace([]);
        assert!(!trust.allows("other.example.com", certificate));
    }
}
