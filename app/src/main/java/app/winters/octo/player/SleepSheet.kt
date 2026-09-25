package app.winters.octo.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.playback.SleepState
import app.winters.octo.ui.common.asClock

// The lengths offered, in minutes.
private val SleepChoices = listOf(5, 15, 30, 45, 60)

// The longest custom timer: twelve hours.
private const val MAX_SLEEP_MINUTES = 720

// The sleep timer, inside a GlassSheet: how long until the music stops, or
// the end of the song, and the time left once one is set. Starting or
// cancelling a timer calls onDone so the sheet can close.
@Composable
fun SleepSheet(model: PlayerViewModel, onDone: () -> Unit) {
    val sleep by model.sleep.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Text("Sleep timer", style = OctoType.section, color = OctoColors.TextPrimary)
        val state = sleep
        if (state != SleepState.Off) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state is SleepState.Counting) {
                    Text(
                        ((state.remainingMs + 999) / 1000).toInt().asClock(),
                        style = OctoType.display.copy(fontFeatureSettings = "tnum"),
                        color = OctoColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "At the end of this song",
                        style = OctoType.body,
                        color = OctoColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                GlazeButton("Cancel", onClick = {
                    model.cancelSleep()
                    onDone()
                })
            }
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SleepChoices.forEach { minutes ->
                GlazeButton(if (minutes == 60) "1 hour" else "$minutes min", onClick = {
                    model.sleepIn(minutes)
                    onDone()
                })
            }
            GlazeButton("End of song", onClick = {
                model.sleepAtEndOfSong()
                onDone()
            })
            GlazeButton("Custom", onClick = { custom = true })
        }
        if (custom) {
            Spacer(Modifier.height(16.dp))
            CustomMinutes(onStart = { minutes ->
                model.sleepIn(minutes)
                onDone()
            })
        }
    }
}

// A minutes field that takes digits only, and starts once it holds 1 to 720.
@Composable
private fun CustomMinutes(onStart: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val minutes = text.toIntOrNull()?.takeIf { it in 1..MAX_SLEEP_MINUTES }
    val focus = remember { FocusRequester() }
    // Opens the keyboard as the field appears.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassInput(
            value = text,
            onValueChange = { typed -> if (typed.length <= 3 && typed.all { it in '0'..'9' }) text = typed },
            placeholder = "Minutes, 1 to $MAX_SLEEP_MINUTES",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { minutes?.let(onStart) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        Spacer(Modifier.width(10.dp))
        AccentButton("Start", onClick = { minutes?.let(onStart) }, enabled = minutes != null)
    }
}
