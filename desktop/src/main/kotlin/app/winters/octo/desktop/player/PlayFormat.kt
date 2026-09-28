package app.winters.octo.desktop.player

import app.winters.octo.subsonic.Song

// What the song playing is made of: its codec ("flac", "mp3", lowercase),
// whether it is lossless, its rate in hertz, bits and channels when known,
// and for a lossy song the bit rate in kbps the library gives.
data class SongFormat(
    val codec: String,
    val lossless: Boolean,
    val sampleRate: Int? = null,
    val bits: Int? = null,
    val channels: Int? = null,
    val bitRate: Int? = null,
)

// What the sound device runs at: its rate in hertz, channels, and the
// samples it takes (bits, and whether they are floats).
data class DeviceFormat(val sampleRate: Int, val channels: Int, val bits: Int?, val float: Boolean)

// The song's format and the device's, side by side. The song is the
// decoder's word once it starts playing, the library's until then.
data class PlayFormat(val song: SongFormat?, val output: DeviceFormat?) {
    // The song reaches the device at another rate, so it is converted.
    val resampled: Boolean
        get() = song?.sampleRate != null && output != null && song.sampleRate != output.sampleRate
}

// Formats that keep every bit of the recording.
private val LosslessCodecs = setOf("flac", "alac", "wav", "aiff", "aif", "pcm", "ape", "wv", "dsf", "dff")

// The format as the library tells it, from the file's suffix and the
// server's rate, bits and bit rate. Null when the library does not say.
fun libraryFormat(song: Song): SongFormat? {
    val suffix = song.suffix?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    val codec = when {
        // An m4a with a bit depth is Apple Lossless; without one, AAC.
        suffix == "m4a" || suffix == "mp4" -> if ((song.bitDepth ?: 0) > 0) "alac" else "aac"
        suffix == "ogg" || suffix == "oga" -> "vorbis"
        else -> suffix
    }
    return SongFormat(
        codec = codec,
        lossless = codec in LosslessCodecs,
        sampleRate = song.samplingRate?.takeIf { it > 0 },
        bits = song.bitDepth?.takeIf { it > 0 },
        bitRate = song.bitRate?.takeIf { it > 0 },
    )
}

// The codec's name as people know it.
fun codecName(codec: String): String = when (codec.lowercase()) {
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "wv" -> "WavPack"
    "aif" -> "AIFF"
    "dsf", "dff" -> "DSD"
    else -> codec.uppercase()
}

// A rate in kilohertz, as short as it goes: 44100 is "44.1", 48000 is "48".
fun kilohertz(hz: Int): String {
    val whole = hz / 1000
    val rest = (hz % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (rest.isEmpty()) "$whole" else "$whole.$rest"
}

// The short label for the player bar: "FLAC 16/44.1" for lossless, "MP3
// 320" for lossy (its bit rate), or the codec alone when that is all
// there is. Null with no song.
fun formatLabel(format: PlayFormat?): String? {
    val song = format?.song ?: return null
    val name = codecName(song.codec)
    val rate = song.sampleRate?.let(::kilohertz)
    return when {
        song.lossless && song.bits != null && rate != null -> "$name ${song.bits}/$rate"
        song.lossless && rate != null -> "$name $rate"
        !song.lossless && song.bitRate != null -> "$name ${song.bitRate}"
        else -> name
    }
}

// The song's format in words, for an Info panel: "Lossless FLAC, 24-bit,
// 96 kHz, stereo" or "MP3, 320 kbps, 44.1 kHz, stereo". Null with no song.
fun songFormatWords(format: PlayFormat?): String? {
    val song = format?.song ?: return null
    val parts = mutableListOf(if (song.lossless) "Lossless ${codecName(song.codec)}" else codecName(song.codec))
    if (!song.lossless) song.bitRate?.let { parts += "$it kbps" }
    song.bits?.let { parts += "$it-bit" }
    song.sampleRate?.let { parts += "${kilohertz(it)} kHz" }
    song.channels?.let { parts += channelWords(it) }
    return parts.joinToString(", ")
}

// What the device plays at, in a sentence for an Info panel: "Playing at
// 48 kHz, 32-bit float on Speakers, resampled from 44.1 kHz". `device` is
// the device's name. Null until a device is open.
fun outputSentence(format: PlayFormat?, device: String?): String? {
    val output = format?.output ?: return null
    val samples = when {
        output.bits != null && output.float -> ", ${output.bits}-bit float"
        output.bits != null -> ", ${output.bits}-bit"
        output.float -> ", float"
        else -> ""
    }
    val channels = if (output.channels == 2) "" else ", ${channelWords(output.channels)}"
    val on = device?.takeIf(String::isNotBlank)?.let { " on $it" } ?: ""
    val from = format.song?.sampleRate?.takeIf { format.resampled }?.let { ", resampled from ${kilohertz(it)} kHz" } ?: ""
    return "Playing at ${kilohertz(output.sampleRate)} kHz$samples$channels$on$from"
}

private fun channelWords(channels: Int): String = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    else -> "$channels channels"
}
