import Foundation
import Testing
@testable import OctoKit

/// The iPhone's sign-in sites read the shared sign-in words where they used to type them out.
@Suite struct SignInWordSitesTests {
    /// A file under ios/Sources, found from this file upward, with CRLF read as LF.
    private func source(_ path: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("Sources").appendingPathComponent(path)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try String(contentsOf: candidate, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
            }
            dir = dir.deletingLastPathComponent()
        }
        throw FixtureMissing(name: path)
    }

    /// Red when a server that cannot check a token is told about the old switch.
    @Test func aTokenTheServerCannotCheckPointsAtTheMethodChoice() {
        #expect(userMessage(for: OctoError.authUnsupported(code: 41, message: "")) == SignInWords.legacyHint)
        #expect(userMessage(plainError(SubsonicError.authNotSupported(code: 41, message: "x"))) == SignInWords.legacyHint)
        #expect(SignInWords.describe(OctoError.authUnsupported(code: 41, message: "")) == SignInWords.legacyHint)
    }

    /// Red when a sign-in source types the old legacy words, or the sign-in page fields lose the
    /// names of the sign-in page met.
    @Test func theSignInSourcesReadTheSharedWords() throws {
        let model = try source("Octo/SignIn/SignInModel.swift")
        #expect(model.contains("self.error = SignInWords.legacyUnsafe"))
        let view = try source("Octo/SignIn/SignInView.swift")
        #expect(view.contains("GatewayWords.fieldLabels(model.gatewayKind, detected: model.gatewayMet)"))
        #expect(view.contains("keptPage ? SignInWords.unchanged : SignInWords.required"))
        for path in ["Octo/SignIn/SignInModel.swift", "Octo/SignIn/SignInView.swift", "OctoKit/Shell/PageWords.swift",
                     "OctoKit/Server/PasswordChange.swift", "OctoKit/Catalog/UserMessages.swift", "OctoKit/Server/SignInWords.swift"] {
            let text = try source(path)
            #expect(!text.contains("Turn on Legacy sign-in"), "\(path)")
        }
    }
}
