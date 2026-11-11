package app.winters.octo.catalog

import android.database.sqlite.SQLiteFullException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.device.DEVICE
import app.winters.octo.listening.TotalsHooks
import app.winters.octo.listening.TotalsRig
import app.winters.octo.offline.RulesRig
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.PlayRequest
import app.winters.octo.playback.QueueRig
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// What each reader waits for while a save applies. Between two groups, a
// stable read of each kind, a read of the play totals, the listening
// history and a whole walk answer; a Walk's last try parked inside a save
// keeps the next group out but not a totals read; Up next fills while a
// save is parked between groups. A save stopped after its first group turns
// its stamp even and says why, and the offline rules' walk and a whole
// reader wait until a later save has ended it. Each group's stamp marks the
// totals built through with no rebuild queued, but not while a rebuild's
// apply runs; and the swap stamp is even while a group only waits.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class SaveSignalsTest {
    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    // A library and a change to it that saves in many groups at the floor
    // bound (and at the default, a run of pages).
    private suspend fun CatalogDatabase.changed(bound: Long): CatalogMerge {
        val songs = if (bound == SAVE_GROUP_FLOOR) 30 else 1_200
        put(library(DEVICE, songs))
        boundMerge(null, bound).rebuild()
        put(library(DEVICE, songs + 10).let { it.copy(tracks = it.tracks.map { t -> if (t.nativeId.removePrefix("s").toInt() % 3 == 0) t.copy(title = t.title + " (Live)") else t }) })
        return boundMerge(null, bound)
    }

    @Test
    fun readersAnswerBetweenGroups() = run {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                val merge = db.changed(bound)
                val rig = TotalsRig(db, merge).started()
                val answered = CompletableDeferred<List<Any?>?>()
                val hooks = object : GroupHooks() {}
                var parks = 0
                val odd = ArrayList<Long>()
                hooks.between = { turn ->
                    parks++
                    if (merge.swapStamp.value % 2 != 0L) odd += turn
                    if (turn == 1L) {
                        // Each runs on another coroutine while the save waits
                        // here, between groups.
                        val reads = listOf(
                            async(Dispatchers.Default) { stableRead(merge, HoldKind.Read) { db.longOf("SELECT COUNT(*) FROM track") } },
                            async(Dispatchers.Default) { stableRead(merge, HoldKind.Page) { db.longOf("SELECT COUNT(*) FROM album") } },
                            async(Dispatchers.Default) { stableRead(merge, HoldKind.Walk) { db.longOf("SELECT COUNT(*) FROM artist") } },
                            async(Dispatchers.Default) { rig.totals.read(HoldKind.Read) { rig.dao.storedCount() } },
                            async(Dispatchers.Default) { rig.history.recentlyPlayedIds(5).size },
                            async(Dispatchers.Default) { merge.betweenRebuilds(HoldKind.Walk) { db.longOf("SELECT COUNT(*) FROM source_track") } },
                        )
                        val got = withTimeoutOrNull(10_000) { reads.awaitAll() }
                        if (got == null) reads.forEach { it.cancel() }
                        answered.complete(got)
                    }
                }
                merge.hooks = hooks
                merge.rebuild()
                assertTrue("bound $bound: the swap stamp was odd between groups after turns $odd", odd.isEmpty())
                assertEquals("bound $bound: the readers did not answer between groups", 6, answered.await()?.size)
                assertTrue("bound $bound: $parks parks", parks > 1)
            } finally {
                db.close()
            }
        }
    }

    // A Walk's last try parked inside, between two groups: the next group
    // waits for it, and a totals read (a short hold) still answers.
    @Test
    fun aParkedWalkHoldsTheNextGroupButNoTotalsRead() = run {
        val db = openTestCatalog()
        try {
            val merge = db.changed(SAVE_GROUP_FLOOR)
            val rig = TotalsRig(db, merge).started()
            val held = CompletableDeferred<Unit>()
            val waits = CompletableDeferred<Long>()
            val hooks = object : GroupHooks() {
                override fun groupWaits() {
                    waits.complete(merge.swapStamp.value)
                }
            }
            var hold: kotlinx.coroutines.Job? = null
            hooks.between = { turn ->
                if (turn == 1L) {
                    // A Walk's last try enters between groups and stays.
                    hold = launch(Dispatchers.Default) { merge.betweenRebuilds(HoldKind.Walk) { held.complete(Unit); awaitCancellation() } }
                    held.await()
                }
            }
            merge.hooks = hooks
            val saving = async(Dispatchers.Default) { merge.rebuild() }
            val stamp = waits.await()
            assertEquals("the swap stamp is even while a group only waits", 0L, stamp % 2)
            assertEquals(0L, merge.swapStamp.value % 2)
            val read = withTimeout(10_000) { rig.totals.read(HoldKind.Read) { rig.dao.storedCount() } }
            assertNotNull(read)
            assertFalse("the group ran beside the walk", saving.isCompleted)
            hold!!.cancel()
            saving.await()
        } finally {
            db.close()
        }
    }

    // Up next fills from a whole walk of the library while a save is
    // parked between two of its groups.
    @Test
    fun upNextFillsWhileASaveIsParked() {
        val db = openTestCatalog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val merge = runBlocking { db.changed(SAVE_GROUP_FLOOR) }
            val parked = CompletableDeferred<Unit>()
            val go = CompletableDeferred<Unit>()
            val hooks = object : GroupHooks() {}
            hooks.between = { turn ->
                if (turn == 1L) {
                    parked.complete(Unit)
                    go.await()
                }
            }
            merge.hooks = hooks
            val saving = scope.async { merge.rebuild() }
            runBlocking { withTimeout(20_000) { parked.await() } }
            val rig = QueueRig(db, libraryMerge = merge)
            try {
                rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null))
                rig.joined()
                val songs = db.longOf("SELECT COUNT(*) FROM track")
                assertEquals("Up next filled while the save was parked", songs, rig.songs().size.toLong())
                assertFalse(saving.isCompleted)
            } finally {
                go.complete(Unit)
                runBlocking { withTimeout(20_000) { saving.await() } }
                rig.close()
            }
        } finally {
            scope.cancel()
            db.close()
        }
    }

    // A save stopped by a full disk after its first group: its stamp is
    // even, it says why, and it is still part way; its one resume meets the
    // full disk too. The offline rules' walk and a whole reader (as a
    // playlist's snapshot reads) wait until a merge ask, with room again,
    // resumes it to its end.
    @Test
    fun aStoppedSaveHoldsTheRulesWalkAndWholeReadersUntilItsResumeEndsIt() = run {
        val db = openTestCatalog()
        try {
            val merge = db.changed(SAVE_GROUP_FLOOR)
            val resumed = CompletableDeferred<Throwable?>()
            val hooks = object : GroupHooks() {
                override fun resumed(failure: Throwable?) {
                    resumed.complete(failure)
                }
            }
            hooks.between = { turn -> if (turn == 1L) db.counted.fullDisk = { it.startsWith("UPDATE merge_state SET swapCount") } }
            merge.hooks = hooks
            val failed = runCatching { merge.rebuild() }.exceptionOrNull()
            assertTrue("$failed", failed is SQLiteFullException)
            assertTrue("the stop's resume ended the save", withTimeout(20_000) { resumed.await() } is SQLiteFullException)
            assertEquals("the apply stamp is even after a stop", 0L, merge.applyStamp.value % 2)
            assertNotNull(merge.applyStopped.value)
            assertTrue("a stopped save is still part way", merge.applyPending.value)
            assertFalse(merge.whole)
            val rules = RulesRig(db, merge)
            val walk = async(Dispatchers.Default) { rules.rules.walk(keepLiked = true, emptySet(), null) }
            val whole = async(Dispatchers.Default) { stableRead(merge, HoldKind.Walk, {}, WholeCheck(merge)) { db.longOf("SELECT COUNT(*) FROM track") } }
            repeat(50) { yield() }
            assertFalse("the rules walk ran beside a stopped save", walk.isCompleted)
            assertFalse("a whole reader read beside a stopped save", whole.isCompleted)
            db.counted.fullDisk = null
            merge.hooks = MergeHooks()
            merge.rebuild()
            assertTrue(merge.whole)
            withTimeout(20_000) {
                walk.await()
                whole.await()
            }
        } finally {
            db.counted.fullDisk = null
            db.close()
        }
    }

    // A save part applied when the app ended: after the open, a new
    // launch's offline rules' walk and a whole reader wait for no save to be
    // part way, read from its record, until the launch's repair resumes it.
    @Test
    fun aKillImageHoldsTheRulesWalkAndWholeReadersUntilRepairEndsIt() = run {
        val db = openTestCatalog()
        var image: java.io.File? = null
        try {
            val merge = db.changed(SAVE_GROUP_FLOOR)
            val hooks = object : GroupHooks() {}
            hooks.between = { turn -> if (turn == 2L) image = killImage(db) }
            merge.hooks = hooks
            merge.rebuild()
            assertNotNull("no image", image)
        } finally {
            db.close()
        }
        LaunchMark.next()
        val copy = openTestCatalog(image!!)
        try {
            val merge = copy.boundMerge(null, SAVE_GROUP_FLOOR)
            val rules = RulesRig(copy, merge)
            val walk = async(Dispatchers.Default) { rules.rules.walk(keepLiked = true, emptySet(), null) }
            val whole = async(Dispatchers.Default) { stableRead(merge, HoldKind.Walk, {}, WholeCheck(merge)) { copy.longOf("SELECT COUNT(*) FROM track") } }
            withTimeout(10_000) { merge.applyPending.first { it } }
            repeat(50) { yield() }
            assertFalse("the rules walk ran beside a part-applied save", walk.isCompleted)
            assertFalse("a whole reader read beside a part-applied save", whole.isCompleted)
            merge.repair()
            assertTrue(merge.whole)
            withTimeout(20_000) {
                walk.await()
                whole.await()
            }
        } finally {
            copy.close()
        }
    }

    // After each group, with the totals built once and no rebuild applying,
    // the totals are built through its stamp with no rebuild opened; with a
    // rebuild's apply parked, a group's stamp is not built through until
    // that rebuild ends.
    @Test
    fun eachGroupMarksTheTotalsBuiltThroughUnlessARebuildApplies() = run {
        val db = openTestCatalog()
        try {
            val merge = db.changed(SAVE_GROUP_FLOOR)
            val rig = TotalsRig(db, merge).started()
            var opened = 0
            rig.totals.hooks = object : TotalsHooks() {
                override suspend fun opened() {
                    opened++
                }
            }
            val behind = ArrayList<String>()
            val hooks = object : GroupHooks() {}
            hooks.between = { turn ->
                val stamp = merge.swapStamp.value
                rig.totals.builtThrough.first { it >= stamp }
                if (opened != 0) behind += "turn $turn opened a rebuild"
            }
            merge.hooks = hooks
            merge.rebuild()
            assertTrue(behind.joinToString(), behind.isEmpty())
            assertTrue("one group only", hooks.turns.get() > 1)

            // A rebuild's apply parked at its first page.
            val parked = CompletableDeferred<Unit>()
            val go = CompletableDeferred<Unit>()
            rig.totals.hooks = object : TotalsHooks() {
                override suspend fun applied() {
                    if (parked.complete(Unit)) go.await()
                }
            }
            db.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, '', 1, 1, 1)", "$DEVICE:s1")
            rig.totals.rebuild()
            parked.await()
            db.put(library(DEVICE, 41))
            merge.hooks = MergeHooks()
            merge.rebuild()
            assertTrue("the totals were built through a group while a rebuild applied", rig.totals.builtThrough.value < merge.swapStamp.value)
            go.complete(Unit)
            rig.totals.builtThrough.first { it >= merge.swapStamp.value }
        } finally {
            db.close()
        }
    }
}
