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

// Play next many times at one place runs the gap between two keys out: the
// keys around it are spaced again, and every song keeps its place, in both
// orders, each key apart from the next.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class KeyRespaceTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun aCrowdedPlaceIsSpacedAgainAndKeepsItsOrder() {
        rig.db.songs(100)
        rig.db.songs(80, album = "x", prefix = "x")
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), ListStart.Row("s10", null), shownOverWindow = true))
        rig.joined()
        for (i in 0 until 80) rig.run { rig.window.addList(ListSpec.Ids(listOf("x$i")), next = true, source = null) }
        rig.until("every Play next is in Room") { rig.songs().size == 180 }
        val songs = rig.songs()
        // Each Play next goes right after the song that is on.
        assertEquals((0..10).map { "s$it" } + (79 downTo 0).map { "x$it" } + (11 until 100).map { "s$it" }, songs)
        for (order in QueueOrder.values()) {
            val keys = rig.rows(order).map { it.key(order) }
            assertTrue("$order keys apart", keys.zipWithNext().all { (a, b) -> b > a })
        }
    }
}
