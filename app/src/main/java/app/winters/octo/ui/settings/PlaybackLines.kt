package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackLinesViewModel @Inject constructor(private val settings: PlayerSettings) : ViewModel() {
    val prefs: StateFlow<PlayerPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun setSkipSilence(on: Boolean) {
        viewModelScope.launch { settings.setSkipSilence(on) }
    }

    fun setAutoplay(on: Boolean) {
        viewModelScope.launch { settings.setAutoplay(on) }
    }

    fun setResumeWired(on: Boolean) {
        viewModelScope.launch { settings.setResumeWired(on) }
    }

    fun setResumeBluetooth(on: Boolean) {
        viewModelScope.launch { settings.setResumeBluetooth(on) }
    }

    fun setResumeAlways(on: Boolean) {
        viewModelScope.launch { settings.setResumeAlways(on) }
    }
}

// The Player card's lines for how songs play: skipping silence, Autoplay,
// and music coming back when headphones connect.
@Composable
internal fun PlaybackLines(vm: PlaybackLinesViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    SwitchLine(
        label = "Skip silence",
        detail = "Quiet stretches inside songs are skipped.",
        checked = prefs.skipSilence,
        onChange = vm::setSkipSilence,
    )
    SwitchLine(
        label = "Autoplay",
        detail = "When the queue ends, similar songs keep playing: songs like it from your server, or by the same artist or in the same genre.",
        checked = prefs.autoplay,
        onChange = vm::setAutoplay,
    )
    Text("Headphones", style = OctoType.label, color = OctoColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
    SwitchLine(
        label = "Resume when headphones connect",
        detail = "Plays again when a cable or USB headset goes back in, if taking it out paused the music in the last 30 minutes.",
        checked = prefs.resumeWired,
        onChange = vm::setResumeWired,
    )
    SwitchLine(
        label = "Resume when Bluetooth connects",
        detail = "Plays again when Bluetooth headphones or a speaker reconnect, if losing them paused the music in the last 30 minutes.",
        checked = prefs.resumeBluetooth,
        onChange = vm::setResumeBluetooth,
    )
    if (prefs.resumeWired || prefs.resumeBluetooth) {
        SwitchLine(
            label = "Always play on connect",
            detail = "Plays on connect even when the music was paused some other way, or longer ago.",
            checked = prefs.resumeAlways,
            onChange = vm::setResumeAlways,
        )
        Text(
            "Works while Octo is playing or paused in the background. Once Android has closed Octo, connecting does nothing.",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
        )
    }
}
