package app.winters.octo.catalog

import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// A server's first copy into an empty library is saved in groups like any
// copy: more than one, each within the bound by what it measures, its
// progress going from none to every group planned, and the library it
// leaves exactly today's swap's.
@RunWith(RobolectricTestRunner::class)
class FirstCopyGroupsTest {
    private val server = "server:first.test"

    @Test
    fun aFirstCopyIsSavedInBoundedGroups() = runBlocking { withTimeout(120_000) { check() } }

    private suspend fun CoroutineScope.check() {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val start = openTestCatalog()
            try {
                val songs = if (bound == SAVE_GROUP_FLOOR) 40 else 600
                start.stage(library(server, songs), generation = 1)
                val ours = copyCatalog(start)
                val today = copyCatalog(start)
                try {
                    val hooks = GroupHooks()
                    val merge = ours.boundMerge(server, bound, hooks)
                    val progress = CopyOnWriteArrayList<ApplyProgress?>()
                    val watching = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { merge.applyProgress.collect { progress += it } }
                    val (_, claim) = claimAndBuild(ours, server)
                    val (_, ran) = ours.counted.record { runBlocking { merge.saveBuilt(claim.sources, claim.staged, server) } }
                    watching.cancel()
                    val groups = hooks.saves.single()
                    assertTrue("bound $bound: $groups groups", groups > 1)
                    val done = progress.filterNotNull()
                    assertTrue("bound $bound: no progress", done.isNotEmpty())
                    assertTrue("bound $bound: progress $done", done.zipWithNext().all { (a, b) -> b.done > a.done } && done.last().done == done.last().of)
                    assertEquals(null, merge.applyProgress.value)
                    // Each group's transaction, measured, within the bound.
                    val measure = Measure(ours.openHelper.writableDatabase)
                    val transactions = ours.counted.transactions.filter { it.label == SAVE_APPLY }.sortedBy { it.beginSeq }
                    val begins = transactions.map { it.beginSeq } + Long.MAX_VALUE
                    var measured = 0
                    for (i in transactions.indices) {
                        val inside = ran.filter { it.inTransaction && !it.bookkeeping && it.seq > begins[i] && it.seq < begins[i + 1] && it.label == SAVE_APPLY }
                        val cost = measure.of(inside)
                        assertTrue("bound $bound: transaction $i costs ${cost.total} (${cost.reads} reads, ${cost.writes} writes)", cost.total <= bound)
                        if (inside.isNotEmpty()) measured++
                    }
                    assertTrue("bound $bound: $measured measured", measured >= groups)
                    val (todayClaim, _) = claimAndBuild(today, server)
                    todaySwap(today, todayClaim)
                    todayAdoptions(today)
                    todayKeys(today)
                    val differences = SaveOracle.differences(SaveOracle.image(today.openHelper.writableDatabase), SaveOracle.image(ours.openHelper.writableDatabase))
                    assertTrue("bound $bound: ${differences.joinToString("\n")}", differences.isEmpty())
                    assertEquals(songs.toLong(), ours.longOf("SELECT COUNT(*) FROM track"))
                } finally {
                    ours.close()
                    today.close()
                }
            } finally {
                start.close()
            }
        }
    }
}
