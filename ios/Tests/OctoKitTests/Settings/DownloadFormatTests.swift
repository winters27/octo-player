import Foundation
import Testing
@testable import OctoKit

/// The Download format choice: its names, its default for everyone, its backup, and the shared backup
/// fixture Android's test reads too.
@Suite struct DownloadFormatTests {
    @Test func storedNamesAreTheOtherAppsAndAnUnknownNameIsOpus() {
        #expect(DownloadFormat.allCases.map(\.storedName) == ["Opus", "Aac", "Mp3"])
        #expect(DownloadFormat.allCases.map(\.param) == ["opus", "aac", "mp3"])
        #expect(DownloadFormat.named(nil) == .opus)
        #expect(DownloadFormat.named("Flac") == .opus)
        #expect(DownloadFormat.named("Aac") == .aac)
        #expect(OfflinePrefs().downloadFormat == .opus)
    }

    @Test func offlineSettingsWithoutTheFieldReadAsOpus() throws {
        let old = #"{"cacheSize":"Gb2","downloadQuality":"Kbps128","wifiOnly":true}"#
        let read = try JSONDecoder().decode(OfflinePrefs.self, from: Data(old.utf8))
        #expect(read.downloadFormat == .opus)
        #expect(read.downloadQuality == .mp3(kbps: 128))
    }

    @Test func aPickedFormatGoesThroughABackup() {
        var prefs = OfflinePrefs()
        prefs.downloadFormat = .aac
        var backup = Backup()
        backup.offline = prefs
        guard case .read(let read) = BackupFile.decode(BackupFile.encode(backup)) else {
            Issue.record("not read"); return
        }
        #expect(read.offline?.downloadFormat == .aac)
    }

    @Test func theSharedBackupFixtureReadsAsOnAndroid() throws {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var found: URL?
        while dir.path != "/" && !dir.path.isEmpty {
            let candidate = dir.appendingPathComponent("shared/core/src/commonTest/resources/backup/download-format-offline.json")
            if FileManager.default.fileExists(atPath: candidate.path) { found = candidate; break }
            dir = dir.deletingLastPathComponent()
        }
        guard let url = found else {
            Issue.record("shared/core/src/commonTest/resources/backup/download-format-offline.json not found above \(#filePath)")
            return
        }
        let root = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cases = try #require(root["cases"] as? [[String: Any]])
        #expect(cases.count == 5)
        for item in cases {
            let offline = try JSONSerialization.data(withJSONObject: try #require(item["offline"]))
            let read = try JSONDecoder().decode(OfflinePrefs.self, from: offline)
            #expect(read.downloadFormat.storedName == item["downloadFormat"] as? String, "\(item["name"] ?? "")")
        }
    }

    /// Opus stays Opus on every phone: without the system's Opus the Ogg Opus is kept, never AAC.
    @Test func anOpusDownloadIsKeptAsOggOpusWhereTheSystemCannotPlayItsCaf() {
        // A first Ogg page: its 26-byte header, one segment of 19 bytes, then the OpusHead packet.
        let head = [UInt8]("OggS".utf8) + [0, 2] + [UInt8](repeating: 0, count: 20) + [1, 19] + [UInt8]("OpusHead".utf8)
            + [1, 2, 0x38, 0x01, 0x80, 0xBB, 0, 0, 0, 0, 0]
        let inCaf = downloadArrival(head: head, answeredType: "audio/ogg", listed: "audio/flac", askedSmaller: true, asked: .opus)
        #expect(inCaf.rewrap == .caf)
        let kept = downloadArrival(head: head, answeredType: "audio/ogg", listed: "audio/flac", askedSmaller: true, asked: .opus,
                                   opusInCaf: false)
        #expect(kept == DownloadArrival(label: "audio/opus", madeSmaller: true, rewrap: nil))
        #expect(kept.ext == "opus")
    }

    @Test func theSettingIsFoundBySearch() {
        #expect(SettingsIndex.all.contains(SettingsIndex.downloadFormat))
        #expect(SettingsIndex.downloadFormat.keywords.contains("opus"))
        #expect(SettingsIndex.downloadFormat.summary == "What smaller downloads are saved as")
    }
}
