package app.winters.octo.ui.common

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

// The song that is on, and whether it is sounding, so any list can mark its
// row without each row watching the player.
@Immutable
data class NowMark(val trackId: String? = null, val playing: Boolean = false)

val LocalNowPlayingId = compositionLocalOf { NowMark() }
