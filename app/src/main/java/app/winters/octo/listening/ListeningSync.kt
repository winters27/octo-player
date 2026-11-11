package app.winters.octo.listening

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.ReadHold
import app.winters.octo.catalog.SongIds
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.SyncStarredEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.tracksByIds
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// How many songs go in one star or unstar call, so the address stays short.
private const val STAR_BATCH = 50

// Runs work between rebuilds of the library, never during one: `between`
// for a page of a walk, `read` for one short read.
interface Rebuilds {
    suspend fun <T> between(block: suspend () -> T): T

    suspend fun <T> read(block: suspend () -> T): T = between(block)
}

// Keeps what the listener does in step with the signed-in server: a heart
// stars the song's server copy, a rating rates it, plays count on the
// server, and stars and ratings made elsewhere come back. None of it ever holds up playback or shows an
// error; what fails is tried again later.
//
// Each change is for the server in use when it was made, even when it is
// sent after a switch: a play counts for the server it was played from.
@Singleton
class ListeningSync internal constructor(
    // The session in use now, if there is one.
    private val signedIn: () -> Session?,
    private val sources: SourceDao,
    private val user: UserDao,
    private val catalog: CatalogDao,
    private val listening: ListeningDao,
    private val store: ListeningStore,
    private val rebuilds: Rebuilds,
    // Told of every change to the plays sent, which play counts follow.
    private val totals: TotalsRequests = TotalsRequests.None,
) {
    @Inject constructor(
        sessions: SessionRepository,
        sources: SourceDao,
        user: UserDao,
        catalog: CatalogDao,
        listening: ListeningDao,
        store: ListeningStore,
        merge: Lazy<CatalogMerge>,
        totals: Lazy<PlayTotals>,
    ) : this(
        { (sessions.state.value as? SessionState.SignedIn)?.session },
        sources,
        user,
        catalog,
        listening,
        store,
        object : Rebuilds {
            override suspend fun <T> between(block: suspend () -> T): T = merge.get().betweenRebuilds(HoldKind.Page, block)

            override suspend fun <T> read(block: suspend () -> T): T = merge.get().betweenRebuilds(HoldKind.Read, block)
        },
        object : TotalsRequests {
            override fun refresh(trackIds: Collection<String>) = totals.get().refresh(trackIds)

            override fun refreshCopies(sourceId: String, serverIds: Collection<String>) = totals.get().refreshCopies(sourceId, serverIds)

            override fun rebuild() = totals.get().rebuild()
        },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val ids = SongIds(
        user,
        object : ReadHold {
            override suspend fun <T> read(block: suspend () -> T): T = rebuilds.read(block)
        },
    )
    private val stars = Mutex()
    private val plays = Mutex()
    private val ratings = Mutex()

    // Plays still being noted, so a switch can wait for them.
    private val noting = HashSet<Job>()

    // Called after each chunk of the after-sync walks.
    internal var afterChunk: suspend () -> Unit = {}

    // The server a change is for: its client, its account and its source.
    private class Server(val client: SubsonicClient, val account: String, val sourceId: String)

    // Runs after each sync of the server's library: takes in the server's
    // stars, brings likes and stars together, then ratings, notes which sent
    // plays the server now counts, and sends plays that could not be sent
    // before. The server's copies are walked a chunk at a time by server id,
    // so each is seen once, with the library song it is when its chunk is
    // read. FavouriteSync reads the stars taken in here.
    suspend fun afterSync() {
        val server = inUse() ?: return
        store.ready()
        val starred = takeInStarred(server)
        if (!listening.hasServerCopies(server.sourceId)) return
        reconcile(server, starred)
        syncRatings(server)
        store.foldSent(server.account, server.sourceId)
        sendPending(server)
        // The server's counts as copied, and the sent plays folded into them:
        // every song's play count again.
        totals.rebuild()
    }

    // A like changed on the phone. The song's server copies follow; a song
    // only on the phone has none.
    fun likeChanged(trackId: String, liked: Boolean) {
        val server = inUse() ?: return
        scope.launch {
            val ids = serverIds(server, trackId).ifEmpty { return@launch }
            stars.withLock {
                val failure = failureOf { if (liked) server.client.star(ids) else server.client.unstar(ids) }
                if (failure == null) {
                    if (liked) store.addSyncedStars(server.account, ids) else store.removeSyncedStars(server.account, ids)
                }
            }
        }
    }

    // A rating changed on the phone. The song's server copies follow; when
    // the server cannot take it now, the next sync sends it.
    fun ratingChanged(trackId: String) {
        if (isFind(trackId)) return
        val server = inUse() ?: return
        scope.launch {
            val (song, ids) = copiesOf(server, trackId)
            if (ids.isEmpty()) return@launch
            ratings.withLock {
                // The rating as it is now, so quick changes end on the last one.
                val rating = cleanRating(user.rating(song))
                val sent = ids.all { failureOf { server.client.setRating(it, rating) } == null }
                if (sent) {
                    if (rating > 0) store.setSyncedRatings(server.account, ids, rating) else store.clearSyncedRatings(server.account, ids)
                    listening.clearTaps(server.sourceId, ids, rating)
                }
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

    // Takes in the server's stars as they are now, a page at a time, under a
    // new generation, the older ones let go. Answers the generation, or null
    // when the server could not give them whole: then nothing of them stays.
    private suspend fun takeInStarred(server: Server): Long? {
        val generation = listening.nextStarredGeneration()
        listening.dropStarredBefore(generation)
        return try {
            server.client.starredInto { items ->
                listening.insertStarred(items.map { SyncStarredEntity(generation, it.kind.wire, it.id, it.starredAt) })
            }
            listening.insertStarred(listOf(SyncStarredEntity(generation, STARRED_END, "", null)))
            generation
        } catch (e: SubsonicException) {
            listening.dropStarred(generation)
            null
        }
    }

    private suspend fun reconcile(server: Server, generation: Long?) = stars.withLock {
        // Without the server's stars as they are now, nothing can be decided.
        if (generation == null) return@withLock
        var after = ""
        while (true) {
            // A chunk is read and its likes written with no rebuild in
            // between, so each like and unlike lands on the song as it is.
            val (chunk, plan) = rebuilds.between {
                val read = listening.starChunk(server.sourceId, server.account, generation, after)
                val decided = reconcileStars(read.copies, read.liked, read.starred.keys, read.synced)
                applyLikes(read, decided)
                read to decided
            }
            if (chunk.copies.isEmpty()) break
            val starFailed = inBatches(plan.star, server.client::star)
            val unstarFailed = inBatches(plan.unstar, server.client::unstar)
            store.settleStars(server.account, chunk.copies.map { it.serverId }, plan.settled(starFailed, unstarFailed))
            afterChunk()
            if (chunk.copies.size < AFTER_SYNC_CHUNK) break
            after = chunk.copies.last().serverId
        }
    }

    // A like from the server keeps the time it was starred there.
    private suspend fun applyLikes(chunk: StarChunk, plan: StarPlan) {
        val starredAt = chunk.starred.mapValues { (_, at) -> parseServerTime(at) }
        val likedAt = chunk.copies.groupBy({ it.trackId }, { starredAt[it.serverId] }).mapValues { (_, times) -> times.filterNotNull().minOrNull() }
        val now = System.currentTimeMillis()
        user.likeAll(
            catalog.tracksByIds(plan.like.toList()).map { LikedTrackEntity(it.id, it.relinkKey, likedAt[it.id] ?: now) },
        )
        plan.unlike.forEach { user.unlike(it) }
    }

    private suspend fun syncRatings(server: Server) = ratings.withLock {
        var after = ""
        while (true) {
            val chunk = listening.ratingChunk(server.sourceId, server.account, after)
            if (chunk.copies.isEmpty()) break
            syncRatingChunk(server, chunk)
            afterChunk()
            if (chunk.copies.size < AFTER_SYNC_CHUNK) break
            after = chunk.copies.last().serverId
        }
    }

    private suspend fun syncRatingChunk(server: Server, chunk: RatingChunk) {
        val plan = reconcileRatings(chunk.copies, chunk.local, chunk.synced, chunk.tapped)
        val failed = plan.send.filter { (_, send) ->
            send.serverIds.any { failureOf { server.client.setRating(it, send.rating) } != null }
        }.keys
        val now = System.currentTimeMillis()
        // The library shows the rating both sides now have, or will once
        // sent. A song sent only because the server never confirmed what its
        // copies already show is left as it is, so a tap made while this
        // chunk was sending stays.
        val alreadyShown = plan.send.filter { (song, send) ->
            song !in plan.adopt && chunk.copies.filter { it.trackId == song }.all { it.rating == send.rating }
        }.keys
        val shown = (plan.send - alreadyShown).mapValues { it.value.rating } + plan.adopt
        if (shown.isNotEmpty()) {
            rebuilds.between {
                // Each song by the id it has now: a rebuild since the chunk
                // was read may have moved it to another.
                val serverIdOf = chunk.copies.groupBy { it.trackId }.mapValues { (_, copies) -> copies.minOf { it.serverId } }
                val idNow = listening.linksAmong(server.sourceId, shown.keys.mapNotNull { serverIdOf[it] })
                    .associate { it.serverId to it.trackId }
                val relinkKeys = catalog.tracksByIds(idNow.values.distinct()).associate { it.id to it.relinkKey }
                for ((song, rating) in shown) {
                    val id = serverIdOf[song]?.let(idNow::get) ?: continue
                    // The phone's own rating changes only for a song taking
                    // the server's; one it cannot key keeps what it has.
                    val phone = when {
                        song !in plan.adopt -> null
                        rating == 0 -> ""
                        else -> relinkKeys[id]
                    }
                    user.noteRating(id, rating, phone, now, copies = song in plan.send && song !in failed)
                }
            }
        }
        store.settleRatings(server.account, chunk.copies.map { it.serverId }, plan.settled(failed, chunk.synced))
        // Both sides now agree on every song but the failed ones: their taps
        // of the agreed rating are answered.
        if (chunk.tapped.isNotEmpty()) {
            chunk.copies.filter { it.serverId in chunk.tapped && it.trackId !in failed }
                .groupBy { plan.send[it.trackId]?.rating ?: plan.adopt[it.trackId] ?: chunk.local[it.trackId] ?: 0 }
                .forEach { (rating, copies) -> listening.clearTaps(server.sourceId, copies.map { it.serverId }, rating) }
        }
    }

    // Sends the waiting plays oldest first, and stops at the first one the
    // server cannot take right now.
    private suspend fun sendPending(server: Server) = plays.withLock {
        for (play in store.pending(server.account)) {
            val failure = failureOf { server.client.scrobble(play.serverId, play.startedAt, submission = true) }
            if (failure != null && worthRetrying(failure)) break
            store.finishPending(server.account, play, sent = failure == null)
            if (failure == null) totals.refreshCopies(server.sourceId, listOf(play.serverId))
        }
    }

    // The ids that did not go through.
    private suspend fun inBatches(ids: Set<String>, call: suspend (List<String>) -> Unit): Set<String> =
        ids.sorted().chunked(STAR_BATCH).filter { failureOf { call(it) } != null }.flatten().toSet()

    // The server in use now, if there is one.
    private fun inUse(): Server? = signedIn()?.let { Server(it.client, it.id, it.sourceId) }

    // A song's copies on the server, the same one first every time.
    // A song found online is a server song itself.
    private suspend fun serverIds(server: Server, trackId: String): List<String> = copiesOf(server, trackId).second

    // The song a library id names now (SongIds) and its copies on the
    // server, its old ids' by follow included (a save part way may not have
    // moved them yet), read together in one short hold between groups of a
    // save; the server is asked after the hold ends, under this sync's own
    // locks.
    private suspend fun copiesOf(server: Server, trackId: String): Pair<String, List<String>> =
        if (isFind(trackId)) {
            trackId to listOf(trackId.removePrefix(FIND_PREFIX))
        } else {
            ids.write {
                val song = ids.song(trackId)
                song to ids.copyIds(song).flatMap { sources.copies(it) }.filter { it.sourceId == server.sourceId }.map { it.nativeId }.distinct().sorted()
            }
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
