package app.winters.octo.playback

import app.winters.octo.listening.timed
import app.winters.octo.radio.RADIO_CANDIDATES
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A radio over 20,000 songs, most of them fitting, holds a page: no
// statement reads more than 900 rows (counted at the database, below Room)
// or binds more than 900 ids, and the pool keeps RADIO_CANDIDATES of the
// songs that fit. The base read every song in one statement.
@RunWith(RobolectricTestRunner::class)
class RadioPoolResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun theRadioHoldsAPage() = timed(120) {
        val tracks = (0 until 20_000).map { i ->
            radioTrack("t%05d".format(i), sortKey = "song %05d".format(i), artist = "Artist ${i % 300}", artistId = "ar${i % 300}", genre = if (i % 10 < 8) "Rock" else "Jazz", albumId = "al${i % 2_000}")
        }
        db.putTracks(tracks)
        val rig = RadioRig(db).started()
        var seen: Pair<Int, List<String>>? = null
        rig.radio.hooks = object : RadioHooks() {
            override suspend fun walked(offered: Int, kept: List<String>) {
                seen = offered to kept
            }
        }
        db.counted.reset()
        val picks = rig.radio.songsLike(listOf(tracks[5]), RADIO_LENGTH, random = Random(3))
        assertEquals(RADIO_LENGTH, picks.size)
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 900)
        assertTrue("${db.counted.maxBound}", db.counted.maxBound!!.boundArgs <= 900)
        val (offered, kept) = seen!!
        // Every Rock song; the seed's artist has only Rock songs here.
        assertEquals(16_000, offered)
        assertEquals(RADIO_CANDIDATES, kept.toSet().size)
    }
}
