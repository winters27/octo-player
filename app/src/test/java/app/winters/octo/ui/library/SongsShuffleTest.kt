package app.winters.octo.ui.library

import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.listening.PlayTotals
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlayAnswer
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.QueueStore
import app.winters.octo.playback.RealLengths
import app.winters.octo.playback.Shown
import app.winters.octo.playback.StartingPlays
import app.winters.octo.playback.songs
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.testing.Presented
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.ListMeta
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.catalog.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.util.Collections

// Shuffle on the Songs list filtered in Kotlin, through the view model with
// the service stood in for. Tapped before the list's count's pass has
// landed, Shuffle shows it is loading and nothing is asked to play; once
// the pass lands the play starts from the pass's first pick. Shuffles after
// that hand out the pass's 16 picks, each once and in turn, reading no row;
// the one that takes the last asks for a fresh pass, and the next Shuffle
// starts from that pass's first pick. Play starts from the pass's first
// kept row.
@RunWith(RobolectricTestRunner::class)
class SongsShuffleTest {
    private val db = openTestCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val merge = db.testMerge(null)
    private val totals = PlayTotals(db, merge, { Long.MAX_VALUE }, MutableStateFlow("home"), {}, 500)
    private val loaders = ListLoaders(
        db, merge, { Long.MAX_VALUE }, PlaylistSets(db), WorkOwners(), Drains(Files.createTempDirectory("drain").toFile()), totals, { null },
        { error("no downloads") }, { error("no sorted library") }, { error("no health") }, { error("no live") },
    )
    private val plays = Plays()
    private val vm = LibraryViewModel(
        db.dao(), SortedLibrary(db.dao(), SortSettings(NoSettings), db, totals), plays, loaders, LibraryReads(merge, totals), db, SavedStateHandle(),
    )
    private val rock = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "Rock")))

    // A third of the songs, in runs, are Rock.
    private val tracks = db.songs(20_000, genre = { i -> if (i % 3 == 0 || i in 7_000 until 8_500) "Rock" else "Jazz" })
    private val rockIds = tracks.filter { it.genre == "Rock" }.mapTo(HashSet()) { it.id }

    // No saved orders: the orders asked for are given.
    private object NoSettings : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    // A play asked of the service.
    private data class Asked(val spec: ListSpec, val start: ListStart?, val shuffle: Boolean, val shownOverWindow: Boolean)

    // The service stood in for: each list asked to play, as asked.
    private inner class Plays : PlaybackConnection(ApplicationProvider.getApplicationContext(), RealLengths(), QueueStore(db), StartingPlays(), { Feedback() }) {
        val asked: MutableList<Asked> = Collections.synchronizedList(ArrayList())

        override fun playList(
            spec: ListSpec,
            start: ListStart?,
            shuffle: Boolean,
            source: String?,
            playWhenReady: Boolean,
            pageOwner: String?,
            shownOverWindow: Boolean,
            handlesRefusal: Boolean,
            shown: Shown?,
            word: String?,
            answered: ((PlayAnswer) -> Unit)?,
        ) {
            asked += Asked(spec, start, shuffle, shownOverWindow)
        }
    }

    @After
    fun close() {
        vm.viewModelScope.cancel()
        idle()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // Turns the main looper, where the view model and its list run, until
    // `done`; fails after a few seconds.
    private fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!done()) {
            idle()
            if (System.nanoTime() > end) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    // The Songs list, filtered to Rock.
    private fun filtered(): ListHandle<SongsQuestion, TrackEntity> {
        until("the Songs list is made") { vm.songs.value != null }
        vm.filterSongs(rock)
        idle()
        return vm.songs.value!!
    }

    // The count's pass of the generation on screen, once it landed.
    private fun counted(handle: ListHandle<SongsQuestion, TrackEntity>, after: Long = 0): ListMeta {
        until("the count's pass landed") { handle.shown.value.let { it != null && it.generation > after && it.generation == handle.presented && it.start != null } }
        return handle.shown.value!!
    }

    private fun shuffle(): Asked {
        val n = plays.asked.size
        vm.playSongs(true)
        until("Shuffle asked for a play") { plays.asked.size > n }
        return plays.asked.last()
    }

    @Test
    fun aShuffleBeforeTheCountShowsLoadingThenPlaysThePassesFirstPick() {
        val handle = filtered()
        // No page is on screen yet, so no pass has run.
        vm.playSongs(true)
        idle()
        assertTrue("Shuffle shows it is loading", vm.shuffleWaiting.value)
        assertTrue("nothing plays before the count", plays.asked.isEmpty())
        Presented(handle.pages, scope).use {
            until("Shuffle asked for a play") { plays.asked.isNotEmpty() }
            assertFalse("Shuffle no longer loading", vm.shuffleWaiting.value)
            val meta = counted(handle)
            val asked = plays.asked.single()
            assertEquals(ListSpec.Songs(app.winters.octo.sort.SongScope.All, vm.songOrder.value!!, rock), asked.spec)
            assertTrue("shuffled, the page over the window", asked.shuffle && asked.shownOverWindow)
            val start = asked.start as ListStart.First
            assertEquals("the pass's first pick", meta.start!!.picks[0].trackId, start.trackId)
            assertTrue("a song the filter keeps", start.trackId in rockIds)

            // Play starts from the pass's first kept row.
            val n = plays.asked.size
            vm.playSongs(false)
            until("Play asked for a play") { plays.asked.size > n }
            val play = plays.asked.last()
            assertFalse(play.shuffle)
            assertEquals(meta.start!!.first!!.trackId, (play.start as ListStart.First).trackId)
        }
    }

    @Test
    fun sixteenShufflesHandOutThePassesPicksOnceEachThenAFreshPassBringsMore() {
        val handle = filtered()
        Presented(handle.pages, scope).use {
            val meta = counted(handle)
            val picks = meta.start!!.picks
            assertEquals(16, picks.size)
            assertTrue("every pick is a song the filter keeps", picks.all { it.trackId in rockIds })
            assertFalse("a Shuffle after the count never loads", vm.shuffleWaiting.value)
            db.counted.reset()
            val firstFifteen = List(15) { shuffle() }
            assertEquals("the first 15 picks, in turn", picks.take(15).map { it.trackId }, firstFifteen.map { (it.start as ListStart.First).trackId })
            assertNull("no row was read: ${db.counted.maxRead}", db.counted.maxRead?.takeIf { it.rowsRead > 0 })
            assertTrue(firstFifteen.all { it.shuffle && it.shownOverWindow })
            // The 16th takes the last pick and asks for a fresh pass.
            val last = shuffle()
            assertEquals(picks[15].trackId, (last.start as ListStart.First).trackId)
            val fresh = counted(handle, after = meta.generation)
            val next = shuffle()
            assertEquals("the fresh pass's first pick", fresh.start!!.picks[0].trackId, (next.start as ListStart.First).trackId)
            assertTrue(next.shuffle)
        }
    }
}
