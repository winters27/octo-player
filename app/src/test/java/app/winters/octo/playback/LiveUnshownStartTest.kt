package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A saved live list played with no screen showing it (from the car before
// its page is opened, by voice, or anything else with no page): its start
// is worked out from its rules with no write, and every song it picks is
// queued, the start's song current; whole when it is small, built behind its
// start when it is big. Played from the car with the car's own copy of the
// list kept, the start reads that copy.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class LiveUnshownStartTest {
    private val rig = QueueRig(page = 50)
    private val rock = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "Rock")))
    private val spec = ListSpec.Live("l1", rock, "Rock")

    @After
    fun close() = rig.close()

    // `n` songs, every other one Rock: the Rock ones, in the list's order.
    private fun library(n: Int): List<String> {
        val rocks = rig.db.songs(n, genre = { if (it % 2 == 0) "Rock" else "Jazz" }).filter { it.genre == "Rock" }.map { it.id }
        // Live lists wait for the merge's keys.
        rig.run { rig.merge.start() }
        return rocks
    }

    private fun played(request: PlayRequest): List<String> {
        val answer = rig.play(request)
        assertTrue("refused: ${answer.refusal}, words ${rig.words}", answer.started)
        rig.joined()
        return rig.songs()
    }

    @Test
    fun aSmallListNoScreenShowsPlaysWhole() {
        val rocks = library(240)
        assertFalse("a screen shows the list", rig.run { rig.live.forSpec(spec.listId, rock, "probe").shown() })
        assertEquals(rocks, played(PlayRequest(spec)))
        assertEquals(rocks.first(), rig.current())
    }

    @Test
    fun aBigListNoScreenShowsQueuesEverySong() {
        val rocks = library(1_200)
        assertTrue("the list is big", rocks.size > QUEUE_WINDOW)
        assertEquals(rocks, played(PlayRequest(spec)))
        assertEquals(rocks.first(), rig.current())
    }

    @Test
    fun aBigListNoScreenShowsPlaysShuffledAndFromATappedSong() {
        val rocks = library(1_200)
        val shuffled = played(PlayRequest(spec, shuffle = true))
        assertEquals(rocks.toSet(), shuffled.toSet())
        assertEquals("each song once", rocks.size, shuffled.size)
        assertTrue(rig.current() in rocks)
        val tapped = rocks[550]
        val fromTap = played(PlayRequest(spec, ListStart.Track(tapped)))
        assertEquals(rocks.toSet(), fromTap.toSet())
        assertEquals("each song once", rocks.size, fromTap.size)
        assertEquals(tapped, rig.current())
    }

    // The car keeps its own copy of the list under an owner of its own and
    // plays with that owner as the page's: the start reads it.
    @Test
    fun theCarsOwnCopyIsReadWhenNoPhoneScreenShowsTheList() {
        val rocks = library(1_200)
        val car = rig.owners.car()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.launch { rig.live.watch(car, spec.listId, flowOf(rock), keepHeld = true).collect {} }
            rig.until("the car shows the list") { rig.run { rig.live.shown(car) } != null }
            assertTrue("the car's copy is not read", rig.run { rig.live.forSpec(spec.listId, rock, "probe", fallback = car).shown() })
            assertFalse("a phone screen shows the list", rig.run { rig.live.forSpec(spec.listId, rock, "probe").shown() })
            assertEquals(rocks, played(PlayRequest(spec, pageOwner = car)))
            assertEquals(rocks.first(), rig.current())
        } finally {
            // The watch tidies up as it stops, so it ends before the rig
            // closes the database.
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
}
