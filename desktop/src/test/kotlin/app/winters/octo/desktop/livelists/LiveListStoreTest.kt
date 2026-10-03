package app.winters.octo.desktop.livelists

import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListDraft
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import org.junit.Assume.assumeTrue

// An account's live lists kept in its folder: written as they change, read
// back as they were, one account's never another's.
class LiveListStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_000L

    @After
    fun stop() = scope.cancel()

    // Writes happen on the caller's thread, so the file is there at once.
    private fun store() = LiveListStore(scope, Dispatchers.Unconfined) { now }

    private val favourites = LibraryQuery(listOf(FilterPresets.Favourites, FilterPresets.Lossless))

    @Test
    fun listsAreWrittenAsTheyChangeAndReadBackAfterARestart() {
        val file = File(temp.root, "listening/abc/live-lists.json")
        val store = store()
        store.open(file)
        assertEquals(emptyList<LiveList>(), store.lists.value)
        val made = store.save(LiveList.new(" Lossless favorites ", favourites, 0, "a"))
        assertEquals("Lossless favorites", made.name)
        assertEquals(1_000L, made.created)
        now = 2_000
        store.save(LiveList.new("Recent", LibraryQuery(listOf(FilterPresets.AddedThisMonth)), 0, "b"))
        val copy = store.duplicate(made)
        store.rename("b", "Just in")
        assertTrue(file.exists())

        val again = store()
        again.open(file)
        assertEquals(listOf("Lossless favorites", "Lossless favorites (copy)", "Just in"), again.lists.value.map { it.name })
        assertEquals(favourites, again.byId(copy.id)?.query)
        assertEquals(1_000L, again.byId("a")?.created)
        assertEquals(2_000L, again.byId("b")?.changed)

        again.remove("a")
        val third = store()
        third.open(file)
        assertEquals(listOf(copy.id, "b"), third.lists.value.map { it.id })
    }

    // Something else (a virus scanner, a backup) has the file open while a
    // list is saved: the list is kept all the same, and read back.
    @Test
    fun aListSavedWhileTheFileIsHeldOpenIsKept() {
        val file = File(temp.root, "listening/abc/live-lists.json")
        val store = store()
        store.open(file)
        store.save(LiveList.new("First", favourites, 0, "a"))
        val open = FileInputStream(file)
        val closing = thread {
            Thread.sleep(2_500)
            open.close()
        }
        store.save(LiveList.new("Second", favourites, 0, "b"))
        val meanwhile = store()
        meanwhile.open(file)
        assertEquals(listOf("First", "Second"), meanwhile.lists.value.map { it.name })
        closing.join()
        store.rename("b", "Second, renamed")
        val after = store()
        after.open(file)
        assertEquals(listOf("First", "Second, renamed"), after.lists.value.map { it.name })
        assertTrue(file.readText().contains("Second, renamed"))
    }

    // Lists that cannot be read at the start are never saved over with none.
    @Test
    fun listsThatCannotBeReadAreLeftAlone() {
        // A lock only stops reading on Windows; elsewhere locks are advisory.
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val file = File(temp.root, "listening/abc/live-lists.json")
        store().apply { open(file) }.save(LiveList.new("Kept", favourites, 0, "a"))
        RandomAccessFile(file, "rw").use { locked ->
            locked.channel.lock().use {
                val store = store()
                store.open(file)
                assertEquals(emptyList<LiveList>(), store.lists.value)
                store.save(LiveList.new("New", favourites, 0, "b"))
                assertEquals(listOf("New"), store.lists.value.map { it.name })
            }
        }
        val again = store()
        again.open(file)
        assertEquals(listOf("Kept"), again.lists.value.map { it.name })
    }

    @Test
    fun eachAccountHasItsOwnLists() {
        val first = File(temp.root, "one/live-lists.json")
        val second = File(temp.root, "two/live-lists.json")
        val store = store()
        store.open(first)
        store.save(LiveList.new("Mine", favourites, 0, "m"))
        store.open(second)
        assertEquals(emptyList<LiveList>(), store.lists.value)
        store.save(LiveList.new("Theirs", favourites, 0, "t"))
        store.open(first)
        assertEquals(listOf("Mine"), store.lists.value.map { it.name })
        // Signed out: nothing shows, and nothing is written anywhere.
        store.open(null)
        assertEquals(emptyList<LiveList>(), store.lists.value)
        store.save(LiveList.new("Nowhere", favourites, 0, "n"))
        store.open(first)
        assertEquals(listOf("Mine"), store.lists.value.map { it.name })
    }

    @Test
    fun aDamagedFileStartsEmptyAndABlankRenameIsIgnored() {
        val file = File(temp.root, "live-lists.json").apply { writeText("{ not json") }
        val store = store()
        store.open(file)
        assertEquals(emptyList<LiveList>(), store.lists.value)
        store.save(LiveList.new("Kept", favourites, 0, "k"))
        store.rename("k", "   ")
        store.rename("missing", "Other")
        assertEquals(listOf("Kept"), store.lists.value.map { it.name })
        assertFalse(file.readText().contains("not json"))
    }

    @Test
    fun theDraftKeepsTypedNamesOrSuggestsOne() {
        val list = LiveList.new("Mine", favourites, 0, "m")
        assertFalse(LiveListDraft.of(list).changes(list))
        assertTrue(LiveListDraft.of(list).copy(name = "Other").changes(list))
        assertTrue(LiveListDraft.of(list).copy(query = LibraryQuery()).changes(list))
        assertEquals("Favorites, lossless", LiveListDraft("  ", favourites).savedName)
        assertEquals("Mine", LiveListDraft(" Mine ", favourites).savedName)
    }

    @Test
    fun aListsPictureIsFourAlbumCoversOrOne() {
        fun song(id: String, cover: String?) = Song(id, id, coverArt = cover)
        assertEquals(listOf("c1", "c2", "c3", "c4"), liveListCovers(listOf(song("1", "c1"), song("2", "c1"), song("3", null), song("4", "c2"), song("5", "c3"), song("6", "c4"), song("7", "c5"))))
        assertEquals(listOf("c1"), liveListCovers(listOf(song("1", "c1"), song("2", "c2"))))
        assertEquals(emptyList<String>(), liveListCovers(listOf(song("1", ""))))
    }
}
