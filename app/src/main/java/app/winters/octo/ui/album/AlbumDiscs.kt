package app.winters.octo.ui.album

import androidx.paging.PagingData
import androidx.paging.TerminalSeparatorType
import androidx.paging.insertSeparators
import androidx.paging.map
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.SqlValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// A line of a big album's pages: one of its song rows, or the heading of
// the disc the row after it is on.
sealed interface AlbumLine {
    data class Song(val item: ListItem<TrackEntity>) : AlbumLine

    data class Disc(val disc: Int, val before: ListItem<TrackEntity>) : AlbumLine
}

// The song row a line is, or null for a heading.
fun AlbumLine.row(): ListItem<TrackEntity>? = (this as? AlbumLine.Song)?.item

// A line's key in the lazy list: a song row's own key, a heading's the
// disc's and the row's after it.
fun AlbumLine.key(): String = when (this) {
    is AlbumLine.Song -> item.key.token
    is AlbumLine.Disc -> "disc:$disc:${before.key.token}"
}

// The disc a song is on; a song with none is on the first.
fun discOf(track: TrackEntity): Int = track.discNo ?: 1

// A big album's pages with the held album's disc headings: one between
// two rows on different discs, and one over the first row when the album
// has a song on another disc than it (`otherDisc`, asked only at the top).
// Each heading is decided by the two rows beside it as their pages come, so
// nothing beyond a page is held, and a heading between two pages shows
// once both are read.
fun Flow<PagingData<ListItem<TrackEntity>>>.withDiscs(otherDisc: suspend (firstDisc: Int) -> Boolean): Flow<PagingData<AlbumLine>> = map { data ->
    val songs: PagingData<AlbumLine.Song> = data.map { AlbumLine.Song(it) }
    songs.insertSeparators(TerminalSeparatorType.SOURCE_COMPLETE) { before: AlbumLine.Song?, after: AlbumLine.Song? -> heading(before, after, otherDisc) }
}

private suspend fun heading(before: AlbumLine.Song?, after: AlbumLine.Song?, otherDisc: suspend (Int) -> Boolean): AlbumLine? {
    val next = after?.item ?: return null
    val disc = discOf(next.row)
    return when {
        before == null -> if (otherDisc(disc)) AlbumLine.Disc(disc, next) else null
        discOf(before.item.row) != disc -> AlbumLine.Disc(disc, next)
        else -> null
    }
}

// Whether a library album has a song on another disc than `disc`.
suspend fun libraryOtherDisc(db: CatalogDatabase, albumId: String, disc: Int): Boolean =
    db.listsDao().count(
        rawQuery("SELECT EXISTS(SELECT 1 FROM track WHERE albumId = ? AND IFNULL(discNo, 1) != ?)", listOf(SqlValue.Text(albumId), SqlValue.Int64(disc.toLong()))),
    ) == 1
