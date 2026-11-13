package app.winters.octo.listening

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.catalog.titleLookup
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import app.winters.octo.catalog.today.byLatestPlay as todayByLatestPlay
import app.winters.octo.catalog.today.byPlayCount as todayByPlayCount
import app.winters.octo.listening.today.mostPlayed as todayMostPlayed
import app.winters.octo.listening.today.recentHistory as todayRecentHistory
import app.winters.octo.listening.today.withServerAlbumPlays as todayWithServerAlbumPlays
import app.winters.octo.listening.today.withServerPlays as todayWithServerPlays
import app.winters.octo.ui.home.today.homeRediscovery as todayRediscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

// A fixed now, for plays to fall before and shelves to count from.
internal const val NOW = 1_790_000_000_000L
internal const val DAY = 24L * 60 * 60 * 1000

internal const val HOME = "server:h"
internal const val OTHER = "server:o"

// The totals rigs made while a timed body runs, which it stops as it ends;
// null outside one.
private var rigsMade: MutableList<TotalsRig>? = null
private val rigsLock = Any()

// A test's body, ended if it takes longer than `seconds`. The totals rigs
// made in it stop sharing their shelves as it ends, and a read the shelves
// were running ends before it returns.
internal fun timed(seconds: Long = 10, block: suspend () -> Unit) = runBlocking {
    val made = ArrayList<TotalsRig>()
    val outer = synchronized(rigsLock) { rigsMade.also { rigsMade = made } }
    try {
        withTimeout(seconds * 1000) { block() }
    } finally {
        val rigs = synchronized(rigsLock) {
            rigsMade = outer
            made.toList()
        }
        withContext(NonCancellable) { withTimeout(seconds * 1000) { rigs.forEach { it.stop() } } }
    }
}

// The totals and their readers over a test library, with the account in
// use, the free space and the page size given. The shelves stop being
// shared as soon as no one looks, and a read they were running then ends
// before the test closes its database: a timed body stops the rigs made
// in it, and a rig's other owner stops it (stop).
internal class TotalsRig(
    val db: CatalogDatabase,
    val merge: CatalogMerge,
    account: String? = "home",
    space: Long = Long.MAX_VALUE,
    val page: Int = PLAY_PAGE,
    val now: Long = NOW,
    keepMillis: Long = 0,
) {
    val account = MutableStateFlow(account)
    @Volatile var space = space
    val totals = PlayTotals(db, merge, { this.space }, this.account, {}, page)
    val store = ListeningStore(MemoryPrefs(), this.account, db.listeningDao())

    // Where the history shares its shelves: the rig's own, so stop ends it.
    private val sharing = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val history = PlayHistory(store, totals, merge, db, page, clock = { now }, keepMillis = keepMillis, scope = sharing)
    val dao = db.playTotalsDao()

    init {
        synchronized(rigsLock) { rigsMade?.add(this) }
    }

    // Stops sharing the shelves and waits for a read they were running to
    // end; the shelves give nothing after it.
    suspend fun stop() = sharing.coroutineContext.job.cancelAndJoin()

    // Starts the totals and waits for their first rebuild.
    suspend fun started(): TotalsRig = apply {
        totals.start()
        totals.builtThrough.first { it >= 0 }
    }

    // Waits until the totals are built through the swap stamp now.
    suspend fun built() = totals.awaitBuilt()

    suspend fun shelves(): PlayShelves = value(history.shelves.first())
}

internal fun <T> value(answer: TotalsAnswer<T>?): T = when (answer) {
    is TotalsAnswer.Value -> answer.value
    is TotalsAnswer.Problem -> throw AssertionError("the totals failed: ${answer.words}")
    null -> throw AssertionError("no answer")
}

internal fun CatalogDatabase.longOf(sql: String, vararg args: Any?): Long =
    query(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

// A phone library of `songs` songs ("device:s<n>", ten an album) merged
// in by a real merge, which the test then drives.
internal suspend fun CatalogDatabase.mergedLibrary(songs: Int, hooks: MergeHooks = MergeHooks()): CatalogMerge {
    put(library(DEVICE, songs))
    return testMerge(null, hooks).also { it.rebuild() }
}

// A play on the phone of a library song, found again by `relinkKey`.
internal fun CatalogDatabase.playOn(trackId: String, at: Long, relinkKey: String = "") =
    exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, ?, ?, 1000, 1000)", trackId, relinkKey, at)

// A swap that changes the library: the phone gains a song, so the swap
// count moves.
internal suspend fun CatalogDatabase.growAndSwap(merge: CatalogMerge, songs: Int) {
    put(library(DEVICE, songs))
    merge.rebuild()
}

// Stops at one point until the test lets it go; later passes go by.
internal class Gate {
    val reached = CompletableDeferred<Unit>()
    private val go = CompletableDeferred<Unit>()

    suspend fun hit() {
        if (reached.complete(Unit)) go.await()
    }

    fun open() {
        go.complete(Unit)
    }
}

// How a generated library names its songs, albums and artists, and keys
// its songs for sorting. Short by default; `server` names them as a real
// server's library does, with its address and 32-digit ids.
internal open class PlayIds {
    open fun track(i: Int) = "t%05d".format(i)

    open fun album(i: Int) = "al$i"

    open fun artist(i: Int) = "ar$i"

    open fun sortKey(i: Int) = "song %05d".format(i)

    companion object {
        val server = object : PlayIds() {
            private val at = "server:music.home-server.example.net:4533:"

            override fun track(i: Int) = at + "%032x".format(i)

            override fun album(i: Int) = at + "a%031x".format(i)

            override fun artist(i: Int) = at + "b%031x".format(i)

            override fun sortKey(i: Int) = "untitled song number %05d".format(i)
        }
    }
}

// A library with plays to count, written straight into its tables:
// `songs` library songs over `albums` albums, the last two of them missing
// from the album table; phone plays on about two songs in three, at
// distinct times over 400 days, every ninth album's only long ago; server
// copies with a record on most songs (some without a time or a count, a
// second copy on one song in fifty, a copy on a second server on one in
// thirty, some at the phone's own play time); sent plays the server holds
// and newer ones it does not; copies of songs the library lacks; and
// songs played equally often at the same time.
internal fun CatalogDatabase.fillPlays(seed: Long, songs: Int, albums: Int, account: String = "home", ids: PlayIds = PlayIds()) {
    val r = Random(seed)
    val sql = openHelper.writableDatabase
    sql.beginTransaction()
    try {
        val track = sql.compileStatement(
            "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, " +
                "lookupKey, looseKey) VALUES (?, 'device', ?, ?, ?, ?, 'Artist', ?, 'Album', ?, ?, 0, '', ?, ?)",
        )
        val album = sql.compileStatement(
            "INSERT INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) " +
                "VALUES (?, 'device', ?, ?, ?, ?, 'Artist', 'ar', ?, 0, ?)",
        )
        val play = sql.compileStatement("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, '', ?, 1000, 1000)")
        val copy = sql.compileStatement(
            "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, " +
                "albumOrder, relinkKey, genre, mergedId, playCount, lastPlayedAt) VALUES (?, ?, ?, 'Song', 'song', 'song', 'Artist', 'ar', 'Album', 'al', 0, 0, 0, '', '', ?, ?, ?)",
        )
        val sent = sql.compileStatement("INSERT OR IGNORE INTO sent_play (account, serverId, folded) VALUES (?, ?, ?)")
        val recent = sql.compileStatement("INSERT OR IGNORE INTO sent_recent (account, serverId, startedAt) VALUES (?, ?, ?)")
        fun put(st: androidx.sqlite.db.SupportSQLiteStatement, vararg args: Any?) {
            st.clearBindings()
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> st.bindNull(i + 1)
                    is String -> st.bindString(i + 1, a)
                    is Number -> st.bindLong(i + 1, a.toLong())
                }
            }
            st.executeInsert()
        }
        val perAlbum = IntArray(albums)
        var counter = 0L
        for (i in 0 until songs) {
            val id = ids.track(i)
            val albumIndex = i % albums
            perAlbum[albumIndex]++
            val keys = titleLookup("Song $i", "Artist")
            put(track, id, id, "Song $i", "song $i", ids.sortKey(i), ids.artist(i % 13), ids.album(albumIndex), 180_000L + (i % 50) * 1_000, keys.lookupKey, keys.looseKey)
            var phoneLast: Long? = null
            if (i % 97 == 0) {
                // Played as often as each other, last at the same moment.
                put(play, id, NOW - DAY)
                put(play, id, NOW - 2 * DAY - ++counter)
                put(play, id, NOW - 3 * DAY - ++counter)
                phoneLast = NOW - DAY
            } else if (r.nextInt(3) != 0) {
                repeat(1 + r.nextInt(4)) {
                    val days = if (albumIndex % 9 == 0) 200 + r.nextInt(200) else r.nextInt(400)
                    val at = NOW - days * DAY - ++counter
                    put(play, id, at)
                    phoneLast = maxOf(phoneLast ?: at, at)
                }
            }
            if (r.nextInt(7) != 0) {
                val count: Int? = when (r.nextInt(6)) {
                    0 -> null
                    1 -> 0
                    else -> r.nextInt(8)
                }
                val last: Long? = when {
                    r.nextInt(5) == 0 -> null
                    phoneLast != null && r.nextInt(3) == 0 -> phoneLast!! + 300
                    else -> NOW - r.nextInt(400) * DAY - r.nextInt(1_000_000)
                }
                put(copy, "$HOME:h-$i", HOME, "h-$i", id, count, last)
                if (i % 4 == 0) {
                    put(sent, account, "h-$i", r.nextInt(3))
                    if (last != null) {
                        put(recent, account, "h-$i", last - 5_000)
                        put(recent, account, "h-$i", last + 60_000)
                    }
                }
                if (i % 50 == 0) put(copy, "$HOME:h2-$i", HOME, "h2-$i", id, 1 + r.nextInt(3), NOW - r.nextInt(30) * DAY)
                if (i % 30 == 0) put(copy, "$OTHER:o-$i", OTHER, "o-$i", id, r.nextInt(4), NOW - r.nextInt(300) * DAY)
            }
            if (i % 40 == 0) put(copy, "$HOME:g-$i", HOME, "g-$i", "gone-$i", 5, NOW - DAY)
        }
        for (a in 0 until albums - 2) {
            val songCount = perAlbum[a] + if (a % 5 == 0) 1 else 0
            put(album, ids.album(a), ids.album(a), "Album $a", "album $a", "album %05d".format(a), songCount, if (a % 7 == 0) 0L else a * 1_000L)
        }
        sql.setTransactionSuccessful()
    } finally {
        sql.endTransaction()
    }
}

// Today's answers for the library as it is now, from today's whole reads
// and the frozen copies of today's functions.
internal class TodayPlays(private val db: CatalogDatabase, private val account: String?) {
    private val user = db.userDao()

    private suspend fun sent(): Map<String, SentPlays> = if (account == null) emptyMap() else sentOf(db.listeningDao().sentRows(account).first())

    // The server copies with a record, in the order they were written.
    private suspend fun server(): List<ServerPlayedTrack> {
        val rows = HashMap<String, Long>()
        db.query("SELECT nativeId, rowid FROM source_track", null).use { c -> while (c.moveToNext()) rows[c.getString(0)] = c.getLong(1) }
        return user.serverPlayedTracks().first().sortedBy { rows.getValue(it.serverId) }
    }

    suspend fun tracks(): List<PlayedTrack> = todayWithServerPlays(user.playedTracks().first(), server(), sent())

    suspend fun shelves(now: Long): PlayShelves {
        val tracks = tracks()
        return PlayShelves(
            mostPlayed = todayByPlayCount(tracks, PLAY_SHELF),
            recentlyPlayed = todayByLatestPlay(todayWithServerAlbumPlays(user.playedAlbums().first(), user.serverPlayedAlbums().first()), PLAY_SHELF),
            rediscovery = todayRediscovery(db.dao().albums().first(), tracks, now),
        )
    }

    suspend fun mostPlayed(since: Long?, limit: Int): List<PlayedTrack> {
        val local = if (since == null) user.playedTracks().first() else user.playedTracksSince(since).first()
        return todayMostPlayed(local, server(), sent(), since, limit)
    }

    suspend fun recent(limit: Int): List<HistoryPlay> = todayRecentHistory(user.recentPlays(limit).first(), server(), limit)
}

// The ids of each shelf, to compare two readings.
internal fun PlayShelves.ids(): List<List<String>> = listOf(
    mostPlayed.map { it.id },
    recentlyPlayed.map { it.id },
    rediscovery.notPlayedLately.map { it.id },
    rediscovery.neverFinished.map { it.id },
    rediscovery.neverPlayed.map { it.id },
)
