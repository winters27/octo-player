package app.winters.octo.query

import app.winters.octo.server.serverTime
import app.winters.octo.subsonic.Song
import java.time.LocalDate

// What a filter, and a query's order, can read from a song. Each app reads
// its own kind of song: the desktop a server's Song, the phone its library's
// rows. A value the song does not carry is null (or 0, or empty), and a
// filter on it matches nothing.
interface SongFields<T> {
    fun id(song: T): String
    fun title(song: T): String
    fun artist(song: T): String?
    fun album(song: T): String?
    fun albumId(song: T): String?
    fun albumArtist(song: T): String?

    // Every genre the song is filed under.
    fun genres(song: T): List<String>
    fun composer(song: T): String?
    fun year(song: T): Int?
    fun disc(song: T): Int?
    fun track(song: T): Int?

    // Times in milliseconds since 1970.
    fun addedAt(song: T): Long?
    fun lastPlayedAt(song: T): Long?
    fun plays(song: T): Long

    // 1 to 5 stars, 0 for none.
    fun rating(song: T): Int
    fun favourite(song: T): Boolean
    fun likedAt(song: T): Long?
    fun seconds(song: T): Int

    // The file's kind in lower case, like "flac" or "mp3".
    fun format(song: T): String?
    fun lossless(song: T): Boolean

    // Kilobits per second.
    fun bitRate(song: T): Int?
    fun bpm(song: T): Int?
}

// Formats that keep every bit of the recording.
private val LosslessFormats = setOf("flac", "alac", "wav", "wave", "aiff", "aif", "aifc", "pcm", "ape", "wv", "dsf", "dff", "tta")

// Whether a file of this kind is lossless. An m4a or mp4 is Apple Lossless
// when the server gives it a bit depth, and AAC otherwise.
fun isLosslessFormat(format: String?, bitDepth: Int? = null): Boolean {
    val kind = format?.lowercase() ?: return false
    if (kind == "m4a" || kind == "mp4") return (bitDepth ?: 0) > 0
    return kind in LosslessFormats
}

// A server's songs, as the desktop keeps them.
open class SubsonicSongFields : SongFields<Song> {
    override fun id(song: Song) = song.id
    override fun title(song: Song) = song.title
    override fun artist(song: Song) = song.artist ?: song.displayArtist
    override fun album(song: Song) = song.album
    override fun albumId(song: Song) = song.albumId
    override fun albumArtist(song: Song) = song.displayAlbumArtist ?: song.albumArtists.firstOrNull()?.name

    override fun genres(song: Song): List<String> {
        val one = song.genre?.takeIf(String::isNotBlank)
        return when {
            song.genres.isEmpty() -> listOfNotNull(one)
            one == null || one in song.genres -> song.genres
            else -> song.genres + one
        }
    }

    override fun composer(song: Song) = song.displayComposer
    override fun year(song: Song) = song.year
    override fun disc(song: Song) = song.discNumber
    override fun track(song: Song) = song.track
    override fun addedAt(song: Song) = quickTime(song.created)
    override fun lastPlayedAt(song: Song) = quickTime(song.played)
    override fun plays(song: Song) = song.playCount ?: 0
    override fun rating(song: Song) = song.userRating ?: 0
    override fun favourite(song: Song) = song.starred != null
    override fun likedAt(song: Song) = quickTime(song.starred)
    override fun seconds(song: Song) = song.duration
    override fun format(song: Song) = song.suffix?.lowercase()?.takeIf(String::isNotBlank)
    override fun lossless(song: Song) = isLosslessFormat(song.suffix, song.bitDepth)
    override fun bitRate(song: Song) = song.bitRate
    override fun bpm(song: Song) = song.bpm
}

// The server's songs read as they are. The desktop reads them through its
// own subclass, which knows hearts and ratings changed a moment ago.
object SubsonicSongs : SubsonicSongFields()

// A server's time, read fast when it is the usual UTC form
// ("2026-09-28T10:00:00Z", with or without a fraction of a second), else as
// serverTime reads it. Reading 100,000 dates the general way took most of
// a date filter's time.
fun quickTime(text: String?): Long? {
    if (text == null || text.length < 20) return serverTime(text)
    fun digits(from: Int, count: Int): Int {
        var n = 0
        for (i in from until from + count) {
            val d = text[i] - '0'
            if (d !in 0..9) return -1
            n = n * 10 + d
        }
        return n
    }
    if (text[4] != '-' || text[7] != '-' || text[10] != 'T' || text[13] != ':' || text[16] != ':') return serverTime(text)
    val year = digits(0, 4)
    val month = digits(5, 2)
    val day = digits(8, 2)
    val hour = digits(11, 2)
    val minute = digits(14, 2)
    val second = digits(17, 2)
    if (year < 0 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return serverTime(text)
    var at = 19
    var millis = 0
    if (text[at] == '.') {
        at++
        var scale = 100
        while (at < text.length && text[at] in '0'..'9') {
            millis += (text[at] - '0') * scale
            scale /= 10
            at++
        }
    }
    if (at != text.lastIndex || text[at] != 'Z') return serverTime(text)
    val days = runCatching { LocalDate.of(year, month, day).toEpochDay() }.getOrNull() ?: return serverTime(text)
    return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
}

// The songs of `items` read by their places in it, so a query can pick
// places rather than songs: a playlist filtered on screen still knows
// which of its entries each row is, and a list keeps its headings in step.
fun <T> SongFields<T>.at(items: List<T>): SongFields<Int> {
    val read = this
    return object : SongFields<Int> {
        override fun id(song: Int) = read.id(items[song])
        override fun title(song: Int) = read.title(items[song])
        override fun artist(song: Int) = read.artist(items[song])
        override fun album(song: Int) = read.album(items[song])
        override fun albumId(song: Int) = read.albumId(items[song])
        override fun albumArtist(song: Int) = read.albumArtist(items[song])
        override fun genres(song: Int) = read.genres(items[song])
        override fun composer(song: Int) = read.composer(items[song])
        override fun year(song: Int) = read.year(items[song])
        override fun disc(song: Int) = read.disc(items[song])
        override fun track(song: Int) = read.track(items[song])
        override fun addedAt(song: Int) = read.addedAt(items[song])
        override fun lastPlayedAt(song: Int) = read.lastPlayedAt(items[song])
        override fun plays(song: Int) = read.plays(items[song])
        override fun rating(song: Int) = read.rating(items[song])
        override fun favourite(song: Int) = read.favourite(items[song])
        override fun likedAt(song: Int) = read.likedAt(items[song])
        override fun seconds(song: Int) = read.seconds(items[song])
        override fun format(song: Int) = read.format(items[song])
        override fun lossless(song: Int) = read.lossless(items[song])
        override fun bitRate(song: Int) = read.bitRate(items[song])
        override fun bpm(song: Int) = read.bpm(items[song])
    }
}

// The places in `items` a query picks, in its order. The ids a sort breaks
// ties by are the items' own, so a song twice in a list sorts beside itself.
fun <T> LibraryQuery.places(items: List<T>, now: Long, fields: SongFields<T>): List<Int> =
    select(items.indices.toList(), now, fields.at(items))
