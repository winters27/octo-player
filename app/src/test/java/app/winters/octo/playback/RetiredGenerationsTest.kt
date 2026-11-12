package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.QueueAccountEntity
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.QueueListEntity
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Generations no play needs any more go, and nothing remembers them after:
// a retired one leaves the retired set once it is deleted, and a start's
// one sweep deletes what an earlier run left (killed while it deleted a
// generation, or having made one it never took up), never one an account
// keeps, one Clear holds for its Undo, or one this run made.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class RetiredGenerationsTest {
    private fun generations(db: CatalogDatabase): Set<Long> =
        db.query("SELECT queueId FROM queue_entry UNION SELECT queueId FROM queue_list", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toSet()
        }

    @Test
    fun fiftyPlaysLeaveNoDeletedGenerationRetired() {
        val rig = QueueRig()
        try {
            rig.db.songs(20)
            val played = HashSet<Long>()
            repeat(50) { i ->
                rig.play(PlayRequest(ListSpec.Ids(listOf("s${i % 20}", "s${(i + 1) % 20}")), null))
                played += rig.window.queueId
            }
            assertEquals("each play took a generation of its own", 50, played.size)
            rig.until("every replaced generation is deleted") { generations(rig.db) == setOf(rig.window.queueId) }
            rig.settle()
            assertEquals(emptySet<Long>(), rig.store.retired())
        } finally {
            rig.close()
        }
    }

    // What an earlier run left, as its process ended: generation 3 half
    // deleted, 5 made and never taken up, 4 kept for another account, and
    // 7 the queue in use.
    private fun earlierRun(db: CatalogDatabase) = runBlocking {
        db.songs(20)
        db.openHelper.writableDatabase.execSQL("UPDATE queue_meta SET nextQueueId = 100, nextListId = 100")
        val dao = db.queueDao()
        fun rows(queueId: Long, listId: Long, n: Int) = (0 until n).map { i ->
            QueueEntryEntity(queueId, entryOf(listId, i.toLong()), "s$i", listId, playKey(i.toLong()), shuffledKey(listId, playKey(i.toLong())), 1_000)
        }
        fun list(queueId: Long, listId: Long) =
            QueueListEntity(listId, queueId, null, false, null, listId, QueueBuilds.JOINED, null, null, null, 0, 0, null, null, null, 0, null, null, null, null, null)
        dao.putEntries(rows(3, 30, 7))
        dao.putList(list(3, 30))
        dao.putEntries(rows(5, 50, 4))
        dao.putEntries(rows(4, 40, 6))
        dao.putList(list(4, 40))
        dao.putAccount(QueueAccountEntity("other", 4, entryOf(40, 0), 0, 0, false, null, null, null, 1))
        dao.putEntries(rows(7, 70, 9))
        dao.putList(list(7, 70))
        dao.putAccount(QueueAccountEntity("home", 7, entryOf(70, 2), 0, 0, false, null, null, null, 2))
    }

    @Test
    fun theStartsSweepDeletesWhatAnEarlierRunLeft() {
        val db = openTestCatalog()
        earlierRun(db)
        val rig = QueueRig(db)
        try {
            assertEquals(setOf(3L, 4L, 5L, 7L), generations(db))
            val saved = rig.run { rig.window.readSaved("home") }!!
            assertEquals(7L, saved.queueId)
            rig.until("the leftovers are gone") { generations(db) == setOf(4L, 7L) }
            // The parked queue is whole.
            assertEquals(6, rig.run { rig.store.inTurn { rig.store.count(4) } })
            // Once a process: what a later restore finds stays.
            db.queueDao().let { dao -> runBlocking { dao.putEntries(listOf(QueueEntryEntity(2, entryOf(20, 0), "s1", 20, KEY_MID, SK_MID, 1_000))) } }
            rig.run { rig.window.readSaved("home") }
            rig.settle()
            assertTrue(2L in generations(db))
        } finally {
            rig.close()
        }
    }

    @Test
    fun theSweepKeepsWhatClearHoldsAndWhatThisRunMade() {
        val db = openTestCatalog()
        earlierRun(db)
        val rig = QueueRig(db)
        try {
            // A play of this run, written and not taken up yet.
            val guard = PlayGuard({ rig.player.replacements }, { "home" })
            val started = rig.run {
                carStart(rig.window, guard, CarPlay(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Track("s3"), QueueSource.Played("Songs"), network = false), guard.mark())
            }
            assertTrue(started is CarStarted.Items)
            rig.settle()
            val made = generations(db) - setOf(3L, 4L, 5L, 7L)
            assertEquals(1, made.size)
            // Generation 5 is held as Clear holds one for its Undo.
            rig.store.sweepOnce(setOf(5L))
            rig.until("the half deleted one is gone") { 3L !in generations(db) }
            rig.settle()
            assertEquals(setOf(4L, 5L, 7L) + made, generations(db))
        } finally {
            rig.close()
        }
    }
}
