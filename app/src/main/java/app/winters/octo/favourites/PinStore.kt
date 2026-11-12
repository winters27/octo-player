package app.winters.octo.favourites

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.PIN_LIMIT
import app.winters.octo.catalog.PinKind
import app.winters.octo.catalog.PinnedItemEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.SongIds
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
// they were pinned unless moved. Home holds at most PIN_LIMIT. A pin
// change is written on the album or artist its id names now (SongIds),
// between groups of a save; while a save is part way a pin still on an old
// id shows as the id it became.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PinStore internal constructor(
    private val dao: FavouritesDao,
    private val catalog: CatalogDao,
    // A playlist's summary, as it changes.
    private val summary: (String) -> Flow<PlaylistSummary?>,
    private val ids: SongIds,
) {
    @Inject constructor(dao: FavouritesDao, catalog: CatalogDao, playlists: PlaylistStore, ids: SongIds) : this(dao, catalog, playlists::summary, ids)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The pins Home can show now, in row order, each once.
    val shown: StateFlow<List<PinKey>> = dao.shownPins()
        .map { rows -> rows.mapNotNull { row -> PinKind.of(row.kind)?.let { PinKey(it, row.itemId) } }.distinct().take(PIN_LIMIT) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // A pin's key on the id it names now, as that changes: what to look
    // for in `shown` when the id may be an old one.
    fun current(key: PinKey): Flow<PinKey> =
        if (key.kind == PinKind.Playlist) flowOf(key) else dao.currentIdFlow(key.kind.id, key.id).map { PinKey(key.kind, it) }

    // The pins with what they show, for the shelf.
    val items: Flow<List<PinnedItem>> = shown.flatMapLatest { keys ->
        if (keys.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(keys.map(::itemFor)) { found -> found.filterNotNull() }
        }
    }

    // Pins at the end of the row. False when Home is full.
    suspend fun pin(key: PinKey): Boolean = ids.write {
        val id = now(key)
        val relinkKey = when (key.kind) {
            PinKind.Album -> dao.albumKey(id)?.searchKey ?: return@write false
            PinKind.Artist -> dao.artistKey(id)?.searchKey ?: return@write false
            // A playlist's id never changes.
            PinKind.Playlist -> id
        }
        dao.pinEither(PinnedItemEntity(key.kind.id, id, relinkKey, position = 0))
    }

    suspend fun unpin(key: PinKey) = ids.write { dao.unpinEither(key.kind.id, now(key)) }

    suspend fun moveToFront(key: PinKey) = ids.write { dao.moveToFrontEither(key.kind.id, now(key)) }

    // The id a pin's key names now; a playlist's never changes.
    private suspend fun now(key: PinKey): String = when (key.kind) {
        PinKind.Album -> ids.album(key.id)
        PinKind.Artist -> ids.artist(key.id)
        PinKind.Playlist -> key.id
    }

    private fun itemFor(key: PinKey): Flow<PinnedItem?> = when (key.kind) {
        PinKind.Album -> catalog.album(key.id).map { it?.let(PinnedItem::Album) }
        PinKind.Artist -> catalog.artist(key.id).map { it?.let(PinnedItem::Artist) }
        PinKind.Playlist -> summary(key.id).map { it?.let(PinnedItem::Playlist) }
    }
}
