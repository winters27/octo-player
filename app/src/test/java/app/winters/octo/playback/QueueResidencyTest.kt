package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.counted
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A queue of 6,000 songs holds at most one window in the player and reads
// at most a page (plus a constant) per statement, counted at the database
// for every statement it makes: playing it, walking it with Next, shuffle
// on and off, and bringing it back after a restart. A build counts its rows
// at most twice, never per page.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class QueueResidencyTest {
    private val page = 100
    private val rig = QueueRig(page = page)

    @After
    fun close() = rig.close()

    @Test
    fun aBigQueueHoldsAWindowAndReadsAPageAtATime() {
        rig.db.songs(6_000)
        val log = rig.db.counted
        log.reset()
        log.recording = true
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s3000", null), shownOverWindow = true))
        rig.joined()
        repeat(60) {
            rig.next()
            assertTrue("the player held ${rig.held().size}", rig.held().size <= rig.sizes.window)
        }
        rig.player.shuffleModeEnabled = true
        rig.settle()
        rig.player.shuffleModeEnabled = false
        rig.settle()
        assertTrue(rig.held().size <= rig.sizes.window)
        log.recording = false
        val statements = log.own
        // The record holds what the queue did, not nothing.
        assertTrue("nothing was recorded", statements.size > 50)
        val biggest = statements.maxBy { it.rowsRead }
        assertTrue("one statement read ${biggest.rowsRead} rows: $biggest", biggest.rowsRead <= page + 50 || "FROM track" in biggest.sql && biggest.rowsRead <= QUEUE_WINDOW + 1)
        // Counts of the whole generation (not the build's one-song probe).
        val counts = statements.count { app.winters.octo.catalog.QUEUE_COUNT_SQL in it.sql }
        assertTrue("the queue was counted $counts times", counts <= 12)
    }

    @Test
    fun aRestoreReadsOneWindow() {
        rig.db.songs(6_000)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s4000", null), shownOverWindow = true))
        rig.joined()
        rig.window.saveState()
        rig.settle()
        val later = QueueRig(rig.db)
        try {
            val log = rig.db.counted
            log.reset()
            log.recording = true
            val saved = later.run { later.window.readSaved("home") }!!
            assertTrue(later.window.restore(saved, "home"))
            later.settle()
            assertTrue(later.held().size <= later.sizes.window)
            assertTrue("s4000" == later.current())
            val statements = log.own
            assertTrue("nothing was recorded", statements.isNotEmpty())
            assertTrue(statements.all { it.rowsRead <= QUEUE_WINDOW + 1 })
        } finally {
            rig.db.counted.recording = false
            later.window.release()
        }
    }
}
