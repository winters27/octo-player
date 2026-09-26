package app.winters.octo.playback

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.sound.ReplayGainInfo
import app.winters.octo.sound.storedReplayGain

// Which copy plays when a song is both on the phone and on a server.
enum class CopyPreference { PhoneFirst, BestQuality }

// How big a stream is: the server's file as it is, or an MP3 made on the
// way at most this many kilobits a second.
enum class StreamQuality(val kbps: Int?) {
    Original(null),
    Kbps320(320),
    Kbps256(256),
    Kbps192(192),
    Kbps128(128),
}

// A copy from a server streams; any other copy is a file on the phone.
val SourceTrackEntity.isServerCopy: Boolean get() = sourceId.startsWith("server:")

// The copy of a song to play. The setting decides between copies that can
// play right now: a server's when one can be reached, the phone's while its
// file is still there. When none can, one is still handed over, so it fails
// and is skipped like any song that cannot play. A tie goes to the phone.
fun chooseCopy(
    copies: List<SourceTrackEntity>,
    preference: CopyPreference,
    serverReachable: Boolean,
    phoneFileMissing: (SourceTrackEntity) -> Boolean = { false },
): SourceTrackEntity? {
    val ordered = copies.sortedBy { it.isServerCopy }
    val playable = ordered.filter { if (it.isServerCopy) serverReachable else !phoneFileMissing(it) }
    val pool = playable.ifEmpty { ordered }
    if (pool.isEmpty()) return null
    val best = pool.reduce { best, next -> if (compareQuality(next, best) > 0) next else best }
    return when (preference) {
        CopyPreference.PhoneFirst -> pool.firstOrNull { !it.isServerCopy } ?: best
        CopyPreference.BestQuality -> best
    }
}

// Above zero when `a` sounds better than `b`: lossless beats lossy, then the
// higher sample rate, the deeper bits, and last the higher bitrate. A fact
// only one of them has is skipped.
internal fun compareQuality(a: SourceTrackEntity, b: SourceTrackEntity): Int {
    isLossless(a.mimeType).compareTo(isLossless(b.mimeType)).takeIf { it != 0 }?.let { return it }
    compareKnown(a.sampleRate, b.sampleRate).takeIf { it != 0 }?.let { return it }
    compareKnown(a.bitDepth, b.bitDepth).takeIf { it != 0 }?.let { return it }
    return compareKnown(bitrateOf(a), bitrateOf(b))
}

private fun compareKnown(a: Int?, b: Int?): Int =
    if (a == null || b == null || a <= 0 || b <= 0) 0 else a.compareTo(b)

private val LosslessTypes = setOf(
    "audio/flac", "audio/x-flac", "audio/alac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
    "audio/aiff", "audio/x-aiff", "audio/ape", "audio/x-ape", "audio/wavpack", "audio/x-wavpack",
)

fun isLossless(mimeType: String?): Boolean = mimeType?.lowercase() in LosslessTypes

// Bits per second, as the source says, or worked out from the file's size
// and length. Servers speak in kilobits, so a small number is read as that.
fun bitrateOf(copy: SourceTrackEntity): Int? {
    copy.bitrate?.takeIf { it > 0 }?.let { return if (it < 10_000) it * 1000 else it }
    val size = copy.sizeBytes?.takeIf { it > 0 } ?: return null
    val seconds = copy.durationMs.takeIf { it > 0 }?.div(1000.0) ?: return null
    return (size * 8 / seconds).toInt()
}

// What to ask the server for: its file as it is, or an MP3 capped at
// `maxKbps`. MP3 because every server that makes smaller streams offers it,
// and a constant-bitrate MP3 has a length the player can seek in and blend.
data class StreamRequest(val maxKbps: Int?) {
    val params: Map<String, String>
        get() = if (maxKbps == null) {
            mapOf("format" to "raw")
        } else {
            mapOf("format" to "mp3", "maxBitRate" to "$maxKbps", "estimateContentLength" to "true")
        }

    // What the player will receive, for the quality badge.
    fun mimeType(original: String?): String? = if (maxKbps == null) original else "audio/mpeg"
}

// A lossy file already within the size plays as it is: making it an MP3
// would only lose quality.
fun streamRequest(mimeType: String?, bitrate: Int?, quality: StreamQuality): StreamRequest {
    val cap = quality.kbps ?: return StreamRequest(null)
    if (!isLossless(mimeType) && bitrate != null && bitrate / 1000 <= cap) return StreamRequest(null)
    return StreamRequest(cap)
}

// The loudness a copy's source keeps for it, if any.
fun SourceTrackEntity.storedLoudness(): ReplayGainInfo? = storedReplayGain(trackGain, trackPeak, albumGain, albumPeak)

// The stored loudness to play a song at: the playing copy's, or failing that
// another copy's of the same song, since each copy is the same recording.
fun storedLoudness(playing: SourceTrackEntity?, copies: List<SourceTrackEntity>): ReplayGainInfo? =
    playing?.storedLoudness() ?: copies.firstNotNullOfOrNull { it.storedLoudness() }
