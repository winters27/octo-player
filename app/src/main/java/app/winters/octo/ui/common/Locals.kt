package app.winters.octo.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import app.winters.octo.subsonic.SubsonicClient
import dev.chrisbanes.haze.HazeState

// The signed-in server, for anything that builds cover addresses.
val LocalSubsonic = staticCompositionLocalOf<SubsonicClient> { error("No server signed in") }

// What floating controls blur: the screen content under the shell.
val LocalHaze = staticCompositionLocalOf<HazeState> { error("No blur source") }
