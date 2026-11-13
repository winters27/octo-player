package app.winters.octo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import app.winters.octo.design.OverArt
import dev.chrisbanes.haze.HazeState

// What floating controls blur: the screen content under the shell.
val LocalHaze = staticCompositionLocalOf<HazeState> { error("No blur source") }

// The menus, sheets and messages the shell opens over its pages. While the
// full player covers the pages they take its dark look and frost the player,
// since the pages under it draw nothing; otherwise they frost the pages.
// Each menu keeps the frost it opened with through its exit (GlassPopup).
@Composable
fun OverlayLayer(playerOver: Boolean, player: HazeState, page: HazeState, content: @Composable () -> Unit) {
    OverArt(enabled = playerOver) {
        CompositionLocalProvider(LocalHaze provides if (playerOver) player else page, content = content)
    }
}
