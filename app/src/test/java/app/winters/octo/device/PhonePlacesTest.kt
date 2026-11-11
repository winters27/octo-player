package app.winters.octo.device

import app.winters.octo.catalog.MergeHooks
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

// A phone scan keeps the places (seq) of the phone's songs, albums and
// artists where their order still holds, as a server's copy does: a new
// album by a new artist that comes first and a song taken out of another
// album cost no other row its place, so its save runs no statement for
// places.
@RunWith(RobolectricTestRunner::class)
class PhonePlacesTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun places(): Map<String, Long> =
        db.query("SELECT id, seq FROM source_track WHERE sourceId = 'device'", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    @Test
    fun aScanKeepsEveryUnmovedSongsPlace() = runBlocking {
        // Each claimed save's steps, summed over its groups.
        val swaps: MutableList<Map<String, Long>> = Collections.synchronizedList(ArrayList())
        val current = Collections.synchronizedMap(LinkedHashMap<String, Long>())
        var claimed = false
        val hooks = object : MergeHooks() {
            override suspend fun inSwap() {
                claimed = true
            }

            override fun groupStep(turn: Long, step: String, rows: Long) {
                current.merge(step, rows, Long::plus)
            }

            override fun applied(turns: Long, rows: Long, millis: Long) {
                if (claimed) swaps += LinkedHashMap(current)
                claimed = false
                current.clear()
            }
        }
        val merge = db.testMerge(null, hooks)
        val files = PhoneFiles.albums(files = 600, perAlbum = 12, unknownAlbum = 100)
        val phone = db.phoneLibrary(files, merge)
        phone.rescan()
        val before = places()
        assertEquals(700, before.size)
        // A new album listed first, and the last song of the third album
        // taken out.
        val gone = files.file(35).id
        val fresh = (0 until 4).map { n ->
            PhoneFile(
                id = 5_000L + n, displayName = "%02d New %d.flac".format(n + 1, n), relativePath = "Music/New Band/New Album/",
                title = "New $n", artist = "New Band", album = "New Album", albumArtist = "New Band", track = n + 1,
            )
        }
        val kept = (0 until files.count).map(files.file).filter { it.id != gone }
        FakeMediaStore.current!!.files = PhoneFiles(fresh.size + kept.size) { i -> if (i < fresh.size) fresh[i] else kept[i - fresh.size] }
        phone.rescan()
        val after = places()
        assertEquals(703, after.size)
        val both = before.keys.filter { it in after }
        assertEquals(699, both.size)
        assertEquals("songs that took a new place", emptyList<String>(), both.filter { before[it] != after[it] })
        val scan = swaps.last()
        assertEquals("no statement for places runs: $scan", emptyList<String>(), scan.keys.filter { it.startsWith("update seq of") })
        assertEquals("$scan", 4L, scan["insert source_track"])
        assertEquals("$scan", 1L, scan["delete source_track"])
        assertEquals("$scan", 1L, scan["insert source_album"])
        assertEquals("$scan", 1L, scan["insert source_artist"])
        assertTrue("the new songs come before the rest", fresh.all { f -> after.getValue("$DEVICE:${f.id}") < both.minOf { after.getValue(it) } })
    }
}
