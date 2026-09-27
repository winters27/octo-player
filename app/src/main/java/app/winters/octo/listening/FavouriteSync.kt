package app.winters.octo.listening

import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.LikedAlbumEntity
import app.winters.octo.catalog.LikedArtistEntity
import app.winters.octo.catalog.ServerCopy
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// How many albums or artists go in one star or unstar call.
private const val FAVOURITE_BATCH = 50

// The server call that stars or unstars albums or artists, each by its own
// parameter (albumId or artistId), never as a song id.
fun favouriteCall(client: SubsonicClient, kind: FavouriteKind, star: Boolean): suspend (List<String>) -> Unit =
    when (kind) {
        FavouriteKind.Album -> if (star) client::starAlbums else client::unstarAlbums
        FavouriteKind.Artist -> if (star) client::starArtists else client::unstarArtists
    }

// Keeps favourite albums and artists in step with stars on the signed-in
// server, the way ListeningSync does for liked songs: a favourite made on
// the phone stars the server's copy, and stars made elsewhere come back as
// favourites after each sync. Albums and artists only on the phone stay
// local. Nothing here shows an error; a change that fails to send is tried
// again shortly, and the next sync catches whatever is still left.
@Singleton
class FavouriteSync @Inject constructor(
    private val sessions: SessionRepository,
    private val favourites: FavouritesDao,
    private val store: FavouriteSyncStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    // Runs after each sync of the server's library.
    suspend fun afterSync() {
        val session = signedIn() ?: return
        val client = session.client
        // Without the server's stars as they are now, nothing can be decided.
        val starred = try {
            client.starred()
        } catch (e: SubsonicException) {
            return
        }
        lock.withLock {
            val albums = copies(session, FavouriteKind.Album)
            if (albums.isNotEmpty()) {
                val times = starred.album.associate { it.id to parseServerTime(it.starred) }
                reconcile(client, FavouriteKind.Album, albums, likedIds(FavouriteKind.Album), times)
            }
            val artists = copies(session, FavouriteKind.Artist)
            if (artists.isNotEmpty()) {
                val times = starred.artist.associate { it.id to parseServerTime(it.starred) }
                reconcile(client, FavouriteKind.Artist, artists, likedIds(FavouriteKind.Artist), times)
            }
        }
    }

    // A favourite changed on the phone. The server's copies follow at once;
    // a try that fails is made again a few times, then left to the next
    // sync. An album or artist only on the phone has no copies to send.
    fun changed(kind: FavouriteKind, libraryId: String, liked: Boolean) {
        scope.launch {
            retried(FAVOURITE_RETRY_WAITS) { sendChange(kind, libraryId, liked) }
        }
    }

    // One try at sending a change. True when nothing is left to send.
    private suspend fun sendChange(kind: FavouriteKind, libraryId: String, liked: Boolean): Boolean {
        // No server at all means nothing to send; one still signing in is tried again.
        if (sessions.state.value is SessionState.SignedOut) return true
        val session = signedIn() ?: return false
        return lock.withLock {
            val ids = idsToSend(copies(session, kind), libraryId, liked, likedNow = libraryId in likedIds(kind))
            if (ids.isEmpty()) return@withLock true
            val sent = failureOf { favouriteCall(session.client, kind, star = liked)(ids) } == null
            if (sent) store.updateSynced(kind) { if (liked) it + ids else it - ids.toSet() }
            sent
        }
    }

    // The favourite albums or artists on the phone now.
    private suspend fun likedIds(kind: FavouriteKind): Set<String> =
        when (kind) {
            FavouriteKind.Album -> favourites.likedAlbumIds().first()
            FavouriteKind.Artist -> favourites.likedArtistIds().first()
        }.toSet()

    private suspend fun reconcile(
        client: SubsonicClient,
        kind: FavouriteKind,
        copies: List<ServerCopy>,
        liked: Set<String>,
        starredAt: Map<String, Long?>,
    ) {
        val plan = reconcileStars(copies, liked, starredAt.keys, store.synced(kind))
        val starFailed = inBatches(plan.star, favouriteCall(client, kind, star = true))
        val unstarFailed = inBatches(plan.unstar, favouriteCall(client, kind, star = false))
        // A favourite from the server keeps the time it was starred there.
        val likedAt = copies.groupBy({ it.trackId }, { starredAt[it.serverId] }).mapValues { (_, times) -> times.filterNotNull().minOrNull() }
        val now = System.currentTimeMillis()
        when (kind) {
            FavouriteKind.Album -> {
                favourites.likeAlbums(plan.like.mapNotNull { id -> favourites.albumKey(id)?.let { LikedAlbumEntity(id, it.searchKey, likedAt[id] ?: now) } })
                plan.unlike.forEach { favourites.unlikeAlbum(it) }
            }
            FavouriteKind.Artist -> {
                favourites.likeArtists(plan.like.mapNotNull { id -> favourites.artistKey(id)?.let { LikedArtistEntity(id, it.searchKey, likedAt[id] ?: now) } })
                plan.unlike.forEach { favourites.unlikeArtist(it) }
            }
        }
        store.updateSynced(kind) { plan.settled(starFailed, unstarFailed) }
    }

    // The library's albums or artists and their copies on the signed-in server.
    private suspend fun copies(session: Session, kind: FavouriteKind): List<ServerCopy> {
        val counts = when (kind) {
            FavouriteKind.Album -> favourites.serverAlbumCounts(session.sourceId)
            FavouriteKind.Artist -> favourites.serverArtistCounts(session.sourceId)
        }
        return serverCopiesOf(counts, session.sourceId)
    }

    // The ids that did not go through.
    private suspend fun inBatches(ids: Set<String>, call: suspend (List<String>) -> Unit): Set<String> =
        ids.sorted().chunked(FAVOURITE_BATCH).filter { failureOf { call(it) } != null }.flatten().toSet()

    // The signed-in session, if there is one.
    private suspend fun signedIn(): Session? {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return null
        store.useServer("${session.client.username}@${session.client.primaryUrl}")
        return session
    }

    // Runs a server call, handing back what went wrong, if anything.
    private suspend fun failureOf(call: suspend () -> Unit): SubsonicException? =
        try {
            call()
            null
        } catch (e: SubsonicException) {
            e
        }
}
