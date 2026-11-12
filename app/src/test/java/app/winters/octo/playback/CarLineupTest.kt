package app.winters.octo.playback

import app.winters.octo.discovery.Station
import app.winters.octo.liveradio.LiveShelfEntry
import app.winters.octo.liveradio.LiveStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarLineupTest {
    private val kept = LiveStation("uuid-kept", "Kept FM", "https://kept.example/stream")
    private val serverAlone = Station("st1", "Server FM", null, streamUrl = "https://server.example/stream")
    private val serverKept = Station("st2", "Both FM", null, streamUrl = "https://both.example/stream")
    private val bothKept = LiveStation("uuid-both", "Both FM", "https://both.example/stream")
    private val shelf = listOf(
        LiveShelfEntry.Server(serverAlone),
        LiveShelfEntry.Server(serverKept, bothKept),
        LiveShelfEntry.Kept(kept),
    )

    @Test
    fun theShelfPlaysFromTheStationChosen() {
        val (lineup, _) = liveLineupOf(shelf, "kept:uuid-kept")!!
        assertEquals(3, (lineup.spec as ListSpec.Ids).ids.size)
        assertEquals(ListStart.At(2, 0), lineup.start)
    }

    @Test
    fun aStationKeptOnThePhoneIsReportedStarted() {
        assertEquals("uuid-kept", liveLineupOf(shelf, "kept:uuid-kept")!!.second)
        assertEquals("uuid-both", liveLineupOf(shelf, "server:st2")!!.second)
        assertNull(liveLineupOf(shelf, "server:st1")!!.second)
    }

    @Test
    fun aStationThatLeftTheShelfPlaysNothing() {
        assertNull(liveLineupOf(shelf, "kept:gone"))
    }
}
