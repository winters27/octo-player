package app.winters.octo.server

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.GroupHooks
import app.winters.octo.catalog.LaunchMark
import app.winters.octo.catalog.MERGE_WORK_CLEAR
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.boundMerge
import app.winters.octo.catalog.killImage
import app.winters.octo.subsonic.Starred
import app.winters.octo.testing.FakeSubsonic
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

private const val SOURCE = "server:progress.test"
private const val ACCOUNT = "acc-progress"

// A copy's Save step moves through its share as the save's groups go in,
// and never moves back: not when the save stops after a group, not across
// the resume a stop starts, nor across a resume after the app's end. A
// resume's run starts where the stop left the bar, and the done copy's
// record and counts are the resume's.
@RunWith(RobolectricTestRunner::class)
class SaveStepProgressTest {
    // The fractions a tracker's runs showed, in order, with their steps.
    private class Shown(tracker: CopyTracker) {
        val states: MutableList<CopyRunState> = Collections.synchronizedList(ArrayList())

        init {
            tracker.seen = { it?.let(states::add) }
        }

        val fractions: List<Float> get() = synchronized(states) { states.mapNotNull { it.fraction } }
    }

    // Resumes shown on `tracker` and finished as the server side finishes
    // them; `done` hears each one finished.
    private fun resumes(db: CatalogDatabase, tracker: CopyTracker, copy: StagedCopy, done: CompletableDeferred<CopyDone>) = CopyResumes(
        tracker,
        nameOf = { "Home" },
        began = {},
        failed = {},
        finish = { resumed, token, pass, report ->
            val finished = runBlocking {
                CopyRun(copy, db.copyDao(), emptyList(), clock = { 42L }).complete(resumed.account, resumed.sourceId, copied = true, resumed.scan, swapped = {}, report)
            }
            report.staged(finished.songs, finished.albums)
            tracker.passDone(token, pass)
            tracker.end(token)
            done.complete(finished)
        },
    )

    @Test
    fun theSaveStepNeverMovesBackAcrossAStopAResumeAndAKill() = runBlocking {
        withTimeout(120_000) {
            for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
                val where = "bound $bound"
                val shape = if (bound == SAVE_GROUP_FLOOR) GenShape(songs = 60, albums = 6, artists = 3) else GenShape(songs = 900, albums = 60, artists = 12)
                val first = FakeSubsonic(LibraryGen.server(11, shape), Starred())
                val second = FakeSubsonic(LibraryGen.server(12, shape), Starred())
                val db = openTestCatalog()
                var image: File? = null
                try {
                    val hooks = GroupHooks()
                    val merge = db.boundMerge(SOURCE, bound, hooks)
                    val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE })
                    copy.copy(ACCOUNT, SOURCE, first.client())
                    merge.awaitClear(MERGE_WORK_CLEAR)
                    merge.awaitStageClear(SOURCE)

                    val tracker = CopyTracker()
                    val shown = Shown(tracker)
                    val resumed = CompletableDeferred<CopyDone>()
                    merge.resumes = resumes(db, tracker, copy, resumed)
                    // The second copy's save stops once, at the first park
                    // after a whole group is in; a file image is taken at
                    // that park.
                    val armed = AtomicBoolean(false)
                    val thrown = AtomicBoolean(false)
                    var atImage: Float? = null
                    var parks = 0
                    hooks.between = { _ ->
                        parks++
                        if (!armed.get() && (merge.applyProgress.value?.done ?: 0L) >= 1L) {
                            atImage = tracker.run.value?.fraction
                            image = killImage(db)
                            armed.set(true)
                        }
                    }
                    hooks.inside = { _ -> if (armed.get() && thrown.compareAndSet(false, true)) throw IllegalStateException("a group broke") }
                    val token = tracker.begin(ACCOUNT, "Home", arriving = false, whole = true)
                    val failure = runCatching {
                        copyPass(tracker, token, arriving = { false }, guess = true, plan = { true }) { _, report -> copy.copy(ACCOUNT, SOURCE, second.client(), report, scan = "scan-2") }
                    }.exceptionOrNull()
                    assertTrue("$where: the save did not stop: $failure", failure != null)
                    val done = withTimeout(30_000) { resumed.await() }
                    assertTrue("$where: $parks parks", parks >= 3)
                    val fractions = shown.fractions
                    assertTrue("$where: the bar moved back: $fractions", fractions.zipWithNext().all { (a, b) -> b >= a })
                    val saves = synchronized(shown.states) { shown.states.filter { it.step == CopyStep.Save && it.of != null } }
                    assertTrue("$where: the Save step never moved: $saves", saves.map { it.fraction }.distinct().size >= 3)
                    // The resume's run starts where the stop left the bar.
                    val resumedRun = saves.first { it.token != token }
                    val stoppedAt = saves.last { it.token == token }.fraction!!
                    assertEquals("$where: the resume started elsewhere", stoppedAt, resumedRun.fraction!!, 1e-6f)
                    assertEquals(1f, fractions.last(), 0f)
                    assertEquals(done.songs.toLong(), db.copyDao().liveTrackCount(SOURCE).toLong())
                    assertEquals("scan-2", db.copyDao().accountSync(ACCOUNT)?.lastScan)
                    val arrived = shown.states.last()
                    assertEquals(done.songs, arrived.songs)

                    // The app's end at the image: the next launch's resume
                    // starts no lower than the bar was there.
                    LaunchMark.next()
                    val again = openTestCatalog(image!!)
                    try {
                        val tracker2 = CopyTracker()
                        val shown2 = Shown(tracker2)
                        val merge2 = again.boundMerge(SOURCE, bound)
                        val resumed2 = CompletableDeferred<CopyDone>()
                        merge2.resumes = resumes(again, tracker2, StagedCopy(again, merge2, FreeSpace { Long.MAX_VALUE }), resumed2)
                        merge2.repair()
                        withTimeout(30_000) { resumed2.await() }
                        val after = shown2.fractions
                        assertTrue("$where: no bar after the kill", after.isNotEmpty())
                        assertTrue("$where: the bar after the kill started at ${after.first()}, below ${atImage}", after.first() >= (atImage ?: 0f))
                        assertTrue("$where: the bar moved back after the kill: $after", after.zipWithNext().all { (a, b) -> b >= a })
                        assertEquals(1f, after.last(), 0f)
                    } finally {
                        again.close()
                    }
                } finally {
                    first.server.close()
                    second.server.close()
                    db.close()
                }
            }
        }
    }
}
