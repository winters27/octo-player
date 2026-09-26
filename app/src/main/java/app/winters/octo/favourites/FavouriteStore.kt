package app.winters.octo.favourites

import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.LikedAlbumEntity
import app.winters.octo.catalog.LikedArtistEntity
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
// on the phone first, then the server's copy follows.
@Singleton
class FavouriteStore @Inject constructor(
    private val dao: FavouritesDao,
    private val sync: FavouriteSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val albums: StateFlow<Set<String>> =
        dao.likedAlbumIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val artists: StateFlow<Set<String>> =
        dao.likedArtistIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun ids(kind: FavouriteKind): StateFlow<Set<String>> = if (kind == FavouriteKind.Album) albums else artists

    fun toggle(kind: FavouriteKind, id: String) = set(kind, id, id !in ids(kind).value)

    fun set(kind: FavouriteKind, id: String, favourite: Boolean) {
        scope.launch {
            when (kind) {
                FavouriteKind.Album -> if (favourite) {
                    val key = dao.albumKey(id) ?: return@launch
                    dao.likeAlbums(listOf(LikedAlbumEntity(id, key.searchKey, System.currentTimeMillis())))
                } else {
                    dao.unlikeAlbum(id)
                }
                FavouriteKind.Artist -> if (favourite) {
                    val key = dao.artistKey(id) ?: return@launch
                    dao.likeArtists(listOf(LikedArtistEntity(id, key.searchKey, System.currentTimeMillis())))
                } else {
                    dao.unlikeArtist(id)
                }
            }
            sync.changed(kind, id, favourite)
        }
    }
}
