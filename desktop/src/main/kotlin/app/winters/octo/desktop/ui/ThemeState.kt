package app.winters.octo.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import app.winters.octo.design.OctoDefaultPalette
import app.winters.octo.design.OverArt
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.themes
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ResolvedPalette
import app.winters.octo.theme.ThemeSettings
import app.winters.octo.theme.activeTheme
import app.winters.octo.theme.resolvePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

// What the app is drawn in, resolved once for every window: the theme
// showing (the chosen one, or with Match on the Day or Night one for the
// computer's mode), the accent chosen, and the playing cover's color for
// an accent taken from the art. Each window provides it from `palette`.
class ThemeState(
    settings: StateFlow<AppSettings>,
    // Whether the computer is in dark mode; windows and macOS write it from
    // the main window, Linux from the desktop portal.
    val deviceDark: MutableStateFlow<Boolean>,
    // The playing cover's color while the accent follows it, null otherwise.
    art: Flow<Int?>,
    scope: CoroutineScope,
) {
    val palette: StateFlow<ResolvedPalette> = combine(
        settings.map(::chosenIn).distinctUntilChanged(),
        deviceDark,
        art.onStart { emit(null) }.distinctUntilChanged(),
    ) { chosen, dark, seed -> resolvePalette(activeTheme(chosen.themes, dark), chosen.accent, seed, false) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, paletteFor(settings.value, deviceDark.value))
}

private data class Chosen(val themes: ThemeSettings, val accent: AccentChoice)

private fun chosenIn(settings: AppSettings) = Chosen(settings.appearance.themes, settings.appearance.accent)

// The page color everything over a cover is drawn on: Octo's own, in every theme.
val OverArtPage = Color(OctoDefaultPalette.page)

// A window of its own whose face sits over a cover or the dark backdrop
// (the mini player and its station menu): the app's palette, drawn in
// Octo's dark look.
@Composable
fun OverArtWindow(app: AppState, content: @Composable () -> Unit) {
    val palette by app.palette.collectAsState()
    ProvideOctoTheme(palette) { OverArt(content = content) }
}

// The palette for these settings and this mode of the computer, with no
// cover playing: what the first frame is drawn in.
fun paletteFor(settings: AppSettings, deviceDark: Boolean): ResolvedPalette {
    val chosen = chosenIn(settings)
    return resolvePalette(activeTheme(chosen.themes, deviceDark), chosen.accent, null, false)
}

// Whether a window's frame (the system title bar, the traffic lights) is
// drawn dark: a dark theme, the full player, or the sign-in page, where
// the window sits over a cover or the dark backdrop.
fun chromeDark(palette: ResolvedPalette, fullPlayer: Boolean, signedOut: Boolean): Boolean =
    !palette.light || fullPlayer || signedOut

// The main color of the playing song's cover while the accent follows the
// art, null with nothing playing, no server or another accent. The last
// cover's color stays until the next one is read.
@OptIn(ExperimentalCoroutinesApi::class)
fun coverSeed(settings: StateFlow<AppSettings>, state: StateFlow<PlayerState>, connection: () -> Connection?, covers: WashCovers): Flow<Int?> {
    val wanted = settings.map { s ->
        val wash = s.appearance.wash
        (s.appearance.accent == AccentChoice.FromArt) to WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    }.distinctUntilChanged()
    val cover = state.map { it.current?.song?.coverArt }.distinctUntilChanged()
    return combine(wanted, cover, snapshotFlow(connection)) { (follows, tuning), id, link -> Follow(follows, tuning, id, link) }
        .flatMapLatest { f ->
            if (!f.follows || f.coverId == null || f.link == null) {
                flowOf(null)
            } else {
                covers.follow(f.link.client, f.coverId, f.tuning).map { it.main.takeIf { main -> main != 0 } }
            }
        }
}

private class Follow(val follows: Boolean, val tuning: WashTuning, val coverId: String?, val link: Connection?)
