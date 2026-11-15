//! Certificates the listener chose to trust although the system does not,
//! each for its own host, the client certificate offered to the server,
//! and the TLS connection that uses them.
//!
//! Every certificate is first checked the system's way. Only when that
//! fails is a pin looked at: the connection goes ahead when the host has a
//! pin for the certificate the server showed, either the SHA-256 of its
//! public key (written `spki:` and the hex, which a renewal with the same
//! key keeps) or, as pins were kept before, of the whole certificate. A
//! pin never applies to any other host, and nothing else is ever let
//! through. The same rule as the app's own HTTP client.
//!
//! A client certificate is offered only to the origins (scheme, host and
//! port) set with it, and to no other server. A change to the pins or the
//! client certificate applies from the next connection: one made before it
//! is never used again.

use std::collections::HashMap;
use std::fmt;
use std::io::{Read, Write};
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::sign::CertifiedKey;
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme, StreamOwned};
use ureq::unversioned::transport::{
    Buffers, ConnectionDetails, Connector, Either, LazyBuffers, NextTimeout, Transport, TransportAdapter,
};

/// The pinned certificates and the client certificate, shared by every
/// connection an engine makes.
#[derive(Debug, Default)]
pub struct Trust {
    // Pins by host.
    pins: RwLock<HashMap<String, Vec<Pin>>>,
    // The client certificate and the origins it is offered to.
    identity: RwLock<Option<Arc<Identity>>>,
    // Moves on with every change, so TLS settings made before it are made
    // again and connections made before it are not used again.
    generation: AtomicU64,
}

impl Trust {
    /// Replaces every pin with these, as (host, pin) pairs. A host is
    /// matched in any case; a pin is `spki:` and the hex SHA-256 of a
    /// public key, or the hex SHA-256 of a whole certificate, and anything
    /// in the hex that is not hex (colons, spaces) is ignored.
    pub fn replace(&self, pins: impl IntoIterator<Item = (String, String)>) {
        let mut by_host: HashMap<String, Vec<Pin>> = HashMap::new();
        for (host, pin) in pins {
            match parse_pin(&pin) {
                Some(pin) => by_host.entry(host_key(&host)).or_default().push(pin),
                None => log::warn!("ignoring a trusted certificate for {host}: not a SHA-256 pin"),
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
        let whole = ring::digest::digest(&ring::digest::SHA256, certificate);
        let key = spki_of(certificate).map(|spki| ring::digest::digest(&ring::digest::SHA256, spki));
        pinned.iter().any(|pin| match pin {
            Pin::Certificate(digest) => digest[..] == *whole.as_ref(),
            Pin::Key(digest) => key.as_ref().is_some_and(|key| digest[..] == *key.as_ref()),
        })
    }

    /// Offers this client certificate to its own origins from the next
    /// connection on, or none. It is in place before the generation moves,
    /// so a connection that sees the new generation also sees it.
    pub fn set_identity(&self, identity: Option<Identity>) {
        *self.identity.write().unwrap_or_else(|e| e.into_inner()) = identity.map(Arc::new);
        self.generation.fetch_add(1, Ordering::SeqCst);
    }

    fn identity(&self) -> Option<Arc<Identity>> {
        self.identity.read().unwrap_or_else(|e| e.into_inner()).clone()
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

// An origin as it is matched: the scheme in lowercase and the host as
// `host_key` writes it, with the port.
type OriginKey = (String, String, u16);

fn origin_key(scheme: &str, host: &str, port: u16) -> OriginKey {
    (scheme.trim().to_ascii_lowercase(), host_key(host), port)
}

// The origin of a connection that needs TLS: https, at port 443 unless the
// address names another.
fn tls_origin(uri: &ureq::http::Uri) -> OriginKey {
    let scheme = uri.scheme_str().unwrap_or_default();
    origin_key(scheme, uri.host().unwrap_or_default(), uri.port_u16().unwrap_or(443))
}

/// A client certificate: its chain, the client's own first, its private
/// key, and the origins it is offered to.
pub struct Identity {
    origins: Vec<OriginKey>,
    chain: Vec<CertificateDer<'static>>,
    key: PrivatePkcs8KeyDer<'static>,
}

impl Identity {
    /// A client certificate offered to these origins, each a scheme, host
    /// and port, with its chain in DER (the client's own first) and its
    /// PKCS#8 DER key. Refused, saying why, when the engine could not
    /// present it (see `identity_problem`).
    pub fn new(
        origins: impl IntoIterator<Item = (String, String, u16)>,
        chain: Vec<Vec<u8>>,
        key: Vec<u8>,
    ) -> Result<Self, String> {
        if let Some(problem) = identity_problem(&chain, &key) {
            return Err(problem);
        }
        let origins: Vec<OriginKey> = origins.into_iter().map(|(s, h, p)| origin_key(&s, &h, p)).collect();
        let chain = chain.into_iter().map(CertificateDer::from).collect();
        Ok(Self { origins, chain, key: PrivatePkcs8KeyDer::from(key) })
    }
}

// By hand, so the key never reaches a log: only the origins and how many
// certificates the chain holds show.
impl fmt::Debug for Identity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Identity")
            .field("origins", &self.origins)
            .field("chain", &self.chain.len())
            .finish_non_exhaustive()
    }
}

/// Why the engine could not present this chain (DER, the client's own
/// first) with this PKCS#8 DER key, or `None` when it can: there is no
/// certificate, the key is not one it signs with (RSA of at least 2048
/// bits, P-256 or P-384 carrying its public key, Ed25519), or it is not the
/// key the first certificate names.
pub fn identity_problem(chain: &[Vec<u8>], key: &[u8]) -> Option<String> {
    if chain.is_empty() {
        return Some("no certificate came with the key".into());
    }
    let chain = chain.iter().map(|der| CertificateDer::from(der.clone())).collect();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key.to_vec()));
    let provider = rustls::crypto::ring::default_provider();
    CertifiedKey::from_der(chain, key, &provider).err().map(|e| e.to_string())
}

// What a pin stands for: the SHA-256 of a certificate's public key (its
// SubjectPublicKeyInfo), or of the whole certificate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Pin {
    Key([u8; 32]),
    Certificate([u8; 32]),
}

// The mark a key pin starts with, as the app keeps it.
const KEY_PIN_MARK: &str = "spki:";

// A pin as the app keeps it: the mark and a key's hex, or a certificate's
// hex alone.
fn parse_pin(text: &str) -> Option<Pin> {
    let text = text.trim();
    match text.get(..KEY_PIN_MARK.len()) {
        Some(mark) if mark.eq_ignore_ascii_case(KEY_PIN_MARK) => {
            parse_fingerprint(&text[KEY_PIN_MARK.len()..]).map(Pin::Key)
        }
        _ => parse_fingerprint(text).map(Pin::Certificate),
    }
}

// One DER element: its tag, the element whole, its contents, and what
// follows it in the input.
type DerElement<'a> = (u8, &'a [u8], &'a [u8], &'a [u8]);

// The DER element at the start of `input`. None when it runs past the input.
fn der_next(input: &[u8]) -> Option<DerElement<'_>> {
    let tag = *input.first()?;
    let first = *input.get(1)? as usize;
    let (length, header) = if first < 0x80 {
        (first, 2)
    } else {
        let count = first & 0x7f;
        if count == 0 || count > 4 {
            return None;
        }
        let bytes = input.get(2..2 + count)?;
        (bytes.iter().fold(0usize, |n, b| n << 8 | *b as usize), 2 + count)
    };
    let end = header.checked_add(length)?;
    let whole = input.get(..end)?;
    Some((tag, whole, &whole[header..], &input[end..]))
}

// The SubjectPublicKeyInfo of a DER certificate, whole: Certificate is a
// SEQUENCE whose first element, tbsCertificate, holds an optional [0]
// version, then serialNumber, signature, issuer, validity, subject, and
// subjectPublicKeyInfo.
fn spki_of(certificate: &[u8]) -> Option<&[u8]> {
    let (_, _, certificate, _) = der_next(certificate)?;
    let (_, _, mut tbs, _) = der_next(certificate)?;
    if tbs.first() == Some(&0xa0) {
        tbs = der_next(tbs)?.3;
    }
    for _ in 0..5 {
        tbs = der_next(tbs)?.3;
    }
    let (tag, spki, _, _) = der_next(tbs)?;
    (tag == 0x30).then_some(spki)
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

/// Puts TLS on HTTPS connections, checked as above, offering the client
/// certificate to its own origins only.
pub struct PinnedTls {
    trust: Arc<Trust>,
    // The settings made for the trust's generation, one set for each way of
    // naming servers, reused until the generation moves.
    sets: Mutex<Vec<Arc<Made>>>,
}

// The TLS settings made for one generation of the trust and one way of
// naming servers: one offering no client certificate, and one offering the
// identity there was then, kept with the origins it is for. A connection
// picks from the set its origins came from, so it never offers one
// identity on the strength of another's origins.
struct Made {
    generation: u64,
    sni: bool,
    plain: Arc<ClientConfig>,
    identified: Option<(Vec<OriginKey>, Arc<ClientConfig>)>,
}

impl Made {
    // The settings for a connection to `origin`.
    fn for_origin(&self, origin: &OriginKey) -> &Arc<ClientConfig> {
        match &self.identified {
            Some((origins, config)) if origins.contains(origin) => config,
            _ => &self.plain,
        }
    }
}

impl PinnedTls {
    pub fn new(trust: Arc<Trust>) -> Self {
        Self { trust, sets: Mutex::new(Vec::new()) }
    }

    // Made again after the pins or the identity change, which also forgets
    // the sessions made before, so none can be picked up again. The
    // generation is read under the lock, so no caller comes to the sets with
    // a generation older than one already kept there, and before the
    // identity, so a set kept for a generation never holds an identity older
    // than it.
    fn made(&self, sni: bool) -> Result<Arc<Made>, rustls::Error> {
        let mut sets = self.sets.lock().unwrap_or_else(|e| e.into_inner());
        let generation = self.trust.generation();
        self.made_for(&mut sets, generation, sni)
    }

    // The settings for `generation` from `sets`, or made now and kept there.
    // Sets for an older generation go; a set for a newer one stays, so a
    // caller that read the generation before it moved never takes away the
    // settings, and the sessions they resume, of the generation in use.
    fn made_for(
        &self,
        sets: &mut Vec<Arc<Made>>,
        generation: u64,
        sni: bool,
    ) -> Result<Arc<Made>, rustls::Error> {
        if let Some(found) = sets.iter().find(|set| set.generation == generation && set.sni == sni) {
            return Ok(found.clone());
        }
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let system = Arc::new(rustls_platform_verifier::Verifier::new(provider.clone())?);
        let verifier = Arc::new(PinnedVerifier { system, trust: self.trust.clone() });
        let plain = settings(&provider, &verifier, None, sni)?;
        let identified = match self.trust.identity() {
            Some(identity) => {
                let config = settings(&provider, &verifier, Some(&identity), sni)?;
                Some((identity.origins.clone(), config))
            }
            None => None,
        };
        let made = Arc::new(Made { generation, sni, plain, identified });
        sets.retain(|set| set.generation >= generation);
        sets.push(made.clone());
        Ok(made)
    }
}

// TLS settings checked as above, offering `identity` when there is one.
// Each is made on its own, with sessions of its own, so a session made
// without the identity is never resumed with it, or the other way round.
fn settings(
    provider: &Arc<CryptoProvider>,
    verifier: &Arc<PinnedVerifier>,
    identity: Option<&Identity>,
    sni: bool,
) -> Result<Arc<ClientConfig>, rustls::Error> {
    let builder = ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(verifier.clone());
    let mut config = match identity {
        Some(identity) => {
            let key = PrivateKeyDer::Pkcs8(identity.key.clone_key());
            builder.with_client_auth_cert(identity.chain.clone(), key)?
        }
        None => builder.with_no_client_auth(),
    };
    config.enable_sni = sni;
    Ok(Arc::new(config))
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
        let made = self.made(details.config.tls_config().use_sni())?;
        // The identity goes only to one of its own origins; every other
        // server is offered none.
        let config = made.for_origin(&tls_origin(details.uri)).clone();
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
        let stream = StreamOwned { conn, sock };
        let tls = TlsTransport { buffers, stream, generation: made.generation, trust: self.trust.clone() };
        Ok(Some(Either::B(tls)))
    }
}

/// A connection with TLS on it.
pub struct TlsTransport {
    buffers: LazyBuffers,
    stream: StreamOwned<ClientConnection, TransportAdapter>,
    // The trust's generation it was made in. Once that moves (a pin or the
    // client certificate changed), it counts as closed, so it is never used
    // for another request.
    generation: u64,
    trust: Arc<Trust>,
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
        self.trust.generation() == self.generation && self.stream.get_mut().get_mut().is_open()
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

    use rcgen::PublicKeyData;

    // A self-made certificate for this name with this key, numbered so two
    // with the same key differ.
    fn made(key: &rcgen::KeyPair, serial: u64) -> Vec<u8> {
        let mut params = rcgen::CertificateParams::new(vec!["music.example.com".to_string()]).unwrap();
        params.serial_number = Some(rcgen::SerialNumber::from_slice(&serial.to_be_bytes()));
        params.self_signed(key).unwrap().der().to_vec()
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    #[test]
    fn a_key_pin_is_read_with_its_mark_and_a_plain_one_as_a_certificate_pin() {
        let plain = parse_fingerprint(DIGEST).unwrap();
        assert_eq!(parse_pin(&format!("spki:{DIGEST}")), Some(Pin::Key(plain)));
        assert_eq!(parse_pin(&format!(" SPKI:{} ", DIGEST.to_uppercase())), Some(Pin::Key(plain)));
        assert_eq!(parse_pin(DIGEST), Some(Pin::Certificate(plain)));
        assert_eq!(parse_pin("spki:"), None);
    }

    // The key read out of a certificate is the key it was made with, in
    // the SubjectPublicKeyInfo form the app's pin hashes.
    #[test]
    fn the_key_in_a_certificate_is_found() {
        for alg in [&rcgen::PKCS_ECDSA_P256_SHA256, &rcgen::PKCS_ECDSA_P384_SHA384, &rcgen::PKCS_ED25519] {
            let key = rcgen::KeyPair::generate_for(alg).unwrap();
            assert_eq!(spki_of(&made(&key, 1)), Some(key.subject_public_key_info().as_slice()));
        }
        assert_eq!(spki_of(b"not a certificate"), None);
        assert_eq!(spki_of(&[0x30, 0x82, 0xff]), None);
    }

    // A server that renews its certificate with the same key stays trusted
    // by a key pin; a new key does not. A pin of the whole certificate, as
    // kept before, still lets that one certificate through.
    #[test]
    fn a_key_pin_survives_a_renewal_with_the_same_key_and_an_old_pin_still_counts() {
        let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).unwrap();
        let first = made(&key, 1);
        let renewed = made(&key, 2);
        let other = made(&rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).unwrap(), 3);
        assert_ne!(first, renewed);
        let key_pin = format!(
            "spki:{}",
            hex(ring::digest::digest(&ring::digest::SHA256, &key.subject_public_key_info()).as_ref())
        );
        let trust = Trust::default();
        trust.replace([("music.example.com".to_string(), key_pin)]);
        assert!(trust.allows("music.example.com", &first));
        assert!(trust.allows("music.example.com", &renewed));
        assert!(!trust.allows("music.example.com", &other));
        assert!(!trust.allows("other.example.com", &renewed));
        let old_pin = hex(ring::digest::digest(&ring::digest::SHA256, &first).as_ref());
        trust.replace([("music.example.com".to_string(), old_pin)]);
        assert!(trust.allows("music.example.com", &first));
        assert!(!trust.allows("music.example.com", &renewed));
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

    // Red when: `origin_key` reads the scheme or a name in its case, or an
    // address in one written form only, or `tls_origin` leaves out https's
    // own port.
    #[test]
    fn origins_match_in_any_case_and_address_form_with_https_own_port_filled_in() {
        let music = origin_key("https", "music.example.com", 443);
        assert_eq!(origin_key("HTTPS", "Music.Example.COM", 443), music);
        assert_ne!(origin_key("https", "music.example.com", 8443), music);
        assert_ne!(origin_key("http", "music.example.com", 443), music);
        assert_eq!(origin_key("https", "[::1]", 8443), origin_key("https", "0:0:0::1", 8443));
        let uri = |text: &str| text.parse::<ureq::http::Uri>().unwrap();
        assert_eq!(tls_origin(&uri("https://Music.Example.com/rest")), music);
        assert_eq!(tls_origin(&uri("https://[::1]:8443/rest")), origin_key("https", "::1", 8443));
    }

    // Red when: `identity_problem` lets through an empty chain, bytes that
    // are no key, another certificate's key or a chain that is no
    // certificate, or `Identity::new` makes one it refused.
    #[test]
    fn an_identity_is_refused_without_a_certificate_or_with_a_key_the_engine_cannot_use() {
        use crate::testing::http_server::ClientCa;
        let ca = ClientCa::generate();
        for alg in [&rcgen::PKCS_ECDSA_P256_SHA256, &rcgen::PKCS_ECDSA_P384_SHA384, &rcgen::PKCS_ED25519] {
            let client = ca.issue("listener", alg);
            assert_eq!(identity_problem(&client.chain, &client.key), None, "{alg:?}");
        }
        let a = ca.issue("a", &rcgen::PKCS_ECDSA_P256_SHA256);
        let b = ca.issue("b", &rcgen::PKCS_ECDSA_P256_SHA256);
        assert!(identity_problem(&[], &a.key).is_some(), "no certificate");
        assert!(identity_problem(&a.chain, b"not a key").is_some(), "no key");
        assert!(identity_problem(&b.chain, &a.key).is_some(), "another certificate's key");
        assert!(identity_problem(&[b"not a certificate".to_vec()], &a.key).is_some(), "not a certificate");
        let origins = [("https".to_string(), "music.example.com".to_string(), 443)];
        assert!(Identity::new(origins, b.chain.clone(), a.key.clone()).is_err(), "made although refused");
    }

    // Red when: settings made for one way of naming servers are used for
    // the other, or kept once the generation moves.
    #[test]
    fn settings_are_made_for_each_way_of_naming_servers_and_each_generation() {
        let trust = Arc::new(Trust::default());
        let tls = PinnedTls::new(trust.clone());
        let named = tls.made(true).unwrap();
        let unnamed = tls.made(false).unwrap();
        assert!(named.plain.enable_sni && !unnamed.plain.enable_sni);
        assert!(Arc::ptr_eq(&named, &tls.made(true).unwrap()), "made again with nothing changed");
        trust.replace([]);
        let again = tls.made(true).unwrap();
        assert!(!Arc::ptr_eq(&named, &again) && again.generation > named.generation);
    }

    // Red when: a caller that read the generation before it moved takes
    // away the settings already made for the newer one, so the next
    // connection makes them again and resumes none of their sessions.
    #[test]
    fn a_caller_with_an_older_generation_keeps_the_newer_settings() {
        let trust = Arc::new(Trust::default());
        let tls = PinnedTls::new(trust.clone());
        let stale = trust.generation();
        trust.replace([]);
        let newest = tls.made(true).unwrap();
        assert!(newest.generation > stale);
        {
            let mut sets = tls.sets.lock().unwrap();
            let old = tls.made_for(&mut sets, stale, true).unwrap();
            assert_eq!(old.generation, stale);
        }
        assert!(
            Arc::ptr_eq(&newest, &tls.made(true).unwrap()),
            "made again after a caller with an older generation"
        );
    }
}
