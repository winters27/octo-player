package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.queueQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Play all and Shuffle all on an album page with finds showing more than
// QUEUE_WINDOW rows start from the page's own copy of the album, one read,
// with no wait for the network; with that copy gone they start from the
// library's songs. The build then makes and walks a copy of its own and
// leaves the page's alone.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class AlbumFindsPlayAllNoWaitTest {
    private val rig = QueueRig(page = 100)
    private val page = WorkOwners.marked("vm:5")

    @After
    fun close() = rig.close()

    private fun pageRows(): Long = rig.run { rig.db.queueDao().number(queueQuery("SELECT COUNT(*) FROM lists_album_entry WHERE owner = ?1", page)) ?: 0 }

    @Test
    fun playAllStartsFromThePagesCopy() {
        val songs = rig.db.songs(700, album = "big").map { it.id }
        rig.run { rig.loaders.buildAlbum(page, "big") }
        assertEquals(700L, pageRows())
        rig.play(PlayRequest(ListSpec.Album("big", withFinds = true), null, pageOwner = page, shownOverWindow = true))
        assertEquals(songs.first(), rig.current())
        rig.joined()
        assertEquals(songs, rig.songs())
        // The page's copy is as it was; the build's own is gone.
        assertEquals(700L, pageRows())
        val (from, until) = WorkOwners.kindRange("queue")
        val queued = rig.run { rig.db.queueDao().number(queueQuery("SELECT COUNT(*) FROM lists_album_entry WHERE owner >= ? AND owner < ?", from, until)) }
        assertEquals(0L, queued)
    }

    @Test
    fun shuffleAllPicksFromThePagesCopy() {
        val songs = rig.db.songs(700, album = "big").map { it.id }
        rig.run { rig.loaders.buildAlbum(page, "big") }
        rig.play(PlayRequest(ListSpec.Album("big", withFinds = true), null, shuffle = true, pageOwner = page, shownOverWindow = true))
        val first = rig.current()!!
        assertTrue(first in songs)
        rig.joined()
        assertEquals(first, rig.songs(QueueOrder.Shuffle).first())
        assertEquals(songs.toSet(), rig.songs().toSet())
    }

    @Test
    fun withThePagesCopyGoneItStartsFromTheLibrary() {
        val songs = rig.db.songs(700, album = "big").map { it.id }
        rig.run {
            rig.loaders.buildAlbum(page, "big")
            rig.db.deleteOwned("lists_album_entry", page)
        }
        rig.play(PlayRequest(ListSpec.Album("big", withFinds = true), null, pageOwner = page, shownOverWindow = true))
        assertEquals(songs.first(), rig.current())
        rig.joined()
        assertEquals(songs, rig.songs())
    }
}
