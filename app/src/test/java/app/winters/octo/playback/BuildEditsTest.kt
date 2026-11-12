package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.queueQuery
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// What the listener does while a list is still being added: Play next goes
// into the player at once and into Room at the join, in its place; a new
// play stops the build and its queue goes; a big Play next takes the songs
// after its place out of the player until it joins, so Next plays it first,
// and its Undo stops it and gives them back; a build that fails or runs out
// of room joins with the songs it wrote and says so.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class BuildEditsTest {
    private val titles = SortOrder(SongSort.Title, false)
    private var rig = QueueRig()

    @After
    fun close() = rig.close()

    private fun gated(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page >= 1) gate.await()
            }
        }
        return gate
    }

    private fun rowsOf(queueId: Long): Long = rig.run { rig.db.queueDao().number(queueQuery("SELECT COUNT(*) FROM queue_entry WHERE queueId = ?1", queueId)) ?: 0 }

    @Test
    fun playNextDuringABuildLandsAfterTheStartEverywhere() {
        rig.db.songs(600)
        rig.db.songs(1, album = "x", prefix = "x")
        val gate = gated()
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), ListStart.Row("s150", null), shownOverWindow = true))
        rig.settle()
        rig.run { rig.window.addList(ListSpec.Ids(listOf("x0")), next = true, source = null) }
        rig.settle()
        assertEquals(listOf("s150", "x0"), rig.held())
        gate.complete(Unit)
        rig.joined()
        val songs = rig.songs()
        assertEquals(601, songs.size)
        assertEquals("x0", songs[151])
        assertEquals("s151", songs[152])
    }

    @Test
    fun aPlayDuringABuildStopsItAndItsQueueGoes() {
        rig.db.songs(400)
        gated()
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s10", null), shownOverWindow = true))
        val old = rig.window.queueId
        rig.until("the old build wrote a page") { rowsOf(old) > 1 }
        rig.play(PlayRequest(ListSpec.Ids(listOf("s1", "s2")), null))
        rig.until("the old queue is gone") { rowsOf(old) == 0L }
        assertEquals(listOf("s1", "s2"), rig.songs())
    }

    @Test
    fun aBigPlayNextPlaysFirstAndItsUndoGivesTheSongsBack() {
        rig.db.songs(40)
        rig.db.songs(700, album = "other", prefix = "o")
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), ListStart.Row("s0", null)))
        rig.settle()
        val gate = gated()
        val added = rig.run { rig.window.addList(ListSpec.Album("other", withFinds = false), next = true, source = null) }!!
        rig.settle()
        assertTrue(added.building)
        // The songs after its place leave the player until it joins.
        assertEquals(listOf("s0"), rig.held())
        rig.window.removeList(added.listId)
        rig.settle()
        gate.complete(Unit)
        rig.until("the songs came back") { "s1" in rig.held() }
        assertEquals((0 until 40).map { "s$it" }, rig.songs())
        // Again, now let it join: Next plays the added album first.
        rig.run { rig.window.addList(ListSpec.Album("other", withFinds = false), next = true, source = null) }
        rig.joined()
        rig.next()
        assertEquals("o0", rig.current())
        val songs = rig.songs()
        assertEquals("o699", songs[700])
        assertEquals("s1", songs[701])
    }

    @Test
    fun aFailedBuildJoinsWithWhatItWroteAndSaysSo() {
        rig.db.songs(400)
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 3) error("the loader failed")
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shownOverWindow = true))
        rig.joined()
        assertEquals((0 until 151).map { "s$it" }, rig.songs())
        rig.until("the words show") { rig.words.any { it.startsWith("Couldn't add every song to the queue. 151 of 400") } }
    }

    @Test
    fun aShortDiskJoinsWithTheStartAndTheSpaceWords() {
        rig.close()
        rig = QueueRig(space = { 1_000 })
        rig.db.songs(400)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shownOverWindow = true))
        rig.joined()
        assertEquals(listOf("s0"), rig.songs())
        rig.until("the words show") { rig.words.any { it.startsWith("Not enough space to queue every song.") } }
    }
}
