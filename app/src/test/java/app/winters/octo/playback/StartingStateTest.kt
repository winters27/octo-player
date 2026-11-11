package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A tap shows its song at once as starting, and that ends on the player's
// own view: when the item playing is the play's start entry. A newer tap
// takes the place of an older one, whatever ends the older one later. The
// words are "Starting" all the while: no play waits for the library.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class StartingStateTest {
    private val rig = QueueRig()
    private val plays = StartingPlays()

    @After
    fun close() = rig.close()

    @Test
    fun aPlayEndsItsStartingStateWhenItsStartEntryPlays() {
        rig.db.songs(700)
        for ((big, start) in listOf(false to ListStart.Row("s300", null), true to ListStart.Row("s400", null))) {
            val token = rig.run { rig.store.claimList() }
            plays.start(StartingPlay(token, "Song", "Band", null))
            assertEquals(STARTING_WORDS, plays.starting.value!!.words)
            rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), start, shownOverWindow = big, token = token))
            val playing = rig.player.currentMediaItem?.entryId
            assertEquals(entryKey(entryOf(token, 0)), playing)
            plays.playing(playing)
            assertNull(plays.starting.value)
            rig.joined()
        }
    }

    @Test
    fun aNewerTapTakesThePlaceAndTheOlderEndChangesNothing() {
        plays.start(StartingPlay(1, "A", null, null))
        plays.start(StartingPlay(2, "B", null, null))
        assertTrue(!plays.end(1))
        assertEquals(2L, plays.starting.value!!.token)
        plays.playing(entryKey(entryOf(1, 0)))
        assertEquals(2L, plays.starting.value!!.token)
        assertTrue(plays.end(2))
        assertNull(plays.starting.value)
    }

    // From the tap to its first song the words stay "Starting", whatever
    // the library does, as no play waits for it.
    @Test
    fun theWordsStayStartingUntilTheFirstSongPlays() {
        plays.start(StartingPlay(5, "A", null, null))
        assertEquals(STARTING_WORDS, plays.starting.value!!.words)
        plays.learned(5, "A", "Band", "cover")
        assertEquals(STARTING_WORDS, plays.starting.value!!.words)
        plays.playing(entryKey(entryOf(5, 0)))
        assertNull(plays.starting.value)
    }
}
