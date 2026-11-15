import Foundation
import Testing
@testable import OctoKit

/// Every stream version word against the fixture the Android and desktop apps test against too.
@Suite struct StreamVersionWordsTests {
    static func swiftWords() -> [String: String] {
        [
            "menu.row": StreamVersionWords.menuRow,
            "sheet.title": StreamVersionWords.title,
            "sheet.done": StreamVersionWords.done,
            "section.playing": StreamVersionWords.playingNow,
            "section.others": StreamVersionWords.otherVersions,
            "picked.mine": StreamVersionWords.yourPick,
            "picked.by": StreamVersionWords.pickedBy("{name}"),
            "picked.someone": StreamVersionWords.pickedBySomeone,
            "picked.earlier": StreamVersionWords.pickFromBefore,
            "picked.octo": StreamVersionWords.octosPick,
            "nextStart": StreamVersionWords.nextStart,
            "kind.release": StreamVersionWords.kindRelease,
            "kind.musicVideo": StreamVersionWords.kindMusicVideo,
            "kind.video": StreamVersionWords.kindVideo,
            "looking": StreamVersionWords.looking,
            "couldNotReach": StreamVersionWords.couldNotReach,
            "serverUnreachable": StreamVersionWords.serverUnreachable,
            "tryAgain": StreamVersionWords.tryAgain,
            "nothing": StreamVersionWords.nothing,
            "missed.youtubeMusic": StreamVersionWords.missedYouTubeMusic,
            "missed.youtube": StreamVersionWords.missedYouTube,
            "checking": StreamVersionWords.checking,
            "playsFrom.download": StreamVersionWords.playsFromDownload,
            "playsFrom.file": StreamVersionWords.playsFromFile,
            "busy": StreamVersionWords.busy,
            "footer.note": StreamVersionWords.note,
            "footer.undo": StreamVersionWords.goBack,
            "moreBelow": StreamVersionWords.moreBelow,
            "pick.unreachable": StreamVersionWords.pickUnreachable,
            "song.line": StreamVersionWords.songLine(title: "{title}", artist: "{artist}"),
        ]
    }

    @Test func everyWordMatchesTheSharedFixture() throws {
        let fixture = try #require(try streamVersionsFixture()["words"] as? [String: String])
        let words = Self.swiftWords()
        #expect(Set(words.keys) == Set(fixture.keys))
        for (key, text) in fixture { #expect(words[key] == text, "\(key)") }
    }

    /// A streamed outside song is the song itself.
    @Test func noWordSaysPreviewOrHasADashOrACurlyQuote() {
        for words in Self.swiftWords().values {
            #expect(!words.lowercased().contains("preview"), "\(words)")
            #expect(!words.contains(where: { "\u{2014}\u{2013}\u{2018}\u{2019}\u{201C}\u{201D}".contains($0) }), "\(words)")
        }
    }

    @Test func songLinesLengthsAndRowDetailsMatchTheSharedFixture() throws {
        let fixture = try streamVersionsFixture()
        for line in try #require(fixture["songLines"] as? [[String: Any]]) {
            #expect(StreamVersionWords.songLine(title: line["title"] as? String ?? "", artist: line["artist"] as? String ?? "")
                == line["text"] as? String)
        }
        for length in try #require(fixture["lengths"] as? [[String: Any]]) {
            #expect(StreamVersionWords.length(length["seconds"] as? Int ?? -1) == length["text"] as? String)
        }
        for detail in try #require(fixture["rowDetails"] as? [[String: Any]]) {
            let text = StreamVersionWords.rowDetail(channel: detail["channel"] as? String, seconds: detail["duration"] as? Int,
                                                   kind: detail["kind"] as? String)
            #expect(text == detail["text"] as? String)
        }
    }

    @Test func playingNotesMatchTheSharedFixture() throws {
        for note in try #require(try streamVersionsFixture()["playingNotes"] as? [[String: Any]]) {
            let playing = StreamVersionPlaying(videoId: note["videoId"] as? String ?? "", picked: note["picked"] as? String ?? "",
                                               pickedBy: note["pickedBy"] as? String, mine: note["mine"] as? Bool ?? false)
            #expect(StreamVersionWords.playingNote(playing, heardVideoId: note["heard"] as? String) == note["text"] as? String)
        }
    }
}
