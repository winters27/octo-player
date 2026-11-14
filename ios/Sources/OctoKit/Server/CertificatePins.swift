import Foundation

/// How a server certificate the person trusted is kept, by host: `spki:` and the SHA-256 of the
/// certificate's public key (its SubjectPublicKeyInfo), so a renewal with the same key still
/// counts. A pin without the mark is the SHA-256 of a whole certificate, as pins were kept before
/// (written as colon-separated pairs); those count for exactly that certificate, as they always
/// did. The same rule as the phone and desktop apps (`Certificates.kt`).
public enum CertificatePins {
    public static let keyMark = "spki:"

    /// The pin a certificate (its DER bytes) is kept as: its key's, or the whole certificate's
    /// when its key can't be read.
    public static func pin(ofCertificate der: [UInt8]) -> String {
        guard let key = keyInfo(ofCertificate: der) else { return Hashes.sha256Hex(der) }
        return keyMark + Hashes.sha256Hex(key)
    }

    /// A pin as it is compared: a key pin keeps its mark, and its hex (or a whole certificate's)
    /// loses colons, spaces and capitals.
    public static func normalize(_ pin: String) -> String {
        let trimmed = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix(keyMark) {
            return keyMark + hexOnly(String(trimmed.dropFirst(keyMark.count)))
        }
        return hexOnly(trimmed)
    }

    /// Whether a kept pin stands for this certificate (its DER bytes).
    public static func matches(_ pin: String, certificate der: [UInt8]) -> Bool {
        let kept = normalize(pin)
        if kept.hasPrefix(keyMark) {
            guard let key = keyInfo(ofCertificate: der) else { return false }
            return kept == keyMark + Hashes.sha256Hex(key)
        }
        return kept == Hashes.sha256Hex(der)
    }

    /// The SubjectPublicKeyInfo of a DER certificate, whole: a Certificate is a SEQUENCE whose
    /// first element, tbsCertificate, holds an optional [0] version, then serialNumber,
    /// signature, issuer, validity, subject and subjectPublicKeyInfo. Nil when it isn't one.
    public static func keyInfo(ofCertificate der: [UInt8]) -> [UInt8]? {
        guard let certificate = element(der[...]), let tbs = element(certificate.contents) else { return nil }
        var rest = tbs.contents
        if rest.first == 0xA0 {
            guard let version = element(rest) else { return nil }
            rest = version.rest
        }
        for _ in 0..<5 {
            guard let skipped = element(rest) else { return nil }
            rest = skipped.rest
        }
        guard let key = element(rest), key.tag == 0x30 else { return nil }
        return Array(key.whole)
    }

    /// One DER element at the start of `input`: its tag, the element whole, its contents, and
    /// what follows it. Nil when it runs past the input.
    private static func element(_ input: ArraySlice<UInt8>) -> (tag: UInt8, whole: ArraySlice<UInt8>, contents: ArraySlice<UInt8>, rest: ArraySlice<UInt8>)? {
        let start = input.startIndex
        guard input.count >= 2 else { return nil }
        let tag = input[start]
        let first = Int(input[start + 1])
        var length = first
        var header = 2
        if first >= 0x80 {
            let count = first & 0x7F
            guard count > 0, count <= 4, input.count >= 2 + count else { return nil }
            length = input[(start + 2)..<(start + 2 + count)].reduce(0) { $0 << 8 | Int($1) }
            header = 2 + count
        }
        guard length >= 0, input.count >= header + length else { return nil }
        let end = start + header + length
        return (tag, input[start..<end], input[(start + header)..<end], input[end...])
    }

    private static func hexOnly(_ text: String) -> String {
        String(text.lowercased().filter { $0.isHexDigit })
    }
}
