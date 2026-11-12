package app.winters.octo.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.SwingUtilities

// A sheet keeps drawing over art or in the theme as it was when it opened,
// through its closing, while its colors stay live: a theme switched with it
// open draws what a fresh start in that theme draws.
class HoldLookTest {
    private fun palette(theme: ThemeChoice) = resolvePalette(theme, AccentChoice.Coral, null, false)

    @Test
    fun aSheetKeepsItsLookThroughThePlayerClosingAndItsOwnExit() {
        var theme by mutableStateOf(ThemeChoice.Daylight)
        var player by mutableStateOf(true)
        var open by mutableStateOf(false)
        var seen: OctoSurfaces? = null
        var accent: Color? = null
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(40, 40, Density(1f)) {
                ProvideOctoTheme(palette(theme)) {
                    OverArt(enabled = player) {
                        HoldLook(open) {
                            seen = LocalOctoSurfaces.current
                            accent = LocalOctoAccent.current.accent
                        }
                    }
                }
            }
            var frame = 0L
            fun step() {
                Snapshot.sendApplyNotifications()
                frame += 16_000_000L
                scene.render(frame)
            }
            try {
                val dark = surfacesOf(OctoDefaultPalette)
                val darkAccent = Color(palette(ThemeChoice.Daylight).overArt.accent)
                step()
                // Opened over the full player: Octo's dark look.
                open = true
                step()
                assertEquals("opened over the player", dark, seen)
                assertEquals("opened over the player, its accent", darkAccent, accent)
                // The player closes behind it: still dark.
                player = false
                step()
                assertEquals("the player closed behind it", dark, seen)
                // Dismissed: still dark all through its exit.
                open = false
                step()
                assertEquals("closing", dark, seen)
                step()
                assertEquals("still closing", dark, seen)
                // Opened again with no player: the theme's own look.
                open = true
                step()
                assertEquals("opened over the page", surfacesOf(palette(ThemeChoice.Daylight)), seen)
                // The theme switched while it is open: the new theme, as a
                // fresh start in it draws.
                theme = ThemeChoice.Moss
                step()
                assertEquals("switched to Moss while open", surfacesOf(palette(ThemeChoice.Moss)), seen)
                assertEquals("switched to Moss while open, its accent", Color(palette(ThemeChoice.Moss).accents.accent), accent)
            } finally {
                scene.close()
            }
        }
    }

    // The song menu over the full player, then "Go to album": the menu
    // closes and the player goes in the same frame, and the backdrop the
    // shell hands the menu turns from the player's to the page's. All
    // through the menu's exit it still frosts the player, in the dark look.
    @Test
    fun aMenuOverThePlayerFrostsThePlayerAllThroughItsExit() {
        var player by mutableStateOf(true)
        var open by mutableStateOf(false)
        var frosts: String? = null
        var seen: OctoSurfaces? = null
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(40, 40, Density(1f)) {
                ProvideOctoTheme(palette(ThemeChoice.Daylight)) {
                    OverArt(enabled = player) {
                        // As GlassPopup holds what the shell passes it.
                        val held = rememberHeld(open, if (player) "player" else "page")
                        HoldLook(open) {
                            frosts = held
                            seen = LocalOctoSurfaces.current
                        }
                    }
                }
            }
            var frame = 0L
            fun step() {
                Snapshot.sendApplyNotifications()
                frame += 16_000_000L
                scene.render(frame)
            }
            try {
                val dark = surfacesOf(OctoDefaultPalette)
                step()
                open = true
                step()
                assertEquals("opened over the player", "player", frosts)
                assertEquals("opened over the player, its look", dark, seen)
                // Go to album: the menu and the player close together.
                Snapshot.withMutableSnapshot {
                    open = false
                    player = false
                }
                step()
                assertEquals("closing", "player", frosts)
                assertEquals("closing, its look", dark, seen)
                step()
                assertEquals("still closing", "player", frosts)
                assertEquals("still closing, its look", dark, seen)
                // Opened again over the page: the page's backdrop and look.
                open = true
                step()
                assertEquals("opened over the page", "page", frosts)
                assertEquals("opened over the page, its look", surfacesOf(palette(ThemeChoice.Daylight)), seen)
            } finally {
                scene.close()
            }
        }
    }
}
