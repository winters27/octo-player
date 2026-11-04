package app.winters.octo.server

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.catalog.AccountSyncEntity
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.StageAborted
import app.winters.octo.listening.FavouriteSync
import app.winters.octo.listening.ListeningSync
import app.winters.octo.catalog.SourceDao
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.StoredServer
import app.winters.octo.data.userMessage
import app.winters.octo.discovery.Downloads
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playlists.PlaylistSync
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// When a server's library was last copied, and how much came.
data class LastSync(val sourceId: String, val at: Long, val songs: Int, val albums: Int)

private val Context.syncData by preferencesDataStore("server_sync")

// Runs a step of a server change, noting a database error (a full disk,
// say) instead of passing it on, so the change still finishes and the next
// one tries again. Answers the step's result, or null when it failed.
internal suspend fun <T> orNoted(step: suspend () -> T): T? =
    try {
        step()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Octo", "library switch failed: ${e.javaClass.simpleName}")
        null
    }

// Said while the library still holds another server's music because its
// switch could not be merged.
internal const val SWITCH_PROBLEM = "Couldn't finish switching the library. Octo tries again each time you open it."

// Makes the library the server in use's (`wanted`, "" for none) when the
// last merge was for another, and says so in `problem`: the switch's words
// show while it fails and go once the library is the wanted one's,
// whichever try got it there. Any other problem stays. Which server the
// library holds (`mergedFor`) is what the merge itself recorded with its
// result, so it is never out of step with the library. One try at a time.
internal class LibrarySwitch(
    private val mergedFor: suspend () -> String?,
    private val merge: suspend () -> Unit,
    private val problem: MutableStateFlow<String?>,
    private val wanted: () -> String,
) {
    private val lock = Mutex()

    // Answers whether the library is `want`'s now.
    suspend fun finish(want: String): Boolean = lock.withLock {
        val done = orNoted {
            if (mergedFor() != want) merge()
            mergedFor() == want
        } == true
        if (done) {
            problem.compareAndSet(SWITCH_PROBLEM, null)
        } else if (wanted() == want) {
            problem.value = SWITCH_PROBLEM
        }
        done
    }
}

// Whether a server's arrival starts a copy: unless its copy is fresh and
// its library in place, since sync() finishes a failed switch first.
internal fun copyOnArrival(needed: CopyNeed, switched: Boolean): Boolean = needed != CopyNeed.Fresh || !switched

// A server's arrival (`account` null when signed out): the library is
// switched to it and `ready` told whose it is; then a copy starts unless its
// copy is fresh and its switch worked, and a fresh one gets a look instead.
internal suspend fun arrive(
    account: String?,
    sourceId: String?,
    finish: suspend (String) -> Boolean,
    ready: (String) -> Unit,
    need: suspend () -> CopyNeed,
    copy: () -> Unit,
    look: () -> Unit,
) {
    val switched = finish(sourceId.orEmpty())
    ready(account ?: PHONE_LIBRARY)
    if (account == null) return
    if (copyOnArrival(need(), switched)) copy() else look()
}

// A freshen's first step: nothing until arrive() put a library in place;
// then a switch whose merge failed is merged again, signed out too. Answers
// whether the freshen goes on with the server in use.
internal suspend fun freshenSwitch(ready: String?, account: String?, sourceId: String?, finish: suspend (String) -> Boolean): Boolean {
    if (ready != (account ?: PHONE_LIBRARY)) return false
    return finish(sourceId.orEmpty()) && account != null
}

// Before a copy: a switch to its server whose merge failed is finished
// first, and while it fails nothing is copied (null). Otherwise answers
// whether to copy the whole library (true) or only the steps owed after a
// copy swapped in (false).
internal suspend fun copyPlan(sourceId: String, copyAllAnyway: Boolean, finish: suspend (String) -> Boolean, need: suspend () -> CopyNeed): Boolean? {
    if (!finish(sourceId)) return null
    return copyAllAnyway || need() != CopyNeed.AfterSync
}

// The one copy running at a time. `run` copies; its flag copies the whole
// library even when only the steps after a copy are owed.
internal class CopyJobs(private val scope: CoroutineScope, private val run: suspend (copyAll: Boolean) -> Unit) {
    private var job: Job? = null

    // The copy running now, or the last one.
    fun current(): Job? = synchronized(this) { job }

    fun isActive(): Boolean = synchronized(this) { job?.isActive == true }

    // How many stops are waiting for their copy to end.
    private var stopping = 0

    // Starts a copy unless one is already running or being stopped (the
    // one stopping it decides what runs next). One asked while a copy
    // stopped earlier still cleans up runs once that is done.
    fun start() {
        synchronized(this) {
            if (stopping > 0) return
            val old = job
            if (old?.isActive == true) return
            job = after(old, copyAll = false)
        }
    }

    // Stops the copy running and waits until it has cleaned up. No copy
    // starts meanwhile, and one started afresh meanwhile is stopped too, so
    // none runs once this returns until the next start.
    suspend fun stop() {
        synchronized(this) { stopping++ }
        try {
            while (true) {
                val running = synchronized(this) { job } ?: return
                running.cancelAndJoin()
                if (synchronized(this) { job } === running) return
            }
        } finally {
            synchronized(this) { stopping-- }
        }
    }

    // Stops the copy running and starts a whole one in its place, in one
    // step, so no other start slips in between.
    fun restart() {
        synchronized(this) {
            val old = job
            old?.cancel()
            job = after(old, copyAll = true)
        }
    }

    // A copy that first waits out `old` whatever stops it, and always
    // starts, so stopping it (current()) also waits until `old` has cleaned
    // up, and two copies never overlap.
    @OptIn(DelicateCoroutinesApi::class)
    private fun after(old: Job?, copyAll: Boolean): Job = scope.launch(start = CoroutineStart.ATOMIC) {
        withContext(NonCancellable) { old?.join() }
        ensureActive()
        run(copyAll)
    }
}

// A copy older than this is made again when the app starts or comes to the
// front.
internal const val STALE_MS =6 * 60 * 60 * 1000L

// Raised whenever a copy learns to keep something new from the server, so
// the next start copies the library again whatever its age. Copies made
// before this was kept count as version 1; version 2 keeps genres, credits,
// original years, MusicBrainz ids and the other details; version 3 keeps
// each album's release types, for the artist page's shelves; version 4
// leaves out songs the server marks as outside the library, which a
// download that arrived could bring in with its album; version 5 gives
// covers a fallback for when the server answers with its stand-in picture,
// and marks only songs the server calls explicit, not every song of an
// album holding one; version 6 keeps which songs are in a family member's
// own library.
internal const val ROWS_VERSION = 6

// Whose library is in place: a kept server's id, or this for the phone's
// music alone.
const val PHONE_LIBRARY = "phone"

// The source a kept server's music is kept under.
val StoredServer.sourceId: String? get() = serverUrl.toHttpUrlOrNull()?.let(::serverSourceId)

// Keeps a copy of each kept server's library beside the phone's music:
// copied after signing in, at app start or on coming to the front when the
// last copy is old, and when asked. In between, while the app is in front,
// music the server took in since is brought in album by album (look), and
// a search brings in what it finds that the copy lacks (takeInArriving).
// Only the server in use joins the library (CatalogMerge); the
// others' copies wait for a switch, which then needs no new copy unless it
// is old. Removing a server takes its copy away. Two accounts on one
// address share one copy, made again when the other one is in use.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ServerSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val merge: CatalogMerge,
    private val copy: StagedCopy,
    private val copies: CopyDao,
    private val listening: ListeningSync,
    private val favourites: FavouriteSync,
    private val downloads: Downloads,
    private val playlists: PlaylistSync,
    private val offline: OfflineDownloads,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dropLock = Mutex()
    private val jobs = CopyJobs(scope) { copyAll -> sync(copyAll) }
    private var started = false

    // One take-in or look at a time, so two never put in the same rows.
    private val takeInLock = Mutex()

    // When each kept server was last looked at for new music, by its id,
    // on the clock that counts time since the phone started.
    private val lookedAt = ConcurrentHashMap<String, Long>()

    // Whether any of the app's screens is in front, and the looks made
    // while one is.
    @Volatile private var foreground = false
    private var looks: Job? = null

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    // Why the last copy failed, in words; null once one works.
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    private val switch = LibrarySwitch(merge::mergedFor, { merge.rebuild() }, _problem) { signedIn()?.sourceId.orEmpty() }

    // Whose library the screens show now: the id of the server in use once
    // its music has joined, or PHONE_LIBRARY. Null until the first is in place.
    private val _ready = MutableStateFlow<String?>(null)
    val ready: StateFlow<String?> = _ready

    // The last copy of the server in use whose steps afterwards were all
    // done. It changes only when a copy's steps are done.
    val last: Flow<LastSync?> = sessions.state
        .map { (it as? SessionState.SignedIn)?.session?.id }
        .distinctUntilChanged()
        .flatMapLatest { account -> if (account == null) flowOf(null) else copies.accountSyncFlow(account) }
        .map { row -> row?.takeIf { it.syncedAt > 0 }?.let { LastSync(it.sourceId, it.syncedAt, it.songs, it.albums) } }
        .distinctUntilChanged()

    fun start() {
        if (started) return
        started = true
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ForegroundCallbacks())
        scope.launch {
            // The kept servers are known once the saved sign-in is read.
            sessions.state.first { it !is SessionState.Loading }
            takeInOneServersRecord()
            moveCopyRecords()
            sessions.state
                .filter { it !is SessionState.Loading }
                .map { (it as? SessionState.SignedIn)?.session }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.sourceId == b?.sourceId }
                .collect { session -> arrived(session) }
        }
    }

    // Another server (or none) is in use: a copy of the last one stops, the
    // library becomes the new one's, and its copy is made again when old.
    // A database error (a full disk, say) is noted and said, the change
    // still finishes, and this keeps listening for changes; the library mark
    // is written only once a merge worked, so a failed switch is merged
    // again by the copy this starts and on every freshen.
    private suspend fun arrived(session: Session?) {
        jobs.stop()
        _problem.value = null
        orNoted { dropUnkept() }
        arrive(
            session?.id,
            session?.sourceId,
            switch::finish,
            ready = { _ready.value = it },
            need = { session?.let { orNoted { need(it) } } ?: CopyNeed.Copy },
            copy = ::syncNow,
            look = { if (foreground && session != null) scope.launch { quietly { look(session) } } },
        )
    }

    // What the server's copy needs (copyNeed): made again when never made,
    // made by an older version, old, or made for another account on the
    // same address; only its steps afterwards when those were cut off.
    private suspend fun need(session: Session): CopyNeed =
        copyNeed(copies.accountSync(session.id), copies.copyOf(session.sourceId), session.id, session.sourceId, System.currentTimeMillis())

    // Starts a copy unless one is already running.
    fun syncNow() = jobs.start()

    // Starts a copy unless one is already running, and waits for it to end.
    // Answers why it failed, in words, or null when it worked.
    suspend fun syncNowAndWait(): String? {
        syncNow()
        jobs.current()?.join()
        return _problem.value
    }

    // Copies the library afresh, stopping a copy already running: for when
    // what the copy should hold has changed, like the chosen music folder.
    fun syncAgain() = jobs.restart()

    // Signs out of the server in use. Its music leaves the library; its copy
    // stays for signing in again. Nothing on the server changes.
    fun disconnect() {
        scope.launch {
            jobs.stop()
            sessions.signOut()
        }
    }

    // Takes a kept server off the list, and its copy of the library with it
    // (unless another kept account shares the address). Nothing on the
    // server changes.
    suspend fun remove(id: String): StoredServer? {
        if (sessions.state.value.let { it is SessionState.SignedIn && it.session.id == id }) {
            jobs.stop()
        }
        val gone = sessions.remove(id) ?: return null
        dropUnkept()
        copies.forgetAccount(id)
        context.syncData.edit { p -> listOf(syncedAtKey(id), songsKey(id), albumsKey(id), rowsKey(id), sourceKey(id), scanKey(id)).forEach { p.remove(it) } }
        lookedAt.remove(id)
        return gone
    }

    // Puts one song from the server into the library at once, with the rest
    // of its album as the server has it now, ahead of the next full copy:
    // for a download that just finished. Answers the library song it
    // became, or null when the server could not give it.
    suspend fun takeInSong(serverId: String): String? {
        val session = signedIn() ?: return null
        val song = try {
            session.client.song(serverId)
        } catch (e: SubsonicException) {
            return null
        }
        takeInLock.withLock { takeIn(session, listOf(song), listOfNotNull(song.albumId?.takeIf(String::isNotEmpty))) }
        return sources.libraryLinks(session.sourceId, listOf(serverId)).firstOrNull()?.trackId
    }

    // Takes in music a search found on the server that the library does not
    // have yet (see Arriving), with the albums it is on, ahead of the next
    // full copy. It goes on to the end even when the one asking stops
    // waiting, so the next search finds it in place. A copy already running
    // brings it all, so that is waited for instead. Answers whether any of
    // it is in the library now.
    suspend fun takeInArriving(arriving: Arriving): Boolean {
        if (arriving.isEmpty) return false
        val session = signedIn() ?: return false
        return scope.async {
            takeInLock.withLock {
                jobs.current()?.join()
                if (signedIn()?.sourceId != session.sourceId) return@withLock false
                takeIn(session, arriving.songs, arriving.albumIds())
                val songIds = arriving.songs.map { it.id }
                val albumIds = arriving.albums.map { it.id }
                (songIds.isNotEmpty() && sources.libraryLinks(session.sourceId, songIds).isNotEmpty()) ||
                    (albumIds.isNotEmpty() && sources.knownAlbums(session.sourceId, albumIds).isNotEmpty())
            }
        }.await()
    }

    // Takes in the server's songs a search found, as takeInArriving does,
    // without waiting on a full copy of the library: when one is running, or
    // starts before the take-in ends, this answers null at once and the
    // take-in carries on behind it. A copy never takes takeInLock, so the
    // race is with the copy's start.
    suspend fun takeInIfFree(arriving: Arriving): Boolean? {
        if (arriving.isEmpty) return false
        val work = scope.async { takeInArriving(arriving) }
        val copying = jobs.isActive()
        return unlessCopying(work, copying, _syncing)
    }

    // Puts server songs and albums into the library ahead of the next full
    // copy: each album in `albumIds` read as the server has it now, with
    // all its songs, and each of `songs` too (alone when its album could
    // not be read). `listed` gives an album's details as the server's album
    // lists give them, where known, so the next look sees the same counts.
    // Only what the library lacks goes in, then the library is merged
    // again; the next full copy puts everything right. Answers whether
    // anything went in. Callers hold takeInLock.
    private suspend fun takeIn(
        session: Session,
        songs: List<Song>,
        albumIds: List<String>,
        listed: Map<String, Album> = emptyMap(),
    ): Boolean {
        val client = session.client
        val sourceId = session.sourceId
        val read = coroutineScope {
            albumIds.distinct().map { id ->
                async {
                    try {
                        client.album(id)
                    } catch (e: SubsonicException) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val all = (read.flatMap { it.song } + songs).filterNot { it.isExternal }.distinctBy { it.id }
        if (all.isEmpty()) return false
        val rows = buildServerCatalog(sourceId, Library(all, read.map { listed[it.id] ?: it.asListed() }, emptyList()))
        // What the library lacks is worked out again under the merge's gate
        // (StagedCopy.takeIn), which is let go before the merge.
        if (!copy.takeIn(sourceId, rows, readAlbums = read.mapTo(HashSet()) { it.id })) return false
        merge.rebuild()
        return true
    }

    // Brings the server in use up to date while the app is in front: copies
    // it whole when its copy is old (the same rule as at start), and
    // otherwise looks for new music. Either at most once a
    // NEW_MUSIC_LOOK_MS, so a copy that keeps failing is not tried on and on.
    private suspend fun freshen() {
        val session = signedIn()
        if (!freshenSwitch(_ready.value, session?.id, session?.sourceId, switch::finish) || session == null) return
        if (need(session) == CopyNeed.Fresh) return look(session)
        val now = SystemClock.elapsedRealtime()
        if (!newMusicLookDue(lookedAt[session.id], now)) return
        lookedAt[session.id] = now
        syncNow()
    }

    // One look at the server for music the copy lacks (see
    // lookForNewMusic): new albums, and albums with songs added, are taken
    // in; too many at once copies the whole library instead. Never while a
    // copy runs, and never twice within NEW_MUSIC_LOOK_MS.
    private suspend fun look(session: Session) {
        if (jobs.isActive()) return
        val now = SystemClock.elapsedRealtime()
        if (!newMusicLookDue(lookedAt[session.id], now)) return
        lookedAt[session.id] = now
        takeInLock.withLock {
            if (signedIn()?.id != session.id) return
            val seen = copies.accountSync(session.id)?.lastScan
            val look = try {
                lookForNewMusic(session.client, seen) { ids ->
                    ids.chunked(900).flatMap { sources.albumSongCounts(session.sourceId, it) }.associate { it.serverId to it.songCount }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "new music look failed: ${e.javaClass.simpleName}")
                return
            }
            when (val found = look.found) {
                NewMusic.None -> Unit
                is NewMusic.TakeIn -> {
                    val ids = found.albums.map { it.id }
                    takeIn(session, emptyList(), ids, found.albums.associateBy { it.id })
                    Log.i("Octo", "new music look: took in ${found.albums.size} albums")
                    // An album the server would not give is left for the
                    // next look, which then reads on whatever the scan says.
                    val kept = sources.albumSongCounts(session.sourceId, ids).associate { it.serverId to it.songCount }
                    if (found.albums.any { kept[it.id] != it.songCount }) return
                }
                NewMusic.CopyAll -> {
                    // The copy notes the server's scan itself.
                    syncNow()
                    return
                }
            }
            val scan = look.scan
            if (scan != null && scan != seen) copies.setLastScan(session.id, scan)
        }
    }

    private fun signedIn(): Session? = (sessions.state.value as? SessionState.SignedIn)?.session

    // A look that goes wrong is only noted; the next one tries again.
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "new music look failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun sync(copyAllAnyway: Boolean) {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return
        _syncing.value = true
        try {
            val started = SystemClock.elapsedRealtime()
            // While a failed switch still fails, its words show and nothing is copied.
            val copyAll = copyPlan(session.sourceId, copyAllAnyway, switch::finish) { need(session) } ?: return
            val done = CopyRun(copy, copies, afterSyncSteps()).run(session.id, session.sourceId, session.client, copyAll)
            lookedAt[session.id] = SystemClock.elapsedRealtime()
            _problem.value = null
            // Counts only, like the phone scan's line.
            Log.i(
                "Octo",
                "server sync: ${if (copyAll) "copy" else "steps after a copy"}, ${done.songs} tracks, ${done.albums} albums, " +
                    "${SystemClock.elapsedRealtime() - started} ms",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: StageAborted) {
            _problem.value = e.reason
            Log.w("Octo", "server sync failed: the merge could not swap the copy in")
        } catch (e: Exception) {
            _problem.value = e.userMessage()
            Log.w("Octo", "server sync failed: ${e.javaClass.simpleName}")
        } finally {
            _syncing.value = false
        }
    }

    // What follows every copy, in order: likes and stars brought together
    // and plays still waiting sent; the same for favorite albums and artists;
    // songs downloaded since the last copy join the library as liked; the
    // server's playlists and the phone's copies brought together; downloads
    // follow their songs, and songs kept downloaded that just arrived start.
    private fun afterSyncSteps(): List<suspend () -> Unit> = listOf(
        { listening.afterSync() },
        { favourites.afterSync() },
        { downloads.afterSync() },
        { playlists.afterSync() },
        { offline.afterSync() },
    )

    // Removes the music of every server no longer kept. A signed-out server
    // is still kept, so signing in again needs no new copy.
    private suspend fun dropUnkept() = dropLock.withLock {
        val kept = sessions.servers.value.servers.mapNotNullTo(HashSet()) { it.sourceId }
        val gone = sources.sourceIds().filter { isServerSource(it) && it !in kept }
        // Their songs are no library songs already; the rows just go, never
        // while a merge reads the sources.
        if (gone.isNotEmpty()) merge.sourceWrite { gone.forEach { sources.deleteSource(it) } }
        if (gone.isNotEmpty()) context.syncData.edit { p -> gone.forEach { p.remove(copyOfKey(it)) } }
        // Playlists kept with a server that is gone stay, only on the phone.
        playlists.keepOnly(kept)
    }

    // An older version kept one record of its one server's copy. It belongs
    // to the kept account on that address, the one in use first.
    private suspend fun takeInOneServersRecord() {
        val kept = sessions.servers.value
        context.syncData.edit { p ->
            // Older versions kept which server the library was merged for
            // here; the merge keeps that itself now.
            p.remove(OLD_LIBRARY)
            val source = p[SOURCE_ID] ?: return@edit
            val owner = (listOfNotNull(kept.inUse) + kept.servers).firstOrNull { it.sourceId == source }
            if (owner != null && p[syncedAtKey(owner.id)] == null) {
                p[sourceKey(owner.id)] = source
                p[SYNCED_AT]?.let { p[syncedAtKey(owner.id)] = it }
                p[songsKey(owner.id)] = p[SONGS] ?: 0
                p[albumsKey(owner.id)] = p[ALBUMS] ?: 0
                p[ROWS]?.let { p[rowsKey(owner.id)] = it }
                p[copyOfKey(source)] = owner.id
            }
            p.forgetOneServersRecord()
        }
    }

    private fun MutablePreferences.forgetOneServersRecord() {
        listOf<Preferences.Key<*>>(SOURCE_ID, SYNCED_AT, SONGS, ALBUMS, ROWS).forEach { remove(it) }
    }

    // An older version kept each account's last copy, and which account each
    // address's rows are, as keys here. They move into the database once,
    // and the keys go once the rows are in.
    private suspend fun moveCopyRecords() {
        val p = context.syncData.data.first()
        val names = p.asMap().keys.map { it.name }
        val accountPrefix = syncedAtKey("").name
        val ownerPrefix = copyOfKey("").name
        val accounts = names.filter { it.startsWith(accountPrefix) }.map { it.removePrefix(accountPrefix) }
        val addresses = names.filter { it.startsWith(ownerPrefix) }.map { it.removePrefix(ownerPrefix) }
        if (accounts.isEmpty() && addresses.isEmpty()) return
        val rows = accounts.mapNotNull { id ->
            AccountSyncEntity(
                account = id,
                sourceId = p[sourceKey(id)] ?: return@mapNotNull null,
                syncedAt = p[syncedAtKey(id)] ?: return@mapNotNull null,
                songs = p[songsKey(id)] ?: 0,
                albums = p[albumsKey(id)] ?: 0,
                rowsVersion = p[rowsKey(id)] ?: 0,
                lastScan = p[scanKey(id)],
                // An older version wrote these only once every step after a copy was done.
                afterSyncDone = true,
            )
        }
        val owners = addresses.mapNotNull { source -> p[copyOfKey(source)]?.let { source to it } }
        copies.moveRecords(rows, owners)
        context.syncData.edit { e ->
            accounts.forEach { id -> listOf(syncedAtKey(id), songsKey(id), albumsKey(id), rowsKey(id), sourceKey(id), scanKey(id)).forEach { e.remove(it) } }
            addresses.forEach { e.remove(copyOfKey(it)) }
        }
    }

    // The app came to the front: the server in use is brought up to date
    // now and every NEW_MUSIC_LOOK_MS while it stays there. Nothing is
    // asked while no screen is showing.
    private fun cameToFront() {
        foreground = true
        synchronized(this) {
            looks?.cancel()
            looks = scope.launch {
                while (isActive) {
                    quietly { freshen() }
                    delay(NEW_MUSIC_LOOK_MS)
                }
            }
        }
    }

    private fun wentAway() {
        foreground = false
        synchronized(this) {
            looks?.cancel()
            looks = null
        }
    }

    private inner class ForegroundCallbacks : Application.ActivityLifecycleCallbacks {
        private val started = AtomicInteger(0)

        override fun onActivityStarted(activity: Activity) {
            if (started.incrementAndGet() == 1) cameToFront()
        }

        override fun onActivityStopped(activity: Activity) {
            if (started.decrementAndGet() <= 0) {
                started.set(0)
                wentAway()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        // Each kept server's last copy, by its id.
        fun sourceKey(id: String) = stringPreferencesKey("source_id@$id")
        fun syncedAtKey(id: String) = longPreferencesKey("synced_at@$id")
        fun songsKey(id: String) = intPreferencesKey("songs@$id")
        fun albumsKey(id: String) = intPreferencesKey("albums@$id")
        fun rowsKey(id: String) = intPreferencesKey("rows_version@$id")

        // When the server last read its folders, as it said at the last copy
        // or look.
        fun scanKey(id: String) = stringPreferencesKey("last_scan@$id")

        // Which account's copy an address's rows are.
        fun copyOfKey(sourceId: String) = stringPreferencesKey("copy_of@$sourceId")

        val OLD_LIBRARY = stringPreferencesKey("library_server")

        // Where an older version kept its one server's record.
        val SOURCE_ID = stringPreferencesKey("source_id")
        val SYNCED_AT = longPreferencesKey("synced_at")
        val SONGS = intPreferencesKey("songs")
        val ALBUMS = intPreferencesKey("albums")
        val ROWS = intPreferencesKey("rows_version")
    }
}
