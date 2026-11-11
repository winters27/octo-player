package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// An album played with its finds is built from a copy of its own, made at
// Play and then left as it is: songs the library renames while the build
// walks it are followed to their new ids as each row is written, and the
// queue holds every row once, in the album's order, from the tapped row.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class AlbumCopyTest {
    private val rig = QueueRig(page = 100)

    // The rows of every queue list's copy in this launch.
    private fun queueCopyRows(): Long? {
        val (from, until) = app.winters.octo.catalog.WorkOwners.kindRange("queue")
        return rig.run { rig.db.queueDao().number(app.winters.octo.catalog.queueQuery("SELECT COUNT(*) FROM lists_album_entry WHERE owner >= ? AND owner < ?", from, until)) }
    }

    @After
    fun close() = rig.close()

    @Test
    fun theBuildWalksItsOwnCopyOnceAndLetsItGo() {
        val songs = rig.db.songs(3_000, album = "big").map { it.id }
        rig.play(PlayRequest(ListSpec.Album("big", withFinds = true), ListStart.Row("s1200", null), shownOverWindow = true))
        assertEquals("s1200", rig.current())
        rig.joined()
        assertEquals(songs, rig.songs())
        assertEquals(0L, queueCopyRows())
        assertTrue(rig.lists().single().copyOwner == null)
    }

    // The fill reads the copy as it was made, keeping a row whose song was
    // renamed since, and follows it at insert.
    @Test
    fun renamesDuringTheBuildAreFollowedAndEveryRowIsQueuedOnce() {
        val songs = rig.db.songs(3_000, album = "big").map { it.id }
        val renamed = (2_000 until 2_050).map { "s$it" }
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 3) renamed.forEach { rig.db.rename(it, "$it-new") }
            }
        }
        rig.play(PlayRequest(ListSpec.Album("big", withFinds = true), ListStart.Row("s1200", null), shownOverWindow = true))
        assertEquals("s1200", rig.current())
        rig.joined()
        val expected = songs.map { if (it in renamed) "$it-new" else it }
        assertEquals(expected, rig.songs())
        // The build read its own copy, which it let go once it joined.
        assertEquals(0L, queueCopyRows())
        assertTrue(rig.lists().single().copyOwner == null)
    }
}
