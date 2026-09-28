package app.winters.octo.livelists

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.listening.PlayHistory
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.select
import app.winters.octo.sort.TrackFields
import app.winters.octo.sort.songListening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// The library as a live list reads it: every song, with its likes and its
// listening (the phone's plays and the server's).
class LiveLibrary(val tracks: List<TrackEntity>, val fields: TrackFields) {
    fun pick(query: LibraryQuery, now: Long = System.currentTimeMillis()): List<TrackEntity> = query.select(tracks, now, fields)
}

// Works out the songs a live list picks, again whenever the library, a
// like or a play changes, away from the main thread.
@Singleton
class LiveListSongs internal constructor(
    tracks: Flow<List<TrackEntity>>,
    liked: Flow<List<String>>,
    played: Flow<List<PlayedTrack>>,
) {
    @Inject constructor(dao: CatalogDao, user: UserDao, history: PlayHistory) : this(dao.tracks(), user.likedIds(), history.tracks)

    val library: Flow<LiveLibrary> = combine(tracks, liked, played) { all, likes, plays ->
        LiveLibrary(all, TrackFields(likes.toHashSet(), songListening(plays)))
    }.flowOn(Dispatchers.Default)

    // The songs `query` picks, kept up to date.
    fun songs(query: Flow<LibraryQuery>): Flow<List<TrackEntity>> =
        combine(library, query) { lib, q -> lib.pick(q) }.flowOn(Dispatchers.Default)

    // The songs a list picks right now, for a copy of it or a play from a menu.
    suspend fun now(list: LiveList): List<TrackEntity> = library.map { it.pick(list.query) }.flowOn(Dispatchers.Default).first()
}
