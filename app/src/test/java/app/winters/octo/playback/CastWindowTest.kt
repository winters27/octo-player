package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// While music plays on a cast device, the device holds the window, never
// the whole queue: a big queue walks through it with Next, the device
// holding at most the window's songs all the while.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class CastWindowTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun theDeviceHoldsTheWindow() {
        rig.db.songs(600)
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null, shownOverWindow = true))
        rig.joined()
        assertTrue(rig.player.active === rig.remote)
        for (i in 1..40) {
            rig.next()
            assertEquals("s$i", rig.current())
            assertTrue("the device held ${rig.remote.mediaItemCount}", rig.remote.mediaItemCount <= rig.sizes.window)
        }
        assertEquals(600, rig.songs().size)
    }
}
