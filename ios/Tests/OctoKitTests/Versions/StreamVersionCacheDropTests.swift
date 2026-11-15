import Foundation
import Testing
@testable import OctoKit

/// A version change lets go of every saved copy of the song, and of no other song.
@Suite struct StreamVersionCacheDropTests {
    @Test func everySavedCopyOfTheSongGoesAndNoOtherSong() {
        var index = StreamCacheIndex(enabled: true)
        let mine = ["server:a|find:X|audio/webm|raw", "server:a|find:X|audio/webm|raw|4000", "server:a|find:X|audio/mp4|128"]
        let others = ["server:a|find:X|Y|audio/webm|raw", "server:a|find:XY|audio/webm|raw", "server:b|find:X|audio/webm|raw",
                      "server:a|tr-1|audio/flac|raw"]
        for key in mine + others { index.wrote(key, offset: 0, count: 10, freshFile: true, nowMs: 1) }
        let gone = index.dropSong(sourceId: "server:a", serverId: "find:X")
        #expect(gone == mine.sorted())
        #expect(Set(index.entries.keys) == Set(others))
        #expect(Set(index.takeDropped()) == Set(mine))
    }

    /// A copy only described so far, waiting for its first bytes, goes too, so those bytes start a
    /// new file.
    @Test func aCopyOnlyDescribedGoesToo() {
        var index = StreamCacheIndex(enabled: true)
        index.describe("server:a|find:X|audio/webm|raw", contentLength: 4_000, mimeType: "audio/webm", nowMs: 1)
        #expect(index.dropSong(sourceId: "server:a", serverId: "find:X") == ["server:a|find:X|audio/webm|raw"])
        #expect(index.entries.isEmpty)
    }
}
