package app.winters.octo.listening

import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.UserDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

// What was played, on the phone and on the server together. The history
// shelves on Home and in the car read this.
@Singleton
class PlayHistory @Inject constructor(user: UserDao, store: ListeningStore) {
    val tracks: Flow<List<PlayedTrack>> =
        combine(user.playedTracks(), user.serverPlayedTracks(), store.sent, ::withServerPlays)

    val albums: Flow<List<PlayedAlbum>> =
        combine(user.playedAlbums(), user.serverPlayedAlbums(), ::withServerAlbumPlays)
}
