package app.winters.octo.testing

import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.relinkKey
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.device.DEVICE
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.ArtistRef
import app.winters.octo.subsonic.ItemDate
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import kotlin.random.Random

// How a generated library is shaped. Shares are of the songs.
data class GenShape(
    val songs: Int,
    val albums: Int,
    val artists: Int,
    // Songs titled "Untitled", which match each other by title.
    val untitled: Double = 0.3,
    // Songs with no track number.
    val noTrackNo: Double = 0.2,
    // Albums missing from the album list (their songs still name them).
    val unlisted: Double = 0.05,
    // Listed albums with no songs.
    val songless: Int = 3,
    // Songs listed twice, as a library changing during the read repeats them.
    val repeats: Int = 0,
    // Songs with no album id, filed by album name.
    val albumless: Int = 5,
    // Songs in one extra album, for an album too big to sort in memory.
    val giantAlbum: Int = 0,
    // Server songs that also have a copy on the phone, and songs only there.
    val onPhone: Double = 0.3,
    val phoneOnly: Int = 50,
    // Titles with emoji and other letters outside the basic plane.
    val wide: Double = 0.02,
)

// A source's rows as the phone builds them.
class PhoneRows(val tracks: List<SourceTrackEntity>, val albums: List<SourceAlbumEntity>, val artists: List<SourceArtistEntity>)

// Libraries made up from a seed, the same every time for the same seed and
// shape, with the things real libraries do that the merge and the copy
// must get right: same-titled songs, missing numbers, albums the album
// list lacks, repeats, and the same song on the phone under other tags.
object LibraryGen {
    private val words = listOf(
        "Night", "Drive", "Golden", "Hours", "River", "Static", "Paper", "Moon", "Echo", "Glass",
        "Summer", "Wires", "Velvet", "Signal", "Harbor", "Ghost", "Neon", "Silver", "Thunder", "Bloom",
    )
    private val wideTitles = listOf("Fire 🔥", "𝔸 Song", "Café ☕", "🎵 Notes")

    fun server(seed: Long, shape: GenShape): Library {
        val r = Random(seed)
        val artists = (0 until shape.artists).map { i ->
            Artist(id = "ar$i", name = "${words[i % words.size]} ${words[(i / words.size) % words.size]} $i", albumCount = 0)
        }
        val albums = (0 until shape.albums).map { i ->
            val artist = artists[r.nextInt(artists.size)]
            val year = 1960 + r.nextInt(65)
            Album(
                id = "al$i",
                name = if (i % 17 == 0) "Greatest Hits" else "${words[r.nextInt(words.size)]} ${words[r.nextInt(words.size)]}",
                artist = artist.name,
                artistId = artist.id,
                coverArt = "al-$i",
                year = year,
                created = "2025-0${1 + i % 9}-1${i % 10}T10:00:00Z",
                originalReleaseDate = if (i % 5 == 0) ItemDate(year = year - 10) else null,
                releaseTypes = if (i % 7 == 0) listOf("EP") else listOf("Album"),
            )
        }
        val listedCount = albums.size - (albums.size * shape.unlisted).toInt()
        var nextSong = 0
        val songs = ArrayList<Song>(shape.songs + shape.repeats + shape.giantAlbum)
        fun song(album: Album?, trackNo: Int?, disc: Int?): Song {
            val i = nextSong++
            val title = when {
                r.nextDouble() < shape.untitled -> "Untitled"
                r.nextDouble() < shape.wide -> wideTitles[r.nextInt(wideTitles.size)]
                else -> "${words[r.nextInt(words.size)]} ${words[r.nextInt(words.size)]}"
            }
            val credited = if (i % 9 == 0) listOf(ArtistRef(album?.artistId.orEmpty(), album?.artist.orEmpty()), ArtistRef("ar-guest$i", "Guest $i")) else emptyList()
            return Song(
                id = "s$i",
                title = title,
                album = album?.name ?: "Loose ${i % 3}",
                albumId = album?.id,
                artist = album?.artist ?: "Loose Artist",
                artistId = album?.artistId,
                track = trackNo,
                discNumber = disc,
                year = album?.year?.takeIf { i % 4 != 0 },
                duration = 120 + r.nextInt(300),
                coverArt = "s-$i",
                suffix = if (i % 3 == 0) "flac" else "mp3",
                size = 3_000_000L + r.nextInt(40_000_000),
                created = "2025-0${1 + i % 9}-0${1 + i % 9}T0${i % 10}:00:00Z",
                playCount = if (i % 6 == 0) (i % 40).toLong() else null,
                played = if (i % 6 == 0) "2026-10-0${1 + i % 9}T12:00:00Z" else null,
                userRating = if (i % 11 == 0) 1 + i % 5 else null,
                genres = listOf(words[i % words.size]),
                artists = credited,
                explicitStatus = if (i % 13 == 0) "explicit" else null,
            )
        }
        // Songs spread over the albums, numbered within each, some unnumbered.
        val perAlbum = IntArray(albums.size)
        val songAlbums = if (albums.size <= shape.songless) emptyList() else albums.dropLast(shape.songless)
        repeat(shape.songs - shape.albumless) {
            val album = songAlbums[r.nextInt(songAlbums.size)]
            val index = albums.indexOf(album)
            perAlbum[index]++
            val disc = if (index % 10 == 0) 1 + perAlbum[index] % 2 else null
            songs += song(album, if (r.nextDouble() < shape.noTrackNo) null else perAlbum[index], disc)
        }
        repeat(shape.albumless) { songs += song(null, null, null) }
        if (shape.giantAlbum > 0) {
            val giant = Album(id = "al-giant", name = "Everything", artist = artists[0].name, artistId = artists[0].id, year = 2001)
            repeat(shape.giantAlbum) { n -> songs += song(giant, if (n % 7 == 0) null else 1 + n % 500, 1 + n / 2000) }
        }
        // Repeats: a song sent again on a later page.
        repeat(shape.repeats) { songs += songs[r.nextInt(songs.size)] }
        val listed = albums.take(listedCount).mapIndexed { index, a -> a.copy(songCount = perAlbum[index]) }
        return Library(songs, listed, artists)
    }

    // The phone's rows: copies of a share of the server's songs under
    // slightly different tags (another album artist, a remaster note, a
    // second or two off, no track number), then songs only on the phone.
    fun phone(seed: Long, server: Library, shape: GenShape): PhoneRows {
        val r = Random(seed xor 0x5EED)
        val tracks = ArrayList<SourceTrackEntity>()
        val albums = LinkedHashMap<String, SourceAlbumEntity>()
        val artists = LinkedHashMap<String, SourceArtistEntity>()
        fun row(n: Int, title: String, artist: String, albumTitle: String, trackNo: Int?, durationMs: Long, rating: Int?): SourceTrackEntity {
            val artistId = "$DEVICE:artist:${searchKey(artist)}"
            val albumId = "$DEVICE:album:${searchKey("$albumTitle $artist")}"
            artists.getOrPut(artistId) { SourceArtistEntity(artistId, DEVICE, artist, searchKey(artist), sortKey(artist), 0, 0, null) }
            albums.getOrPut(albumId) {
                SourceAlbumEntity(albumId, DEVICE, albumId.removePrefix("$DEVICE:"), albumTitle, searchKey("$albumTitle $artist"), sortKey(albumTitle), artist, artistId, null, 0, 0, 1_600_000_000L + n, null)
            }
            return SourceTrackEntity(
                id = "$DEVICE:$n", sourceId = DEVICE, nativeId = "$n", title = title, searchKey = searchKey(title), sortKey = sortKey(title),
                artist = artist, artistId = artistId, album = albumTitle, albumId = albumId, trackNo = trackNo, discNo = null,
                year = if (n % 3 == 0) 1990 + n % 30 else null, durationMs = durationMs, addedAt = 1_600_000_000L + n,
                mimeType = "audio/flac", sizeBytes = 10_000_000L, artwork = null, uri = "content://media/$n", albumOrder = 0,
                relinkKey = relinkKey(artist, albumTitle, null, trackNo, title, durationMs), genre = "", rating = rating,
            )
        }
        var n = 0
        val seen = HashSet<String>()
        for (song in server.songs) {
            if (!seen.add(song.id) || r.nextDouble() >= shape.onPhone) continue
            val albumArtist = if (n % 5 == 0) "${song.artist} feat. Someone" else song.artist.orEmpty()
            val title = if (n % 6 == 0) "${song.title} - Remastered 2011" else song.title
            val skew = (r.nextInt(5) - 2) * 1000L
            tracks += row(n++, title, albumArtist, song.album.orEmpty(), song.track.takeIf { n % 4 != 0 }, song.duration * 1000L + skew, if (n % 8 == 0) 1 + n % 5 else null)
        }
        repeat(shape.phoneOnly) { tracks += row(n++, "Phone Song $it", "Phone Artist ${it % 7}", "Phone Album ${it % 5}", 1 + it % 12, 200_000L + it * 1000L, null) }
        // Each album's songs in its own order, as the phone builder writes them.
        val ordered = tracks.groupBy { it.albumId }.flatMap { (_, group) ->
            group.sortedWith(compareBy({ it.trackNo ?: Int.MAX_VALUE }, { it.sortKey })).mapIndexed { i, t -> t.copy(albumOrder = i) }
        }.mapIndexed { i, t -> t.copy(seq = i.toLong()) }
        val albumRows = albums.values.mapIndexed { i, a ->
            val own = ordered.filter { it.albumId == a.id }
            a.copy(songCount = own.size, durationMs = own.sumOf { it.durationMs }, seq = i.toLong())
        }
        val artistRows = artists.values.mapIndexed { i, a ->
            a.copy(albumCount = albumRows.count { it.artistId == a.id }, songCount = ordered.count { it.artistId == a.id }, seq = i.toLong())
        }
        return PhoneRows(ordered, albumRows, artistRows)
    }

    // The same library read again later: lists in another order, a few
    // songs gone, a few new, a few retagged.
    fun secondCopy(seed: Long, library: Library): Library {
        val r = Random(seed xor 0xC0FFEE)
        val kept = library.songs.filter { r.nextDouble() > 0.02 }.map { s ->
            if (r.nextDouble() < 0.03) s.copy(title = s.title + " (Live)", userRating = 3) else s
        }
        val added = (0 until 20).map { i -> library.songs[i % library.songs.size].copy(id = "new$i", title = "New Song $i") }
        return Library((kept + added).shuffled(r), library.albums.shuffled(r), library.artists.shuffled(r))
    }
}
