package app.winters.octo.testing

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.MediaItem
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiHarnessTest {
    @get:Rule
    val ui = RobolectricComposeRule()

    // Compose content shows and recomposes on Robolectric, found by its text.
    @Test
    fun composeContentRunsOnRobolectric() {
        var words by mutableStateOf("Starting")
        ui.compose.setContent { BasicText(words) }
        ui.compose.onNodeWithText("Starting").assertExists()
        words = "Playing"
        ui.compose.onNodeWithText("Playing").assertExists()
    }

    // Media3's test player runs here too, on a fake clock: a queue set and a
    // jump within it, with every command handled.
    @Test
    fun testPlayerRunsOnRobolectric() {
        val player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
        player.setMediaItems((1..3).map { MediaItem.Builder().setMediaId("e$it").setUri("http://127.0.0.1/$it.flac").build() })
        player.seekTo(1, 0)
        TestPlayerRunHelper.run(player).untilPendingCommandsAreFullyHandled()
        assertEquals(3, player.mediaItemCount)
        assertEquals("e2", player.currentMediaItem?.mediaId)
        player.release()
    }
}
