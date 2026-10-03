package app.winters.octo.query

import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.ArtistRef
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class LibraryQueryTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()

    private fun daysAgo(days: Int) = Instant.ofEpochMilli(now - days * 86_400_000L).toString()

    private val songs = listOf(
        Song(
            "1", "Karma Police", album = "OK Computer", albumId = "a1", artist = "Radiohead", year = 1997, duration = 264,
            suffix = "flac", bitRate = 900, genre = "Alternative", created = daysAgo(3), played = daysAgo(1), playCount = 12,
            userRating = 5, starred = daysAgo(2), bpm = 75, track = 6,
        ),
        Song(
            "2", "Beyoncé Interlude", album = "Lemonade", albumId = "a2", artist = "Beyoncé", year = 2016, duration = 95,
            suffix = "mp3", bitRate = 320, genres = listOf("R&B", "Pop"), created = daysAgo(20), playCount = 0,
        ),
        Song(
            "3", "Windowlicker", album = "Windowlicker", albumId = "a3", artist = "Aphex Twin", year = 1999, duration = 367,
            suffix = "m4a", bitDepth = 16, genre = "Electronic", created = daysAgo(400), played = daysAgo(300), playCount = 2,
            userRating = 3, displayComposer = "Richard D. James", albumArtists = listOf(ArtistRef("r3", "Aphex Twin")),
        ),
        Song(
            "4", "Teardrop", album = "Mezzanine", albumId = "a4", artist = "Massive Attack", duration = 330,
            suffix = "m4a", genre = "electronic", created = daysAgo(100), played = daysAgo(10), playCount = 7, userRating = 4,
        ),
    )

    private fun ids(query: LibraryQuery) = query.apply(songs, now).map { it.id }

    private fun rule(rule: QueryRule) = ids(LibraryQuery(listOf(rule)))

    @Test
    fun anEmptyQueryKeepsEverySongInItsOrder() {
        assertEquals(listOf("1", "2", "3", "4"), ids(LibraryQuery()))
        assertFalse(LibraryQuery().filters)
    }

    @Test
    fun typedWordsIgnoreCaseAndAccentsAndMustAllAppear() {
        assertEquals(listOf("2"), ids(LibraryQuery(text = "beyonce")))
        assertEquals(listOf("2"), ids(LibraryQuery(text = "BEYONCÉ lemon")))
        // Genre and album artist count too.
        assertEquals(listOf("3", "4"), ids(LibraryQuery(text = "electronic")))
        assertEquals(listOf("2"), ids(LibraryQuery(text = "pop")))
        assertEquals(listOf("1"), ids(LibraryQuery(text = "  radiohead   karma ")))
        assertEquals(emptyList<String>(), ids(LibraryQuery(text = "radiohead windowlicker")))
        assertTrue(LibraryQuery(text = "x").filters)
        assertFalse(LibraryQuery(text = "   ").filters)
    }

    @Test
    fun wordsAreNotLookedForInTheComposer() {
        assertEquals(emptyList<String>(), ids(LibraryQuery(text = "richard")))
    }

    @Test
    fun textRules() {
        assertEquals(listOf("1"), rule(QueryRule(QueryField.Title, QueryOp.Contains, text = "POLICE")))
        assertEquals(listOf("3"), rule(QueryRule(QueryField.Title, QueryOp.Is, text = "windowlicker")))
        assertEquals(listOf("1", "2", "4"), rule(QueryRule(QueryField.Title, QueryOp.IsNot, text = "Windowlicker")))
        assertEquals(listOf("4"), rule(QueryRule(QueryField.Artist, QueryOp.StartsWith, text = "mass")))
        assertEquals(listOf("2", "3", "4"), rule(QueryRule(QueryField.Artist, QueryOp.DoesNotContain, text = "radio")))
        assertEquals(listOf("3"), rule(QueryRule(QueryField.Composer, QueryOp.Contains, text = "james")))
        assertEquals(listOf("3"), rule(QueryRule(QueryField.AlbumArtist, QueryOp.Is, text = "aphex twin")))
        assertEquals(listOf("1"), rule(QueryRule(QueryField.Format, QueryOp.Is, text = "FLAC")))
    }

    @Test
    fun onlyALibraryFileThatLosesDetailCanBeUpgraded() {
        val (flac, mp3, alac, aac) = songs
        assertFalse(isUpgradable(flac))
        assertTrue(isUpgradable(mp3))
        // An m4a with a bit depth is Apple Lossless; without one it is AAC.
        assertFalse(isUpgradable(alac))
        assertTrue(isUpgradable(aac))
        // A song the server only found online is not a library file.
        assertFalse(isUpgradable(mp3.copy(isExternal = true)))
        // Nor is one whose kind is unknown: it may be lossless already.
        assertFalse(isUpgradable(mp3.copy(suffix = null)))
    }

    @Test
    fun aGenreRuleLooksAtEveryGenreOfASong() {
        assertEquals(listOf("3", "4"), rule(FilterPresets.genre("Electronic")))
        assertEquals(listOf("2"), rule(FilterPresets.genre("pop")))
        assertEquals(listOf("2"), rule(FilterPresets.genre("R&B")))
        // "Is not" holds only when no genre is it.
        assertEquals(listOf("1", "3", "4"), rule(QueryRule(QueryField.Genre, QueryOp.IsNot, text = "Pop")))
    }

    @Test
    fun numberRules() {
        assertEquals(listOf("1"), rule(QueryRule(QueryField.Year, QueryOp.Is, number = 1997)))
        // No year: only "is not" holds.
        assertEquals(listOf("2", "3", "4"), rule(QueryRule(QueryField.Year, QueryOp.IsNot, number = 1997)))
        assertEquals(listOf("2", "3"), rule(QueryRule(QueryField.Year, QueryOp.AtLeast, number = 1998)))
        assertEquals(listOf("1"), rule(QueryRule(QueryField.Year, QueryOp.AtMost, number = 1998)))
        assertEquals(listOf("1", "3"), rule(FilterPresets.decade(1990)))
        // A range written the wrong way round still means the same years.
        assertEquals(listOf("1", "3"), rule(QueryRule(QueryField.Year, QueryOp.Between, number = 1999, to = 1990)))
        assertEquals(listOf("1", "4"), rule(FilterPresets.ratingAtLeast(4)))
        assertEquals(listOf("2"), rule(QueryRule(QueryField.Plays, QueryOp.Is, number = 0)))
        assertEquals(listOf("1", "4"), rule(QueryRule(QueryField.Plays, QueryOp.AtLeast, number = 5)))
        assertEquals(listOf("3", "4"), rule(QueryRule(QueryField.Duration, QueryOp.AtLeast, number = 300)))
        assertEquals(listOf("2"), rule(QueryRule(QueryField.BitRate, QueryOp.AtMost, number = 320)))
        assertEquals(listOf("1"), rule(QueryRule(QueryField.Bpm, QueryOp.Between, number = 70, to = 80)))
    }

    @Test
    fun dateRules() {
        assertEquals(listOf("1"), rule(FilterPresets.AddedThisWeek))
        assertEquals(listOf("1", "2"), rule(FilterPresets.AddedThisMonth))
        assertEquals(listOf("1", "2", "4"), rule(FilterPresets.AddedThisYear))
        assertEquals(listOf("3"), rule(QueryRule(QueryField.Added, QueryOp.NotInTheLast, days = 365)))
        assertEquals(listOf("2"), rule(FilterPresets.NeverPlayed))
        // Never played counts as not played lately.
        assertEquals(listOf("2", "3"), rule(FilterPresets.NotPlayedLately))
        assertEquals(listOf("1", "4"), rule(QueryRule(QueryField.LastPlayed, QueryOp.InTheLast, days = 30)))
        val cut = now - 50 * 86_400_000L
        assertEquals(listOf("3", "4"), rule(QueryRule(QueryField.Added, QueryOp.Before, at = cut)))
        assertEquals(listOf("1", "2"), rule(QueryRule(QueryField.Added, QueryOp.After, at = cut)))
        assertEquals(listOf("3"), rule(QueryRule(QueryField.LastPlayed, QueryOp.Before, at = cut)))
    }

    @Test
    fun flagRules() {
        assertEquals(listOf("1"), rule(FilterPresets.Favourites))
        assertEquals(listOf("2", "3", "4"), rule(QueryRule(QueryField.Favourite, QueryOp.Is, flag = false)))
        // An m4a with a bit depth is Apple Lossless; without one, AAC.
        assertEquals(listOf("1", "3"), rule(FilterPresets.Lossless))
        assertEquals(listOf("2", "4"), rule(QueryRule(QueryField.Lossless, QueryOp.Is, flag = false)))
    }

    @Test
    fun allOrAnyAndTheWordsTogether() {
        val rules = listOf(FilterPresets.ratingAtLeast(4), FilterPresets.genre("Electronic"))
        assertEquals(listOf("4"), ids(LibraryQuery(rules)))
        assertEquals(listOf("1", "3", "4"), ids(LibraryQuery(rules, QueryMatch.Any)))
        assertEquals(listOf("3"), ids(LibraryQuery(rules, QueryMatch.Any, text = "aphex")))
    }

    @Test
    fun aRuleThatCannotBeTestedIsLeftOut() {
        val broken = listOf(
            QueryRule(QueryField.Title, QueryOp.Contains),
            QueryRule(QueryField.Year, QueryOp.InTheLast, days = 3),
            QueryRule(QueryField.Added, QueryOp.Is, number = 3),
            QueryRule(QueryField.Year, QueryOp.Between, number = 1990),
        )
        broken.forEach { assertFalse(it.toString(), it.complete) }
        assertEquals(listOf("1", "2", "3", "4"), ids(LibraryQuery(broken)))
        assertEquals(listOf("1", "2", "3", "4"), ids(LibraryQuery(broken, QueryMatch.Any)))
        assertEquals(listOf("1"), ids(LibraryQuery(broken + FilterPresets.Favourites, QueryMatch.Any)))
    }

    @Test
    fun anOrderAndALimit() {
        val longest = LibraryQuery(sort = QuerySort.of(SortOrder(SongSort.Length, descending = true)), limit = 2)
        assertEquals(listOf("3", "4"), ids(longest))
        val mostPlayed = LibraryQuery(listOf(FilterPresets.AddedThisYear), sort = QuerySort.of(SortOrder(SongSort.MostPlayed, descending = true)))
        assertEquals(listOf("1", "4", "2"), ids(mostPlayed))
        assertEquals(listOf("1", "2", "3", "4"), ids(LibraryQuery(sort = QuerySort("NoSuchOrder"))))
        assertEquals(emptyList<String>(), ids(LibraryQuery(limit = 0)))
    }

    @Test
    fun aQueryCanPickPlacesInAList() {
        // A playlist with a song twice: both places pass or fail together.
        val playlist = listOf(songs[3], songs[0], songs[2], songs[0])
        assertEquals(listOf(1, 3), LibraryQuery(listOf(FilterPresets.Favourites)).places(playlist, now, SubsonicSongs))
        assertEquals(listOf(0, 2), LibraryQuery(text = "electronic").places(playlist, now, SubsonicSongs))
        val byTitle = LibraryQuery(sort = QuerySort.of(SortOrder(SongSort.Title, descending = false)))
        assertEquals(listOf(1, 3, 0, 2), byTitle.places(playlist, now, SubsonicSongs))
    }

    @Test
    fun aFilterBarKeepsOneRuleAField() {
        val query = LibraryQuery()
            .setting(FilterPresets.AddedThisWeek)
            .setting(FilterPresets.Favourites)
            .setting(FilterPresets.AddedThisYear)
        assertEquals(listOf(FilterPresets.Favourites, FilterPresets.AddedThisYear), query.rules)
        assertEquals(listOf(FilterPresets.AddedThisYear), query.without(FilterPresets.Favourites).rules)
        val cleared = query.copy(text = "x", limit = 5).cleared()
        assertFalse(cleared.filters)
        assertEquals(5, cleared.limit)
    }

    @Test
    fun theSavedShapeIsStableAndCompact() {
        val query = LibraryQuery(
            listOf(FilterPresets.AddedThisMonth, FilterPresets.genre("Electronic"), FilterPresets.Favourites, FilterPresets.decade(1990)),
            QueryMatch.Any,
            text = "night",
            sort = QuerySort.of(SortOrder(SongSort.RecentlyAdded, descending = true)),
            limit = 50,
        )
        val json = query.toJson()
        assertEquals(
            """{"rules":[{"field":"added","op":"inTheLast","days":30},{"field":"genre","op":"is","text":"Electronic"},""" +
                """{"field":"favourite","op":"is","flag":true},{"field":"year","op":"between","number":1990,"to":1999}],""" +
                """"match":"any","text":"night","sort":{"by":"RecentlyAdded","descending":true},"limit":50}""",
            json,
        )
        assertEquals(query, LibraryQuery.fromJson(json))
        assertEquals("{}", LibraryQuery().toJson())
        assertEquals(LibraryQuery(), LibraryQuery.fromJson("{}"))
    }

    @Test
    fun aSavedQueryFromALaterVersionStillReads() {
        val later = """{"v":3,"rules":[{"field":"mood","op":"is","text":"calm"},{"field":"added","op":"inTheLast","days":7,"unit":"d"},""" +
            """{"field":"title","op":"soundsLike","text":"x"}],"match":"most","text":"a","extra":{"x":1}}"""
        val read = LibraryQuery.fromJson(later)
        assertEquals(LibraryQuery(listOf(FilterPresets.AddedThisWeek), QueryMatch.All, text = "a"), read)
        assertNull(LibraryQuery.fromJson("not json"))
        assertNull(LibraryQuery.fromJson("[1,2]"))
    }

    @Test
    fun rulesInPlainWords() {
        val utc = ZoneOffset.UTC
        fun label(rule: QueryRule) = rule.label(utc)
        assertEquals("Added in the last week", label(FilterPresets.AddedThisWeek))
        assertEquals("Added in the last month", label(FilterPresets.AddedThisMonth))
        assertEquals("Added in the last year", label(FilterPresets.AddedThisYear))
        assertEquals("Added in the last 45 days", label(QueryRule(QueryField.Added, QueryOp.InTheLast, days = 45)))
        assertEquals("Not played in the last 6 months", label(FilterPresets.NotPlayedLately))
        assertEquals("Never played", label(FilterPresets.NeverPlayed))
        assertEquals("Favourites", label(FilterPresets.Favourites))
        assertEquals("Not favourites", label(QueryRule(QueryField.Favourite, QueryOp.Is, flag = false)))
        assertEquals("Lossless", label(FilterPresets.Lossless))
        assertEquals("Lossy", label(QueryRule(QueryField.Lossless, QueryOp.Is, flag = false)))
        assertEquals("Rating at least 4", label(FilterPresets.ratingAtLeast(4)))
        assertEquals("Genre is Electronic", label(FilterPresets.genre("Electronic")))
        assertEquals("From the 1990s", label(FilterPresets.decade(1990)))
        assertEquals("Year between 1995 and 2003", label(QueryRule(QueryField.Year, QueryOp.Between, number = 2003, to = 1995)))
        assertEquals("Title does not contain live", label(QueryRule(QueryField.Title, QueryOp.DoesNotContain, text = "live")))
        assertEquals("Format is FLAC", label(QueryRule(QueryField.Format, QueryOp.Is, text = "flac")))
        assertEquals("5:00 or longer", label(QueryRule(QueryField.Duration, QueryOp.AtLeast, number = 300)))
        assertEquals("3:00 or shorter", label(FilterPresets.shorterThan(180)))
        assertEquals("Played 10 times or more", label(FilterPresets.playedAtLeast(10)))
        assertEquals("Played at least once", label(FilterPresets.playedAtLeast(1)))
        assertEquals("Played in the last month", label(FilterPresets.playedInTheLast(30)))
        assertEquals("Artist is Radiohead", label(FilterPresets.artist("Radiohead")))
        assertEquals("Bit rate at most 320 kbps", label(QueryRule(QueryField.BitRate, QueryOp.AtMost, number = 320)))
        assertEquals("Plays is not 0", label(QueryRule(QueryField.Plays, QueryOp.IsNot, number = 0)))
        val day = Instant.parse("2024-01-05T00:00:00Z").toEpochMilli()
        assertEquals("Added before 5 Jan 2024", label(QueryRule(QueryField.Added, QueryOp.Before, at = day)))
        assertEquals("Last played since 5 Jan 2024", label(QueryRule(QueryField.LastPlayed, QueryOp.After, at = day)))
        // One that cannot be tested just names its field.
        assertEquals("Title", label(QueryRule(QueryField.Title, QueryOp.Contains)))
    }

    @Test
    fun ratingChoicesInWords() {
        assertEquals(listOf("5 stars", "4 stars or more", "3 stars or more", "2 stars or more", "1 star or more"), (5 downTo 1).map(FilterPresets::ratingWords))
    }

    @Test
    fun spansInWords() {
        assertEquals(listOf("day", "week", "2 weeks", "month", "6 months", "year", "2 years", "10 days"), listOf(1, 7, 14, 30, 180, 365, 730, 10).map(::spanWords))
    }

    @Test
    fun theGenresAndDecadesAListOffers() {
        assertEquals(listOf("Alternative", "Electronic", "Pop", "R&B"), genresIn(songs, SubsonicSongs))
        assertEquals(listOf(1990, 2010), decadesIn(songs, SubsonicSongs))
    }

    @Test
    fun foldingMatchesTheCataloguesSearchKey() {
        val names = listOf("Beyoncé", "  Sigur Rós ", "Mötley Crüe", "ÆSIR", "Ångström", "坂本龍一", "Straße", "Ǆ", "Plain Words", "Zoë́", "ﬁre", "𝄞 clef")
        names.forEach { assertEquals(it, app.winters.octo.catalog.searchKey(it), foldText(it)) }
    }

    @Test
    fun theQuickTimeReadsWhatServerTimeReads() {
        val times = listOf(
            "2026-09-28T10:00:00Z", "2026-09-28T10:00:00.5Z", "2026-09-28T10:00:00.123Z", "2026-09-28T10:00:00.123456789Z",
            "1999-12-31T23:59:59Z", "2024-02-29T00:00:00Z", "2026-09-28T10:00:00+02:00", "2026-09-28T10:00:00.250-05:30",
            "2026-02-30T10:00:00Z", "2026-13-01T10:00:00Z", "not a time at all ok", "2026-09-28", "", null,
        )
        times.forEach { assertEquals(it, app.winters.octo.server.serverTime(it), quickTime(it)) }
    }

    @Test
    fun everyOpIsOfferedForTheKindsItSuits() {
        QueryField.entries.forEach { field ->
            QueryOp.forKind(field.kind).forEach { op -> assertTrue("$field $op", op in QueryOp.entries) }
        }
        assertEquals(listOf(QueryOp.Is), QueryOp.forKind(FieldKind.Flag))
    }
}
