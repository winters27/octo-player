package app.winters.octo.playback

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.Session
import app.winters.octo.discovery.ServerLists
import app.winters.octo.discovery.Station
import app.winters.octo.liveradio.LiveStation
import app.winters.octo.subsonic.ChartChoice
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarShelvesTest {
    private fun session(id: String, octo: Boolean = true) = Session(
        SubsonicClient("http://localhost/$id/".toHttpUrl(), Credentials("u", "p"), OkHttpClient()),
        if (octo) "octo" else "navidrome", null, octo, adminReachable = false, extensions = emptySet(), id = id,
    )

    private fun key(s: Session, octo: Boolean = true) = ShelfKey(s.id, octo, s.id.takeIf { octo })

    private val octoA = session("A")
    private val octoB = session("B")
    private val navC = session("C", octo = false)
    private val navD = session("D", octo = false)

    private val keys = MutableStateFlow<ShelfKey?>(key(octoA))
    private var now: Pair<Session, ShelfKey>? = octoA to key(octoA)
    private val syncEnds = MutableSharedFlow<Any?>()
    private val network = MutableSharedFlow<Unit>()
    private val gone = MutableSharedFlow<String>()
    private val nearChanges = MutableSharedFlow<Any?>()

    private fun station(id: String, stream: String? = null) = Station(id, id, null, streamUrl = stream)

    // A server whose answers the test hands out: each ask waits on its own
    // answer, per session.
    private inner class FakeSource : ShelfSource {
        val stationAsks = mutableListOf<String>()
        val listAsks = mutableListOf<String>()
        var seeds = mutableMapOf<String, CarServerShelves>()
        val stationAnswers = mutableMapOf<String, CompletableDeferred<List<Station>>>()
        var listsFail = false
        var listsAnswer: () -> ServerLists = { ServerLists() }
        var stationsChange = false
        var nearAsks = 0

        override suspend fun stations(session: Session): List<Station> {
            stationAsks += session.id
            if (stationsChange) return listOf(station("st-${session.id}-${stationAsks.size}"))
            return stationAnswers.getOrPut(session.id) { CompletableDeferred(listOf(station("st-${session.id}"))) }.await()
        }

        override suspend fun lists(session: Session): ServerLists {
            listAsks += session.id
            if (listsFail) error("lists down")
            return listsAnswer()
        }

        override suspend fun chart(): Pair<List<TrackEntity>, List<ChartChoice>> = emptyList<TrackEntity>() to emptyList()

        override suspend fun near(): List<LiveStation> {
            nearAsks++
            return emptyList()
        }

        override suspend fun seed(keptUnder: String): CarServerShelves? = seeds[keptUnder]
    }

    private fun TestScope.shelves(source: FakeSource) =
        CarShelves(keys, { now }, source, syncEnds, network, gone, nearChanges, backgroundScope)

    private fun switchTo(s: Session, octo: Boolean = s.id == "A" || s.id == "B") {
        now = s to key(s, octo)
        keys.value = key(s, octo)
    }

    @Test
    fun whatThePhoneKeptShowsAtOnceAndARefreshReplacesIt() = runTest {
        val source = FakeSource().apply { seeds["A"] = CarServerShelves(stations = listOf(station("kept"))) }
        val shelves = shelves(source)
        runCurrent()
        assertEquals(listOf("kept"), shelves.current().stations.map { it.id })
        shelves.carConnected()
        runCurrent()
        assertEquals(listOf("st-A"), shelves.current().stations.map { it.id })
    }

    @Test
    fun aSwitchDuringASlowRefreshDropsItAndRefreshesTheNewServer() = runTest {
        val source = FakeSource()
        val slowA = CompletableDeferred<List<Station>>().also { source.stationAnswers["A"] = it }
        val shelves = shelves(source)
        shelves.carConnected()
        runCurrent()
        switchTo(octoB)
        runCurrent()
        slowA.complete(listOf(station("late-A")))
        runCurrent()
        assertEquals(listOf("st-B"), shelves.current().stations.map { it.id })
        assertTrue("B" in source.stationAsks)
    }

    @Test
    fun switchesBetweenServersThatDoNotRunOctoStartOver() = runTest {
        val source = FakeSource()
        switchTo(navC, octo = false)
        val shelves = shelves(source)
        shelves.carConnected()
        runCurrent()
        assertEquals(listOf("st-C"), shelves.current().stations.map { it.id })
        switchTo(navD, octo = false)
        runCurrent()
        assertEquals(listOf("st-D"), shelves.current().stations.map { it.id })
        switchTo(octoA)
        runCurrent()
        assertEquals(listOf("st-A"), shelves.current().stations.map { it.id })
    }

    @Test
    fun aServerThatStartsRunningOctoStartsOver() = runTest {
        val source = FakeSource()
        switchTo(octoA, octo = false)
        val shelves = shelves(source)
        shelves.carConnected()
        runCurrent()
        val asksBefore = source.listAsks.size
        switchTo(octoA, octo = true)
        runCurrent()
        assertTrue(source.listAsks.size > asksBefore)
    }

    @Test
    fun theVersionMovesOnlyWhenWhatIsShownChanges() = runTest {
        val source = FakeSource()
        val shelves = shelves(source)
        shelves.carConnected()
        runCurrent()
        val after = shelves.version.value
        syncEnds.emit(1)
        runCurrent()
        assertEquals(after, shelves.version.value)
    }

    @Test
    fun aFailedPartIsAskedAgainOnlyWhenTheCarOpensAListOrTheNetworkChanges() = runTest {
        val source = FakeSource().apply {
            listsFail = true
            stationsChange = true
        }
        var count = 0
        source.listsAnswer = { ServerLists(madeForYou = listOf(station("m${count++}"))) }
        val shelves = shelves(source)
        shelves.carConnected()
        runCurrent()
        val asked = source.listAsks.size
        // Stations answering differently each time never brings the lists back.
        repeat(3) {
            val version = shelves.version.value
            syncEnds.emit(it)
            runCurrent()
            assertTrue(shelves.version.value > version)
        }
        assertEquals(asked + 3, source.listAsks.size)
        source.listsFail = false
        shelves.carOpened()
        runCurrent()
        assertEquals(asked + 4, source.listAsks.size)
        // Nothing failed now: opening asks for nothing.
        shelves.carOpened()
        runCurrent()
        assertEquals(asked + 4, source.listAsks.size)
        source.listsFail = true
        syncEnds.emit(9)
        runCurrent()
        network.emit(Unit)
        runCurrent()
        assertEquals(asked + 6, source.listAsks.size)
    }

    @Test
    fun aListFoundGoneLeavesAtOnce() = runTest {
        val source = FakeSource().apply { listsAnswer = { ServerLists(madeForYou = listOf(station("og1"), station("og2"))) } }
        val shelves = shelves(source)
        shelves.carConnected()
        runCurrent()
        source.listsAnswer = { ServerLists(madeForYou = listOf(station("og2"))) }
        gone.emit("og1")
        runCurrent()
        assertEquals(listOf("og2"), shelves.current().madeForYou.map { it.id })
    }

    @Test
    fun aNewPlaceForStationsNearYouAsksForThemAgain() = runTest {
        val source = FakeSource()
        val shelves = shelves(source)
        shelves.carConnected()
        runCurrent()
        val before = source.nearAsks
        nearChanges.emit(1)
        runCurrent()
        assertEquals(before + 1, source.nearAsks)
    }

    @Test
    fun nothingIsAskedUntilACarConnects() = runTest {
        val source = FakeSource()
        shelves(source)
        runCurrent()
        syncEnds.emit(1)
        network.emit(Unit)
        runCurrent()
        assertTrue(source.stationAsks.isEmpty())
    }
}
