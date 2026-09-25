package app.winters.octo.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

// How a song is encoded, for the badge under the progress line: a short
// tier like "Lossless" or "Opus", and the details after it, like "FLAC 24/96"
// or "128 kbps".
data class AudioQuality(val tier: String, val detail: String?)

// Uses what the decoder reports once the song is open, and the file type
// before then.
fun audioQuality(fileMime: String?, tracks: Tracks): AudioQuality? {
    val format = tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
    return audioQuality(
        codecMime = format?.sampleMimeType,
        fileMime = fileMime,
        pcmEncoding = format?.pcmEncoding ?: Format.NO_VALUE,
        sampleRate = format?.sampleRate ?: Format.NO_VALUE,
        bitrate = format?.let { it.averageBitrate.takeIf { rate -> rate > 0 } ?: it.bitrate } ?: Format.NO_VALUE,
    )
}

internal fun audioQuality(
    codecMime: String?,
    fileMime: String?,
    pcmEncoding: Int,
    sampleRate: Int,
    bitrate: Int,
): AudioQuality? {
    val codec = codecName(codecMime) ?: codecName(fileMime) ?: return null
    if (codec in Lossless) {
        // Written the short way, like 24/96 for 24-bit at 96 kHz.
        val spec = listOfNotNull(bitDepth(pcmEncoding)?.toString(), sampleRate.takeIf { it > 0 }?.let(::kilohertz))
            .joinToString("/")
        // Above 48 kHz counts as high resolution.
        val tier = if (sampleRate > 48_000) "Hi-Res Lossless" else "Lossless"
        return AudioQuality(tier, listOf(codec, spec).filter { it.isNotEmpty() }.joinToString(" "))
    }
    val detail = bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }
        ?: sampleRate.takeIf { it > 0 }?.let { "${kilohertz(it)} kHz" }
    return AudioQuality(codec, detail)
}

private val Lossless = setOf("FLAC", "ALAC", "WAV")

private fun codecName(mime: String?): String? = when (mime?.lowercase()) {
    null -> null
    MimeTypes.AUDIO_FLAC, "audio/x-flac" -> "FLAC"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_MPEG_L1 -> "MP3"
    MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MP4, "audio/x-m4a", "audio/m4a", "audio/aac-adts" -> "AAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_OGG, "application/ogg" -> "Ogg"
    MimeTypes.AUDIO_WAV, MimeTypes.AUDIO_RAW, "audio/x-wav", "audio/wave" -> "WAV"
    else -> null
}

private fun bitDepth(encoding: Int): Int? = when (encoding) {
    C.ENCODING_PCM_8BIT -> 8
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
    else -> null
}

// 44100 reads as "44.1", 48000 as "48".
private fun kilohertz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000.0)
