package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import kotlin.random.Random

// Rows for the phone and a server shaped to reach every rule of the merge:
// albums on both, on one, or filed under another credit or edition name;
// "Untitled" songs; lengths near and far; partial track numbers; editions
// with original years; fuller credits; songless and emptied albums.
internal object MergeRowsGen {
    const val SERVER = "server:gen.test"

    private val genres = listOf("", "Rock", "Pop", "Hip-Hop")

    fun sources(seed: Long, songs: Int, phone: Boolean = true): List<SourceCatalog> {
        val random = Random(seed)
        val artistCount = maxOf(4, songs / 40)
        val phoneTracks = ArrayList<SourceTrackEntity>()
        val serverTracks = ArrayList<SourceTrackEntity>()
        val phoneAlbums = ArrayList<SourceAlbumEntity>()
        val serverAlbums = ArrayList<SourceAlbumEntity>()
        val usedPhoneArtists = LinkedHashSet<Int>()
        val usedServerArtists = LinkedHashSet<Int>()
        var made = 0
        var album = 0
        while (made < songs) {
            val artist = random.nextInt(artistCount)
            val title = if (random.nextInt(10) == 0) "Greatest Hits" else "Album $album"
            val onPhone = phone && random.nextInt(10) < 6
            val onServer = !onPhone || random.nextInt(10) < 7
            val serverTitle = if (random.nextInt(8) == 0) "$title (Deluxe)" else title
            val label = random.nextInt(10) == 0
            val n = 1 + random.nextInt(12)
            val year = if (random.nextBoolean()) 1990 + random.nextInt(30) else null
            val original = if (year != null && random.nextInt(4) == 0) year - random.nextInt(10) else null
            val phoneOrder = (0 until n).shuffled(random)
            val phoneAlbumId = "$DEVICE:album:$album"
            val serverAlbumId = "$SERVER:al$album"
            val serverArtistId = if (label) "$SERVER:label" else "$SERVER:ar$artist"
            for (t in 0 until n) {
                val songTitle = if (random.nextInt(10) < 3) "Untitled" else "Song ${album}_$t"
                val ms = 120_000L + random.nextInt(240_000)
                val trackNo = if (random.nextInt(5) == 0) null else t + 1
                val credits = if (random.nextInt(5) == 0) "Artist $artist\nGuest ${random.nextInt(9)}" else "Artist $artist"
                if (onPhone) {
                    phoneTracks += row(
                        id = "$DEVICE:${album}_$t", source = DEVICE, title = songTitle, artist = "Artist $artist",
                        artistId = "$DEVICE:artist:$artist", album = title, albumId = phoneAlbumId,
                        trackNo = if (random.nextInt(3) == 0) null else trackNo, ms = ms, year = year, original = null,
                        genre = genres[random.nextInt(genres.size)], credits = "Artist $artist",
                        added = if (random.nextInt(5) == 0) 0 else 1_500_000_000L + random.nextInt(100_000_000),
                        rating = null, explicit = if (random.nextInt(4) == 0) false else null, order = phoneOrder[t],
                    )
                    usedPhoneArtists += artist
                }
                val serverHas = onServer && (!onPhone || random.nextInt(10) < 8)
                if (serverHas) {
                    val near = if (random.nextInt(10) == 0) ms + 10_000 else ms + random.nextInt(-2_500, 2_500)
                    serverTracks += row(
                        id = "$SERVER:s${album}_$t", source = SERVER, title = songTitle,
                        artist = if (random.nextInt(6) == 0) "ARTIST $artist" else "Artist $artist",
                        artistId = serverArtistId, album = serverTitle, albumId = serverAlbumId, trackNo = trackNo, ms = near,
                        year = year, original = original, genre = genres[random.nextInt(genres.size)], credits = credits,
                        added = 1_600_000_000L + random.nextInt(100_000_000), rating = random.nextInt(6),
                        explicit = if (random.nextInt(3) == 0) random.nextBoolean() else null, order = t,
                    )
                }
                made++
            }
            if (onOnlyBonus(random) && onServer) {
                serverTracks += row(
                    id = "$SERVER:bonus$album", source = SERVER, title = "Bonus $album", artist = "Artist $artist",
                    artistId = serverArtistId, album = serverTitle, albumId = serverAlbumId, trackNo = n + 1, ms = 180_000,
                    year = year, original = original, genre = "", credits = "Artist $artist", added = 0, rating = 0,
                    explicit = null, order = n,
                )
            }
            if (onPhone) phoneAlbums += albumOf(phoneAlbumId, DEVICE, title, "Artist $artist", "$DEVICE:artist:$artist", n, year)
            if (onServer || random.nextInt(20) == 0) {
                serverAlbums += albumOf(serverAlbumId, SERVER, serverTitle, if (label) "Label Records" else "ARTIST $artist", serverArtistId, n, year)
                if (!label) usedServerArtists += artist
            }
            album++
        }
        val phoneArtists = usedPhoneArtists.map { artistOf("$DEVICE:artist:$it", DEVICE, "Artist $it") }
        val serverArtists = usedServerArtists.map { artistOf("$SERVER:ar$it", SERVER, "artist $it") } +
            artistOf("$SERVER:label", SERVER, "Label Records")
        val server = SourceCatalog(SERVER, false, serverTracks, serverAlbums, serverArtists)
        return if (phone) listOf(SourceCatalog(DEVICE, true, phoneTracks, phoneAlbums, phoneArtists), server) else listOf(server)
    }

    // The same server read again later: rows in another order, some gone,
    // some changed, some new.
    fun secondCopy(seed: Long, server: SourceCatalog): SourceCatalog {
        val random = Random(seed)
        val tracks = server.tracks.shuffled(random).mapNotNull { t ->
            when (random.nextInt(40)) {
                0, 1 -> null
                2 -> t.copy(durationMs = t.durationMs + 20_000)
                3 -> t.copy(title = t.title + " (Live)", searchKey = t.searchKey + " live")
                4 -> t.copy(rating = random.nextInt(6))
                else -> t
            }
        } + (0 until 5).map { i -> server.tracks.first().copy(id = "$SERVER:new$i", nativeId = "new$i", title = "New $i") }
        return server.copy(tracks = tracks, albums = server.albums.shuffled(random))
    }

    private fun onOnlyBonus(random: Random) = random.nextInt(6) == 0

    private fun row(
        id: String, source: String, title: String, artist: String, artistId: String, album: String, albumId: String,
        trackNo: Int?, ms: Long, year: Int?, original: Int?, genre: String, credits: String, added: Long, rating: Int?,
        explicit: Boolean?, order: Int,
    ) = SourceTrackEntity(
        id = id, sourceId = source, nativeId = id.substringAfterLast(':'), title = title, searchKey = title.lowercase(),
        sortKey = title.lowercase(), artist = artist, artistId = artistId, album = album, albumId = albumId, trackNo = trackNo,
        discNo = null, year = year, durationMs = ms, addedAt = added, mimeType = null, sizeBytes = null, artwork = null,
        uri = null, albumOrder = order, relinkKey = "$artist|$title|$ms", genre = genre, rating = rating,
        originalYear = original, artists = credits, explicit = explicit,
    )

    private fun albumOf(id: String, source: String, title: String, artist: String, artistId: String, songs: Int, year: Int?) =
        SourceAlbumEntity(id, source, id.substringAfterLast(':'), title, title.lowercase(), title.lowercase(), artist, artistId, year, songs, songs * 200_000L, 0, null)

    private fun artistOf(id: String, source: String, name: String) =
        SourceArtistEntity(id, source, name, name.lowercase(), name.lowercase(), 1, 1, null)
}
