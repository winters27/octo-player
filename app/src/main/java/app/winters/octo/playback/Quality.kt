package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

// How a song is encoded, for the badge under the progress line.
data class AudioQuality(
    val codec: String,
    val lossless: Boolean,
    // Lossless above 48 kHz.
    val hiRes: Boolean,
    // Like "24-bit · 48 kHz" for lossless, or "128 kbps" for everything else.
    val specs: String?,
    // The song's sample rate, and the rate the phone's output runs at, when
    // known; they differ when the phone resamples.
    val songRate: Int? = null,
    val outputRate: Int? = null,
) {
    // What the badge says at a glance.
    val label: String get() = when {
        hiRes -> "Hi-Res"
        lossless -> "Lossless"
        else -> codec
    }

    // Everything, shown when the badge is tapped, with the output's rate
    // when the phone plays the song at another.
    val full: String get() = listOfNotNull(codec, specs, resampledTo?.let { "plays at ${kilohertz(it)} kHz" }).joinToString(" · ")

    private val resampledTo: Int? get() = outputRate?.takeIf { songRate != null && it != songRate }

    fun playingAt(rate: Int?): AudioQuality = copy(outputRate = rate)
}

// Uses what the decoder reports once the song is open, and the file type
// before then.
@OptIn(UnstableApi::class)
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

@OptIn(UnstableApi::class)
internal fun audioQuality(
    codecMime: String?,
    fileMime: String?,
    pcmEncoding: Int,
    sampleRate: Int,
    bitrate: Int,
): AudioQuality? {
    val codec = codecName(codecMime) ?: codecName(fileMime) ?: return null
    val rate = sampleRate.takeIf { it > 0 }?.let { "${kilohertz(it)} kHz" }
    if (codec in Lossless) {
        val specs = listOfNotNull(bitDepth(pcmEncoding)?.let { "$it-bit" }, rate).joinToString(" · ").ifEmpty { null }
        return AudioQuality(codec, lossless = true, hiRes = sampleRate > 48_000, specs = specs, songRate = sampleRate.takeIf { it > 0 })
    }
    val specs = bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: rate
    return AudioQuality(codec, lossless = false, hiRes = false, specs = specs, songRate = sampleRate.takeIf { it > 0 })
}

private val Lossless = setOf("FLAC", "ALAC", "WAV")

@OptIn(UnstableApi::class)
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

@OptIn(UnstableApi::class)
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
