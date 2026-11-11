package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.listening.timed
import app.winters.octo.offline.putPlaylist
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Labeled guard: each car answer of a playlist pins the set it reads under a
// token of its own, so the playlist's list and one of its parts answered at
// once hold two pins, and neither answer ending lets the other's go.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarPlaylistPinTest {
    private val rig = CarRig()

    @After
    fun close() = rig.close()

    private fun pins() = rig.db.query("SELECT owner FROM playlist_set_pin WHERE playlistId = 'p' ORDER BY owner", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    @Test
    fun aListAndOneOfItsPartsAnsweredAtOnceHoldTwoPins() = timed(60) {
        val songs = rig.db.songs(2_000)
        runBlocking { rig.db.putPlaylist("p", songs.map { it.id }) }
        val node = CarNode.Playlist("p")
        // Each answer in turn reads its first page and holds there.
        val list = runBlocking { rig.pages.paged(node, fresh = false) }!!
        val part = runBlocking { rig.pages.paged(node, fresh = false) }!!
        runBlocking {
            list.listing.loader.after(null, 1)
            part.listing.loader.after(null, 1)
        }
        val held = pins()
        assertEquals(2, held.size)
        assertTrue(held.all { app.winters.octo.catalog.WorkOwners.kindOf(it) == "car" })
        // The list's answer ends; the part's pin stays.
        runBlocking { list.release() }
        assertEquals(1, pins().size)
        runBlocking { part.release() }
        assertEquals(emptyList<String>(), pins())

        // Whole answers, the list and its first part at once: each ends
        // having let go of only its own pin.
        runBlocking {
            val whole = async { rig.answer(node) }
            val first = async { rig.answer(CarNode.Part(0, node)) }
            assertTrue(whole.await().isNotEmpty())
            assertTrue(first.await().isNotEmpty())
        }
        assertEquals(emptyList<String>(), pins())
    }
}
