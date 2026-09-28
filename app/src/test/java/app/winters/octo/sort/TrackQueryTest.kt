package app.winters.octo.sort

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.query.decadesIn
import app.winters.octo.query.genresIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackQueryTest {
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    private fun track(
        id: String,
        title: String,
        artist: String = "",
        genre: String = "",
        year: Int? = null,
        addedDaysAgo: Int = 1_000,
        mime: String? = "audio/mpeg",
        rating: Int = 0,
    ) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = year,
        durationMs = 200_000, addedAt = (now - addedDaysAgo * day) / 1_000, mimeType = mime, sizeBytes = null, artwork = null, uri = null,
        genre = genre, rating = rating,
    )

    private val tracks = listOf(
        track("1", "Airbag", "Radiohead", "Alternative", 1997, addedDaysAgo = 3, mime = "audio/flac", rating = 5),
        track("2", "Halo", "Beyoncé", "Pop", 2008, addedDaysAgo = 40),
        track("3", "Teardrop", "Massive Attack", "Electronic", 1998, mime = "audio/x-flac", rating = 3),
        track("4", "Untitled", mime = null),
    )

    // Liked "3"; "1" played yesterday, "3" a year ago, "2" and "4" never.
    private val fields = TrackFields(
        liked = setOf("3"),
        listening = mapOf("1" to Listening(4, now - day), "3" to Listening(1, now - 365 * day)),
    )

    private fun ids(query: LibraryQuery) = filteredSongs(Sorted(tracks, SortList.Songs.default, null), query, fields, now).items.map { it.id }

    @Test
    fun thePhonesSongsAnswerTheSharedFilters() {
        assertEquals(listOf("1"), ids(LibraryQuery(listOf(FilterPresets.AddedThisWeek))))
        assertEquals(listOf("1", "2"), ids(LibraryQuery(listOf(FilterPresets.AddedThisYear))))
        assertEquals(listOf("2", "4"), ids(LibraryQuery(listOf(FilterPresets.NeverPlayed))))
        assertEquals(listOf("2", "3", "4"), ids(LibraryQuery(listOf(FilterPresets.NotPlayedLately))))
        assertEquals(listOf("3"), ids(LibraryQuery(listOf(FilterPresets.Favourites))))
        assertEquals(listOf("1", "3"), ids(LibraryQuery(listOf(FilterPresets.Lossless))))
        assertEquals(listOf("1", "3"), ids(LibraryQuery(listOf(FilterPresets.ratingAtLeast(3)))))
        assertEquals(listOf("3"), ids(LibraryQuery(listOf(FilterPresets.genre("electronic")))))
        assertEquals(listOf("1", "3"), ids(LibraryQuery(listOf(FilterPresets.decade(1990)))))
        assertEquals(listOf("1"), ids(LibraryQuery(listOf(QueryRule(QueryField.Plays, QueryOp.AtLeast, number = 2)))))
        assertEquals(listOf("2"), ids(LibraryQuery(text = "beyonce")))
        assertEquals(listOf("3"), ids(LibraryQuery(listOf(FilterPresets.Lossless), text = "attack")))
    }

    @Test
    fun whatThePhoneDoesNotKeepMatchesNothing() {
        assertEquals(emptyList<String>(), ids(LibraryQuery(listOf(QueryRule(QueryField.Composer, QueryOp.Contains, text = "a")))))
        assertEquals(emptyList<String>(), ids(LibraryQuery(listOf(QueryRule(QueryField.Bpm, QueryOp.AtLeast, number = 1)))))
    }

    @Test
    fun theHeadingsStayInStepWithTheSongsLeft() {
        val all = Sorted(tracks, SortList.Songs.default, listOf("A", "H", "T", "U"))
        val shown = filteredSongs(all, LibraryQuery(listOf(FilterPresets.Lossless)), fields, now)
        assertEquals(listOf("1", "3"), shown.items.map { it.id })
        assertEquals(listOf("A", "T"), shown.headings)
        assertEquals(all.order, shown.order)
    }

    @Test
    fun noFiltersHandsBackTheSameList() {
        val all = Sorted(tracks, SortList.Songs.default, null)
        assertSame(all, filteredSongs(all, LibraryQuery(text = "  "), fields, now))
    }

    @Test
    fun theKindOfFileFromItsMimeType() {
        assertEquals("flac", formatOf("audio/flac"))
        assertEquals("flac", formatOf("audio/x-flac"))
        assertEquals("mp3", formatOf("audio/mpeg"))
        assertEquals("m4a", formatOf("audio/mp4"))
        assertEquals("wav", formatOf("audio/x-wav"))
        assertEquals("ogg", formatOf("audio/ogg; codecs=opus"))
        assertNull(formatOf(null))
        assertNull(formatOf("nonsense"))
        assertTrue(fields.lossless(track("x", "x", mime = "audio/wav")))
        assertFalse(fields.lossless(track("x", "x", mime = "audio/mp4")))
    }

    @Test
    fun theChoicesComeFromTheSongs() {
        assertEquals(listOf("Alternative", "Electronic", "Pop"), genresIn(tracks, TrackFields()))
        assertEquals(listOf(1990, 2000), decadesIn(tracks, TrackFields()))
    }
}
