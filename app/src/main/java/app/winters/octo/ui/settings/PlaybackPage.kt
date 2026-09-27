package app.winters.octo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.playback.speedLabel
import app.winters.octo.player.CrossfadeSecondsRange
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.SpeedPopup
import app.winters.octo.ui.settings.rows.ChoiceRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.ui.settings.rows.SliderRow
import app.winters.octo.ui.settings.rows.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(private val settings: PlayerSettings) : ViewModel() {
    val prefs: StateFlow<PlayerPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    private fun change(block: suspend PlayerSettings.() -> Unit) {
        viewModelScope.launch { settings.block() }
    }

    fun setCrossfade(on: Boolean) = change { setCrossfade(on) }
    fun setCrossfadeSeconds(seconds: Int) = change { setCrossfadeSeconds(seconds) }
    fun setSkipSilence(on: Boolean) = change { setSkipSilence(on) }
    fun setAutoplay(on: Boolean) = change { setAutoplay(on) }
    fun setResumeWired(on: Boolean) = change { setResumeWired(on) }
    fun setResumeBluetooth(on: Boolean) = change { setResumeBluetooth(on) }
    fun setResumeAlways(on: Boolean) = change { setResumeAlways(on) }
    fun setCastRenderers(on: Boolean) = change { setCastRenderers(on) }
    fun setCastKeepPlaying(on: Boolean) = change { setCastKeepPlaying(on) }
}

// The speed as its row shows it: "1x", or "1.25x, voices keep their pitch".
private fun paceLabel(prefs: PlayerPrefs): String = buildString {
    append(speedLabel(prefs.speed))
    if (prefs.speed != 1f) append(if (prefs.keepPitch) ", voices keep their pitch" else ", pitch follows the speed")
    if (prefs.pitchSemitones != 0) append(", shifted ${prefs.pitchSemitones} st")
}

// How songs play: blending, speed, skipping silence, Autoplay, music
// coming back when headphones connect, and casting to TVs and speakers.
@Composable
fun PlaybackPage(onBack: () -> Unit, highlight: String?, vm: PlaybackViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var speedOpen by rememberSaveable { mutableStateOf(false) }

    SettingsPageFrame("Playback", onBack, highlight) {
        SettingsGroup {
            SwitchRow(
                SettingsIndex.Crossfade,
                checked = prefs.crossfade,
                onChange = vm::setCrossfade,
                helper = "Each song fades into the next. Albums played in order stay gapless.",
            )
            if (prefs.crossfade) CrossfadeLength(prefs.crossfadeSeconds, vm::setCrossfadeSeconds)
            ChoiceRow(SettingsIndex.Speed, value = paceLabel(prefs), onClick = { speedOpen = true })
            SwitchRow(
                SettingsIndex.SkipSilence,
                checked = prefs.skipSilence,
                onChange = vm::setSkipSilence,
                helper = "Quiet stretches inside songs are skipped.",
            )
            SwitchRow(
                SettingsIndex.Autoplay,
                checked = prefs.autoplay,
                onChange = vm::setAutoplay,
                helper = "When the queue ends, similar songs keep playing: songs like it from your server, " +
                    "or by the same artist or in the same genre.",
            )
        }
        SettingsGroup(
            title = "Headphones",
            footer = if (prefs.resumeWired || prefs.resumeBluetooth) {
                "Works while Octo is playing or paused in the background. Once Android has closed Octo, connecting does nothing."
            } else {
                null
            },
        ) {
            SwitchRow(
                SettingsIndex.ResumeWired,
                checked = prefs.resumeWired,
                onChange = vm::setResumeWired,
                helper = "Plays again when a cable or USB headset goes back in, if taking it out paused the music in the last 30 minutes.",
            )
            SwitchRow(
                SettingsIndex.ResumeBluetooth,
                checked = prefs.resumeBluetooth,
                onChange = vm::setResumeBluetooth,
                helper = "Plays again when Bluetooth headphones or a speaker reconnect, if losing them paused the music in the last 30 minutes.",
            )
            if (prefs.resumeWired || prefs.resumeBluetooth) {
                SwitchRow(
                    SettingsIndex.ResumeAlways,
                    checked = prefs.resumeAlways,
                    onChange = vm::setResumeAlways,
                    helper = "Plays on connect even when the music was paused some other way, or longer ago.",
                )
            }
        }
        SettingsGroup(title = "Casting") {
            SwitchRow(
                SettingsIndex.CastRenderers,
                checked = prefs.castRenderers,
                onChange = vm::setCastRenderers,
                helper = "Smart TVs, AV receivers and hi-fi streamers on the same Wi-Fi show up beside Cast devices when you cast.",
            )
            SwitchRow(
                SettingsIndex.CastKeepPlaying,
                checked = prefs.castKeepPlaying,
                onChange = vm::setCastKeepPlaying,
                helper = "When a TV or speaker goes away, or is stopped from somewhere else, the music carries on here. Off, it pauses.",
            )
        }
    }
    // The same speed controls as the player's, kept for every song.
    SpeedPopup(visible = speedOpen, onDismiss = { speedOpen = false })
}

// How long the blend is, from 1 to 12 seconds, saved as it changes.
@Composable
private fun CrossfadeLength(seconds: Int, onChange: (Int) -> Unit) {
    val range = CrossfadeSecondsRange
    val span = (range.last - range.first).toFloat()
    SliderRow(
        SettingsIndex.CrossfadeLength,
        value = "$seconds s",
        fraction = { (seconds - range.first) / span },
        onSeek = { fraction ->
            val picked = range.first + Math.round(fraction * span)
            if (picked != seconds) onChange(picked)
        },
    )
}
