package app.winters.octo.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState

// What floating controls blur: the screen content under the shell.
val LocalHaze = staticCompositionLocalOf<HazeState> { error("No blur source") }
