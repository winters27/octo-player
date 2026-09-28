package app.winters.octo.desktop.livelists

import app.winters.octo.livelists.LiveList
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
        val made = store.save(LiveList.new(" Lossless favourites ", favourites, 0, "a"))
        assertEquals("Lossless favourites", made.name)
        assertEquals(1_000L, made.created)
        now = 2_000
        store.save(LiveList.new("Recent", LibraryQuery(listOf(FilterPresets.AddedThisMonth)), 0, "b"))
        val copy = store.duplicate(made)
        store.rename("b", "Just in")
        assertTrue(file.exists())

        val again = store()
        again.open(file)
        assertEquals(listOf("Lossless favourites", "Lossless favourites (copy)", "Just in"), again.lists.value.map { it.name })
        assertEquals(favourites, again.byId(copy.id)?.query)
        assertEquals(1_000L, again.byId("a")?.created)
        assertEquals(2_000L, again.byId("b")?.changed)

        again.remove("a")
        val third = store()
        third.open(file)
        assertEquals(listOf(copy.id, "b"), third.lists.value.map { it.id })
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
        assertEquals("Favourites, lossless", LiveListDraft("  ", favourites).savedName)
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
