package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.sortKey
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.today.sortFolderSongs
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.folders.today.phoneSongOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A labeled pin: a big folder's songs, written in order to the page's copy
// and walked a page at a time, are today's (ui/folders/today's
// phoneSongOrder, then sort/today's sortFolderSongs, as c2208eaa had them,
// the song's id the declared last tie) in every folder order, both ways,
// on names past the BMP, in the private use area, alike but for case,
// accents and nothing at all, with ties in every key.
@RunWith(RobolectricTestRunner::class)
class FolderOrderParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val names = EDGE_TITLES + listOf("𝄞𝄞", "￿ end", "", "𐐀 deseret", "𐐨 deseret")

    @Test
    fun everyFolderOrderIsTodays() = timed(90) {
        val random = Random(17)
        val tracks = (0 until 700).map { i ->
            val title = names[random.nextInt(names.size)]
            val artist = names[random.nextInt(names.size)]
            val album = names[random.nextInt(names.size)]
            TrackEntity(
                id = "device:f%04d-%d".format(random.nextInt(10_000), i), sourceId = "device", nativeId = "$i", title = title, searchKey = title.lowercase(),
                sortKey = sortKey(title), artist = artist, artistId = "ar", album = album, albumId = "al:${random.nextInt(4)}", trackNo = null, discNo = null,
                year = if (random.nextInt(3) == 0) null else 1990 + random.nextInt(3), durationMs = 1_000L * random.nextInt(4), addedAt = random.nextLong(4),
                mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = random.nextInt(3),
            )
        }
        val folder = db.phoneFolder("Mixed", tracks)
        val folders = PhoneFolders(db, db.testMerge(null), { Long.MAX_VALUE })
        val inFolder = tracks.sortedWith(phoneSongOrder.thenBy { it.id })
        val sorts = listOf(SongSort.FolderOrder, SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded, SongSort.Year, SongSort.Length)
        for (sort in sorts) {
            for (down in listOf(false, true)) {
                val order = SortOrder(sort, down)
                folders.copy(WorkOwners.marked("vm:1"), listOf(folder), order, ranked = false)
                val want = sortFolderSongs(inFolder, order).map { it.id }
                assertEquals("$sort ${if (down) "desc" else "asc"}", want, folders.loader(WorkOwners.marked("vm:1")).walked(page = 37))
            }
        }
    }
}
