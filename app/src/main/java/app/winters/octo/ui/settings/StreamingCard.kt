package app.winters.octo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.playback.CopyPreference
import app.winters.octo.playback.StreamQuality
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.StreamPrefs
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamingViewModel @Inject constructor(private val settings: PlayerSettings) : ViewModel() {
    val prefs: StateFlow<StreamPrefs> =
        settings.streamPrefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamPrefs())

    fun setCopies(preference: CopyPreference) {
        viewModelScope.launch { settings.setCopies(preference) }
    }

    fun setWifi(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamWifi(quality) }
    }

    fun setMobile(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamMobile(quality) }
    }
}

private val CopyChoices = mapOf(
    CopyPreference.PhoneFirst to Choice("Phone copy first", "Plays the phone's file, and streams only songs the phone lacks."),
    CopyPreference.BestQuality to Choice("Best quality", "Plays whichever copy sounds better, even when that means streaming."),
)

private val StreamQuality.label: String get() = kbps?.let { "$it kbps" } ?: "Original"

// A size as its sheet lists it, with roughly how much data an hour uses.
private val StreamQuality.choice: Choice
    get() = Choice(label, kbps?.let { "MP3, about ${Math.round(it * 0.45)} MB an hour" } ?: "The server's file, unchanged")

// How music from a server plays. Each line opens a sheet with its options.
@Composable
fun StreamingCard(modifier: Modifier = Modifier, vm: StreamingViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current
    val qualities = StreamQuality.entries

    Card("Streaming", modifier) {
        ChoiceLine("When a song is on the phone and the server", CopyChoices.getValue(prefs.copies).label) {
            val options = CopyPreference.entries
            sheet.show(
                ChoiceRequest("When a song is on the phone and the server", options.map(CopyChoices::getValue), options.indexOf(prefs.copies)) {
                    vm.setCopies(options[it])
                },
            )
        }
        ChoiceLine("Streaming on Wi-Fi", prefs.wifi.label) {
            sheet.show(ChoiceRequest("Streaming on Wi-Fi", qualities.map { it.choice }, qualities.indexOf(prefs.wifi)) { vm.setWifi(qualities[it]) })
        }
        ChoiceLine("Streaming on mobile data", prefs.mobile.label) {
            sheet.show(ChoiceRequest("Streaming on mobile data", qualities.map { it.choice }, qualities.indexOf(prefs.mobile)) { vm.setMobile(qualities[it]) })
        }
    }
}

// A setting and its current choice; tapping it opens the options.
@Composable
private fun ChoiceLine(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            Text(value, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}
