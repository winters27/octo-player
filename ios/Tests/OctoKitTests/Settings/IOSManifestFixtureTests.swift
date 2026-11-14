import Foundation
import Testing
@testable import OctoKit

/// The iPhone app reads an iPhone release exactly as the release scripts publish it. The
/// fixture comes from scripts/release/test_release_assets.py, which also checks its signature
/// with OpenSSL; here the app's own code must hand those exact bytes to its verifier and pick
/// the IPA from them.
@Suite struct IOSManifestFixtureTests {
    @Test func theAppReadsWhatTheReleaseScriptsPublish() throws {
        let bytes = try #require(Data(base64Encoded: IOSManifestFixture.manifest))
        let key = try #require(Data(base64Encoded: IOSManifestFixture.publicKey))
        let signature = try #require(Data(base64Encoded: IOSManifestFixture.signature))
        var handed: [(Data, Data, Data)] = []
        let manifest = try UpdateFiles.verifiedManifest(bytes, signature: IOSManifestFixture.signature, keys: [key], app: .ios,
                                                        tag: IOSManifestFixture.tag) { k, m, s in
            handed.append((k, m, s))
            return k == key && m == bytes && s == signature
        }
        #expect(handed.count == 1)
        #expect(handed.first?.0 == key && handed.first?.1 == bytes && handed.first?.2 == signature)
        let ipa = try #require(UpdateFiles.iosInstaller(manifest))
        #expect(ipa.name == "Octo-1.11.0-iPhone.ipa")
        #expect(ipa.sha256 == IOSManifestFixture.ipaSha256)
        #expect(ipa.arch == "arm64")
        #expect(manifest.version == "1.11.0")
    }

    /// The release as GitHub lists it, with the IPA at `ipaSize` bytes.
    private func releases(ipaSize: Int, manifestSize: Int) throws -> [GitHubRelease] {
        let json = """
        [{"tag_name": "ios-v1.11.0", "name": "Octo for iPhone 1.11.0", "draft": false, "prerelease": false, "body": "",
          "html_url": "https://github.com/winters27/octo/releases/tag/ios-v1.11.0",
          "assets": [{"name": "update.json", "size": \(manifestSize), "browser_download_url": "https://example.test/update.json"},
                     {"name": "update.json.sig", "size": 88, "browser_download_url": "https://example.test/update.json.sig"},
                     {"name": "Octo-1.11.0-iPhone.ipa", "size": \(ipaSize), "browser_download_url": "https://example.test/Octo.ipa"}]}]
        """
        return try JSONDecoder().decode([GitHubRelease].self, from: Data(json.utf8))
    }

    @Test func aSideloadedCopyFindsTheRelease() async throws {
        let bytes = try #require(Data(base64Encoded: IOSManifestFixture.manifest))
        let key = try #require(Data(base64Encoded: IOSManifestFixture.publicKey))
        let signature = try #require(Data(base64Encoded: IOSManifestFixture.signature))
        let running = try #require(PlayerVersion.parse("1.10.0"))
        let found = await UpdateFiles.decide(try releases(ipaSize: 15, manifestSize: bytes.count), running: running, early: false,
                                             keys: [key],
                                             fetch: { address, _ in address.hasSuffix(".sig") ? Data(IOSManifestFixture.signature.utf8) : bytes },
                                             verify: { k, m, s in k == key && m == bytes && s == signature })
        #expect(found == .available(version: try #require(PlayerVersion.parse("1.11.0")), tag: "ios-v1.11.0",
                                    notes: "- First iPhone release.", page: "https://github.com/winters27/octo/releases/tag/ios-v1.11.0"))
    }

    @Test func anIPAOfAnotherSizeIsRefused() async throws {
        let bytes = try #require(Data(base64Encoded: IOSManifestFixture.manifest))
        let key = try #require(Data(base64Encoded: IOSManifestFixture.publicKey))
        let running = try #require(PlayerVersion.parse("1.10.0"))
        let found = await UpdateFiles.decide(try releases(ipaSize: 14, manifestSize: bytes.count), running: running, early: false,
                                             keys: [key],
                                             fetch: { address, _ in address.hasSuffix(".sig") ? Data(IOSManifestFixture.signature.utf8) : bytes },
                                             verify: { _, _, _ in true })
        #expect(found == .refused(reason: "the release is missing Octo-1.11.0-iPhone.ipa"))
    }

    @Test func aReleaseForAnotherAppIsRefused() throws {
        let bytes = try #require(Data(base64Encoded: IOSManifestFixture.manifest))
        let key = try #require(Data(base64Encoded: IOSManifestFixture.publicKey))
        #expect(throws: UpdateRefused.self) {
            try UpdateFiles.verifiedManifest(bytes, signature: IOSManifestFixture.signature, keys: [key], app: .android,
                                             tag: IOSManifestFixture.tag) { _, _, _ in true }
        }
    }
}
