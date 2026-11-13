package app.winters.octo.listening

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.stableRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

// How many plays History's recent list goes back.
const val HISTORY_RECENT = 500

// Where a most played song is looked for.
sealed interface PlayedIn {
    data class Album(val id: String) : PlayedIn

    data class Artist(val id: String) : PlayedIn
}

// What was played, on the phone and on the server together. Home's and the
// car's shelves, History, Song info and Autoplay read from here, each a
// page at a time or from the stored totals, so none holds more than its
// list. A read of the totals waits until they are built
// through the library as it is, and ends with TotalsFailed instead while
// they cannot be brought up to date.
@Singleton
class PlayHistory internal constructor(
    private val store: ListeningStore,
    private val totals: PlayTotals,
    private val merge: CatalogMerge,
    private val db: CatalogDatabase,
    private val page: Int,
    private val clock: () -> Long,
    // How long the shelves stay shared after the last look.
    private val keepMillis: Long = 5_000,
    // Where the shelves are shared while anyone looks.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Inject constructor(store: ListeningStore, totals: PlayTotals, merge: CatalogMerge, db: CatalogDatabase) :
        this(store, totals, merge, db, PLAY_PAGE, System::currentTimeMillis)

    private val dao = db.playTotalsDao()

    internal var hooks = PlayWalkHooks()

    // Home's and the car's shelves, read again whenever the totals, the
    // albums or the songs change and when the totals fail or recover; reads
    // that pile up while one runs become one more. Nothing comes before the
    // totals are first built, so a shelf shows it is loading, never empty.
    // A read walks every album's totals, so its last try is a walk of the
    // merge lock, which never enters past a waiting swap. Shared while
    // anyone looks, and forgotten when no one does, so the next look reads
    // them afresh.
    val shelves: Flow<TotalsAnswer<PlayShelves>> = changes(listOf("play_total", "album", "track"), totals.failed)
        .conflate()
        .map { answer { totals.read(HoldKind.Walk) { shelvesOf(dao, clock(), hooks, page) } } }
        .shareIn(scope, SharingStarted.WhileSubscribed(keepMillis, replayExpirationMillis = 0), replay = 1)

    // One song's stored plays and its latest play, or null when it was
    // never played: for Song info.
    suspend fun played(trackId: String): PlayCount? = totals.read(HoldKind.Read) { dao.playsAmong(listOf(trackId)).firstOrNull() }

    // The songs last played on the phone, the latest first, then the lower
    // id: Autoplay's songs not to pick again. One read, holding the totals
    // still, once they are built through the library as it is, with no
    // lock after it; a waiting write turns it away until the write has run.
    // Ends with TotalsFailed while they cannot be brought up to date.
    suspend fun recentlyPlayedIds(limit: Int): List<String> {
        totals.awaitBuilt()
        return totals.withReadHold { dao.recentPhoneIds(limit) }
    }

    // History's latest plays, newest first, as a walk of the server copies
    // read as one state of the library, again on each change of the plays
    // or the copies.
    fun recentPlays(limit: Int = HISTORY_RECENT): Flow<List<HistoryPlay>> =
        changes(listOf("play_event", "source_track", "track"))
            .conflate()
            .map {
                store.ready()
                stableRead(merge, HoldKind.Walk) { recentHistoryOf(dao, limit, hooks, page) }
            }
            .flowOn(Dispatchers.Default)

    // History's most played songs, played since `since` or ever (null). For
    // all time they are the stored totals; within a range, a walk of the
    // plays and the copies read as one state of the library, again on each
    // change of what it reads or of the account in use.
    fun mostPlayed(since: Long?, limit: Int): Flow<TotalsAnswer<List<PlayedTrack>>> =
        if (since == null) {
            changes(listOf("play_total", "track"), totals.failed)
                .conflate()
                .map { answer { totals.read(HoldKind.Read) { dao.mostPlayed(limit) } } }
        } else {
            changes(listOf("play_event", "source_track", "sent_play", "sent_recent", "track"), totals.account)
                .conflate()
                .map {
                    store.ready()
                    TotalsAnswer.Value(stableRead(merge, HoldKind.Walk) { mostPlayedSince(dao, totals.account.first(), since, limit, hooks, page) })
                }
                .flowOn(Dispatchers.Default)
        }

    // Try again, from the words failed totals showed.
    fun tryAgain() = totals.tryAgain()

    // A count that moves on every change of these tables (and once at
    // first), and on every later value of `also`.
    private fun changes(tables: List<String>, also: Flow<*>? = null): Flow<Long> = flow {
        var n = 0L
        val tracked = db.invalidationTracker.createFlow(*tables.toTypedArray()).map { }
        val more = also?.drop(1)?.map { }
        (if (more == null) tracked else merge(tracked, more)).collect { emit(++n) }
    }
}
