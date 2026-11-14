import Foundation
import Testing
@testable import OctoKit

/// The long dashes, which the app's words never use.
private let longDashes: [Character] = ["\u{2014}", "\u{2013}"]

@Suite struct SettingsIndexTests {
    private func titles(_ query: String, _ entries: [SettingEntry] = SettingsIndex.all) -> [String] {
        searchSettings(query, in: entries).map(\.title)
    }

    @Test func everySettingHasItsOwnIdAndATitle() {
        let ids = SettingsIndex.all.map(\.id)
        #expect(ids.count == Set(ids).count)
        #expect(SettingsIndex.all.allSatisfy { !$0.title.trimmingCharacters(in: .whitespaces).isEmpty })
        #expect(SettingsIndex.byId("crossfade") == SettingsIndex.betweenSongs)
    }

    @Test func searchLeavesOutTheUpdaterWhileItIsOff() {
        let off = searchSettings("update", in: SettingsIndex.searchable(updaterOn: false)).map(\.id)
        #expect(off.allSatisfy { !SettingsIndex.updaterRows.contains($0) }, "\(off)")
        let on = searchSettings("update", in: SettingsIndex.searchable(updaterOn: true)).map(\.id)
        #expect(SettingsIndex.updaterRows.isSubset(of: Set(on)), "\(on)")
        #expect(SettingsIndex.updaterRows == ["check_updates", "early_versions", "check_now"])
    }

    @Test func betweenSongsKeepsTheCrossfadeRowsIds() {
        // Between songs keeps the id the Crossfade switch had; Crossfade's length keeps its id
        // under its new title; Smart transitions is gone, its words folded into Between songs.
        #expect(SettingsIndex.betweenSongs.id == "crossfade")
        #expect(SettingsIndex.betweenSongs.title == "Between songs")
        #expect(SettingsIndex.byId("crossfade_length")?.title == "Length")
        #expect(SettingsIndex.byId("smart_transitions") == nil)
        #expect(!SettingsIndex.all.contains { $0.title == "Smart transitions" || $0.title == "Longest blend" })
        for words in ["crossfade", "gapless", "automix", "blend", "mix", "transition", "dj", "smart transitions", "between songs"] {
            #expect(titles(words).contains("Between songs"), "\(words)")
        }
        #expect(titles("smart transitions").first == "Between songs")
        #expect(titles("automix").first == "Between songs")
        #expect(titles("longest blend").first == "Length")
    }

    @Test func everyPageHasSettings() {
        for page in SettingsPage.allCases {
            #expect(SettingsIndex.all.contains { $0.page == page }, "\(page) is empty")
        }
    }

    @Test func noLongDashesInWhatPeopleRead() {
        for entry in SettingsIndex.all {
            for text in [entry.title, entry.summary, entry.place] + entry.keywords {
                #expect(!text.contains { longDashes.contains($0) }, "\(text)")
            }
        }
    }

    @Test func nothingTypedFindsNothing() {
        #expect(titles("").isEmpty)
        #expect(titles("   ").isEmpty)
        #expect(titles("-- ?").isEmpty)
    }

    @Test func theTitleComesFirst() {
        #expect(Array(titles("crossfade").prefix(3)) == ["Crossfade curve", "Between songs", "Length"])
        #expect(titles("CROSS").first == "Crossfade curve")
        #expect(titles("between").first == "Between songs")
    }

    @Test func otherWordsFindASetting() {
        #expect(titles("gapless").first == "Between songs")
        #expect(titles("semitones").first == "Speed")
        #expect(titles("tempo").contains("Speed"))
        #expect(titles("replaygain").contains("Loudness"))
    }

    @Test func punctuationAndAccentsDoNotMatter() {
        #expect(titles("wifi").contains("Streaming on Wi-Fi"))
        #expect(titles("whats new").contains("What's new"))
        #expect(titles("Whät's").contains("What's new"))
    }

    @Test func everyWordMustBeFound() {
        // Both words in the title first, then "mobile" with the page's name.
        #expect(titles("streaming mobile") == ["Streaming on mobile data", "Fetch ahead on mobile data", "Download on Wi-Fi only"])
        #expect(titles("crossfade bluetooth").isEmpty)
    }

    @Test func shortWordsOnlyMatchTheStartOfAWord() {
        #expect(titles("eq").first == "Equalizer")
        #expect(titles("ss").isEmpty)
    }

    @Test func aResultSaysWhereItLives() {
        #expect(SettingsIndex.ambientHome.place == "Appearance · Where it shows")
        #expect(SettingsIndex.lyricsOnline.place == "Lyrics")
        #expect(SettingsIndex.radioDiscovery.place == "Radio · Song radio")
        #expect(SettingsIndex.radioStations.place == "Radio · Live radio")
        #expect(SettingsIndex.checkUpdates.place == "About · Updates")
        #expect(SettingsIndex.musicFolders.place == "Library")
    }

    @Test func titlesRankAboveOtherWordsAboveTheSummary() {
        let entries = [
            SettingEntry("summary", .lyrics, "Other", "screen stuff"),
            SettingEntry("keyword", .lyrics, "Keep awake", "", ["screen"]),
            SettingEntry("inside", .lyrics, "Full screen mode"),
            SettingEntry("start", .lyrics, "Screen"),
            SettingEntry("none", .lyrics, "Nothing", "at all"),
        ]
        #expect(searchSettings("screen", in: entries).map(\.id) == ["start", "inside", "keyword", "summary"])
    }

    @Test func wordsCanBeSpreadOverTitleAndOtherWords() {
        let entries = [
            SettingEntry("spread", .playback, "Resume", "", ["bluetooth"]),
            SettingEntry("title", .playback, "Resume on bluetooth"),
        ]
        #expect(searchSettings("resume bluetooth", in: entries).map(\.id) == ["title", "spread"])
        // The page's name counts as another word; both rank the same, so they keep the order
        // they are written in.
        #expect(searchSettings("playback resume", in: entries).map(\.id) == ["spread", "title"])
    }

    @Test func theAudioQualityEntriesSitAmongStreamingInTheirOrder() {
        let ids = SettingsIndex.all.map(\.id)
        let wifi = ids.firstIndex(of: "stream_wifi")!
        let start = ids.firstIndex(of: "start_after")!
        let mobile = ids.firstIndex(of: "stream_mobile")!
        let cache = ids.firstIndex(of: "cache_size")!
        #expect(wifi < start && start < mobile && mobile < cache)
    }

    @Test func titlesUseUSSpelling() {
        #expect(SettingsIndex.ambient.title == "Ambient color from the artwork")
        #expect(SettingsIndex.all.allSatisfy { !$0.title.contains("colour") && !$0.title.contains("favourite") })
    }

    @Test func normalizingKeepsLettersDigitsAndSingleSpaces() {
        #expect(settingsNormalized("  Wi-Fi   Ön! 2 ") == "wifi on 2")
        #expect(settingsNormalized("What's  new") == "whats new")
    }

    @Test func theServerPagesRowsAreFound() {
        #expect(titles("password").contains("Change password"))
        #expect(searchSettings("version").contains(SettingsIndex.serverKind))
    }
}
