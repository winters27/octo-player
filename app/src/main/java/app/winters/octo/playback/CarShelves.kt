package app.winters.octo.playback

import android.util.Log
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.data.runsOcto
import app.winters.octo.discovery.ChartAnswer
import app.winters.octo.discovery.ChartLists
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.ServerLists
import app.winters.octo.discovery.Station
import app.winters.octo.lists.OctoListGroup
import app.winters.octo.liveradio.LiveRadioStore
import app.winters.octo.liveradio.LiveStation
import app.winters.octo.liveradio.Places
import app.winters.octo.liveradio.RadioBrowser
import app.winters.octo.liveradio.livePlayable
import app.winters.octo.liveradio.radiusMeters
import app.winters.octo.liveradio.usesMiles
import app.winters.octo.lyrics.NetworkWatch
import app.winters.octo.server.ServerSync
import app.winters.octo.subsonic.CHART_OVERALL
import app.winters.octo.subsonic.ChartChoice
import app.winters.octo.ui.home.KeptShelvesFiles
import app.winters.octo.ui.playlist.KeptOctoListsFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// What the server gives a car beyond the library: its stations (Octo's own
// and the streams added to it), the lists Octo made for the listener (Made
// for you, and every group of them), the overall chart and the listener's
// charts, and the stations near the listener's saved place.
data class CarServerShelves(
    val stations: List<Station> = emptyList(),
    val madeForYou: List<Station> = emptyList(),
    val groups: List<Pair<OctoListGroup, List<Station>>> = emptyList(),
    val chart: List<TrackEntity> = emptyList(),
    val charts: List<ChartChoice> = emptyList(),
    val near: List<LiveStation> = emptyList(),
) {
    // The same with a list the server no longer has taken out everywhere.
    fun without(id: String) = copy(
        stations = stations.filterNot { it.id == id },
        madeForYou = madeForYou.filterNot { it.id == id },
        groups = groups.map { (group, lists) -> group to lists.filterNot { it.id == id } }.filter { it.second.isNotEmpty() },
    )
}

// The parts a refresh asks for, each on its own: one failing never holds
// back another.
enum class ShelfPart { Stations, Lists, Chart, Near }

// The account in use, whether it runs Octo, and the key its server's shelves
// are kept under on the phone (only for a server that runs Octo).
data class ShelfKey(val account: String, val runsOcto: Boolean, val keptUnder: String?)

// Where the shelves come from: the server, the radio catalog, and what the
// phone's own screens kept.
interface ShelfSource {
    suspend fun stations(session: Session): List<Station>
    suspend fun lists(session: Session): ServerLists
    suspend fun chart(): Pair<List<TrackEntity>, List<ChartChoice>>
    suspend fun near(): List<LiveStation>
    suspend fun seed(keptUnder: String): CarServerShelves?
}

// The server's shelves for a car, for the account in use. What the phone's
// screens last kept shows at once; the server is asked again when a car
// connects, a copy of the library ends, the network changes, the listener
// changes their place for stations near them, or a car opens a list while a
// part of it last failed. A list the car was told changed never asks
// again, so the car and this never chase each other. Another account in
// use starts over, and a late answer for the one before is dropped.
@Singleton
class CarShelves internal constructor(
    keys: Flow<ShelfKey?>,
    private val sessionNow: () -> Pair<Session, ShelfKey>?,
    private val source: ShelfSource,
    syncEnds: Flow<Any?>,
    networkChanges: Flow<Unit>,
    gone: Flow<String>,
    nearChanges: Flow<Any?>,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        sessions: SessionRepository,
        discovery: Discovery,
        charts: ChartLists,
        keptHome: KeptShelvesFiles,
        keptLists: KeptOctoListsFiles,
        liveRadio: LiveRadioStore,
        radioCatalog: RadioBrowser,
        places: Places,
        sync: ServerSync,
        network: NetworkWatch,
    ) : this(
        keys = sessions.state.map(::shelfKeyOf).distinctUntilChanged(),
        sessionNow = {
            val state = sessions.state.value
            val now = (state as? SessionState.SignedIn)?.session
            val key = shelfKeyOf(state)
            if (now != null && key != null) now to key else null
        },
        source = AppShelfSource(discovery, charts, keptHome, keptLists, liveRadio, radioCatalog, places),
        syncEnds = sync.last.map { it?.at }.distinctUntilChanged().drop(1),
        networkChanges = network.changes,
        gone = discovery.gone,
        nearChanges = liveRadio.search.distinctUntilChanged().drop(1),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val lock = Mutex()
    private var generation = 0
    private var key: ShelfKey? = null
    private var shown = CarServerShelves()
    private val written = HashSet<ShelfPart>()
    private val failed = HashSet<ShelfPart>()
    private val pending = HashSet<ShelfPart>()
    private var seeded = CompletableDeferred<Unit>()
    private var job: Job? = null

    // Whether a car has connected since the app started, so the server is
    // only asked for a car.
    @Volatile private var carAsked = false

    private val _version = MutableStateFlow(0L)

    // Goes up each time what the shelves hold changes.
    val version: StateFlow<Long> = _version

    init {
        scope.launch { keys.collect { started(it) } }
        scope.launch { syncEnds.collect { if (carAsked) ask(ShelfPart.entries.toSet()) } }
        scope.launch { networkChanges.collect { if (carAsked) askFailed() } }
        scope.launch { gone.collect { dropList(it) } }
        scope.launch { nearChanges.collect { if (carAsked) ask(setOf(ShelfPart.Near)) } }
    }

    // What the shelves hold now, once what the phone kept has been read.
    suspend fun current(): CarServerShelves {
        val waitFor = lock.withLock { seeded }
        waitFor.await()
        return lock.withLock { shown }
    }

    // A car connected: every part is asked for.
    fun carConnected() {
        carAsked = true
        scope.launch { ask(ShelfPart.entries.toSet()) }
    }

    // A car opened a list of its own accord: parts that failed are asked
    // for again. A read the car made because it was told of a change never
    // gets here.
    fun carOpened() {
        if (carAsked) scope.launch { askFailed() }
    }

    private suspend fun askFailed() {
        val parts = lock.withLock { failed.toSet() }
        if (parts.isNotEmpty()) ask(parts)
    }

    private suspend fun started(next: ShelfKey?) {
        val (gen, keptUnder) = lock.withLock {
            job?.cancel()
            job = null
            generation++
            key = next
            failed.clear()
            written.clear()
            pending.clear()
            seeded.complete(Unit)
            seeded = CompletableDeferred()
            setShown(CarServerShelves())
            generation to next?.keptUnder
        }
        val seed = keptUnder?.let { kept ->
            try {
                source.seed(kept)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "car shelves seed failed: ${e.javaClass.simpleName}")
                null
            }
        }
        lock.withLock {
            if (gen != generation) return
            if (seed != null) {
                setShown(
                    CarServerShelves(
                        stations = if (ShelfPart.Stations in written) shown.stations else seed.stations,
                        madeForYou = if (ShelfPart.Lists in written) shown.madeForYou else seed.madeForYou,
                        groups = if (ShelfPart.Lists in written) shown.groups else seed.groups,
                        chart = if (ShelfPart.Chart in written) shown.chart else seed.chart,
                        charts = shown.charts,
                        near = shown.near,
                    ),
                )
            }
            seeded.complete(Unit)
        }
        if (carAsked && next != null) ask(ShelfPart.entries.toSet())
    }

    // Asks for `parts` unless a refresh is on its way, in which case they
    // go once it ends. The session is taken with the generation, under the
    // lock, and only while it is the account the generation is for; a
    // session newer than the generation waits for its own.
    private suspend fun ask(parts: Set<ShelfPart>) {
        val run = lock.withLock {
            if (job?.isActive == true) {
                pending += parts
                return
            }
            val (current, currentKey) = sessionNow() ?: return
            if (currentKey != key) return
            val gen = generation
            scope.launch(start = CoroutineStart.LAZY) { refresh(gen, current, parts) }.also { job = it }
        }
        run.start()
    }

    private suspend fun refresh(gen: Int, session: Session, parts: Set<ShelfPart>) {
        for (part in ShelfPart.entries.filter { it in parts }) {
            val answer = try {
                Result.success(fetch(part, session))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "car shelves $part failed: ${e.javaClass.simpleName}")
                Result.failure(e)
            }
            lock.withLock {
                if (gen != generation) return
                val got = answer.getOrNull()
                if (got == null) {
                    failed += part
                } else {
                    failed -= part
                    written += part
                    setShown(got(shown))
                }
            }
        }
        val more = lock.withLock {
            if (gen != generation) return
            job = null
            pending.toSet().also { pending.clear() }
        }
        if (more.isNotEmpty()) ask(more)
    }

    // The part's answer, as a change to what is shown.
    private suspend fun fetch(part: ShelfPart, session: Session): (CarServerShelves) -> CarServerShelves = when (part) {
        ShelfPart.Stations -> source.stations(session).let { stations -> { it.copy(stations = stations) } }
        ShelfPart.Lists -> source.lists(session).let { lists -> { it.copy(madeForYou = lists.madeForYou, groups = lists.groups) } }
        ShelfPart.Chart -> source.chart().let { (chart, charts) -> { it.copy(chart = chart, charts = charts) } }
        ShelfPart.Near -> source.near().let { near -> { it.copy(near = near) } }
    }

    private suspend fun dropList(id: String) {
        lock.withLock { setShown(shown.without(id)) }
        if (carAsked) ask(setOf(ShelfPart.Lists))
    }

    // Callers hold the lock.
    private fun setShown(next: CarServerShelves) {
        if (next == shown) return
        shown = next
        _version.value = _version.value + 1
    }
}

// The key a signed-in state's shelves are kept under.
internal fun shelfKeyOf(state: SessionState): ShelfKey? {
    val session = (state as? SessionState.SignedIn)?.session ?: return null
    val account = state.accountId ?: return null
    return ShelfKey(account, session.runsOcto, session.id.takeIf { session.runsOcto })
}

// A chart the server could not give (it failed, or another server answered).
private class ChartFailed : Exception()

private class AppShelfSource(
    private val discovery: Discovery,
    private val charts: ChartLists,
    private val keptHome: KeptShelvesFiles,
    private val keptLists: KeptOctoListsFiles,
    private val liveRadio: LiveRadioStore,
    private val radioCatalog: RadioBrowser,
    private val places: Places,
) : ShelfSource {
    override suspend fun stations(session: Session): List<Station> = discovery.stationsOn(session)

    override suspend fun lists(session: Session): ServerLists = discovery.serverListsOn(session)

    override suspend fun chart(): Pair<List<TrackEntity>, List<ChartChoice>> {
        val songs = when (val answer = charts.answer(CHART_OVERALL)) {
            is ChartAnswer.Got -> answer.list.songs.map { it.track }
            ChartAnswer.None -> return emptyList<TrackEntity>() to emptyList()
            is ChartAnswer.Failed -> throw ChartFailed()
        }
        val choices = charts.choices()?.chart.orEmpty()
        return songs to choices
    }

    override suspend fun near(): List<LiveStation> {
        val search = liveRadio.search.first()
        val place = search.place ?: return emptyList()
        val miles = usesMiles(places.localeCountry())
        return radioCatalog.near(place, radiusMeters(miles, search.radiusIndex), search.sort) { livePlayable(it, places.decoders) }
    }

    override suspend fun seed(keptUnder: String): CarServerShelves? = withContext(Dispatchers.IO) {
        val home = keptHome.store.read(keptUnder)
        val lists = keptLists.store.read(keptUnder)?.read()
        if (home == null && lists == null) return@withContext null
        val mixes = home?.mixes.orEmpty().mapNotNull { (name, items) -> OctoListGroup.entries.firstOrNull { it.name == name }?.let { it to items } }
        CarServerShelves(
            stations = home?.stations.orEmpty(),
            madeForYou = home?.madeForYou.orEmpty(),
            groups = lists ?: mixes,
            chart = home?.charts.orEmpty(),
        )
    }
}
