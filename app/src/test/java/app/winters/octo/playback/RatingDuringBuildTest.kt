package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Rating taps while a queue build walks a list in the Rating sort move
// nothing in the queue: the build walks its own copy, made at Play, so
// every song is queued once, in the copy's order.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class RatingDuringBuildTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun ratingTapsDuringTheBuildMoveNothing() {
        rig.db.songs(800)
        rig.run { (0 until 800).forEach { rig.db.userDao().showRating("s$it", it % 6) } }
        val spec = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Rating, true))
        val atPlay = rig.run { rig.loaders.songs(spec.scope, spec.order).after(null, 10_000).map { it.row.id } }
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page < 8) {
                    rig.db.userDao().showRating("s${page * 13}", 5 - (page % 6))
                    rig.db.userDao().showRating("s${799 - page}", 0)
                }
            }
        }
        rig.play(PlayRequest(spec, ListStart.Row(atPlay[400], null), shownOverWindow = true))
        rig.joined()
        assertEquals(atPlay, rig.songs())
        assertEquals(800, rig.songs().toSet().size)
    }
}
