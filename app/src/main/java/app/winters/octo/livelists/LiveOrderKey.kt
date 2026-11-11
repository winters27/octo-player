package app.winters.octo.livelists

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.naturalSortKey
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.TrackFields

// A song's place in a live list as bytes, so the database keeps the list in
// order: the bytes of two songs compare, byte by byte, the way the list's
// sort puts them (sortSongsBy's order, every tie broken as it breaks them,
// the last by id), or with no sort by library order (sort key, then id, as
// the database orders them). No two songs share one.
//
// Each part is written in hex digits and ended by a byte below every digit,
// so a part that is a prefix of another sorts first; a part sorted the
// other way has its digits turned around and ends with a byte above every
// digit. Names compare as Kotlin compares text (their UTF-16 units); the
// library order's sort key and id as the database compares them (their
// UTF-8 bytes).
fun liveOrderKey(song: TrackEntity, order: SortOrder?, fields: TrackFields): ByteArray {
    val key = OrderKeyWriter()
    when (val by = order?.by as? SongSort) {
        null -> Unit
        SongSort.Title -> {
            key.text(name(fields.title(song)), order.descending)
            key.byTitle(song, fields)
        }
        SongSort.Artist -> {
            key.text(name(fields.artist(song)), order.descending)
            key.inAlbum(song, fields)
        }
        SongSort.Album -> {
            key.text(name(fields.album(song)), order.descending)
            key.text(name(fields.artist(song)))
            key.inAlbum(song, fields)
        }
        SongSort.Year -> {
            key.number(fields.year(song)?.takeIf { it > 0 }?.toLong(), order.descending)
            key.inAlbum(song, fields)
        }
        SongSort.Length -> {
            key.number(fields.seconds(song).takeIf { it > 0 }?.toLong(), order.descending)
            key.byTitle(song, fields)
        }
        SongSort.RecentlyAdded -> {
            key.number(fields.addedAt(song), order.descending)
            key.inAlbum(song, fields)
        }
        SongSort.Rating -> {
            key.number(fields.rating(song).takeIf { it > 0 }?.toLong(), order.descending)
            key.byTitle(song, fields)
        }
        SongSort.Liked, SongSort.DateLiked -> {
            key.number(fields.likedAt(song), order.descending)
            key.byTitle(song, fields)
        }
        SongSort.MostPlayed, SongSort.RecentlyPlayed -> {
            // Songs never played keep the title order, after the played ones
            // when descending and before them otherwise; played songs go by
            // their plays and last play, ties in the title order.
            val plays = fields.plays(song).toInt().toLong()
            val last = fields.lastPlayedAt(song) ?: 0
            val played = if (by == SongSort.MostPlayed) plays > 0 else last > 0
            key.segment(if (played == order.descending) 0 else 1)
            if (played) {
                val (first, second) = if (by == SongSort.MostPlayed) plays to last else last to plays
                key.number(first, order.descending)
                key.number(second, order.descending)
            }
            key.text(name(fields.title(song)))
            key.byTitle(song, fields)
        }
        SongSort.FolderOrder -> {
            key.library(song, order.descending)
            return key.bytes()
        }
    }
    // Every sort's ties end with the song's id, which no two songs share,
    // so the library's order would never be reached after them.
    if (order?.by !is SongSort) key.library(song, descending = false)
    return key.bytes()
}

// A name as the song lists sort it: its natural sort key.
private fun name(text: String?): String = naturalSortKey(text.orEmpty())

private class OrderKeyWriter {
    private val out = java.io.ByteArrayOutputStream(96)

    fun segment(n: Int) = out.write('0'.code + n)

    // Text in Kotlin's order of its UTF-16 units.
    fun text(text: String, descending: Boolean = false) {
        for (unit in text) digits(unit.code.toLong(), 4, descending)
        end(descending)
    }

    // A number, or none, which comes after every number either way.
    fun number(value: Long?, descending: Boolean = false) {
        if (value == null) {
            out.write(MISSING)
            return
        }
        out.write(PRESENT)
        digits(value xor Long.MIN_VALUE, 16, descending)
    }

    // An album's songs in its order: album, album id, disc, track, title, id.
    fun inAlbum(song: TrackEntity, fields: TrackFields) {
        text(name(fields.album(song)))
        text(fields.albumId(song).orEmpty())
        number((fields.disc(song) ?: 0).toLong())
        number((fields.track(song) ?: 0).toLong())
        text(name(fields.title(song)))
        text(fields.id(song))
    }

    // Title, artist, then id.
    fun byTitle(song: TrackEntity, fields: TrackFields) {
        text(name(fields.title(song)))
        text(name(fields.artist(song)))
        text(fields.id(song))
    }

    // The library's order: sort key, then id, by their UTF-8 bytes.
    fun library(song: TrackEntity, descending: Boolean) {
        bytes(song.sortKey.toByteArray(Charsets.UTF_8), descending)
        bytes(song.id.toByteArray(Charsets.UTF_8), descending)
    }

    private fun bytes(value: ByteArray, descending: Boolean) {
        for (b in value) digits((b.toInt() and 0xff).toLong(), 2, descending)
        end(descending)
    }

    // `count` hex digits of an unsigned value, most significant first.
    private fun digits(value: Long, count: Int, descending: Boolean) {
        for (i in count - 1 downTo 0) {
            val digit = ((value ushr (4 * i)) and 0xf).toInt()
            out.write(HEX[if (descending) 15 - digit else digit].code)
        }
    }

    private fun end(descending: Boolean) = out.write(if (descending) HIGH_END else LOW_END)

    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        const val HEX = "0123456789abcdef"
        const val LOW_END = 0x20
        const val HIGH_END = 0x7e
        const val PRESENT = 'p'.code
        const val MISSING = 'q'.code
    }
}
