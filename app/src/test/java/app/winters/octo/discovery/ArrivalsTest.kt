package app.winters.octo.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrivalsTest {
    @Test
    fun aSongAskedForIsAnnouncedOnce() {
        val arrivals = Arrivals()
        arrivals.askedSong("find:1", "Nightcall")
        assertEquals("Nightcall is in your library", arrivals.landed("find:1"))
        assertNull(arrivals.landed("find:1"))
    }

    @Test
    fun aSongAskedForBeforeTheAppStartedArrivesQuietly() {
        assertNull(Arrivals().landed("find:1"))
    }

    @Test
    fun anAlbumIsAnnouncedWhenItsLastSongLands() {
        val arrivals = Arrivals()
        arrivals.askedAlbum("OutRun", listOf("find:1", "find:2", "find:3"))
        assertNull(arrivals.landed("find:2"))
        assertNull(arrivals.landed("find:1"))
        assertEquals("OutRun is in your library", arrivals.landed("find:3"))
    }

    @Test
    fun anAlbumWithASongThatFailedSaysSo() {
        val arrivals = Arrivals()
        arrivals.askedAlbum("OutRun", listOf("find:1", "find:2", "find:3"))
        assertNull(arrivals.landed("find:1"))
        assertNull(arrivals.failed("find:2"))
        assertEquals("OutRun is in your library, but 1 song could not be downloaded", arrivals.landed("find:3"))
    }

    @Test
    fun anAlbumWhoseLastSongFailedIsSettledByTheFailure() {
        val arrivals = Arrivals()
        arrivals.askedAlbum("OutRun", listOf("find:1", "find:2", "find:3"))
        assertNull(arrivals.failed("find:1"))
        assertNull(arrivals.landed("find:2"))
        assertEquals("OutRun is in your library, but 2 songs could not be downloaded", arrivals.failed("find:3"))
    }

    @Test
    fun anAlbumWhereNothingCameSaysNothing() {
        val arrivals = Arrivals()
        arrivals.askedAlbum("OutRun", listOf("find:1", "find:2"))
        assertNull(arrivals.failed("find:1"))
        assertNull(arrivals.failed("find:2"))
    }

    @Test
    fun aSongOfTheAlbumAskedForAgainStillCountsWithTheAlbum() {
        val arrivals = Arrivals()
        arrivals.askedAlbum("OutRun", listOf("find:1", "find:2"))
        assertNull(arrivals.failed("find:1"))
        arrivals.askedSong("find:1", "Nightcall")
        assertNull(arrivals.landed("find:2"))
        assertEquals("OutRun is in your library", arrivals.landed("find:1"))
    }

    @Test
    fun aSongAskedForAloneThenWithItsAlbumIsAnnouncedWithTheAlbum() {
        val arrivals = Arrivals()
        arrivals.askedSong("find:1", "Nightcall")
        arrivals.askedAlbum("OutRun", listOf("find:1", "find:2"))
        assertNull(arrivals.landed("find:1"))
        assertEquals("OutRun is in your library", arrivals.landed("find:2"))
    }

    @Test
    fun aFailedSongOnItsOwnSaysNothing() {
        val arrivals = Arrivals()
        arrivals.askedSong("find:1", "Nightcall")
        assertNull(arrivals.failed("find:1"))
    }
}
