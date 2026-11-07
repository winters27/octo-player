package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.AccentSwatch
import app.winters.octo.design.AccentSwatches
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.player.ArtworkPalette
import app.winters.octo.player.PlayerColors
import app.winters.octo.player.PlayerSettings
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.FixedAccents
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.accentHelp
import app.winters.octo.theme.themedAccent
import app.winters.octo.ui.settings.rows.RowFrame
import app.winters.octo.ui.settings.rows.SwitchRow
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The accent chosen in Appearance, and for From the album art the playing
// cover's color it grows from (none while nothing with a cover plays).
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccentViewModel @Inject constructor(
    private val settings: PlayerSettings,
    palette: ArtworkPalette,
    playback: PlaybackConnection,
) : ViewModel() {
    val choice: StateFlow<AccentChoice?> = settings.prefs
        .map<_, AccentChoice?> { it.accent }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The cover's color the accent grows from, and whether it is known yet:
    // at once for every accent but From the album art (no color), and for
    // that once the playing cover's colors are read. Kept as one value so
    // the two never disagree for a frame.
    val seed: StateFlow<AccentSeed> = choice
        .flatMapLatest { choice ->
            when (choice) {
                null -> flowOf(AccentSeed(null, seedKnown = false))
                AccentChoice.FromArt -> playback.now
                    .map { it.artwork }
                    .distinctUntilChanged()
                    .mapLatest { artwork -> AccentSeed(palette.colorsFor(artwork).seed(), seedKnown = true) }
                else -> flowOf(AccentSeed(null, seedKnown = true))
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccentSeed(null, seedKnown = false))

    // Whether the glass takes the accent too.
    val glassTint: StateFlow<Boolean?> = settings.prefs
        .map<_, Boolean?> { it.glassTint }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Whether the player's settings have been read, so the accent chosen and
    // the glass tint are known rather than still unread.
    val loaded: StateFlow<Boolean> = combine(choice, glassTint) { choice, tint -> choice != null && tint != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setChoice(choice: AccentChoice) {
        viewModelScope.launch { settings.setAccent(choice) }
    }

    fun setGlassTint(on: Boolean) {
        viewModelScope.launch { settings.setGlassTint(on) }
    }
}

// The cover's color From the album art grows from (null for none), and
// whether it has been worked out for the accent chosen.
data class AccentSeed(val argb: Int?, val seedKnown: Boolean)

// The cover's color an accent grows from: its most vivid, or none for
// music without artwork.
private fun PlayerColors.seed(): Int? = if (this === PlayerColors.Quiet) null else mesh[1].toArgb()

// Appearance's accent picker: a circle for each accent in the theme showing
// and one for the playing cover's, the chosen one checked, and a line
// naming it and saying what it colors.
@Composable
fun AccentPicker(active: ThemeChoice, vm: AccentViewModel = hiltViewModel()) {
    val chosen = vm.choice.collectAsStateWithLifecycle().value ?: AccentChoice.Octo
    RowFrame(SettingsIndex.AccentColor) {
        Column(Modifier.weight(1f)) {
            Text(SettingsIndex.AccentColor.title, style = OctoType.body, color = OctoColors.TextPrimary)
            AccentSwatches(
                swatches = AccentChoice.entries.map { choice ->
                    AccentSwatch(
                        choice.label,
                        choice.takeIf { it != AccentChoice.FromArt }?.let { Color(themedAccent(active, it, null)) },
                        selected = choice == chosen,
                    )
                },
                onPick = { vm.setChoice(AccentChoice.entries[it]) },
                spectrum = FixedAccents.drop(1).map { Color(themedAccent(active, it, null)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "${chosen.label}: ${accentHelp(chosen, active)}",
                style = OctoType.caption,
                color = OctoColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// Whether the glass takes the accent too, under the picker. Off, the bar,
// glazed buttons and sheets keep the theme's own accent whatever accent is
// chosen.
@Composable
fun GlassTintRow(vm: AccentViewModel = hiltViewModel()) {
    val chosen = vm.choice.collectAsStateWithLifecycle().value ?: AccentChoice.Octo
    val on = vm.glassTint.collectAsStateWithLifecycle().value ?: false
    SwitchRow(
        SettingsIndex.GlassTint,
        checked = on,
        onChange = vm::setGlassTint,
        helper = if (chosen == AccentChoice.Octo) GLASS_TINT_SAME else GLASS_TINT_HELP,
        enabled = chosen != AccentChoice.Octo,
    )
}

const val GLASS_TINT_HELP = "The bar, glass buttons and sheets take the accent too. Off, they keep the theme's own accent."
const val GLASS_TINT_SAME = "The theme's own accent is already the glass's color. Pick another accent to tint the glass with it."

