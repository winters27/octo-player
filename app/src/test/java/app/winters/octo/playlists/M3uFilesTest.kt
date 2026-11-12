package app.winters.octo.playlists

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.data.Exports
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import app.winters.octo.playlists.today.matchM3u as todayMatch

// A labeled pin. A playlist file read a line at a time makes the playlist
// today's import made (PlaylistFiles.kt at c2208eaa: the file's first 8 MB
// as text, UTF-8 or Windows' Western encoding, parsed whole and matched
// against the whole library and the phone's paths), with the same report.
// Line endings of every kind, a byte order mark, #EXTINF lines, a
// character cut at the 8 MB mark. An export written a page at a time is
// today's file byte for byte (a find's line included; of two phone copies,
// the one the scan found first, declared), copied out with no lock held. No
// statement reads more than a page and a few rows, but the matching's read
// of its title questions, at most four for each of ID_CHUNK entries.
@RunWith(RobolectricTestRunner::class)
class M3uFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: CatalogDatabase = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val merge = db.testMerge(null)
    private val made = LinkedHashMap<String, List<String>>()
    private val files = PlaylistFiles(
        context, db, merge, sets, WorkOwners(), MatchJobs(db, WorkOwners(), FreeSpace { Long.MAX_VALUE }, 50),
        Exports(Drains(Files.createTempDirectory("export").toFile())), FreeSpace { Long.MAX_VALUE },
        create = { name, pages ->
            val rows = ArrayList<String>()
            pages { page -> rows += page.map { it.trackId } }
            made[name] = rows
            "made-$name"
        },
    )
    private val dir: File = Files.createTempDirectory("m3u").toFile()

    @After
    fun close() = db.close()

    private fun file(name: String, bytes: ByteArray): Uri = Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })

    // Today's import of the same bytes.
    private fun today(name: String, bytes: ByteArray, tracks: List<app.winters.octo.catalog.TrackEntity>): ImportReport {
        val text = playlistText(bytes.copyOf(minOf(bytes.size, PLAYLIST_FILE_LIMIT)))
        val entries = parseM3u(text)
        val match = todayMatch(entries, tracks.inLibraryOrder().map { M3uSong(it.id, it.title, it.artist, it.durationMs) }, db.phonePaths())
        if (match.trackIds.isNotEmpty()) made["today:$name"] = match.trackIds
        return ImportReport(playlistNameOf(name), match.trackIds.size, entries.size, match.missed.map { it.shown() })
    }

    private fun m3u(tracks: List<app.winters.octo.catalog.TrackEntity>, lines: Int): String {
        val r = Random(21)
        val paths = db.phonePaths().values.toList()
        val ends = listOf("\n", "\r\n", "\r")
        val out = StringBuilder(BYTE_ORDER_MARK + "#EXTM3U\n")
        repeat(lines) { i ->
            val t = tracks[r.nextInt(tracks.size)]
            val end = ends[i % 3]
            when (r.nextInt(5)) {
                0 -> out.append("/storage/emulated/0/").append(paths[r.nextInt(paths.size)]).append(end)
                1 -> out.append("#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}").append(end).append("x/${t.title}.mp3").append(end)
                2 -> out.append("#EXTINF:-1,${t.title}").append(end).append("Somewhere/Nothing $i.flac").append(end)
                3 -> out.append("# a comment").append(end).append(end).append("C:\\Music\\${t.artist} - ${t.title}.mp3").append(end)
                else -> out.append("http://example.com/a/Unknown%20$i.mp3").append(end)
            }
        }
        return out.toString()
    }

    @Test
    fun anImportIsTodaysImport() = timed(120) {
        val tracks = db.matchLibrary(300)
        val bytes = m3u(tracks, 1_500).toByteArray(Charsets.UTF_8)
        val expected = today("Road trip.m3u8", bytes, tracks)
        db.counted.reset()
        db.counted.recording = true
        val report = runBlocking { files.import(file("Road trip.m3u8", bytes)) }!!
        db.counted.recording = false
        val (questions, others) = db.counted.own.partition { it.sql.startsWith("SELECT query, rank, title") && "FROM match_title" in it.sql }
        assertTrue("${others.maxBy { it.rowsRead }}", others.all { it.rowsRead <= 600 })
        assertTrue("${questions.maxByOrNull { it.rowsRead }}", questions.all { it.rowsRead <= 4 * ID_CHUNK })
        assertEquals(expected, report)
        assertEquals(made["today:Road trip.m3u8"], made["Road trip"])
        assertEquals(0, db.query("SELECT COUNT(*) FROM match_query", null).use { it.moveToFirst(); it.getInt(0) })
    }

    // A Western file falls back as today's does, and a file whose 8 MB mark
    // cuts a character in two reads as today's cut read did.
    @Test
    fun theEncodingIsTodays() = timed(60) {
        val tracks = db.matchLibrary(60)
        val western = "#EXTINF:200,Beyoncé - Café\nMusic/Café.mp3\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("windows-1252", charsetOf(ByteArrayInputStream(western)).name())
        assertEquals(today("w.m3u", western, tracks), runBlocking { files.import(file("w.m3u", western)) })
        // A comment fills the file to just before the mark; "é" (two
        // bytes) starts at its last byte.
        val head = "#EXTINF:200,Kavinsky - Nightcall\nMusic/Nightcall.mp3\n#"
        val filler = "x".repeat(PLAYLIST_FILE_LIMIT - head.length - 1)
        val cut = (head + filler + "é\nMore.mp3\n").toByteArray(Charsets.UTF_8)
        assertEquals(PLAYLIST_FILE_LIMIT - 1, (head + filler).toByteArray(Charsets.UTF_8).size)
        assertEquals("windows-1252", charsetOf(ByteArrayInputStream(cut)).name())
        assertEquals(today("cut.m3u", cut, tracks), runBlocking { files.import(file("cut.m3u", cut)) })
    }

    // At most 1,000 lines not found are listed, then how many more.
    @Test
    fun aLongListOfMissedLinesIsCut() = timed(60) {
        db.matchLibrary(20)
        val bytes = (0 until 1_200).joinToString("\n") { "Nowhere/Missing $it.mp3" }.toByteArray()
        val report = runBlocking { files.import(file("gone.m3u", bytes)) }!!
        assertEquals(ImportReport("gone", 0, 1_200, (0 until 1_000).map { "Nowhere/Missing $it.mp3" } + "and 200 more not found"), report)
        assertFalse("gone" in made)
    }

    @Test
    fun anExportIsTodaysFile() = timed(60) {
        val tracks = db.matchLibrary(400)
        runBlocking {
            db.onlineDao().keep(
                listOf(
                    OnlineSongEntity(
                        id = "find:1", sourceId = "server:x", nativeId = "1", title = "Found", artist = "Online", album = "",
                        albumId = null, artistId = null, durationMs = 0, coverId = null, mimeType = null, bitrate = null, seenAt = 0,
                    ),
                ),
            )
            val songs = (0 until 1_300).map { tracks[(it * 7) % tracks.size].id } + listOf("find:1", "gone", tracks[0].id)
            sets.create(PlaylistEntity("p", "P", 1, 1)) { emit -> songs.chunked(200).forEach { page -> emit(page.map { NewItem(it, "") }) } }
        }
        val expected = runBlocking {
            val paths = db.phonePaths()
            writeM3u(
                db.userDao().playlistTracks("p").first().map { it.track }.map { t ->
                    M3uLine((t.durationMs / 1000).toInt(), t.artist, t.title, paths[t.id] ?: fallbackLocation(t.artist, t.title))
                },
            )
        }
        val out = ByteArrayOutputStream()
        var lockHeld = true
        files.page = 50
        files.copy = { file, _ ->
            lockHeld = merge.holdsMergeLock()
            out.write(file.readBytes())
            true
        }
        db.counted.reset()
        assertEquals(null, runBlocking { files.export("p", Uri.parse("content://picked/p.m3u")) })
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 600)
        assertFalse(lockHeld)
        assertEquals(expected, String(out.toByteArray(), Charsets.UTF_8))
        assertEquals(0, db.query("SELECT COUNT(*) FROM playlist_set_pin", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
