package app.winters.octo.catalog

import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.device.DEVICE
import app.winters.octo.server.isServerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Rebuilds the library the screens read from what the sources have, after
// any source changes. One rebuild at a time, the phone's files first so its
// songs keep their ids.
//
// Every kept server's music is kept (see ServerSync), but only the server
// in use joins the library, so two servers' copies of a song never become
// one row. The other servers' copies are no library song's meanwhile: they
// lose their merged id, so nothing that looks up a song's copies (playing,
// stars, plays, downloads) finds them.
@Singleton
class CatalogMerge @Inject constructor(
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val user: UserDao,
    private val favourites: FavouritesDao,
    private val sessions: SessionRepository,
) {
    private val lock = Mutex()

    // Returns how big the merged library is. Songs are matched as slim rows
    // and the library is written 500 songs at a time, so a library of 100,000
    // songs is never held whole (it ran a phone out of memory).
    suspend fun rebuild(): LibrarySize = lock.withLock {
        val server = serverInUse()
        val ids = sources.sourceIds()
            .filter { inLibrary(it, server) }
            .sortedBy { if (it == DEVICE) "" else it }
        val plan = planned(ids)
        catalog.replaceAllPlanned(plan.songs, plan.albums, plan.artists)
        sources.forgetMergedExcept(ids)
        // Each copy remembers its library song, so playback can find them all.
        sources.setMergedIds(plan.mergedIds)
        // Likes, plays and playlists follow songs whose ids changed.
        user.relinkAll()
        // So do favourite albums and artists, and pins on Home.
        favourites.relinkAll()
        // Songs with no rating on a server show the one made on the phone.
        user.showPhoneRatings()
        LibrarySize(plan.songs.size, plan.albums.size, plan.artists.size)
    }

    // A function of its own, so the rows are let go of once the plan is made.
    private suspend fun planned(ids: List<String>): MergePlan {
        val rows = ids.map { id -> SourceRows(id, onPhone = id == DEVICE, sources.mergeRows(id), sources.albums(id), sources.artists(id)) }
        return withContext(Dispatchers.Default) { planMerge(rows) }
    }

    // Runs `block` between rebuilds, never during one, so it sees song ids
    // and everything that follows them already in step.
    suspend fun <T> betweenRebuilds(block: suspend () -> T): T = lock.withLock { block() }

    // The server source in use once the saved sign-in is read, or null.
    private suspend fun serverInUse(): String? =
        (sessions.state.first { it !is SessionState.Loading } as? SessionState.SignedIn)?.session?.sourceId
}

// Whether a source's music joins the library: the phone's own always, a
// server's only while it is the one in use.
fun inLibrary(sourceId: String, serverInUse: String?): Boolean = !isServerSource(sourceId) || sourceId == serverInUse

// How many songs, albums and artists the library has.
class LibrarySize(val tracks: Int, val albums: Int, val artists: Int)
