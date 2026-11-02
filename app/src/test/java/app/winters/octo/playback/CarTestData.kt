package app.winters.octo.playback

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.OnlineArtist

// Songs, albums and artists for the car's tests, with only what they need.
internal fun track(id: String, title: String = id, artist: String = "Artist") = TrackEntity(
    id = id, sourceId = "device", nativeId = id, title = title, searchKey = searchKey(title), sortKey = searchKey(title),
    artist = artist, artistId = "artist:$artist", album = "Album", albumId = "album:1", trackNo = 1, discNo = 1, year = 2020,
    durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
)

internal fun album(id: String, title: String = id, artist: String = "Artist") = AlbumEntity(
    id = id, sourceId = "device", nativeId = id, title = title, searchKey = searchKey(title), sortKey = searchKey(title),
    artist = artist, artistId = "artist:$artist", year = 2020, songCount = 10, durationMs = 0, addedAt = 0, artwork = null,
)

internal fun artist(id: String, name: String = id) =
    ArtistEntity(id = id, sourceId = "device", name = name, searchKey = searchKey(name), sortKey = searchKey(name), albumCount = 1, songCount = 10, artwork = null)

internal fun onlineAlbum(id: String, title: String = id, owned: Int? = null) =
    OnlineAlbum(id, title, "Artist", null, 2020, 12, null, ownedCount = owned)

internal fun onlineArtist(id: String, name: String = id) = OnlineArtist(id, name, 3, null)

internal fun playlist(id: String, name: String = id) = PlaylistSummary(id = id, name = name, songCount = 3, durationMs = 0, covers = emptyList())
