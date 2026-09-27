package app.winters.octo.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.playback.SleepState
import app.winters.octo.playback.speedLabel
import app.winters.octo.ui.common.asClock
import app.winters.octo.ui.common.songs

// The lengths offered, in minutes.
private val SleepChoices = listOf(5, 15, 30, 45, 60)

// What a running countdown can be lengthened by, in minutes.
private val SleepExtensions = listOf(5, 10)

// How many songs the music can stop after, the one playing included.
private val SongChoices = listOf(1, 2, 3, 5)

// A timer in words, for the sheet and the sleep button.
fun sleepSummary(state: SleepState): String = when (state) {
    SleepState.Off -> "Off"
    is SleepState.Counting -> "${((state.remainingMs + 59_999) / 60_000).toInt()} min left"
    SleepState.EndOfSong -> "At the end of this song"
    is SleepState.Songs -> "After ${songs(state.left)}"
    is SleepState.AfterSong -> "After ${state.title}"
}

// How wide the card for time is: room for three buttons in a row.
internal val TimeCardWidth = 320.dp

// The longest custom timer: twelve hours.
private const val MAX_SLEEP_MINUTES = 720

// The sleep timer, a page of a glass card beside its button: how long until
// the music stops, or after how many songs, and the time left once one is
// set, with more time to add while it counts down. Starting or cancelling
// a timer calls onDone so the card can close. Playback speed, the other
// setting about time, opens from its foot as the next page.
@Composable
fun SleepPage(model: PlayerViewModel, onDone: () -> Unit, onOpenSpeed: () -> Unit) {
    val sleep by model.sleep.collectAsStateWithLifecycle()
    val prefs by model.prefs.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }

    Column(
        Modifier
            .width(TimeCardWidth)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
    ) {
        Text("Sleep timer", style = OctoType.body, color = OctoColors.TextPrimary)
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
                        sleepSummary(state),
                        style = OctoType.body,
                        color = OctoColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                GlazeButton("Cancel", onClick = {
                    model.cancelSleep()
                    onDone()
                })
            }
            // More time on a running countdown, without closing the sheet.
            if (state is SleepState.Counting) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SleepExtensions.forEach { minutes ->
                        GlazeButton("+$minutes min", onClick = { model.extendSleep(minutes) })
                    }
                }
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
            GlazeButton("Custom", onClick = { custom = true })
        }
        Spacer(Modifier.height(16.dp))
        Text("Stop after", style = OctoType.label, color = OctoColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SongChoices.forEach { count ->
                GlazeButton(if (count == 1) "This song" else "$count songs", onClick = {
                    model.sleepAfterSongs(count)
                    onDone()
                })
            }
        }
        if (custom) {
            Spacer(Modifier.height(16.dp))
            CustomMinutes(onStart = { minutes ->
                model.sleepIn(minutes)
                onDone()
            })
        }
        Spacer(Modifier.height(16.dp))
        SpeedLine(prefs.speed, onOpenSpeed)
    }
}

// "Playback speed" and what it is now, opening the speed sheet.
@Composable
private fun SpeedLine(speed: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Playback speed", style = OctoType.bodySmall, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(
            speedLabel(speed),
            style = OctoType.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = if (speed == 1f) OctoColors.TextMuted else OctoColors.TextPrimary,
        )
        Icon(
            painterResource(OctoIcons.Chevron),
            contentDescription = null,
            tint = OctoColors.TextMuted,
            modifier = Modifier.padding(start = 6.dp).size(20.dp),
        )
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
