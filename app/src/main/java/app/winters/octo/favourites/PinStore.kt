package app.winters.octo.favourites

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.PIN_LIMIT
import app.winters.octo.catalog.PinKind
import app.winters.octo.catalog.PinnedItemEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.playback.PlaylistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

// One pin: what kind of thing, and which.
data class PinKey(val kind: PinKind, val id: String)

// A pin as the Home shelf draws it.
sealed interface PinnedItem {
    val key: PinKey

    data class Album(val album: AlbumEntity) : PinnedItem {
        override val key get() = PinKey(PinKind.Album, album.id)
    }

    data class Artist(val artist: ArtistEntity) : PinnedItem {
        override val key get() = PinKey(PinKind.Artist, artist.id)
    }

    data class Playlist(val playlist: PlaylistSummary) : PinnedItem {
        override val key get() = PinKey(PinKind.Playlist, playlist.id)
    }
}

// Albums, artists and playlists pinned to the front of Home, in the order
// they were pinned unless moved. Home holds at most PIN_LIMIT.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PinStore @Inject constructor(
    private val dao: FavouritesDao,
    private val catalog: CatalogDao,
    private val playlists: PlaylistStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The pins Home can show now, in row order.
    val shown: StateFlow<List<PinKey>> = dao.shownPins()
        .map { rows -> rows.mapNotNull { row -> PinKind.of(row.kind)?.let { PinKey(it, row.itemId) } }.take(PIN_LIMIT) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // The pins with what they show, for the shelf.
    val items: Flow<List<PinnedItem>> = shown.flatMapLatest { keys ->
        if (keys.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(keys.map(::itemFor)) { found -> found.filterNotNull() }
        }
    }

    // Pins at the end of the row. False when Home is full.
    suspend fun pin(key: PinKey): Boolean {
        val relinkKey = when (key.kind) {
            PinKind.Album -> dao.albumKey(key.id)?.searchKey ?: return false
            PinKind.Artist -> dao.artistKey(key.id)?.searchKey ?: return false
            // A playlist's id never changes.
            PinKind.Playlist -> key.id
        }
        return dao.pin(PinnedItemEntity(key.kind.id, key.id, relinkKey, position = 0))
    }

    suspend fun unpin(key: PinKey) = dao.unpin(key.kind.id, key.id)

    suspend fun moveToFront(key: PinKey) = dao.moveToFront(key.kind.id, key.id)

    private fun itemFor(key: PinKey): Flow<PinnedItem?> = when (key.kind) {
        PinKind.Album -> catalog.album(key.id).map { it?.let(PinnedItem::Album) }
        PinKind.Artist -> catalog.artist(key.id).map { it?.let(PinnedItem::Artist) }
        PinKind.Playlist -> playlists.playlists.map { list -> list.firstOrNull { it.id == key.id }?.let(PinnedItem::Playlist) }
    }
}
