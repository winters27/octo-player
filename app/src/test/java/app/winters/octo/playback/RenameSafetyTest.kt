package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.QueueAccountEntity
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.QueueListEntity
import app.winters.octo.catalog.renameLikeASave
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Songs a merge renames keep their places in the queue. A row the window
// reads before the heal reached it is followed to the song it is now, not
// dropped. A rename that leaves a list that plays each song once holding a
// song twice keeps the one first in the order that plays (the other goes
// from the player and Room), never the song playing; a playlist keeps
// both. The swap leaves such a row to the heal.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class RenameSafetyTest {
    private val rig = QueueRig(random = java.util.Random(9))
    private val titles = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))

    @After
    fun close() = rig.close()

    private fun playBig(from: String = "s0") {
        rig.db.songs(200)
        rig.play(PlayRequest(titles, ListStart.Row(from, null), shownOverWindow = true))
        rig.joined()
        rig.settle()
    }

    @Test
    fun aRowTheWindowReadsBeforeTheHealIsFollowedNotDropped() {
        playBig()
        // A skipped merge's relink: the song's id changed, the heal not yet run.
        rig.run { rig.db.rename("s100", "s100-new") }
        val entry = rig.rows().single { it.trackId == "s100" }.entry
        rig.window.playEntry(entry)
        rig.until("the renamed song plays") { rig.current() == "s100-new" }
        rig.settle()
        val songs = rig.songs()
        assertEquals(200, songs.size)
        assertEquals("s100-new", songs[100])
        assertTrue("s100" !in songs)
    }

    // Merges `from` into `into`, as a swap leaves the library, and heals.
    private fun merge(from: String, into: String) {
        rig.run {
            rig.db.mergeInto(from, into)
            rig.db.workDao().raiseSwapCount()
        }
        healed()
    }

    private fun healed() {
        rig.window.swapped()
        rig.until("the heal pass ran") { rig.run { rig.db.queueDao().accounts().single().healedAt } == rig.run { rig.db.workDao().swapCount() } }
        rig.settle()
    }

    @Test
    fun twoSongsMergedPlayOnceTheFirstInPlayOrderKept() {
        playBig()
        // The build stored what its loader says.
        assertTrue(rig.lists().single().songsOnce)
        val kept = rig.rows().single { it.trackId == "s50" }.entry
        merge("s150", "s50")
        val rows = rig.rows()
        assertEquals(199, rows.size)
        assertEquals(listOf(kept), rows.filter { it.trackId == "s50" }.map { it.entry })
        assertEquals(199, rig.songs().toSet().size)
    }

    @Test
    fun withShuffleOnTheFirstInShuffledOrderIsKept() {
        playBig()
        rig.player.shuffleModeEnabled = true
        rig.settle()
        val (a, b) = rig.rows().filter { it.trackId == "s50" || it.trackId == "s150" }.let { r -> r[0] to r[1] }
        val first = if (a.sk < b.sk) a else b
        merge("s150", "s50")
        val rows = rig.rows()
        assertEquals(199, rows.size)
        assertEquals(listOf(first.entry), rows.filter { it.trackId == "s50" }.map { it.entry })
    }

    @Test
    fun theSongPlayingIsNeverTheOneThatGoes() {
        playBig()
        val playing = rig.rows().single { it.trackId == "s150" }.entry
        rig.window.playEntry(playing)
        rig.until("s150 plays") { rig.current() == "s150" }
        rig.settle()
        merge("s150", "s50")
        val rows = rig.rows()
        assertEquals(199, rows.size)
        assertEquals(listOf(playing), rows.filter { it.trackId == "s50" }.map { it.entry })
        assertEquals("s150", rig.current())
    }

    @Test
    fun aPlaylistOfSongsNamedOneByOneKeepsBoth() {
        rig.db.songs(5)
        rig.play(PlayRequest(ListSpec.Ids(listOf("s1", "s2", "s3")), null))
        rig.settle()
        assertTrue(!rig.lists().single().songsOnce)
        merge("s3", "s1")
        assertEquals(listOf("s1", "s2", "s1"), rig.songs())
    }

    // A queue kept for another account heals as it comes back; one that
    // nothing plays loses the later row at once.
    @Test
    fun aGenerationNotInUseKeepsTheEarlierRowAtOnce() {
        rig.db.songs(10)
        val dao = rig.db.queueDao()
        runBlocking {
            dao.putList(QueueListEntity(90, 9, null, false, titles.toJson(), 1, QueueBuilds.JOINED, null, null, null, 0, 0, null, null, null, 0, null, null, null, null, null, songsOnce = true))
            dao.putEntries((0 until 10).map { i -> QueueEntryEntity(9, entryOf(90, i.toLong()), "s$i", 90, playKey(i.toLong()), shuffledKey(1, playKey(i.toLong())), 1_000) })
            dao.putAccount(QueueAccountEntity("other", 9, entryOf(90, 0), 0, 0, false, null, null, null, 1))
            rig.db.mergeInto("s7", "s2")
        }
        rig.store.healRange(9, Long.MIN_VALUE, Long.MAX_VALUE, 1, emptyList(), null)
        rig.settle()
        val rows = runBlocking { rig.store.inTurn { rig.store.page(9, QueueOrder.Play, null, true, 100, Floors.NONE) } }
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s8", "s9"), rows.map { it.trackId })
        assertEquals(entryOf(90, 2), rows.single { it.trackId == "s2" }.entry)
    }

    // A save's rename moves a queued song whose list holds the song it
    // becomes no further (the queue's guard): the heal does, keeping one.
    @Test
    fun theSwapLeavesADoublingRenameToTheHeal() {
        playBig()
        // Song s150 also queued on its own, by Add to queue.
        rig.run { rig.window.addList(ListSpec.Ids(listOf("s150")), next = false, source = null) }
        rig.settle()
        runBlocking {
            rig.db.mergeInto("s150", "s50")
            rig.db.renameLikeASave("s150", "s50")
        }
        val beforeHeal = rig.rows()
        // The added row has a list of its own and follows at once; the
        // big list's row waits for the heal.
        assertEquals(1, beforeHeal.count { it.trackId == "s150" })
        assertEquals(2, beforeHeal.count { it.trackId == "s50" })
        healed()
        val rows = rig.rows()
        assertEquals(201 - 1, rows.size)
        assertEquals(2, rows.count { it.trackId == "s50" })
        assertEquals(0, rows.count { it.trackId == "s150" })
    }
}
