package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.Gate
import app.winters.octo.listening.NOW
import app.winters.octo.listening.PlayStream
import app.winters.octo.listening.TotalsHooks
import app.winters.octo.listening.TotalsRig
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.mergedLibrary
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A list by plays waits for the totals of the stamp it began waiting at,
// never a later one: saves land after the rebuild's pages and the rebuild
// is held inside its apply, the list opens then and waits, and it shows
// once that rebuild ends, built through every save that landed meanwhile,
// with no rebuild after it (each save's groups kept the totals).
@RunWith(RobolectricTestRunner::class)
class ListeningWaitStartTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    @Test
    fun aListByPlaysShowsOnceTheRebuildItsWaitBeganInEnds() = timed(60) {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge, page = 2).started()
        val swaps = AtomicInteger(0)
        val opened = AtomicInteger()
        val inApply = java.util.concurrent.atomic.AtomicBoolean(false)
        val held = Gate()
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
            }

            // A save lands after each of the rebuild's first pages.
            override suspend fun page(stream: PlayStream) {
                if (swaps.get() < 6) db.growAndSwap(merge, 31 + swaps.getAndIncrement())
            }

            // One more lands inside the rebuild's apply, which is then held:
            // that one waits for the apply to end.
            override suspend fun applied() {
                if (inApply.compareAndSet(false, true)) db.growAndSwap(merge, 40)
                held.hit()
            }
        }
        // A play since the totals were built, so the rebuild has a page to apply.
        db.playOn("device:s10", NOW - 10)
        rig.totals.rebuild()
        held.reached.await()
        val from = merge.swapStamp.value
        assertTrue("the saves are built through while the rebuild applies", rig.totals.builtThrough.value < from)
        val handle = ListHandle<SortOrder, TrackEntity>(
            scope,
            SortOrder(SongSort.MostPlayed, true),
            { SongLists(db.listsDao()).loader(SongScope.All, it) },
            LibraryReads(merge, rig.totals),
            { _, l -> db.tableChanges(l.tables) },
        )
        Presented(handle.pages, scope).use { shown ->
            // The list's refresh has begun its wait before the rebuild goes on.
            withTimeout(5_000) { while (shown.presenter.loadStateFlow.value?.refresh !is androidx.paging.LoadState.Loading) delay(5) }
            delay(200)
            assertEquals("the list showed before the rebuild ended", 0, handle.presented)
            held.open()
            withTimeoutOrNull(10_000) { while (handle.presented < 1) delay(10) }
                ?: throw AssertionError("the list never showed: ${shown.presenter.loadStateFlow.value}, built ${rig.totals.builtThrough.value} from $from, stamp ${merge.swapStamp.value}")
            assertEquals(6, swaps.get())
            assertTrue("the list showed before the totals were built through its wait's stamp", rig.totals.builtThrough.value >= from)
            assertEquals("a rebuild followed the saves", 1, opened.get())
            assertTrue(shown.size() >= 31)
            val top = (0 until 10).map { shown.get(it)!!.row.id }
            assertEquals("played songs first, the latest first", (0 until 10).map { "device:s$it" }, top)
        }
        rig.totals.builtThrough.first { it >= merge.swapStamp.value }
    }
}
