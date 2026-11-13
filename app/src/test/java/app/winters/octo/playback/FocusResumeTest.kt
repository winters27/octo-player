package app.winters.octo.playback

import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// A call or another short interruption pauses through the player's one
// pause path, and its end brings the music back whenever the person still
// wanted it: while it was waiting for its sound too, never at the queue's
// end.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusResumeTest {
    @After
    fun close() = PlayerRig.closeAll()

    private fun focusChange(rig: PlayerRig, change: Int) {
        val audio = rig.context.getSystemService(AudioManager::class.java)
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(change)
        rig.runFor(1)
    }

    @Test
    fun aCallDuringARebufferStillResumesAfterIt() {
        val rig = PlayerRig(listOf(PcmSong("s", 4_000)), stalled = setOf("s"))
        rig.play("s")
        rig.runFor(300)
        assertEquals(Player.STATE_BUFFERING, rig.player.playbackState)
        assertTrue(rig.player.playWhenReady)
        focusChange(rig, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(rig.player.playWhenReady)
        focusChange(rig, AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("the music was still wanted", rig.player.playWhenReady)
    }

    @Test
    fun aCallAtTheQueuesEndBringsNoMusicBack() {
        val rig = PlayerRig(listOf(PcmSong("a", 600)))
        rig.play("a")
        assertTrue(rig.runUntil(3_000) { rig.player.playbackState == Player.STATE_ENDED })
        focusChange(rig, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusChange(rig, AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(Player.STATE_ENDED, rig.player.playbackState)
    }
}
