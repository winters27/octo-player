package app.winters.octo.playlists

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.data.Exports
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// An M3U file of 20,000 lines is imported, and a 20,000-song playlist
// exported, with no statement reading more than a page of rows but the
// matching's read of its title questions, at most four for each of ID_CHUNK
// entries. Guard: the base read the whole library and every phone path to
// match, and the playlist whole to export.
@RunWith(RobolectricTestRunner::class)
class M3uResidencyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: CatalogDatabase = openTestCatalog()
    private val sets = PlaylistSets(db)
    private val merge = db.testMerge(null)
    private var made = 0
    private val files = PlaylistFiles(
        context, db, merge, sets, WorkOwners(), MatchJobs(db, WorkOwners(), FreeSpace { Long.MAX_VALUE }, 100),
        Exports(Drains(Files.createTempDirectory("export").toFile())), FreeSpace { Long.MAX_VALUE },
        create = { _, pages ->
            pages { page -> made += page.size }
            "made"
        },
    )
    private val dir: File = Files.createTempDirectory("m3u").toFile()

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private val n = 20_000

    @Test
    fun anImportOf20000LinesAndAnExportOf20000SongsHoldAPage() = timed(300) {
        val tracks = db.matchLibrary(600)
        val paths = db.phonePaths().values.toList()
        val text = buildString {
            append("#EXTM3U\n")
            repeat(n) { i ->
                val t = tracks[(i * 7) % tracks.size]
                when (i % 3) {
                    0 -> append("/storage/emulated/0/").append(paths[i % paths.size]).append('\n')
                    1 -> append("#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}\nx/${t.title}.mp3\n")
                    else -> append("Somewhere/Nothing $i.flac\n")
                }
            }
        }
        val file = Uri.fromFile(File(dir, "Big.m3u8").apply { writeText(text) })
        db.counted.reset()
        db.counted.recording = true
        val report = runBlocking { files.import(file) }!!
        db.counted.recording = false
        assertEquals(n, report.total)
        val (questions, others) = db.counted.own.partition { it.sql.startsWith("SELECT query, rank, title") && "FROM match_title" in it.sql }
        assertTrue("the import was recorded", others.isNotEmpty())
        assertTrue("${others.maxBy { it.rowsRead }}", others.all { it.rowsRead <= 600 })
        assertTrue("${questions.maxByOrNull { it.rowsRead }}", questions.all { it.rowsRead <= 4 * ID_CHUNK })
        // Every phone path at least is found, and the playlist holds what was.
        assertTrue("$made", made >= n / 3)
        assertEquals(report.matched, made)

        runBlocking { sets.create(PlaylistEntity("p", "P", 1, 1)) { emit -> (0 until n).chunked(500).forEach { page -> emit(page.map { NewItem(tracks[(it * 3) % tracks.size].id, "") }) } } }
        files.copy = { _, _ -> true }
        db.counted.reset()
        assertEquals(null, runBlocking { files.export("p", Uri.parse("content://picked/p.m3u")) })
        val export = db.counted.maxRead
        assertTrue("the export read: $export", export != null && export.rowsRead <= 600)
    }
}
