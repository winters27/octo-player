package app.winters.octo.sort

import androidx.room.RoomRawQuery
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.HoldKind
import app.winters.octo.listening.PlayTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

// A list in the order it was asked for. When the order is by name, each
// item's name to file it under a letter; null otherwise.
class Sorted<T>(val items: List<T>, val order: SortOrder, val headings: List<String>?)

// Each album's plays, from the stored totals in one walk of their album
// index: its played songs' plays added up, and the latest of their last
// plays and its songs' recent plays on a server.
internal const val ALBUM_LISTENING_SQL = """
    SELECT albumId AS id, SUM(CASE WHEN plays > 0 THEN plays ELSE 0 END) AS plays,
        MAX(IFNULL(MAX(CASE WHEN plays > 0 THEN lastPlayedAt END), 0), IFNULL(MAX(recentAt), 0)) AS lastPlayedAt
    FROM play_total GROUP BY albumId
"""

// Each artist's plays: their played songs' plays added up, and the latest.
internal const val ARTIST_LISTENING_SQL = """
    SELECT artistId AS id, SUM(plays) AS plays, MAX(lastPlayedAt) AS lastPlayedAt
    FROM play_total WHERE plays > 0 GROUP BY artistId
"""

// The library's albums and artists in the order the listener chose for
// each, kept up to date as the library, the plays and the chosen order
// change. Both are album- and artist-sized and stay whole; their orders by
// plays read the stored totals' sums by album and by artist, under the
// totals' reader rule. Song lists are read a page at a time (ListLoaders).
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SortedLibrary @Inject constructor(
    private val dao: CatalogDao,
    val settings: SortSettings,
    private val db: CatalogDatabase,
    private val totals: PlayTotals,
) {
    fun albums(list: SortList, artistId: String? = null): Flow<Sorted<AlbumEntity>> =
        settings.order(list).flatMapLatest { order -> albums(order, artistId) }

    fun artists(): Flow<Sorted<ArtistEntity>> =
        settings.order(SortList.Artists).flatMapLatest { order -> artists(order) }

    // Every album's or every artist's id in an order, for playing them
    // one after another (album- or artist-sized).
    suspend fun albumIds(order: SortOrder): List<String> = albums(order, null).first().items.map { it.id }

    suspend fun artistIds(order: SortOrder): List<String> = artists(order).first().items.map { it.id }

    // The sums by album or by artist, read again when the totals change.
    private fun listening(sql: String): Flow<Map<String, Listening>> =
        db.invalidationTracker.createFlow("play_total", emitInitialState = false).map { }.onStart { emit(Unit) }.conflate().map {
            totals.read(HoldKind.Read) { db.listsDao().listening(RoomRawQuery(sql)) }.associate { it.id to Listening(it.plays, it.lastPlayedAt) }
        }

    private fun albums(order: SortOrder, artistId: String?): Flow<Sorted<AlbumEntity>> {
        val sort = order.by as? AlbumSort ?: AlbumSort.Title
        val rows = dao.sortedAlbums(albumQuery(sort, order.descending, artistId).toRoom())
        if (!sort.byListening) {
            return rows.map { list -> Sorted(list.map { it.album }, order, headingsOf(sort, list) { it.heading }) }
        }
        return combine(rows, listening(ALBUM_LISTENING_SQL)) { list, listening ->
            val albums = list.map { it.album }
            Sorted(byListening(albums, { it.id }, listening, sort == AlbumSort.MostPlayed, order.descending), order, null)
        }.flowOn(Dispatchers.Default)
    }

    private fun artists(order: SortOrder): Flow<Sorted<ArtistEntity>> {
        val sort = order.by as? ArtistSort ?: ArtistSort.Name
        val rows = dao.sortedArtists(artistQuery(sort, order.descending).toRoom())
        if (!sort.byListening) {
            return rows.map { list -> Sorted(list.map { it.artist }, order, headingsOf(sort, list) { it.heading }) }
        }
        return combine(rows, listening(ARTIST_LISTENING_SQL)) { list, listening ->
            val artists = list.map { it.artist }
            Sorted(byListening(artists, { it.id }, listening, sort == ArtistSort.MostPlayed, order.descending), order, null)
        }.flowOn(Dispatchers.Default)
    }

    suspend fun setOrder(list: SortList, order: SortOrder) = settings.set(list, order)
}

private fun <R> headingsOf(sort: SortOption, rows: List<R>, heading: (R) -> String?): List<String>? =
    if (sort.byName) rows.map { heading(it).orEmpty() } else null

// The query for Room, with its values bound to the ? marks in order.
private fun SqlQuery.toRoom(): RoomRawQuery =
    RoomRawQuery(sql) { statement -> args.forEachIndexed { index, value -> statement.bindText(index + 1, value) } }
