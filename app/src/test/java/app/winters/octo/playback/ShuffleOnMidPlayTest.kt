package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

// Turning shuffle on in the middle of a play keeps the song playing first,
// then every other song of the queue once, played ones included, in the
// play's own shuffled order; turning it off goes on in list order from the
// song playing. Whether the list is still being written or joined, and
// after on, off, on at two different songs, no song comes twice ahead.
// The order after the song playing is a random order: the checks run on
// the model the queue is proven to equal.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ShuffleOnMidPlayTest {
    private val rig = QueueRig(random = Random(17))
    private val titles = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))
    private val n = 2_000

    @After
    fun close() = rig.close()

    // The order shuffle on at `current` gives a play of `n` songs started at
    // `start`: that song first, then every other by its key from the seed.
    private fun model(start: Int, current: Int, seed: Long, n: Int = this.n): List<String> =
        listOf("s$current") + (0 until n).filter { it != current }.sortedBy { shuffledKey(seed, playKey(playNumber(it, start))) }.map { "s$it" }

    private fun shuffleOn() {
        rig.player.shuffleModeEnabled = true
        rig.settle()
    }

    private fun shuffleOff() {
        rig.player.shuffleModeEnabled = false
        rig.settle()
    }

    private fun assertOrderAhead(start: Int, current: Int) {
        val seed = rig.lists().first().seed
        val order = rig.songs(QueueOrder.Shuffle)
        assertEquals(model(start, current, seed), order)
        assertEquals("every song once", n, order.toSet().size)
        assertEquals("s$current", rig.current())
    }

    @Test
    fun afterTheJoinTheSongPlayingStaysFirstThenEveryOtherOnce() {
        rig.db.songs(n)
        rig.play(PlayRequest(titles, ListStart.Row("s300", null), shownOverWindow = true))
        rig.joined()
        repeat(5) { rig.next() }
        assertEquals("s305", rig.current())
        shuffleOn()
        assertOrderAhead(300, 305)
        // Next walks that order.
        val order = rig.songs(QueueOrder.Shuffle)
        repeat(4) { i ->
            rig.next()
            assertEquals(order[1 + i], rig.current())
        }
    }

    @Test
    fun whileTheListIsWrittenTheStartStaysFirstThenEveryOtherOnce() {
        rig.db.songs(n)
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 3) gate.await()
            }
        }
        rig.play(PlayRequest(titles, ListStart.Row("s300", null), shownOverWindow = true))
        rig.until("the build is part way") { rig.lists().first().filled0 >= 100 }
        assertTrue(rig.window.building.value.isNotEmpty())
        shuffleOn()
        gate.complete(Unit)
        rig.joined()
        rig.settle()
        assertOrderAhead(300, 300)
        val order = rig.songs(QueueOrder.Shuffle)
        rig.next()
        assertEquals(order[1], rig.current())
    }

    @Test
    fun onOffOnAtTwoSongsPlaysNoSongTwiceAhead() {
        rig.db.songs(n)
        rig.play(PlayRequest(titles, ListStart.Row("s300", null), shownOverWindow = true))
        rig.joined()
        repeat(5) { rig.next() }
        shuffleOn()
        val seed = rig.lists().first().seed
        repeat(3) { rig.next() }
        shuffleOff()
        val x = rig.current()!!
        rig.next()
        val y = rig.current()!!
        assertEquals("s${x.removePrefix("s").toInt() + 1}", y)
        shuffleOn()
        val current = y.removePrefix("s").toInt()
        assertOrderAhead(300, current)
        // The song that held the first key took its own back.
        val s305 = rig.rows().single { it.trackId == "s305" }
        assertEquals(shuffledKey(seed, playKey(playNumber(305, 300))), s305.sk)
        assertNull(s305.skBefore)
        // Ahead in the window and past it, every song once.
        val ahead = rig.songs(QueueOrder.Shuffle).drop(1)
        assertEquals(n - 1, ahead.toSet().size)
        assertTrue(y !in ahead)
        assertTrue(rig.held().toSet().size == rig.held().size)
    }

    // A queue the player holds whole: the player's own order puts the song
    // playing first at once, then follows the keys.
    @Test
    fun aWholeQueueTakesTheNewOrderAtOnce() {
        rig.db.songs(12)
        rig.play(PlayRequest(titles, ListStart.Row("s3", null)))
        rig.settle()
        rig.next()
        assertEquals("s4", rig.current())
        rig.player.shuffleModeEnabled = true
        val playing = rig.player.active
        assertEquals(playing.currentMediaItemIndex, playing.currentTimeline.getFirstWindowIndex(true))
        rig.settle()
        val seed = rig.lists().first().seed
        assertEquals(model(3, 4, seed, 12), rig.songs(QueueOrder.Shuffle))
        val order = rig.songs(QueueOrder.Shuffle)
        for (i in 1 until 12) {
            rig.next()
            assertEquals(order[i], rig.current())
        }
    }

    // The songs the playing player plays, in its own shuffled order.
    private fun playerShuffled(): List<String> {
        val now = rig.player.active
        val timeline = now.currentTimeline
        return generateSequence(timeline.getFirstWindowIndex(true).takeIf { it != C.INDEX_UNSET }) { i ->
            timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true).takeIf { it != C.INDEX_UNSET }
        }.map { now.getMediaItemAt(it).mediaId }.toList()
    }

    // Shuffle off, a song ends, shuffle on, a song ends, all while Room's
    // worker is held: no job of the worker runs, yet the player plays the
    // new order already, the song that held the first key back at its own
    // place; once the worker runs, Room has the same order.
    @Test
    fun offThenOnWithSongsEndingBeforeRoomIsWrittenPlaysInOrder() {
        rig.db.songs(12)
        rig.play(PlayRequest(titles, ListStart.Row("s3", null)))
        rig.settle()
        shuffleOn()
        val seed = rig.lists().first().seed
        val playing = rig.player.active
        // A song ends: the player moves on by itself.
        fun songEnds() {
            playing.seekToNextMediaItem()
            rig.idle()
        }
        songEnds()
        val held = CompletableDeferred<Unit>()
        var ran = 0
        rig.store.post { held.await() }
        rig.store.post { ran++ }
        rig.player.shuffleModeEnabled = false
        rig.idle()
        songEnds()
        val now = rig.current()!!.removePrefix("s").toInt()
        rig.player.shuffleModeEnabled = true
        rig.idle()
        val model = model(3, now, seed, 12)
        assertTrue("the seed leaves s3 out of the second place", model[1] != "s3")
        assertEquals(model, playerShuffled())
        songEnds()
        assertEquals(model[1], rig.current())
        assertEquals("no job of the worker ran", 0, ran)
        held.complete(Unit)
        rig.settle()
        assertEquals(1, ran)
        assertEquals(model, rig.songs(QueueOrder.Shuffle))
        assertEquals(model, playerShuffled())
    }

    // The order after the song playing, on the model: a random order by
    // Brandon's checks and the battery, the song a toggle left first
    // dropped from it.
    @Test
    fun theOrderAfterTheSongPlayingIsRandom() {
        val seeds = Random(0x51)
        val places = Random(0x52)
        val after = Shuffler { size, _ ->
            val all = size + 1
            val start = places.nextInt(all)
            val current = places.nextInt(all)
            val seed = seeds.nextLong()
            (0 until all).filter { it != current }.sortedBy { shuffledKey(seed, playKey(playNumber(it, start))) }
                .map { if (it > current) it - 1 else it }.toIntArray()
        }
        // The whole order shuffle on gives at song 0: it first, the rest after.
        val atFirst = Shuffler { size, _ ->
            val start = places.nextInt(size)
            val seed = seeds.nextLong()
            (listOf(0) + (1 until size).sortedBy { shuffledKey(seed, playKey(playNumber(it, start))) }).toIntArray()
        }
        val failed = ShuffleStatistics.battery(after, atFirst).filterNot { it.accepted }
        assertTrue(failed.joinToString("\n") { "${it.name}: ${it.detail}" }, failed.isEmpty())
        val many = List(200) { after.order(2_000, it) }
        val checks = listOfNotNull(
            OrderChecks.allDifferent(many),
            OrderChecks.correlation(many, 2_000),
            OrderChecks.neighbors(many, 2_000),
            OrderChecks.lagDistances(many, 2_000, 0.001),
            OrderChecks.everyOnce(many, 2_000),
        )
        assertTrue(checks.joinToString("; "), checks.isEmpty())
    }
}
