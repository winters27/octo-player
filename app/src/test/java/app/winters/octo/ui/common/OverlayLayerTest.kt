package app.winters.octo.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.winters.octo.design.LocalOverArtOn
import app.winters.octo.design.rememberHeld
import app.winters.octo.testing.RobolectricComposeRule
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What the shell opens over its pages (a choice, playlist or share sheet, the
// sign-out question, an undo message) frosts the full player while it covers
// the pages, since they draw nothing under it, and the pages otherwise.
@RunWith(RobolectricTestRunner::class)
class OverlayLayerTest {
    @get:Rule
    val ui = RobolectricComposeRule()

    @Test
    fun overThePlayerTheSheetsFrostThePlayerAndKeepItThroughTheirExit() {
        val player = HazeState()
        val page = HazeState()
        var playerOver by mutableStateOf(true)
        var open by mutableStateOf(false)
        var live: HazeState? = null
        var held: HazeState? = null
        var dark: Boolean? = null
        ui.compose.setContent {
            OverlayLayer(playerOver = playerOver, player = player, page = page) {
                live = LocalHaze.current
                dark = LocalOverArtOn.current
                // As a GlassPopup host holds what it is handed.
                held = rememberHeld(open, LocalHaze.current)
            }
        }
        ui.compose.waitForIdle()
        assertSame("over the player, what opens frosts the player", player, live)
        assertEquals("over the player, in its dark look", true, dark)
        open = true
        ui.compose.waitForIdle()
        assertSame("a sheet opened over the player", player, held)
        // A choice that closes the sheet and the player together.
        Snapshot.withMutableSnapshot {
            open = false
            playerOver = false
        }
        ui.compose.waitForIdle()
        assertSame("closing, it still frosts the player", player, held)
        assertSame("with the player gone, an undo message frosts the page", page, live)
        assertEquals("with the player gone, the page's look", false, dark)
        open = true
        ui.compose.waitForIdle()
        assertSame("a sheet opened over the page", page, held)
    }
}
