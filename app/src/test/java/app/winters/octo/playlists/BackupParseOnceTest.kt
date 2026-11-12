package app.winters.octo.playlists

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.read
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.discovery.sameSong
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import kotlin.math.abs

// The big bucket's titles, each by five artists in the library, and how
// many songs a page of the walk reads.
private const val TITLES = 200
private const val SONGS = TITLES * 5
private const val PAGE = 100

// A backup's walk over one title bucket that every song and every entry
// shares ("Track 1" to "Track 200" all read "track"): each page parses each
// of its songs once and each entry its songs reach once, counted, however
// many songs meet however many entries; and each entry's title answer is
// what comparing every song with it field by field, in library order,
// gives (the closest length, the first on a tie).
@RunWith(RobolectricTestRunner::class)
class BackupParseOnceTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun track(i: Int): TrackEntity {
        val title = "Track ${i % TITLES + 1}"
        val artist = "Artist ${i / TITLES}"
        return TrackEntity(
            id = "t%04d".format(i), sourceId = "device", nativeId = "$i", title = title, searchKey = searchKey(title), sortKey = sortKey(title),
            artist = artist, artistId = "a:$artist", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
            durationMs = 180_000L + (i % 7) * 4_000L, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "",
        )
    }

    // A backup's song with nothing but its title, artist and length (a
    // second off its song's), so only the title answer can find it.
    private fun entry(i: Int): MatchSong {
        val song = track(i % TITLES + TITLES * ((i / 3) % 5))
        return MatchSong(title = song.title, artist = song.artist, durationMs = song.durationMs + 1_000L)
    }

    // Every song against each entry, field by field, in library order.
    private fun oracle(entries: List<MatchSong>, library: List<TrackEntity>): List<String?> {
        val ordered = library.sortedWith(compareBy({ it.sortKey }, { it.id }))
        return entries.map { e ->
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
    }

    @Test
    fun aPageParsesEachSongAndEachEntryOnce() = timed(120) {
        val library = (0 until SONGS).map(::track)
        library.chunked(500).forEach { db.dao().insertTracks(it) }
        val entries = (0 until 300).map(::entry)
        val pages = Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
        val jobs = matchJobs(db, page = PAGE)
        jobs.hooks = object : MatchHooks() {
            override fun parsed(songs: Int, entries: Int) {
                pages += songs to entries
            }
        }
        val answers = jobs.run(MatchKind.Backup, SpaceFor.Restore, entries.size.toLong()) { job ->
            job.stageSongs(0, entries)
            job.walk()
            db.read("SELECT titleId FROM match_query WHERE owner = ? ORDER BY id", job.owner) { it.getString(0) }
        }
        assertEquals(SONGS / PAGE, pages.size)
        for ((songs, reached) in pages) {
            assertTrue("a page parsed $songs songs for $PAGE", songs <= PAGE)
            assertTrue("a page parsed $reached entries for ${entries.size}", reached <= entries.size)
        }
        assertTrue("the bucket was walked: $pages", pages.sumOf { it.second } > 0)
        assertEquals(oracle(entries, library), answers)
        assertTrue("every entry found", answers.all { it != null })
    }
}
