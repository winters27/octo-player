package app.winters.octo.device

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.relinkKey
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import java.security.MessageDigest

const val DEVICE = "device"
private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"
private const val VARIOUS_ARTISTS = "Various Artists"

class DeviceCatalog(
    val tracks: List<TrackEntity>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
)

// A leading track number in a file name: "04 Title", "04. Title", "04 - Title".
private val fileNumber = Regex("""^\s*(\d{1,3})(?=\s*[-._)]|\s)""")

private fun String?.orNull() = this?.takeIf(String::isNotBlank)

// Turns the phone's files into albums and artists.
//
// Albums are grouped by name, not by the phone's album id, which follows
// the folder when a file has no album-artist tag and so splits one album
// kept in two folders. A same-named group is only split apart when every
// file in it says which release it belongs to (a MusicBrainz id) or who the
// album is by (an album artist); a partly tagged group stays together.
fun buildDeviceCatalog(rows: List<DeviceRow>): DeviceCatalog {
    val groups = rows
        .groupBy { searchKey(albumName(it)) }
        .flatMap { (nameKey, cluster) -> splitCluster(cluster).map { (splitKey, group) -> Triple(nameKey, splitKey, group) } }

    val tracks = mutableListOf<TrackEntity>()
    val albums = mutableListOf<AlbumEntity>()
    val genreNames = genreSpellings(rows)

    for ((nameKey, splitKey, group) in groups) {
        val albumId = "$DEVICE:album:${stableId("$nameKey|$splitKey")}"
        val title = mostCommon(group.map(::albumName))
        val artist = albumArtistOf(group)
        val artistId = artistIdOf(artist)
        val ordered = inAlbumOrder(group)
        val artwork = ArtworkRef.Device("album:$albumId", ordered.first().first.uri).encode()

        ordered.forEachIndexed { index, (row, number) ->
            val trackTitle = row.title.orNull() ?: row.fileName.substringBeforeLast('.').ifBlank { "Untitled" }
            tracks += TrackEntity(
                id = "$DEVICE:${row.id}",
                sourceId = DEVICE,
                nativeId = row.id.toString(),
                title = trackTitle,
                searchKey = searchKey(trackTitle),
                sortKey = sortKey(trackTitle),
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
                genre = row.genres.firstOrNull()?.let { genreNames.getValue(genreKey(it)) } ?: "",
            )
        }

        albums += AlbumEntity(
            id = albumId,
            sourceId = DEVICE,
            nativeId = stableId("$nameKey|$splitKey"),
            title = title,
            searchKey = searchKey("$title $artist"),
            sortKey = sortKey(title),
            artist = artist,
            artistId = artistId,
            year = group.mapNotNull { it.year }.maxOrNull(),
            songCount = group.size,
            durationMs = group.sumOf { it.durationMs },
            addedAt = group.maxOf { it.addedAtSeconds },
            artwork = artwork,
        )
    }

    val artists = albums.groupBy { it.artistId }.map { (id, owned) ->
        val name = mostCommon(owned.map { it.artist })
        ArtistEntity(
            id = id,
            sourceId = DEVICE,
            name = name,
            searchKey = searchKey(name),
            sortKey = sortKey(name),
            albumCount = owned.size,
            songCount = owned.sumOf { it.songCount },
            artwork = owned.maxByOrNull { it.addedAt }?.artwork,
        )
    }

    return DeviceCatalog(tracks, albums, artists)
}

// The album a file belongs to by name; with no album tag, its folder's name.
private fun albumName(row: DeviceRow): String =
    row.album.orNull()
        ?: row.folder?.trimEnd('/')?.substringAfterLast('/').orNull()
        ?: UNKNOWN_ALBUM

// Splits one same-named group only when every file in it can say which
// album it is. Otherwise the whole group is one album.
private fun splitCluster(cluster: List<DeviceRow>): Map<String, List<DeviceRow>> = when {
    cluster.all { it.mbAlbumId.orNull() != null } -> cluster.groupBy { "mb:" + it.mbAlbumId!!.lowercase() }
    cluster.all { it.albumArtist.orNull() != null } -> cluster.groupBy { "by:" + searchKey(it.albumArtist!!) }
    else -> mapOf("" to cluster)
}

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

// One spelling per genre across the library, the most common one, so
// "Hip Hop", "hip-hop" and "Hip-Hop/Rap" are the same genre.
private fun genreSpellings(rows: List<DeviceRow>): Map<String, String> =
    rows.flatMap { it.genres }.groupBy(::genreKey).mapValues { (_, names) -> mostCommon(names) }

private fun artistIdOf(name: String) = "$DEVICE:artist:${searchKey(name)}"

// Orders an album's files: disc, then track number, then file name. Where
// the tags have no track numbers, numbers at the start of the file names
// are used, but only when every file has one and no two are the same;
// otherwise a name like "50 Cent - In Da Club" would read as track 50.
private fun inAlbumOrder(group: List<DeviceRow>): List<Pair<DeviceRow, Int?>> {
    val fromNames = group.map { row -> fileNumber.find(row.fileName)?.groupValues?.get(1)?.toInt() }
    val namesUsable = group.size > 1 && fromNames.none { it == null } && fromNames.toSet().size == group.size
    return group.mapIndexed { i, row -> row to (row.track ?: fromNames[i].takeIf { namesUsable }) }
        .sortedWith(
            compareBy<Pair<DeviceRow, Int?>> { it.first.disc ?: 1 }
                .thenBy { it.second ?: Int.MAX_VALUE }
                .thenBy { it.first.fileName.lowercase() },
        )
}

private fun mostCommon(values: List<String>): String =
    values.groupingBy { it }.eachCount().maxWith(compareBy({ it.value }, { it.key.length })).key

// A short id that stays the same for the same input across scans.
private fun stableId(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
