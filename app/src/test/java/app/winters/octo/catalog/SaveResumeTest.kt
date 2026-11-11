package app.winters.octo.catalog

import android.database.sqlite.SQLiteFullException
import app.winters.octo.device.DEVICE
import app.winters.octo.server.CopyRun
import app.winters.octo.server.ROWS_VERSION
import app.winters.octo.server.StagedCopy
import app.winters.octo.server.dropSources
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// A save stopped or cut off after its first group is resumed, never
// aborted: its record (merge_state) holds what the resume needs, the open
// keeps it, and one resume at a time runs it to the end, outside every
// transaction, finishing equal to the save that ran straight through, with
// the copy's record and its Save step's progress. A kill is a file image:
// the database and its log copied mid-save and opened as a new launch.
@RunWith(RobolectricTestRunner::class)
class SaveResumeTest {
    private val server = "server:resume.test"
    private val account = "acct-resume"

    private fun run(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(120_000) { block() } }

    // How many songs make a save of several groups at `bound`, and of a run
    // of pages at the default.
    private fun songs(bound: Long) = if (bound == SAVE_GROUP_FLOOR) 30 else 1_200

    // The server's copy of its library, and a new copy that changes a third
    // of its songs' titles and adds ten, staged for the account as a copy
    // leaves it; the phone's music beside it. With `titlesOnly`, the new
    // copy only retitles, so no source row is left unmerged at any group.
    private suspend fun CatalogDatabase.serverChange(bound: Long, titlesOnly: Boolean = false, rating: Boolean = false) {
        val n = songs(bound)
        put(library(DEVICE, 4))
        put(library(server, n, msShift = 100))
        boundMerge(server, bound).also {
            it.rebuild()
            it.awaitClear(MERGE_WORK_CLEAR)
        }
        copyDao().ensureAccountSync(account, server, ROWS_VERSION)
        val next = library(server, if (titlesOnly) n else n + 10, msShift = 100)
        val retitled = next.tracks.map { t -> if (t.nativeId.removePrefix("s").toInt() % 3 == 0) t.copy(title = t.title + " (Live)") else t }
        // Beyond titles, every song moves to one new album: one set over the
        // bound, a run of pages.
        val staged = if (titlesOnly) {
            next.copy(tracks = retitled)
        } else {
            next.copy(tracks = retitled.map { it.copy(albumId = "$server:big", album = "Big") }, albums = listOf(albumRow(server, "big", "Big")))
        }
        stage(staged, generation = 7, owner = account)
        if (rating) exec("INSERT INTO rating_note (sourceRowId, rating) VALUES (?, 4)", "$server:s1")
    }

    // The library as a reader compares it, with the background clears done.
    private suspend fun settledImage(db: CatalogDatabase, merge: CatalogMerge): DbImage {
        merge.awaitClear(MERGE_WORK_CLEAR)
        db.workDao().metas().forEach { merge.awaitStageClear(it.sourceId) }
        // The launch's mark differs by launch, and the copy's record is the
        // completion's (asserted on its own).
        val carved = SaveOracle.CARVED + ("merge_state" to SaveOracle.CARVED.getValue("merge_state") + "launchMark")
        return SaveOracle.image(db.openHelper.writableDatabase, cleared = SaveOracle.CLEARED + "account_sync", carved = carved)
    }

    // A resume's report, as the server side gives it: what it was told, and
    // the copy finished as a copy is once in.
    private inner class Reports(private val db: CatalogDatabase, private val merge: CatalogMerge) : SaveResumes {
        val copies: MutableList<ResumedCopy> = Collections.synchronizedList(ArrayList())
        val parts: MutableList<Float> = Collections.synchronizedList(ArrayList())
        val ended = CompletableDeferred<Throwable?>()

        override fun resuming(copy: ResumedCopy): ResumeReport {
            copies += copy
            return object : ResumeReport {
                override fun saved(part: Float) {
                    parts += part
                }

                override fun ended(failure: Throwable?) {
                    if (failure == null) {
                        runBlocking {
                            CopyRun(StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE }), db.copyDao(), emptyList(), clock = { 42L })
                                .complete(copy.account, copy.sourceId, copied = true, copy.scan, swapped = {})
                        }
                    }
                    this@Reports.ended.complete(failure)
                }
            }
        }
    }

    // Counts resumes and their ends; `atResume` runs as each begins.
    private class Resumes(@Volatile var atResume: suspend (Boolean) -> Unit = {}) : GroupHooks() {
        val started = AtomicInteger()
        val automatic = AtomicInteger()
        val ends: MutableList<Throwable?> = Collections.synchronizedList(ArrayList())
        @Volatile var endedOnce = CompletableDeferred<Unit>()

        override suspend fun resuming(auto: Boolean) {
            started.incrementAndGet()
            if (auto) automatic.incrementAndGet()
            atResume(auto)
        }

        override fun resumed(failure: Throwable?) {
            ends += failure
            endedOnce.complete(Unit)
        }

        // The plan's group of each turn of the first save, so a run (one
        // group over several turns, each parked at) can be told.
        val groupOf = java.util.concurrent.ConcurrentHashMap<Long, Long>()

        override suspend fun inGroup(turn: Long, group: Long) {
            groupOf.putIfAbsent(turn, group)
            super.inGroup(turn, group)
        }

        fun ranARun() = groupOf.values.groupingBy { it }.eachCount().any { it.value > 1 }
    }

    // A kill after group k, opened as a new launch with a fresh merge, and
    // only its launch's repair: it resumes and finishes equal to the save
    // that ran on, with the copy's record and its progress, every
    // transaction within the bound; the stage's rows, the take-in log and a
    // rating note it kept across the open.
    @Test
    fun aKillImageAfterGroupKFinishesEqualToTheSaveThatRanOn() = run {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            for (k in listOf(1L, 2L)) {
                val where = "bound $bound, kill after group $k"
                val db = openTestCatalog()
                var image: File? = null
                try {
                    db.serverChange(bound, rating = true)
                    db.exec("INSERT INTO take_in_log (generation, kind, id) VALUES (7, 'track', ?)", "$server:s0")
                    val hooks = Resumes()
                    var parked = 0
                    hooks.between = { turn ->
                        parked++
                        if (turn == k) image = killImage(db)
                    }
                    val merge = db.boundMerge(server, bound, hooks)
                    merge.noteCopy(server, 7, CopyNote("scan-7"))
                    merge.rebuild()
                    merge.awaitStage(server, 7)
                    assertTrue("$where: $parked parks", parked > k)
                    assertTrue("$where: ${hooks.saves} groups", hooks.saves.last() >= 3)
                    assertTrue("$where: no run parked: ${hooks.groupOf}", hooks.ranARun())
                    val expected = settledImage(db, merge)

                    LaunchMark.next()
                    val copy = openTestCatalog(image!!)
                    try {
                        val w = copy.openHelper.writableDatabase
                        assertNotNull("$where: the record outlived the kill", copy.stringOf("SELECT applyClaim FROM merge_state"))
                        assertEquals("$where: the claimed stage kept", STAGE_CLAIMED, copy.stringOf("SELECT stage FROM source_meta WHERE sourceId = ?", server))
                        assertTrue("$where: the stage's rows kept", copy.longOf("SELECT COUNT(*) FROM stage_track WHERE sourceId = ?", server) > 0)
                        assertEquals("$where: the rating note kept", 1L, copy.longOf("SELECT COUNT(*) FROM rating_note"))
                        assertEquals("$where: the take-in log kept", 1L, copy.longOf("SELECT COUNT(*) FROM take_in_log"))
                        assertEquals("$where: the claim still active", 1L, copy.longOf("SELECT claimActive FROM merge_state"))
                        // How much of the save is in moves group by group (a
                        // run's pages hold it until the run is in).
                        val part = SaveRecord.of(copy.stringOf("SELECT applyClaim FROM merge_state")!!).part
                        assertTrue("$where: the record says the save is in: $part", part >= 0f && part < 1f)

                        // A resume runs outside every transaction: one that
                        // begins inside one stops at once, and is told here.
                        val inside = Collections.synchronizedList(ArrayList<Boolean>())
                        val resumes = Resumes { _ ->
                            val nested = copy.inTransaction()
                            inside += nested
                            check(!nested) { "a resume began inside a transaction" }
                        }
                        val again = copy.boundMerge(server, bound, resumes)
                        val reports = Reports(copy, again)
                        again.resumes = reports
                        copy.counted.reset()
                        copy.counted.recording = true
                        again.repair()
                        copy.counted.recording = false
                        assertEquals("$where: a resume began inside a transaction", listOf(false), inside.toList())
                        assertNull("$where: the resume failed", withTimeout(20_000) { reports.ended.await() })
                        assertEquals("$where: one resume", 1, resumes.started.get())
                        assertNull("$where: the record is gone", copy.stringOf("SELECT applyClaim FROM merge_state"))
                        assertEquals(1, reports.copies.size)
                        val resumed = reports.copies.single()
                        assertEquals(account, resumed.account)
                        assertEquals(server, resumed.sourceId)
                        assertEquals(7L, resumed.generation)
                        assertTrue("$where: the account's first copy", resumed.firstCopy)
                        assertEquals("scan-7", resumed.scan)
                        // The Save step goes on from where the save was.
                        assertTrue("$where: no progress", reports.parts.isNotEmpty())
                        assertTrue("$where: progress ${reports.parts} from $part", reports.parts.first() >= part && reports.parts.zipWithNext().all { (a, b) -> b >= a })
                        assertEquals(1f, reports.parts.last(), 0f)
                        // The copy's record, as its completion writes it.
                        assertEquals(42L, copy.longOf("SELECT syncedAt FROM account_sync WHERE account = ?", account))
                        assertEquals(1L, copy.longOf("SELECT afterSyncDone FROM account_sync WHERE account = ?", account))
                        assertEquals("scan-7", copy.stringOf("SELECT lastScan FROM account_sync WHERE account = ?", account))
                        assertEquals(account, copy.stringOf("SELECT copyOf FROM source_meta WHERE sourceId = ?", server))
                        // No write of the resume passed the bound (the clears
                        // after it run in pages of their own).
                        val writes = copy.counted.transactions.filter { it.totalChanges > 0 && it.label != MERGE_WORK_CLEAR && it.label != STAGE_CLEAR }
                        assertTrue("$where: no write recorded", writes.isNotEmpty())
                        val most = writes.maxBy { it.totalChanges }
                        assertTrue("$where: a transaction of the resume changed ${most.totalChanges} rows, over $bound: $most", most.totalChanges <= bound)
                        val differences = SaveOracle.differences(expected, settledImage(copy, again))
                        assertTrue("$where: ${differences.joinToString("\n")}", differences.isEmpty())
                        assertEquals(0L, w.query("SELECT COUNT(*) FROM take_in_log").use { it.moveToFirst(); it.getLong(0) })
                    } finally {
                        copy.close()
                    }
                } finally {
                    db.close()
                }
            }
        }
    }

    // A kill image of a save that only retitles, so no source row is left
    // unmerged, then only the launch's repair: the record alone starts the
    // resume.
    @Test
    fun aKillImageThenOnlyRepairResumes() = run {
        val db = openTestCatalog()
        var image: File? = null
        try {
            db.serverChange(SAVE_GROUP_FLOOR, titlesOnly = true)
            val hooks = GroupHooks()
            hooks.between = { turn -> if (turn == 2L) image = killImage(db) }
            val merge = db.boundMerge(server, SAVE_GROUP_FLOOR, hooks)
            merge.rebuild()
            assertNotNull("no image at group 2", image)
        } finally {
            db.close()
        }
        LaunchMark.next()
        val copy = openTestCatalog(image!!)
        try {
            assertFalse("a source row is unmerged", copy.workDao().hasUnmerged(server) || copy.workDao().hasUnmerged(DEVICE))
            val resumes = Resumes()
            val again = copy.boundMerge(server, SAVE_GROUP_FLOOR, resumes)
            again.repair()
            assertEquals("repair did not resume", 1, resumes.started.get())
            assertNull(copy.stringOf("SELECT applyClaim FROM merge_state"))
            assertTrue(again.whole)
        } finally {
            copy.close()
        }
    }

    // A throw, a full disk and a cancel after group k each stop the save,
    // which starts exactly one resume of it; when that one stops too,
    // nothing more starts until work asks: here a merge ask, which resumes
    // it to the end.
    @Test
    fun aStopStartsOneAutomaticResumeWhateverItsCause() = run {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            for (cause in listOf("throw", "full disk", "cancel", "changing")) {
                for (k in listOf(1L, 2L)) {
                    val where = "bound $bound, $cause after group $k"
                    val db = openTestCatalog()
                    try {
                        db.serverChange(bound)
                        val failing = AtomicBoolean(false)
                        val armed = AtomicBoolean(false)
                        val stops = AtomicInteger()
                        val resumes = Resumes()
                        var parked = 0
                        // The first save's group k is the last in; from then
                        // on every group fails, until the test lets it go.
                        resumes.between = { turn ->
                            parked++
                            if (turn == k && armed.compareAndSet(false, true)) {
                                failing.set(true)
                                if (cause == "full disk") db.counted.fullDisk = { it.startsWith("UPDATE merge_state SET swapCount") }
                            }
                        }
                        resumes.inside = { _ ->
                            if (failing.get()) {
                                when (cause) {
                                    "throw" -> throw IllegalStateException("a group broke")
                                    "cancel" -> throw CancellationException("a group was cancelled")
                                    // A cause that changes at every stop.
                                    "changing" -> if (stops.getAndIncrement() % 2 == 0) throw IllegalStateException("a group broke") else throw SQLiteFullException("full")
                                }
                            }
                        }
                        val merge = db.boundMerge(server, bound, resumes)
                        val first = runCatching { merge.rebuild() }.exceptionOrNull()
                        assertNotNull("$where: the save did not stop", first)
                        withTimeout(20_000) { resumes.endedOnce.await() }
                        repeat(50) { yield() }
                        assertEquals("$where: resumes", 1, resumes.started.get())
                        assertEquals("$where: automatic resumes", 1, resumes.automatic.get())
                        assertNotNull("$where: the automatic resume did not stop", resumes.ends.single())
                        assertTrue("$where: still part way", merge.applyPending.value)
                        assertNotNull("$where: the stop's words", db.stringOf("SELECT applyStopped FROM merge_state"))
                        assertEquals("$where: the apply stamp is even after a stop", 0L, merge.applyStamp.value % 2)
                        assertTrue("$where: $parked parks", parked >= k)
                        assertTrue("$where: no run parked: ${resumes.groupOf}", resumes.ranARun())
                        // Work asks: the resume runs, and ends the save.
                        failing.set(false)
                        db.counted.fullDisk = null
                        merge.rebuild()
                        assertEquals("$where: resumes after the ask", 2, resumes.started.get())
                        assertEquals("$where: automatic resumes after the ask", 1, resumes.automatic.get())
                        assertNull("$where: the record is left", db.stringOf("SELECT applyClaim FROM merge_state"))
                        assertTrue(merge.whole)
                        assertEquals(songs(bound) + 10L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
                        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_track WHERE mergedId = ''"))
                    } finally {
                        db.counted.fullDisk = null
                        db.close()
                    }
                }
            }
        }
    }

    // Three callers at once (a merge ask, a write to the sources, Try again)
    // give one resume, and one that waited and was cancelled leaves it
    // running; the write runs once the record is gone.
    @Test
    fun threeResumersAtOnceGiveOneResumeThatACancelDoesNotStop() = run {
        val image = keptImage(SAVE_GROUP_FLOOR)
        LaunchMark.next()
        val copy = openTestCatalog(image)
        try {
            val entered = CompletableDeferred<Unit>()
            val go = CompletableDeferred<Unit>()
            val resumes = Resumes { _ ->
                entered.complete(Unit)
                go.await()
            }
            val merge = copy.boundMerge(server, SAVE_GROUP_FLOOR, resumes)
            val wrote = AtomicInteger()
            val callers = listOf(
                async(Dispatchers.Default) { merge.rebuild() },
                async(Dispatchers.Default) { merge.sourceWrite { wrote.incrementAndGet() } },
                async(Dispatchers.Default) { merge.resumeSave() },
            )
            val cancelled = async(Dispatchers.Default) { merge.rebuild() }
            entered.await()
            repeat(20) { yield() }
            cancelled.cancel()
            go.complete(Unit)
            callers.awaitAll()
            assertTrue(cancelled.isCancelled)
            assertEquals("resumes", 1, resumes.started.get())
            assertEquals(1, wrote.get())
            assertNull(copy.stringOf("SELECT applyClaim FROM merge_state"))
        } finally {
            copy.close()
        }
    }

    // A resume that stops again leaves the record; a copy that starts then
    // gets ApplyPending and leaves the claimed stage as it is.
    @Test
    fun aFailedResumeThenACopysStartKeepsTheStage() = run {
        val image = keptImage(SAVE_GROUP_FLOOR)
        LaunchMark.next()
        val copy = openTestCatalog(image)
        try {
            val staged = copy.longOf("SELECT COUNT(*) FROM stage_track WHERE sourceId = ?", server)
            assertTrue(staged > 0)
            copy.counted.fullDisk = { it.startsWith("UPDATE merge_state SET swapCount") }
            val merge = copy.boundMerge(server, SAVE_GROUP_FLOOR, Resumes())
            merge.repair()
            assertNotNull("the resume ended the save", copy.stringOf("SELECT applyClaim FROM merge_state"))
            val started = runCatching { StagedCopy(copy, merge, FreeSpace { Long.MAX_VALUE }).start(account, server) }.exceptionOrNull()
            assertTrue("$started", started is ApplyPending)
            assertEquals(staged, copy.longOf("SELECT COUNT(*) FROM stage_track WHERE sourceId = ?", server))
            assertEquals(STAGE_CLAIMED, copy.stringOf("SELECT stage FROM source_meta WHERE sourceId = ?", server))
            // A removed server's rows wait for the next drop, and nothing fails.
            val rows = copy.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server)
            assertFalse(dropSources(merge, copy.sourceDao(), listOf(server)))
            assertEquals(rows, copy.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
            copy.counted.fullDisk = null
        } finally {
            copy.counted.fullDisk = null
            copy.close()
        }
    }

    // Inside a Page hold, a write to the sources while a save is part
    // applied gets ApplyPending at once, never a hang, and the resume starts
    // once the hold is let go.
    @Test
    fun aPageHoldsSourceWriteGetsApplyPendingAtOnce() = run {
        val image = keptImage(SAVE_GROUP_FLOOR)
        LaunchMark.next()
        val copy = openTestCatalog(image)
        try {
            val resumes = Resumes()
            val merge = copy.boundMerge(server, SAVE_GROUP_FLOOR, resumes)
            val failure = withTimeout(10_000) { merge.betweenRebuilds(HoldKind.Page) { runCatching { merge.sourceWrite { } }.exceptionOrNull() } }
            assertTrue("$failure", failure is ApplyPending)
            withTimeout(20_000) { resumes.endedOnce.await() }
            assertEquals(1, resumes.started.get())
            assertNull(copy.stringOf("SELECT applyClaim FROM merge_state"))
        } finally {
            copy.close()
        }
    }

    // Try again's resume (resumeSave) ends the save; with none part way it
    // says so, and a copy is what Try again starts then.
    @Test
    fun tryAgainResumesTheRecord() = run {
        val image = keptImage(SAVE_GROUP_FLOOR)
        LaunchMark.next()
        val copy = openTestCatalog(image)
        try {
            val resumes = Resumes()
            val merge = copy.boundMerge(server, SAVE_GROUP_FLOOR, resumes)
            assertTrue(merge.resumeSave())
            assertEquals(1, resumes.started.get())
            assertNull(copy.stringOf("SELECT applyClaim FROM merge_state"))
            assertFalse("nothing was part way", merge.resumeSave())
        } finally {
            copy.close()
        }
    }

    // Signed out (the server no longer in use) while a save applies: the
    // apply finishes with the server's songs, then the next merge takes them
    // out of the library.
    @Test
    fun aSignOutMidApplyFinishesThenTheNextMergeTakesTheServerOut() = run {
        val db = openTestCatalog()
        try {
            db.serverChange(SAVE_GROUP_FLOOR)
            val inUse = java.util.concurrent.atomic.AtomicReference<String?>(server)
            val hooks = GroupHooks()
            hooks.between = { turn -> if (turn == 1L) inUse.set(null) }
            val merge = CatalogMerge(db, { Long.MAX_VALUE }, { inUse.get() }, db.workDao(), SAVE_GROUP_FLOOR).also { it.hooks = hooks }
            merge.rebuild()
            assertTrue("one group only", hooks.turns.get() > 1)
            assertEquals("the apply finished", 0L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ? AND mergedId = ''", server))
            assertTrue("the server's songs are in", db.longOf("SELECT COUNT(*) FROM track") > 4)
            merge.rebuild()
            assertEquals("the server's songs left", 4L, db.longOf("SELECT COUNT(*) FROM track"))
        } finally {
            db.close()
        }
    }

    // A find cancelled midway (no group in, so no record): the next merge in
    // the same process clears what it left and saves equal to a save that
    // ran straight through.
    @Test
    fun aFindCancelledMidwayThenAMergeEqualsTheStraightSave() = run {
        val db = openTestCatalog()
        try {
            db.put(library(DEVICE, 30))
            db.boundMerge(null, SAVE_GROUP_FLOOR).also {
                it.rebuild()
                it.awaitClear(MERGE_WORK_CLEAR)
            }
            db.put(library(DEVICE, 40).let { it.copy(tracks = it.tracks.map { t -> if (t.nativeId.removePrefix("s").toInt() % 3 == 0) t.copy(title = t.title + " (Live)") else t }) })
            val straight = copyCatalog(db)
            val expected = try {
                val merge = straight.boundMerge(null, SAVE_GROUP_FLOOR)
                merge.rebuild()
                settledImage(straight, merge)
            } finally {
                straight.close()
            }
            // The find is cancelled at a later transaction of its found rows.
            val cancelled = AtomicBoolean(false)
            val inserts = AtomicInteger()
            db.counted.fullDisk = { sql ->
                if (sql.startsWith("INSERT INTO save_found") && inserts.incrementAndGet() == 25 && cancelled.compareAndSet(false, true)) throw CancellationException("cancelled mid find")
                false
            }
            val merge = db.boundMerge(null, SAVE_GROUP_FLOOR)
            val first = runCatching { merge.rebuild() }.exceptionOrNull()
            db.counted.fullDisk = null
            assertTrue("$first", first is CancellationException && cancelled.get())
            assertNull("a record without a group", db.stringOf("SELECT applyClaim FROM merge_state"))
            merge.rebuild()
            val differences = SaveOracle.differences(expected, settledImage(db, merge))
            assertTrue(differences.joinToString("\n"), differences.isEmpty())
        } finally {
            db.counted.fullDisk = null
            db.close()
        }
    }

    // A file image taken after the first group of a save of the server's
    // change, for a test to open as a new launch.
    private suspend fun keptImage(bound: Long): File {
        val db = openTestCatalog()
        try {
            db.serverChange(bound)
            var image: File? = null
            val hooks = GroupHooks()
            hooks.between = { turn -> if (turn == 1L) image = killImage(db) }
            db.boundMerge(server, bound, hooks).rebuild()
            return image!!
        } finally {
            db.close()
        }
    }

    @Test
    fun aFullDiskStopSaysWhy() = run {
        val db = openTestCatalog()
        try {
            db.serverChange(SAVE_GROUP_FLOOR)
            val resumes = Resumes()
            resumes.between = { turn -> if (turn == 1L) db.counted.fullDisk = { it.startsWith("UPDATE merge_state SET swapCount") } }
            val merge = db.boundMerge(server, SAVE_GROUP_FLOOR, resumes)
            val failed = runCatching { merge.rebuild() }.exceptionOrNull()
            assertTrue("$failed", failed is SQLiteFullException)
            val outcome = runCatching { merge.awaitStage(server, 7) }.exceptionOrNull()
            assertTrue("$outcome", outcome is ApplyPending)
            withTimeout(20_000) { resumes.endedOnce.await() }
            db.counted.fullDisk = null
            assertNotNull(merge.applyStopped.value)
        } finally {
            db.counted.fullDisk = null
            db.close()
        }
    }
}
