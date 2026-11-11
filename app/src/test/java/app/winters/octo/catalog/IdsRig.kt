package app.winters.octo.catalog

import app.winters.octo.data.SessionState
import app.winters.octo.device.DEVICE
import app.winters.octo.favourites.FavouriteStore
import app.winters.octo.favourites.PinStore
import app.winters.octo.listening.AfterSyncServer
import app.winters.octo.listening.FavouriteSync
import app.winters.octo.listening.FavouriteSyncStore
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.ListeningSync
import app.winters.octo.listening.MemoryPrefs
import app.winters.octo.listening.Rebuilds
import app.winters.octo.offline.RulesRig
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlayFollows
import app.winters.octo.playback.PlayStore
import app.winters.octo.playback.PlaylistSongs
import app.winters.octo.playback.RatingStore
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Every user writer that takes a library id, over one test library and a
// real merge with a save bound of its own, signed in to a fake server that
// notes each call: the stores as the app makes them, sharing one SongIds
// (the offline rules and the listening sync keep their own, which `parkAt`
// reaches too).
internal class IdsRig(val bound: Long) : AutoCloseable {
    val server = AfterSyncServer()
    val source: String = server.session.sourceId
    val db: CatalogDatabase = openTestCatalog()
    val hooks = RunHooks(db)
    val merge: CatalogMerge = db.boundMerge(source, bound, hooks)
    val ids = SongIds(db, merge)

    private val rebuilds = object : Rebuilds {
        override suspend fun <T> between(block: suspend () -> T): T = merge.betweenRebuilds(HoldKind.Page, block)

        override suspend fun <T> read(block: suspend () -> T): T = merge.betweenRebuilds(HoldKind.Read, block)
    }
    val listening = ListeningSync(
        { server.session }, db.sourceDao(), db.userDao(), db.dao(), db.listeningDao(),
        ListeningStore(MemoryPrefs(), MutableStateFlow("home"), db.listeningDao()), rebuilds,
    )

    // What followed each play: ListenBrainz's and the play counts' ids.
    val heard: MutableList<String> = CopyOnWriteArrayList()
    val likes = LikeStore(db.userDao(), db.dao(), listening, db, ids)
    val plays = PlayStore(
        db.userDao(), db.dao(), listening,
        object : PlayFollows {
            override fun nowPlaying(trackId: String) = Unit

            override fun played(trackId: String, startedAt: Long) {
                heard += "played $trackId"
            }

            override fun counted(trackIds: List<String>) {
                heard += trackIds.map { "counted $it" }
            }
        },
        ids,
    )
    val ratings = RatingStore(db.userDao(), listening, ids)
    val favourites = FavouriteStore(
        db.favouritesDao(),
        FavouriteSync({ SessionState.SignedIn(server.session) }, db.favouritesDao(), FavouriteSyncStore(MemoryPrefs()), db.listeningDao()),
        ids,
    )
    val pins = PinStore(db.favouritesDao(), db.dao(), { flowOf(null) }, ids)
    val sets = PlaylistSets(db)
    val edits = PlaylistEdits(db, sets)
    fun playlistSongs(page: Int) = PlaylistSongs(db.dao(), ids, page)
    val offline = RulesRig(db, merge, server = source)

    // Stops every id read of every writer here at `park`, inside its hold.
    fun parkAt(park: SongIdHooks) {
        ids.hooks = park
        offline.rules.ids.hooks = park
        listening.ids.hooks = park
    }

    override fun close() {
        db.close()
        server.close()
    }
}

// A save's group hooks that also note each group's number in its plan (the
// pages of a run share one), and a group's wait for holds.
internal class RunHooks(db: CatalogDatabase) : GroupHooks(db) {
    val groups: MutableList<Long> = CopyOnWriteArrayList()
    val waits = AtomicInteger()
    @Volatile var onWait: () -> Unit = {}

    override suspend fun inGroup(turn: Long, group: Long) {
        groups += group
        super.inGroup(turn, group)
    }

    override fun groupWaits() {
        waits.incrementAndGet()
        onWait()
    }

    // Whether a park after `turn` sat between two pages of one run.
    fun inRun(turn: Long): Boolean = turn >= 1 && groups.size > turn && groups[(turn - 1).toInt()] == groups[turn.toInt()]
}

// An id read stopped until `go`, `times` reads at most; each read is noted
// (kind, id asked, id now), and each one made outside a hold of `merge`'s
// lock.
internal class ParkedReads(private val times: Int, private val merge: CatalogMerge) : SongIdHooks() {
    val reads: MutableList<Triple<String, String, String>> = CopyOnWriteArrayList()
    val unheld: MutableList<String> = CopyOnWriteArrayList()
    val parked = AtomicInteger()
    private val all = CompletableDeferred<Unit>()
    val go = CompletableDeferred<Unit>()

    override suspend fun read(kind: String, id: String, now: String) {
        reads += Triple(kind, id, now)
        if (!merge.holdsMergeLock()) unheld += "$kind $id"
        if (parked.get() >= times) return
        if (parked.incrementAndGet() == times) all.complete(Unit)
        go.await()
    }

    suspend fun allParked() = all.await()
}

// A server's library that the phone's copies then join: every song, album
// and artist the save renames by follow (the server's ids to the phone's).
internal suspend fun IdsRig.serverLibraryThenPhone(songs: Int): SourceCatalog {
    db.put(library(source, songs))
    db.boundMerge(source, bound).rebuild()
    return library(DEVICE, songs)
}

// The id a song of the test library has in the server's copy, and the
// phone's: `n` is the song's number.
internal fun IdsRig.serverSong(n: Int) = "$source:s$n"

internal fun phoneSong(n: Int) = "$DEVICE:s$n"
