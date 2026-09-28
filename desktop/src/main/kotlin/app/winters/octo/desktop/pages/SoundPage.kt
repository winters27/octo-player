package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.settings.PlaybackPrefs
import app.winters.octo.desktop.sound.EqCurve
import app.winters.octo.desktop.sound.SoundController
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.windowRect
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.Separator
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.playback.FASTEST_SPEED
import app.winters.octo.playback.PITCH_RANGE_SEMITONES
import app.winters.octo.playback.SLOWEST_SPEED
import app.winters.octo.playback.semitonesAt
import app.winters.octo.playback.semitonesFraction
import app.winters.octo.playback.semitonesLabel
import app.winters.octo.playback.speedAt
import app.winters.octo.playback.speedFraction
import app.winters.octo.playback.speedLabel
import app.winters.octo.sound.EqFilter
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.FilterType
import app.winters.octo.sound.ParametricEqFile
import app.winters.octo.sound.ReplayGainMode
import app.winters.octo.sound.SoundSettings
import app.winters.octo.ui.sound.MAX_FILTERS
import app.winters.octo.ui.sound.MAX_HZ
import app.winters.octo.ui.sound.MAX_Q
import app.winters.octo.ui.sound.MIN_HZ
import app.winters.octo.ui.sound.MIN_Q
import app.winters.octo.ui.sound.cleanGain
import app.winters.octo.ui.sound.presetLabel
import app.winters.octo.ui.sound.readDb
import app.winters.octo.ui.sound.readHz
import app.winters.octo.ui.sound.tidyHz
import app.winters.octo.ui.sound.withFilter
import app.winters.octo.ui.sound.withMode
import app.winters.octo.ui.sound.withNewFilter
import app.winters.octo.ui.sound.withPreset
import app.winters.octo.ui.sound.withoutFilter
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

// The ranges the page offers, in decibels, as on the phone.
private val PreampRange = -12f..6f
private val ReplayGainPreampRange = -12f..12f
private val FallbackRange = -12f..6f

private val ReplayGainModes = listOf(ReplayGainMode.Off, ReplayGainMode.Track, ReplayGainMode.Album, ReplayGainMode.Smart)
private val ReplayGainHelp = mapOf(
    ReplayGainMode.Off to "Songs play at the level they were made.",
    ReplayGainMode.Track to "Evens out volume between songs.",
    ReplayGainMode.Album to "Keeps an album's quiet and loud songs as the artist made them.",
    ReplayGainMode.Smart to "Album while you play an album in order, otherwise song.",
)

private val FilterTypes = listOf(FilterType.Peak, FilterType.LowShelf, FilterType.HighShelf)

// Sound: the equalizer with its curve, loudness, balance, crossfade and
// speed, for the output playing now (or every output). Everything is heard
// as it moves and saved to the settings file.
@Composable
fun SoundPage(app: AppState, visit: Visit) {
    val list = rememberListState(app.navigator, visit)
    val controller = app.sound
    val playerState by app.player.state.collectAsState()
    val all by app.settings.state.collectAsState()
    LazyColumn(state = list, contentPadding = pagePadding(LocalBottomRoom.current)) {
        item(key = "title") { PageTitle("Sound") }
        if (controller == null) {
            item(key = "none") {
                SettingsCard("No sound engine") {
                    Txt("Octo's sound engine couldn't start on this computer, so there is nothing to shape.", OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 3)
                }
            }
            return@LazyColumn
        }
        item(key = "output") {
            val device = playerState.playingOn?.name ?: "this computer"
            SettingsCard(if (all.sound.perOutput) "Sound for $device" else "Sound for every output") {
                SwitchLine(
                    "Each output keeps its own sound",
                    "Speakers, headphones and each USB device remember their own settings, and switch with them.",
                    all.sound.perOutput,
                    controller::setPerOutput,
                )
            }
        }
        item(key = "eq") { EqualizerCard(app, controller) }
        item(key = "loudness") { LoudnessCard(controller) }
        item(key = "balance") { BalanceCard(controller) }
        item(key = "playback") { PlaybackCard(app, all.playback) }
        item(key = "correction") { CorrectionCard(controller) }
    }
}

@Composable
private fun EqualizerCard(app: AppState, sound: SoundController) {
    val settings by sound.current.collectAsState()
    var chosen by remember { mutableIntStateOf(0) }
    val selected = chosen.coerceIn(0, (settings.filters.size - 1).coerceAtLeast(0))
    val parametric = settings.mode == EqMode.Parametric
    SettingsCard("Equalizer", trailing = { OctoSwitch(settings.eqEnabled, { on -> sound.update { it.copy(eqEnabled = on) } }) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlazeSegments(listOf(EqMode.Graphic, EqMode.Parametric), settings.mode, { if (it == EqMode.Graphic) "Ten bands" else "Free filters" }, { mode ->
                sound.update { withMode(it, mode) }
            })
            Box(Modifier.weight(1f))
            if (!parametric) PresetButton(app, sound, settings)
        }
        EqCurve(
            settings,
            selected,
            onSelect = { chosen = it },
            onPreview = sound::preview,
            onSettle = sound::settle,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Txt(
            if (parametric) "Drag a point to move a filter; the wheel nudges its gain, a double click sets it flat." else "Drag a band up or down; the wheel nudges it, a double click sets it flat.",
            OctoType.caption,
            OctoColors.TextMuted,
        )
        if (parametric) FilterEditor(settings, selected, sound) { chosen = it }
        Separator(Modifier.padding(vertical = 6.dp))
        SwitchLine(
            "Automatic preamp",
            "Lowers the level by the curve's highest boost, so boosting never distorts.",
            settings.autoPreamp,
        ) { on -> sound.update { it.copy(autoPreamp = on) } }
        if (!settings.autoPreamp) {
            DbSlider("Preamp", settings.preampDb, PreampRange, sound) { s, db -> s.copy(preampDb = db) }
        }
        InfoLine("Level now", readDb(remember(settings) { settings.effectivePreampDb() }))
    }
}

// The preset in use, opening the list of presets to pick from, save to
// and delete.
@Composable
private fun PresetButton(app: AppState, sound: SoundController, settings: SoundSettings) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    Box(Modifier.onGloballyPositioned { anchor = it.windowRect() }) {
        GlazeCapsule(OctoIcons.Sound, presetLabel(settings), {
            app.popups.showUnder(anchor, width = 280.dp) { close ->
                MenuTitle("Presets")
                // Watched, so a preset deleted here leaves the list at once.
                val saved by app.settings.state.collectAsState()
                val now by sound.current.collectAsState()
                val own = saved.sound.presets.map { it.name }.toSet()
                sound.presets().forEach { preset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            MenuRow(preset.name, {
                                sound.update { withPreset(it, preset) }
                                close()
                            }, if (preset.name == now.preset) OctoIcons.Check else null)
                        }
                        if (preset.name in own) TextAction("Delete", { sound.deletePreset(preset.name) }, Modifier.padding(end = 10.dp))
                    }
                }
                MenuSeparator()
                MenuRow("Save as preset", {
                    close()
                    app.popups.showCentred { done -> SavePreset(sound, done) }
                }, OctoIcons.AddToLibrary)
            }
        }, height = 34.dp)
    }
}

@Composable
private fun SavePreset(sound: SoundController, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    MenuTitle("Save as preset")
    PopupPadding {
        GlassField(name, { name = it }, placeholder = "Preset name", onSubmit = {
            if (sound.canSaveAs(name)) {
                sound.savePreset(name)
                close()
            }
        })
        if (name.isNotBlank() && !sound.canSaveAs(name)) Txt("A built-in preset has that name.", OctoType.caption, OctoColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlazeCapsule(null, "Save", {
                sound.savePreset(name)
                close()
            }, enabled = sound.canSaveAs(name), height = 34.dp)
            GlazeCapsule(null, "Cancel", close, height = 34.dp)
        }
    }
}

// Type, frequency, gain and width of the chosen filter, with adding and
// deleting filters.
@Composable
private fun FilterEditor(settings: SoundSettings, selected: Int, sound: SoundController, onChoose: (Int) -> Unit) {
    val filter = settings.filters.getOrNull(selected)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (filter == null) {
            Txt("No filters yet. Add one, then drag it on the curve.", OctoType.caption, OctoColors.TextMuted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Filter ${selected + 1} of ${settings.filters.size}", OctoType.label, modifier = Modifier.weight(1f))
                GlazeSegments(FilterTypes, filter.type, { typeName(it) }, { type -> sound.update { edit(it, selected) { f -> f.copy(type = type) } } })
            }
            val logHz = ln(MAX_HZ / MIN_HZ)
            SliderLine(
                "Frequency",
                readHz(filter.frequency),
                ln(filter.frequency / MIN_HZ) / logHz,
                { x -> sound.preview { edit(it, selected) { f -> f.copy(frequency = tidyHz(MIN_HZ * (MAX_HZ / MIN_HZ).pow(x))) } } },
                onRelease = sound::settle,
            )
            SliderLine(
                "Gain",
                readDb(filter.gainDb),
                (filter.gainDb + 12f) / 24f,
                { x -> sound.preview { edit(it, selected) { f -> f.copy(gainDb = cleanGain(x * 24f - 12f)) } } },
                onRelease = sound::settle,
                wheelStep = 0.5f / 24f,
            )
            val logQ = ln(MAX_Q / MIN_Q)
            SliderLine(
                "Width (Q)",
                String.format(Locale.ROOT, "%.2f", filter.q),
                ln(filter.q / MIN_Q) / logQ,
                { x -> sound.preview { edit(it, selected) { f -> f.copy(q = MIN_Q * (MAX_Q / MIN_Q).pow(x)) } } },
                onRelease = sound::settle,
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlazeCapsule(OctoIcons.AddToLibrary, "Add filter", {
                val next = settings.filters.size
                sound.update { withNewFilter(it).copy(eqEnabled = true) }
                onChoose(next)
            }, enabled = settings.filters.size < MAX_FILTERS, height = 34.dp)
            if (filter != null) GlazeCapsule(OctoIcons.Delete, "Delete filter", { sound.update { withoutFilter(it, selected) } }, height = 34.dp)
        }
    }
}

// Changes one filter, and turns the equalizer on so the change is heard.
private fun edit(settings: SoundSettings, index: Int, change: (EqFilter) -> EqFilter): SoundSettings {
    val filter = settings.filters.getOrNull(index) ?: return settings
    return withFilter(settings, index, change(filter)).copy(eqEnabled = true)
}

private fun typeName(type: FilterType) = when (type) {
    FilterType.Peak -> "Peak"
    FilterType.LowShelf -> "Low shelf"
    FilterType.HighShelf -> "High shelf"
}

// A level in decibels on a slider, heard as it moves.
@Composable
private fun DbSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, sound: SoundController, set: (SoundSettings, Float) -> SoundSettings) {
    val span = range.endInclusive - range.start
    SliderLine(
        label,
        readDb(value),
        (value - range.start) / span,
        { x -> sound.preview { set(it, tenths(range.start + x * span)) } },
        onRelease = sound::settle,
        wheelStep = 0.5f / span,
    )
}

private fun tenths(db: Float): Float = (db * 10f).roundToInt() / 10f

@Composable
private fun LoudnessCard(sound: SoundController) {
    val settings by sound.current.collectAsState()
    SettingsCard("Loudness") {
        GlazeSegments(ReplayGainModes, settings.replayGain, {
            when (it) {
                ReplayGainMode.Off -> "Off"
                ReplayGainMode.Track -> "Song"
                ReplayGainMode.Album -> "Album"
                ReplayGainMode.Smart -> "Smart"
            }
        }, { mode -> sound.update { it.copy(replayGain = mode) } })
        Txt(ReplayGainHelp.getValue(settings.replayGain), OctoType.caption, OctoColors.TextMuted)
        if (settings.replayGain != ReplayGainMode.Off) {
            DbSlider("Preamp", settings.replayGainPreampDb, ReplayGainPreampRange, sound) { s, db -> s.copy(replayGainPreampDb = db) }
            DbSlider("Songs without loudness info", settings.replayGainFallbackDb, FallbackRange, sound) { s, db -> s.copy(replayGainFallbackDb = db) }
            SwitchLine("Prevent clipping", "Turns a song down rather than letting a boost distort it.", settings.preventClipping) { on ->
                sound.update { it.copy(preventClipping = on) }
            }
        }
        SwitchLine("Limiter", "Catches any peak that would still distort, just below full volume.", settings.limiter) { on ->
            sound.update { it.copy(limiter = on) }
        }
    }
}

@Composable
private fun BalanceCard(sound: SoundController) {
    val settings by sound.current.collectAsState()
    SettingsCard("Balance") {
        SliderLine(
            "Left and right",
            readBalance(settings.balance),
            (settings.balance + 1f) / 2f,
            { x ->
                // Snaps to the middle when close, so centred is easy to find.
                val value = (x * 2f - 1f).let { if (abs(it) < 0.04f) 0f else (it * 100f).roundToInt() / 100f }
                sound.preview { it.copy(balance = value) }
            },
            onRelease = sound::settle,
            wheelStep = 0.05f,
        )
        SwitchLine("Mono", "Both ears hear the same thing. Handy with one earbud.", settings.mono) { on ->
            sound.update { it.copy(mono = on) }
        }
    }
}

// The balance in words: "Centre", "20% left".
private fun readBalance(balance: Float): String {
    val percent = (abs(balance) * 100).roundToInt()
    return when {
        percent == 0 -> "Centre"
        balance < 0 -> "$percent% left"
        else -> "$percent% right"
    }
}

// Crossfade, speed and pitch: for every output, not one at a time.
@Composable
private fun PlaybackCard(app: AppState, playback: PlaybackPrefs) {
    fun change(edit: (PlaybackPrefs) -> PlaybackPrefs) = app.settings.update { it.copy(playback = edit(it.playback)) }
    SettingsCard("Crossfade and speed") {
        SliderLine(
            "Crossfade",
            if (playback.crossfadeSeconds == 0) "Off" else "${playback.crossfadeSeconds} s",
            playback.crossfadeSeconds / 12f,
            { x -> change { it.copy(crossfadeSeconds = (x * 12).roundToInt()) } },
            live = false,
            wheelStep = 1f / 12f,
        )
        Txt("Songs from one album played in order still run straight into each other.", OctoType.caption, OctoColors.TextMuted)
        SliderLine(
            "Speed",
            speedLabel(playback.speed),
            speedFraction(playback.speed),
            { x -> change { it.copy(speed = speedAt(x)) } },
            live = false,
            wheelStep = 0.05f / (FASTEST_SPEED - SLOWEST_SPEED),
        )
        SwitchLine("Keep the pitch", "Voices stay where they are at other speeds. Off, they rise and fall like a record.", playback.keepPitch) { on ->
            change { it.copy(keepPitch = on) }
        }
        SliderLine(
            "Pitch",
            semitonesLabel(playback.pitchSemitones),
            semitonesFraction(playback.pitchSemitones),
            { x -> change { it.copy(pitchSemitones = semitonesAt(x)) } },
            live = false,
            wheelStep = 1f / (2 * PITCH_RANGE_SEMITONES),
        )
        if (playback.speed != 1f || !playback.keepPitch || playback.pitchSemitones != 0) {
            Row { TextAction("Back to normal", { change { it.copy(speed = 1f, keepPitch = true, pitchSemitones = 0) } }) }
        }
    }
}

// A correction for the headphones in use, read from a ParametricEQ.txt
// file, and the whole curve written out as one.
@Composable
private fun CorrectionCard(sound: SoundController) {
    val settings by sound.current.collectAsState()
    var problem by remember { mutableStateOf<String?>(null) }
    val correction = settings.correction
    SettingsCard("Headphone correction") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Txt(correction?.name ?: "None", OctoType.bodySmall)
                Txt(
                    when {
                        correction == null -> "Evens out how your headphones colour the sound."
                        correction.source == ParametricEqFile.IMPORTED -> "Imported from a file"
                        else -> "Measured by ${correction.source}"
                    },
                    OctoType.caption,
                    OctoColors.TextMuted,
                )
            }
            if (correction != null) TextAction("Remove", { sound.update { it.copy(correction = null) } })
        }
        problem?.let { Txt(it, OctoType.caption, OctoColors.TextMuted) }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlazeCapsule(null, "Import correction", {
                val file = chooseFile("Choose a ParametricEQ.txt file", save = false) ?: return@GlazeCapsule
                val read = runCatching { ParametricEqFile.correction(file.name, file.readText()) }.getOrNull()
                problem = if (read == null) "That file has no filters Octo can read." else null
                if (read != null) sound.update { it.copy(eqEnabled = true, correction = read) }
            }, height = 34.dp)
            GlazeCapsule(null, "Export my curve", {
                val file = chooseFile("Save the curve", save = true, suggested = "Octo ParametricEQ.txt") ?: return@GlazeCapsule
                val on = settings.copy(eqEnabled = true)
                problem = if (runCatching { file.writeText(ParametricEqFile.write(on.effectivePreampDb(), on.activeFilters())) }.isSuccess) null else "The file could not be saved."
            }, height = 34.dp)
        }
    }
}

// The system's own file window, for one file to open or save.
private fun chooseFile(title: String, save: Boolean, suggested: String? = null): File? {
    val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    suggested?.let { dialog.file = it }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
