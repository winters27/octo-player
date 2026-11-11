package app.winters.octo.playback

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.QUEUE_BUILD_INSERT_SQL
import app.winters.octo.catalog.QUEUE_BUILD_UPDATE_SQL
import app.winters.octo.catalog.QUEUE_HOLDS_SONG_SQL
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.QueueListEntity
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.megabytes
import app.winters.octo.catalog.queueQuery
import app.winters.octo.data.spaceWords
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.PlayTotals
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.livelists.LivePicks
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.Retiring
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.readsUserData
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

// Bytes one queue entry takes on disk with its indexes, measured by the
// file's pages on a play of 20,000 songs whose ids are as long as a real
// server's (294).
const val QUEUE_BYTES_PER_ENTRY = 310L

// A build checks free space before its first page and every this many.
private const val SPACE_CHECK_PAGES = 20

// A list whose rows are all written, ready for the window: its generation,
// whether its rows were written with a library older than the library now
// (so the window treats them as healed only after the heal), and words to
// show when it could not add every song.
data class Joined(val queueId: Long, val listId: Long, val words: String? = null)

// Points a test can stop a build at. Each does nothing in the app.
internal open class BuildHooks {
    // A page's rows are read and its transaction is about to begin.
    open suspend fun beforePage(listId: Long, page: Int) {}

    // A page committed.
    open suspend fun afterPage(listId: Long, page: Int) {}

    // The build holds the merge lock for its last attempt.
    open suspend fun inLastAttempt(listId: Long) {}

    // The build ended, joined or stopped, and lets its copy and pin go.
    open suspend fun releasing(listId: Long) {}
}

// Writes the rows of lists too big to play whole, each by its own builder
// off the queue's worker, a page at a time, then joins it to its queue in
// one worker job. A list over live library tables compares the library's
// swap count inside each page's transaction and starts over by number when
// a swap came, at most three times, the third holding the merge lock for
// its whole walk. A list over a copy of its own, or over a pinned playlist
// set, walks once and follows each song to the library song it is now as
// it writes it.
@Singleton
class QueueBuilds @Inject constructor(
    private val store: QueueStore,
    private val loaders: Lazy<ListLoaders>,
    private val live: Lazy<LivePicks>,
    private val health: Lazy<HealthBuild>,
    private val merge: CatalogMerge,
    private val totals: PlayTotals,
    private val freeSpace: FreeSpace,
    private val owners: WorkOwners,
    private val radio: Lazy<LibraryRadio>,
) : QueueFill {
    private val db = store.db
    private val dao = store.dao
    internal var buildHooks = BuildHooks()
    override var hooks = FillHooks()

    // Rows a page writes; tests make it small.
    internal var pageSize = QUEUE_PAGE

    // Draws a radio's songs after its seed; tests hand in their own.
    internal var pool: (suspend (List<TrackEntity>, Int, suspend (List<String>) -> Set<String>, List<String>) -> List<String>)? = null

    private val _joins = MutableSharedFlow<Joined>(extraBufferCapacity = 64)
    override val joins: SharedFlow<Joined> = _joins

    // Words a radio's pool leaves for the listener while its seed plays.
    private val _radioWords = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val radioWords: SharedFlow<String> = _radioWords

    // A play's start entry takes the middle play key; a shuffled play's
    // comes before every hashed key.
    override fun startKeys(seed: Long, firstShuffled: Boolean): Pair<Long, Long> = KEY_MID to playShuffledKey(0, seed, firstShuffled)

    override suspend fun page(queueId: Long, order: QueueOrder, from: QueueEntryEntity?, forward: Boolean, limit: Int, floors: Floors): List<QueueEntryEntity> =
        store.page(queueId, order, from, forward, limit, floors)

    override fun blocks(listId: Long, play: Boolean, row: QueueEntryEntity): Boolean =
        listOfEntry(row.entry) == listId && !(play && numberOfEntry(row.entry) == 0L)

    // Starts `listId`'s builder once `opened` (its opening job) is done,
    // unless one already runs for it.
    override fun start(listId: Long, queueId: Long, opened: CompletableDeferred<Unit>?): Job =
        store.startBuilder(listId, queueId) {
            store.scope.launch(WriteLabels.of(QUEUE_BUILD_WRITE), start = CoroutineStart.LAZY) {
                opened?.await()
                build(listId)
            }.also { job ->
                job.invokeOnCompletion { stopped(listId, queueId, job) }
                job.start()
            }
        }

    private fun stopped(listId: Long, queueId: Long, job: Job) {
        if (store.builderStopped(listId, job)) store.deleteGeneration(queueId)
    }

    // One list's build, from where it stands.
    private suspend fun build(listId: Long) {
        val list = dao.list(listId) ?: return
        if (list.fillState != BUILDING) return
        val spec = list.spec?.let(ListSpec::fromJson) ?: return
        val owner = owners.queue(listId)
        var loader: ListLoader<Any>? = null
        var words: String? = null
        try {
            if (spec is ListSpec.Radio) {
                words = radioPool(list, spec)
            } else {
                val before = store.swapCount()
                val opened = open(spec, owner)
                loader = opened.loader
                dao.setSongsOnce(listId, opened.loader.songsOnce)
                if (opened.frozen) dao.setBaseline(listId, opened.baseline ?: before)
                if (opened.copied) dao.setCopyOwner(listId, owner)
                words = walk(listId, opened)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { release(listId, owner, loader) }
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "queue: build of $listId failed: ${e.javaClass.simpleName}", e)
            words = withContext(NonCancellable) { ended(listId, e) }
        }
        withContext(NonCancellable) {
            release(listId, owner, loader)
            join(listId, words)
        }
    }

    // What a build walks: its loader, whether it is frozen (a copy or a
    // pinned set: one attempt, no compares) or live, and for a copy the
    // swap count its data dates from.
    private class Opened(val loader: ListLoader<Any>, val frozen: Boolean, val copied: Boolean, val baseline: Long?, val listening: Boolean)

    @Suppress("UNCHECKED_CAST")
    private suspend fun open(spec: ListSpec, owner: String): Opened = when (spec) {
        is ListSpec.Live -> {
            val loader = live.get().forSpec(spec.listId, spec.query, owner)
            val made = loader.copy()
            Opened(loader as ListLoader<Any>, frozen = true, copied = true, baseline = made.swapCount, listening = false)
        }
        is ListSpec.Health -> {
            val loader = health.get().loader(spec.check, owner)
            val made = loader.copy()
            val frozen = spec.check != HealthCheck.NoLength
            Opened(loader as ListLoader<Any>, frozen, copied = frozen, baseline = made.swapCount.takeIf { frozen }, listening = false)
        }
        is ListSpec.AlbumList -> Opened(AlbumListLoader(db, spec.albumIds) as ListLoader<Any>, frozen = false, copied = false, baseline = null, listening = false)
        else -> {
            val loader = loaders.get().forSpec(spec, owner) as? ListLoader<Any> ?: error("no loader for ${spec.javaClass.simpleName}")
            val frozen = frozen(spec)
            // A list the server answers for dates from its copy's read,
            // which the play's start was read from too.
            val baseline = if (spec.fromServer) loaders.get().copyStamp(owner) else null
            Opened(loader, frozen, copied = frozen, baseline = baseline, listening = listening(spec))
        }
    }

    // Whether a list's loader walks a copy made for the build or a pinned
    // set, rather than the library's tables as they change.
    private fun frozen(spec: ListSpec): Boolean = when (spec) {
        is ListSpec.Songs -> readsUserData(spec.scope, spec.order)
        is ListSpec.Album -> spec.withFinds
        is ListSpec.Folder, is ListSpec.Downloads, is ListSpec.Playlist, is ListSpec.Live -> true
        is ListSpec.Health -> spec.check != HealthCheck.NoLength
        is ListSpec.Online, is ListSpec.ServerFolder, ListSpec.ServerQueue -> true
        is ListSpec.Selection -> frozen(spec.base)
        else -> false
    }

    private fun listening(spec: ListSpec): Boolean = when (spec) {
        is ListSpec.Songs -> spec.order.by.byListening && !readsUserData(spec.scope, spec.order)
        is ListSpec.Groups -> spec.order.by.byListening
        is ListSpec.Selection -> listening(spec.base)
        else -> false
    }

    // Walks the list in its phases until it is all written, starting over
    // when a swap ended a live attempt. Answers words for the listener when
    // the walk stopped short.
    private suspend fun walk(listId: Long, opened: Opened): String? {
        var list = dao.list(listId) ?: return null
        if (list.phase == null) list = mapAnchor(list, opened.loader)
        if (list.expected == null) dao.setExpected(listId, opened.loader.count().toLong())
        while (true) {
            list = dao.list(listId) ?: return null
            val done = if (opened.frozen) {
                attempt(list, opened)
            } else if (list.attempts < 2) {
                merge.swapStamp.first { it % 2 == 0L }
                if (opened.listening) totals.awaitBuilt()
                if (opened.listening) totals.withReadHold { attempt(list, opened) } else attempt(list, opened)
            } else {
                if (opened.listening) totals.awaitBuilt()
                val inside: suspend () -> Boolean = {
                    merge.betweenRebuilds(HoldKind.Walk) {
                        buildHooks.inLastAttempt(listId)
                        attempt(list, opened)
                    }
                }
                if (opened.listening) totals.withReadHold(inside) else inside()
            }
            if (done) return null
        }
    }

    // The start's row in the build's own keys, once its copy or pin is
    // made: the tapped row's key mapped into them, else the first row of
    // the start's song; none means the whole list comes after the start.
    private suspend fun mapAnchor(list: QueueListEntity, loader: ListLoader<Any>): QueueListEntity {
        val start = dao.entry(list.queueId, entryOf(list.listId, 0))
        val tapped = list.anchor?.let(RowKey::of)
        val anchor = when {
            list.qStep != null -> null
            tapped != null -> loader.rekey(tapped) ?: start?.let { loader.keyOf(it.trackId) }
            start != null && list.cursor != NO_ANCHOR -> loader.keyOf(start.trackId)
            else -> null
        }
        db.withTransaction {
            dao.setAnchor(list.listId, anchor?.token)
            dao.progress(list.listId, 0, anchor?.token, 0, 0, null)
        }
        return dao.list(list.listId)!!
    }

    // One attempt from where the list stands: answers true once every row
    // is written, false when a swap made it start over.
    private suspend fun attempt(start: QueueListEntity, opened: Opened): Boolean {
        val loader = opened.loader
        val listId = start.listId
        val queueId = start.queueId
        val play = start.qStep == null
        val anchor = start.anchor?.let(RowKey::of)
        val startSong = if (play && loader.songsOnce) dao.entry(queueId, entryOf(listId, 0))?.trackId?.let { store.followed(it) } else null
        val first = firstEntryOf(listId)
        var phase = start.phase ?: 0
        var cursor = start.cursor?.takeIf { it != NO_ANCHOR }?.let(RowKey::of)
        var filled0 = start.filled0
        var filled1 = start.filled1
        var phase0End = start.phase0End
        var pages = 0
        // A resume in phase 1 finishes the deletes the move to it began.
        if (play && phase == 1) dropFrom(queueId, first, (phase0End ?: filled0) + 1, PHASE1_BASE)
        while (true) {
            coroutineContext.ensureActive()
            if (pages % SPACE_CHECK_PAGES == 0) checkSpace(start.expected, filled0 + filled1)
            val read = loader.after(cursor, pageSize)
            val rows = if (play && phase == 1 && anchor != null) read.takeWhile { loader.order.compare(it.key, anchor) < 0 } else read
            val ended = read.size < pageSize || rows.size < read.size
            buildHooks.beforePage(listId, pages)
            // A frozen copy's songs are followed to the library songs they
            // are now; one gone for good is left out.
            val ids = rows.map { row -> songOf(loader, row.row)?.let { if (opened.frozen) store.followedOrGone(it) else it } }
            val lengths = lengthsOf(ids.filterNotNull())
            val movesOn = ended && play && phase == 0
            val written = withContext(WriteLabels.of("queue build page")) {
                db.withTransaction {
                    if (!opened.frozen && store.swapCount() != start.swapCount) {
                        dao.restart(listId, store.swapCount())
                        return@withTransaction false
                    }
                    val sql = db.openHelper.writableDatabase
                    val update = sql.compileStatement(QUEUE_BUILD_UPDATE_SQL)
                    val insert = sql.compileStatement(QUEUE_BUILD_INSERT_SQL)
                    rows.forEachIndexed { i, _ ->
                        val song = ids[i] ?: return@forEachIndexed
                        if (startSong != null && song == startSong) return@forEachIndexed
                        if (loader.songsOnce && holds(queueId, first, song, play, phase, filled0, filled1, phase0End)) return@forEachIndexed
                        val n = when {
                            !play -> filled0
                            phase == 0 -> filled0 + 1
                            else -> PHASE1_BASE + filled1
                        }
                        // Past a phase's room the list plays its first that many.
                        if ((phase == 0 && n >= PHASE1_BASE) || (phase == 1 && filled1 >= PHASE1_BASE)) return@forEachIndexed
                        val qk = if (play) playKey(n) else start.qBase!! + (n + 1) * start.qStep!!
                        val sk = if (play) shuffledKey(start.seed, qk) else start.sBase!! + (n + 1) * start.sStep!!
                        val args = arrayOf<Any?>(queueId, first + n, song, listId, qk, sk, lengths[song] ?: 0L)
                        bindAll(update, args)
                        update.executeUpdateDelete()
                        bindAll(insert, args)
                        insert.executeInsert()
                        if (phase == 0) filled0++ else filled1++
                    }
                    cursor = rows.lastOrNull()?.key ?: cursor
                    if (movesOn) {
                        // Phase 0 is done: its last number is kept, so a
                        // resume numbers phase 1 on and clears past it.
                        phase = 1
                        phase0End = filled0
                        cursor = null
                    }
                    dao.progress(listId, phase, cursor?.token, filled0, filled1, phase0End)
                    true
                }
            }
            if (!written) return false
            buildHooks.afterPage(listId, pages)
            pages++
            if (movesOn) {
                // What an earlier attempt left past phase 0's new end goes
                // before phase 1's first page.
                dropFrom(queueId, first, filled0 + 1, PHASE1_BASE)
                if (anchor == null) break
                continue
            }
            if (ended) break
        }
        // What an earlier attempt left past this one's end goes.
        if (play) dropFrom(queueId, first, PHASE1_BASE + filled1, PHASE1_BASE shl 1) else dropFrom(queueId, first, filled0, PHASE1_BASE)
        return true
    }

    // Whether the list already holds `song` among the numbers this attempt
    // wrote: the start entry, phase 0's so far (or all of it, in phase 1),
    // phase 1's so far.
    private suspend fun holds(queueId: Long, first: Long, song: String, play: Boolean, phase: Int, filled0: Long, filled1: Long, phase0End: Long?): Boolean {
        val hi0 = when {
            !play -> filled0 - 1
            phase == 0 -> filled0
            else -> phase0End ?: filled0
        }
        val (lo1, hi1) = if (play && phase == 1) PHASE1_BASE to PHASE1_BASE + filled1 - 1 else 1L to 0L
        val n = dao.number(queueQuery(QUEUE_HOLDS_SONG_SQL, song, queueId, first, first + hi0, first + lo1, first + hi1)) ?: 0L
        return n > 0
    }

    // Deletes the list's rows numbered from `from` up to before `until`, a
    // page at a time.
    private suspend fun dropFrom(queueId: Long, first: Long, from: Long, until: Long) {
        if (from >= until) return
        var after = first + from - 1
        val last = first + until - 1
        while (true) {
            after = store.deletePage(queueId, after, last) ?: return
        }
    }

    private fun checkSpace(expected: Long?, filled: Long) {
        val need = ((expected ?: 0) - filled).coerceAtLeast(0) * QUEUE_BYTES_PER_ENTRY
        if (need > 0 && freeSpace.bytes() < need) throw NotEnoughSpace(need, SpaceFor.Queue)
    }

    // A build that failed keeps the rows it wrote: whatever an earlier
    // attempt left past them goes, and the list joins with the words.
    private suspend fun ended(listId: Long, e: Exception): String {
        val list = dao.list(listId)
        if (list != null) {
            val first = firstEntryOf(listId)
            runCatching {
                if (list.qStep == null) {
                    val end0 = if (list.phase == 1) list.phase0End ?: list.filled0 else list.filled0
                    dropFrom(list.queueId, first, end0 + 1, PHASE1_BASE)
                    dropFrom(list.queueId, first, PHASE1_BASE + (if (list.phase == 1) list.filled1 else 0), PHASE1_BASE shl 1)
                } else {
                    dropFrom(list.queueId, first, list.filled0, PHASE1_BASE)
                }
            }
        }
        val got = list?.let { it.filled0 + (if (it.phase == 1) it.filled1 else 0) + if (it.qStep == null) 1 else 0 } ?: 0
        return when (e) {
            is NotEnoughSpace -> spaceWords(SpaceFor.Queue, e.neededBytes)
            is SQLiteFullException -> spaceWords(SpaceFor.Queue, null)
            else -> list?.expected?.takeIf { it > 0 }?.let { "Couldn't add every song to the queue. ${"%,d".format(got)} of ${"%,d".format(it)} are in it." }
                ?: "Couldn't add the rest of the list to the queue."
        }
    }

    // Lets the build's copy and pin go: every row its owner holds in any
    // work table.
    private suspend fun release(listId: Long, owner: String, loader: ListLoader<Any>?) {
        runCatching { buildHooks.releasing(listId) }
        runCatching { (loader as? Retiring)?.retire() }
        runCatching { owners.clear(owner) { loaders.get().releaseOwned(owner) } }
        runCatching { dao.setCopyOwner(listId, null) }
    }

    // The list joins its queue: one worker job marks it joined and applies
    // the edits that waited for it.
    private fun join(listId: Long, words: String?) {
        store.post {
            val list = dao.list(listId) ?: return@post
            if (list.fillState != BUILDING) return@post
            val apply = QueueApply(store)
            val waiting = ArrayList<Long>()
            db.withTransaction {
                dao.joined(listId)
                if (list.expected == null || words != null) dao.setExpected(listId, list.filled0 + list.filled1)
                // The edits that waited for this list, in the order they came.
                for (row in dao.pending(listId)) {
                    when (val edit = queueEditOf(row.body)) {
                        is QueueEdit.AddList -> if (place(list.queueId, edit)) waiting += edit.listId
                        else -> apply.apply(list.queueId, edit)
                    }
                }
                dao.deletePending(listId)
            }
            val rows = list.filled0 + list.filled1 + if (list.qStep == null) 1 else 0
            hooks.filled(listId, rows, rows * QUEUE_BYTES_PER_ENTRY)
            // Rows written from data older than the library now follow it.
            val count = store.swapCount()
            if (list.swapCount != null && list.swapCount != count) {
                store.healRange(list.queueId, firstEntryOf(listId) - 1, lastEntryOf(listId), count, emptyList(), listId)
            }
            waiting.forEach { start(it, list.queueId, null) }
            _joins.emit(Joined(list.queueId, listId, words))
        }
    }

    // A big add that waited for another list gets its keys where its place
    // is now, and builds.
    private suspend fun place(queueId: Long, edit: QueueEdit.AddList): Boolean {
        val list = dao.list(edit.listId) ?: return false
        if (list.fillState != WAITING) return false
        val (qBase, qStep) = store.addKeys(queueId, QueueOrder.Play, edit.play)
        val (sBase, sStep) = store.addKeys(queueId, QueueOrder.Shuffle, edit.shuffle)
        dao.placed(edit.listId, BUILDING, qBase, qStep, sBase, sStep)
        return true
    }

    // A radio's songs after its seed: the pool, drawn off the tap's path,
    // joined as one list of at most RADIO_LENGTH songs after the seed.
    private suspend fun radioPool(list: QueueListEntity, spec: ListSpec.Radio): String? {
        val seedRow = dao.entry(list.queueId, entryOf(list.listId, 0)) ?: return null
        val seeds = radioSeeds(seedRow.trackId, spec.around)
        if (seeds.isEmpty()) return null
        val excluded: suspend (List<String>) -> Set<String> = when (val around = spec.around) {
            is RadioAround.Album -> { ids -> albumMembers(around.albumId, ids) }
            else -> { _ -> emptySet() }
        }
        val picks = try {
            val draw = pool ?: { s, c, e, b -> radio.get().songsLike(s, c, e, b) }
            draw(seeds, RADIO_LENGTH, excluded, listOf(seedRow.trackId))
        } catch (e: TotalsFailed) {
            return e.words
        }
        if (picks.isEmpty()) return "No similar songs found"
        val lengths = lengthsOf(picks)
        val rows = picks.filter { it != seedRow.trackId }.distinct().mapIndexed { i, id ->
            val qk = playKey(i + 1L)
            QueueEntryEntity(list.queueId, entryOf(list.listId, i + 1L), store.followed(id), list.listId, qk, shuffledKey(list.seed, qk), lengths[id] ?: 0)
        }
        db.withTransaction {
            rows.chunked(QUEUE_PAGE).forEach { dao.putEntries(it) }
            dao.progress(list.listId, 1, null, rows.size.toLong(), 0, rows.size.toLong())
            // Its songs are drawn once each.
            dao.setSongsOnce(list.listId, true)
        }
        return null
    }

    // The songs a radio draws its features from: its seed and, for an
    // album's or an artist's radio, their first songs, at most a page.
    private suspend fun radioSeeds(seedId: String, around: RadioAround?): List<TrackEntity> {
        val seed = db.dao().track(seedId) ?: return emptyList()
        val more = when (around) {
            is RadioAround.Album -> dao.albumFirst(around.albumId, RADIO_SEEDS)
            is RadioAround.Artist -> dao.artistFirst(around.artistId, RADIO_SEEDS)
            null -> emptyList()
        }
        return (listOf(seed) + more).distinctBy { it.id }
    }

    // Which of these songs are on an album, a chunk at a time.
    private suspend fun albumMembers(albumId: String, ids: List<String>): Set<String> =
        ids.chunked(900).flatMapTo(HashSet()) { dao.onAlbum(albumId, it) }

    // The listed lengths of a page's songs, library and found ones.
    private suspend fun lengthsOf(ids: List<String>): Map<String, Long> = songLengths(store, ids)

    private fun bindAll(statement: androidx.sqlite.db.SupportSQLiteStatement, args: Array<Any?>) {
        statement.clearBindings()
        args.forEachIndexed { i, v ->
            when (v) {
                null -> statement.bindNull(i + 1)
                is Long -> statement.bindLong(i + 1, v)
                is String -> statement.bindString(i + 1, v)
                else -> error("unexpected ${v.javaClass}")
            }
        }
    }

    companion object {
        const val JOINED = 0
        const val BUILDING = 1
        const val WAITING = 2

        // A list whose start's row is not to be found again in its build:
        // the whole list comes after the start.
        const val NO_ANCHOR = "none"

        // How many songs of an album or artist a radio takes its features from.
        const val RADIO_SEEDS = 200
    }
}

// The song a loader's row holds.
fun songOf(loader: ListLoader<Any>, row: Any): String? = loader.trackIdOf(row) ?: row as? String
