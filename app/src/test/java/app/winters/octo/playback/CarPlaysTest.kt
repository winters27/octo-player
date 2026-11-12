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

    private fun play(answer: CarPlayAnswer?) = (answer as? CarPlayAnswer.Play)?.play ?: throw AssertionError("expected a play, got $answer")

    private fun refused(answer: CarPlayAnswer?) = (answer as? CarPlayAnswer.Refused)?.words ?: throw AssertionError("expected a refusal, got $answer")

    @Test
    fun aStationPlaysUnderItsNameFreshFromTheServer() = runTest {
        val station = CarNode.Station("or1", "Discovery Radio")
        lists = { CarList(ListSpec.Online(OnlineKind.Station, "or1"), QueueSource.Station("or1", "Discovery Radio"), network = true) }
        val got = play(plays.playFor(carId(station)))
        assertEquals(ListSpec.Online(OnlineKind.Station, "or1"), got.spec)
        assertEquals(null, got.start)
        assertEquals(QueueSource.Station("or1", "Discovery Radio"), got.source)
        assertTrue(got.network)
        assertEquals("Nothing is playing on Discovery Radio right now", got.emptyWords)
        assertEquals(listOf(station to true), asked)
    }

    @Test
    fun aSongInAListPlaysTheListFromItAsShown() = runTest {
        val list = CarNode.OctoList("og1", "Deep Cuts")
        lists = { CarList(ListSpec.Online(OnlineKind.List, "og1"), QueueSource.Played("Deep Cuts"), network = true) }
        val got = play(plays.playFor(carId(CarNode.Song("b", list))))
        assertEquals(ListStart.Track("b"), got.start)
        assertEquals(ListSpec.Online(OnlineKind.List, "og1"), got.spec)
        assertEquals(listOf(list to false), asked)
    }

    // The queue finds the song in the list, or plays it alone when the list
    // no longer holds it.
    @Test
    fun aSongInAListOfSongsStartsThereByItsId() = runTest {
        lists = { carIds(listOf("a", "c"), QueueSource.Played("Charts")) }
        val got = play(plays.playFor(carId(CarNode.Song("b", CarNode.Chart))))
        assertEquals(ListSpec.Ids(listOf("a", "c")), got.spec)
        assertEquals(ListStart.Track("b"), got.start)
        assertEquals(QueueSource.Played("Charts"), got.source)
    }

    @Test
    fun aSongWithNoListPlaysAlone() = runTest {
        val got = play(plays.playFor(carId(CarNode.Song("b", null))))
        assertEquals(ListSpec.Ids(listOf("b")), got.spec)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aLongListOfSongsIsNamedAtMostAWindowOfThem() = runTest {
        lists = { carIds(List(QUEUE_WINDOW + 40) { "device:$it" }, NoSource) }
        val got = play(plays.playFor(carId(CarNode.Chart)))
        assertEquals(QUEUE_WINDOW, (got.spec as ListSpec.Ids).ids.size)
    }

    @Test
    fun aLineupStartsAtTheStationChosen() = runTest {
        lists = { carIds(listOf("radio:a", "radio:b", "radio:c"), QueueSource.Played("Live radio"), start = 2) }
        assertEquals(ListStart.At(2, 0), play(plays.playFor(carId(CarNode.Live("kept:c")))).start)
    }

    @Test
    fun anEmptyStationSaysNothingIsPlaying() = runTest {
        lists = { carIds(emptyList(), QueueSource.Station("or1", "Late Night")) }
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
        lists = { carIds(emptyList(), NoSource, network = true) }
        assertEquals(CAR_RESUME_GONE, refused(plays.playFor(carId(CarNode.Resume))))
        // The queue read again from the server: found empty only then, it is
        // refused with the same words.
        lists = { CarList(ListSpec.ServerQueue, NoSource, network = true) }
        assertEquals(CAR_RESUME_GONE, play(plays.playFor(carId(CarNode.Resume))).emptyWords)
    }

    @Test
    fun theAppsOwnIdsAreNotACarsChoice() = runTest {
        assertEquals(null, plays.playFor("device:4054"))
    }

    @Test
    fun aChoiceThatHoldsNothingKeepsTheQueueAndSaysSo() = runTest {
        // A station that left the Live radio shelf, and a tab, which does not play.
        lists = { null }
        assertEquals(CAR_NOTHING_TO_PLAY, refused(plays.playFor(carId(CarNode.Live("kept:gone")))))
        assertEquals(CAR_NOTHING_TO_PLAY, refused(plays.playFor(carId(CarNode.Home))))
        // A list of songs named one by one that is empty now.
        lists = { carIds(emptyList(), QueueSource.Played(CAR_LIKED)) }
        assertEquals(CAR_NOTHING_TO_PLAY, refused(plays.playFor(carId(CarNode.Liked))))
    }

    @Test
    fun playCountsThatCannotBeBroughtUpToDateSayWhy() = runTest {
        failure = app.winters.octo.listening.TotalsFailed("Couldn't update play counts.")
        assertEquals("Couldn't update play counts.", refused(plays.playFor(carId(CarNode.MostPlayed))))
    }
}
