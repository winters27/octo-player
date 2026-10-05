package app.winters.octo.listening

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.ServerCopy
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.TrackRatingEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.tracksByIds
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// How many songs go in one star or unstar call, so the address stays short.
private const val STAR_BATCH = 50

// Keeps what the listener does in step with the signed-in server: a heart
// stars the song's server copy, a rating rates it, plays count on the
// server, and stars and ratings made elsewhere come back. None of it ever holds up playback or shows an
// error; what fails is tried again later.
//
// Each change is for the server in use when it was made, even when it is
// sent after a switch: a play counts for the server it was played from.
@Singleton
class ListeningSync @Inject constructor(
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val user: UserDao,
    private val catalog: CatalogDao,
    private val store: ListeningStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stars = Mutex()
    private val plays = Mutex()
    private val ratings = Mutex()

    // Plays still being noted, so a switch can wait for them.
    private val noting = HashSet<Job>()

    // The server a change is for: its client, its account and its source.
    private class Server(val client: SubsonicClient, val account: String, val sourceId: String)

    // Runs after each sync of the server's library: brings likes and stars
    // together, notes which sent plays the server now counts, and sends plays
    // that could not be sent before.
    suspend fun afterSync() {
        val server = inUse() ?: return
        val copies = sources.serverCopies()
        if (copies.isEmpty()) return
        reconcile(server, copies)
        syncRatings(server)
        store.updateSent(server.account) { it.foldedInto(copies.associate { copy -> copy.serverId to copy.lastPlayedAt }) }
        sendPending(server)
    }

    // A like changed on the phone. The song's server copies follow; a song
    // only on the phone has none.
    fun likeChanged(trackId: String, liked: Boolean) {
        val server = inUse() ?: return
        scope.launch {
            val ids = serverIds(server, trackId).ifEmpty { return@launch }
            stars.withLock {
                val failure = failureOf { if (liked) server.client.star(ids) else server.client.unstar(ids) }
                if (failure == null) store.updateSynced(server.account) { if (liked) it + ids else it - ids }
            }
        }
    }

    // A rating changed on the phone. The song's server copies follow; when
    // the server cannot take it now, the next sync sends it.
    fun ratingChanged(trackId: String) {
        if (isFind(trackId)) return
        val server = inUse() ?: return
        scope.launch {
            val ids = serverIds(server, trackId).ifEmpty { return@launch }
            ratings.withLock {
                // The rating as it is now, so quick changes end on the last one.
                val rating = cleanRating(user.rating(trackId))
                val sent = ids.all { failureOf { server.client.setRating(it, rating) } == null }
                if (sent) store.updateSyncedRatings(server.account) { if (rating > 0) it + ids.associateWith { rating } else it - ids.toSet() }
            }
        }
    }

    // A song started playing, so the server shows it as playing now.
    fun nowPlaying(trackId: String) {
        val server = inUse() ?: return
        scope.launch {
            val id = serverIds(server, trackId).firstOrNull() ?: return@launch
            failureOf { server.client.scrobble(id, System.currentTimeMillis(), submission = false) }
        }
    }

    // A play counted. It waits in its server's queue until the server has it.
    fun played(trackId: String, startedAt: Long) {
        val server = inUse() ?: return
        val job = scope.launch {
            val id = serverIds(server, trackId).firstOrNull() ?: return@launch
            store.addPending(server.account, PendingPlay(id, startedAt))
            sendPending(server)
        }
        synchronized(noting) { noting += job }
        job.invokeOnCompletion { synchronized(noting) { noting -= job } }
    }

    // Waits until every play counted so far is noted for its server: before
    // a switch, so the last song counts for the server it played from.
    suspend fun noted() {
        synchronized(noting) { noting.toList() }.joinAll()
    }

    private suspend fun reconcile(server: Server, copies: List<ServerCopy>) = stars.withLock {
        // Without the server's stars as they are now, nothing can be decided.
        val starred = try {
            server.client.starred().song
        } catch (e: SubsonicException) {
            return@withLock
        }
        val liked = user.likedIds().first().toSet()
        val plan = reconcileStars(copies, liked, starred.mapTo(HashSet()) { it.id }, store.synced(server.account))
        val starFailed = inBatches(plan.star, server.client::star)
        val unstarFailed = inBatches(plan.unstar, server.client::unstar)
        // A like from the server keeps the time it was starred there.
        val starredAt = starred.associate { it.id to parseServerTime(it.starred) }
        val likedAt = copies.groupBy({ it.trackId }, { starredAt[it.serverId] }).mapValues { (_, times) -> times.filterNotNull().minOrNull() }
        val now = System.currentTimeMillis()
        user.likeAll(
            catalog.tracksByIds(plan.like.toList()).map { LikedTrackEntity(it.id, it.relinkKey, likedAt[it.id] ?: now) },
        )
        plan.unlike.forEach { user.unlike(it) }
        store.updateSynced(server.account) { plan.settled(starFailed, unstarFailed) }
    }

    private suspend fun syncRatings(server: Server) = ratings.withLock {
        val before = store.syncedRatings(server.account)
        val local = user.ratings().associate { it.trackId to cleanRating(it.rating) }
        val plan = reconcileRatings(sources.serverRatings(), local, before)
        val failed = plan.send.filter { (_, send) ->
            send.serverIds.any { failureOf { server.client.setRating(it, send.rating) } != null }
        }.keys
        plan.send.forEach { (song, send) -> if (song !in failed) sources.setServerRating(song, send.rating) }
        val now = System.currentTimeMillis()
        val tracks = catalog.tracksByIds(plan.adopt.keys.toList()).associateBy { it.id }
        plan.adopt.forEach { (song, rating) ->
            if (rating == 0) user.unrate(song) else tracks[song]?.let { user.rate(TrackRatingEntity(song, it.relinkKey, rating, now)) }
        }
        // The library shows the rating both sides now have, or will once sent.
        (plan.send.mapValues { it.value.rating } + plan.adopt).forEach { (song, rating) -> user.showRating(song, rating) }
        store.updateSyncedRatings(server.account) { plan.settled(failed, before) }
    }

    // Sends the waiting plays oldest first, and stops at the first one the
    // server cannot take right now.
    private suspend fun sendPending(server: Server) = plays.withLock {
        for (play in store.pending(server.account)) {
            val failure = failureOf { server.client.scrobble(play.serverId, play.startedAt, submission = true) }
            if (failure != null && worthRetrying(failure)) break
            store.finishPending(server.account, play, sent = failure == null)
        }
    }

    // The ids that did not go through.
    private suspend fun inBatches(ids: Set<String>, call: suspend (List<String>) -> Unit): Set<String> =
        ids.sorted().chunked(STAR_BATCH).filter { failureOf { call(it) } != null }.flatten().toSet()

    // The server in use now, if there is one.
    private fun inUse(): Server? =
        (sessions.state.value as? SessionState.SignedIn)?.session?.let { Server(it.client, it.id, it.sourceId) }

    // A song's copies on the server, the same one first every time.
    // A song found online is a server song itself.
    private suspend fun serverIds(server: Server, trackId: String): List<String> =
        if (isFind(trackId)) {
            listOf(trackId.removePrefix(FIND_PREFIX))
        } else {
            sources.copies(trackId).filter { it.sourceId == server.sourceId }.map { it.nativeId }.sorted()
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
