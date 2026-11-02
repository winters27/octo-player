package app.winters.octo.output

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CountingForwardingPlayerTest {
    private val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
    private val player = CountingForwardingPlayer(exo)

    private fun songs(vararg ids: String) = ids.map { MediaItem.Builder().setMediaId(it).setUri("http://localhost/$it").build() }

    @After
    fun release() {
        exo.release()
    }

    @Test
    fun replacingTheQueueCounts() {
        player.setMediaItems(songs("a", "b"))
        assertEquals(1, player.replacements)
        player.setMediaItem(songs("c").single())
        assertEquals(2, player.replacements)
        player.clearMediaItems()
        assertEquals(3, player.replacements)
    }

    @Test
    fun changingSomeOfTheQueueDoesNot() {
        player.setMediaItems(songs("a", "b", "c"))
        player.addMediaItems(songs("d"))
        player.moveMediaItem(0, 2)
        player.removeMediaItem(1)
        player.replaceMediaItem(0, songs("e").single())
        assertEquals(1, player.replacements)
    }

    @Test
    fun swappingTheWholeQueueCounts() {
        player.setMediaItems(songs("a", "b"))
        player.replaceMediaItems(0, 2, songs("x", "y"))
        assertEquals(2, player.replacements)
        player.removeMediaItems(0, 2)
        assertEquals(3, player.replacements)
    }

    @Test
    fun closeCountsByHand() {
        player.markReplaced()
        assertEquals(1, player.replacements)
    }
}
