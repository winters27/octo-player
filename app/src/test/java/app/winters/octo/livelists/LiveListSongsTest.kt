package app.winters.octo.livelists

import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.mosaicCovers
import app.winters.octo.catalog.naturalSortKey
import app.winters.octo.catalog.read
import app.winters.octo.listening.timed
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.query.decadesIn
import app.winters.octo.query.foldText
import app.winters.octo.query.genresIn
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.TrackFields
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A labeled pin. Live lists as the screens read them, from what LivePicks
// keeps: a shown list's songs page by page are today's picks; its covers,
// found by a walk that stops once it has them or keeps only an album's
// first place, are today's four; the editor's genres, artists and decades
// come from one walk of the library (an artist spelt two ways by the same
// name kept as its first song in library order spells it, declared); a
// Copy or the car's songs are today's picks, read back from a copy of
// their own a page at a time, with nothing made for a list that picks
// nothing.
@RunWith(RobolectricTestRunner::class)
class LiveListSongsTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private suspend fun rig(): Pair<LiveRig, LiveListSongs> {
        db.liveLibrary(400)
        // Covers on most songs, none on two albums, and some songs without.
        db.change("UPDATE track SET artwork = 'art-' || albumId WHERE rowid % 4 != 2 AND albumId NOT IN ('al3', 'al5')")
        val rig = LiveRig(db, page = 37).started()
        return rig to LiveListSongs(rig.picks, db, rig.owners)
    }

    private val queries = listOf(
        LibraryQuery(),
        LibraryQuery(listOf(FilterPresets.Lossless)),
        LibraryQuery(limit = 25),
        LibraryQuery(listOf(FilterPresets.Favourites), limit = 3),
        LibraryQuery(sort = QuerySort(SongSort.Title.id), limit = 50),
        LibraryQuery(sort = QuerySort(SongSort.Year.id, true)),
        LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true)),
        LibraryQuery(listOf(FilterPresets.Favourites), sort = QuerySort(SongSort.Artist.id)),
        LibraryQuery(listOf(FilterPresets.playedAtLeast(3)), sort = QuerySort(SongSort.RecentlyPlayed.id, true), limit = 10),
    )

    @Test
    fun aListsCoversAreTodays() = timed(180) {
        val (rig, songs) = rig()
        for (query in queries) {
            assertEquals("$query", mosaicCovers(rig.today(query).map { it.albumId to it.artwork }), songs.covers(null, query))
        }
        // With no order and no limit, the walk stops at the fourth album.
        db.counted.reset()
        db.counted.recording = true
        songs.covers(null, LibraryQuery())
        db.counted.recording = false
        assertEquals(1, db.counted.own.count { it.sql.startsWith("SELECT * FROM track WHERE (sortKey, id) >") })
        // A list shown reads its own songs for them.
        coroutineScope {
            val query = MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.Title.id, true)))
            val shown = Watching(this, rig.picks, songs.owner("a"), "a", query)
            shown.after(0)
            db.counted.reset()
            db.counted.recording = true
            assertEquals(mosaicCovers(rig.today(query.value).map { it.albumId to it.artwork }), songs.covers("a", query.value))
            db.counted.recording = false
            assertTrue(db.counted.own.none { it.sql.startsWith("SELECT * FROM track WHERE (sortKey, id) >") })
            shown.job.cancel()
        }
    }

    @Test
    fun aShownListsPagesAndItsCopyAreTodays() = timed(180) {
        val (rig, songs) = rig()
        coroutineScope {
            for ((i, query) in queries.withIndex()) {
                val today = rig.today(query).map { it.id }
                val owner = songs.owner("l$i")
                val shown = Watching(this, rig.picks, owner, "l$i", MutableStateFlow(query))
                shown.after(0)
                // Its page, read a few songs at a time.
                val pages = songs.pages(owner)
                val read = ArrayList<String>()
                var after: RowKey? = null
                while (true) {
                    val page = pages.after(after, 9)
                    read += page.map { it.row.id }
                    if (page.size < 9) break
                    after = page.last().key
                }
                assertEquals("$query", today, read)
                assertEquals(today.size, pages.count())
                // A Copy of it.
                val list = LiveList.new("List $i", query, 0, "l$i")
                val made = ArrayList<NewItem>()
                val count = songs.copy(list) { pages -> pages { made += it } }
                assertEquals("$query", today.size, count)
                assertEquals("$query", today, made.map { it.trackId })
                shown.job.cancel()
            }
        }
        // A list nothing shows is walked for its Copy; one that picks
        // nothing makes nothing.
        val unseen = LiveList.new("Unseen", LibraryQuery(sort = QuerySort(SongSort.Length.id)), 0, "u")
        val made = ArrayList<NewItem>()
        assertEquals(rig.today(unseen.query).size, songs.copy(unseen) { pages -> pages { made += it } })
        assertEquals(rig.today(unseen.query).map { it.id }, made.map { it.trackId })
        var created = false
        val none = LiveList.new("None", LibraryQuery(text = "nothing is called this"), 0, "n")
        assertEquals(0, songs.copy(none) { created = true })
        assertTrue(!created)
        // No copy is left behind.
        val (from, until) = app.winters.octo.catalog.WorkOwners.kindRange("copy")
        assertEquals(0L, db.read("SELECT COUNT(*) FROM live_song WHERE owner >= ? AND owner < ?", from, until) { it.getLong(0) }.single())
    }

    @Test
    fun theEditorsChoicesAreTheLibrarys() = timed(120) {
        val (_, songs) = rig()
        val library = db.dao().tracks().first().sortedWith { a, b -> SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }
        val fields = TrackFields()
        val choices = songs.choices().first()
        assertEquals(genresIn(library, fields), choices.genres)
        assertEquals(decadesIn(library, fields), choices.decades)
        // Each artist once, A to Z, spelt as its first song spells it.
        val firsts = LinkedHashMap<String, String>()
        library.forEach { song -> song.artist.takeIf(String::isNotBlank)?.let { firsts.getOrPut(foldText(it)) { it.trim() } } }
        assertEquals(firsts.values.sortedBy(::naturalSortKey), choices.artists)
        assertTrue(choices.artists.isNotEmpty() && choices.genres.isNotEmpty() && choices.decades.isNotEmpty())
    }
}
