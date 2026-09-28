package app.winters.octo.desktop.perf

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.ArtistRef
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import java.time.Instant
import kotlin.random.Random

// The genre most songs are in, for timing one big genre's page.
const val BIG_GENRE = "Rock"

private val genres = listOf(
    BIG_GENRE, "Electronic", "Pop", "Hip-Hop", "Jazz", "Classical", "Indie", "Metal", "Folk", "Ambient",
    "Soundtrack", "R&B", "Soul", "Funk", "Punk", "Post-punk", "Synth-pop", "House", "Techno", "Drum & Bass",
    "J-Pop", "K-Pop", "Anime", "Chanson", "Bossa Nova", "Reggae", "Blues", "Country", "Shoegaze", "Dream Pop",
    "Trip-Hop", "Lo-fi", "Game", "Emo", "Grunge", "Disco", "Latin", "Afrobeat", "Downtempo", "Experimental",
)

// Plain words, and words from other scripts, so sort keys and text drawing
// meet what a real library holds.
private val words = listOf(
    "love", "night", "blue", "city", "fire", "dream", "heart", "rain", "gold", "ghost", "river", "summer",
    "echo", "glass", "midnight", "paper", "stars", "velvet", "wild", "silver", "neon", "ocean", "shadow",
    "light", "machine", "garden", "winter", "radio", "satellite", "honey", "storm", "violet", "tokyo",
    "café", "élan", "déjà", "naïve", "über", "señor", "smörgås", "façade", "piñata",
    "夜に駆ける", "紅蓮華", "群青", "残響散歌", "사랑", "봄날", "звезда", "город", "ночь", "αγάπη", "東京", "花",
)

private val suffixes = listOf("flac", "flac", "flac", "flac", "flac", "mp3", "mp3", "mp3", "m4a", "opus")

private const val ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

// A made-up library shaped like a big real one: about ten songs to an album
// and eight albums to an artist, with Unicode and long titles, several
// genres, and play data on some songs. The same seed makes the same library.
fun madeUpLibrary(songCount: Int, seed: Long = 42): Library {
    val random = Random(seed)
    // Ids like Navidrome's: 22 letters and digits.
    fun id() = String(CharArray(22) { ID_CHARS[random.nextInt(ID_CHARS.length)] })
    fun word() = words[random.nextInt(words.size)]
    fun name(parts: Int) = (1..parts).joinToString(" ") { word() }.replaceFirstChar { it.uppercase() }
    // A copy of a string, as a JSON reader makes one for every field.
    fun fresh(text: String) = String(text.toCharArray())
    fun date(fromYear: Int, toYear: Int): Instant {
        val from = Instant.parse("$fromYear-01-01T00:00:00Z").epochSecond
        val to = Instant.parse("$toYear-01-01T00:00:00Z").epochSecond
        return Instant.ofEpochSecond(from + (random.nextDouble() * (to - from)).toLong(), random.nextInt(1_000) * 1_000_000L)
    }
    fun genre(): String = if (random.nextInt(4) == 0) BIG_GENRE else genres[random.nextInt(genres.size)]

    val albumCount = (songCount + 9) / 10
    val artistCount = (albumCount + 7) / 8
    val artists = List(artistCount) { i ->
        val name = (if (random.nextInt(10) == 0) "The " else "") + name(1 + random.nextInt(3)) + if (random.nextInt(5) == 0) " $i" else ""
        Artist(id(), name, coverArt = "ar-$i", albumCount = 0)
    }
    val artistGenre = artists.map { genre() }

    val songs = ArrayList<Song>(songCount)
    val albums = ArrayList<Album>(albumCount)
    for (a in 0 until albumCount) {
        val artistIndex = a / 8
        val artist = artists[artistIndex]
        val albumId = id()
        val albumName = name(1 + random.nextInt(4)) + if (random.nextInt(8) == 0) " (Deluxe Edition)" else ""
        val year = if (random.nextInt(20) == 0) null else 1960 + random.nextInt(67)
        val added = date(2019, 2026)
        val genre = artistGenre[artistIndex]
        val inAlbum = if (a == albumCount - 1) songCount - songs.size else minOf(songCount - songs.size, 6 + random.nextInt(9))
        val discs = if (random.nextInt(10) == 0) 2 else 1
        var albumSeconds = 0
        var albumPlays = 0L
        var albumPlayed: Instant? = null
        for (t in 0 until inAlbum) {
            val long = random.nextInt(25) == 0
            val title = when {
                long -> name(6 + random.nextInt(9)) + " (feat. ${name(2)}) [${2000 + random.nextInt(26)} Remaster]"
                random.nextInt(12) == 0 -> "${name(2)}, Pt. ${1 + random.nextInt(12)}"
                else -> name(1 + random.nextInt(4))
            }
            val suffix = suffixes[random.nextInt(suffixes.size)]
            val lossless = suffix == "flac"
            val seconds = 60 + random.nextInt(540)
            val played = if (random.nextInt(100) < 45) date(2024, 2027) else null
            val plays = if (played == null) null else (1 + (random.nextDouble() * random.nextDouble() * 200).toLong())
            albumSeconds += seconds
            albumPlays += plays ?: 0
            if (played != null && (albumPlayed == null || played > albumPlayed)) albumPlayed = played
            val songGenre = fresh(genre)
            songs += Song(
                id = id(),
                title = title,
                album = fresh(albumName),
                albumId = fresh(albumId),
                artist = fresh(artist.name),
                artistId = fresh(artist.id),
                track = t + 1,
                discNumber = if (discs == 2) 1 + t * 2 / inAlbum else 1,
                year = year,
                duration = seconds,
                coverArt = "al-$albumId",
                suffix = suffix,
                contentType = if (lossless) "audio/flac" else "audio/$suffix",
                bitRate = if (lossless) 700 + random.nextInt(900) else listOf(128, 192, 256, 320).random(random),
                samplingRate = if (lossless && random.nextInt(4) == 0) listOf(48_000, 96_000, 192_000).random(random) else 44_100,
                bitDepth = if (lossless) (if (random.nextInt(3) == 0) 24 else 16) else null,
                size = seconds * 100_000L,
                starred = if (random.nextInt(30) == 0) date(2023, 2026).toString() else null,
                genre = songGenre,
                genres = if (random.nextInt(5) == 0) listOf(fresh(genre), genre()) else listOf(songGenre),
                created = added.plusSeconds(t.toLong()).toString(),
                played = played?.toString(),
                playCount = plays,
                artists = listOf(ArtistRef(fresh(artist.id), fresh(artist.name))),
                albumArtists = listOf(ArtistRef(fresh(artist.id), fresh(artist.name))),
                musicBrainzId = if (random.nextBoolean()) java.util.UUID(random.nextLong(), random.nextLong()).toString() else null,
                isrc = if (random.nextInt(10) < 6) listOf("US" + id().take(3).uppercase() + (10_000_000 + random.nextInt(89_999_999))) else emptyList(),
            )
        }
        albums += Album(
            id = albumId,
            name = albumName,
            artist = fresh(artist.name),
            artistId = fresh(artist.id),
            coverArt = "al-$albumId",
            songCount = inAlbum,
            duration = albumSeconds,
            year = year,
            genre = fresh(genre),
            created = added.toString(),
            played = albumPlayed?.toString(),
            playCount = albumPlays,
            genres = listOf(fresh(genre)),
        )
    }
    val counted = artists.mapIndexed { i, artist -> artist.copy(albumCount = minOf(8, albumCount - i * 8)) }
    return Library(songs, albums, counted)
}
