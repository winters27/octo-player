package app.winters.octo.favourites

import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.LikedAlbumEntity
import app.winters.octo.catalog.LikedArtistEntity
import app.winters.octo.catalog.SongIds
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.listening.FavouriteSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Favourite albums and artists, as the screens see them. A change is saved
// on the phone first, then the server's copy follows. A change is written on
// the album or artist its id names now (SongIds), between groups of a save,
// and while a save is part way a favorite on an old id or its new one shows
// on both.
@Singleton
class FavouriteStore @Inject constructor(
    private val dao: FavouritesDao,
    private val sync: FavouriteSync,
    private val songIds: SongIds,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val albums: StateFlow<Set<String>> =
        dao.favouriteAlbumIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val artists: StateFlow<Set<String>> =
        dao.favouriteArtistIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun ids(kind: FavouriteKind): StateFlow<Set<String>> = if (kind == FavouriteKind.Album) albums else artists

    fun toggle(kind: FavouriteKind, id: String) = set(kind, id, id !in ids(kind).value)

    fun set(kind: FavouriteKind, id: String, favourite: Boolean) {
        scope.launch {
            val now = songIds.write {
                when (kind) {
                    FavouriteKind.Album -> {
                        val now = songIds.album(id)
                        if (favourite) {
                            val key = dao.albumKey(now) ?: return@write null
                            dao.likeAlbums(listOf(LikedAlbumEntity(now, key.searchKey, System.currentTimeMillis())))
                        } else {
                            dao.unlikeAlbumEither(now)
                        }
                        now
                    }
                    FavouriteKind.Artist -> {
                        val now = songIds.artist(id)
                        if (favourite) {
                            val key = dao.artistKey(now) ?: return@write null
                            dao.likeArtists(listOf(LikedArtistEntity(now, key.searchKey, System.currentTimeMillis())))
                        } else {
                            dao.unlikeArtistEither(now)
                        }
                        now
                    }
                }
            } ?: return@launch
            sync.changed(kind, now, favourite)
        }
    }
}
