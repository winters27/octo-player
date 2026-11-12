package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.discovery.ListServer
import app.winters.octo.listening.timed
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.Song
import app.winters.octo.ui.album.albumOnServer
import app.winters.octo.ui.album.found
import app.winters.octo.ui.album.listed
import app.winters.octo.ui.paging.walked
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A library album the car opens and plays keeps nothing album-sized in
// the car: with finds, the server's listing is read into the car's own
// owner and CarLists keeps only that owner and whether it holds a find; the
// answer stays in budget, and the play names the album, started from the
// car's listing. With no find, nothing is written at all, and the album
// reads and plays as the library has it.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarAlbumNoCacheTest {
    private val n = 3_000

    private fun rig(finds: Boolean): CarRig {
        val listing = (0 until n).map { "s$it" } + if (finds) (0 until 30).map { "f$it" } else emptyList()
        val songs: Map<String, Song> = (0 until n).associate { "s$it" to listed(it) } + (0 until 30).associate { "f$it" to found(it) }
        val server = ListServer(songs)
        val rig = CarRig(server = server)
        runBlocking { rig.db.albumOnServer(n, serverSourceId(server.client().primaryUrl)) { 1 } }
        // The listing in the server's order, a find every hundred songs.
        server.albums["srv"] = "Big" to if (!finds) listing else (0 until n).flatMap { i -> listOfNotNull("s$i", if (i % 100 == 50) "f${i / 100}" else null) }
        return rig
    }

    private fun copyRows(rig: CarRig) = rig.db.query("SELECT COUNT(*) FROM lists_album_entry", null).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun anAlbumWithFindsKeepsOnlyItsOwnerInTheCar() = timed(90) {
        rig(finds = true).use { rig ->
            val node = CarNode.Album("al")
            val answer = runBlocking { rig.answer(node, fresh = true) }
            assertTrue(answer.isNotEmpty())
            assertTrue("the answer stays in budget", answer.sumOf(::carBytes) <= rig.budget)
            assertTrue("the listing is read into the car's owner", copyRows(rig) > n)
            // Nothing album-sized is kept: only an owner and a yes.
            val kept = runBlocking { rig.lists.keptFor<Any>(node) }
            assertTrue("kept $kept", kept is CarOwned<*> && kept.value == true)
            assertEquals(1, runBlocking { rig.lists.ownedCount() })
            assertEquals(0, runBlocking { rig.lists.builtCount() })
            // The play names the album, from the listing the car shows.
            val play = runBlocking { rig.pages.albumPlay(node, "Big") }
            assertEquals(ListSpec.Album("al", withFinds = true), play.spec)
            assertTrue(play.shownOverWindow)
            assertEquals((kept as CarOwned<*>).owner, play.pageOwner)
            // Once the car lets it go, its rows go.
            runBlocking {
                rig.lists.clear()
                rig.scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
            }
            assertEquals(0, copyRows(rig))
        }
    }

    @Test
    fun anAlbumWithNoFindWritesNothingAndReadsAsTheLibrary() = timed(90) {
        rig(finds = false).use { rig ->
            val node = CarNode.Album("al")
            val answer = runBlocking { rig.answer(node, fresh = true) }
            assertTrue(answer.isNotEmpty())
            assertEquals("no copy for a listing with no find", 0, copyRows(rig))
            // The car's rows are the library album's own.
            val library = runBlocking { rig.loaders.albumLibrary("al").walked() }
            val play = runBlocking { rig.pages.albumPlay(node, "Big") }
            assertEquals(ListSpec.Album("al", withFinds = false), play.spec)
            assertEquals(null, play.pageOwner)
            assertEquals(n, library.size)
            assertEquals(0, copyRows(rig))
        }
    }
}
