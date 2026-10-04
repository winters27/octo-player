package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.library.LibraryState
import app.winters.octo.desktop.library.isOutsideSong
import app.winters.octo.desktop.library.outsideIds
import app.winters.octo.desktop.search.FetchPhase
import app.winters.octo.desktop.search.phaseText
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.flow.MutableStateFlow

// The library once read, kept up to date, or null until then.
@Composable
private fun rememberLibraryIndex(app: AppState): LibraryIndex? {
    val library = app.library
    val state by remember(library) { library?.state ?: MutableStateFlow(LibraryState.Idle) }.collectAsState()
    return (state as? LibraryState.Ready)?.index
}

// Whether a song is one the server found online rather than one in the
// library (see isOutsideSong). A song with no mark from the server that
// arrives in the library once fetched stops being outside when the
// library is read again.
@Composable
fun isOutside(app: AppState, song: Song): Boolean {
    val index = rememberLibraryIndex(app)
    val canFetch = app.fetches != null
    return remember(song, index, canFetch) { isOutsideSong(song, index, canFetch) }
}

// The ids of a list's songs that are outside the library.
@Composable
fun rememberOutside(app: AppState, songs: List<Song>): Set<String> {
    val index = rememberLibraryIndex(app)
    val canFetch = app.fetches != null
    return remember(songs, index, canFetch) { outsideIds(songs, index, canFetch) }
}

// The same, as things stand, for a menu opened on some songs: whether any
// of them is outside the library.
fun AppState.anyOutside(songs: List<Song>): Boolean {
    val index = library?.index
    val canFetch = fetches != null
    return songs.any { isOutsideSong(it, index, canFetch) }
}

// The "+" that has the server add a song found online to the library: a
// ring fills while it downloads and a check shows once it is in. A failed
// one says why when hovered, and pressing it again tries again.
@Composable
fun FetchButton(
    app: AppState,
    song: Song,
    size: Dp = ControlHeight.L,
    iconSize: Dp = IconSize.Transport,
    tint: Color = OctoColors.TextPrimary.copy(alpha = 0.7f),
) {
    val fetches = app.fetches ?: return
    val phases by fetches.phases.collectAsState()
    val phase = phases[song.id] ?: FetchPhase.None
    val canAsk = phase == FetchPhase.None || phase is FetchPhase.Failed
    val hint = when (phase) {
        FetchPhase.None -> "Not in your library. Press to add it"
        is FetchPhase.Failed -> "Couldn't add it: ${phase.reason}. Press to try again."
        else -> phaseText(phase)
    }
    OctoTooltip(hint) {
        Box(
            Modifier
                .size(size)
                .hoverLift(CircleShape, clickable = canAsk)
                .clickable(enabled = canAsk) { fetches.request(song.id) }
                .semantics {
                    contentDescription = "Add to your library"
                    stateDescription = phaseText(phase)
                },
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                FetchPhase.None -> Glyph(OctoIcons.AddToLibrary, size = iconSize, tint = tint)
                FetchPhase.Queued -> ProgressRing(null, size = iconSize)
                is FetchPhase.Downloading -> ProgressRing(phase.progress, size = iconSize)
                FetchPhase.Adding -> ProgressRing(1f, size = iconSize)
                FetchPhase.Done -> Glyph(OctoIcons.Check, size = iconSize, tint = OctoColors.Accent)
                is FetchPhase.Failed -> Glyph(OctoIcons.Info, size = iconSize, tint = OctoColors.Error)
            }
        }
    }
}

// A song row's word on where the song is, in a list that mixes library
// songs with songs found online: a check lit in the key colour for one in
// the library, and for one that is not, a quiet "+" that adds it. Once
// the "+" has brought the song in, the row has the lit check too.
@Composable
fun LibraryMark(app: AppState, song: Song, outside: Boolean) {
    val fetches = app.fetches
    val phases by remember(fetches) { fetches?.phases ?: MutableStateFlow(emptyMap()) }.collectAsState()
    if (outside && phases[song.id] != FetchPhase.Done) {
        FetchButton(app, song, ControlHeight.S, IconSize.Table, tint = OctoColors.TextMuted)
    } else {
        OctoTooltip("In your library") {
            Box(
                Modifier.size(ControlHeight.S).semantics { contentDescription = "In your library" },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(OctoIcons.Check, size = IconSize.Toolbar, tint = LocalKeyColour.current)
            }
        }
    }
}
