package app.winters.octo.device

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.joinLines
import app.winters.octo.catalog.relinkKey
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.server.sortKeyHex
import java.security.MessageDigest

const val DEVICE = "device"
internal const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"
internal const val VARIOUS_ARTISTS = "Various Artists"

// How the phone's files become albums and artists (PhoneScan builds them).
//
// Albums are grouped by name, not by the phone's album id, which follows
// the folder when a file has no album-artist tag and so splits one album
// kept in two folders. A same-named group is only split apart when every
// file in it says which release it belongs to (a MusicBrainz id) or who the
// album is by (an album artist); a partly tagged group stays together.

// A leading track number in a file name: "04 Title", "04. Title", "04 - Title".
private val fileNumber = Regex("""^\s*(\d{1,3})(?=\s*[-._)]|\s)""")

internal fun String?.orNull() = this?.takeIf(String::isNotBlank)

// What a scan needs of one file to group it: the album it is filed under by
// name, what splits a same-named group (its release, or who the album is
// by, where the file says), the keys that order it within its album, and
// its genres, each with the key that groups its spellings.
internal class RowFacts(
    val albumName: String,
    val nameKey: String,
    val mbKey: String?,
    val byKey: String?,
    val fileNo: Int?,
    val discKey: Int,
    val numKey: Int,
    val nameOrder: String,
    val genres: List<Pair<String, String>>,
)

internal fun rowFacts(row: DeviceRow): RowFacts {
    val name = albumName(row)
    return RowFacts(
        albumName = name,
        nameKey = searchKey(name),
        mbKey = row.mbAlbumId.orNull()?.let { "mb:" + it.lowercase() },
        byKey = row.albumArtist.orNull()?.let { "by:" + searchKey(it) },
        fileNo = fileNumberOf(row.fileName),
        discKey = row.disc ?: 1,
        numKey = row.track ?: Int.MAX_VALUE,
        nameOrder = sortKeyHex(row.fileName.lowercase()),
        genres = row.genres.map { genreKey(it) to it },
    )
}

// One album as the scan writes it: the album and its songs in album order.
internal class AlbumRows(val album: AlbumEntity, val tracks: List<SourceTrackEntity>)

// Builds one album from all of its files, in the order the phone listed
// them. `spelling` gives each genre's spelling by its key.
internal fun albumRows(nameKey: String, splitKey: String, group: List<DeviceRow>, spelling: (String) -> String): AlbumRows {
    val albumId = albumIdOf(nameKey, splitKey)
    val title = mostCommon(group.map(::albumName))
    val artist = albumArtistOf(group)
    val artistId = artistIdOf(artist)
    val ordered = inAlbumOrder(group)
    val artwork = albumArtwork(albumId, ordered.first().first.uri)
    val tracks = ordered.mapIndexed { index, (row, number) -> trackRow(row, number, index, albumId, title, artist, artistId, artwork, spelling) }
    val album = AlbumEntity(
        id = albumId,
        sourceId = DEVICE,
        nativeId = albumNativeId(nameKey, splitKey),
        title = title,
        searchKey = searchKey("$title $artist"),
        sortKey = sortKey(group.firstNotNullOfOrNull { it.tags.sortAlbum.orNull() } ?: title),
        artist = artist,
        artistId = artistId,
        // The year it shows: the original release year where known.
        year = group.mapNotNull { it.tags.originalYear ?: it.year }.maxOrNull(),
        songCount = group.size,
        durationMs = group.sumOf { it.durationMs },
        addedAt = group.maxOf { it.addedAtSeconds },
        artwork = artwork,
    )
    return AlbumRows(album, tracks)
}

// One song of an album: `number` is its track number as the album shows it
// and `index` its place in the album.
internal fun trackRow(
    row: DeviceRow,
    number: Int?,
    index: Int,
    albumId: String,
    title: String,
    artist: String,
    artistId: String,
    artwork: String,
    spelling: (String) -> String,
): SourceTrackEntity {
    val trackTitle = row.title.orNull() ?: row.fileName.substringBeforeLast('.').ifBlank { "Untitled" }
    val tags = row.tags
    return SourceTrackEntity(
        id = "$DEVICE:${row.id}",
        sourceId = DEVICE,
        nativeId = row.id.toString(),
        title = trackTitle,
        searchKey = searchKey(trackTitle),
        sortKey = sortKey(tags.sortTitle.orNull() ?: trackTitle),
        artist = row.artist.orNull() ?: artist,
        artistId = artistId,
        album = title,
        albumId = albumId,
        trackNo = number,
        discNo = row.disc,
        year = row.year,
        durationMs = row.durationMs,
        addedAt = row.addedAtSeconds,
        mimeType = row.mimeType,
        sizeBytes = row.sizeBytes,
        artwork = artwork,
        uri = row.uri,
        albumOrder = index,
        relinkKey = relinkKey(artist, title, row.disc, number, trackTitle, row.durationMs),
        genre = row.genres.firstOrNull()?.let { spelling(genreKey(it)) } ?: "",
        trackGain = tags.trackGain,
        albumGain = tags.albumGain,
        trackPeak = tags.trackPeak,
        albumPeak = tags.albumPeak,
        originalYear = tags.originalYear,
        genres = joinLines(row.genres.map { spelling(genreKey(it)) }.distinct()),
        artists = joinLines(tags.artists),
        composer = tags.composer,
        bpm = tags.bpm,
        comment = tags.comment,
        explicit = tags.explicit,
        discTitle = tags.discTitle,
        mbRecordingId = tags.mbRecordingId,
        mbAlbumId = row.mbAlbumId,
        mbReleaseGroupId = tags.mbReleaseGroupId,
        mbArtistIds = joinLines(tags.mbArtistIds),
    )
}

internal fun albumIdOf(nameKey: String, splitKey: String) = "$DEVICE:album:${albumNativeId(nameKey, splitKey)}"

internal fun albumNativeId(nameKey: String, splitKey: String) = stableId("$nameKey|$splitKey")

// An album's cover: the art of its first file.
internal fun albumArtwork(albumId: String, firstUri: String) = ArtworkRef.Device("album:$albumId", firstUri).encode()

// The album a file belongs to by name; with no album tag, its folder's name.
internal fun albumName(row: DeviceRow): String =
    row.album.orNull()
        ?: row.folder?.trimEnd('/')?.substringAfterLast('/').orNull()
        ?: UNKNOWN_ALBUM

// Who the album is by: its album-artist tag, "Various Artists" for a
// compilation, otherwise whoever appears on most of its tracks.
private fun albumArtistOf(group: List<DeviceRow>): String {
    val tagged = group.mapNotNull { it.albumArtist.orNull() }
    return when {
        tagged.isNotEmpty() -> mostCommon(tagged)
        group.any { it.compilation } -> VARIOUS_ARTISTS
        else -> group.mapNotNull { it.artist.orNull() }.takeIf { it.isNotEmpty() }?.let(::mostCommon) ?: UNKNOWN_ARTIST
    }
}

internal fun artistIdOf(name: String) = "$DEVICE:artist:${searchKey(name)}"

// A file name's leading track number, if it has one.
internal fun fileNumberOf(fileName: String): Int? = fileNumber.find(fileName)?.groupValues?.get(1)?.toInt()

// Whether an album's file-name numbers can stand in for missing track
// numbers: only when it has more than one file, every file has one and no
// two are the same; otherwise a name like "50 Cent - In Da Club" would read
// as track 50.
internal fun namesUsable(numbers: List<Int?>): Boolean =
    numbers.size > 1 && numbers.none { it == null } && numbers.toSet().size == numbers.size

// Orders an album's files: disc, then track number, then file name. Where
// the tags have no track numbers, numbers at the start of the file names
// are used (see namesUsable).
private fun inAlbumOrder(group: List<DeviceRow>): List<Pair<DeviceRow, Int?>> {
    val fromNames = group.map { row -> fileNumberOf(row.fileName) }
    val usable = namesUsable(fromNames)
    return group.mapIndexed { i, row -> row to (row.track ?: fromNames[i].takeIf { usable }) }
        .sortedWith(
            compareBy<Pair<DeviceRow, Int?>> { it.first.disc ?: 1 }
                .thenBy { it.second ?: Int.MAX_VALUE }
                .thenBy { it.first.fileName.lowercase() },
        )
}

// The value seen most; of those seen equally often, the longest; of those,
// the first seen.
private fun mostCommon(values: List<String>): String =
    values.groupingBy { it }.eachCount().maxWith(compareBy({ it.value }, { it.key.length })).key

// A short id that stays the same for the same input across scans.
private fun stableId(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
