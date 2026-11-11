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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A car's choice plays its list through the queue, by what the list is:
// Media3 is handed the whole list when it is small, else the chosen song
// alone, read with no write; the queue is taken up when Media3 sets them,
// and a big list's rest is written behind. A choice that holds nothing is
// refused with its own words and the queue stays as it was; one a newer
// choice overtook is dropped. A choice keeps the player's shuffle, and a
// spoken one sets its own with its songs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class CarStartsTest {
    private val rig = QueueRig()
    private val guard = PlayGuard({ rig.player.replacements }, { "home" })
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    private fun start(play: CarPlay, mark: PlayMark = guard.mark()): CarStarted =
        rig.run { carStart(rig.window, guard, play, mark) }

    // As Media3 sets a car's play.
    private fun apply(started: CarStarted.Items) {
        rig.player.setMediaItems(started.items, started.start, started.positionMs)
        rig.player.prepare()
        rig.settle()
    }

    @Test
    fun aSongChosenInABigListPlaysAloneThenTheListJoins() {
        val songs = rig.db.songs(QUEUE_WINDOW + 100)
        val play = CarPlay(ListSpec.Songs(SongScope.All, titles), ListStart.Track("s300"), QueueSource.Played("Songs"), network = false)
        val started = start(play) as CarStarted.Items
        assertEquals(listOf("s300"), started.items.map { it.mediaId })
        assertEquals(QueueSource.Played("Songs"), started.items.single().queueSource)
        apply(started)
        assertEquals("s300", rig.current())
        rig.joined()
        assertEquals(songs.map { it.id }, rig.songs())
        assertEquals("s300", rig.current())
    }

    @Test
    fun aSmallListPlaysWholeFromTheChosenSong() {
        val songs = rig.db.songs(12)
        val started = start(CarPlay(ListSpec.Songs(SongScope.All, titles), ListStart.Track("s5"), NoSource, network = false)) as CarStarted.Items
        assertEquals(songs.map { it.id }, started.items.map { it.mediaId })
        assertEquals(5, started.start)
        apply(started)
        assertEquals("s5", rig.current())
        assertEquals(songs.map { it.id }, rig.songs())
    }

    @Test
    fun aSongItsListNoLongerHoldsPlaysAlone() {
        rig.db.songs(12)
        val started = start(CarPlay(ListSpec.Ids(listOf("s1", "s2")), ListStart.Track("s9"), QueueSource.Played("Charts"), network = false)) as CarStarted.Items
        assertEquals(listOf("s9"), started.items.map { it.mediaId })
    }

    @Test
    fun aListThatHoldsNothingIsRefusedWithItsWordsAndTheQueueStays() {
        rig.db.songs(12)
        apply(start(CarPlay(ListSpec.Songs(SongScope.All, titles), null, NoSource, network = false)) as CarStarted.Items)
        val before = rig.songs()
        val empty = CarPlay(ListSpec.Songs(SongScope.Genre("Nothing here"), titles), null, NoSource, network = false, emptyWords = "Nothing is playing on Late Night right now")
        assertEquals(CarStarted.Refused("Nothing is playing on Late Night right now"), start(empty))
        rig.settle()
        assertEquals(before, rig.songs())
        assertEquals("s0", rig.current())
    }

    @Test
    fun aChoiceANewerOneOvertookIsDropped() {
        rig.db.songs(12)
        val mark = guard.mark()
        // Something else set the queue meanwhile.
        apply(start(CarPlay(ListSpec.Ids(listOf("s3")), null, NoSource, network = false)) as CarStarted.Items)
        val before = rig.songs()
        assertEquals(CarStarted.Superseded, start(CarPlay(ListSpec.Songs(SongScope.All, titles), ListStart.Track("s5"), NoSource, network = false), mark))
        rig.settle()
        assertEquals(before, rig.songs())
        assertTrue(rig.held().contains("s3"))
    }

    // The songs the playing player plays, in its own shuffled order.
    private fun playerShuffled(): List<String> {
        val now = rig.player.active
        val timeline = now.currentTimeline
        return generateSequence(timeline.getFirstWindowIndex(true).takeIf { it != C.INDEX_UNSET }) { i ->
            timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true).takeIf { it != C.INDEX_UNSET }
        }.map { now.getMediaItemAt(it).mediaId }.toList()
    }

    // With shuffle on, the chosen song of a big list plays first, then
    // every other song once, shuffled.
    @Test
    fun withShuffleOnAChosenSongPlaysFirstThenTheRestShuffled() {
        val songs = rig.db.songs(2_000)
        rig.player.shuffleModeEnabled = true
        rig.settle()
        val started = start(CarPlay(ListSpec.Songs(SongScope.All, titles), ListStart.Track("s300"), QueueSource.Played("Songs"), network = false)) as CarStarted.Items
        assertEquals(listOf("s300"), started.items.map { it.mediaId })
        apply(started)
        rig.joined()
        rig.settle()
        val order = rig.songs(QueueOrder.Shuffle)
        assertEquals("s300", order.first())
        assertEquals(songs.size, order.toSet().size)
        assertEquals("s300", rig.current())
        assertTrue(rig.player.shuffleModeEnabled)
        rig.next()
        assertEquals(order[1], rig.current())
    }

    // A small shuffled choice plays, and shows, the order its stored keys
    // give, the chosen song first.
    @Test
    fun aSmallShuffledChoicePlaysTheOrderItsKeysGive() {
        rig.db.songs(12)
        rig.player.shuffleModeEnabled = true
        rig.settle()
        apply(start(CarPlay(ListSpec.Songs(SongScope.All, titles), ListStart.Track("s5"), NoSource, network = false)) as CarStarted.Items)
        val order = rig.songs(QueueOrder.Shuffle)
        assertEquals("s5", order.first())
        assertEquals(order, playerShuffled())
    }

    // A choice stops the old queue's build before its read, and the old
    // queue holds the window until Media3 sets the new songs; a refused
    // choice lets the build go on.
    @Test
    fun aChoiceStopsTheOldBuildBeforeItsReadAndARefusalLetsItGoOn() {
        rig.db.songs(2_000)
        val gate = CompletableDeferred<Unit>()
        rig.builds.buildHooks = object : BuildHooks() {
            override suspend fun beforePage(listId: Long, page: Int) {
                if (page == 2) gate.await()
            }
        }
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), ListStart.Row("s10", null), shownOverWindow = true))
        val building = rig.lists().single().listId
        rig.until("the build is part way") { rig.lists().single().filled0 >= 100 }
        val empty = CarPlay(ListSpec.Songs(SongScope.Genre("Nothing here"), titles), null, NoSource, network = false, emptyWords = "Nothing here plays")
        assertEquals(CarStarted.Refused("Nothing here plays"), start(empty))
        rig.until("the refused choice let the build go on") { rig.store.builderOf(building)?.isActive == true }
        assertEquals("s10", rig.current())
        val started = start(CarPlay(ListSpec.Songs(SongScope.All, titles), ListStart.Track("s700"), NoSource, network = false)) as CarStarted.Items
        assertTrue("the old build stopped", rig.store.builderOf(building)?.isActive != true)
        assertTrue("the old queue holds the window", rig.window.building.value.any { it.listId == building })
        gate.complete(Unit)
        apply(started)
        rig.joined()
        assertEquals("s700", rig.current())
        assertEquals(2_000, rig.songs().size)
    }

    // A spoken request that asks for shuffle sets it with its own songs:
    // the queue it replaces is never shuffled first.
    @Test
    fun aSpokenShuffleComesWithItsSongsNotOnTheQueueTheyReplace() {
        rig.db.songs(12)
        apply(start(CarPlay(ListSpec.Songs(SongScope.All, titles), null, NoSource, network = false)) as CarStarted.Items)
        val old = rig.window.queueId
        assertTrue(!rig.player.shuffleModeEnabled)
        val spoken = CarPlay(ListSpec.Songs(SongScope.All, titles), null, NoSource, network = true, shuffle = true)
        val started = rig.run { carStart(rig.window, guard, spoken, guard.mark(), keepsShuffle = false) } as CarStarted.Items
        rig.settle()
        assertTrue("the queue playing stays unshuffled", !rig.player.shuffleModeEnabled)
        assertTrue(rig.rows().none { it.skBefore != null })
        apply(started)
        assertTrue(rig.player.shuffleModeEnabled)
        assertTrue(rig.window.queueId != old)
        val order = rig.songs(QueueOrder.Shuffle)
        assertEquals(rig.current(), order.first())
        assertEquals(order, playerShuffled())
    }
}
