package app.winters.octo.sound

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

// A song's loudness tags: how far to turn it up or down to reach the common
// level, in decibels, and its highest sample, where 1 is full scale. Any of
// them can be missing.
data class ReplayGainInfo(
    val trackGain: Float? = null,
    val trackPeak: Float? = null,
    val albumGain: Float? = null,
    val albumPeak: Float? = null,
) {
    val hasGain: Boolean get() = trackGain != null || albumGain != null
}

// What the audio path knows about the song that is about to play: its tags,
// and whether it follows a song from the same album.
data class SongLoudness(val replayGain: ReplayGainInfo?, val followsSameAlbum: Boolean)

// The newer loudness tags aim 5 dB quieter than the older ones.
private const val R128_TO_REPLAY_GAIN_DB = 5f

// Gains past this are a broken tag, not a real song.
private const val LARGEST_GAIN_DB = 64f

object ReplayGain {
    // Reads the tags from a song's metadata: ID3 user text frames, Vorbis
    // comments (FLAC, Ogg, Opus) and MP4 free-form tags. An MP3's own
    // encoder header is only used when none of those are there.
    @OptIn(UnstableApi::class)
    fun read(metadata: Metadata?): ReplayGainInfo? {
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

    // Reads the tags from name and value pairs, names in any case. The
    // standard tags win over the newer R128 ones when a song has both.
    fun fromTags(tags: Iterable<Pair<String, String>>): ReplayGainInfo? {
        var trackGain: Float? = null
        var trackPeak: Float? = null
        var albumGain: Float? = null
        var albumPeak: Float? = null
        var r128Track: Float? = null
        var r128Album: Float? = null
        for ((name, value) in tags) {
            when (name.trim().uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> trackGain = trackGain ?: parseGain(value)
                "REPLAYGAIN_TRACK_PEAK" -> trackPeak = trackPeak ?: parsePeak(value)
                "REPLAYGAIN_ALBUM_GAIN" -> albumGain = albumGain ?: parseGain(value)
                "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = albumPeak ?: parsePeak(value)
                "R128_TRACK_GAIN" -> r128Track = r128Track ?: parseR128(value)
                "R128_ALBUM_GAIN" -> r128Album = r128Album ?: parseR128(value)
            }
        }
        val info = ReplayGainInfo(trackGain ?: r128Track, trackPeak, albumGain ?: r128Album, albumPeak)
        return info.takeIf { it != ReplayGainInfo() }
    }

    // A gain like "-6.54 dB", "+2.1dB" or "-6,54 dB".
    fun parseGain(value: String): Float? {
        val text = value.trim()
        val bare = if (text.endsWith("db", ignoreCase = true)) text.dropLast(2) else text
        return number(bare.trim())?.takeIf { abs(it) <= LARGEST_GAIN_DB }
    }

    // A peak like "0.988251", where 1 is full scale.
    fun parsePeak(value: String): Float? = number(value.trim())?.takeIf { it > 0f }

    // An R128 gain: whole 256ths of a decibel, measured against a level
    // 5 dB quieter than the standard tags use.
    fun parseR128(value: String): Float? =
        value.trim().toIntOrNull()?.let { it / 256f + R128_TO_REPLAY_GAIN_DB }?.takeIf { abs(it) <= LARGEST_GAIN_DB }

    @OptIn(UnstableApi::class)
    private fun fromHeader(header: Mp3InfoReplayGain): ReplayGainInfo? {
        val fields = listOfNotNull(header.field1, header.field2)
        val track = fields.firstOrNull { it.name == Mp3InfoReplayGain.GainField.NAME_RADIO }?.gain
        val album = fields.firstOrNull { it.name == Mp3InfoReplayGain.GainField.NAME_AUDIOPHILE }?.gain
        val peak = header.peak.takeIf { it > 0f }
        return ReplayGainInfo(track, peak, album, null).takeIf { it.hasGain }
    }

    private fun number(text: String): Float? =
        text.removePrefix("+").replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
}

// How much to scale a song, as a multiplier. The gain is the chosen tag plus
// the ReplayGain preamp, or the fallback for a song with no tags. Album and
// track each fall back to the other when missing. Smart uses the album gain
// only while songs from one album follow each other, so an album keeps its
// own loud and quiet songs, and a mix levels every song. With clipping
// prevented, the song is never raised past its own peak.
fun replayGainFactor(settings: SoundSettings, song: SongLoudness?): Float {
    val useAlbum = when (settings.replayGain) {
        ReplayGainMode.Off -> return 1f
        ReplayGainMode.Track -> false
        ReplayGainMode.Album -> true
        ReplayGainMode.Smart -> song?.followsSameAlbum == true
    }
    val info = song?.replayGain
    val (gain, peak) = when {
        info == null || !info.hasGain -> null to null
        useAlbum && info.albumGain != null -> info.albumGain to (info.albumPeak ?: info.trackPeak)
        useAlbum -> info.trackGain to (info.trackPeak ?: info.albumPeak)
        info.trackGain != null -> info.trackGain to (info.trackPeak ?: info.albumPeak)
        else -> info.albumGain to (info.albumPeak ?: info.trackPeak)
    }
    val db = if (gain == null) settings.replayGainFallbackDb else gain + settings.replayGainPreampDb
    val factor = 10f.pow(db / 20f)
    return if (settings.preventClipping && peak != null && peak > 0f) min(factor, 1f / peak) else factor
}

// Remembers the album of the last song that started, across both decks, for
// Smart ReplayGain. The rule: a song uses its album gain when the song
// before it came from the same album. A song set up again (the same song
// twice in a row, as the player reopens it) keeps the answer it had.
class AlbumRun {
    private var lastSong: String? = null
    private var lastAlbum: String? = null
    private var lastAnswer = false

    @Synchronized
    fun follows(song: String?, album: String?): Boolean {
        if (song != null && song == lastSong) return lastAnswer
        lastAnswer = album != null && album == lastAlbum
        lastSong = song
        lastAlbum = album
        return lastAnswer
    }
}

// The loudness a song plays at: its own tags when the sound carries them,
// otherwise the values its source keeps, such as a server's for a stream
// made smaller on the way, which loses its tags. Peaks alone say nothing
// about how loud to play, so stored gains win over a stream with only those.
fun chooseReplayGain(stream: ReplayGainInfo?, stored: ReplayGainInfo?): ReplayGainInfo? = when {
    stream?.hasGain == true -> stream
    stored?.hasGain == true -> stored
    else -> stream
}

// Loudness values a source kept, checked the way tags are: a gain past the
// limit or a peak that is not above zero is left out. Nothing without a gain.
fun storedReplayGain(trackGain: Float?, trackPeak: Float?, albumGain: Float?, albumPeak: Float?): ReplayGainInfo? {
    fun gain(value: Float?) = value?.takeIf { it.isFinite() && abs(it) <= LARGEST_GAIN_DB }
    fun peak(value: Float?) = value?.takeIf { it.isFinite() && it > 0f }
    return ReplayGainInfo(gain(trackGain), peak(trackPeak), gain(albumGain), peak(albumPeak)).takeIf { it.hasGain }
}
