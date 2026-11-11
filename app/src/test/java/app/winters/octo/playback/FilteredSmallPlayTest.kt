package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
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

// A filtered list of at most QUEUE_WINDOW songs plays whole at once from a
// row tap, Play or Shuffle; a bigger one, which its page says shows more
// than QUEUE_WINDOW, plays its first song at once and joins the rest, in
// the filter's order.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class FilteredSmallPlayTest {
    // The app's window sizes, so a small list stays whole in the player.
    private val rig = QueueRig(sizes = WindowSizes())
    private val rock = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "Rock")))
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    @Test
    fun aSmallFilteredListPlaysWholeAtOnce() {
        val songs = rig.db.songs(2_000, genre = { if (it % 7 == 0) "Rock" else "Jazz" })
        val kept = songs.filter { it.genre == "Rock" }.map { it.id }
        val spec = ListSpec.Songs(SongScope.All, titles, rock)
        rig.play(PlayRequest(spec, ListStart.Row(kept[40], null)))
        rig.settle()
        assertEquals(kept, rig.held())
        assertEquals(kept[40], rig.current())
        rig.play(PlayRequest(spec, null))
        rig.settle()
        assertEquals(kept[0], rig.current())
        rig.play(PlayRequest(spec, null, shuffle = true))
        rig.settle()
        assertEquals(kept.toSet(), rig.held().toSet())
        assertTrue(rig.window.building.value.isEmpty())
    }

    @Test
    fun aBigFilteredListPlaysItsStartAndJoinsTheRest() {
        val songs = rig.db.songs(2_000, genre = { if (it % 2 == 0) "Rock" else "Jazz" })
        val kept = songs.filter { it.genre == "Rock" }.map { it.id }
        val spec = ListSpec.Songs(SongScope.All, titles, rock)
        rig.play(PlayRequest(spec, ListStart.First(kept[0], null), shownOverWindow = true))
        rig.settle()
        assertEquals(kept[0], rig.current())
        rig.joined()
        assertEquals(kept, rig.songs())
    }
}
