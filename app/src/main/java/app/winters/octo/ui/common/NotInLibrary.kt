package app.winters.octo.ui.common

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.isFind
import app.winters.octo.design.Glaze
import app.winters.octo.design.OctoIcons
import app.winters.octo.discovery.DownloadPhase
import app.winters.octo.discovery.Downloads
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.sqrt

// What TalkBack adds for artwork of something not in the library.
const val NotInLibraryText = "Not in your library"

// Songs found online that have since been downloaded into the library, so
// their artwork goes back to clean before the list around them refreshes.
val LocalAdoptedFinds = compositionLocalOf<Set<String>> { emptySet() }

@HiltViewModel
class AdoptedFindsViewModel @Inject constructor(downloads: Downloads) : ViewModel() {
    val adopted: StateFlow<Set<String>> = downloads.phases
        .map(::adoptedFinds)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}

// Gives everything inside the songs found online that are now in the library.
@Composable
fun ProvideAdoptedFinds(vm: AdoptedFindsViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val adopted by vm.adopted.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAdoptedFinds provides adopted, content = content)
}

// The finds whose download has arrived in the library.
fun adoptedFinds(phases: Map<String, DownloadPhase>): Set<String> =
    phases.filterValues { it == DownloadPhase.Done }.keys.toSet()

// Whether a song is one found online and not in the library yet.
fun isOutsideLibrary(trackId: String?, adopted: Set<String>): Boolean =
    trackId != null && isFind(trackId) && trackId !in adopted

@Composable
fun isOutsideLibrary(trackId: String?): Boolean = isOutsideLibrary(trackId, LocalAdoptedFinds.current)

// Whether a song row draws the mark on its artwork. A row that offers the
// download button leaves it off: the button already says it, and a row
// says a thing once.
fun rowMarksArtwork(trackId: String, adopted: Set<String>, offersDownload: Boolean): Boolean =
    !offersDownload && isOutsideLibrary(trackId, adopted)

// What TalkBack says for a picture, with the note for one not in the library.
fun artworkDescription(description: String?, outside: Boolean): String? = when {
    !outside -> description
    description.isNullOrEmpty() -> NotInLibraryText
    else -> "$description, ${NotInLibraryText.lowercase()}"
}

// The quiet line on an album page from the server, where the big cover
// carries no mark: nothing once every song is in the library.
fun albumLibraryNote(songs: Int, outside: Int): String? = when {
    outside <= 0 -> null
    outside >= songs -> NotInLibraryText
    else -> "${songs - outside} of $songs in your library"
}

// How big the mark is for artwork of a given side: an eighth of it, never
// so small the arrow blurs, nor big enough to crowd a large cover.
private const val MarkShare = 0.125f
private val MarkMin = 12.dp
private val MarkMax = 20.dp

fun notInLibraryMarkSize(art: Dp): Dp = (art * MarkShare).coerceIn(MarkMin, MarkMax)

// How far the mark sits in from the bottom and right edges. On a square
// cover, just clear of the rounded corner. On a round picture, along the
// diagonal, far enough in that the whole disc stays inside the circle.
fun notInLibraryMarkInset(art: Dp, mark: Dp, round: Boolean): Dp {
    val margin = (art * 0.04f).coerceAtLeast(3.dp)
    if (!round) return margin
    val radius = art / 2
    val centre = radius - (radius - margin - mark / 2) / sqrt(2f)
    return centre - mark / 2
}

// Dark enough under the arrow to read on a white cover; the glass edge is
// what shows it on a dark one.
private val MarkFilm = Color.Black.copy(alpha = 0.42f)

// A small glass disc in the bottom right of the artwork, holding a thin
// arrow: the same arrow as the download button, quieter.
@Composable
internal fun BoxScope.NotInLibraryMark(art: Dp, round: Boolean) {
    val mark = notInLibraryMarkSize(art)
    val inset = notInLibraryMarkInset(art, mark, round)
    Glaze(
        Modifier.align(Alignment.BottomEnd).padding(end = inset, bottom = inset).size(mark),
        shape = CircleShape,
        film = MarkFilm,
    ) {
        Icon(
            painterResource(OctoIcons.NotInLibrary),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(mark * 0.68f),
        )
    }
}
