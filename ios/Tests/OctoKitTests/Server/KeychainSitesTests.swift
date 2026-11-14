import Foundation
import Testing

@Suite struct KeychainSitesTests {
    struct Missing: Error {
        let what: String
    }

    /// An app or OctoKit source file, by its path under `ios/Sources`.
    static func source(_ path: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" && !dir.path.isEmpty {
            let file = dir.appendingPathComponent("Sources").appendingPathComponent(path)
            if FileManager.default.fileExists(atPath: file.path) {
                return try String(contentsOf: file, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
            }
            dir = dir.deletingLastPathComponent()
        }
        throw Missing(what: path)
    }

    /// The text from `start` to the first `end` after it, both included.
    static func slice(_ text: String, from start: String, to end: String) throws -> String {
        guard let head = text.range(of: start) else { throw Missing(what: start) }
        guard let tail = text.range(of: end, range: head.upperBound..<text.endIndex) else { throw Missing(what: end) }
        return String(text[head.lowerBound..<tail.upperBound])
    }

    static func count(_ part: String, in text: String) -> Int { text.components(separatedBy: part).count - 1 }

    @Test func theKeepForgetsTheOldIdOnlyAfterTheListNamingTheNewOneIsSaved() throws {
        let session = try Self.source("Octo/App/Session.swift")
        let keep = try Self.slice(session, from: "    func keepServer(", to: "\n    /// Gives a kept server a name of its own")
        let call = try #require(keep.range(of: "try keepSecrets("))
        let leaving = try #require(keep.range(of: "await leaving?(from, id)"))
        let held = try Self.slice(keep, from: "        guard use else {", to: "\n        }\n")
        let heldRange = try #require(keep.range(of: held))
        // The held path returns before the leaving await: only the text around it must not forget.
        #expect(!keep[call.upperBound..<heldRange.lowerBound].contains("forgetSecrets("))
        #expect(!keep[heldRange.upperBound..<leaving.lowerBound].contains("forgetSecrets("))
        let saved = try #require(held.range(of: "save()"))
        let forgotHere = try #require(held.range(of: "forgetSecrets("))
        #expect(saved.upperBound <= forgotHere.lowerBound)
        let activate = try #require(keep.range(of: "activate(server, credentials:"))
        let forgotLast = try #require(keep.range(of: "forgetSecrets(", options: .backwards))
        #expect(activate.upperBound <= forgotLast.lowerBound)
        #expect(Self.count("forgetSecrets(", in: keep) == 2)
        #expect(!keep.contains("Keychain.save"))
        #expect(!keep.contains("Keychain.delete"))
    }

    @Test func theFirstSignInsOldItemGoesOnlyOnceTheNewOneIsKept() throws {
        let session = try Self.source("Octo/App/Session.swift")
        let migrate = try Self.slice(session, from: "    private func migrateFirstSignIn() {", to: "\n    private func save() {")
        #expect(migrate.contains("if Keychain.save(secret, for: Self.secretAccount(server.id)) { Keychain.delete(for: oldAccount) }"))
        #expect(Self.count("Keychain.delete(for:", in: migrate) == 1)
    }

    @Test func aPasswordChangeThatCannotBeKeptLeavesNoneBehind() throws {
        let session = try Self.source("Octo/App/Session.swift")
        #expect(session.contains("if !Keychain.save(new, for: Self.secretAccount(id)) { Keychain.delete(for: Self.secretAccount(id)) }"))
    }

    @Test func theFormMarksACertificateStaleOnlyWhenItIsGone() throws {
        let model = try Self.source("Octo/SignIn/SignInModel.swift")
        #expect(model.contains("hadCertificate = certificateState.shown"))
        #expect(model.contains("staleCertificate = server.hasClientCertificate && certificateState == .stale"))
        // The init's write only (the declaration's default sits behind `private var`).
        #expect(Self.count("        staleCertificate = ", in: model) == 1)
        #expect(!model.contains("isSaved("))
    }

    @Test func theKeychainUpdatesInPlace() throws {
        let keychain = try Self.source("Octo/App/Keychain.swift")
        let save = try Self.slice(keychain, from: "    static func saveData(", to: "\n    }\n")
        #expect(save.contains("SecItemUpdate("))
        #expect(save.contains("guard update == errSecItemNotFound else { return false }"))
        #expect(!save.contains("SecItemDelete("))
    }
}
