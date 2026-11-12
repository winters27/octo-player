package app.winters.octo.proof

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Song
import kotlin.random.Random

// How a changed library differs from the first: songs taken out, songs
// added in new albums that sort newest, titles changed, and the first
// albums retitled and artists renamed, each keeping its id.
const val PROOF_REMOVED = 100
const val PROOF_ADDED = 100
const val PROOF_NEW_ALBUMS = 10
const val PROOF_RETITLED = 2_000
const val PROOF_RETITLED_ALBUMS = 100
const val PROOF_RENAMED_ARTISTS = 5

// With -PoctoProofLongTitles, every song's title, album and artist name
// (the Unknown album and the lead album's aside) carries a long tail, for a
// measure of what longer names take.
private val LONG_NAMES = System.getProperty("octo.proofLongTitles") == "true"
private val LONG_TITLE_TAIL = if (LONG_NAMES) " (Live at the Royal Albert Hall, London, 14 March 1971) [2011 Remastered Deluxe Edition]" else ""
private val LONG_ALBUM_TAIL = if (LONG_NAMES) ": The Complete Recordings (50th Anniversary Super Deluxe Edition)" else ""
private val LONG_ARTIST_TAIL = if (LONG_NAMES) " & The Royal Philharmonic Orchestra and Chorus" else ""

// The songs of the album `leadAlbum` adds.
const val PROOF_LEAD_SONGS = 12

// A word about 4% of the proof library's titles hold, for a search or a
// filter that finds thousands of songs.
const val PROOF_COMMON_WORD = "Love"

// One album in this many has its songs titled "Track N" by their place, and
// one find in this many is titled so.
const val TRACK_TITLES_EVERY = 50

// A title as a real library has one, drawn from `r`: two to four made-up
// words, so almost every title's letters are its own, about 4% of them
// holding PROOF_COMMON_WORD; and the repeats real libraries have, about 1%
// "Untitled" and 0.5% "Intro".
fun proofTitle(r: Random): String {
    val pick = r.nextInt(1_000)
    if (pick < 10) return "Untitled"
    if (pick < 15) return "Intro"
    val words = MutableList(2 + r.nextInt(3)) { proofWord(r) }
    if (pick < 55) words[r.nextInt(words.size)] = PROOF_COMMON_WORD
    return words.joinToString(" ")
}

// A made-up word of one to three syllables, capitalized.
private fun proofWord(r: Random): String {
    val word = StringBuilder()
    repeat(1 + r.nextInt(3)) { word.append(ONSETS[r.nextInt(ONSETS.size)]).append(VOWELS[r.nextInt(VOWELS.size)]) }
    if (r.nextInt(3) == 0) word.append(CODAS[r.nextInt(CODAS.size)])
    word.setCharAt(0, word[0].uppercaseChar())
    return word.toString()
}

private val ONSETS = listOf("", "b", "br", "c", "ch", "d", "dr", "f", "fl", "g", "gr", "h", "j", "k", "l", "m", "n", "p", "pl", "r", "s", "sh", "st", "t", "th", "tr", "v", "w", "y", "z")
private val VOWELS = listOf("a", "e", "i", "o", "u", "ai", "ea", "ee", "oo", "ou")
private val CODAS = listOf("n", "r", "s", "l", "t", "nd", "st", "x")

// A library of any size, made item by item from a seed, so a test can
// serve 200,000 songs without holding them. Songs are spread over albums
// in order (album i holds songs i*per to (i+1)*per - 1). Titles are drawn
// as proofTitle draws them, and one album in TRACK_TITLES_EVERY (never the
// Unknown album) titles its songs "Track N" by their place. With
// `unknownAlbumSongs`, the last album is "Unknown album", holding that
// many of the songs (the last ones), as a library of untagged files does.
//
// `changed` turns it into the library as the server has it after reading
// its folders again: the last PROOF_REMOVED songs before the Unknown album
// gone, PROOF_ADDED songs in PROOF_NEW_ALBUMS new albums added after the
// rest and newest of all, PROOF_RETITLED titles changed, the first
// PROOF_RETITLED_ALBUMS albums retitled and the first PROOF_RENAMED_ARTISTS
// artists renamed (ids kept), and a later scan time. It can be switched on
// while the library is served.
//
// `leadAlbum` adds one album of PROOF_LEAD_SONGS songs by an artist of its
// own, listed first of every album, its songs listed after the rest.
//
// `retagged` is the library after every song's tags were written again:
// every album and artist under a new name and so a new id, as a server
// that keys albums and artists by their tags lists them, each song keeping
// its own id. It can be switched on while the library is served.
//
// With `bigGenreSongs`, that many of the first songs share one genre,
// "Genre big". With `pairedAlbums`, every odd album besides the Unknown
// album is a second part of the album before it: the same name, artist
// and year under an id of its own, as a split album is.
class LazyLibrary(
    val songs: Int,
    val albums: Int,
    val artists: Int,
    private val seed: Long = 1,
    val unknownAlbumSongs: Int = 0,
    private val bigGenreSongs: Int = 0,
    private val pairedAlbums: Boolean = false,
) {
    @Volatile var changed = false
    @Volatile var leadAlbum = false
    @Volatile var retagged = false

    // An album's or an artist's id, as the tags make it now.
    private val albumPrefix get() = if (retagged) "alr" else "al"
    private val artistPrefix get() = if (retagged) "arr" else "ar"

    // The album an id names now, or null.
    fun albumIndexOf(id: String): Int? = id.takeIf { it.startsWith(albumPrefix) }?.removePrefix(albumPrefix)?.toIntOrNull()?.takeIf(::hasAlbum)

    // The lead album's index, its first song's and its artist's.
    private val leadIndex = albums + PROOF_NEW_ALBUMS
    private val firstLeadSong = songs + PROOF_ADDED
    private val leadArtist = artists

    private val regularSongs = songs - unknownAlbumSongs
    private val regularAlbums = if (unknownAlbumSongs > 0) albums - 1 else albums
    private val perAlbum = if (regularAlbums > 0) (regularSongs + regularAlbums - 1) / regularAlbums else 0

    init {
        require(unknownAlbumSongs in 0..songs && (unknownAlbumSongs == 0 || albums >= 1)) { "the Unknown album needs an album of its own" }
        require(regularSongs == 0 || regularAlbums > 0) { "songs need albums" }
    }

    // The songs the change takes out, and the first song id it adds.
    private val removedFrom = (regularSongs - PROOF_REMOVED).coerceAtLeast(0)
    private val removedTo = regularSongs
    private val retitleEvery = (removedFrom / PROOF_RETITLED).coerceAtLeast(1)

    // When the server last read its folders.
    val lastScan: String get() = if (changed) "2026-11-02T00:00:00Z" else "2026-11-01T00:00:00Z"

    // How many songs, albums and artists the server lists now.
    val songCount: Int get() = (if (changed) songs - (removedTo - removedFrom) + PROOF_ADDED else songs) + if (leadAlbum) PROOF_LEAD_SONGS else 0
    val albumCount: Int get() = (if (changed) albums + PROOF_NEW_ALBUMS else albums) + if (leadAlbum) 1 else 0
    val artistCount: Int get() = artists + if (leadAlbum) 1 else 0

    // Whether the server has the album with this index now.
    fun hasAlbum(i: Int): Boolean = i in 0 until (if (changed) albums + PROOF_NEW_ALBUMS else albums) || (leadAlbum && i == leadIndex)

    fun removed(i: Int): Boolean = changed && i in removedFrom until removedTo

    // How many of the first n songs the change took out.
    fun removedBefore(n: Int): Int = if (changed) (minOf(n, removedTo) - removedFrom).coerceIn(0, removedTo - removedFrom) else 0

    // The index of the song at place p among the first songs still listed.
    fun keptAt(p: Int): Int = if (!changed || p < removedFrom) p else p + (removedTo - removedFrom)

    // The song at a place in the server's list of every song: the first
    // songs in order, then the added ones, then the lead album's.
    fun songAt(position: Int): Song {
        val kept = songs - removedBefore(songs)
        if (position < kept) return song(keptAt(position))
        val added = if (changed) PROOF_ADDED else 0
        val extra = position - kept
        return if (extra < added) song(songs + extra) else song(firstLeadSong + extra - added)
    }

    // The album at a place in a list of albums: the lead album first, then
    // newest first puts the added albums ahead of the rest, any other order
    // after them.
    fun albumAt(position: Int, newest: Boolean): Album {
        if (leadAlbum) return if (position == 0) album(leadIndex) else plainAlbumAt(position - 1, newest)
        return plainAlbumAt(position, newest)
    }

    private fun plainAlbumAt(position: Int, newest: Boolean): Album {
        if (!changed) return album(position)
        return if (newest) {
            if (position < PROOF_NEW_ALBUMS) album(albums + position) else album(position - PROOF_NEW_ALBUMS)
        } else {
            album(position)
        }
    }

    fun artist(i: Int): Artist = Artist(id = "$artistPrefix$i", name = if (i == leadArtist) "Aaron Lead" else artistName(i), albumCount = 0)

    private fun artistName(i: Int) = when {
        retagged -> "Artist $i Band"
        changed && i < PROOF_RENAMED_ARTISTS -> "Artist $i & Co"
        else -> "Artist $i"
    } + LONG_ARTIST_TAIL

    fun album(i: Int): Album {
        if (i == leadIndex) {
            return Album(
                id = "al$i", name = "Aa Lead Album", artist = "Aaron Lead", artistId = "ar$leadArtist",
                coverArt = "al-$i", songCount = PROOF_LEAD_SONGS, year = 2026, created = "2026-11-03T00:00:00Z",
            )
        }
        val unknown = isUnknown(i)
        val named = if (pairedAlbums && !unknown && i < albums && i % 2 == 1) i - 1 else i
        val artist = named % artists
        val range = albumSongs(i)
        val gone = if (changed) (minOf(range.last + 1, removedTo) - maxOf(range.first, removedFrom)).coerceAtLeast(0) else 0
        val count = (range.last - range.first + 1).coerceAtLeast(0) - gone
        val name = when {
            unknown -> "Unknown album"
            retagged -> "Album $named (Deluxe)$LONG_ALBUM_TAIL"
            changed && named < PROOF_RETITLED_ALBUMS -> "Album $named (Remastered)$LONG_ALBUM_TAIL"
            else -> "Album $named$LONG_ALBUM_TAIL"
        }
        return Album(
            id = "$albumPrefix$i", name = name, artist = artistName(artist), artistId = "$artistPrefix$artist",
            coverArt = "al-$i", songCount = count, year = if (unknown) null else 1960 + named % 65,
            created = if (i >= albums) "2026-11-02T00:00:00Z" else "2025-01-01T00:00:00Z",
        )
    }

    // The ids of an album's songs, in its order, before any are taken out.
    fun albumSongs(i: Int): IntRange = when {
        i == leadIndex -> firstLeadSong until firstLeadSong + PROOF_LEAD_SONGS
        i >= albums -> (songs + (i - albums) * (PROOF_ADDED / PROOF_NEW_ALBUMS)).let { it until it + PROOF_ADDED / PROOF_NEW_ALBUMS }
        isUnknown(i) -> regularSongs until songs
        else -> (i * perAlbum) until minOf((i + 1) * perAlbum, regularSongs).coerceAtLeast(i * perAlbum)
    }

    private fun isUnknown(i: Int) = unknownAlbumSongs > 0 && i == albums - 1

    private fun albumOf(i: Int): Int = when {
        i >= firstLeadSong -> leadIndex
        i >= songs -> albums + (i - songs) / (PROOF_ADDED / PROOF_NEW_ALBUMS)
        i >= regularSongs -> albums - 1
        else -> i / perAlbum
    }

    fun song(i: Int): Song {
        val r = Random(seed * 1_000_003 + i)
        val albumIndex = albumOf(i)
        val album = album(albumIndex)
        val unknown = isUnknown(albumIndex)
        val track = if (unknown) null else 1 + (i - albumSongs(albumIndex).first)
        val drawn = proofTitle(r)
        val title = if (track != null && albumIndex % TRACK_TITLES_EVERY == 7) "Track $track" else drawn
        val retitled = changed && i < removedFrom && i % retitleEvery == 0 && i / retitleEvery < PROOF_RETITLED
        return Song(
            id = "s$i",
            title = (if (retitled) "$title (remastered)" else title) + LONG_TITLE_TAIL,
            album = album.name, albumId = album.id, artist = album.artist, artistId = album.artistId,
            track = track, duration = 120 + r.nextInt(300),
            coverArt = "s-$i", suffix = "flac",
            size = 30_000_000L, created = if (i >= songs) "2026-11-02T00:00:00Z" else "2025-02-01T00:00:00Z",
            playCount = (i % 7).toLong(), played = if (i % 7 == 0) null else "2026-10-01T12:00:00Z",
            userRating = if (i % 11 == 0) 1 + i % 5 else null, genres = listOf(if (i < bigGenreSongs) "Genre big" else "Genre ${i % 40}"),
        )
    }
}
