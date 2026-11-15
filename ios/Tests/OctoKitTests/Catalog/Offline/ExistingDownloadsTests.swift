import Foundation
import Testing
@testable import OctoKit

/// A phone with MP3 downloads from before the Download format, now on the Opus default: nothing it
/// keeps is lost, looked for under another name or downloaded again.
@Suite struct ExistingDownloadsTests {
    let legacyName = "Drake - Hotline Bling [a1b2c3d4].mp3"

    func done(_ format: String?, _ path: String, fileSize: Int64?, serverSize: Int64?, serverType: String? = "audio/flac") -> DownloadedFile {
        DownloadedFile(sourceId: "server:a", serverId: "tr-1", format: format, sizeBytes: 7_000_000, fileSize: fileSize,
                       serverSize: serverSize, serverType: serverType, path: "/var/Octo/\(path)")
    }

    @Test func anOldMp3DownloadIsCheckedExactlyAsBefore() {
        #expect(OfflinePrefs().downloadFormat == .opus)
        #expect(checkDownload(done("audio/mpeg", legacyName, fileSize: 31_000_000, serverSize: 31_000_000)) == .keep)
        #expect(checkDownload(done("audio/mpeg", legacyName, fileSize: 31_000_000, serverSize: 28_500_000)) == .refetch)
        #expect(checkDownload(done("audio/mpeg", legacyName, fileSize: nil, serverSize: 31_000_000)) == .note)
        #expect(checkDownload(done("audio/mpeg", legacyName, fileSize: nil, serverSize: nil)) == .keep)
    }

    @Test func anOldMp3StreamCopyIsFoundUnderTheNameItAlwaysHad() {
        let ref = StreamRef(sourceId: "server:a", serverId: "tr-1", mimeType: "audio/flac", bitrate: nil, size: 31_000_000)
        let streamed = phoneRequest("audio/flac", nil, .mp3(kbps: 128))
        #expect(streamCacheKey("server:a", ref, streamed) == "server:a|tr-1|audio/flac|128|31000000")
        var saved = Saved(keys: ["server:a|tr-1|audio/flac|128|31000000"])
        #expect(savedCopyKey("server:a", ref, streamed, &saved) == "server:a|tr-1|audio/flac|128|31000000")
        #expect(savedSongOf("server:a|tr-1|audio/flac|128|31000000") == savedSongOf("server:a|tr-1|audio/flac|opus128|31000000"))
    }

    @Test func aSmallerDownloadIsNeverTheFileAsItIsWhileAnOldRawOneStillIs() {
        // An Opus copy of an Ogg library file has the server's kind but the mark.
        #expect(checkDownload(done("audio/ogg", "A [a1b2c3d4~s].caf", fileSize: nil, serverSize: 9_000_000, serverType: "audio/ogg")) == .note)
        // An old raw ADTS download, no mark: checked by its own size as before.
        #expect(checkDownload(done("audio/aac", "A [a1b2c3d4].aac", fileSize: nil, serverSize: 9_000_000, serverType: "audio/aac")) == .refetch)
        #expect(checkDownload(done("audio/aac", "A [a1b2c3d4].aac", fileSize: nil, serverSize: 7_000_000, serverType: "audio/aac")) == .note)
    }

    @Test func aTransferFromBeforeTheUpdateReadsItsFourParts() {
        let description = ["server:a|tr-1", legacyName, "audio/mpeg", "31000000"].joined(separator: "\n")
        let parts = description.components(separatedBy: "\n")
        #expect(parts.count == 4)
        #expect(!isMadeSmallerName(parts[1]))
        #expect(DownloadFormat.allCases.first { $0.mimeType == parts[2] } == .mp3)
    }

    struct Saved: SavedCopies {
        var keys: Set<String>
        func has(_ key: String) -> Bool { keys.contains(key) }
        func length(_ key: String) -> Int64? { keys.contains(key) ? 7_000_000 : nil }
        func notedSize(_ key: String) -> Int64? { nil }
        mutating func note(_ key: String, size: Int64) {}
        mutating func drop(_ key: String) { keys.remove(key) }
    }
}
