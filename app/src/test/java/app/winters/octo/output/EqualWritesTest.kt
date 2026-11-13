package app.winters.octo.output

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.AutomixSettings
import app.winters.octo.playback.CommitState
import app.winters.octo.playback.PcmSong
import app.winters.octo.playback.PlayerRig
import app.winters.octo.playback.QueueMirror
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Repeat and shuffle set to what the session already shows go no further,
// compared with what the session shows (the queue's repeat on a window),
// never the player's own: the person's "repeat off" on a window of a queue
// on repeat all is applied, and a controller resending repeat all or
// shuffle keeps the blend.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqualWritesTest {
    // A window of a longer queue the person has on repeat all.
    private class Window : QueueMirror {
        var repeat = Player.REPEAT_MODE_ALL
        val repeats = ArrayList<Int>()
        val shuffles = ArrayList<Boolean>()

        override fun stamp(items: List<MediaItem>, set: Boolean): List<MediaItem> = items
        override fun setItems(items: List<MediaItem>, player: Player) {}
        override fun added(items: List<MediaItem>, player: Player) {}
        override fun removing(items: List<MediaItem>) {}
        override fun removed(player: Player) {}
        override fun moved(items: List<MediaItem>, player: Player) {}
        override fun shuffleChanged(on: Boolean) {
            shuffles += on
        }
        override fun repeatChanged(mode: Int) {
            repeats += mode
            repeat = mode
        }
        override fun innerRepeat(): Int = if (repeat == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else repeat
        override val windowed: Boolean = true
        override val listenerRepeat: Int get() = repeat
        override val atLogicalEnd: Boolean = false
        override val atLogicalStart: Boolean = false
        override fun edge(forward: Boolean): ListenableFuture<*>? = null
        override fun holds(key: String): Boolean = true
        override fun takeAutoStep(): Boolean = false
    }

    @After
    fun close() = PlayerRig.closeAll()

    @Test
    fun theSessionsOwnRepeatAndShuffleDecideWhatChanges() {
        val rig = PlayerRig(listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1)))
        val window = Window()
        val session = OutputSwitch(rig.player) { window }
        rig.play("a", "b", settings = AutomixSettings(maxOverlapMs = 1_500, smart = false))
        assertTrue(rig.runUntil(6_000) { rig.decks[1].playWhenReady })
        // A controller resending what the session shows: nothing moves.
        session.repeatMode = Player.REPEAT_MODE_ALL
        session.shuffleModeEnabled = false
        assertEquals(emptyList<Int>(), window.repeats)
        assertEquals(emptyList<Boolean>(), window.shuffles)
        // The person's repeat off reaches the queue though the player's own
        // repeat is already off, and the player's equal write is skipped.
        session.repeatMode = Player.REPEAT_MODE_OFF
        assertEquals(listOf(Player.REPEAT_MODE_OFF), window.repeats)
        assertEquals(Player.REPEAT_MODE_OFF, session.repeatMode)
        // Nothing of it undid the blend being set up.
        assertEquals(CommitState.INITIAL, rig.mix.mixer.commitWord.stateOf(1))
    }
}
