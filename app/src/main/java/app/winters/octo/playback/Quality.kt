package app.winters.octo.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

// A short line about how the song is encoded, like "FLAC · 24-bit · 96 kHz"
// or "MP3 · 320 kbps". It uses what the decoder reports once the song is
// open, and the file type before then.
fun qualityLabel(fileMime: String?, tracks: Tracks): String? {
    val format = tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
    val codec = codecName(format?.sampleMimeType) ?: codecName(fileMime) ?: return null
    val parts = mutableListOf(codec)
    if (format != null) {
        val lossless = codec in Lossless
        bitDepth(format.pcmEncoding)?.takeIf { lossless }?.let { parts += "$it-bit" }
        val bitrate = format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 }
        if (!lossless && bitrate != null) parts += "${bitrate / 1000} kbps"
        if (format.sampleRate != Format.NO_VALUE) parts += sampleRate(format.sampleRate)
    }
    return parts.joinToString(" · ")
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

// 44100 reads as "44.1 kHz", 48000 as "48 kHz".
private fun sampleRate(hz: Int): String {
    val khz = hz / 1000.0
    return if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(khz)
}
