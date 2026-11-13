package app.winters.octo.desktop.sound

import app.winters.octo.audio.AutomixSettings
import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.ReplayGainSettings
import app.winters.octo.desktop.audio.SoundTarget
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.SHARED_SOUND
import app.winters.octo.desktop.settings.SavedCurve
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.SoundPrefs
import app.winters.octo.playback.Pace
import app.winters.octo.playback.automixSettingsOf
import app.winters.octo.playback.crossfadeMsOf
import app.winters.octo.playback.paceOf
import app.winters.octo.sound.EqPreset
import app.winters.octo.sound.EqPresets
import app.winters.octo.sound.SoundSettings
import app.winters.octo.ui.sound.CUSTOM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// How often a drag saves while the pointer moves.
const val SAVE_EVERY_MS = 100L

// Which saved set a device uses: its own with "each output its own sound"
// on, otherwise the shared one.
fun soundKey(prefs: SoundPrefs, device: String?): String = if (prefs.perOutput && device != null) device else SHARED_SOUND

// The sound for a device. An output never set up starts from the shared
// set, as on the phone.
fun soundFor(prefs: SoundPrefs, device: String?): SoundSettings =
    prefs.profiles[soundKey(prefs, device)] ?: prefs.profiles[SHARED_SOUND] ?: SoundSettings()

// Everything the engine is told about the sound at once.
data class EngineSound(
    val eq: EqSettings,
    val replayGain: ReplayGainSettings,
    val dsp: DspSettings,
    val crossfadeMs: Int,
    val automix: AutomixSettings,
    val pace: Pace,
)

// What the engine is told, from the settings and the device playing. The
// blends come from the Between songs choice as on every app: none for
// Gapless, Crossfade's own length and curve, or Automix's planned blends
// with its longest intro.
fun engineSound(settings: AppSettings, device: String?): EngineSound {
    val sound = soundFor(settings.sound, device)
    val playback = settings.playback
    val blends = automixSettingsOf(playback.betweenSongs, playback.crossfadeLength, playback.crossfadeCurve, playback.filterSweeps, playback.matchTempo)
    return EngineSound(
        eq = sound.engineEq(),
        replayGain = sound.engineReplayGain(),
        dsp = sound.engineDsp(),
        crossfadeMs = crossfadeMsOf(playback.betweenSongs, playback.crossfadeLength).toInt(),
        automix = AutomixSettings(
            smartTransitions = blends.smart,
            filterSweeps = blends.filterSweeps,
            matchTempo = blends.beatMatch,
            maxOverlapMs = blends.maxOverlapMs.toUInt(),
            fadeCurve = blends.fadeCurve.engine(),
            longestIntroMs = blends.longestIntroMs.toUInt(),
        ),
        pace = paceOf(playback.speed, playback.keepPitch, playback.pitchSemitones),
    )
}

// Keeps the engine sounding the way the Sound page says, for the output
// playing now: when a setting changes, and when sound moves to another
// device (headphones plugged in, a USB DAC picked), whose own set then
// applies. Only what changed is sent, so the engine's glides are not
// restarted for nothing.
class SoundController(private val target: SoundTarget, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private var applied: EngineSound? = null

    // The settings as a drag has left them, ahead of the saved ones, with
    // the saved set they belong to: heard at once while that set is the one
    // in use, while the file is written at most every SAVE_EVERY_MS.
    private class Draft(val key: String, val sound: SoundSettings)

    private val draft = MutableStateFlow<Draft?>(null)
    private var pending: ((SoundSettings) -> SoundSettings)? = null
    private var writer: Job? = null

    // The drag's settings while its set is the one in use; after sound moved
    // to another output, that output's own.
    private fun shownSound(s: AppSettings, device: String?, moving: Draft?): SoundSettings? =
        moving?.takeIf { it.key == soundKey(s.sound, device) }?.sound

    // The set the Sound page shows and changes.
    val current: StateFlow<SoundSettings> = combine(settings.state, target.deviceKey, draft) { s, device, moving -> shownSound(s, device, moving) ?: soundFor(s.sound, device) }
        .stateIn(scope, SharingStarted.Eagerly, soundFor(settings.current.sound, target.deviceKey.value))

    init {
        scope.launch {
            combine(settings.state, target.deviceKey, draft) { s, device, moving ->
                val heard = shownSound(s, device, moving)
                val shown = if (heard == null) s else s.copy(sound = s.sound.copy(profiles = s.sound.profiles + (soundKey(s.sound, device) to heard)))
                engineSound(shown, device)
            }.collect(::apply)
        }
    }

    // A change from a moving pointer: heard and shown at once, saved at
    // most every SAVE_EVERY_MS, and once more by `settle`. Each edit sets
    // values outright, so saving only the latest loses nothing. The drag
    // keeps to the set it started on, even if sound moves to another output
    // before it is let go.
    fun preview(change: (SoundSettings) -> SoundSettings) {
        val before = draft.value ?: Draft(soundKey(settings.current.sound, target.deviceKey.value), current.value)
        draft.value = Draft(before.key, change(before.sound))
        pending = change
        if (writer?.isActive != true) {
            writer = scope.launch {
                while (true) {
                    val edit = pending ?: break
                    pending = null
                    draft.value?.let { updateSet(it.key, edit) }
                    delay(SAVE_EVERY_MS)
                }
            }
        }
    }

    // The pointer let go: save the last position now, to the set it moved.
    fun settle() {
        val moving = draft.value
        pending?.let {
            pending = null
            if (moving != null) updateSet(moving.key, it)
        }
        writer?.cancel()
        draft.value = null
    }

    // Changes one saved set, starting from it as saved (or from the shared
    // set, for an output never set up).
    private fun updateSet(key: String, change: (SoundSettings) -> SoundSettings) {
        settings.update { s ->
            val from = s.sound.profiles[key] ?: s.sound.profiles[SHARED_SOUND] ?: SoundSettings()
            s.copy(sound = s.sound.copy(profiles = s.sound.profiles + (key to change(from))))
        }
    }

    private fun apply(sound: EngineSound) {
        val before = applied
        applied = sound
        if (before == null || before.eq != sound.eq || before.replayGain != sound.replayGain || before.dsp != sound.dsp) {
            target.shape(sound.eq, sound.replayGain, sound.dsp)
        }
        if (before?.crossfadeMs != sound.crossfadeMs) target.setCrossfade(sound.crossfadeMs)
        if (before?.automix != sound.automix) target.setAutomix(sound.automix)
        if (before?.pace != sound.pace) target.setSpeed(sound.pace.speed, sound.pace.pitch)
    }

    // Changes the set for the output playing now (or the shared one).
    fun update(change: (SoundSettings) -> SoundSettings) {
        val device = target.deviceKey.value
        settings.update { s ->
            val key = soundKey(s.sound, device)
            s.copy(sound = s.sound.copy(profiles = s.sound.profiles + (key to change(soundFor(s.sound, device)))))
        }
    }

    // Whether each output keeps its own sound. Turning it on starts every
    // output from the shared set.
    fun setPerOutput(on: Boolean) = settings.update { it.copy(sound = it.sound.copy(perOutput = on)) }

    // The built-in curves, then the listener's own.
    fun presets(): List<EqPreset> = EqPresets + settings.current.sound.presets.map { EqPreset(it.name, it.gains) }

    // Saves the ten bands under a name, and marks the curve as that preset.
    fun savePreset(name: String) {
        val clean = name.trim()
        if (!canSaveAs(clean)) return
        val gains = current.value.graphicGains
        settings.update { s -> s.copy(sound = s.sound.copy(presets = s.sound.presets.filterNot { it.name == clean } + SavedCurve(clean, gains))) }
        update { it.copy(preset = clean) }
    }

    fun deletePreset(name: String) {
        settings.update { s -> s.copy(sound = s.sound.copy(presets = s.sound.presets.filterNot { it.name == name })) }
        if (current.value.preset == name) update { it.copy(preset = null) }
    }

    // A name can be used unless a built-in preset has it.
    fun canSaveAs(name: String): Boolean {
        val clean = name.trim()
        return clean.isNotEmpty() && !clean.equals(CUSTOM, ignoreCase = true) && EqPresets.none { it.name.equals(clean, ignoreCase = true) }
    }
}
