package app.winters.octo.ui.library.health

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.splitLines
import app.winters.octo.health.HealthFields
import app.winters.octo.health.HealthTag
import app.winters.octo.playback.bitrateOf
import app.winters.octo.playback.isLossless
import app.winters.octo.query.isLosslessFormat
import app.winters.octo.sort.formatOf

// The phone's library health: the shared checks, read the phone's way.
//
// Copies of one recording are looked for among each source's own copies (a
// server's two files of a song), since the phone joins a song it holds
// with the server's copy on purpose and that is no problem. Everything else
// is checked on the library as the phone shows it. The phone keeps no ISRC,
// so copies are matched by their MusicBrainz recording, then their title,
// artist and length.

// A source's copy of a song, as the checks read it.
object SourceCopyHealth : HealthFields<SourceTrackEntity> {
    override fun id(song: SourceTrackEntity) = song.id
    override fun title(song: SourceTrackEntity) = song.title
    override fun artist(song: SourceTrackEntity) = song.artist.ifBlank { null }
    override fun album(song: SourceTrackEntity) = song.album.ifBlank { null }
    override fun albumId(song: SourceTrackEntity) = song.albumId
    override fun albumArtist(song: SourceTrackEntity): String? = null
    override fun genres(song: SourceTrackEntity) = splitLines(song.genres).ifEmpty { listOf(song.genre).filter(String::isNotBlank) }
    override fun year(song: SourceTrackEntity) = song.originalYear ?: song.year
    override fun disc(song: SourceTrackEntity) = song.discNo
    override fun track(song: SourceTrackEntity) = song.trackNo
    override fun seconds(song: SourceTrackEntity) = (song.durationMs / 1_000).toInt()
    override fun format(song: SourceTrackEntity) = formatOf(song.mimeType)
    override fun lossless(song: SourceTrackEntity) = isLossless(song.mimeType) || isLosslessFormat(formatOf(song.mimeType), song.bitDepth)
    override fun bitRate(song: SourceTrackEntity) = bitrateOf(song)?.let { it / 1_000 }
    override fun bitDepth(song: SourceTrackEntity) = song.bitDepth
    override fun sampleRate(song: SourceTrackEntity) = song.sampleRate
    override fun isrcs(song: SourceTrackEntity): List<String> = emptyList()
    override fun recordingId(song: SourceTrackEntity) = song.mbRecordingId
    override fun cover(song: SourceTrackEntity) = song.artwork
    override fun place(song: SourceTrackEntity) = song.sourceId
    override val seen = setOf(HealthTag.Genre, HealthTag.Year, HealthTag.TrackNumber, HealthTag.Cover)
}

// The library's songs as the phone shows them. An album's artist is the
// album's own; the phone keeps none per song. A source's copy of a song
// (by library song) adds every artist it credits and the MusicBrainz
// release it came out on.
class TrackHealth(
    private val albumArtists: Map<String, String> = emptyMap(),
    private val copies: Map<String, SourceTrackEntity> = emptyMap(),
) : HealthFields<TrackEntity> {
    override fun id(song: TrackEntity) = song.id
    override fun title(song: TrackEntity) = song.title
    override fun artist(song: TrackEntity) = song.artist.ifBlank { null }
    override fun album(song: TrackEntity) = song.album.ifBlank { null }
    override fun albumId(song: TrackEntity) = song.albumId
    override fun albumArtist(song: TrackEntity) = albumArtists[song.albumId]?.ifBlank { null }
    override fun genres(song: TrackEntity) = listOf(song.genre).filter(String::isNotBlank)
    override fun year(song: TrackEntity) = song.year
    override fun disc(song: TrackEntity) = song.discNo
    override fun track(song: TrackEntity) = song.trackNo
    override fun seconds(song: TrackEntity) = (song.durationMs / 1_000).toInt()
    override fun format(song: TrackEntity) = formatOf(song.mimeType)
    override fun lossless(song: TrackEntity) = isLossless(song.mimeType) || isLosslessFormat(formatOf(song.mimeType))
    override fun bitRate(song: TrackEntity): Int? = null
    override fun bitDepth(song: TrackEntity): Int? = null
    override fun sampleRate(song: TrackEntity): Int? = null
    override fun isrcs(song: TrackEntity): List<String> = emptyList()
    override fun recordingId(song: TrackEntity): String? = null
    override fun cover(song: TrackEntity) = song.artwork
    override fun artists(song: TrackEntity) = (listOfNotNull(artist(song)) + splitLines(copies[song.id]?.artists.orEmpty())).distinct()
    override fun releaseId(song: TrackEntity) = copies[song.id]?.mbAlbumId?.ifBlank { null }
    override fun releaseGroupId(song: TrackEntity) = copies[song.id]?.mbReleaseGroupId?.ifBlank { null }
    override val seen = setOf(HealthTag.Genre, HealthTag.Year, HealthTag.TrackNumber, HealthTag.Cover)
}
