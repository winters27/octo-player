package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.ambient.AmbienceViewModel
import app.winters.octo.ambient.AmbientArea
import app.winters.octo.ambient.AmbientPrefs
import app.winters.octo.ambient.AmbientPreview
import app.winters.octo.ambient.AmbientStrength
import app.winters.octo.player.LiveBackgroundSupported
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.settings.rows.ChoiceRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.ui.settings.rows.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val StrengthChoices = mapOf(
    AmbientStrength.Off to Choice("Off", "The plain dark background everywhere."),
    AmbientStrength.Subtle to Choice("Subtle", "A faint glow of the artwork's colours behind the pages."),
    AmbientStrength.Rich to Choice("Rich", "A fuller glow, still kept dark enough for text."),
)

@HiltViewModel
class LiveBackgroundViewModel @Inject constructor(private val settings: PlayerSettings) : ViewModel() {
    val prefs: StateFlow<PlayerPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun setLiveBackground(on: Boolean) {
        viewModelScope.launch { settings.setLiveBackground(on) }
    }
}

// How the app looks: the artwork's colours glowing behind the pages, how
// strongly and where, and the player's moving background.
@Composable
fun AppearancePage(
    onBack: () -> Unit,
    highlight: String?,
    vm: AmbienceViewModel = hiltViewModel(),
    live: LiveBackgroundViewModel = hiltViewModel(),
) {
    val prefs = vm.prefs.collectAsStateWithLifecycle().value ?: AmbientPrefs()
    val player by live.prefs.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current

    SettingsPageFrame("Appearance", onBack, highlight) {
        AmbientPreview(prefs.strength, Modifier.padding(horizontal = 16.dp))
        SettingsGroup {
            ChoiceRow(SettingsIndex.Ambient, value = StrengthChoices.getValue(prefs.strength).label, onClick = {
                val options = AmbientStrength.entries
                sheet.show(
                    ChoiceRequest(SettingsIndex.Ambient.title, options.map(StrengthChoices::getValue), options.indexOf(prefs.strength)) {
                        vm.setStrength(options[it])
                    },
                )
            })
        }
        if (prefs.strength != AmbientStrength.Off) {
            SettingsGroup(title = "Where it shows") {
                SwitchRow(SettingsIndex.AmbientHome, prefs.home, { vm.setArea(AmbientArea.Home, it) }, helper = "The home page.")
                SwitchRow(
                    SettingsIndex.AmbientLibrary,
                    prefs.library,
                    { vm.setArea(AmbientArea.Library, it) },
                    helper = "Its lists and pages: albums, artists, playlists, genres and folders.",
                )
                SwitchRow(SettingsIndex.AmbientSearch, prefs.search, { vm.setArea(AmbientArea.Search, it) }, helper = "The search page and its results.")
                SwitchRow(SettingsIndex.AmbientSettings, prefs.settings, { vm.setArea(AmbientArea.Settings, it) }, helper = "These pages.")
                SwitchRow(SettingsIndex.AmbientBar, prefs.bar, vm::setBar, helper = "A trace of the song's colour in the floating bar's glass.")
                SwitchRow(
                    SettingsIndex.AmbientPageArtwork,
                    prefs.pageArtwork,
                    vm::setPageArtwork,
                    helper = "The glow follows the album or artist on the page instead of the song playing.",
                )
            }
        }
        SettingsGroup(title = "Player") {
            SwitchRow(
                SettingsIndex.LiveBackground,
                checked = player.liveBackground && LiveBackgroundSupported,
                onChange = live::setLiveBackground,
                enabled = LiveBackgroundSupported,
                helper = if (LiveBackgroundSupported) {
                    "Colours from the artwork, moving slowly while music plays."
                } else {
                    "Needs Android 13 or newer. The blurred artwork is used instead."
                },
            )
        }
    }
}
