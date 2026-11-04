package app.winters.octo.proof

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Song
import kotlin.random.Random

// A library of any size, made item by item from a seed, so a test can
// serve 200,000 songs without holding them. Songs are spread over albums
// in order (album i holds songs i*per to (i+1)*per - 1); a share of
// titles repeat ("Untitled") as real libraries do.
class LazyLibrary(val songs: Int, val albums: Int, val artists: Int, private val seed: Long = 1) {
    private val perAlbum = (songs + albums - 1) / albums

    fun artist(i: Int): Artist = Artist(id = "ar$i", name = "Artist $i", albumCount = 0)

    fun album(i: Int): Album {
        val artist = i % artists
        val count = minOf(perAlbum, songs - i * perAlbum).coerceAtLeast(0)
        return Album(
            id = "al$i", name = "Album $i", artist = "Artist $artist", artistId = "ar$artist",
            coverArt = "al-$i", songCount = count, year = 1960 + i % 65, created = "2025-01-01T00:00:00Z",
        )
    }

    fun song(i: Int): Song {
        val r = Random(seed * 1_000_003 + i)
        val albumIndex = i / perAlbum
        val album = album(albumIndex)
        return Song(
            id = "s$i",
            title = if (r.nextInt(10) < 3) "Untitled" else "Song ${r.nextInt(5_000)}",
            album = album.name, albumId = album.id, artist = album.artist, artistId = album.artistId,
            track = 1 + i % perAlbum, duration = 120 + r.nextInt(300), coverArt = "s-$i", suffix = "flac",
            size = 30_000_000L, created = "2025-02-01T00:00:00Z",
            playCount = (i % 7).toLong(), played = if (i % 7 == 0) null else "2026-10-01T12:00:00Z",
            userRating = if (i % 11 == 0) 1 + i % 5 else null, genres = listOf("Genre ${i % 40}"),
        )
    }
}
