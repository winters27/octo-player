package app.winters.octo.livelists

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TodayPlays
import app.winters.octo.listening.TotalsRig
import app.winters.octo.listening.playOn
import app.winters.octo.playback.likeAll
import app.winters.octo.playback.putTracks
import app.winters.octo.playback.radioTrack
import app.winters.octo.query.LibraryQuery
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random
import app.winters.octo.livelists.today.pick as todayPick

// Live lists over a test library, its plays counted by a real totals rig.
internal class LiveRig(
    val db: CatalogDatabase,
    val merge: CatalogMerge = db.testMerge(null),
    page: Int = LIVE_PAGE,
    space: FreeSpace = FreeSpace { Long.MAX_VALUE },
) {
    val totals = TotalsRig(db, merge)
    val owners = WorkOwners()
    val picks = LivePicks(db, merge, totals.totals, owners, space, { NOW }, page)

    suspend fun started(): LiveRig = apply { totals.started() }

    // Today's list: every song in library order, picked as before.
    suspend fun today(query: LibraryQuery): List<TrackEntity> {
        val library = db.dao().tracks().first().sortedWith { a, b ->
            SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id)
        }
        return todayPick(library, db.userDao().likedIds().first(), TodayPlays(db, "home").tracks(), query, NOW)
    }

    // Every song an owner's list shows, in order, read a page at a time.
    suspend fun rows(owner: String, pageSize: Int = 7): List<String> {
        val loader = picks.loader(owner)
        val all = ArrayList<String>()
        var after: RowKey? = null
        while (true) {
            val page = loader.after(after, pageSize)
            all += page.map { it.row }
            if (page.size < pageSize) return all
            after = page.last().key
        }
    }
}

// A watch of one list, its states kept as they come.
internal class Watching(scope: CoroutineScope, picks: LivePicks, owner: String, listId: String?, val query: MutableStateFlow<LibraryQuery>) {
    val shown = MutableStateFlow<LiveShown>(LiveShown.Loading)
    val walks = MutableStateFlow(0)
    val job: Job = scope.launch {
        picks.watch(owner, listId, query).collect {
            shown.value = it
            walks.value += 1
        }
    }

    // Waits for the walk after `n` walks, and answers what it showed. The
    // first state comes before any walk.
    suspend fun after(n: Int): LiveShown {
        walks.first { it > n + 1 }
        return shown.value
    }

    // Waits for walks until `done` holds, at most `most` of them after the
    // ones so far, and answers what the last showed.
    suspend fun until(most: Int = 6, done: suspend (LiveShown) -> Boolean): LiveShown {
        var seen = walks.value
        if (seen > 1 && done(shown.value)) return shown.value
        repeat(most) {
            walks.first { it > seen }
            seen = walks.value
            if (done(shown.value)) return shown.value
        }
        throw AssertionError("not done after $most walks: ${shown.value}")
    }
}

// Runs a write in a transaction Room sees, so the lists' triggers follow.
internal suspend fun CatalogDatabase.change(sql: String, vararg args: Any?) =
    withTransaction { openHelper.writableDatabase.execSQL(sql, args) }

// A library for live lists: names with a replacement character and an
// emoji among them, equal sort keys, songs with no year, no length, no
// track number, songs on two discs, genres and likes, and plays on the
// phone at many times.
internal suspend fun CatalogDatabase.liveLibrary(songs: Int, seed: Int = 9): List<TrackEntity> {
    val r = Random(seed)
    val titles = listOf("Song", "song", "Intro", "Ünïcode", "Emoji 😀", "Ok �", "Track 10", "Track 9", "Zed", "")
    val artists = listOf("Band", "The Band", "", "Artist", "Ärtist 😀")
    val tracks = (0 until songs).map { i ->
        radioTrack(
            id = if (i % 50 == 7) "t�$i" else if (i % 50 == 8) "t😀$i" else "t%05d".format(songs - i),
            sortKey = "k%04d".format(i / 3),
            title = titles[r.nextInt(titles.size)] + " ${i % 4}",
            artist = artists[r.nextInt(artists.size)],
            artistId = "ar${i % 7}",
            genre = listOf("Rock", "rock", "Jazz", "", "Pop")[r.nextInt(5)],
            albumId = "al${i % 23}",
            year = if (i % 9 == 0) null else if (i % 11 == 0) 0 else 1970 + r.nextInt(50),
            rating = if (i % 6 == 0) r.nextInt(6) else 0,
            durationMs = if (i % 31 == 0) 500L else 60_000L + r.nextInt(300) * 1_000L,
        ).copy(
            album = "Album ${i % 23}",
            trackNo = if (i % 8 == 0) null else i % 12 + 1,
            discNo = if (i % 5 == 0) 2 else null,
            addedAt = if (i % 13 == 0) 0 else 1_700_000_000L + r.nextInt(10_000_000),
            mimeType = listOf("audio/flac", "audio/mpeg", null)[i % 3],
        )
    }
    putTracks(tracks)
    likeAll(tracks.filterIndexed { i, _ -> i % 4 == 1 }.map { it.id })
    tracks.forEachIndexed { i, t ->
        if (i % 3 == 0) repeat(1 + i % 5) { n -> playOn(t.id, NOW - (i * 997L + n * 13L) * 60_000) }
    }
    return tracks
}
