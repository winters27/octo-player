package app.winters.octo.device

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey

const val DEVICE = "device"
private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"

class DeviceCatalog(
    val tracks: List<TrackEntity>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
)

// Turns the phone's file list into albums and artists. Albums come from
// the media library's album id; artists come from the album artist, so a
// "feat." credit does not split an artist in two.
fun buildDeviceCatalog(rows: List<DeviceRow>): DeviceCatalog {
    val byAlbum = rows.groupBy { it.albumId }

    // The album's artist: its album-artist tag, else its most common artist.
    val albumArtist = byAlbum.mapValues { (_, tracks) ->
        tracks.firstNotNullOfOrNull { it.albumArtist?.takeIf(String::isNotBlank) }
            ?: tracks.mapNotNull { it.artist?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: UNKNOWN_ARTIST
    }

    fun artistId(name: String) = "$DEVICE:artist:${searchKey(name)}"
    fun albumId(id: Long) = "$DEVICE:album:$id"
    fun artwork(albumId: Long, uri: String) = ArtworkRef.Device("album:$albumId", uri).encode()

    val tracks = rows.map { row ->
        val title = row.title?.takeIf(String::isNotBlank) ?: "Untitled"
        // Older phones pack the disc into the track number: 2003 is disc 2, track 3.
        val (disc, track) = when {
            row.disc != null -> row.disc to row.track
            row.track != null && row.track >= 1000 -> row.track / 1000 to row.track % 1000
            else -> null to row.track
        }
        TrackEntity(
            id = "$DEVICE:${row.id}",
            sourceId = DEVICE,
            nativeId = row.id.toString(),
            title = title,
            searchKey = searchKey(title),
            sortKey = sortKey(title),
            artist = row.artist?.takeIf(String::isNotBlank) ?: UNKNOWN_ARTIST,
            artistId = artistId(albumArtist.getValue(row.albumId)),
            album = row.album?.takeIf(String::isNotBlank) ?: UNKNOWN_ALBUM,
            albumId = albumId(row.albumId),
            trackNo = track?.takeIf { it > 0 },
            discNo = disc?.takeIf { it > 0 },
            year = row.year?.takeIf { it > 0 },
            durationMs = row.durationMs,
            addedAt = row.addedAtSeconds,
            mimeType = row.mimeType,
            sizeBytes = row.sizeBytes,
            artwork = artwork(row.albumId, row.uri),
            uri = row.uri,
        )
    }

    val albums = byAlbum.map { (id, group) ->
        val title = group.firstNotNullOfOrNull { it.album?.takeIf(String::isNotBlank) } ?: UNKNOWN_ALBUM
        val artist = albumArtist.getValue(id)
        AlbumEntity(
            id = albumId(id),
            sourceId = DEVICE,
            nativeId = id.toString(),
            title = title,
            searchKey = searchKey("$title $artist"),
            sortKey = sortKey(title),
            artist = artist,
            artistId = artistId(artist),
            year = group.mapNotNull { it.year?.takeIf { y -> y > 0 } }.maxOrNull(),
            songCount = group.size,
            durationMs = group.sumOf { it.durationMs },
            addedAt = group.maxOf { it.addedAtSeconds },
            artwork = artwork(id, group.first().uri),
        )
    }

    val artists = albums.groupBy { it.artistId }.map { (id, owned) ->
        val name = owned.first().artist
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
