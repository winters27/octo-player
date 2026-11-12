package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Likes and unlikes while a queue build walks a list ordered by them (the
// Liked page's date order, and Songs in the Liked sort) move nothing in the
// queue: the build walks its own copy of the list, made at Play in an
// order no like moves, so every song the copy read is queued once, in the
// copy's order, none dropped or doubled.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class LikeDuringBuildTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    private fun like(id: String, at: Long) = rig.run { rig.db.userDao().like(LikedTrackEntity(id, "", at)) }

    // Likes, unlikes and an unlike then a like, before each of some pages.
    private fun churnDuringTheBuild() {
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page > 6) return
                val dao = rig.db.userDao()
                dao.unlike("s${page * 7}")
                dao.like(LikedTrackEntity("s${700 + page}", "", 10_000L + page))
                dao.unlike("s${page * 11 + 1}")
                dao.like(LikedTrackEntity("s${page * 11 + 1}", "", 20_000L + page))
            }
        }
    }

    // The list's order as it stands now, by its own loader.
    private fun orderNow(spec: ListSpec.Songs): List<String> = rig.run {
        rig.loaders.songs(spec.scope, spec.order).after(null, 10_000).map { it.row.id }
    }

    @Test
    fun theLikedPagesDateOrder() {
        rig.db.songs(720)
        (0 until 700).forEach { like("s$it", 1_000L + (it * 37) % 700) }
        val spec = ListSpec.Songs(SongScope.Liked, SortOrder(SongSort.DateLiked, true))
        val atPlay = orderNow(spec)
        churnDuringTheBuild()
        rig.play(PlayRequest(spec, ListStart.Row(atPlay[300], null), shownOverWindow = true))
        rig.joined()
        assertEquals(atPlay, rig.songs())
        assertEquals(atPlay.size, rig.songs().toSet().size)
    }

    @Test
    fun songsInTheLikedSort() {
        rig.db.songs(720)
        (0 until 360).forEach { like("s${it * 2}", 1_000L + it) }
        val spec = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Liked, true))
        val atPlay = orderNow(spec)
        churnDuringTheBuild()
        rig.play(PlayRequest(spec, null, shownOverWindow = true))
        rig.joined()
        assertEquals(atPlay, rig.songs())
    }
}
