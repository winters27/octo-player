package app.winters.octo.sort

import androidx.room.RoomRawQuery
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.PlayHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// A list in the order it was asked for. When the order is by name, each
// item's name to file it under a letter; null otherwise.
class Sorted<T>(val items: List<T>, val order: SortOrder, val headings: List<String>?)

// The library's lists in the order the listener chose for each, kept up to
// date as the library, the likes, the plays and the chosen order change.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SortedLibrary @Inject constructor(
    private val dao: CatalogDao,
    private val history: PlayHistory,
    val settings: SortSettings,
) {
    fun songs(list: SortList, scope: SongScope): Flow<Sorted<TrackEntity>> =
        settings.order(list).flatMapLatest { order -> songs(order, scope) }

    fun albums(list: SortList, artistId: String? = null): Flow<Sorted<AlbumEntity>> =
        settings.order(list).flatMapLatest { order -> albums(order, artistId) }

    fun artists(): Flow<Sorted<ArtistEntity>> =
        settings.order(SortList.Artists).flatMapLatest { order -> artists(order) }

    private fun songs(order: SortOrder, scope: SongScope): Flow<Sorted<TrackEntity>> {
        val sort = order.by as? SongSort ?: SongSort.Title
        val query = songQuery(sort, order.descending, scope)
        val rows = if (query.readsLikes) dao.sortedTracksWithLikes(query.toRoom()) else dao.sortedTracks(query.toRoom())
        if (!sort.byListening) {
            return rows.map { list -> Sorted(list.map { it.track }, order, headingsOf(sort, list) { it.heading }) }
        }
        return combine(rows, history.tracks) { list, played ->
            val tracks = list.map { it.track }
            Sorted(byListening(tracks, { it.id }, songListening(played), sort == SongSort.MostPlayed, order.descending), order, null)
        }.flowOn(Dispatchers.Default)
    }

    private fun albums(order: SortOrder, artistId: String?): Flow<Sorted<AlbumEntity>> {
        val sort = order.by as? AlbumSort ?: AlbumSort.Title
        val rows = dao.sortedAlbums(albumQuery(sort, order.descending, artistId).toRoom())
        if (!sort.byListening) {
            return rows.map { list -> Sorted(list.map { it.album }, order, headingsOf(sort, list) { it.heading }) }
        }
        return combine(rows, history.tracks, history.albums) { list, played, playedAlbums ->
            val albums = list.map { it.album }
            val listening = albumListening(played, playedAlbums)
            Sorted(byListening(albums, { it.id }, listening, sort == AlbumSort.MostPlayed, order.descending), order, null)
        }.flowOn(Dispatchers.Default)
    }

    private fun artists(order: SortOrder): Flow<Sorted<ArtistEntity>> {
        val sort = order.by as? ArtistSort ?: ArtistSort.Name
        val rows = dao.sortedArtists(artistQuery(sort, order.descending).toRoom())
        if (!sort.byListening) {
            return rows.map { list -> Sorted(list.map { it.artist }, order, headingsOf(sort, list) { it.heading }) }
        }
        return combine(rows, history.tracks) { list, played ->
            val artists = list.map { it.artist }
            Sorted(byListening(artists, { it.id }, artistListening(played), sort == ArtistSort.MostPlayed, order.descending), order, null)
        }.flowOn(Dispatchers.Default)
    }

    suspend fun setOrder(list: SortList, order: SortOrder) = settings.set(list, order)
}

private fun <R> headingsOf(sort: SortOption, rows: List<R>, heading: (R) -> String?): List<String>? =
    if (sort.byName) rows.map { heading(it).orEmpty() } else null

// The query for Room, with its values bound to the ? marks in order.
private fun SqlQuery.toRoom(): RoomRawQuery =
    RoomRawQuery(sql) { statement -> args.forEachIndexed { index, value -> statement.bindText(index + 1, value) } }
