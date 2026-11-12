package app.winters.octo.playlists

import app.winters.octo.catalog.PlaylistEntryEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.data.Drains
import app.winters.octo.discovery.TitleIndex
import app.winters.octo.discovery.sameSong
import app.winters.octo.discovery.titleKeys
import app.winters.octo.testing.CountedTransaction
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.stream.Collectors

// The read the tests resolve.
private const val READ = 1L

// The big bucket's titles, each five times in the library.
private const val TITLES = 200
private const val POOL_SONGS = TITLES * 5

// How many pool songs a page of the walk reads, and about how many rows one
// of its transactions writes before the next one goes on.
private const val PAGE_SONGS = 50
private const val ROWS_A_TRANSACTION = 500

// The title pool's walk over buckets that share one lookup key. Each test
// writes a read's entries straight into the work tables and resolves it,
// and each group takes what a labeled pin of today's matching gives it.
@RunWith(RobolectricTestRunner::class)
class PoolBucketBoundTest {
    private val db = openTestCatalog()
    private val dir = Files.createTempDirectory("poolbucket").toFile()
    private val reads = PlaylistReads(db, Drains(dir), { Long.MAX_VALUE }, { SOURCE }, { NOW })

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun track(id: String, title: String, artist: String, seconds: Int) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = searchKey(title), sortKey = sortKey(title),
        artist = artist, artistId = "a:$artist", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = seconds * 1000L, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "k:$id",
    )

    private fun entry(position: Int, title: String, artist: String, lengthMs: Long) = PlaylistEntryEntity(
        read = READ, position = position.toLong(), songId = "e$position", external = false, title = title, artist = artist,
        lengthMs = lengthMs, album = "", albumId = null, artistId = null, coverId = null, mimeType = null, bitrate = null,
        explicit = null, trackId = null, groupId = null,
    )

    private suspend fun seed(library: List<TrackEntity>, entries: List<PlaylistEntryEntity>) {
        library.chunked(500).forEach { db.dao().insertTracks(it) }
        entries.chunked(500).forEach { db.playlistWorkDao().insertEntries(it) }
    }

    // What each entry resolved to, in order: its song, or its find.
    private fun resolved(): List<String> =
        db.query("SELECT trackId FROM playlist_entry WHERE read = ? ORDER BY position", arrayOf<Any?>(READ)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    // A labeled pin: today's matching (resolveSongs: TitleIndex with
    // sameSong, the first candidate that is the same song) over the pool in
    // the order the walk offers it, by search key and then id. Never edited
    // to follow the app. Entries are matched on every core at once, each on
    // its own, in order.
    private fun pinned(entries: List<PlaylistEntryEntity>, library: List<TrackEntity>): List<String> {
        val keys = entries.flatMap { titleKeys(it.title) }.toSet()
        val pool = library.filter { it.searchKey in keys }.sortedWith(compareBy<TrackEntity> { it.searchKey }.thenBy { it.id })
        val index = TitleIndex(pool, { it.title }, { it.artist })
        return entries.parallelStream().map { e ->
            index.candidates(e.title, e.artist).firstOrNull { sameSong(e.title, e.artist, e.lengthMs, it.title, it.artist, it.durationMs) }?.id
                ?: "find:${e.songId}"
        }.collect(Collectors.toList())
    }

    // POOL_SONGS pool songs, "Track 1" to "Track 200" five times each,
    // against 200 open groups "Track 1" to "Track 200" that all share the
    // lookup key "track". The odd groups are by an artist the library lacks
    // and stay open to the end; each even one has the same song in its five,
    // some twice (the first in the pool's order taken), some first as
    // another version 40 s longer (the second taken), some with no length.
    // Every group takes what the pin gives it. The walk's work is counted,
    // never timed: each pool song is parsed once and each group at most
    // once, so the parses are at most the pool's songs and its distinct
    // groups; and each page of the pool writes in one transaction, plus one
    // for each ROWS_A_TRANSACTION rows it writes.
    @Test
    fun aBigBucketResolvesAsTodayInBoundedWork() = runBlocking {
        val library = (1..TITLES).flatMap { j ->
            (0 until 5).map { slot ->
                // Two of the five by one artist on some titles.
                val twin = j % 10 in setOf(2, 4) && slot == (j + 1) % 5
                val artist = if (twin) j % 5 else slot
                val earlier = minOf(j % 5, (j + 1) % 5)
                val longer = j % 10 == 4 && slot == earlier
                track("t%04d-%d".format(j, slot), "Track $j", "Band $artist", 180 + j % 60 + slot + if (longer) 40 else 0)
            }
        }
        val entries = (1..TITLES).map { k ->
            val artist = if (k % 2 == 1) "Stranger ${k % 7}" else "Band ${k % 5}"
            entry(k - 1, "Track $k", artist, if (k % 6 == 0) 0 else (180L + k % 60) * 1000)
        }
        seed(library, entries)
        val pin = pinned(entries, library)
        assertEquals("the pin takes every even group", TITLES / 2, pin.count { !it.startsWith("find:") })
        assertEquals("both by one artist and the same song: the first in the pool's order", "t0012-2", pin[11])
        assertEquals("the first by its artist is another version: the second", "t0014-4", pin[13])

        // Each page's own transactions: from the page's parse (its songs read)
        // to its end. A walk that does not report its parses has each page
        // run from the end of the one before, the first from the start.
        val log = db.counted
        val perPage = ArrayList<List<CountedTransaction>>()
        var pageStart = 0
        var songsParsed = 0
        var groupsParsed = 0
        reads.hooks = object : ReadHooks() {
            override fun parsed(read: Long, songs: Int, groups: Int) {
                if (songs > 0) pageStart = log.transactions.size
                songsParsed += songs
                groupsParsed += groups
            }

            override suspend fun pooled(read: Long, pages: Int) {
                val all = log.transactions
                perPage += all.subList(pageStart, all.size).toList()
                pageStart = all.size
            }
        }
        log.reset()
        log.recording = true
        val started = System.nanoTime()
        try {
            assertTrue(reads.resolve(READ))
        } finally {
            log.recording = false
        }
        val took = (System.nanoTime() - started) / 1_000_000

        assertEquals(pin, resolved())
        assertEquals("pages of $PAGE_SONGS pool songs", POOL_SONGS / PAGE_SONGS, perPage.size)
        // Room runs each read outside a transaction as one of its own,
        // holding that one statement; a page's writing transaction holds its
        // songs' existence checks and their writes.
        val writing = perPage.map { page -> page.filter { it.statements > 1 } }
        assertTrue("no transaction was recorded", writing.any { it.isNotEmpty() })
        writing.forEachIndexed { i, page ->
            val rows = page.sumOf { it.rowsChanged }
            val bound = 1 + rows / ROWS_A_TRANSACTION
            assertTrue("page ${i + 1}: ${page.size} transactions wrote $rows rows, bound $bound: $page", page.size <= bound)
        }
        val transactions = writing.sumOf { it.size }
        val rows = writing.sumOf { page -> page.sumOf { it.rowsChanged } }
        assertTrue("$transactions transactions for $rows rows", transactions <= perPage.size + rows / ROWS_A_TRANSACTION)

        assertTrue("no parse was recorded", songsParsed > 0 && groupsParsed > 0)
        assertEquals("each pool song parsed once", POOL_SONGS, songsParsed)
        assertTrue("$groupsParsed group parses for $TITLES groups", groupsParsed <= TITLES)
        assertTrue("$songsParsed + $groupsParsed parses", songsParsed + groupsParsed <= POOL_SONGS + TITLES)
        println(
            "RESULT time-1b pool bucket: $POOL_SONGS pool songs against $TITLES open groups: $transactions writing transactions over ${perPage.size} pages " +
                "($rows rows), $songsParsed song and $groupsParsed group parses, $took ms (no bound; the work is counted)",
        )
    }

    // One pool song is the same song as 300 open groups (one title and
    // artist, every length within ten seconds of its own), and 50 more are
    // another version. Its matches take more than one transaction, none
    // writing more than about 500 rows (the walk's bound, and the two rows
    // of the group that reaches it), and every one of the 300 takes it, the
    // first of the two such songs in the pool's order.
    @Test
    fun aSongTakingManyGroupsSpreadsItsWrites() = runBlocking {
        val library = listOf(track("a1", "Same Song", "Band", 180), track("a2", "Same Song", "Band", 180))
        val entries = (0 until 350).map { i ->
            entry(i, "Same Song", "Band", if (i < 300) 175_000L + 30L * i else 200_000L + 100L * i)
        }
        seed(library, entries)
        val pin = pinned(entries, library)
        assertEquals(List(300) { "a1" }, pin.take(300))
        assertTrue(pin.drop(300).all { it.startsWith("find:") })

        val log = db.counted
        log.reset()
        log.recording = true
        try {
            withTimeout(60_000) { assertTrue(reads.resolve(READ)) }
        } finally {
            log.recording = false
        }
        assertEquals(pin, resolved())
        val written: List<CountedTransaction> = log.transactions.filter { it.rowsChanged > 0 }
        assertTrue("no transaction was recorded", written.isNotEmpty())
        assertTrue("a transaction wrote more than about 500 rows: ${written.maxBy { it.rowsChanged }}", written.all { it.rowsChanged <= 502 })
    }
}
