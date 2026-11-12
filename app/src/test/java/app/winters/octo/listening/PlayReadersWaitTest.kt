package app.winters.octo.listening

import android.database.sqlite.SQLiteFullException
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Song info's plays and Autoplay's recent songs follow the reader rule:
// before the totals are first built they wait, never answer from nothing;
// after a save they answer at once by the new ids, its groups having kept
// the totals, with no rebuild after it; while the totals failed halfway through an apply they end at
// once with the words. (A radio's seed reads the stored totals with no
// wait, RadioSeedFirstTest.)
@RunWith(RobolectricTestRunner::class)
class PlayReadersWaitTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private suspend fun TotalsRig.readAll(): List<Any?> = coroutineScope {
        listOf(
            async { history.played("t00001")?.plays },
            async { history.recentlyPlayedIds(5) },
        ).awaitAll()
    }

    @Test
    fun theyWaitForTheFirstBuild() = timed {
        db.fillPlays(seed = 141, songs = 200, albums = 10)
        val first = Gate()
        val rig = TotalsRig(db, db.testMerge(HOME))
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() = first.hit()
        }
        rig.totals.start()
        first.reached.await()
        coroutineScope {
            val answers = async { rig.readAll() }
            assertNull(withTimeoutOrNull(300) { answers.await() })
            first.open()
            val read = answers.await()
            assertEquals(TodayPlays(db, "home").tracks().firstOrNull { it.track.id == "t00001" }?.plays, read[0])
            assertEquals(5, (read[1] as List<*>).size)
        }
    }

    @Test
    fun afterASaveTheyAnswerAtOnceWithNoRebuild() = timed {
        val merge = db.mergedLibrary(30)
        repeat(5) { db.playOn("device:s3", NOW - it) }
        val rig = TotalsRig(db, merge).started()
        val opened = AtomicInteger()
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
            }
        }
        db.growAndSwap(merge, 31)
        // Each group of the save kept the totals and marked them built
        // through its stamp, so the readers wait for no rebuild.
        assertEquals(merge.swapStamp.value, rig.totals.builtThrough.first { it >= merge.swapStamp.value })
        assertEquals(5, withTimeoutOrNull(10_000) { rig.history.played("device:s3")?.plays })
        assertEquals(listOf("device:s3"), withTimeoutOrNull(10_000) { rig.history.recentlyPlayedIds(1) })
        assertEquals("a rebuild followed the save", 0, opened.get())
    }

    @Test
    fun whileTheTotalsFailedHalfwayTheyEndWithTheWords() = timed {
        db.fillPlays(seed = 142, songs = 200, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME), page = 2).started()
        val full = AtomicBoolean(true)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun applied() {
                if (full.get()) throw SQLiteFullException("database or disk is full")
            }
        }
        repeat(10) { db.playOn("t%05d".format(it), NOW + it) }
        rig.totals.rebuild()
        rig.totals.failed.first { it }
        val words = "Not enough space to update play counts. Free up some space, then try again."
        assertEquals(TotalsAnswer.Problem(words), answer { rig.history.played("t00001") })
        assertEquals(TotalsAnswer.Problem(words), answer { rig.history.recentlyPlayedIds(5) })
        full.set(false)
        rig.totals.tryAgain()
        rig.totals.failed.first { !it }
        assertEquals(5, value(answer { rig.history.recentlyPlayedIds(5) }).size)
    }
}
