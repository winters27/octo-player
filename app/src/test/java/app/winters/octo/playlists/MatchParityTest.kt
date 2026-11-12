package app.winters.octo.playlists

import app.winters.octo.backup.SongKey
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import app.winters.octo.playlists.today.SongFinder as TodaySongFinder
import app.winters.octo.playlists.today.matchM3u as todayMatchM3u

// A labeled pin. One walk of the library finds what today's whole-library
// finders found, entry for entry: a backup's songs by relink key (the first
// in library order), then title, artist and album (the closest length, the
// first on a tie), then the same song by title and artist; and a playlist
// file's entries by path (the most folders shared, the first phone copy on
// a tie), then by #EXTINF artist and title, then by the file's name. Blank
// titles and artists, versions, guests, accents, an emoji, lengths unknown
// and far apart, songs sharing a sort key. Read in pages of 7; no
// statement reads more than 900 rows.
@RunWith(RobolectricTestRunner::class)
class MatchParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aBackupsSongsAreFoundAsToday() = timed(120) {
        val tracks = db.matchLibrary(400)
        val r = Random(11)
        fun pick(list: List<String>) = list[r.nextInt(list.size)]
        val lists = (0 until 3).map { list ->
            (0 until 250).map {
                val near = tracks[r.nextInt(tracks.size)]
                when (r.nextInt(6)) {
                    0 -> SongKey(relinkKey = "rk${r.nextInt(45)}", title = near.title, artist = near.artist)
                    1 -> SongKey(title = near.title, artist = near.artist, album = near.album, durationMs = near.durationMs + r.nextInt(-9, 9) * 1_000L)
                    2 -> SongKey(title = near.title, artist = near.artist, album = "Elsewhere", durationMs = if (r.nextBoolean()) 0 else near.durationMs + 4_000)
                    3 -> SongKey(title = pick(MATCH_TITLES), artist = pick(MATCH_ARTISTS), album = pick(MATCH_ALBUMS), durationMs = 180_000L + r.nextInt(13) * 3_000L)
                    4 -> SongKey(title = if (r.nextBoolean()) "" else "  ", artist = near.artist)
                    else -> SongKey(title = near.title, artist = "", album = pick(MATCH_ALBUMS), durationMs = near.durationMs)
                }
            }
        }
        val finder = TodaySongFinder(tracks.inLibraryOrder())
        db.counted.reset()
        val found = matchJobs(db).run(MatchKind.Backup, SpaceFor.Restore, 750) { job ->
            lists.forEachIndexed { list, songs ->
                songs.chunked(40).forEach { chunk -> job.stageSongs(list, chunk.mapIndexed { i, s -> MatchSong(s.relinkKey, s.title, s.artist, s.album, s.durationMs, value = i % 5) }) }
            }
            job.walk()
            job.finish()
            lists.indices.map { job.allRows(it) }
        }
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 900)
        lists.forEachIndexed { list, songs ->
            val today = songs.map(finder::find)
            assertEquals("list $list", today.filterNotNull(), found[list].map { it.trackId })
            assertEquals("list $list", today.indices.filter { today[it] != null }.map { it.toLong() + list * 250 }, found[list].map { it.entry })
        }
        assertTrue("nothing was found", found.sumOf { it.size } > 200)
        // The job's rows are gone with it.
        assertEquals(0, db.query("SELECT COUNT(*) FROM match_query", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun aPlaylistFilesEntriesAreFoundAsToday() = timed(120) {
        val tracks = db.matchLibrary(400)
        val paths = db.phonePaths()
        val r = Random(5)
        val entries = (0 until 300).map { line ->
            val near = tracks[r.nextInt(tracks.size)]
            val path = paths.values.elementAt(r.nextInt(paths.size))
            when (r.nextInt(7)) {
                0 -> M3uEntry(path, line = line)
                1 -> M3uEntry("C:\\Users\\me\\" + path.replace('/', '\\'), line = line)
                2 -> M3uEntry("Other/" + path.substringAfterLast('/'), seconds = 200, artist = near.artist, title = near.title, line = line)
                3 -> M3uEntry("x/${r.nextInt(99)}.mp3", seconds = (near.durationMs / 1000).toInt() + r.nextInt(-12, 12), artist = near.artist.ifBlank { null }, title = near.title, line = line)
                4 -> M3uEntry("Music/${near.artist} - ${near.title}.flac", line = line)
                5 -> M3uEntry("Music/0${r.nextInt(9)} ${near.title.replace(' ', '_')}.mp3", seconds = if (r.nextBoolean()) null else 190, line = line)
                else -> M3uEntry("file:///sdcard/Music/${MATCH_TITLES[r.nextInt(MATCH_TITLES.size)]}.ogg", title = MATCH_TITLES[r.nextInt(MATCH_TITLES.size)], line = line)
            }
        }
        val today = todayMatchM3u(entries, tracks.inLibraryOrder().map { M3uSong(it.id, it.title, it.artist, it.durationMs) }, paths)
        val (found, missed) = matchJobs(db).run(MatchKind.Playlist, SpaceFor.KeepList, entries.size.toLong()) { job ->
            entries.chunked(60).forEach { job.stageEntries(it) }
            job.walk()
            job.finish()
            job.allRows(0) to job.missed(0, limit = 1_000).map { it.second }
        }
        assertEquals(today.trackIds, found.map { it.trackId })
        assertEquals(today.missed.map { it.line }, missed)
        assertTrue("too few found by path or title: ${found.size}", found.size > 100)
        assertTrue("nothing missed", missed.isNotEmpty())
    }
}
