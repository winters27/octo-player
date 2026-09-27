package app.winters.octo.desktop.sound

import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.ReplayGainSettings
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.FilterType
import app.winters.octo.sound.ReplayGainMode
import app.winters.octo.sound.SoundSettings
import app.winters.octo.audio.EqFilter as EngineFilter
import app.winters.octo.audio.EqMode as EngineEqMode
import app.winters.octo.audio.FilterType as EngineFilterType
import app.winters.octo.audio.HeadphoneCorrection as EngineCorrection
import app.winters.octo.audio.ReplayGainMode as EngineGainMode
import app.winters.octo.sound.EqFilter as SharedFilter

// The phone's sound settings (shared core) as the audio engine takes them.
// The engine does the same maths as the phone, so these only move values
// across, one to one.

fun SoundSettings.engineEq(): EqSettings = EqSettings(
    enabled = eqEnabled,
    mode = when (mode) {
        EqMode.Graphic -> EngineEqMode.GRAPHIC
        EqMode.Parametric -> EngineEqMode.PARAMETRIC
    },
    graphicGains = graphicGains,
    filters = filters.map { it.engine() },
    preampDb = preampDb,
    autoPreamp = autoPreamp,
    correction = correction?.let { EngineCorrection(it.name, it.source, it.preampDb, it.filters.map { f -> f.engine() }) },
)

fun SoundSettings.engineReplayGain(): ReplayGainSettings = ReplayGainSettings(
    mode = when (replayGain) {
        ReplayGainMode.Off -> EngineGainMode.OFF
        ReplayGainMode.Track -> EngineGainMode.TRACK
        ReplayGainMode.Album -> EngineGainMode.ALBUM
        ReplayGainMode.Smart -> EngineGainMode.SMART
    },
    preampDb = replayGainPreampDb,
    fallbackDb = replayGainFallbackDb,
    preventClipping = preventClipping,
)

fun SoundSettings.engineDsp(): DspSettings = DspSettings(limiter = limiter, balance = balance.coerceIn(-1f, 1f), mono = mono)

private fun SharedFilter.engine() = EngineFilter(
    filterType = when (type) {
        FilterType.Peak -> EngineFilterType.PEAK
        FilterType.LowShelf -> EngineFilterType.LOW_SHELF
        FilterType.HighShelf -> EngineFilterType.HIGH_SHELF
    },
    frequency = frequency,
    gainDb = gainDb,
    q = q,
    enabled = enabled,
)
