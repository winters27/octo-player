package app.winters.octo.device

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

// When a phone file counts as added, and a file's tags surviving the trip
// to the database and back.
class FileDetailsTest {
    private val now = 1_790_000_000L // September 2026

    @Test
    fun aCopiedLibraryKeepsItsFilesOwnDates() {
        // A file from 2020 copied onto the phone in September 2026.
        assertEquals(1_583_485_817L, fileAddedAt(modifiedSeconds = 1_583_485_817, addedSeconds = 1_789_693_267, takenMs = null, nowSeconds = now))
    }

    @Test
    fun aFileEditedAfterArrivingKeepsItsArrivalDate() {
        assertEquals(1_700_000_000L, fileAddedAt(modifiedSeconds = 1_750_000_000, addedSeconds = 1_700_000_000, takenMs = null, nowSeconds = now))
    }

    @Test
    fun aTakenDateCountsWhenAPhoneSetsOne() {
        assertEquals(1_500_000_000L, fileAddedAt(1_600_000_000, 1_700_000_000, takenMs = 1_500_000_000_000, nowSeconds = now))
    }

    @Test
    fun brokenClocksArePassedOver() {
        // Unset (1970), before 1990, and far in the future.
        assertEquals(1_700_000_000L, fileAddedAt(0, 1_700_000_000, null, now))
        assertEquals(1_700_000_000L, fileAddedAt(86_400, 1_700_000_000, null, now))
        assertEquals(1_700_000_000L, fileAddedAt(now + 30 * 86_400, 1_700_000_000, null, now))
        assertEquals(1_600_000_000L, fileAddedAt(1_600_000_000, 1_700_000_000, takenMs = 0, nowSeconds = now))
    }

    @Test
    fun withNoRealTimeThePhonesOwnIsKept() {
        assertEquals(5L, fileAddedAt(0, 5, null, now))
        assertEquals(0L, fileAddedAt(0, -1, null, now))
    }

    @Test
    fun everyTagSurvivesBeingKept() {
        val tags = FileTags(
            title = "T", artist = "A feat. B", albumArtist = "A", album = "L", trackNo = 3, discNo = 2, year = 2017,
            compilation = true, mbAlbumId = "r", genres = listOf("Rock", "Pop"), originalYear = 1977,
            artists = listOf("A", "B"), composer = "C", bpm = 120, comment = "note", explicit = false,
            discTitle = "Side B", mbRecordingId = "rec", mbReleaseGroupId = "grp", mbArtistIds = listOf("x", "y"),
            sortTitle = "T, The", sortAlbum = "L, The", sortAlbumArtist = "A, The",
            trackGain = -6.5f, albumGain = -7f, trackPeak = 0.9f, albumPeak = 1f,
        )
        val saved = savedTags(mediaId = 7, modifiedAt = 100, size = 200, tags = tags, version = 2)
        assertEquals(tags, saved.toTags())
        assertEquals(2, saved.tagsVersion)
        assertEquals(true, saved.readOk)
        // Every field of FileTags is kept, so a new one cannot be forgotten here.
        assertEquals(27, FileTags::class.java.declaredFields.count { !it.isSynthetic && !Modifier.isStatic(it.modifiers) })
    }

    @Test
    fun aFileThatCouldNotBeReadIsKeptAsUnread() {
        val saved = savedTags(7, 100, 200, tags = null, version = 2)
        assertEquals(false, saved.readOk)
        assertEquals(FileTags(), saved.toTags())
    }
}
