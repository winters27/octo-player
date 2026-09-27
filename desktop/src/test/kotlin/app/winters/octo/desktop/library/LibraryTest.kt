package app.winters.octo.desktop.library

import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongSortingTest {
    private val songs = listOf(
        Song("1", "Yesterday", artist = "The Beatles", album = "Help!", albumId = "h", track = 13, year = 1965, duration = 125, created = "2024-01-02T00:00:00Z", playCount = 3, played = "2026-09-01T10:00:00Z"),
        Song("2", "Airbag", artist = "Radiohead", album = "OK Computer", albumId = "ok", track = 1, year = 1997, duration = 284, created = "2025-05-05T00:00:00Z", playCount = 10, played = "2026-08-01T10:00:00Z"),
        Song("3", "Help!", artist = "The Beatles", album = "Help!", albumId = "h", track = 1, year = 1965, duration = 138, created = "2024-01-02T00:00:00Z"),
        Song("4", "An Untitled Demo", artist = "Abba", album = "Demos", albumId = "d", duration = 0),
    )

    private fun ids(list: List<Song>) = list.map { it.id }

    @Test
    fun everySortableColumnIsASharedSongOrder() {
        SongColumn.entries.filter { it.sort != null }.forEach { column ->
            assertTrue("${column.title} is offered for songs", SortList.Songs.options.contains(column.sort!!))
            assertEquals(column, SongColumn.of(column.sort!!))
        }
        assertNull(SongColumn.Number.sort)
    }

    @Test
    fun aNewColumnStartsItsUsualWayAndAClickTurnsItRound() {
        val start = SortList.Songs.default
        assertEquals(SortOrder(SongSort.Title, false), start)
        val byYear = start.clicking(SongColumn.Year)
        assertEquals(SortOrder(SongSort.Year, true), byYear)
        assertEquals(SortOrder(SongSort.Year, false), byYear.clicking(SongColumn.Year))
        assertEquals(SortOrder(SongSort.Artist, false), byYear.clicking(SongColumn.Artist))
        assertEquals("the number column does not sort", start, start.clicking(SongColumn.Number))
    }

    @Test
    fun titlesFileWithoutTheirArticle() {
        assertEquals(listOf("2", "3", "4", "1"), ids(sortSongs(songs, SortOrder(SongSort.Title, false))))
    }

    @Test
    fun anArtistsSongsStayInAlbumOrder() {
        // "Abba", then "The Beatles" filed under B with Help! before Yesterday, then Radiohead.
        assertEquals(listOf("4", "3", "1", "2"), ids(sortSongs(songs, SortOrder(SongSort.Artist, false))))
    }

    @Test
    fun missingValuesGoLastWhicheverWay() {
        assertEquals("4", sortSongs(songs, SortOrder(SongSort.Year, true)).last().id)
        assertEquals("4", sortSongs(songs, SortOrder(SongSort.Year, false)).last().id)
        assertEquals("4", sortSongs(songs, SortOrder(SongSort.Length, false)).last().id)
    }

    @Test
    fun playsUseTheSharedListeningOrder() {
        assertEquals(listOf("2", "1", "3", "4"), ids(sortSongs(songs, SortOrder(SongSort.MostPlayed, true))))
        // Ascending, the never-played come first, then the least played.
        assertEquals(listOf("3", "4", "1", "2"), ids(sortSongs(songs, SortOrder(SongSort.MostPlayed, false))))
    }

    @Test
    fun newestAddedFirst() {
        assertEquals("2", sortSongs(songs, SortOrder(SongSort.RecentlyAdded, true)).first().id)
    }

    @Test
    fun historyIsWhatWasPlayedNewestFirst() {
        assertEquals(listOf("1", "2"), ids(recentlyPlayed(songs)))
    }

    @Test
    fun lengthsReadPlainly() {
        assertEquals("2:05", lengthText(125))
        assertEquals("1:02:03", lengthText(3723))
        assertEquals("", lengthText(0))
        assertEquals("2 h 14 min", totalLengthText(8040))
        assertEquals("38 min", totalLengthText(2280))
    }
}

class SelectionTest {
    @Test
    fun aClickPicksOneRow() {
        val s = TableSelection()
        s.click(3, toggle = false, range = false)
        s.click(5, toggle = false, range = false)
        assertEquals(setOf(5), s.picked)
    }

    @Test
    fun ctrlAddsAndTakesAway() {
        val s = TableSelection()
        s.click(1, false, false)
        s.click(4, toggle = true, range = false)
        assertEquals(setOf(1, 4), s.picked)
        s.click(1, toggle = true, range = false)
        assertEquals(setOf(4), s.picked)
    }

    @Test
    fun shiftPicksTheRunFromTheLastClick() {
        val s = TableSelection()
        s.click(6, false, false)
        s.click(2, toggle = false, range = true)
        assertEquals(setOf(2, 3, 4, 5, 6), s.picked)
        // The run still starts where the plain click was.
        s.click(8, toggle = false, range = true)
        assertEquals(setOf(6, 7, 8), s.picked)
        s.click(0, toggle = true, range = true)
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8), s.picked)
    }

    @Test
    fun aRightClickOutsideTheSelectionActsOnThatRowAlone() {
        val s = TableSelection()
        s.click(1, false, false)
        s.click(3, true, false)
        s.pickForMenu(3)
        assertEquals(setOf(1, 3), s.picked)
        s.pickForMenu(7)
        assertEquals(setOf(7), s.picked)
    }

    @Test
    fun thePickedRowsComeOutInTableOrder() {
        val s = TableSelection()
        s.click(2, false, false)
        s.click(0, true, false)
        assertEquals(listOf("a", "c"), s.of(listOf("a", "b", "c")))
    }
}

class LibraryIndexTest {
    private val index = LibraryIndex(
        songs = listOf(
            Song("s1", "Blue Monday", artist = "New Order", albumId = "a1", genre = "Synth-pop", duration = 448),
            Song("s2", "Ceremony", artist = "New Order", albumId = "a2", genres = listOf("Post-punk", "Synth-pop"), duration = 264),
            Song("s3", "Atmosphere", artist = "Joy Division", albumId = "a3", genre = "post-punk", duration = 250),
        ),
        albums = listOf(Album("a1"), Album("a2"), Album("a3")),
        artists = listOf(Artist("r1", "New Order")),
    )

    @Test
    fun genresAreCountedAcrossSongsWithOneNameEach() {
        assertEquals(listOf(GenreCount("Post-punk", 2, 2), GenreCount("Synth-pop", 2, 2)), index.genres)
        assertEquals(listOf("s2", "s3"), index.songsInGenre("post-punk").map { it.id })
    }

    @Test
    fun aSongIsInTheLibraryByIdOrAsTheSameSong() {
        assertTrue(index.holds(Song("s1", "Blue Monday")))
        assertTrue("a find downloaded since", index.holds(Song("other", "Blue Monday", artist = "New Order", duration = 447)))
        assertFalse("another recording", index.holds(Song("other", "Blue Monday (Live)", artist = "New Order", duration = 600)))
        assertFalse(index.holds(Song("x", "Temptation", artist = "New Order")))
    }

    @Test
    fun coversAreCachedByWhatTheyAreNotWhereTheyCameFrom() {
        assertEquals(300, coverBucket(160))
        assertEquals(1200, coverBucket(5000))
        assertEquals("cover:music.test:al-1:300", coverKey("music.test", "al-1", 300))
        assertEquals("online-cover:v2:music.test:al-1:300", coverKey("music.test", "al-1", 300, online = true))
    }
}
