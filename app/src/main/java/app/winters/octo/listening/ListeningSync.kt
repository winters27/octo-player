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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

    // Runs after each sync of the server's library: brings likes and stars
    // together, notes which sent plays the server now counts, and sends plays
    // that could not be sent before.
    suspend fun afterSync() {
        val client = signedIn() ?: return
        val copies = sources.serverCopies()
        if (copies.isEmpty()) return
        reconcile(client, copies)
        syncRatings(client)
        store.updateSent { it.foldedInto(copies.associate { copy -> copy.serverId to copy.lastPlayedAt }) }
        sendPending(client)
    }

    // A like changed on the phone. The song's server copies follow; a song
    // only on the phone has none.
    fun likeChanged(trackId: String, liked: Boolean) {
        scope.launch {
            val client = signedIn() ?: return@launch
            val ids = serverIds(trackId).ifEmpty { return@launch }
            stars.withLock {
                val failure = failureOf { if (liked) client.star(ids) else client.unstar(ids) }
                if (failure == null) store.updateSynced { if (liked) it + ids else it - ids }
            }
        }
    }

    // A rating changed on the phone. The song's server copies follow; when
    // the server cannot take it now, the next sync sends it.
    fun ratingChanged(trackId: String) {
        if (isFind(trackId)) return
        scope.launch {
            val client = signedIn() ?: return@launch
            val ids = serverIds(trackId).ifEmpty { return@launch }
            ratings.withLock {
                // The rating as it is now, so quick changes end on the last one.
                val rating = cleanRating(user.rating(trackId))
                val sent = ids.all { failureOf { client.setRating(it, rating) } == null }
                if (sent) store.updateSyncedRatings { if (rating > 0) it + ids.associateWith { rating } else it - ids.toSet() }
            }
        }
    }

    // A song started playing, so the server shows it as playing now.
    fun nowPlaying(trackId: String) {
        scope.launch {
            val client = signedIn() ?: return@launch
            val id = serverIds(trackId).firstOrNull() ?: return@launch
            failureOf { client.scrobble(id, System.currentTimeMillis(), submission = false) }
        }
    }

    // A play counted. It waits in the queue until the server has it.
    fun played(trackId: String, startedAt: Long) {
        scope.launch {
            val client = signedIn()
            val id = serverIds(trackId).firstOrNull() ?: return@launch
            store.addPending(PendingPlay(id, startedAt))
            if (client != null) sendPending(client)
        }
    }

    private suspend fun reconcile(client: SubsonicClient, copies: List<ServerCopy>) = stars.withLock {
        // Without the server's stars as they are now, nothing can be decided.
        val starred = try {
            client.starred().song
        } catch (e: SubsonicException) {
            return@withLock
        }
        val liked = user.likedIds().first().toSet()
        val plan = reconcileStars(copies, liked, starred.mapTo(HashSet()) { it.id }, store.synced())
        val starFailed = inBatches(plan.star, client::star)
        val unstarFailed = inBatches(plan.unstar, client::unstar)
        // A like from the server keeps the time it was starred there.
        val starredAt = starred.associate { it.id to parseServerTime(it.starred) }
        val likedAt = copies.groupBy({ it.trackId }, { starredAt[it.serverId] }).mapValues { (_, times) -> times.filterNotNull().minOrNull() }
        val now = System.currentTimeMillis()
        user.likeAll(
            catalog.tracksByIds(plan.like.toList()).map { LikedTrackEntity(it.id, it.relinkKey, likedAt[it.id] ?: now) },
        )
        plan.unlike.forEach { user.unlike(it) }
        store.updateSynced { plan.settled(starFailed, unstarFailed) }
    }

    private suspend fun syncRatings(client: SubsonicClient) = ratings.withLock {
        val before = store.syncedRatings()
        val local = user.ratings().associate { it.trackId to cleanRating(it.rating) }
        val plan = reconcileRatings(sources.serverRatings(), local, before)
        val failed = plan.send.filter { (_, send) ->
            send.serverIds.any { failureOf { client.setRating(it, send.rating) } != null }
        }.keys
        plan.send.forEach { (song, send) -> if (song !in failed) sources.setServerRating(song, send.rating) }
        val now = System.currentTimeMillis()
        val tracks = catalog.tracksByIds(plan.adopt.keys.toList()).associateBy { it.id }
        plan.adopt.forEach { (song, rating) ->
            if (rating == 0) user.unrate(song) else tracks[song]?.let { user.rate(TrackRatingEntity(song, it.relinkKey, rating, now)) }
        }
        // The library shows the rating both sides now have, or will once sent.
        (plan.send.mapValues { it.value.rating } + plan.adopt).forEach { (song, rating) -> user.showRating(song, rating) }
        store.updateSyncedRatings { plan.settled(failed, before) }
    }

    // Sends the waiting plays oldest first, and stops at the first one the
    // server cannot take right now.
    private suspend fun sendPending(client: SubsonicClient) = plays.withLock {
        for (play in store.pending()) {
            val failure = failureOf { client.scrobble(play.serverId, play.startedAt, submission = true) }
            if (failure != null && worthRetrying(failure)) break
            store.finishPending(play, sent = failure == null)
        }
    }

    // The ids that did not go through.
    private suspend fun inBatches(ids: Set<String>, call: suspend (List<String>) -> Unit): Set<String> =
        ids.sorted().chunked(STAR_BATCH).filter { failureOf { call(it) } != null }.flatten().toSet()

    // The client for the signed-in server, if there is one.
    private suspend fun signedIn(): SubsonicClient? {
        val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client ?: return null
        store.useServer("${client.username}@${client.baseUrl}")
        return client
    }

    // A song's copies on the server, the same one first every time.
    // A song found online is a server song itself.
    private suspend fun serverIds(trackId: String): List<String> =
        if (isFind(trackId)) {
            listOf(trackId.removePrefix(FIND_PREFIX))
        } else {
            sources.copies(trackId).filter { it.sourceId.startsWith("server:") }.map { it.nativeId }.sorted()
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
