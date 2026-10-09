package app.winters.octo.ui.family

import app.winters.octo.subsonic.DeviceQualityMode
import app.winters.octo.subsonic.FamilyQuality
import app.winters.octo.subsonic.StreamQuality

const val AUDIO_QUALITY = "Audio quality"
const val QUALITY_AT_HOME = "At home"
const val QUALITY_AWAY = "Away from home"
const val QUALITY_ON_THIS_DEVICE = "Quality on this device"

fun qualityName(quality: StreamQuality): String = when (quality) {
    StreamQuality.Original -> "Original"
    StreamQuality.High -> "High"
    StreamQuality.Standard -> "Standard"
    StreamQuality.DataSaver -> "Data saver"
}

fun qualityLine(quality: StreamQuality): String = when (quality) {
    StreamQuality.Original -> "The file as it is, lossless when it is"
    StreamQuality.High -> "256 kbps"
    StreamQuality.Standard -> "160 kbps"
    StreamQuality.DataSaver -> "96 kbps, for slow or metered connections"
}

// One choice of quality, and whether the family's limit holds it lower
// than it says: Original, or any above the limit, then plays at the limit.
data class QualityOption(val quality: StreamQuality, val limited: Boolean) {
    val name: String get() = qualityName(quality)
    val line: String get() = qualityLine(quality)
}

fun qualityOptions(limitKbps: Int): List<QualityOption> = StreamQuality.entries.map { quality ->
    QualityOption(quality, limited = limitKbps > 0 && (quality.kbps == 0 || quality.kbps > limitKbps))
}

// The home and away limits that apply: away is the home limit unless the
// family set a lower one for away.
fun homeLimit(quality: FamilyQuality): Int = quality.familyLimitKbps

fun awayLimit(quality: FamilyQuality): Int = when {
    quality.familyAwayLimitKbps <= 0 -> quality.familyLimitKbps
    quality.familyLimitKbps <= 0 -> quality.familyAwayLimitKbps
    else -> minOf(quality.familyLimitKbps, quality.familyAwayLimitKbps)
}

// The family's limits, in plain words, or nothing when there are none.
fun familyLimitLines(quality: FamilyQuality): List<String> = buildList {
    val home = quality.familyLimitKbps
    val away = quality.familyAwayLimitKbps
    if (home > 0) add("Your family plan limits listening to $home kbps")
    if (away > 0 && (home <= 0 || away < home)) add("Your family plan limits away listening to $away kbps")
}

fun deviceModeName(mode: DeviceQualityMode): String = when (mode) {
    DeviceQualityMode.Account -> "Use my account setting"
    DeviceQualityMode.App -> "Let this app decide"
}

fun deviceModeLine(mode: DeviceQualityMode): String = when (mode) {
    DeviceQualityMode.Account -> "The server sends what you chose above, at home and away."
    DeviceQualityMode.App -> "This app's own quality setting decides, within any family limit."
}

// Whether this app's own stream quality setting applies: always without a
// family, and with one only when the device is left to the app. Otherwise
// the app asks for the file as it is and the server applies the account's
// choice.
fun appPicksQuality(familyOn: Boolean, mode: DeviceQualityMode?): Boolean = !familyOn || mode == DeviceQualityMode.App

// What a stream asks the server for: the file as it is, unless this app
// picks the quality and wants less, then Opus at that bitrate.
fun streamParams(appPicks: Boolean, quality: StreamQuality): Map<String, String> =
    if (!appPicks || quality == StreamQuality.Original) mapOf("format" to "raw")
    else mapOf("format" to "opus", "maxBitRate" to quality.kbps.toString())
