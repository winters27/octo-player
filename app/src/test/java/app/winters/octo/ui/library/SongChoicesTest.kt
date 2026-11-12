package app.winters.octo.ui.library

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.timed
import app.winters.octo.query.decadesIn
import app.winters.octo.query.foldText
import app.winters.octo.query.genresIn
import app.winters.octo.sort.TrackFields
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// The Songs list's chips are read from the genre list and a skip over the
// year index, never the songs: no statement reads more rows than there are
// genres or decades. The decades are today's exactly; the genres are
// today's, one each however spelt, each shown by the declared spelling (of
// spellings alike but for case, the smallest; of those alike but for
// accents, the first by name), where today kept whichever its list met
// first.
@RunWith(RobolectricTestRunner::class)
class SongChoicesTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val genres = listOf("Rock", "ROCK", "rock", "Électronique", "Electronique", "Jazz", " Pop ", "Hip-Hop", "", "   ", "R&B", "Ambient", "ambient")

    @Test
    fun chipsAreReadBoundedAndAreTodays() = timed(60) {
        val random = Random(4)
        val tracks = (0 until 3_000).map { i ->
            TrackEntity(
                id = "device:$i", sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song $i", sortKey = "song %04d".format(i),
                artist = "Band", artistId = "ar", album = "", albumId = "al", trackNo = null, discNo = null,
                year = listOf(null, 0, 1959, 1960, 1969, 1987, 1999, 2000, 2024, 2025)[random.nextInt(10)], durationMs = 0, addedAt = 0,
                mimeType = null, sizeBytes = null, artwork = null, uri = null, genre = genres[random.nextInt(genres.size)],
            )
        }
        db.dao().insertTracks(tracks)
        db.counted.reset()
        db.counted.recording = true
        val choices = songChoices(db)
        db.counted.recording = false

        val reads = db.counted.own
        assertTrue("the chips read the library", reads.isNotEmpty())
        val most = reads.maxBy { it.rowsRead }
        assertTrue("no statement reads the songs: $most", most.rowsRead <= genres.size)

        assertEquals(decadesIn(tracks, TrackFields()), choices.decades)
        val today = genresIn(tracks, TrackFields())
        assertEquals("one chip a genre, in today's order", today.map(::foldText), choices.genres.map(::foldText))
        assertEquals(listOf("Ambient", "Electronique", "Hip-Hop", "Jazz", "Pop", "R&B", "ROCK"), choices.genres)
    }
}
