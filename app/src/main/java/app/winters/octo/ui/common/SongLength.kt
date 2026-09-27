package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.FindLengths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// Lengths the player learned for songs found online, so rows already on
// screen show them without their list being loaded again.
val LocalLearnedLengths = compositionLocalOf<Map<String, Long>> { emptyMap() }

@HiltViewModel
class LearnedLengthsViewModel @Inject constructor(lengths: FindLengths) : ViewModel() {
    val learned: StateFlow<Map<String, Long>> = lengths.learned
}

// Gives everything inside the lengths learned so far, kept up to date.
@Composable
fun ProvideLearnedLengths(vm: LearnedLengthsViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val learned by vm.learned.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalLearnedLengths provides learned, content = content)
}

// A song's length as a row shows it, like 3:45. Nothing when it is not
// known, rather than 0:00.
fun rowLength(durationMs: Long): String? = if (durationMs >= 1_000) (durationMs / 1_000).toInt().asClock() else null

// The widest length the column is sized for: anything under an hour.
const val LengthSlotSample = "00:00"

// Lengths in rows: the muted caption, with figures of one width so the
// column lines up whatever the phone's font.
val LengthStyle: TextStyle = OctoType.caption.copy(fontFeatureSettings = "tnum")

// How wide the length column is, measured from the sample in its style.
@Composable
fun rememberLengthSlot(): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val density = LocalDensity.current
    return remember(measurer, density) {
        with(density) { measurer.measure(LengthSlotSample, LengthStyle).size.width.toDp() }
    }
}

// A song's length at the end of its row, in a column of one width, so a
// row with no known length leaves the space and nothing beside it moves.
// A longer song widens it.
@Composable
internal fun LengthSlot(durationMs: Long) {
    Box(Modifier.widthIn(min = rememberLengthSlot()), contentAlignment = Alignment.CenterEnd) {
        rowLength(durationMs)?.let { Text(it, style = LengthStyle, color = OctoColors.TextMuted, maxLines = 1) }
    }
}
