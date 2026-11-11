package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// After a restart the queue comes back as a window around the saved song,
// at the saved place, shuffle and repeat as they were; the rest stays in
// Room, and Next walks on into it. With the saved song gone it starts at
// the queue's first song. A switch of server leaves each account's queue
// in Room and brings it back with its account.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class RestoreWindowTest {
    private val rig = QueueRig()
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    private fun restart(account: String = "home"): QueueRig {
        val later = QueueRig(rig.db, sizes = rig.sizes)
        val saved = later.run { later.window.readSaved(account) } ?: return later
        assertTrue(later.window.restore(saved, account))
        later.settle()
        return later
    }

    @Test
    fun aRestartBringsBackAWindowAroundTheSavedSong() {
        val songs = rig.db.songs(2_000).map { it.id }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s1000", null), shownOverWindow = true))
        rig.joined()
        repeat(3) { rig.next() }
        assertEquals("s1003", rig.current())
        rig.window.saveState()
        rig.settle()
        val later = restart()
        try {
            assertEquals("s1003", later.current())
            assertTrue(later.held().size <= later.sizes.window)
            assertTrue(later.held().contains("s1004"))
            assertEquals(songs, later.songs())
            later.next()
            assertEquals("s1004", later.current())
        } finally {
            later.window.release()
        }
    }

    @Test
    fun aSavedSongGoneStartsAtTheQueuesFirst() {
        rig.db.songs(40)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s20", null)))
        rig.settle()
        rig.window.saveState()
        rig.settle()
        rig.run { rig.db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = 's20'") }
        val later = restart()
        try {
            assertEquals("s0", later.current())
        } finally {
            later.window.release()
        }
    }

    @Test
    fun eachAccountsQueueWaitsForIt() {
        rig.db.songs(30)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s5", null)))
        rig.settle()
        rig.window.saveState()
        rig.settle()
        // Leaving for another server keeps "home"'s queue as it was.
        assertTrue(rig.run { rig.store.handOver("home", "away", keepWhenNone = false) })
        assertTrue(rig.run { rig.store.account("away") } == null)
        val back = restart("home")
        try {
            assertEquals("s5", back.current())
        } finally {
            back.window.release()
        }
        // Signing in where the phone's queue plays keeps it as the server's.
        assertTrue(!rig.run { rig.store.handOver("home", "new", keepWhenNone = true) })
        assertTrue(rig.run { rig.store.account("new") } != null)
    }
}
