package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.ambient.AmbienceViewModel
import app.winters.octo.ambient.AmbientArea
import app.winters.octo.ambient.AmbientPrefs
import app.winters.octo.ambient.AmbientPreview
import app.winters.octo.ambient.AmbientStrength
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet

private val StrengthChoices = mapOf(
    AmbientStrength.Off to Choice("Off", "The plain dark background everywhere."),
    AmbientStrength.Subtle to Choice("Subtle", "A faint glow of the artwork's colours behind the pages."),
    AmbientStrength.Rich to Choice("Rich", "A fuller glow, still kept dark enough for text."),
)

// How the app looks around the player: the artwork's colours glowing behind
// the pages, how strongly, and where.
@Composable
fun AppearanceCard(modifier: Modifier = Modifier, vm: AmbienceViewModel = hiltViewModel()) {
    val prefs = vm.prefs.collectAsStateWithLifecycle().value ?: AmbientPrefs()
    val sheet = LocalChoiceSheet.current

    Card("Appearance", modifier) {
        AmbientPreview(prefs.strength)
        ChoiceLine("Ambient colour from the artwork", StrengthChoices.getValue(prefs.strength).label) {
            val options = AmbientStrength.entries
            sheet.show(
                ChoiceRequest("Ambient colour from the artwork", options.map(StrengthChoices::getValue), options.indexOf(prefs.strength)) {
                    vm.setStrength(options[it])
                },
            )
        }
        if (prefs.strength != AmbientStrength.Off) {
            Text("Where it shows", style = OctoType.label, color = OctoColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            SwitchLine("Home", "The home page.", prefs.home, onChange = { vm.setArea(AmbientArea.Home, it) })
            SwitchLine(
                "Library",
                "Its lists and pages: albums, artists, playlists, genres and folders.",
                prefs.library,
                onChange = { vm.setArea(AmbientArea.Library, it) },
            )
            SwitchLine("Search", "The search page and its results.", prefs.search, onChange = { vm.setArea(AmbientArea.Search, it) })
            SwitchLine("Settings", "These pages.", prefs.settings, onChange = { vm.setArea(AmbientArea.Settings, it) })
            SwitchLine("Mini player and bar", "A trace of the song's colour in the floating bar's glass.", prefs.bar, vm::setBar)
            SwitchLine(
                "Album and artist pages use their own artwork",
                "The glow follows the album or artist on the page instead of the song playing.",
                prefs.pageArtwork,
                vm::setPageArtwork,
            )
        }
    }
}
