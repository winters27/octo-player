package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.QueueAccountEntity
import app.winters.octo.catalog.QueueEntryEntity
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

// The queue an upgrade moved into Room (generation 1, one list, the account
// named '') comes back as a window around its saved song, under the
// account in use, and Next walks on through it. The server's view of a big
// queue is the songs around the current one, at most SERVER_VIEW_SPAN each
// way, with the current one's place.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class LegacyQueueIsWindowTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun theMigratedQueueIsAWindow() {
        rig.db.songs(2_000)
        val dao = rig.db.queueDao()
        rig.run {
            dao.putList(StoredList(1, null, false).row(1, 0))
            (0 until 2_000).chunked(500).forEach { part ->
                dao.putEntries(part.map { n -> QueueEntryEntity(1, entryOf(1, n.toLong()), "s$n", 1, playKey(n.toLong()), SK_MID + n * KEY_STEP, 1_000) })
            }
            dao.putAccount(QueueAccountEntity("", 1, entryOf(1, 1_200), 3_000, 0, false, null, null, null, 0))
        }
        val saved = rig.run { rig.window.readSaved("home") }!!
        assertTrue(rig.window.restore(saved, "home"))
        rig.settle()
        assertEquals("s1200", rig.current())
        assertTrue(rig.held().size <= rig.sizes.window)
        repeat(20) { i ->
            rig.next()
            assertEquals("s${1_201 + i}", rig.current())
        }
        rig.until("the account is the one in use") { rig.run { rig.store.account("home") } != null }
    }

    @Test
    fun theServersViewIsTheSongsAroundTheCurrentOne() {
        rig.db.songs(5_000)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), ListStart.Row("s2500", null), shownOverWindow = true))
        rig.joined()
        val view = rig.run { rig.window.serverView() }
        assertEquals((2_500 - SERVER_VIEW_SPAN..2_500 + SERVER_VIEW_SPAN).map { "s$it" }, view.trackIds)
        assertEquals(SERVER_VIEW_SPAN, view.index)
    }
}
