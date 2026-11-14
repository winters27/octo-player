import Foundation

/// What a keychain read found. A read that failed is never taken for an item that is not there.
public enum SecretRead: Equatable, Sendable {
    case found(Data)
    case missing
    case failed

    /// `errSecItemNotFound`, written out: OctoKit builds where `Security` does not.
    public static let itemNotFound: Int32 = -25300

    /// A read as `SecItemCopyMatching` answered it: success with data is the item; success with
    /// no data or empty data, and "not found", are no item; any other status failed.
    public init(status: Int32, data: Data?) {
        if status == 0, let data, !data.isEmpty {
            self = .found(data)
        } else if status == 0 || status == Self.itemNotFound {
            self = .missing
        } else {
            self = .failed
        }
    }
}

/// Whether a kept server's client certificate can be used, from the reads of its data and its
/// password, the data deciding first: kept; stale, gone from the keychain (a phone restored from a
/// backup keeps the server but not its certificate); or unknown, unreadable just now, which is
/// never taken for gone.
public enum CertificateState: Equatable, Sendable {
    case kept
    case stale
    case unknown

    public init(data: SecretRead, password: SecretRead) {
        switch (data, password) {
        case (.missing, _): self = .stale
        case (.failed, _), (.found, .failed): self = .unknown
        case (.found, _): self = .kept
        }
    }

    /// Whether the form shows the certificate, with its name and Remove.
    public var shown: Bool { self != .stale }
}

/// The keychain as the keep order uses it.
public protocol SecretItems {
    func lookup(for account: String) -> SecretRead
    /// True once the keychain holds `data` for `account`.
    func saveData(_ data: Data, for account: String) -> Bool
    func delete(for account: String)
}

/// The accounts one server's secrets are filed under.
public struct ServerSecretNames: Sendable {
    public let secret: String
    public let headers: String
    public let certificate: String
    public let certificatePassword: String

    public init(secret: String, headers: String, certificate: String, certificatePassword: String) {
        self.secret = secret
        self.headers = headers
        self.certificate = certificate
        self.certificatePassword = certificatePassword
    }
}

/// What a keep does with the server's client certificate.
public enum CertificateKeep: Sendable {
    /// Leaves it as it is; a move to a new id carries it along.
    case unchanged
    /// A certificate picked now, with its password.
    case picked(data: Data, password: String)
    /// Takes it off.
    case removed
}

/// Why a keep changed nothing, or left the certificate to be chosen again.
public enum KeepSecretsError: Error, Equatable, Sendable {
    case unreadCertificate
    case unreadSaved
    case unsaved
    case halfSaved

    public var words: String {
        switch self {
        case .unreadCertificate: "Octo couldn't read the certificate, so nothing changed. Try again, or remove the certificate."
        case .unreadSaved: "Octo couldn't read this server's details from the keychain, so nothing changed. Try again."
        case .unsaved: "Octo couldn't save this server to the keychain, so nothing changed. Try again."
        case .halfSaved: "Octo couldn't save this server's details to the keychain. Choose the certificate again."
        }
    }
}

/// Writes a kept server's secrets under `id` so that none is lost on the way. First it reads every
/// item it will write over, and on a move from `oldId` the certificate it carries; a read that
/// failed, or a carried certificate that is gone, stops it with nothing written. Then it writes
/// the password, the headers and the certificate. If any write fails, it puts back only the items
/// whose write landed (the old value, or no item where there was none) and stops; a certificate it
/// could not put back is `halfSaved`. A Remove deletes the certificate only once every write
/// landed. It never deletes under the old id: it returns that id, for the caller to forget once
/// the server list naming `id` is saved.
public func keepSecrets(_ items: some SecretItems, from oldId: String?, to id: String, carriesCertificate: Bool,
                        secret: String, headers: Data, certificate: CertificateKeep,
                        names: (String) -> ServerSecretNames) throws -> String? {
    let target = names(id)
    let moving = oldId != nil && oldId != id
    var writes: [(account: String, data: Data, certificate: Bool)] = [
        (target.secret, Data(secret.utf8), false),
        (target.headers, headers, false),
    ]
    switch certificate {
    case .picked(let data, let password):
        writes.append((target.certificate, data, true))
        writes.append((target.certificatePassword, Data(password.utf8), true))
    case .unchanged:
        if moving, carriesCertificate, let oldId {
            let old = names(oldId)
            guard case .found(let data) = items.lookup(for: old.certificate) else { throw KeepSecretsError.unreadCertificate }
            let password: Data
            switch items.lookup(for: old.certificatePassword) {
            case .found(let kept): password = kept
            case .missing: password = Data()
            case .failed: throw KeepSecretsError.unreadCertificate
            }
            writes.append((target.certificate, data, true))
            writes.append((target.certificatePassword, password, true))
        }
    case .removed:
        break
    }
    var before: [String: SecretRead] = [:]
    for write in writes {
        let read = items.lookup(for: write.account)
        if read == .failed { throw write.certificate ? KeepSecretsError.unreadCertificate : KeepSecretsError.unreadSaved }
        before[write.account] = read
    }
    var landed: [(account: String, certificate: Bool)] = []
    var failed = false
    for write in writes {
        if items.saveData(write.data, for: write.account) {
            landed.append((write.account, write.certificate))
        } else {
            failed = true
        }
    }
    if failed {
        var halfSaved = false
        for item in landed {
            if case .found(let old) = before[item.account] {
                if !items.saveData(old, for: item.account), item.certificate { halfSaved = true }
            } else {
                items.delete(for: item.account)
            }
        }
        throw halfSaved ? KeepSecretsError.halfSaved : KeepSecretsError.unsaved
    }
    if case .removed = certificate {
        items.delete(for: target.certificate)
        items.delete(for: target.certificatePassword)
    }
    return moving ? oldId : nil
}
