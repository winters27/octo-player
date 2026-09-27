package app.winters.octo.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.playback.SpeedDetents
import app.winters.octo.playback.semitonesAt
import app.winters.octo.playback.semitonesFraction
import app.winters.octo.playback.semitonesLabel
import app.winters.octo.playback.speedAt
import app.winters.octo.playback.speedFraction
import app.winters.octo.playback.speedLabel
import app.winters.octo.ui.common.GlassMenuBack
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.rememberOpenedBeside
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpeedViewModel @Inject constructor(private val settings: PlayerSettings) : ViewModel() {
    val prefs: StateFlow<PlayerPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun setSpeed(speed: Float) {
        viewModelScope.launch { settings.setSpeed(speed) }
    }

    fun setKeepPitch(on: Boolean) {
        viewModelScope.launch { settings.setKeepPitch(on) }
    }

    fun setSemitones(semitones: Int) {
        viewModelScope.launch { settings.setPitchSemitones(semitones) }
    }

    fun reset() {
        viewModelScope.launch { settings.resetPace() }
    }
}

// Playback speed, a page of a glass card: a slider from half to double
// speed that settles on the common speeds, whether voices keep their pitch,
// and a pitch shift. Every change is heard at once and kept for every song.
// `onBack` is there when it opened from the sleep timer.
@Composable
fun SpeedPage(onBack: (() -> Unit)?, vm: SpeedViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    Column(Modifier.width(TimeCardWidth).verticalScroll(rememberScrollState())) {
        if (onBack != null) {
            Column(Modifier.padding(6.dp)) { GlassMenuBack("Sleep timer", onBack) }
        }
        SpeedControls(prefs, vm)
    }
}

// The speed card on its own, beside the control that opened it, as from
// the playback settings.
@Composable
fun SpeedPopup(visible: Boolean, onDismiss: () -> Unit) {
    GlassPopup(
        visible = visible,
        anchor = rememberOpenedBeside(visible),
        onDismiss = onDismiss,
        backdrop = LocalHaze.current,
        title = "Speed",
    ) {
        SpeedPage(onBack = null)
    }
}

@Composable
private fun SpeedControls(prefs: PlayerPrefs, vm: SpeedViewModel) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
            if (!prefs.paceIsDefault) GlazeButton("Reset", onClick = vm::reset)
        }
        Spacer(Modifier.height(8.dp))
        Text(speedLabel(prefs.speed), style = OctoType.display.copy(fontFeatureSettings = "tnum"), color = OctoColors.TextPrimary)
        LineSlider(
            fraction = { speedFraction(prefs.speed) },
            onSeek = { fraction ->
                val picked = speedAt(fraction)
                if (picked != prefs.speed) vm.setSpeed(picked)
            },
            live = true,
            modifier = Modifier.semantics {
                contentDescription = "Playback speed"
                stateDescription = speedLabel(prefs.speed)
            },
        )
        DetentLabels(prefs.speed, vm::setSpeed)

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Keep pitch", style = OctoType.bodySmall, color = OctoColors.TextPrimary)
                Text(
                    if (prefs.keepPitch) "Voices sound as usual at any speed." else "Pitch rises and falls with the speed, like a record.",
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                )
            }
            OctoSwitch(checked = prefs.keepPitch, onCheckedChange = vm::setKeepPitch)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pitch", style = OctoType.bodySmall, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(
                semitonesLabel(prefs.pitchSemitones),
                style = OctoType.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = if (prefs.pitchSemitones == 0) OctoColors.TextMuted else OctoColors.TextPrimary,
            )
        }
        LineSlider(
            fraction = { semitonesFraction(prefs.pitchSemitones) },
            onSeek = { fraction ->
                val picked = semitonesAt(fraction)
                if (picked != prefs.pitchSemitones) vm.setSemitones(picked)
            },
            live = true,
            modifier = Modifier.semantics {
                contentDescription = "Pitch"
                stateDescription = semitonesLabel(prefs.pitchSemitones)
            },
        )
    }
}

// The common speeds under the slider, each where it falls on the line.
// A tap goes straight to one.
@Composable
private fun DetentLabels(speed: Float, onPick: (Float) -> Unit) {
    Layout(
        content = {
            SpeedDetents.forEach { detent ->
                Text(
                    speedLabel(detent),
                    style = OctoType.caption.copy(fontFeatureSettings = "tnum"),
                    color = if (detent == speed) OctoColors.TextPrimary else OctoColors.TextMuted,
                    modifier = Modifier
                        .clickable(interactionSource = null, indication = null, role = Role.Button) { onPick(detent) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, placeable ->
                val centre = (speedFraction(SpeedDetents[i]) * width).toInt()
                placeable.placeRelative((centre - placeable.width / 2).coerceIn(0, width - placeable.width), 0)
            }
        }
    }
}
