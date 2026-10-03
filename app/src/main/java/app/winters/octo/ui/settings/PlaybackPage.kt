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
import app.winters.octo.design.OctoIcons
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
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    SettingsPageFrame("Playback", onBack, highlight, icon = OctoIcons.Playback) {
        SettingsGroup {
            SwitchRow(
                SettingsIndex.Crossfade,
                checked = prefs.crossfade,
                onChange = vm::setCrossfade,
                helper = "Albums played in order stay gapless.",
            )
            if (prefs.crossfade) CrossfadeLength(prefs.crossfadeSeconds, vm::setCrossfadeSeconds)
            ChoiceRow(SettingsIndex.Speed, value = paceLabel(prefs), onClick = { speedOpen = true })
            SwitchRow(
                SettingsIndex.SkipSilence,
                checked = prefs.skipSilence,
                onChange = vm::setSkipSilence,
            )
            SwitchRow(
                SettingsIndex.Autoplay,
                checked = prefs.autoplay,
                onChange = vm::setAutoplay,
                helper = "When the queue ends, similar songs keep playing.",
            )
        }
        SettingsGroup(
            title = "Headphones",
            icon = OctoIcons.Headphones,
            footer = if (prefs.resumeWired || prefs.resumeBluetooth) {
                "Only while Octo is open or paused in the background."
            } else {
                null
            },
        ) {
            SwitchRow(
                SettingsIndex.ResumeWired,
                checked = prefs.resumeWired,
                onChange = vm::setResumeWired,
                helper = "If unplugging paused it in the last 30 minutes.",
            )
            SwitchRow(
                SettingsIndex.ResumeBluetooth,
                checked = prefs.resumeBluetooth,
                onChange = vm::setResumeBluetooth,
                helper = "If losing them paused it in the last 30 minutes.",
            )
            if (prefs.resumeWired || prefs.resumeBluetooth) {
                SwitchRow(
                    SettingsIndex.ResumeAlways,
                    checked = prefs.resumeAlways,
                    onChange = vm::setResumeAlways,
                    helper = "However or whenever the music was paused.",
                )
            }
        }
        SettingsGroup(title = "Casting", icon = OctoIcons.Cast) {
            SwitchRow(
                SettingsIndex.CastRenderers,
                checked = prefs.castRenderers,
                onChange = vm::setCastRenderers,
                helper = "TVs, receivers and streamers on your Wi-Fi, beside Cast devices.",
            )
            SwitchRow(
                SettingsIndex.CastKeepPlaying,
                checked = prefs.castKeepPlaying,
                onChange = vm::setCastKeepPlaying,
                helper = "When a TV or speaker drops out, the music carries on here.",
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
