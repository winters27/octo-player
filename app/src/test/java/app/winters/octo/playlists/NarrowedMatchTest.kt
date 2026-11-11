package app.winters.octo.playlists

import app.winters.octo.catalog.PlaylistEntryEntity
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.read
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.data.Drains
import app.winters.octo.discovery.SAME_SONG_LENGTH_MS
import app.winters.octo.discovery.TitleIndex
import app.winters.octo.discovery.sameSong
import app.winters.octo.discovery.titleKeys
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

// The read the pool walk resolves.
private const val READ = 1L

// Songs and entries that share one title key compare only where their
// lengths can still agree (within the compare's own tolerance, or one of
// them with no length), and find exactly what comparing every pair does: a
// backup's titles, a playlist file's title questions, and a playlist's
// pool walk, each over buckets of thousands of pairs with lengths spread
// over five minutes and some unknown. Every compare is counted.
@RunWith(RobolectricTestRunner::class)
class NarrowedMatchTest {
    private val db = openTestCatalog()
    private val dir = Files.createTempDirectory("narrowed").toFile()

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    // A length: two minutes to seven, to the second, or none.
    private fun length(r: Random): Long = if (r.nextInt(7) == 0) 0L else 120_000L + r.nextInt(300) * 1_000L

    // Songs in two buckets ("Untitled", "Intro"), by six artists.
    private fun library(r: Random, songs: Int): List<TrackEntity> = (0 until songs).map { i ->
        val title = if (i % 3 == 0) "Intro" else "Untitled"
        val artist = "Artist ${i % 6}"
        TrackEntity(
            id = "t%04d".format(i), sourceId = "device", nativeId = "$i", title = title, searchKey = searchKey(title), sortKey = sortKey(title),
            artist = artist, artistId = "a:$artist", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
            durationMs = length(r), addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "",
        )
    }

    private fun near(a: Long, b: Long, tolerance: Long) = a <= 0 || b <= 0 || abs(a - b) <= tolerance

    @Test
    fun aBackupsTitlesCompareOnlyLengthsThatCanAgree() = timed(180) {
        val r = Random(21)
        val library = library(r, 900)
        library.chunked(500).forEach { db.dao().insertTracks(it) }
        val entries = (0 until 400).map { i ->
            MatchSong(title = if (i % 4 == 0) "Intro" else "Untitled", artist = "Artist ${i % 6}", durationMs = length(r))
        }
        val compared = AtomicInteger()
        val jobs = matchJobs(db, page = 100)
        jobs.hooks = object : MatchHooks() {
            override fun compared(pairs: Int) {
                compared.addAndGet(pairs)
            }
        }
        val answers = jobs.run(MatchKind.Backup, SpaceFor.Restore, entries.size.toLong()) { job ->
            job.stageSongs(0, entries)
            job.walk()
            db.read("SELECT titleId FROM match_query WHERE owner = ? ORDER BY id", job.owner) { it.getString(0) }
        }
        // Every song against each entry, field by field, in library order.
        val ordered = library.inLibraryOrder()
        val oracle = entries.map { e ->
            var best: Pair<String, Long>? = null
            val keys = SongIdentity.titleLookupKeys(e.title, e.artist)
            for (song in ordered) {
                if (SongIdentity.titleLookupKeys(song.title, song.artist).none { it in keys }) continue
                if (!sameSong(e.title, e.artist, e.durationMs, song)) continue
                val dist = abs(song.durationMs - e.durationMs)
                if (best == null || (e.durationMs > 0 && dist < best.second)) best = song.id to dist
            }
            best?.first
        }
        assertEquals(oracle, answers)
        assertTrue("some entries found", answers.count { it != null } > 50)
        val sharing = library.sumOf { song ->
            val keys = SongIdentity.titleLookupKeys(song.title, song.artist)
            entries.count { e -> SongIdentity.titleLookupKeys(e.title, e.artist).any { it in keys } }
        }
        val agreeing = library.sumOf { song ->
            val keys = SongIdentity.titleLookupKeys(song.title, song.artist)
            entries.count { e -> SongIdentity.titleLookupKeys(e.title, e.artist).any { it in keys } && near(song.durationMs, e.durationMs, SAME_SONG_LENGTH_MS) }
        }
        assertTrue("no compare was counted", compared.get() > 0)
        assertTrue("${compared.get()} compares, $agreeing pairs whose lengths can agree", compared.get() <= agreeing)
        assertTrue("$agreeing pairs of $sharing sharing a key", agreeing * 2 < sharing)
    }

    @Test
    fun aPlaylistFilesQuestionsCompareOnlyLengthsThatCanAgree() = timed(180) {
        val r = Random(22)
        val library = library(r, 900)
        library.chunked(500).forEach { db.dao().insertTracks(it) }
        val entries = (0 until 400).map { i ->
            val ms = length(r)
            M3uEntry(location = "Music/x$i.mp3", seconds = (ms / 1000).toInt().takeIf { it > 0 }, artist = if (i % 5 == 0) null else "Artist ${i % 6}", title = if (i % 4 == 0) "Intro" else "Untitled", line = i)
        }
        val compared = AtomicInteger()
        val jobs = matchJobs(db, page = 100)
        jobs.hooks = object : MatchHooks() {
            override fun compared(pairs: Int) {
                compared.addAndGet(pairs)
            }
        }
        val answers = jobs.run(MatchKind.Playlist, SpaceFor.KeepList, entries.size.toLong()) { job ->
            job.stageEntries(entries)
            job.walk()
            job.finish()
            db.read("SELECT foundId FROM match_query WHERE owner = ? ORDER BY id", job.owner) { it.getString(0) }
        }
        val songs = library.inLibraryOrder().map { M3uSong(it.id, it.title, it.artist, it.durationMs) }
        val oracle = matchM3u(entries, songs, emptyMap())
        assertEquals(oracle.trackIds, answers.filterNotNull())
        assertEquals(oracle.missed.size, answers.count { it == null })
        assertTrue("some entries found", oracle.trackIds.size > 50)
        // Each song against each title question its title keys reach.
        val questions = entries.flatMap { m3uQuestions(it) }
        val sharing = library.sumOf { song -> questions.count { q -> titleKeys(q.title).any { it in titleKeys(song.title) } } }
        val agreeing = library.sumOf { song ->
            questions.count { q -> titleKeys(q.title).any { it in titleKeys(song.title) } && near(song.durationMs, q.lengthMs, M3U_SAME_LENGTH_MS) }
        }
        assertTrue("no compare was counted", compared.get() > 0)
        assertTrue("${compared.get()} compares, $agreeing pairs whose lengths can agree", compared.get() <= agreeing)
        assertTrue("$agreeing pairs of $sharing sharing a key", agreeing * 2 < sharing)
    }

    @Test
    fun aPoolWalkComparesOnlyLengthsThatCanAgree() = timed(180) {
        val r = Random(23)
        val library = library(r, 900).map { it.copy(relinkKey = "k:${it.id}") }
        library.chunked(500).forEach { db.dao().insertTracks(it) }
        val entries = (0 until 400).map { i ->
            PlaylistEntryEntity(
                read = READ, position = i.toLong(), songId = "e$i", external = false, title = if (i % 4 == 0) "Intro" else "Untitled",
                artist = "Artist ${i % 6}", lengthMs = length(r), album = "", albumId = null, artistId = null, coverId = null, mimeType = null,
                bitrate = null, explicit = null, trackId = null, groupId = null,
            )
        }
        entries.chunked(500).forEach { db.playlistWorkDao().insertEntries(it) }
        val reads = PlaylistReads(db, Drains(dir), { Long.MAX_VALUE }, { SOURCE }, { NOW })
        val compared = AtomicInteger()
        reads.hooks = object : ReadHooks() {
            override fun compared(read: Long, pairs: Int) {
                compared.addAndGet(pairs)
            }
        }
        assertTrue(reads.resolve(READ))
        val resolved = db.query("SELECT trackId FROM playlist_entry WHERE read = ? ORDER BY position", arrayOf<Any?>(READ)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        // Today's matching over the pool in the walk's order: the first
        // candidate that is the same song.
        val keys = entries.flatMap { titleKeys(it.title) }.toSet()
        val pool = library.filter { it.searchKey in keys }.sortedWith(compareBy<TrackEntity> { it.searchKey }.thenBy { it.id })
        val index = TitleIndex(pool, { it.title }, { it.artist })
        val pinned = entries.map { e ->
            index.candidates(e.title, e.artist).firstOrNull { sameSong(e.title, e.artist, e.lengthMs, it.title, it.artist, it.durationMs) }?.id ?: "find:${e.songId}"
        }
        assertEquals(pinned, resolved)
        assertTrue("some entries found", pinned.count { !it.startsWith("find:") } > 50)
        // Each distinct group (title, artist, length) against each pool song,
        // once for each lookup key they share, as the walk meets them.
        val groups = entries.distinctBy { Triple(it.title, it.artist, it.lengthMs) }
        fun meetings(song: TrackEntity, g: PlaylistEntryEntity): Int {
            val groupKeys = SongIdentity.titleLookupKeys(g.title, g.artist)
            return SongIdentity.titleLookupKeys(song.title, song.artist).count { it in groupKeys }
        }
        val sharing = pool.sumOf { song -> groups.sumOf { g -> meetings(song, g) } }
        val agreeing = pool.sumOf { song -> groups.sumOf { g -> if (near(song.durationMs, g.lengthMs, SAME_SONG_LENGTH_MS)) meetings(song, g) else 0 } }
        assertTrue("no compare was counted", compared.get() > 0)
        assertTrue("${compared.get()} compares, $agreeing pairs whose lengths can agree", compared.get() <= agreeing)
        assertTrue("$agreeing pairs of $sharing sharing a key", agreeing * 2 < sharing)
    }
}
