package app.winters.octo.listening

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.ReadCheck
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.stableRead
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.data.spaceWords
import app.winters.octo.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// The disk a stored totals row and a diff row take with a server's long
// ids, measured (PlayTotalsDiskConstantsTest: 1,548 and 406). A rebuild
// asks for twice that free, for the write-ahead log, before each of its
// two phases.
internal const val PLAY_TOTAL_BYTES_PER_ROW = 1_600L
internal const val PLAY_TOTAL_DIFF_BYTES_PER_ROW = 430L

// What writers of plays tell the totals. Each call only notes what to do
// and returns; the totals' own runner does it later.
interface TotalsRequests {
    // These library songs' plays changed.
    fun refresh(trackIds: Collection<String>)

    // The plays sent for these songs of a server changed.
    fun refreshCopies(sourceId: String, serverIds: Collection<String>)

    // Plays changed for songs no one counted: every song is counted again.
    fun rebuild()

    object None : TotalsRequests {
        override fun refresh(trackIds: Collection<String>) {}

        override fun refreshCopies(sourceId: String, serverIds: Collection<String>) {}

        override fun rebuild() {}
    }
}

// Which write of the totals holds the write turn.
enum class TotalsWrite { Refresh, Apply }

// Points a test can stop the totals at. Each does nothing in the app.
internal open class TotalsHooks : PlayWalkHooks() {
    // After a rebuild's opening transaction, before its first read.
    open suspend fun opened() {}

    // Before each transaction of diff rows a rebuild writes begins, inside
    // it before it writes, and after it.
    open suspend fun beforeDiffWrite() {}

    open suspend fun inDiffWrite() {}

    open suspend fun diffWritten() {}

    // A rebuild's diff is written and checked for space; it asks for the
    // write turn next.
    open suspend fun beforeApply() {}

    // A write holds the write turn, before its first transaction.
    open suspend fun writing(write: TotalsWrite) {}

    // After each transaction of a rebuild's apply.
    open suspend fun applied() {}
}

// What a read of the stored totals answered: the value, or, while the
// totals cannot be brought up to date, the words for why.
sealed interface TotalsAnswer<out T> {
    data class Value<T>(val value: T) : TotalsAnswer<T>

    data class Problem(val words: String) : TotalsAnswer<Nothing>
}

suspend fun <T> answer(read: suspend () -> T): TotalsAnswer<T> = try {
    TotalsAnswer.Value(read())
} catch (e: TotalsFailed) {
    TotalsAnswer.Problem(e.words)
}

// A page of a rebuild's diff left from before, deleted by the diff's key.
internal const val DIFF_CLEAR_ROWS = 2_000
internal const val CLEAR_DIFF_PAGE_SQL = "DELETE FROM play_total_diff WHERE trackId IN (SELECT trackId FROM play_total_diff ORDER BY trackId LIMIT ?1)"

// Each library song's plays, counted once and kept in play_total, so
// readers read a few rows instead of every play. This is the table's one
// writer, one write at a time under its own lock, which holds nothing else
// but the database's transactions and is never taken inside
// betweenRebuilds; it never takes that either.
//
// A writer that knows its songs asks for them to be counted again
// (refresh), a song at a time. Each group of a save keeps the totals of the
// songs it touches itself (SaveApply), so a group's stamp needs no rebuild:
// once one rebuild has finished, the totals are built through each stamp
// as it comes. Everything is counted again (a rebuild) once at start, after
// each sync's steps and when the account in use changes. A rebuild writes
// which songs differ into play_total_diff first, then counts each of them
// again as it applies it, page by page while `applying` is odd, so a
// reader never reads a half-applied table and a group landing between the
// diff and the apply is never undone; it ends in one pass, and writes the
// account it counts for where the groups read it (merge_state).
//
// Readers wait from the swap stamp their wait starts at until the totals
// are built through it (read), and read while `applying` is even; one held
// read (withReadHold) keeps every write out while it reads.
@Singleton
class PlayTotals internal constructor(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val freeSpace: FreeSpace,
    // The account in use, null with none; read once known, then on each change.
    internal val account: Flow<String?>,
    // Waits for what the totals read to be in place.
    private val ready: suspend () -> Unit,
    private val page: Int = PLAY_PAGE,
) : TotalsRequests {
    @Inject constructor(db: CatalogDatabase, merge: CatalogMerge, freeSpace: FreeSpace, sessions: SessionRepository, store: ListeningStore) : this(
        db,
        merge,
        freeSpace,
        sessions.state.filter { it !is SessionState.Loading }.map { it.accountId }.distinctUntilChanged(),
        store::ready,
    )

    private val dao = db.playTotalsDao()
    private val work = db.workDao()
    private val links = db.listeningDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var hooks = TotalsHooks()

    // One write of the totals at a time. A leaf: holding it, nothing is
    // taken but the database's transactions.
    private val lock = Mutex()

    // The totals stamp: odd while a rebuild applies its diff, and after an
    // apply that failed until a rebuild finishes.
    private val _applying = MutableStateFlow(0L)
    val applying: StateFlow<Long> = _applying

    // The swap stamp the last finished rebuild started after, or -1 before
    // one finished in this process.
    private val _builtThrough = MutableStateFlow(-1L)
    val builtThrough: StateFlow<Long> = _builtThrough

    // Set while the last rebuild ended in a failure, with the words for it.
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    // Moves after each write that changed a row.
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    internal val holds = TotalsHolds(_applying) { _problem.value }

    // Whether a rebuild has finished in this process.
    @Volatile private var builtOnce = false

    // What was asked for and is not done yet.
    private val asked = Any()
    private var started = false
    private var rebuildAsked = false
    private var stampAsked = -1L
    private val songsAsked = LinkedHashSet<String>()
    private val copiesAsked = LinkedHashMap<String, LinkedHashSet<String>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // At launch: the first rebuild, then one on every change of account, and
    // whatever writers ask for, one at a time. Each even swap stamp (a group
    // of a save, which kept the totals itself) marks the totals built
    // through it at once, while they have built once, have not failed and
    // no rebuild's apply runs; otherwise it waits its turn.
    fun start() {
        synchronized(asked) {
            if (started) return
            started = true
            rebuildAsked = true
        }
        scope.launch {
            ready()
            launch { merge.swapStamp.collect { if (it > 0 && it % 2 == 0L) stampMoved(it) } }
            launch { account.drop(1).collect { rebuild() } }
            wake.receiveAsFlow().conflate().collect { runAsked() }
        }
        wake.trySend(Unit)
    }

    override fun refresh(trackIds: Collection<String>) {
        if (trackIds.isEmpty()) return
        synchronized(asked) { songsAsked += trackIds }
        wake.trySend(Unit)
    }

    override fun refreshCopies(sourceId: String, serverIds: Collection<String>) {
        if (serverIds.isEmpty()) return
        synchronized(asked) { copiesAsked.getOrPut(sourceId) { LinkedHashSet() } += serverIds }
        wake.trySend(Unit)
    }

    override fun rebuild() {
        synchronized(asked) { rebuildAsked = true }
        wake.trySend(Unit)
    }

    // The app came to the front: totals that failed are built again.
    fun foreground() {
        if (_failed.value) rebuild()
    }

    // Try again, from the words a failure showed.
    fun tryAgain() = rebuild()

    private fun askStamp(stamp: Long) {
        synchronized(asked) { stampAsked = maxOf(stampAsked, stamp) }
        wake.trySend(Unit)
    }

    private fun stampMoved(stamp: Long) {
        if (builtOnce && !_failed.value && _applying.value % 2 == 0L) {
            builtThroughAtLeast(stamp)
            _version.value += 1
        } else {
            askStamp(stamp)
        }
    }

    private fun builtThroughAtLeast(stamp: Long) {
        while (true) {
            val now = _builtThrough.value
            if (now >= stamp || _builtThrough.compareAndSet(now, stamp)) return
        }
    }

    // Reads `block` from the stored totals as one state of them and of the
    // library. It waits first, from the swap stamp now (the next one while
    // a swap runs), until the totals are built through it and no apply
    // runs; then reads, and reads again when a swap or an apply came
    // between. Its last try holds the totals still and runs inside
    // betweenRebuilds(kind), where it waits for nothing. While the totals
    // failed, a read that would wait ends with TotalsFailed instead.
    suspend fun <T> read(kind: HoldKind, prepare: suspend () -> Unit = {}, block: suspend () -> T): T =
        stableRead(merge, kind, prepare, TotalsCheck(waitFrom()), block)

    // Runs `block` with no write of the totals landing meanwhile, for a read
    // (or one attempt of a build) that must see one state of them. Taken
    // last, right before betweenRebuilds or the read it guards; nothing
    // that waits for anything else runs inside.
    suspend fun <T> withReadHold(block: suspend () -> T): T = holds.hold(block)

    // Waits until the totals are built through the library as it is now,
    // as a read does before it reads.
    suspend fun awaitBuilt() = awaitTotals(waitFrom())

    // The stored plays of these songs that were played, by id; never
    // waits. Whoever needs them as one state of the totals holds them still.
    suspend fun playsOf(ids: Collection<String>): Map<String, PlayCount> =
        ids.distinct().chunked(900).flatMap { dao.playsAmong(it) }.associateBy { it.trackId }

    // From the swap stamp now: the next even one while a group runs, but
    // while a save is applying the even one before, since each of its
    // groups keeps the totals itself.
    private fun waitFrom(): Long {
        val stamp = merge.swapStamp.value
        return if (merge.applyStamp.value % 2 != 0L) stamp - (stamp and 1L) else stamp + (stamp and 1L)
    }

    private suspend fun awaitTotals(from: Long) {
        val (built, applying, problem) = combine(_builtThrough, _applying, _problem, ::Triple)
            .first { (built, applying, problem) -> (built >= from && applying % 2 == 0L) || problem != null }
        if (built < from || applying % 2 != 0L) throw TotalsFailed(problem!!)
    }

    // A totals read's check: its tries wait for the totals, and one whose
    // `applying` moved runs again; its last holds the totals still.
    private inner class TotalsCheck(private val from: Long) : ReadCheck {
        override suspend fun ready() = awaitTotals(from)

        override fun stamp(): Long = _applying.value

        override suspend fun <T> last(inside: suspend () -> T): T = holds.hold(inside)
    }

    // Runs what was asked, one job at a time, until nothing is. A refresh
    // that could not write leaves its songs as they were, so everything is
    // counted again after it.
    private suspend fun runAsked() {
        while (true) {
            val job = synchronized(asked) { nextJob() } ?: return
            try {
                lock.withLock { job() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "play totals refresh failed: ${e.javaClass.simpleName}")
                rebuild()
            }
        }
    }

    // Under `asked`: a rebuild first (refreshes asked for while the totals
    // failed become one), then a stamp past the totals, then refreshes.
    private fun nextJob(): (suspend () -> Unit)? {
        val refreshing = songsAsked.isNotEmpty() || copiesAsked.isNotEmpty()
        if (rebuildAsked || (refreshing && _failed.value)) {
            rebuildAsked = false
            stampAsked = -1
            songsAsked.clear()
            copiesAsked.clear()
            return ::rebuildNow
        }
        if (stampAsked > _builtThrough.value) {
            val stamp = stampAsked
            stampAsked = -1
            return { afterSwap(stamp) }
        }
        if (!refreshing) return null
        val songs = songsAsked.toList()
        val copies = copiesAsked.mapValues { it.value.toList() }
        songsAsked.clear()
        copiesAsked.clear()
        return { refreshNow(songs, copies) }
    }

    // A group's stamp waited its turn: once a rebuild has finished and the
    // totals have not failed, they are built through it as they are, since
    // the group kept them; otherwise everything is counted again.
    private suspend fun afterSwap(stamp: Long) {
        if (!_failed.value && builtOnce) {
            builtThroughAtLeast(stamp)
            _version.value += 1
        } else {
            rebuildNow()
        }
    }

    // Counts these songs again, each from its own rows, and writes what
    // changed in one transaction a page. A song the library no longer has
    // loses its row.
    private suspend fun refreshNow(songs: List<String>, copies: Map<String, List<String>>) {
        val ids = LinkedHashSet(songs)
        for ((sourceId, serverIds) in copies) {
            serverIds.chunked(page).forEach { chunk -> links.linksAmong(sourceId, chunk).forEach { ids += it.trackId } }
        }
        if (ids.isEmpty()) return
        val account = account.first()
        for (chunk in ids.chunked(page)) {
            val changed = holds.writing(apply = false) {
                hooks.writing(TotalsWrite.Refresh)
                withContext(WriteLabels.of("totals refresh")) {
                    db.withTransaction {
                        chunk.count { id ->
                            val now = totalOf(SongPlays(id, dao.phonePlaysOf(id), dao.copiesOf(id, account)))
                            val stored = dao.totalOf(id)
                            when {
                                now == stored -> false
                                now == null -> dao.deleteTotals(listOf(id)) > 0
                                else -> true.also { dao.putTotals(listOf(now)) }
                            }
                        }
                    }
                }
            }
            if (changed > 0) _version.value += 1
        }
    }

    // Counts every song again in one pass: opens on an even swap stamp,
    // writing the account it counts for, writes which songs differ from the
    // stored totals, then counts each of them again as it applies them. A
    // failure keeps the last good totals (in the diff) or keeps `applying`
    // odd (in the apply), and is kept with its words until a rebuild
    // finishes.
    private suspend fun rebuildNow() {
        val stamp = merge.swapStamp.first { it % 2 == 0L }
        var applying = false
        try {
            val account = account.first()
            clearDiffs()
            withContext(WriteLabels.of("totals diff page")) {
                db.withTransaction {
                    dao.ensureMergeState()
                    dao.setTotalsAccount(account)
                }
            }
            hooks.opened()
            needs((work.trackTotal() + dao.storedCount()) * PLAY_TOTAL_DIFF_BYTES_PER_ROW * 2)
            diff(account)
            needs(dao.diffCount() * PLAY_TOTAL_BYTES_PER_ROW * 2)
            hooks.beforeApply()
            applying = true
            val changed = apply(account)
            builtOnce = true
            builtThroughAtLeast(stamp)
            _problem.value = null
            _failed.value = false
            if (changed) _version.value += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!applying) withContext(NonCancellable) { runCatching { clearDiffs() } }
            _problem.value = wordsFor(e)
            _failed.value = true
            holds.failed()
            Log.w("Octo", "play totals rebuild failed: ${e.javaClass.simpleName}")
        }
    }

    // The diff a rebuild before this one left (one cut off, or one of an
    // older launch: the library's open deletes nothing) goes, DIFF_CLEAR_ROWS
    // a transaction, before a rebuild writes its own.
    private suspend fun clearDiffs() = withContext(WriteLabels.of("totals diff clear")) {
        while (true) {
            val gone = db.withTransaction {
                db.openHelper.writableDatabase.compileStatement(CLEAR_DIFF_PAGE_SQL).use {
                    it.bindLong(1, DIFF_CLEAR_ROWS.toLong())
                    it.executeUpdateDelete()
                }
            }
            if (gone < DIFF_CLEAR_ROWS) break
        }
    }

    private fun needs(bytes: Long) {
        if (freeSpace.bytes() < bytes) throw NotEnoughSpace(bytes, SpaceFor.PlayCounts)
    }

    private fun wordsFor(e: Exception): String = when (e) {
        is NotEnoughSpace -> spaceWords(SpaceFor.PlayCounts, e.neededBytes)
        is SQLiteFullException -> spaceWords(SpaceFor.PlayCounts, null)
        else -> e.userMessage()
    }

    // Walks the phone's plays, the server copies and the stored totals
    // together by song id and writes each song whose stored row differs
    // from its count now into the diff, a page per transaction.
    private suspend fun diff(account: String?) {
        val rows = ArrayList<PlayTotalDiffEntity>(page)
        suspend fun write() {
            if (rows.isEmpty()) return
            hooks.beforeDiffWrite()
            withContext(WriteLabels.of("totals diff page")) {
                db.withTransaction {
                    hooks.inDiffWrite()
                    dao.putDiffs(rows)
                }
            }
            rows.clear()
            hooks.diffWritten()
        }
        val stored = Paged<PlayTotalEntity>(page) { last ->
            dao.storedPage(last?.trackId ?: "", page).also { hooks.page(PlayStream.Stored) }
        }
        walkPlays(phoneStream(dao, hooks, size = page), copyStream(dao, hooks, account, page), stored) { song, row ->
            val now = totalOf(song)
            if (now != row) {
                rows += now?.let(::putOf) ?: PlayTotalDiffEntity(song.trackId, DIFF_DELETE, null, null, null, null, null, null, null, null, null)
                if (rows.size >= page) write()
            }
        }
        write()
    }

    // Applies the diff to the totals while `applying` is odd: each of its
    // songs counted again inside the transaction that writes it, from its
    // own rows as they are then, as a refresh counts one, so a group that
    // landed since the diff is never undone; a song the library no longer
    // has loses its row. A transaction closes once the phone plays its songs
    // will read (by the diff's count) reach the save's bound, so a song
    // played that many times has one of its own. Answers whether a row
    // changed.
    private suspend fun apply(account: String?): Boolean = holds.writing(apply = true) {
        hooks.writing(TotalsWrite.Apply)
        var changed = false
        var after = ""
        while (true) {
            val rows = dao.diffPage(after, page)
            if (rows.isEmpty()) break
            var from = after
            var at = 0
            while (at < rows.size) {
                var plays = 0L
                var end = at
                while (end < rows.size && (end == at || plays + weight(rows[end]) <= merge.bound)) {
                    plays += weight(rows[end])
                    end++
                }
                val part = rows.subList(at, end)
                val moved = withContext(WriteLabels.of("phase 2 page")) {
                    db.withTransaction {
                        val written = part.count { diff ->
                            val id = diff.trackId
                            val now = totalOf(SongPlays(id, dao.phonePlaysOf(id), dao.copiesOf(id, account)))
                            val stored = dao.totalOf(id)
                            when {
                                now == stored -> false
                                now == null -> dao.deleteTotals(listOf(id)) > 0
                                else -> true.also { dao.putTotals(listOf(now)) }
                            }
                        }
                        dao.dropDiffs(from, part.last().trackId)
                        written
                    }
                }
                if (moved > 0) changed = true
                hooks.applied()
                from = part.last().trackId
                at = end
            }
            if (rows.size < page) break
            after = rows.last().trackId
        }
        changed
    }

    // What counting a diff row's song again reads: its phone plays, by the
    // diff's count, and the song's own row.
    private fun weight(diff: PlayTotalDiffEntity): Long = (diff.phonePlays ?: 0).toLong() + 1

    private fun putOf(row: PlayTotalEntity) = PlayTotalDiffEntity(
        row.trackId, DIFF_PUT, row.albumId, row.artistId, row.plays, row.lastPlayedAt, row.recentAt,
        row.phonePlays, row.phoneLastAt, row.sortKey, row.artistSortKey,
    )
}

// A diff row's kind.
internal const val DIFF_DELETE = 0
internal const val DIFF_PUT = 1
