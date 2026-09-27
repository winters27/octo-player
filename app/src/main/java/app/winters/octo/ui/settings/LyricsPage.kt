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
import app.winters.octo.lyrics.LookDial
import app.winters.octo.lyrics.LyricsLook
import app.winters.octo.lyrics.LyricsLookSettings
import app.winters.octo.lyrics.LyricsStyle
import app.winters.octo.lyrics.LyricsTiming
import app.winters.octo.lyrics.OUTPUT_TIMING_LIMIT_MS
import app.winters.octo.lyrics.signedTiming
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.sound.AudioOutput
import app.winters.octo.sound.SoundEngine
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.TimingButtons
import app.winters.octo.ui.settings.rows.ChoiceRow
import app.winters.octo.ui.settings.rows.InfoRow
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
import kotlin.math.roundToInt

// The look's sliders move in steps of this many percent.
private const val DIAL_STEP = 5

@HiltViewModel
class LyricsSettingsViewModel @Inject constructor(
    private val player: PlayerSettings,
    private val timing: LyricsTiming,
    private val looks: LyricsLookSettings,
    private val sound: SoundEngine,
) : ViewModel() {
    val prefs: StateFlow<PlayerPrefs> = player.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())
    val keepScreenOn: StateFlow<Boolean> = timing.keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val look: StateFlow<LyricsLook> = looks.look.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsLook())

    // The output playing now and its lyrics timing.
    val output: StateFlow<AudioOutput> = sound.output
    val outputOffset: StateFlow<Long> = timing.outputOffsetFor(sound.output).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun stepOutput(steps: Int) {
        val key = sound.output.value.key
        viewModelScope.launch { timing.stepOutput(key, steps) }
    }

    fun resetOutput() {
        val key = sound.output.value.key
        viewModelScope.launch { timing.resetOutput(key) }
    }

    fun setLyricsOnline(on: Boolean) {
        viewModelScope.launch { player.setLyricsOnline(on) }
    }

    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { timing.setKeepScreenOn(on) }
    }

    fun setStyle(style: LyricsStyle) {
        viewModelScope.launch { looks.setStyle(style) }
    }

    fun setDial(dial: LookDial, percent: Int) {
        viewModelScope.launch { looks.setDial(dial, percent) }
    }

    fun setKeepCompleted(on: Boolean) {
        viewModelScope.launch { looks.setKeepCompleted(on) }
    }

    fun setArc(on: Boolean) {
        viewModelScope.launch { looks.setArc(on) }
    }
}

// Each slider's row in the index.
private val DialEntries = mapOf(
    LookDial.Emphasis to SettingsIndex.LyricsEmphasis,
    LookDial.Glow to SettingsIndex.LyricsGlow,
    LookDial.Lift to SettingsIndex.LyricsLift,
    LookDial.Speed to SettingsIndex.LyricsMotionSpeed,
    LookDial.InactiveScale to SettingsIndex.LyricsInactiveScale,
    LookDial.Fade to SettingsIndex.LyricsFade,
    LookDial.Cascade to SettingsIndex.LyricsCascade,
)

// Where lyrics come from, the screen while they show, how synced lyrics
// look, and their timing on the output playing now.
@Composable
fun LyricsPage(onBack: () -> Unit, highlight: String?, vm: LyricsSettingsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val look by vm.look.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val outputOffset by vm.outputOffset.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current

    SettingsPageFrame("Lyrics", onBack, highlight) {
        SettingsGroup {
            SwitchRow(
                SettingsIndex.LyricsOnline,
                checked = prefs.lyricsOnline,
                onChange = vm::setLyricsOnline,
                helper = "When your server and the song's files have none, look them up on LRCLIB. " +
                    "Only the title, artist, album and length are sent.",
            )
            SwitchRow(
                SettingsIndex.LyricsScreenOn,
                checked = keepScreenOn,
                onChange = vm::setKeepScreenOn,
                helper = "While lyrics show in the player, the screen does not turn off.",
            )
        }
        SettingsGroup(title = "Synced lyrics") {
            ChoiceRow(SettingsIndex.LyricsStyle, value = look.style.label, onClick = {
                val options = LyricsStyle.entries
                sheet.show(
                    ChoiceRequest(
                        SettingsIndex.LyricsStyle.title,
                        options.map { Choice(it.label, it.about) },
                        options.indexOf(look.style),
                    ) { vm.setStyle(options[it]) },
                )
            })
        }
        SettingsGroup(
            title = "Timing",
            footer = "If the words light up late, tap Earlier. If early, tap Later. A song of its own is set in the lyrics menu in the player.",
        ) {
            InfoRow(
                SettingsIndex.LyricsOutputTiming,
                value = signedTiming(outputOffset),
                title = output.label,
                helper = "Applies to every song on this output.",
            )
            TimingButtons(
                outputOffset,
                OUTPUT_TIMING_LIMIT_MS,
                onStep = vm::stepOutput,
                onReset = vm::resetOutput,
                modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp),
            )
        }
        SettingsGroup(title = "Look", footer = "For the Flowing style. Reduce motion in Appearance holds all of it still.") {
            LookDial.entries.forEach { dial ->
                DialRow(dial, look.percent(dial)) { vm.setDial(dial, it) }
            }
            SwitchRow(
                SettingsIndex.LyricsKeepCompleted,
                checked = look.keepCompleted,
                onChange = vm::setKeepCompleted,
                helper = "Lines already sung stay faintly above the one being sung.",
            )
            SwitchRow(
                SettingsIndex.LyricsArc,
                checked = look.arc,
                onChange = vm::setArc,
                helper = "The lines bend away around a drum, the one being sung in front.",
            )
        }
    }
}

// One of the look's sliders, in percent.
@Composable
private fun DialRow(dial: LookDial, percent: Int, onChange: (Int) -> Unit) {
    val range = dial.range
    val span = (range.last - range.first).toFloat()
    SliderRow(
        DialEntries.getValue(dial),
        value = "$percent%",
        fraction = { (percent - range.first) / span },
        onSeek = { fraction ->
            val raw = range.first + fraction * span
            val picked = ((raw / DIAL_STEP).roundToInt() * DIAL_STEP).coerceIn(range)
            if (picked != percent) onChange(picked)
        },
    )
}
