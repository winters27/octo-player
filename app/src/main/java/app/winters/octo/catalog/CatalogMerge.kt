package app.winters.octo.catalog

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.userMessage
import app.winters.octo.device.DEVICE
import app.winters.octo.server.isServerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// How big the library is after a merge.
data class MergeCounts(val tracks: Int, val albums: Int, val artists: Int)

// A copy of a source's library that was not swapped in, and why, in words.
class StageAborted(val reason: String) : Exception(reason)

// The space a swap takes per song, measured (SwapSpaceTest), and asked for
// twice over before a copy is swapped in.
internal const val SWAP_BYTES_PER_SONG = 1_400L

// Points a test can stop a merge at. Each does nothing in the app.
internal open class MergeHooks {
    // After the claim, holding the source-write gate.
    open suspend fun claimed() {}

    // After the first page of songs is placed, outside any transaction.
    open suspend fun firstOutputWritten() {}

    // Inside the swap's transaction, before it writes.
    open suspend fun inSwap() {}

    open fun merged() {}

    open fun skipped() {}
}

// Rebuilds the library the screens read from what the sources have, after
// any source changes. One merge at a time, the phone's files first so its
// songs keep their ids.
//
// Every kept server's music is kept (see ServerSync), but only the server
// in use joins the library, so two servers' copies of a song never become
// one row. The other servers' copies are no library song's meanwhile: they
// lose their merged id, so nothing that looks up a song's copies (playing,
// stars, plays, downloads) finds them.
//
// Asking for a merge waits for one that starts after the ask; asks that
// come while one runs share the next. A merge whose inputs (which sources,
// each one's stamp, any copy waiting to be swapped in, the rules) are the
// ones last merged is skipped. A server copy built beside the live rows
// (stage_*) is claimed by the next merge, which reads it in place of the
// live rows and swaps both in together.
//
// Locks, always taken in this order: a writer's own lock (scan, take-in,
// drop), then the source-write gate, then a database transaction. The
// merge holds the gate from its claim to the end of its swap, and the
// between-rebuilds lock only for the swap. The gate is never taken inside a
// transaction or a betweenRebuilds block.
@Singleton
class CatalogMerge internal constructor(
    private val db: CatalogDatabase,
    private val freeSpace: FreeSpace,
    private val serverInUse: suspend () -> String?,
    private val work: WorkDao = db.workDao(),
) {
    @Inject constructor(db: CatalogDatabase, sessions: SessionRepository, freeSpace: FreeSpace) : this(
        db,
        freeSpace,
        { (sessions.state.first { it !is SessionState.Loading } as? SessionState.SignedIn)?.session?.sourceId },
    )

    private val sources = db.sourceDao()
    private val user = db.userDao()
    private val favourites = db.favouritesDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Held by the swap and by betweenRebuilds.
    private val lock = Mutex()

    // The source-write gate: held by a merge from its claim to its swap,
    // and by every write to the sources' rows.
    private val gate = Mutex()

    internal var hooks = MergeHooks()
    private val merger = CatalogMerger(db, work) { hooks }

    private val pending = ArrayList<CompletableDeferred<MergeCounts>>()
    private var running = false

    // How each copy waiting to be swapped in ended, by source and generation.
    private val stages = HashMap<Pair<String, Long>, CompletableDeferred<Unit>>()

    // Asks for a merge and waits for it. Answers how big the library is.
    suspend fun rebuild(): MergeCounts {
        val done = CompletableDeferred<MergeCounts>()
        val start = synchronized(pending) {
            pending += done
            if (running) false else true.also { running = true }
        }
        if (start) scope.launch { runMerges() }
        return done.await()
    }

    // The server the library was last merged for ("" for none), as the
    // merge itself wrote it with its result; null before any merge did.
    suspend fun mergedFor(): String? = work.mergedServer()

    // Runs `block` between rebuilds, never during a swap, so it sees song
    // ids and everything that follows them already in step.
    suspend fun <T> betweenRebuilds(block: suspend () -> T): T = lock.withLock { block() }

    // Runs a write to the sources' rows while no merge holds a claim, so a
    // merge never reads half of one.
    suspend fun <T> sourceWrite(block: suspend () -> T): T = gate.withLock { block() }

    // Waits until the copy of `sourceId` built as `generation` is swapped
    // in, or throws StageAborted when it was not.
    suspend fun awaitStage(sourceId: String, generation: Long) {
        try {
            stageOutcome(sourceId, generation).await()
        } finally {
            forgetStage(sourceId, generation)
        }
    }

    // The copy is done with its outcome, however it ended.
    fun forgetStage(sourceId: String, generation: Long) {
        synchronized(stages) { stages.remove(sourceId to generation) }
    }

    // A new copy of the source starts: outcomes of its earlier ones that no
    // copy waits for any more (one cancelled while its swap ran) go.
    fun forgetStagesBefore(sourceId: String, generation: Long) {
        synchronized(stages) { stages.keys.removeAll { it.first == sourceId && it.second < generation } }
    }

    // At launch: a source row no merge has placed (the app stopped between
    // a write and its merge) gets its merge now.
    suspend fun repair() {
        val server = serverInUse()
        val broken = liveSources().filter { inLibrary(it, server) }.any { work.hasUnmerged(it) }
        if (!broken) return
        work.setSkipKey(null)
        try {
            rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "library repair failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun runMerges() {
        while (true) {
            val batch = synchronized(pending) {
                if (pending.isEmpty()) {
                    running = false
                    return
                }
                pending.toList().also { pending.clear() }
            }
            try {
                val counts = mergeOnce()
                batch.forEach { it.complete(counts) }
            } catch (e: CancellationException) {
                batch.forEach { it.cancel(e) }
                synchronized(pending) { running = false }
                throw e
            } catch (e: Exception) {
                batch.forEach { it.completeExceptionally(e) }
            }
        }
    }

    private suspend fun mergeOnce(): MergeCounts {
        while (true) {
            refillKeys()
            val server = serverInUse()
            var again = false
            val counts = gate.withLock {
                val step = decide(server)
                failStages(step.dropped, ELSEWHERE)
                failStages(step.leftover, CHANGED)
                val claim = step.claim ?: return@withLock null
                hooks.claimed()
                try {
                    val counts = merger.merge(claim.sources)
                    checkSpace(claim, counts)
                    swap(claim)
                    claim.claimed.forEach { stageOutcome(it.sourceId, it.generation).complete(Unit) }
                    hooks.merged()
                    counts
                } catch (e: StageAbort) {
                    abort(claim, e.reason)
                    again = true
                    null
                } catch (e: SQLiteFullException) {
                    if (claim.claimed.isEmpty()) {
                        abort(claim, e.userMessage())
                        throw e
                    }
                    abort(claim, NotEnoughSpace(neededFor(claim, 0)).userMessage())
                    again = true
                    null
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { abort(claim, e.userMessage()) }
                    throw e
                } catch (e: Exception) {
                    abort(claim, e.userMessage())
                    throw e
                } finally {
                    withContext(NonCancellable) { work.clearMergeWork() }
                }
            }
            if (again) continue
            return counts ?: skipped()
        }
    }

    // Nothing changed since the last merge: the library stays, and rows
    // written meanwhile with an id a merge took away still follow it.
    private suspend fun skipped(): MergeCounts {
        lock.withLock {
            user.relinkAll()
            favourites.relinkAll()
            user.showPhoneRatings()
        }
        hooks.skipped()
        return MergeCounts(work.trackTotal(), work.albumTotal(), work.artistTotal())
    }

    // A source's copy that waits to be swapped in, as claimed.
    private class ClaimedStage(val sourceId: String, val generation: Long, val tracks: Int)

    private class Claim(val sources: List<MergeSource>, val claimed: List<ClaimedStage>, val server: String)

    // What a merge does: nothing (claim null) or merge what it claimed.
    // Copies of sources out of the library are dropped either way, and so
    // are copies a failed abort left claimed.
    private class Step(val claim: Claim?, val dropped: List<ClaimedStage>, val leftover: List<ClaimedStage>)

    private class StageAbort(val reason: String) : Exception(reason)

    // Under the gate: skips when nothing changed, otherwise claims every
    // copy waiting for a source in the library, adding in what was taken in
    // while it was built, and starts noting rating taps.
    private suspend fun decide(server: String?): Step = db.withTransaction {
        // A claim left by a merge whose abort could not write (a full disk)
        // is over: under the gate no merge holds one.
        val leftover = work.metas().filter { it.stage == STAGE_CLAIMED }.map { ClaimedStage(it.sourceId, it.generation, 0) }
        leftover.forEach { dropStageRows(it) }
        if (leftover.isNotEmpty() || work.mergeState()?.claimActive == true) {
            work.setClaimActive(false)
            endRatingNotes()
        }
        val metas = work.metas().associateBy { it.sourceId }
        val ready = metas.values.filter { it.stage == STAGE_READY }
        val (inUse, elsewhere) = ready.partition { inLibrary(it.sourceId, server) }
        val dropped = elsewhere.map { ClaimedStage(it.sourceId, it.generation, 0) }
        dropped.forEach { dropStageRows(it) }
        val ids = (liveSources() + inUse.map { it.sourceId })
            .filter { inLibrary(it, server) }
            .distinct()
            .sortedBy { if (it == DEVICE) "" else it }
        val key = skipKey(ids, metas.mapValues { it.value.stamp }, inUse.associate { it.sourceId to it.generation })
        if (work.skipKey() == key) {
            // The same inputs make the same library, now this server's.
            work.setMergedServer(server.orEmpty())
            return@withTransaction Step(null, dropped, leftover)
        }
        val claimed = inUse.map { meta ->
            work.setStage(meta.sourceId, STAGE_CLAIMED)
            ClaimedStage(meta.sourceId, meta.generation, meta.stagedTracks + carryTakeIns(meta))
        }
        work.setClaimActive(true)
        val staged = claimed.mapTo(HashSet()) { it.sourceId }
        Step(Claim(ids.mapIndexed { rank, id -> MergeSource(id, rank, id in staged) }, claimed, server.orEmpty()), dropped, leftover)
    }

    // Songs, albums and artists taken in while a copy was built join it,
    // after its last rows. Answers how many songs joined.
    private suspend fun carryTakeIns(meta: SourceMetaEntity): Int {
        val source = meta.sourceId
        var trackSeq = work.lastStageTrackSeq(source) ?: -1
        val tracks = work.loggedIds(meta.generation, "track").chunked(MERGE_PAGE).sumOf { ids ->
            val rows = work.liveTracks(ids).map { StageTrackEntity(it.copy(seq = ++trackSeq, mergedId = "")) }
            work.insertStageTracks(rows).count { it != -1L }
        }
        var albumSeq = work.lastStageAlbumSeq(source) ?: -1
        val albums = work.loggedIds(meta.generation, "album").chunked(MERGE_PAGE).sumOf { ids ->
            val rows = work.liveAlbums(ids).map { StageAlbumEntity(it.copy(seq = ++albumSeq)) }
            work.insertStageAlbums(rows).count { it != -1L }
        }
        var artistSeq = work.lastStageArtistSeq(source) ?: -1
        val artists = work.loggedIds(meta.generation, "artist").chunked(MERGE_PAGE).sumOf { ids ->
            val rows = work.liveArtists(ids).map { StageArtistEntity(it.copy(seq = ++artistSeq)) }
            work.insertStageArtists(rows).count { it != -1L }
        }
        if (tracks + albums + artists > 0) work.addStaged(source, tracks, albums, artists)
        return tracks
    }

    private fun neededFor(claim: Claim, songs: Int): Long =
        (claim.claimed.sumOf { it.tracks.toLong() } + songs) * SWAP_BYTES_PER_SONG * 2

    private fun checkSpace(claim: Claim, counts: MergeCounts) {
        if (claim.claimed.isEmpty()) return
        val needed = neededFor(claim, counts.tracks)
        if (freeSpace.bytes() < needed) throw StageAbort(NotEnoughSpace(needed).userMessage())
    }

    // Puts the merge in place in one go: each claimed copy into its
    // source's rows, then the library, each copy's library song, and what
    // follows song ids. Only rows that differ are written.
    private suspend fun swap(claim: Claim) = lock.withLock {
        db.withTransaction {
            hooks.inSwap()
            val metas = work.metas().associateBy { it.sourceId }
            for (stage in claim.claimed) {
                val meta = metas[stage.sourceId]
                val intact = meta != null && meta.stage == STAGE_CLAIMED && meta.generation == stage.generation &&
                    work.stageTrackCount(stage.sourceId) == meta.stagedTracks &&
                    work.stageAlbumCount(stage.sourceId) == meta.stagedAlbums &&
                    work.stageArtistCount(stage.sourceId) == meta.stagedArtists
                if (!intact) throw StageAbort(CHANGED)
            }
            val sql = db.openHelper.writableDatabase
            val statements = swapSql(sql)
            for (stage in claim.claimed) {
                statements.applyStage.forEach { sql.execSQL(it, arrayOf<Any?>(stage.sourceId, stage.generation)) }
            }
            statements.applyLibrary.forEach { sql.execSQL(it) }
            for (source in claim.sources) sql.execSQL(statements.mergedIds, arrayOf(source.sourceId))
            sources.forgetMergedExcept(claim.sources.map { it.sourceId })
            // Likes, plays and playlists follow songs whose ids changed.
            user.relinkAll()
            // So do favourite albums and artists, and pins on Home.
            favourites.relinkAll()
            // Songs with no rating on a server show the one made on the phone.
            user.showPhoneRatings()
            // Ratings tapped while a copy was built or since the claim show
            // over what the copy or the merge read. The copies keep what the
            // server said: the steps after the copy settle the two.
            for (note in work.ratingNotes()) work.showCopyRating(note.sourceRowId, note.rating)
            for (stage in claim.claimed) {
                work.owedSteps(stage.sourceId)
                work.takeStageOwner(stage.sourceId)
                work.stageApplied(stage.sourceId)
                work.clearStage(stage.sourceId)
                work.clearTakeInLog(stage.generation)
            }
            if (claim.claimed.isNotEmpty()) work.forgetGoneTaps()
            work.setClaimActive(false)
            endRatingNotes()
            val stamps = work.metas().associate { it.sourceId to it.stamp }
            work.setSkipKey(skipKey(claim.sources.map { it.sourceId }, stamps, emptyMap()))
            work.setMergedServer(claim.server)
        }
    }

    // A claimed copy is not swapped in: it goes, and its copy is told why.
    // When the abort's own writes fail (a full disk), the copy is still told,
    // and the next merge clears the claim it left (decide).
    private suspend fun abort(claim: Claim, reason: String) {
        try {
            db.withTransaction {
                claim.claimed.forEach { dropStageRows(it) }
                work.setClaimActive(false)
                endRatingNotes()
            }
        } finally {
            failStages(claim.claimed, reason)
        }
    }

    // Rating notes last while any copy is being built or waits to be
    // swapped in: one still to come would bring the ratings it read.
    private suspend fun endRatingNotes() {
        if (work.metas().none { it.stage != STAGE_NONE }) work.clearRatingNotes()
    }

    private suspend fun dropStageRows(stage: ClaimedStage) {
        work.clearStage(stage.sourceId)
        work.clearTakeInLog(stage.generation)
        work.stageDropped(stage.sourceId, stage.generation)
    }

    private fun failStages(stages: List<ClaimedStage>, reason: String) {
        stages.forEach { stageOutcome(it.sourceId, it.generation).completeExceptionally(StageAborted(reason)) }
    }

    private fun stageOutcome(sourceId: String, generation: Long): CompletableDeferred<Unit> =
        synchronized(stages) { stages.getOrPut(sourceId to generation) { CompletableDeferred() } }

    // Every source with rows, without reading the rows.
    private suspend fun liveSources(): Set<String> {
        val ids = LinkedHashSet<String>()
        var after = ""
        while (true) {
            val next = work.nextTrackSource(after) ?: break
            ids += next
            after = next
        }
        after = ""
        while (true) {
            val next = work.nextAlbumSource(after) ?: break
            ids += next
            after = next
        }
        return ids
    }

    // Song keys stored by an older way of reading them are worked out again,
    // a page at a time. A row changed meanwhile already has new keys.
    private suspend fun refillKeys() {
        while (true) {
            val old = work.staleKeysVersion(SONG_KEYS_VERSION) ?: return
            var after = ""
            while (true) {
                val rows = work.keyRows(old, after, MERGE_PAGE)
                if (rows.isEmpty()) break
                db.withTransaction {
                    for (row in rows) {
                        val keys = SongIdentity.songKeys(row.artist, row.title)
                        work.setKeys(row.id, keys.title, keys.matchKey, SONG_KEYS_VERSION, old)
                    }
                }
                after = rows.last().id
            }
        }
    }

    private fun swapSql(sql: SupportSQLiteDatabase) = SwapSql(
        source = columnsOf(sql, "source_track"),
        album = columnsOf(sql, "source_album"),
        artist = columnsOf(sql, "source_artist"),
        track = columnsOf(sql, "track"),
        libraryAlbum = columnsOf(sql, "album"),
        libraryArtist = columnsOf(sql, "artist"),
    )

    private companion object {
        const val ELSEWHERE = "Another server is in use now."
        const val CHANGED = "Something went wrong saving the library. Try syncing again."
    }
}

// Whether a source's music joins the library: the phone's own always, a
// server's only while it is the one in use.
fun inLibrary(sourceId: String, serverInUse: String?): Boolean = !isServerSource(sourceId) || sourceId == serverInUse

// What a merge read, as text: the rules and keys versions, each source with
// its stamp, in rank order, and each copy waiting to be swapped in.
internal fun skipKey(sources: List<String>, stamps: Map<String, Long>, stages: Map<String, Long>): String = buildString {
    append("rules ").append(MERGE_RULES_VERSION).append(" keys ").append(SONG_KEYS_VERSION)
    for (source in sources) append('|').append(source).append('=').append(stamps[source] ?: 0)
    for ((source, generation) in stages.toSortedMap()) append("|stage ").append(source).append('=').append(generation)
}

internal fun columnsOf(sql: SupportSQLiteDatabase, table: String): List<String> =
    sql.query("PRAGMA table_info(`$table`)").use { c ->
        val name = c.getColumnIndexOrThrow("name")
        buildList { while (c.moveToNext()) add(c.getString(name)) }
    }

// The swap's statements, built from the tables' own columns so a column
// added later is carried too. Each writes only rows that differ.
internal class SwapSql(
    source: List<String>,
    album: List<String>,
    artist: List<String>,
    track: List<String>,
    libraryAlbum: List<String>,
    libraryArtist: List<String>,
) {
    // A claimed copy into its source's rows; arguments: source, generation.
    val applyStage: List<String> = listOf(
        apply("source_track", "stage_track", "track", source - "mergedId"),
        apply("source_album", "stage_album", "album", album),
        apply("source_artist", "stage_artist", "artist", artist),
    ).flatten()

    // The merge's output into the library; no arguments.
    val applyLibrary: List<String> = listOf(
        library("track", "merge_candidate", track) { column ->
            when (column) {
                "year" -> "COALESCE(c.originalYear, c.year)"
                "rating" -> "CASE WHEN c.rating = 0 THEN COALESCE((SELECT r.rating FROM track_rating r WHERE r.trackId = c.id), 0) ELSE c.rating END"
                else -> "c.`$column`"
            }
        },
        library("album", "album_next", libraryAlbum) { "c.`$it`" },
        library("artist", "artist_next", libraryArtist) { "c.`$it`" },
    ).flatten()

    // Each of a source's copies points at its library song; argument: source.
    val mergedIds = """
        UPDATE source_track
        SET mergedId = COALESCE((SELECT n.mergedId FROM merged_id_next n WHERE n.sourceRowId = source_track.id), id)
        WHERE sourceId = ?1
          AND mergedId IS NOT COALESCE((SELECT n.mergedId FROM merged_id_next n WHERE n.sourceRowId = source_track.id), id)
    """.trimIndent()

    private fun apply(live: String, staged: String, kind: String, columns: List<String>): List<String> {
        val all = columns.joinToString { "`$it`" }
        val set = columns - "id"
        return listOf(
            """
            DELETE FROM `$live` WHERE sourceId = ?1
              AND NOT EXISTS (SELECT 1 FROM `$staged` g WHERE g.id = `$live`.id)
              AND NOT EXISTS (SELECT 1 FROM take_in_log l WHERE l.generation = ?2 AND l.kind = '$kind' AND l.id = `$live`.id)
            """.trimIndent(),
            """
            UPDATE `$live` SET (${set.joinToString { "`$it`" }}) = (SELECT ${set.joinToString { "g.`$it`" }} FROM `$staged` g WHERE g.id = `$live`.id)
            WHERE sourceId = ?1 AND ?2 = ?2
              AND EXISTS (SELECT 1 FROM `$staged` g WHERE g.id = `$live`.id AND NOT (${set.joinToString(" AND ") { "g.`$it` IS `$live`.`$it`" }}))
            """.trimIndent(),
            """
            INSERT INTO `$live` ($all) SELECT ${columns.joinToString { "g.`$it`" }} FROM `$staged` g
            WHERE g.sourceId = ?1 AND ?2 = ?2 AND NOT EXISTS (SELECT 1 FROM `$live` t WHERE t.id = g.id)
            """.trimIndent(),
        )
    }

    private fun library(table: String, from: String, columns: List<String>, value: (String) -> String): List<String> {
        val set = columns - "id"
        return listOf(
            "DELETE FROM `$table` WHERE NOT EXISTS (SELECT 1 FROM `$from` c WHERE c.id = `$table`.id)",
            """
            UPDATE `$table` SET (${set.joinToString { "`$it`" }}) = (SELECT ${set.joinToString { value(it) }} FROM `$from` c WHERE c.id = `$table`.id)
            WHERE EXISTS (SELECT 1 FROM `$from` c WHERE c.id = `$table`.id AND NOT (${set.joinToString(" AND ") { "${value(it)} IS `$table`.`$it`" }}))
            """.trimIndent(),
            "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) SELECT ${columns.joinToString { value(it) }} FROM `$from` c " +
                "WHERE NOT EXISTS (SELECT 1 FROM `$table` t WHERE t.id = c.id)",
        )
    }
}
