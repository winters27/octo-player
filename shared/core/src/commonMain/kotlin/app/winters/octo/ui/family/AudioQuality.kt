package app.winters.octo.ui.family

import app.winters.octo.offline.smallerKbps
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
    StreamQuality.Original -> ORIGINAL_LINE
    StreamQuality.High -> "256 kbps"
    StreamQuality.Standard -> "160 kbps"
    StreamQuality.DataSaver -> "96 kbps, for slow or metered connections"
}

// Original, as the pickers say it: the file as it is stored, sent without
// being made smaller.
const val ORIGINAL_LINE = "As the file is, FLAC stays FLAC"
const val ORIGINAL_LABEL = "Original (as the file is, FLAC stays FLAC)"

// Why Original cannot be picked under a family limit of `limitKbps`, or
// null when it can (no limit).
fun originalBlockedBy(limitKbps: Int): String? =
    if (limitKbps > 0) "Your family plan streams up to $limitKbps kbps" else null

// One choice of quality, and whether the family's limit holds it lower than
// it says: a bitrate above the limit then plays at the limit. Original
// cannot be picked under a limit, and `blocked` says why.
data class QualityOption(val quality: StreamQuality, val limited: Boolean, val blocked: String? = null) {
    val name: String get() = qualityName(quality)
    val line: String get() = blocked ?: qualityLine(quality)
    val enabled: Boolean get() = blocked == null
}

// Every quality, Original first, under a family limit of `limitKbps` (0 for
// none).
fun qualityOptions(limitKbps: Int): List<QualityOption> = StreamQuality.entries.map { quality ->
    val original = quality.kbps == 0
    QualityOption(
        quality,
        limited = limitKbps > 0 && (original || quality.kbps > limitKbps),
        blocked = if (original) originalBlockedBy(limitKbps) else null,
    )
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

// The note over this app's own quality while the account's choice applies.
// With "Quality on this device" shown, it names the answer there that hands
// the choice to this app, its words held together so a line never breaks
// inside the name; without it there is nothing to point at.
fun appQualityNote(deviceShown: Boolean): String =
    if (deviceShown) {
        "Your account's choice applies now. Choose \"${deviceModeName(DeviceQualityMode.App).replace(' ', ' ')}\" above to use these."
    } else {
        "Your account's choice applies now."
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

// What a stream of one song asks for: `params` (from streamParams), except
// that a lossy song already at or under the bitrate asked comes as it is,
// since a copy made smaller would be no smaller and sound worse.
// `bitRateKbps` is the song's own, as its listing says.
fun songParams(params: Map<String, String>, mimeType: String?, bitRateKbps: Int?): Map<String, String> {
    val cap = params["maxBitRate"]?.toIntOrNull() ?: return params
    return if (smallerKbps(mimeType, bitRateKbps?.let { it * 1000 }, cap) == null) mapOf("format" to "raw") else params
}

// A song's type as its listing gives it: its content type, else the one its
// suffix stands for.
fun listedAudioType(contentType: String?, suffix: String?): String? =
    contentType?.trim()?.takeIf { it.isNotEmpty() } ?: when (suffix?.trim()?.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "alac" -> "audio/alac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        "wav" -> "audio/wav"
        "aif", "aiff" -> "audio/aiff"
        "ape" -> "audio/ape"
        "wv" -> "audio/wavpack"
        else -> null
    }
