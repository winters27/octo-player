import Foundation
import OctoKit
import Security

/// Looks at client certificates for the sign-in form without putting them to use: the server in
/// use keeps its own certificate until a new one is saved with a server.
enum CertificateCheck {
    /// Opens a PKCS #12 file with its password and finds its identity, or throws when it cannot.
    /// Answers the certificate's last valid day, when it names one.
    @discardableResult
    static func validate(_ data: Data, password: String) throws -> Date? {
        var items: CFArray?
        let status = SecPKCS12Import(data as CFData, [kSecImportExportPassphrase as String: password] as CFDictionary, &items)
        guard status == errSecSuccess, let first = (items as? [[String: Any]])?.first,
              let found = first[kSecImportItemIdentity as String]
        else { throw ClientCertificateError.unreadable }
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(found as! SecIdentity, &certificate) == errSecSuccess, let certificate else { return nil }
        return SecCertificateCopyNotValidAfterDate(certificate).map { $0 as Date }
    }

    /// A kept server's certificate's last valid day: its file and password read from the keychain and
    /// the file opened once. Nil when either is missing or the file does not open. It blocks, so it
    /// runs off the main actor.
    static func savedExpiry(forServer id: String) -> Date? {
        guard let data = Keychain.readData(for: "server.\(id).certificate") else { return nil }
        let password = Keychain.read(for: "server.\(id).certificate.password") ?? ""
        return try? validate(data, password: password)
    }

    /// Whether a kept server's certificate can be used: kept; gone (it is kept on this device only,
    /// so a phone restored from a backup has the server but not its certificate); or unreadable
    /// just now, which is never taken for gone.
    static func state(forServer id: String) -> CertificateState {
        CertificateState(data: Keychain.lookup(for: "server.\(id).certificate"),
                         password: Keychain.lookup(for: "server.\(id).certificate.password"))
    }
}
