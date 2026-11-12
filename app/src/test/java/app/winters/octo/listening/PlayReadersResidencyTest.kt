package app.winters.octo.listening

import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.today.byPlayCount as todayByPlayCount
import app.winters.octo.playback.RadioSeeds
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Song info's plays, a radio's seed (RadioSeeds, from the stored totals)
// and Autoplay's recent songs each read a few stored rows, counted at the
// database, however many songs were played, and answer what today's whole
// play list does (Song info's plays and last play, the album's or artist's
// most played song, the songs last played on the phone, the lower id first
// of two played at once). Today each of them reads every played song.
@RunWith(RobolectricTestRunner::class)
class PlayReadersResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun eachReadsAFewRowsAndAnswersAsToday() = timed(30) {
        db.fillPlays(seed = 131, songs = 6_000, albums = 300)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val today = TodayPlays(db, "home").tracks()
        val song = today[today.size / 2]
        val album = song.track.albumId
        val artist = song.track.artistId
        val phone = db.userDao().playedTracks().first()
            .sortedWith(compareByDescending<PlayedTrack> { it.lastPlayedAt }.thenBy { it.track.id })
            .take(50).map { it.track.id }
        db.counted.reset()
        assertEquals(PlayCount(song.track.id, song.plays, song.lastPlayedAt), rig.history.played(song.track.id))
        assertEquals(null, rig.history.played("never-played"))
        assertTrue(db.counted.maxRead!!.rowsRead <= 1)
        val seeds = RadioSeeds(db)
        db.counted.reset()
        assertEquals(todayByPlayCount(today.filter { it.track.albumId == album }, 1).single().id, seeds.of(PlayedIn.Album(album))?.id)
        assertEquals(todayByPlayCount(today.filter { it.track.artistId == artist }, 1).single().id, seeds.of(PlayedIn.Artist(artist))?.id)
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 1)
        db.counted.reset()
        assertEquals(phone, rig.history.recentlyPlayedIds(50))
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 50)
    }
}
