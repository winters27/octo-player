package app.winters.octo.livelists

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.followAll
import app.winters.octo.catalog.logRoom
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.catalog.trackOrderPage
import app.winters.octo.catalog.write
import app.winters.octo.data.spaceWords
import app.winters.octo.listening.PlayTotals
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.SongFields
import app.winters.octo.query.matcher
import app.winters.octo.sort.Listening
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.TrackFields
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// How many rows each step of a live list's walk reads or writes at a time.
internal const val LIVE_PAGE = 500

// How many saved live lists nothing reads are kept, the most recently used.
internal const val LIVE_KEPT = 3

// The disk one picked song takes with its key and indexes, measured as the
// file's pages once the log is checkpointed in, on a list sorted by title
// with a server's long ids under an owner led by the launch's mark
// (LiveDiskFullTest: 3,787). A title's order key there runs to 980 bytes,
// so live_song's primary-key entry (owner, gen, orderKey) passes the 1,002
// bytes a 4 KB index page keeps in place for two songs in five, and each of
// those spills onto an overflow page of its own. A build asks for this a
// song and the log's room beside them (logRoom).
internal const val LIVE_BYTES_PER_ROW = 4_050L

// The kinds of owner token of saved lists and of lists being made.
internal const val LIVE_KIND = "live"
internal const val DRAFT_KIND = "draft"

// The owner a walk that keeps nothing reads as; nothing is written under it.
private const val PICKS_OWNER = "picks"

// This launch's lists being made with one query's rules, the last used
// first, and its saved lists, the last used first: each a seek of the
// owner's range of its kind (WorkOwners.kindRange).
internal const val LIVE_DRAFT_SQL = "SELECT owner FROM live_state WHERE owner >= ? AND owner < ? AND queryHash = ? ORDER BY usedAt DESC LIMIT 1"
internal const val LIVE_SAVED_SQL = "SELECT owner FROM live_state WHERE owner >= ? AND owner < ? ORDER BY usedAt DESC"

// The library in id order, a page after an id.
internal const val TRACK_ID_PAGE_SQL = "SELECT * FROM track WHERE id > ? ORDER BY id LIMIT ?"

// How many songs a generation of an owner's list holds: the count its
// publish stored while it is the one published, else its rows counted.
// Arguments owner, generation.
internal const val PINNED_COUNT_SQL =
    "SELECT CASE WHEN (SELECT gen FROM live_state WHERE owner = ?1) = ?2 THEN (SELECT count FROM live_state WHERE owner = ?1) " +
        "ELSE (SELECT COUNT(*) FROM live_song WHERE owner = ?1 AND gen = ?2) END"

// What a live list shows: nothing yet, its published generation (how many
// songs and how long together), or the words for why it cannot be brought
// up to date, over what it showed before, if anything.
sealed interface LiveShown {
    data object Loading : LiveShown

    data class Shown(val owner: String, val gen: Long, val count: Int, val durationMs: Long) : LiveShown

    data class Problem(val words: String, val before: Shown?) : LiveShown
}

// A copy of a live list made for one reader: the library's swap count its
// songs were read at, and how many it holds.
data class LiveCopy(val swapCount: Long, val count: Int)

// Points a test can stop a live list at. Each does nothing in the app.
internal open class LiveHooks {
    // A walk of `owner`'s list holds the build lock, before its first read.
    open suspend fun opened(owner: String) {}

    // After the walk read and wrote its `n`th page.
    open suspend fun page(owner: String, n: Int) {}

    // Inside the read hold of the `n`th page's plays.
    open suspend fun plays(owner: String, n: Int) {}

    // After a walk published.
    open suspend fun published(owner: String, gen: Long) {}

    // A copy into `into` is about to wait for the build lock.
    open suspend fun copying(into: String) {}
}

// Live lists, worked out in the database: each list a reader shows (its
// page, the editor's preview, a car page) is kept in live_song under its
// owner, in the list's order, one generation at a time. A change of the
// list's rules builds a new generation beside the one shown and flips to
// it in one step; a change of the library, a like or a play changes the
// shown generation in place, a page of songs at a time with its count and
// length. A reader that holds the generation it reads (the car's, while
// it answers) asks that a generation held never changes: a walk while one
// is held builds a new generation beside it instead, and a walk changing
// the list in place when a reader takes hold of it goes on as a new one.
// No step holds more than a page of songs, or the list's limit.
//
// Each walk runs once to its end under the build lock, which is held by
// nothing else and taken with no other lock held, never inside the merge
// lock: a swap during a walk is seen in its writes, and the walk finishes,
// publishes, and the swap's own change of the library walks it again. A
// list that reads plays waits for the stored totals first, then reads each
// page's plays holding them still.
//
// A list is kept only while something reads it (a reader pins it), and at
// most LIVE_KEPT saved lists nothing reads are kept beside them.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LivePicks internal constructor(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val totals: PlayTotals,
    private val owners: WorkOwners,
    private val freeSpace: FreeSpace,
    private val clock: () -> Long,
    private val page: Int,
) {
    @Inject constructor(db: CatalogDatabase, merge: CatalogMerge, totals: PlayTotals, owners: WorkOwners, freeSpace: FreeSpace) :
        this(db, merge, totals, owners, freeSpace, System::currentTimeMillis, LIVE_PAGE)

    private val reader = db.readerDao()
    private val work = db.workDao()
    private val lock = Mutex()
    private val used = AtomicLong()

    // Held by each write that changes a shown generation in place for a
    // list whose held generations never change, and by each pin of
    // pinShown: a pin waits for at most one page's write, and no write
    // changes a generation once it is pinned. `changed` counts each owner's
    // writes in place under it.
    private val inPlace = Mutex()
    private val changed = HashMap<String, Long>()

    internal var hooks = LiveHooks()

    // `owner`'s list of the songs `query` picks, kept up to date while
    // collected: what it shows now, then after each walk. A walk follows
    // every change of the library, a like or a play, and play counts that
    // come back after failing; a change of `query` stops a walk for the
    // old rules. A walk that runs short of space or play counts keeps what
    // was shown, with the words for why. With `keepHeld` a generation a
    // reader holds (pinShown) never changes for as long as it reads: a walk
    // while one is held builds a new generation beside it and flips to it,
    // and with none held it changes the shown one in place.
    fun watch(owner: String, listId: String?, query: Flow<LibraryQuery>, keepHeld: Boolean = false): Flow<LiveShown> = flow {
        owners.pin(owner)
        try {
            emit(shown(owner) ?: LiveShown.Loading)
            query.distinctUntilChanged()
                .flatMapLatest { rules -> changes().conflate().map { walkShowing(owner, listId, rules, keepHeld) } }
                .collect { emit(it) }
        } finally {
            withContext(NonCancellable) {
                owners.unpin(owner)
                trim(keeping = null)
            }
        }
    }.flowOn(Dispatchers.IO)

    // What `owner`'s list shows now, or null before its first walk.
    suspend fun shown(owner: String): LiveShown.Shown? = withContext(Dispatchers.IO) {
        db.read("SELECT gen, count, durationMs FROM live_state WHERE owner = ?", owner) {
            LiveShown.Shown(owner, it.getLong(0), it.getInt(1), it.getLong(2))
        }.firstOrNull()
    }

    // The list `owner` holds, read in its order: each read reads the
    // generation published when it runs, or with `at` that one generation.
    fun loader(owner: String, at: LivePinned? = null): LiveLoader = LiveLoader(db, owner, at)

    // Pins the generation `owner` shows now and answers it with its count,
    // so its songs stay as they are until unpinShown, however many walks
    // run meanwhile (for a list watched with `keepHeld`); null before one is
    // published.
    suspend fun pinShown(owner: String): LivePinned? = withContext(Dispatchers.IO) {
        val (gen, changes) = inPlace.withLock { owners.pinPublished(owner)?.let { it to (changed[owner] ?: 0L) } } ?: return@withContext null
        try {
            // A walk that published since the pin left this generation's
            // count behind: its rows are counted.
            LivePinned(gen, db.readLong(PINNED_COUNT_SQL, owner, gen).toInt(), changes)
        } catch (e: Throwable) {
            withContext(NonCancellable) { releaseGen(owner, gen) }
            throw e
        }
    }

    // Lets a pin of pinShown go; a generation no longer shown goes with
    // its last pin.
    suspend fun unpinShown(owner: String, gen: Long) = withContext(Dispatchers.IO + NonCancellable) { releaseGen(owner, gen) }

    // A play of a live list for a queue's fill under the fill's own owner
    // (`into`), read from the list's shown songs until the fill's copy is
    // made, then from the copy. `fallback` is the owner of the page the play
    // was chosen from, read when the list's own page and preview show
    // nothing.
    fun forSpec(listId: String?, query: LibraryQuery, into: String, fallback: String? = null): LiveSpecLoader =
        LiveSpecLoader(this, db, listId, query, into, fallback)

    // What a play of a list no reader shows starts from, read as eachPick
    // reads, with no write or lock: the first `n` songs `query` picks in the
    // list's order, how many it picks (at most its limit), and with `seed`
    // one song drawn at random: over every song it picks, or for a list
    // with a limit, over those first `n`.
    internal suspend fun firstPicks(query: LibraryQuery, n: Int, seed: Long?): LiveStartPicks {
        val limit = query.limit?.takeIf { it >= 0 }
        val keep = if (limit != null) minOf(n, limit) else n
        val inOrder = Comparator<Pair<ByteArray, String>> { a, b -> unsigned(a.first, b.first).let { if (it != 0) it else a.second.compareTo(b.second) } }
        val worstFirst = PriorityQueue(keep + 1, inOrder.reversed())
        val random = seed?.let { Random(it) }
        var picked = 0
        var drawn: Pair<ByteArray, String>? = null
        if (keep > 0) {
            eachPick(query) { song, key ->
                val row = key to song.id
                picked++
                if (random != null && limit == null && random.nextInt(picked) == 0) drawn = row
                worstFirst += row
                if (worstFirst.size > keep) worstFirst.poll()
                true
            }
        }
        val rows = worstFirst.sortedWith(inOrder)
        if (random != null && limit != null && rows.isNotEmpty()) drawn = rows[random.nextInt(rows.size)]
        val keyed = { row: Pair<ByteArray, String> -> Keyed(liveRowKey(row.first, row.second), row.second) }
        return LiveStartPicks(rows.map(keyed), if (limit != null) minOf(picked, limit) else picked, drawn?.let(keyed))
    }

    // The owner whose shown songs a play of this list reads: the saved
    // list's, or an editor's preview of the same rules; null when none is
    // shown yet.
    internal suspend fun sourceOf(listId: String?, query: LibraryQuery): String? = withContext(Dispatchers.IO) {
        if (listId != null) return@withContext owners.live(listId).takeIf { shown(it) != null }
        val (from, until) = WorkOwners.kindRange(DRAFT_KIND)
        db.read(LIVE_DRAFT_SQL, from, until, hashOf(query)) { it.getString(0) }.firstOrNull()
    }

    // Copies the songs `source` shows now into `into`, in their order, a
    // page a transaction, under the build lock so no walk changes them
    // meanwhile. With nothing shown, walks `query` straight into `into`,
    // holding the play counts still for the whole walk when it reads them,
    // so the copy is one state of them. Answers the swap count the copied
    // songs were read at and how many there are.
    suspend fun copy(source: String?, query: LibraryQuery, into: String): LiveCopy = withContext(Dispatchers.IO) {
        val plays = readsPlays(query)
        val showing = source?.let { shown(it) }
        if (showing == null) {
            if (plays) totals.awaitBuilt()
            merge.swapStamp.first { it % 2 == 0L }
        }
        hooks.copying(into)
        lock.withLock {
            val gen = if (showing != null) owners.pinPublished(source) else null
            try {
                if (gen == null) {
                    val walk: suspend () -> Walked = { build(into, null, query, wholeHold = plays) }
                    val built = if (plays) totals.withReadHold(walk) else walk()
                    LiveCopy(built.swapCount, built.count)
                } else {
                    copyGen(source!!, gen, into)
                }
            } finally {
                if (gen != null) releaseGen(source!!, gen)
            }
        }
    }

    // A copy of a saved list's songs for a playlist made from it, under an
    // owner of its own, read through LiveRows and cleared by the caller:
    // the songs it shows, or with none shown, a walk of its rules (`query`).
    suspend fun copyForPlaylist(listId: String, query: LibraryQuery): String {
        val into = owners.copy()
        copy(sourceOf(listId, query), query, into)
        return into
    }

    // Each song `query` picks, in library order, with its key in the
    // list's order, until `each` answers false: for what is worked out of a
    // list without keeping it (its covers). Waits for current play counts
    // when the list reads them, and reads each page's under a hold of its
    // own, as a walk does; takes no lock.
    internal suspend fun eachPick(query: LibraryQuery, each: (TrackEntity, ByteArray) -> Boolean) = withContext(Dispatchers.IO) {
        if (readsPlays(query)) totals.awaitBuilt()
        val walk = Walk(PICKS_OWNER, null, query, baseline = 0, wholeHold = false)
        var after = "" to ""
        while (true) {
            val songs = reader.trackOrderPage(after.first, after.second, page)
            walk.look(songs)
            for (song in songs) {
                if (walk.picks(song) && !each(song, walk.rowOf(song).orderKey)) return@withContext
            }
            if (songs.size < page) return@withContext
            after = songs.last().let { it.sortKey to it.id }
        }
    }

    // The songs of a copy made for a playlist, followed as they are read.
    fun rows(owner: String): LiveRows = LiveRows(db, owner)

    // Empties an owner's list: an editor's preview closing, a queue fill
    // done, a copy made into a playlist. Waits for its last reader.
    suspend fun clear(owner: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            owners.clear(owner) {
                deleteRows(owner, null)
                db.write("DELETE FROM live_state WHERE owner = ?", owner)
            }
        }
    }

    // Moves on every change a walk follows, and once at first.
    private fun changes(): Flow<Unit> = merge(
        db.invalidationTracker.createFlow("track", "album", "liked_track", "play_total").map { },
        totals.failed.drop(1).map { },
    )

    private suspend fun walkShowing(owner: String, listId: String?, query: LibraryQuery, keepHeld: Boolean): LiveShown {
        val before = shown(owner)
        return try {
            if (readsPlays(query)) totals.awaitBuilt()
            merge.swapStamp.first { it % 2 == 0L }
            lock.withLock { build(owner, listId, query, wholeHold = false, keepHeld = keepHeld) }
            shown(owner) ?: LiveShown.Loading
        } catch (e: CancellationException) {
            throw e
        } catch (e: TotalsFailed) {
            LiveShown.Problem(e.words, before)
        } catch (e: NotEnoughSpace) {
            Log.w("Octo", "live list: not enough space")
            LiveShown.Problem(spaceWords(SpaceFor.List, e.neededBytes), before)
        } catch (e: SQLiteFullException) {
            Log.w("Octo", "live list: the disk is full")
            LiveShown.Problem(spaceWords(SpaceFor.List, null), before)
        }
    }

    // What one walk left: the generation shown, how many songs, and the
    // swap count its songs were read at.
    private class Walked(val gen: Long, val count: Int, val swapCount: Long)

    // One walk of `owner`'s list for `query`, under the build lock: a new
    // generation when the rules changed, none is shown, or with `keepHeld`
    // a reader holds the shown one; else the shown one changed in place. A
    // reader that takes hold of it part way (with `keepHeld`) has the walk
    // start again as a new generation. `wholeHold`: the caller holds the
    // play counts still for the whole walk.
    private suspend fun build(owner: String, listId: String?, query: LibraryQuery, wholeHold: Boolean, keepHeld: Boolean = false): Walked {
        hooks.opened(owner)
        val (baseline, state) = db.withTransaction { work.swapCount() to state(owner) }
        if (state == null && WorkOwners.kindOf(owner) == LIVE_KIND) trim(keeping = owner)
        val walk = Walk(owner, listId, query, baseline, wholeHold, keepHeld)
        var crossed = false
        val walked = if (state == null || state.queryHash != walk.hash || (keepHeld && owners.genPinned(owner, state.gen))) {
            walk.fresh(state).also { crossed = walk.crossed }
        } else {
            try {
                walk.diff(state).also { crossed = walk.crossed }
            } catch (e: HeldDuringWalk) {
                val again = Walk(owner, listId, query, baseline, wholeHold, keepHeld)
                again.fresh(state(owner)).also { crossed = walk.crossed || again.crossed }
            }
        }
        if (crossed) Log.i("Octo", "live list: a swap came during the walk")
        hooks.published(owner, walked.gen)
        return walked
    }

    // A reader took hold of the generation a walk was changing in place.
    private class HeldDuringWalk : Exception()

    private inner class Walk(
        val owner: String,
        val listId: String?,
        val query: LibraryQuery,
        val baseline: Long,
        val wholeHold: Boolean,
        val keepHeld: Boolean = false,
    ) {
        val hash = hashOf(query)
        val order = query.sort?.order()
        val limit = query.limit?.takeIf { it >= 0 }
        val plays = readsPlays(query)
        val fields = PageFields()
        val test = query.matcher(clock(), fields)
        var crossed = false
        var pages = 0

        // Reads one page's likes and plays into the fields its songs are
        // tested and ordered by; the plays under a read hold of their own
        // unless the whole walk holds one.
        suspend fun look(songs: List<TrackEntity>) {
            val ids = songs.map { it.id }
            val liked = HashSet<String>()
            ids.chunked(ID_CHUNK).forEach { liked += db.userDao().likedAmong(it).first() }
            val listening = if (!plays || ids.isEmpty()) {
                emptyMap()
            } else {
                val read: suspend () -> Map<String, Listening> = {
                    hooks.plays(owner, pages + 1)
                    totals.playsOf(ids).mapValues { (_, p) -> Listening(p.plays, p.lastPlayedAt) }
                }
                if (wholeHold) read() else totals.withReadHold(read)
            }
            fields.page = TrackFields(liked, listening)
        }

        fun picks(song: TrackEntity): Boolean = test?.invoke(song) ?: true

        fun rowOf(song: TrackEntity) = Row(liveOrderKey(song, order, fields.page), song.id, song.durationMs)

        // A new generation beside the shown one, flipped to at the end; an
        // unfinished one is deleted.
        suspend fun fresh(state: LiveStateRow?): Walked {
            val gen = nextGen(owner)
            var published = false
            try {
                needs(roomFor(limit?.toLong() ?: db.readLong("SELECT COUNT(*) FROM track")))
                var count = 0
                var duration = 0L
                if (limit != null) {
                    val top = topSet()
                    count = top.size
                    duration = top.sumOf { it.durationMs }
                    publish(gen, state, count, duration) { insertRows(gen, top) }
                } else {
                    walkById { songs ->
                        val rows = songs.filter(::picks).map(::rowOf)
                        count += rows.size
                        duration += rows.sumOf { it.durationMs }
                        if (rows.isNotEmpty()) write { insertRows(gen, rows) }
                    }
                    publish(gen, state, count, duration) {}
                }
                published = true
                return Walked(gen, count, baseline)
            } finally {
                if (!published) withContext(NonCancellable) { deleteRows(owner, gen) }
            }
        }

        // The shown generation changed in place: each song's row added,
        // moved or taken out as it now is, its count and length with it.
        suspend fun diff(state: LiveStateRow): Walked {
            val gen = state.gen
            if (limit != null) {
                val top = topSet()
                val stored = db.read("SELECT orderKey, trackId, durationMs FROM live_song WHERE owner = ? AND gen = ?", owner, gen) {
                    Row(it.getBlob(0), it.getString(1), it.getLong(2))
                }
                val now = top.associateBy { it.trackId }
                val before = stored.associateBy { it.trackId }
                val removes = stored.filter { s -> now[s.trackId]?.sameAs(s) != true }
                val adds = top.filter { n -> before[n.trackId]?.sameAs(n) != true }
                needs(roomFor(adds.size.toLong()))
                publish(gen, state, top.size, top.sumOf { it.durationMs }) {
                    removes.forEach { deleteRow(gen, it) }
                    insertRows(gen, adds)
                }
                return Walked(gen, top.size, baseline)
            }
            var count = state.count
            var duration = state.durationMs
            val stored = StoredRows(gen)
            walkById { songs ->
                val removes = ArrayList<Row>()
                val adds = ArrayList<Row>()
                for (song in songs) {
                    removes += stored.before(song.id)
                    val old = stored.take(song.id)
                    val now = if (picks(song)) rowOf(song) else null
                    if (old != null && now?.sameAs(old) != true) removes += old
                    if (now != null && old?.sameAs(now) != true) adds += now
                }
                if (songs.size < page) removes += stored.rest()
                if (removes.isNotEmpty() || adds.isNotEmpty()) {
                    needs(roomFor(adds.size.toLong()))
                    count += adds.size - removes.size
                    duration += adds.sumOf { it.durationMs } - removes.sumOf { it.durationMs }
                    change(gen) {
                        removes.forEach { deleteRow(gen, it) }
                        insertRows(gen, adds)
                        db.write("UPDATE live_state SET count = ?, durationMs = ? WHERE owner = ?", count, duration, owner)
                    }
                }
            }
            publish(gen, state, count, duration) {}
            return Walked(gen, count, baseline)
        }

        // The library in id order, a page at a time, each page's likes and
        // plays read first.
        suspend fun walkById(each: suspend (List<TrackEntity>) -> Unit) {
            var after = ""
            while (true) {
                val songs = reader.tracks(SimpleSQLiteQuery(TRACK_ID_PAGE_SQL, arrayOf<Any>(after, page)))
                look(songs)
                each(songs)
                hooks.page(owner, ++pages)
                if (songs.size < page) return
                after = songs.last().id
            }
        }

        // The first `limit` songs in the list's order: one walk of the
        // library keeping the best `limit` keys seen.
        suspend fun topSet(): List<Row> {
            val most = limit ?: return emptyList()
            if (most == 0) return emptyList()
            val worstFirst = PriorityQueue<Row>(most + 1) { a, b -> unsigned(b.orderKey, a.orderKey) }
            var after = "" to ""
            while (true) {
                val songs = reader.trackOrderPage(after.first, after.second, page)
                look(songs)
                for (song in songs) {
                    if (!picks(song)) continue
                    worstFirst += rowOf(song)
                    if (worstFirst.size > most) worstFirst.poll()
                }
                hooks.page(owner, ++pages)
                if (songs.size < page) break
                after = songs.last().let { it.sortKey to it.id }
            }
            return worstFirst.sortedWith { a, b -> unsigned(a.orderKey, b.orderKey) }
        }

        // One write transaction, which notes a swap since the walk began.
        suspend fun write(block: () -> Unit) = withContext(WriteLabels.of("live list page")) {
            db.withTransaction {
                if (work.swapCount() != baseline) crossed = true
                block()
            }
        }

        // A write that changes `gen`, the shown generation, in place. With
        // `keepHeld` none runs once a reader holds `gen`: the walk gives up
        // (HeldDuringWalk) and starts again as a new generation.
        suspend fun change(gen: Long, block: () -> Unit) {
            if (!keepHeld) return write(block)
            inPlace.withLock {
                if (owners.genPinned(owner, gen)) throw HeldDuringWalk()
                write(block)
                changed[owner] = (changed[owner] ?: 0L) + 1
            }
        }

        // The last transaction: `gen` becomes what readers read, with its
        // count, its length and the swap count its songs were read at. A
        // generation shown before goes once nothing pins it.
        suspend fun publish(gen: Long, state: LiveStateRow?, count: Int, duration: Long, also: () -> Unit) {
            val last = {
                also()
                db.write(
                    "INSERT OR REPLACE INTO live_state (owner, listId, gen, queryHash, count, durationMs, usedAt, swapCount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    owner, listId ?: state?.listId, gen, hash, count, duration, used.incrementAndGet(), baseline,
                )
            }
            if (state?.gen == gen) change(gen) { last() } else write { last() }
            if (state != null && state.gen != gen) owners.publish(owner, state.gen)
            owners.publish(owner, gen).forEach { deleteRows(owner, it) }
        }

        fun insertRows(gen: Long, rows: List<Row>) {
            rows.forEach { db.write("INSERT INTO live_song (owner, gen, orderKey, trackId, durationMs) VALUES (?, ?, ?, ?, ?)", owner, gen, it.orderKey, it.trackId, it.durationMs) }
        }

        fun deleteRow(gen: Long, row: Row) {
            db.write("DELETE FROM live_song WHERE owner = ? AND gen = ? AND orderKey = ?", owner, gen, row.orderKey)
        }

        // The rows of the shown generation in id order, walked beside the
        // library a page at a time.
        inner class StoredRows(private val gen: Long) {
            private val buffer = ArrayDeque<Row>()
            private var after = ""
            private var done = false

            private fun fill() {
                if (buffer.isNotEmpty() || done) return
                val rows = db.read(
                    "SELECT orderKey, trackId, durationMs FROM live_song WHERE owner = ? AND gen = ? AND trackId > ? ORDER BY trackId LIMIT ?",
                    owner, gen, after, page,
                ) { Row(it.getBlob(0), it.getString(1), it.getLong(2)) }
                buffer.addAll(rows)
                if (rows.size < page) done = true
                rows.lastOrNull()?.let { after = it.trackId }
            }

            // The stored rows of songs before `id`, which the library no
            // longer has.
            fun before(id: String): List<Row> {
                val out = ArrayList<Row>()
                while (true) {
                    fill()
                    val head = buffer.firstOrNull() ?: return out
                    if (SqlOrder.text(head.trackId, id) >= 0) return out
                    out += buffer.removeFirst()
                }
            }

            fun take(id: String): Row? {
                fill()
                return if (buffer.firstOrNull()?.trackId == id) buffer.removeFirst() else null
            }

            // Every stored row left, at the end of the library.
            fun rest(): List<Row> {
                val out = ArrayList<Row>()
                while (true) {
                    fill()
                    if (buffer.isEmpty()) return out
                    out += buffer.removeFirst()
                }
            }
        }
    }

    // Copies a pinned generation into `into`, a page a transaction in its
    // order; the copy's swap count is the one its songs were read at.
    private suspend fun copyGen(source: String, gen: Long, into: String): LiveCopy {
        val swapCount = db.readLong("SELECT swapCount FROM live_state WHERE owner = ?", source)
        needs(roomFor(db.readLong("SELECT count FROM live_state WHERE owner = ?", source)))
        deleteRows(into, null)
        var after = ByteArray(0)
        var count = 0
        var duration = 0L
        while (true) {
            val rows = db.read(
                "SELECT orderKey, trackId, durationMs FROM live_song WHERE owner = ? AND gen = ? AND orderKey > ? ORDER BY orderKey LIMIT ?",
                source, gen, after, page,
            ) { Row(it.getBlob(0), it.getString(1), it.getLong(2)) }
            if (rows.isNotEmpty()) {
                withContext(WriteLabels.of("live list copy")) {
                    db.withTransaction {
                        rows.forEach { db.write("INSERT INTO live_song (owner, gen, orderKey, trackId, durationMs) VALUES (?, 1, ?, ?, ?)", into, it.orderKey, it.trackId, it.durationMs) }
                    }
                }
                count += rows.size
                duration += rows.sumOf { it.durationMs }
            }
            if (rows.size < page) break
            after = rows.last().orderKey
        }
        db.write(
            "INSERT OR REPLACE INTO live_state (owner, listId, gen, queryHash, count, durationMs, usedAt, swapCount) VALUES (?, NULL, 1, '', ?, ?, ?, ?)",
            into, count, duration, used.incrementAndGet(), swapCount,
        )
        return LiveCopy(swapCount, count)
    }

    private suspend fun releaseGen(owner: String, gen: Long) {
        if (owners.unpinGen(owner, gen)) withContext(NonCancellable) { deleteRows(owner, gen) }
    }

    // Lets saved lists nothing reads go, the least recently used first,
    // until at most LIVE_KEPT are kept (one fewer beside `keeping`, which a
    // reader is about to show).
    private suspend fun trim(keeping: String?) {
        val (from, until) = WorkOwners.kindRange(LIVE_KIND)
        val idle = db.read(LIVE_SAVED_SQL, from, until) { it.getString(0) }
            .filter { it != keeping && !owners.pinned(it) }
        idle.drop(if (keeping == null) LIVE_KEPT else LIVE_KEPT - 1).forEach { owner ->
            owners.clear(owner) {
                deleteRows(owner, null)
                db.write("DELETE FROM live_state WHERE owner = ?", owner)
            }
        }
    }

    // Deletes an owner's rows, or one generation's, in pages of 2,000.
    private suspend fun deleteRows(owner: String, gen: Long?) {
        while (true) {
            val gone = db.withTransaction {
                if (gen == null) {
                    db.write("DELETE FROM live_song WHERE rowid IN (SELECT rowid FROM live_song WHERE owner = ? LIMIT 2000)", owner)
                } else {
                    db.write("DELETE FROM live_song WHERE rowid IN (SELECT rowid FROM live_song WHERE owner = ? AND gen = ? LIMIT 2000)", owner, gen)
                }
            }
            if (gone < 2000) return
        }
    }

    // A generation number past every one the owner has, published or not.
    private fun nextGen(owner: String): Long =
        maxOf(db.readLong("SELECT MAX(gen) FROM live_song WHERE owner = ?", owner), db.readLong("SELECT gen FROM live_state WHERE owner = ?", owner)) + 1

    private fun state(owner: String): LiveStateRow? =
        db.read("SELECT listId, gen, queryHash, count, durationMs FROM live_state WHERE owner = ?", owner) {
            LiveStateRow(it.stringOrNull(0), it.getLong(1), it.getString(2), it.getInt(3), it.getLong(4))
        }.firstOrNull()

    private fun needs(bytes: Long) {
        if (freeSpace.bytes() < bytes) throw NotEnoughSpace(bytes, SpaceFor.List)
    }

    // The room `rows` picked songs take as they are written a page a
    // transaction: their rows, and the log's room beside them.
    private fun roomFor(rows: Long): Long {
        val work = rows * LIVE_BYTES_PER_ROW
        return work + logRoom(work, minOf(rows, page.toLong()) * LIVE_BYTES_PER_ROW)
    }

    // A song as the list stores it.
    private class Row(val orderKey: ByteArray, val trackId: String, val durationMs: Long) {
        fun sameAs(other: Row) = trackId == other.trackId && durationMs == other.durationMs && orderKey.contentEquals(other.orderKey)
    }

    // What live_state holds of an owner's list.
    private class LiveStateRow(val listId: String?, val gen: Long, val queryHash: String, val count: Int, val durationMs: Long)

    // The fields a page's songs are tested and ordered by, the page's likes
    // and plays swapped in as each page is read.
    private class PageFields : SongFields<TrackEntity> {
        var page = TrackFields()
        override fun id(song: TrackEntity) = page.id(song)
        override fun title(song: TrackEntity) = page.title(song)
        override fun artist(song: TrackEntity) = page.artist(song)
        override fun album(song: TrackEntity) = page.album(song)
        override fun albumId(song: TrackEntity) = page.albumId(song)
        override fun albumArtist(song: TrackEntity) = page.albumArtist(song)
        override fun genres(song: TrackEntity) = page.genres(song)
        override fun composer(song: TrackEntity) = page.composer(song)
        override fun year(song: TrackEntity) = page.year(song)
        override fun disc(song: TrackEntity) = page.disc(song)
        override fun track(song: TrackEntity) = page.track(song)
        override fun addedAt(song: TrackEntity) = page.addedAt(song)
        override fun lastPlayedAt(song: TrackEntity) = page.lastPlayedAt(song)
        override fun plays(song: TrackEntity) = page.plays(song)
        override fun rating(song: TrackEntity) = page.rating(song)
        override fun favourite(song: TrackEntity) = page.favourite(song)
        override fun likedAt(song: TrackEntity) = page.likedAt(song)
        override fun seconds(song: TrackEntity) = page.seconds(song)
        override fun format(song: TrackEntity) = page.format(song)
        override fun lossless(song: TrackEntity) = page.lossless(song)
        override fun bitRate(song: TrackEntity) = page.bitRate(song)
        override fun bpm(song: TrackEntity) = page.bpm(song)
    }
}

// Two keys as the database orders BLOBs: byte by byte, unsigned, a shorter
// prefix first.
internal fun unsigned(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}

// Whether a list's rules or order read play counts.
internal fun readsPlays(query: LibraryQuery): Boolean =
    query.rules.any { it.field == QueryField.Plays || it.field == QueryField.LastPlayed } ||
        query.sort?.order()?.by.let { it == SongSort.MostPlayed || it == SongSort.RecentlyPlayed }

// A question's own name: the same for the same rules, words, order and limit.
internal fun hashOf(query: LibraryQuery): String =
    MessageDigest.getInstance("SHA-256").digest(query.toJson().toByteArray()).joinToString("") { "%02x".format(it) }

// A live list's songs in its order, a page at a time, each followed to the
// song it is now; one gone from the library is left out, and a song read
// twice once. For a copy of a list made into a playlist.
class LiveRows internal constructor(private val db: CatalogDatabase, private val owner: String) {
    // A page after `after` (an order key; null from the top), and the key
    // the next page starts after, null at the end.
    suspend fun page(after: ByteArray?, limit: Int = LIVE_PAGE): Pair<List<String>, ByteArray?> = withContext(Dispatchers.IO) {
        val rows = db.read(
            "SELECT orderKey, trackId FROM live_song WHERE owner = ? AND gen = (SELECT gen FROM live_state WHERE owner = ?) " +
                "AND orderKey > ? ORDER BY orderKey LIMIT ?",
            owner, owner, after ?: ByteArray(0), limit,
        ) { it.getBlob(0) to it.getString(1) }
        val now = db.followAll(rows.map { it.second })
        val ids = LinkedHashSet<String>()
        rows.forEach { (_, id) -> now[id]?.let(ids::add) }
        ids.toList() to rows.lastOrNull()?.first?.takeIf { rows.size == limit }
    }
}
