package app.winters.octo.ui.common

import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The length at the end of a song row, and the line under its title.
class SongLengthTest {
    private fun track(id: String, title: String, artist: String, album: String) = TrackEntity(
        id = id, sourceId = "server:x", nativeId = id, title = title, searchKey = title, sortKey = title,
        artist = artist, artistId = "", album = album, albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    @Test
    fun anUnknownLengthShowsNothingRatherThanZero() {
        assertNull(rowLength(0))
        assertNull(rowLength(-1))
        // Under a second is not a song's length either.
        assertNull(rowLength(999))
    }

    @Test
    fun aKnownLengthShowsAsMinutesAndSeconds() {
        assertEquals("3:45", rowLength(225_000))
        assertEquals("0:07", rowLength(7_400))
        assertEquals("12:03", rowLength(723_000))
        assertEquals("1:02:03", rowLength(3_723_000))
    }

    @Test
    fun theColumnIsSizedForEverySongUnderAnHour() {
        // Figures of one width, so a length is no wider than the sample
        // when it has no more characters.
        assertEquals("tnum", LengthStyle.fontFeatureSettings)
        assertEquals("00:00", LengthSlotSample)
        for (seconds in listOf(1, 59, 60, 599, 600, 3_599)) {
            val shown = rowLength(seconds * 1_000L)!!
            assertTrue("$shown is wider than the column", shown.length <= LengthSlotSample.length)
        }
        // An hour or more widens it, since the slot is only a least width.
        assertTrue(rowLength(3_600_000)!!.length > LengthSlotSample.length)
    }

    @Test
    fun aFindWhoseAlbumIsItsOwnTitleNamesOnlyTheArtist() {
        assertEquals("Singer", songSubtitle(track("find:1", "Night Song", "Singer", "Night Song")))
        assertEquals("Singer", songSubtitle(track("find:1", "Night Song", "Singer", " night song ")))
        assertEquals("Singer • First Album", songSubtitle(track("find:2", "Night Song", "Singer", "First Album")))
        // A library single keeps its album as it is.
        assertEquals("Singer • Night Song", songSubtitle(track("t-1", "Night Song", "Singer", "Night Song")))
        assertEquals("Singer", songSubtitle(track("find:3", "Night Song", "Singer", "")))
    }
}
