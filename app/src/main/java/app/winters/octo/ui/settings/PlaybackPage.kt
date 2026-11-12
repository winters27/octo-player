package app.winters.octo.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.playback.FadeCurve
import app.winters.octo.playback.curveRowLook
import app.winters.octo.playback.detail
import app.winters.octo.playback.fadeInGain
import app.winters.octo.playback.fadeOutGain
import app.winters.octo.playback.label
import app.winters.octo.playback.speedLabel
import app.winters.octo.player.BetweenSongsChange
import app.winters.octo.player.CrossfadeSecondsRange
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.SpeedPopup
import app.winters.octo.player.SwipeAction
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
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
    // Null until the settings are read.
    val prefs: StateFlow<PlayerPrefs?> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun change(block: suspend PlayerSettings.() -> Unit) {
        viewModelScope.launch { settings.block() }
    }

    fun editBetweenSongs(what: BetweenSongsChange) = change { editBetweenSongs(what) }
    fun setSkipSilence(on: Boolean) = change { setSkipSilence(on) }
    fun setAutoplay(on: Boolean) = change { setAutoplay(on) }
    fun setSwipeAction(action: SwipeAction) = change { setSwipeAction(action) }
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

// The rows under Between songs, each shown only for the choice it belongs
// to: Crossfade's length and curve, Automix's Filter sweeps and Match tempo.
enum class UnderBetweenSongs(val entry: SettingEntry, val choice: BetweenSongs) {
    Length(SettingsIndex.CrossfadeLength, BetweenSongs.Crossfade),
    Curve(SettingsIndex.CrossfadeCurve, BetweenSongs.Crossfade),
    FilterSweeps(SettingsIndex.FilterSweeps, BetweenSongs.Automix),
    MatchTempo(SettingsIndex.MatchTempo, BetweenSongs.Automix),
}

// The rows a choice shows under Between songs, in order. Gapless has none.
fun rowsUnder(choice: BetweenSongs): List<UnderBetweenSongs> = UnderBetweenSongs.entries.filter { it.choice == choice }

// The row a search result lights on the Playback page: a row under Between
// songs that the current choice hides lights Between songs itself.
fun playbackHighlight(id: String?, choice: BetweenSongs): String? {
    val under = UnderBetweenSongs.entries.firstOrNull { it.entry.id == id } ?: return id
    return if (under.choice == choice) id else SettingsIndex.BetweenSongs.id
}

// Each choice as the row and its list say it.
private val BetweenSongs.choiceDetail: String
    get() = when (this) {
        BetweenSongs.Gapless -> "Each song plays straight into the next."
        BetweenSongs.Crossfade -> "Each song fades into the next over the length you set."
        BetweenSongs.Automix -> "Songs blend where they meet best, on the beat when they can."
    }

// How songs play: what plays between them, speed and skipping silence; the
// queue, what a swipe on a song adds to it and Autoplay when it ends; music
// coming back when headphones connect, and casting to TVs and speakers.
// Radio has a page of its own.
@Composable
fun PlaybackPage(onBack: () -> Unit, highlight: String?, vm: PlaybackViewModel = hiltViewModel()) {
    val read by vm.prefs.collectAsStateWithLifecycle()
    val prefs = read ?: PlayerPrefs()
    var speedOpen by rememberSaveable { mutableStateOf(false) }

    // The row to light waits for the settings, so it is the one this
    // device's choice shows.
    SettingsPageFrame("Playback", onBack, read?.let { playbackHighlight(highlight, it.betweenSongs) }) {
        SettingsGroup {
            BetweenSongsRow(prefs.betweenSongs) { vm.editBetweenSongs(BetweenSongsChange.Choose(it)) }
            rowsUnder(prefs.betweenSongs).forEach { row ->
                when (row) {
                    UnderBetweenSongs.Length -> CrossfadeLength(prefs.crossfadeSeconds) { vm.editBetweenSongs(BetweenSongsChange.Length(it)) }
                    UnderBetweenSongs.Curve -> CurveRow(prefs.crossfadeCurve) { vm.editBetweenSongs(BetweenSongsChange.Curve(it)) }
                    UnderBetweenSongs.FilterSweeps -> SwitchRow(
                        row.entry,
                        checked = prefs.filterSweeps,
                        onChange = { vm.editBetweenSongs(BetweenSongsChange.FilterSweeps(it)) },
                        helper = "The song ending loses its highs and bass as the next one comes in.",
                    )
                    UnderBetweenSongs.MatchTempo -> SwitchRow(
                        row.entry,
                        checked = prefs.matchTempo,
                        onChange = { vm.editBetweenSongs(BetweenSongsChange.MatchTempo(it)) },
                        helper = "Songs with a close tempo blend in step. The next song settles back to its own speed after.",
                    )
                }
            }
            ChoiceRow(SettingsIndex.Speed, value = paceLabel(prefs), onClick = { speedOpen = true })
            SwitchRow(
                SettingsIndex.SkipSilence,
                checked = prefs.skipSilence,
                onChange = vm::setSkipSilence,
            )
        }
        SettingsGroup(title = "Queue", icon = OctoIcons.Queue) {
            val sheet = LocalChoiceSheet.current
            val actions = SwipeAction.entries
            ChoiceRow(SettingsIndex.SongSwipe, value = prefs.swipeAction.label, helper = prefs.swipeAction.detail, answers = actions.map { it.label }, helpers = actions.map { it.detail }, onClick = {
                sheet.show(
                    ChoiceRequest(SettingsIndex.SongSwipe.title, actions.map { Choice(it.label, it.detail) }, actions.indexOf(prefs.swipeAction)) {
                        vm.setSwipeAction(actions[it])
                    },
                )
            })
            SwitchRow(
                SettingsIndex.Autoplay,
                checked = prefs.autoplay,
                onChange = vm::setAutoplay,
                helper = "When the queue ends, similar songs keep playing, picked as Radio settings say.",
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

// What plays between two songs, picked from a list that says what each
// does. Albums played in order stay gapless whatever is picked.
@Composable
private fun BetweenSongsRow(choice: BetweenSongs, onPick: (BetweenSongs) -> Unit) {
    val sheet = LocalChoiceSheet.current
    val choices = BetweenSongs.entries
    ChoiceRow(
        SettingsIndex.BetweenSongs,
        value = choice.name,
        helper = "Albums played in order stay gapless.",
        answers = choices.map { it.name },
        onClick = {
            sheet.show(
                ChoiceRequest(SettingsIndex.BetweenSongs.title, choices.map { Choice(it.name, it.choiceDetail) }, choices.indexOf(choice)) {
                    onPick(choices[it])
                },
            )
        },
    )
}

// Crossfade's length, from 2 to 16 seconds, saved as it changes.
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

// The shape of the crossfade, picked from a list that draws each one.
@Composable
private fun CurveRow(curve: FadeCurve, onPick: (FadeCurve) -> Unit) {
    val sheet = LocalChoiceSheet.current
    val curves = FadeCurve.entries
    val look = curveRowLook(curve, smartTransitions = false)
    ChoiceRow(
        SettingsIndex.CrossfadeCurve,
        value = look.value,
        enabled = look.choosable,
        helper = look.helper,
        answers = curves.map { it.label },
        helpers = if (look.choosable) curves.map { it.detail } else emptyList(),
        onClick = {
            sheet.show(
                ChoiceRequest(
                    SettingsIndex.CrossfadeCurve.title,
                    curves.map { Choice(it.label, it.detail, leading = { CurvePreview(it) }) },
                    curves.indexOf(curve),
                ) { onPick(curves[it]) },
            )
        },
    )
}

// A small drawing of a curve: the outgoing song falling, the incoming one
// rising.
@Composable
private fun CurvePreview(curve: FadeCurve) {
    val outInk = OctoColors.TextMuted
    val inInk = OctoColors.TextPrimary
    Canvas(Modifier.size(width = 32.dp, height = 20.dp)) {
        val steps = 24
        val stroke = 1.5.dp.toPx()
        fun line(gain: (Double) -> Double, color: Color) {
            val path = Path()
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val x = size.width * t.toFloat()
                val y = size.height * (1f - gain(t).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(stroke))
        }
        // An 8 second blend, so mix only shows its short edges.
        line({ fadeOutGain(curve, it, 8_000.0) }, outInk)
        line({ fadeInGain(curve, it, 8_000.0) }, inInk)
    }
}
