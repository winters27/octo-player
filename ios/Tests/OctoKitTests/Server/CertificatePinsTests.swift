import Foundation
import Testing
@testable import OctoKit

/// Pins against fixtures/certificate-pins (made with openssl, read by every app's tests): a key
/// pin survives a renewal that keeps the key, a new key is a different certificate, and a pin
/// kept before key pins (the whole certificate's SHA-256, in colon pairs) still counts.
struct CertificatePinsTests {
    private static func fixture(_ name: String) throws -> Data {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("fixtures/certificate-pins/\(name)")
            if FileManager.default.fileExists(atPath: candidate.path) { return try Data(contentsOf: candidate) }
            dir = dir.deletingLastPathComponent()
        }
        throw FixtureMissing(name: name)
    }

    private static func certificate(_ name: String) throws -> [UInt8] {
        let pem = String(decoding: try fixture("\(name).pem"), as: UTF8.self)
        // Any line ending: a checkout made on Windows ends the fixture's lines with \r\n.
        let body = pem.components(separatedBy: .newlines).filter { !$0.hasPrefix("-----") }.joined()
        return [UInt8](try #require(Data(base64Encoded: body)))
    }

    private static func expected(_ name: String) throws -> (fingerprint: String, pin: String) {
        let json = try JSONSerialization.jsonObject(with: try fixture("pins.json")) as? [String: Any]
        let all = try #require(json?["certificates"] as? [String: [String: String]])
        let one = try #require(all[name])
        return (try #require(one["fingerprint"]), try #require(one["pin"]))
    }

    @Test func pinsMatchWhatOpensslSays() throws {
        for name in ["first", "renewed", "new-key"] {
            let der = try Self.certificate(name)
            let want = try Self.expected(name)
            #expect(CertificatePins.pin(ofCertificate: der) == want.pin, "\(name)")
            #expect(Hashes.sha256Hex(der) == want.fingerprint, "\(name)")
        }
    }

    @Test func aKeyPinSurvivesARenewalWithTheSameKey() throws {
        let first = try Self.certificate("first"), renewed = try Self.certificate("renewed"), newKey = try Self.certificate("new-key")
        let pin = CertificatePins.pin(ofCertificate: first)
        #expect(CertificatePins.matches(pin, certificate: renewed))
        #expect(!CertificatePins.matches(pin, certificate: newKey))
        #expect(CertificatePins.matches(" SPKI:" + pin.dropFirst(5).uppercased(), certificate: renewed))
    }

    /// Pins kept before key pins: the whole certificate's SHA-256 as colon-separated pairs.
    @Test func anOldWholeCertificatePinStillCounts() throws {
        let first = try Self.certificate("first"), renewed = try Self.certificate("renewed")
        let old = try Self.expected("first").fingerprint.uppercased().chunked(2).joined(separator: ":")
        #expect(CertificatePins.matches(old, certificate: first))
        #expect(!CertificatePins.matches(old, certificate: renewed))
    }

    @Test func somethingThatIsNotACertificateHasNoKey() {
        #expect(CertificatePins.keyInfo(ofCertificate: Array("not a certificate".utf8)) == nil)
        #expect(CertificatePins.keyInfo(ofCertificate: [0x30, 0x82, 0xFF]) == nil)
        #expect(CertificatePins.keyInfo(ofCertificate: []) == nil)
    }
}

private extension String {
    func chunked(_ size: Int) -> [String] {
        stride(from: 0, to: count, by: size).map { start in
            let from = index(startIndex, offsetBy: start)
            return String(self[from..<(index(from, offsetBy: size, limitedBy: endIndex) ?? endIndex)])
        }
    }
}
