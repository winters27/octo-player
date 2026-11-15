package app.winters.octo.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A version picked for the song on plays it again from its start as a new
// play: the deck stops, the fence runs while nothing reads, it opens at 0
// and plays; a second pick in the same play plays too; a blend under way
// ends; another entry changes nothing.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestartFreshTest {
    @After
    fun close() = PlayerRig.closeAll()

    @Test
    fun theEntryOnPlaysAgainFromItsStartWithTheFenceRunWhileNothingReads() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1)))
        rig.play("a", "b")
        assertTrue(rig.runUntil(6_000) { rig.player.currentPosition >= 1_500 })
        var stateAtFence = -1
        assertTrue(rig.player.restartFresh("q:a") { stateAtFence = rig.decks[0].playbackState })
        assertEquals(Player.STATE_IDLE, stateAtFence)
        assertTrue(rig.player.currentPosition < 500)
        assertTrue(rig.runUntil(6_000) { rig.player.isPlaying && rig.player.currentPosition >= 200 })
        assertEquals("q:a", rig.player.currentMediaItem?.entryId)
        assertTrue(rig.player.currentPosition < 1_500)
        // A second pick in the same play plays too.
        assertTrue(rig.player.restartFresh("q:a") {})
        assertTrue(rig.player.currentPosition < 500)
        assertTrue(rig.runUntil(6_000) { rig.player.isPlaying })
    }

    @Test
    fun anotherEntryChangesNothing() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1)))
        rig.play("a", "b")
        assertTrue(rig.runUntil(6_000) { rig.player.currentPosition >= 1_000 })
        val before = rig.player.currentPosition
        assertFalse(rig.player.restartFresh("q:b") { fail("no fence for another entry") })
        assertTrue(rig.player.currentPosition >= before)
        assertEquals("q:a", rig.player.currentMediaItem?.entryId)
    }

    @Test
    fun aRestartDuringABlendEndsItAndPlaysTheNewSongFromItsStart() {
        val crossfade = AutomixSettings(maxOverlapMs = 1_500, smart = false)
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1), PcmSong("c", 2_500)))
        rig.play("a", "b", "c", settings = crossfade)
        assertTrue(rig.runUntil(10_000) { rig.transitions.lastOrNull() == "q:b" && rig.player.isBlending })
        assertTrue(rig.player.restartFresh("q:b") {})
        assertTrue(rig.runUntil(6_000) { !rig.player.isBlending })
        assertEquals("q:b", rig.player.currentMediaItem?.entryId)
        assertTrue(rig.player.currentPosition < 1_000)
    }

    // An entry id the player gives out carries this run's mark, so the next
    // run's count from 0 never names this run's plays.
    @Test
    fun aSongWithNoEntryGetsOneMarkedWithThisRun() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000)))
        val bare = MediaItem.Builder().setMediaId("a").setMediaMetadata(MediaMetadata.Builder().setDurationMs(5_000).build()).build()
        rig.player.setMediaItems(listOf(bare))
        rig.player.prepare()
        val entry = rig.player.currentMediaItem?.entryId.orEmpty()
        assertTrue(entry, entry.startsWith("q:$QUEUE_RUN:"))
        assertTrue(rig.player.restartFresh(entry) {})
    }
}
