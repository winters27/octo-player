import Foundation
import OctoKit
import Testing

/// A keychain in memory: reads can fail, and each account can take a set number of writes before
/// it refuses. Every write and delete is written down.
final class FakeItems: SecretItems {
    var items: [String: Data] = [:]
    var failingReads: Set<String> = []
    var writesLeft: [String: Int] = [:]
    var deleted: [String] = []

    func lookup(for account: String) -> SecretRead {
        if failingReads.contains(account) { return .failed }
        guard let data = items[account], !data.isEmpty else { return .missing }
        return .found(data)
    }

    func saveData(_ data: Data, for account: String) -> Bool {
        if let left = writesLeft[account] {
            if left <= 0 { return false }
            writesLeft[account] = left - 1
        }
        items[account] = data
        return true
    }

    func delete(for account: String) {
        deleted.append(account)
        items[account] = nil
    }
}

private func names(_ id: String) -> ServerSecretNames {
    ServerSecretNames(secret: "server.\(id).secret", headers: "server.\(id).headers", certificate: "server.\(id).certificate",
                      certificatePassword: "server.\(id).certificate.password")
}

private let headers = Data("{}".utf8)

@Suite struct SecretReadTests {
    @Test func successWithDataIsTheItem() {
        #expect(SecretRead(status: 0, data: Data([1])) == .found(Data([1])))
    }

    @Test func successWithNoOrEmptyDataAndNotFoundAreNoItem() {
        #expect(SecretRead(status: 0, data: Data()) == .missing)
        #expect(SecretRead(status: 0, data: nil) == .missing)
        #expect(SecretRead(status: -25300, data: nil) == .missing)
    }

    @Test func anyOtherStatusFailed() {
        #expect(SecretRead(status: -25308, data: nil) == .failed)
    }
}

@Suite struct CertificateStateTests {
    @Test func dataFoundWithItsPasswordFoundOrMissingIsKept() {
        #expect(CertificateState(data: .found(Data([1])), password: .found(Data([2]))) == .kept)
        #expect(CertificateState(data: .found(Data([1])), password: .missing) == .kept)
    }

    @Test func dataMissingIsStaleWhateverThePasswordReadGave() {
        #expect(CertificateState(data: .missing, password: .found(Data([2]))) == .stale)
        #expect(CertificateState(data: .missing, password: .failed) == .stale)
    }

    @Test func aFailedReadIsUnknownNeverStale() {
        #expect(CertificateState(data: .found(Data([1])), password: .failed) == .unknown)
        #expect(CertificateState(data: .failed, password: .found(Data([2]))) == .unknown)
    }

    @Test func keptAndUnknownAreShownAndStaleIsNot() {
        #expect(CertificateState.kept.shown)
        #expect(CertificateState.unknown.shown)
        #expect(!CertificateState.stale.shown)
    }
}

@Suite struct KeepSecretsTests {
    /// Server "old" with a certificate, its items all in place.
    private func kept() -> FakeItems {
        let items = FakeItems()
        let old = names("old")
        items.items[old.secret] = Data("pw".utf8)
        items.items[old.headers] = headers
        items.items[old.certificate] = Data("cert".utf8)
        items.items[old.certificatePassword] = Data("cert-pw".utf8)
        return items
    }

    private func move(_ items: FakeItems, certificate: CertificateKeep = .unchanged) throws -> String? {
        try keepSecrets(items, from: "old", to: "new", carriesCertificate: true, secret: "pw2", headers: headers,
                        certificate: certificate, names: names)
    }

    @Test func aFailedCertificateReadOnAMoveChangesNothing() {
        let items = kept()
        items.failingReads = [names("old").certificate]
        #expect(throws: KeepSecretsError.unreadCertificate) { try move(items) }
        #expect(items.items[names("new").secret] == nil)
    }

    @Test func aFailedPasswordReadOnAServerWithNoCertificateIsUnreadSaved() {
        let items = FakeItems()
        items.failingReads = [names("s").secret]
        #expect(throws: KeepSecretsError.unreadSaved) {
            try keepSecrets(items, from: "s", to: "s", carriesCertificate: false, secret: "pw", headers: headers, certificate: .unchanged, names: names)
        }
    }

    @Test func aCarriedCertificateThatIsGoneChangesNothing() {
        let items = kept()
        items.items[names("old").certificate] = nil
        #expect(throws: KeepSecretsError.unreadCertificate) { try move(items) }
        #expect(items.items[names("new").secret] == nil)
    }

    @Test func emptyCertificateDataIsGone() {
        let items = kept()
        items.items[names("old").certificate] = Data()
        #expect(throws: KeepSecretsError.unreadCertificate) { try move(items) }
    }

    @Test func aMissingCertificatePasswordCarriesAsEmpty() throws {
        let items = kept()
        items.items[names("old").certificatePassword] = nil
        let forget = try move(items)
        #expect(forget == "old")
        #expect(items.items[names("new").certificate] == Data("cert".utf8))
        #expect(items.items[names("new").certificatePassword] == Data())
    }

    @Test func aFailedWriteOnAMoveLeavesTheOldValuesAndDeletesNothingUnderTheOldId() {
        let items = kept()
        items.writesLeft = [names("new").certificatePassword: 0]
        #expect(throws: KeepSecretsError.unsaved) { try move(items) }
        let old = names("old")
        #expect(items.items[old.secret] == Data("pw".utf8))
        #expect(items.items[old.certificate] == Data("cert".utf8))
        #expect(!items.deleted.contains { $0.hasPrefix("server.old.") })
        #expect(items.items[names("new").secret] == nil)
        #expect(items.items[names("new").certificate] == nil)
    }

    @Test func aHalfWriteOnTheSameIdPutsTheOldCertificateBack() {
        let items = kept()
        let same = names("old")
        items.writesLeft = [same.certificatePassword: 0]
        #expect(throws: KeepSecretsError.unsaved) {
            try keepSecrets(items, from: "old", to: "old", carriesCertificate: true, secret: "pw2", headers: headers,
                            certificate: .picked(data: Data("new-cert".utf8), password: "new-pw"), names: names)
        }
        #expect(items.items[same.certificate] == Data("cert".utf8))
        #expect(items.items[same.certificatePassword] == Data("cert-pw".utf8))
        #expect(items.items[same.secret] == Data("pw".utf8))
    }

    @Test func everyWriteFailingPutsNothingBack() {
        let items = kept()
        let same = names("old")
        items.writesLeft = [same.secret: 0, same.headers: 0, same.certificate: 0, same.certificatePassword: 0]
        #expect(throws: KeepSecretsError.unsaved) {
            try keepSecrets(items, from: "old", to: "old", carriesCertificate: true, secret: "pw2", headers: headers,
                            certificate: .picked(data: Data("new-cert".utf8), password: "new-pw"), names: names)
        }
        #expect(items.deleted.isEmpty)
        #expect(items.items[same.certificate] == Data("cert".utf8))
    }

    @Test func aCertificateThatCannotBePutBackIsHalfSaved() {
        let items = kept()
        let same = names("old")
        items.writesLeft = [same.certificate: 1, same.certificatePassword: 0]
        #expect(throws: KeepSecretsError.halfSaved) {
            try keepSecrets(items, from: "old", to: "old", carriesCertificate: true, secret: "pw2", headers: headers,
                            certificate: .picked(data: Data("new-cert".utf8), password: "new-pw"), names: names)
        }
    }

    @Test func aTwinKeepsItsIdAndForgetsNothing() throws {
        let items = kept()
        let forget = try keepSecrets(items, from: "old", to: "old", carriesCertificate: true, secret: "pw2", headers: headers,
                                     certificate: .unchanged, names: names)
        #expect(forget == nil)
        #expect(items.items[names("old").secret] == Data("pw2".utf8))
        #expect(items.items[names("old").certificate] == Data("cert".utf8))
    }

    @Test func aMoveReturnsTheOldIdWithNothingDeletedYet() throws {
        let items = kept()
        let forget = try move(items)
        #expect(forget == "old")
        #expect(items.deleted.isEmpty)
        #expect(items.items[names("new").certificate] == Data("cert".utf8))
        #expect(items.items[names("new").certificatePassword] == Data("cert-pw".utf8))
        #expect(items.items[names("old").certificate] == Data("cert".utf8))
    }

    @Test func removeDeletesTheCertificateOnlyOnceEveryWriteLanded() throws {
        let items = kept()
        let same = names("old")
        items.writesLeft = [same.secret: 0]
        #expect(throws: KeepSecretsError.unsaved) {
            try keepSecrets(items, from: "old", to: "old", carriesCertificate: true, secret: "pw2", headers: headers, certificate: .removed, names: names)
        }
        #expect(items.items[same.certificate] == Data("cert".utf8))
        items.writesLeft = [:]
        _ = try keepSecrets(items, from: "old", to: "old", carriesCertificate: true, secret: "pw2", headers: headers, certificate: .removed, names: names)
        #expect(items.items[same.certificate] == nil)
        #expect(items.items[same.certificatePassword] == nil)
    }

    @Test func theWordsAreThePlans() {
        #expect(KeepSecretsError.unreadCertificate.words == "Octo couldn't read the certificate, so nothing changed. Try again, or remove the certificate.")
        #expect(KeepSecretsError.unreadSaved.words == "Octo couldn't read this server's details from the keychain, so nothing changed. Try again.")
        #expect(KeepSecretsError.unsaved.words == "Octo couldn't save this server to the keychain, so nothing changed. Try again.")
        #expect(KeepSecretsError.halfSaved.words == "Octo couldn't save this server's details to the keychain. Choose the certificate again.")
    }
}
