package app.winters.octo.playback

import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CarPlaysTest {
    private var signedIn = true
    private val gone = mutableListOf<String>()
    private val asked = mutableListOf<Pair<CarNode, Boolean>>()
    private var lists: (CarNode) -> CarList? = { null }
    private var failure: Exception? = null

    private val plays = CarPlays(
        songsOf = { node, fresh ->
            asked += node to fresh
            failure?.let { throw it }
            lists(node)
        },
        signedIn = { signedIn },
        gone = { gone += it },
    )

    private fun play(answer: CarPlayAnswer?) = (answer as CarPlayAnswer.Play).play

    private fun refused(answer: CarPlayAnswer?) = (answer as CarPlayAnswer.Refused).words

    @Test
    fun aStationPlaysItsSongsUnderItsNameFreshFromTheServer() = runTest {
        val station = CarNode.Station("or1", "Discovery Radio")
        lists = { CarList(listOf("find:1", "device:2"), QueueSource.Station("or1", "Discovery Radio"), network = true) }
        val got = play(plays.playFor(carId(station)))
        assertEquals(listOf("find:1", "device:2"), got.ids)
        assertEquals(QueueSource.Station("or1", "Discovery Radio"), got.source)
        assertTrue(got.network)
        assertEquals(listOf(station to true), asked)
    }

    @Test
    fun aSongInAListPlaysTheListFromItAsShown() = runTest {
        val list = CarNode.OctoList("og1", "Deep Cuts")
        lists = { CarList(listOf("a", "b", "c"), QueueSource.Played("Deep Cuts")) }
        val got = play(plays.playFor(carId(CarNode.Song("b", list))))
        assertEquals(1, got.start)
        assertEquals(listOf(list to false), asked)
    }

    @Test
    fun aSongNoLongerInItsListPlaysAlone() = runTest {
        lists = { CarList(listOf("a", "c"), QueueSource.Played("Charts")) }
        val got = play(plays.playFor(carId(CarNode.Song("b", CarNode.Chart))))
        assertEquals(listOf("b"), got.ids)
        assertEquals(0, got.start)
    }

    @Test
    fun aLineupStartsAtTheStationChosen() = runTest {
        lists = { CarList(listOf("radio:a", "radio:b", "radio:c"), QueueSource.Played("Live radio"), start = 2) }
        assertEquals(2, play(plays.playFor(carId(CarNode.Live("kept:c")))).start)
    }

    @Test
    fun anEmptyStationSaysNothingIsPlaying() = runTest {
        lists = { CarList(emptyList(), QueueSource.Station("or1", "Late Night")) }
        assertEquals("Nothing is playing on Late Night right now", refused(plays.playFor(carId(CarNode.Station("or1", "Late Night")))))
    }

    @Test
    fun aListTheServerNoLongerHasLeavesAndSaysSo() = runTest {
        failure = SubsonicException.NotFound("gone")
        assertEquals("Deep Cuts isn't on the server any more", refused(plays.playFor(carId(CarNode.OctoList("og1", "Deep Cuts")))))
        assertEquals(listOf("og1"), gone)
    }

    @Test
    fun aServerThatDoesNotAnswerKeepsTheListAndSaysSo() = runTest {
        failure = SubsonicException.Unreachable(IOException("down"))
        val words = refused(plays.playFor(carId(CarNode.Station("or1", "Discovery Radio"))))
        assertTrue(words.startsWith("Couldn't reach the server"))
        assertTrue(gone.isEmpty())
    }

    @Test
    fun signedOutTheServersListsAreNotAskedFor() = runTest {
        signedIn = false
        assertEquals(CAR_SIGN_IN, refused(plays.playFor(carId(CarNode.Station("or1", "x")))))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aGoneQueueToPickUpSaysSo() = runTest {
        lists = { CarList(emptyList(), NoSource, network = true) }
        assertEquals(CAR_RESUME_GONE, refused(plays.playFor(carId(CarNode.Resume))))
    }

    @Test
    fun theAppsOwnIdsAreNotACarsChoice() = runTest {
        assertEquals(null, plays.playFor("device:4054"))
    }
}
