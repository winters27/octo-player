package app.winters.octo.livelists

import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryMatch
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.query.QuerySort
import app.winters.octo.query.SubsonicSongs
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class LiveListTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private val utc = ZoneOffset.UTC

    private fun daysAgo(days: Int) = Instant.ofEpochMilli(now - days * 86_400_000L).toString()

    private val library = listOf(
        Song("1", "Karma Police", artist = "Radiohead", duration = 264, suffix = "flac", genre = "Alternative", created = daysAgo(3), played = daysAgo(1), playCount = 12, starred = daysAgo(2)),
        Song("2", "Interlude", artist = "Beyoncé", duration = 95, suffix = "mp3", genre = "Pop", created = daysAgo(20), playCount = 0),
        Song("3", "Windowlicker", artist = "Aphex Twin", duration = 367, suffix = "flac", genre = "Electronic", created = daysAgo(400), played = daysAgo(500), playCount = 2, starred = daysAgo(90)),
        Song("4", "Teardrop", artist = "Massive Attack", duration = 330, suffix = "m4a", genre = "Electronic", created = daysAgo(100), played = daysAgo(10), playCount = 7),
    )

    private fun ids(list: LiveList) = list.songsOf(library, now, SubsonicSongs).map { it.id }

    private val lossless = LiveList(
        "a", "Lossless favorites",
        LibraryQuery(listOf(FilterPresets.Favourites, FilterPresets.Lossless), sort = QuerySort("Title"), limit = 10),
        created = 5, changed = 9,
    )

    @Test
    fun listsRoundTripThroughTheirJson() {
        val lists = listOf(lossless, LiveList("b", "Everything", LibraryQuery()))
        assertEquals(lists, LiveListsJson.decode(LiveListsJson.encode(lists)))
        assertEquals(emptyList<LiveList>(), LiveListsJson.decode(LiveListsJson.encode(emptyList())))
    }

    @Test
    fun aDamagedOrUnknownFileLosesOnlyWhatItCannotRead() {
        assertEquals(emptyList<LiveList>(), LiveListsJson.decode(null))
        assertEquals(emptyList<LiveList>(), LiveListsJson.decode("not json"))
        assertEquals(emptyList<LiveList>(), LiveListsJson.decode("[]"))
        val text = """
            {"version":7,"future":true,"lists":[
              {"id":"x","name":"Kept","query":{"rules":[{"field":"mood","op":"is","text":"calm"},{"field":"favourite","op":"is","flag":true}]}},
              {"name":"No id"},
              "a string",
              {"id":"y","name":"No query"},
              {"id":"x","name":"Same id again"}
            ]}
        """.trimIndent()
        val read = LiveListsJson.decode(text)
        assertEquals(listOf("Kept", "No query"), read.map { it.name })
        // The unknown field's rule is dropped, the other kept.
        assertEquals(listOf(FilterPresets.Favourites), read[0].query.rules)
        assertEquals(LibraryQuery(), read[1].query)
    }

    @Test
    fun aListPicksFromTheWholeLibraryInItsOrderAtMostItsLimit() {
        assertEquals(listOf("1", "3"), ids(lossless))
        val recent = LiveList("r", "Recent", LibraryQuery(listOf(FilterPresets.AddedThisYear), sort = DefaultLiveListSort, limit = 2))
        assertEquals(listOf("1", "2"), ids(recent))
        val anyOf = LiveList("g", "Pop or Electronic", LibraryQuery(listOf(FilterPresets.genre("Pop"), FilterPresets.genre("electronic")), QueryMatch.Any))
        assertEquals(listOf("2", "3", "4"), ids(anyOf))
        // With no order the library's own is kept.
        assertEquals(listOf("1", "2", "3", "4"), ids(LiveList("e", "All", LibraryQuery())))
    }

    @Test
    fun theStartersPickWhatTheySay() {
        val byName = LiveListStarters.associate { it.name to LiveList.new(it.name, it.query, now, it.name) }
        assertEquals(listOf("1", "2"), ids(byName.getValue("Recently added")))
        // Played before but not in a year: never played is left out.
        assertEquals(listOf("3"), ids(byName.getValue("Not played in a year")))
        assertEquals(listOf("1", "4"), ids(byName.getValue("Most played this month")))
        assertEquals(listOf("3", "1"), ids(byName.getValue("Lossless favorites")))
        assertEquals(4, LiveListStarters.map { it.name }.toSet().size)
    }

    @Test
    fun savingRemovingAndDuplicating() {
        val b = LiveList("b", "B", LibraryQuery())
        val c = LiveList("c", "C", LibraryQuery())
        val lists = listOf(lossless, b).saving(c)
        assertEquals(listOf("a", "b", "c"), lists.map { it.id })
        val renamed = b.copy(name = "Bee")
        assertEquals(listOf("Lossless favorites", "Bee", "C"), lists.saving(renamed).map { it.name })
        assertEquals(listOf("a", "c"), lists.removing("b").map { it.id })
        val (copied, copy) = lists.duplicating(lossless, now, "d")
        assertEquals(listOf("a", "d", "b", "c"), copied.map { it.id })
        assertEquals("Lossless favorites (copy)", copy.name)
        assertEquals(lossless.query, copy.query)
        assertEquals(now, copy.created)
    }

    @Test
    fun theSummarySaysWhatItMatchesInPlainWords() {
        val month = LibraryQuery(listOf(FilterPresets.AddedThisMonth, FilterPresets.Lossless))
        assertEquals("Added in the last month, lossless, 42 songs", liveListSummary(month, 42, utc))
        assertEquals("Genre is Pop or genre is Rock, 1 song", liveListSummary(LibraryQuery(listOf(FilterPresets.genre("Pop"), FilterPresets.genre("Rock")), QueryMatch.Any), 1, utc))
        assertEquals("Every song, newest first, 1,204 songs", liveListSummary(LibraryQuery(sort = DefaultLiveListSort), 1204, utc))
        val top = LibraryQuery(listOf(FilterPresets.playedInTheLast(30)), sort = QuerySort.of(SortOrder(SongSort.MostPlayed, true)), limit = 50)
        assertEquals("Played in the last month, most played first, the top 50 songs", liveListSummary(top, 50, utc))
        assertEquals("Played in the last month, most played first, 12 songs", liveListSummary(top, 12, utc))
        // Without a count, as in a list of lists.
        assertEquals("Played in the last month, most played first, the top 50", liveListSummary(top, null, utc))
        assertEquals("Added in the last month, lossless", liveListSummary(month, null, utc))
        assertEquals("With “live”, 3 songs", liveListSummary(LibraryQuery(text = " live "), 3, utc))
        // Capitals inside a word stay.
        val bpm = QueryRule(QueryField.Bpm, QueryOp.AtLeast, number = 120)
        assertEquals("Favorites, BPM at least 120, 0 songs", liveListSummary(LibraryQuery(listOf(FilterPresets.Favourites, bpm)), 0, utc))
        assertEquals("1 song matches right now", matchWords(1))
        assertEquals("1,204 songs match right now", matchWords(1204))
        // No dashes of any kind in what the apps show.
        assertFalse(liveListSummary(top, 1, utc).contains('—'))
    }

    @Test
    fun aNameIsSuggestedFromTheRules() {
        assertEquals("Added in the last month", liveListName(LibraryQuery(listOf(FilterPresets.AddedThisMonth)), utc))
        assertEquals("Favorites, lossless", liveListName(LibraryQuery(listOf(FilterPresets.Favourites, FilterPresets.Lossless, FilterPresets.NeverPlayed)), utc))
        assertEquals("Remix", liveListName(LibraryQuery(text = "remix"), utc))
        assertEquals("New live list", liveListName(LibraryQuery(), utc))
    }

    @Test
    fun theEditorTogglesRulesAndLetsSomeFieldsTakeMany() {
        var query = LibraryQuery()
        query = query.toggling(FilterPresets.AddedThisWeek)
        query = query.toggling(FilterPresets.AddedThisMonth)
        assertEquals(listOf(FilterPresets.AddedThisMonth), query.rules)
        query = query.toggling(FilterPresets.genre("Pop")).toggling(FilterPresets.genre("Rock"))
        assertEquals(listOf(FilterPresets.AddedThisMonth, FilterPresets.genre("Pop"), FilterPresets.genre("Rock")), query.rules)
        query = query.toggling(FilterPresets.genre("Pop"))
        assertEquals(listOf(FilterPresets.AddedThisMonth, FilterPresets.genre("Rock")), query.rules)
        assertTrue(query.asksMatch())
        assertFalse(LibraryQuery(listOf(FilterPresets.Lossless)).asksMatch())
        // Every choice the menu offers is a rule that can be tested.
        assertTrue(LiveListRuleGroups.flatMap { it.choices }.all { it.rule.complete })
    }

    @Test
    fun aPagesFiltersBecomeAListOverTheWholeLibrary() {
        val filters = LibraryQuery(listOf(FilterPresets.NeverPlayed), text = " remix ")
        val genre = liveListQueryFrom(listOf(FilterPresets.genre("Pop")), filters, SortOrder(SongSort.Title, false))
        assertEquals(listOf(FilterPresets.genre("Pop"), FilterPresets.NeverPlayed), genre.rules)
        assertEquals("remix", genre.text)
        assertEquals(QuerySort("Title", false), genre.sort)
        // No order given: newest first.
        assertEquals(DefaultLiveListSort, liveListQueryFrom(emptyList(), filters, null).sort)
        // The page's own rule is not added twice.
        assertEquals(listOf(FilterPresets.Favourites), liveListQueryFrom(listOf(FilterPresets.Favourites), LibraryQuery(listOf(FilterPresets.Favourites)), null).rules)
    }

    @Test
    fun theAccountKeyIsStable() {
        assertEquals(accountKey("brandon", "https://music.example"), accountKey("brandon", "https://music.example"))
        assertEquals(16, accountKey("a", "b").length)
        assertTrue(accountKey("a", "b") != accountKey("a", "c"))
    }
}
