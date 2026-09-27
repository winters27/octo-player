package app.winters.octo.sound

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain

// Reads the tags from a song's metadata: ID3 user text frames, Vorbis
// comments (FLAC, Ogg, Opus) and MP4 free-form tags. An MP3's own
// encoder header is only used when none of those are there.
@OptIn(UnstableApi::class)
fun ReplayGain.read(metadata: Metadata?): ReplayGainInfo? {
    if (metadata == null) return null
    val tags = mutableListOf<Pair<String, String>>()
    var header: Mp3InfoReplayGain? = null
    for (i in 0 until metadata.length()) {
        when (val entry = metadata.get(i)) {
            is TextInformationFrame -> if (entry.id == "TXXX") {
                val name = entry.description
                val value = entry.values.firstOrNull()
                if (name != null && value != null) tags += name to value
            }
            is VorbisComment -> tags += entry.key to entry.value
            is InternalFrame -> if (entry.domain.equals("com.apple.iTunes", ignoreCase = true)) {
                tags += entry.description to entry.text
            }
            is Mp3InfoReplayGain -> header = entry
        }
    }
    val fromTags = fromTags(tags)
    if (fromTags?.hasGain == true) return fromTags
    return header?.let(::fromHeader) ?: fromTags
}

@OptIn(UnstableApi::class)
private fun fromHeader(header: Mp3InfoReplayGain): ReplayGainInfo? {
    val fields = listOfNotNull(header.field1, header.field2)
    val track = fields.firstOrNull { it.name == Mp3InfoReplayGain.GainField.NAME_RADIO }?.gain
    val album = fields.firstOrNull { it.name == Mp3InfoReplayGain.GainField.NAME_AUDIOPHILE }?.gain
    val peak = header.peak.takeIf { it > 0f }
    return ReplayGainInfo(track, peak, album, null).takeIf { it.hasGain }
}
