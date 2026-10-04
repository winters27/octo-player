package app.winters.octo.ui.library.health

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.splitLines
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthFields
import app.winters.octo.health.HealthReport
import app.winters.octo.health.HealthTag
import app.winters.octo.health.findDuplicates
import app.winters.octo.health.findMissingTags
import app.winters.octo.health.findSplitAlbums
import app.winters.octo.health.heading
import app.winters.octo.health.qualityText
import app.winters.octo.health.summary
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
// album's own; the phone keeps none per song.
class TrackHealth(private val albumArtists: Map<String, String> = emptyMap()) : HealthFields<TrackEntity> {
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
    override val seen = setOf(HealthTag.Genre, HealthTag.Year, HealthTag.TrackNumber, HealthTag.Cover)
}

// One line of a check's list: a heading over a set of copies or a split
// album, or a song with a note under it.
sealed interface HealthLine {
    data class Heading(val title: String, val detail: String) : HealthLine
    data class Song(val track: TrackEntity, val note: String?) : HealthLine
}

// What the checks found, ready for the screens.
class PhoneHealth(
    val report: HealthReport<TrackEntity>,
    // Each set of copies' heading and summary, in the report's order.
    private val duplicateWords: List<Pair<String, String>> = emptyList(),
    // How each copy in a set sounds, by library song.
    private val quality: Map<String, String> = emptyMap(),
    // Each set of copies as the source keeps them, in the report's order:
    // the files a fix keeps and removes.
    val copyGroups: List<DuplicateGroup<SourceTrackEntity>> = emptyList(),
    // Every library song and every source's copy, as checked.
    val tracks: List<TrackEntity> = emptyList(),
    val copies: List<SourceTrackEntity> = emptyList(),
    val fields: TrackHealth = TrackHealth(),
) {
    // A check's list: headings and songs.
    fun lines(check: HealthCheck): List<HealthLine> = when (check) {
        HealthCheck.Duplicates -> report.duplicates.flatMapIndexed { i, group ->
            val (title, detail) = duplicateWords[i]
            listOf(HealthLine.Heading(title, detail)) + group.copies.map { track ->
                HealthLine.Song(track, listOfNotNull(quality[track.id], track.album.ifBlank { null }).joinToString(" • ").ifEmpty { null })
            }
        }
        HealthCheck.SplitAlbums -> report.splitAlbums.flatMap { album ->
            listOf(HealthLine.Heading(album.heading(), album.summary())) +
                album.parts.flatMap { part -> part.songs.map { HealthLine.Song(it, null) } }
        }
        else -> report.songs(check).map { HealthLine.Song(it, null) }
    }

    // A check's songs, in the list's order, for playing from one of them.
    fun songs(check: HealthCheck): List<TrackEntity> = lines(check).mapNotNull { (it as? HealthLine.Song)?.track }
}

// Runs the checks: copies among each source's own, the rest on the library
// as shown.
fun phoneHealth(tracks: List<TrackEntity>, albums: List<AlbumEntity>, copies: List<SourceTrackEntity>): PhoneHealth {
    val byId = tracks.associateBy { it.id }
    val fields = TrackHealth(albums.associate { it.id to it.artist })
    val groups = ArrayList<DuplicateGroup<TrackEntity>>()
    val sourceGroups = ArrayList<DuplicateGroup<SourceTrackEntity>>()
    val words = ArrayList<Pair<String, String>>()
    val quality = HashMap<String, String>()
    for (group in findDuplicates(copies, SourceCopyHealth)) {
        val mapped = group.copies.mapNotNull { copy -> byId[copy.mergedId]?.also { quality[it.id] = qualityText(copy, SourceCopyHealth) } }
            .distinctBy { it.id }
        if (mapped.size < 2) continue
        groups += DuplicateGroup(mapped, group.basis, group.bestReason)
        sourceGroups += group
        words += group.heading(SourceCopyHealth) to group.summary(SourceCopyHealth)
    }
    val report = HealthReport(
        checked = tracks.size,
        duplicates = groups,
        splitAlbums = findSplitAlbums(tracks, fields),
        noLength = tracks.filter { fields.seconds(it) <= 0 },
        missing = findMissingTags(tracks, fields),
    )
    return PhoneHealth(report, words, quality, sourceGroups, tracks, copies, fields)
}
