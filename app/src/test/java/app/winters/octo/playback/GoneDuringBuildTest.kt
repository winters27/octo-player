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

// A list read from a copy of its own whose song leaves the library for good
// while the list is being written: that song is left out and every other
// is queued once, with no words of a list cut short.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class GoneDuringBuildTest {
    private val rig = QueueRig(page = 20)

    @After
    fun close() = rig.close()

    @Test
    fun aSongGoneForGoodIsLeftOut() {
        val songs = rig.db.songs(120).map { it.id }
        val gone = songs[90]
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 1) rig.db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = ?", arrayOf<Any?>(gone))
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Rating, true)), null, shownOverWindow = true))
        rig.joined()
        assertEquals(emptyList<String>(), rig.words)
        val queued = rig.songs()
        assertEquals((songs - gone).toSet(), queued.toSet())
        assertEquals(songs.size - 1, queued.size)
    }
}
