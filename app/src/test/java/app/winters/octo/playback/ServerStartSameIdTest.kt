package app.winters.octo.playback

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.serverReads
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A start worked out from the server's answer with reads alone names the
// song the build's copy names at that place: the whole list's songs in
// order, a big list's first song, a shuffled pick, and the play queue's own
// entry, over randomized lists and named cases. The named cases: another
// entry's title keys file the song a match group takes (whole, and big with
// that entry on a later page); a link, and a link the server marks outside
// the library; a link whose song is gone; a find downloaded since, its song
// there and gone.
@RunWith(RobolectricTestRunner::class)
class ServerStartSameIdTest {
    private val db = openTestCatalog()
    private val songs = LinkedHashMap<String, Song>()
    private val server = ListServer(songs)
    private val owners = WorkOwners()
    private val reads = db.serverReads(server, page = 50, owners = owners)
    private val starts = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, owners, { reads })
    private var lists = 0
    private var plays = 100L

    @After
    fun close() {
        server.close()
        db.close()
    }

    private fun song(id: String, title: String, artist: String, external: Boolean = false, seconds: Int = 200) =
        Song(id, title = title, artist = artist, isExternal = external, duration = seconds).also { songs[id] = it }

    private fun list(entries: List<String>): String = "L${lists++}".also { server.lists[it] = entries }

    // The copy's shown rows, as the build makes it: (place, song) in order.
    private fun copied(spec: ListSpec, libraryOnly: Boolean = false): List<Pair<Long, String>> = runBlocking {
        val owner = WorkOwners.marked("copy:${lists++}")
        val (kind, id) = what(spec)
        reads.read(kind, id, owner)
        val loader = reads.loader(owner, libraryOnly)
        val out = ArrayList<Pair<Long, String>>()
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, 100)
            rows.forEach { out += ((it.key.values.first() as SqlValue.Int64).v to it.row.id) }
            if (rows.size < 100) break
            after = rows.last().key
        }
        reads.clear(owner)
        out
    }

    private fun start(spec: ListSpec, big: Boolean, shuffle: Boolean = false, seed: Long = 1): StartRead = runBlocking {
        starts.random = Random(seed)
        starts.read(spec, null, shuffle, big, null, plays++).also { reads.dropStart(owners.queue(plays - 1)) }
    }

    private fun placeOf(read: StartRead.Big): Long = (read.key!!.values.first() as SqlValue.Int64).v

    // The start equals the copy at its place, whole and big, first and
    // shuffled.
    private fun assertSame(spec: ListSpec, libraryOnly: Boolean = false, what: String) {
        val copy = copied(spec, libraryOnly)
        val whole = start(spec, big = false)
        if (copy.isEmpty()) {
            assertEquals(what, StartRead.Refused(NOTHING_TO_PLAY), whole)
            return
        }
        if (copy.size <= QUEUE_WINDOW && whole is StartRead.Whole) assertEquals(what, copy.map { it.second }, whole.ids)
        val first = start(spec, big = true).expect<StartRead.Big>()
        assertEquals("$what: first", copy.first(), placeOf(first) to first.trackId)
        for (seed in 1L..3L) {
            val pick = start(spec, big = true, shuffle = true, seed = seed).expect<StartRead.Big>()
            assertTrue("$what: shuffled pick ${placeOf(pick)} ${pick.trackId} is no shown row of $copy", (placeOf(pick) to pick.trackId) in copy)
        }
    }

    private fun library(tracks: List<TrackEntity>, serverId: (TrackEntity) -> String? = { null }) = db.putLibrary(tracks, serverId)

    @Test
    fun anotherEntrysKeyDecidesTheMatch() {
        // The library song files under "don't stop"; the entry asking for it
        // is titled "Dont Stop", whose own keys never file it. Another entry
        // (another artist's, so a find) files it.
        library(listOf(libraryTrack("c", "Don't Stop", "Band")))
        song("e", "Dont Stop", "Band")
        song("f", "Don't Stop", "Other")
        val withKey = list(listOf("e", "f"))
        val alone = list(listOf("e"))
        assertEquals("the copy takes the library song through the other entry's key", "c", copied(ListSpec.Online(OnlineKind.List, withKey)).first().second)
        assertEquals("with no other entry the copy finds it", "find:e", copied(ListSpec.Online(OnlineKind.List, alone)).first().second)
        assertSame(ListSpec.Online(OnlineKind.List, withKey), what = "whole, the key on another entry")
        assertSame(ListSpec.Online(OnlineKind.List, alone), what = "whole, alone")
        // Big, with the deciding entry on a later page of the answer.
        (0 until 1_200).forEach { song("fill$it", "Filler ${wordOf(it)}", "Filler") }
        val big = list(listOf("e") + (0 until 1_100).map { "fill$it" } + "f" + (1_100 until 1_200).map { "fill$it" })
        val bigAlone = list(listOf("e") + (0 until 1_200).map { "fill$it" })
        assertEquals("c", start(ListSpec.Online(OnlineKind.List, big), big = true).expect<StartRead.Big>().trackId)
        assertEquals("find:e", start(ListSpec.Online(OnlineKind.List, bigAlone), big = true).expect<StartRead.Big>().trackId)
        assertEquals("c", copied(ListSpec.Online(OnlineKind.List, big)).first().second)
    }

    // Two library songs are the same song as an entry, both filed by the
    // pool: the group takes the first in (searchKey, id) order, here the
    // one whose id sorts last.
    @Test
    fun theGroupTakesTheFirstOfThePoolsOrder() {
        library(listOf(libraryTrack("a1", "Hello!", "Band"), libraryTrack("z9", "Hello", "Band")))
        song("e", "Hello", "Band")
        song("f", "Hello!", "Other")
        val spec = ListSpec.Online(OnlineKind.List, list(listOf("e", "f")))
        assertEquals("the copy takes the first in the pool's order", "z9", copied(spec).first().second)
        assertSame(spec, what = "the pool's order")
        val big = ListSpec.Online(OnlineKind.List, list(listOf("e", "f") + (0 until 600).map { "e" }))
        assertEquals("z9", start(big, big = true).expect<StartRead.Big>().trackId)
    }

    @Test
    fun linksAndLinksOutsideTheLibrary() {
        library(listOf(libraryTrack("tl", "Linked", "Band"), libraryTrack("tx", "Outside", "Band"), libraryTrack("ty", "Twin", "Band"))) { "s-${it.id}" }
        song("s-tl", "Linked", "Band")
        // Marked outside the library: its link is not trusted.
        song("s-tx", "Outside", "Band", external = true)
        // Outside the library with no link, its title the library's.
        song("s-free", "Twin", "Band", external = true)
        val spec = ListSpec.Online(OnlineKind.List, list(listOf("s-tl", "s-tx", "s-free")))
        val copy = copied(spec)
        assertEquals("tl", copy[0].second)
        assertEquals("ty", copy.last().second)
        assertSame(spec, what = "links")
    }

    @Test
    fun aLinkWhoseSongIsGone() {
        library(listOf(libraryTrack("ok", "Fine", "Band")), serverId = { "s-ok" })
        runBlocking {
            db.sourceDao().putTracks(
                listOf(
                    SourceTrackEntity(
                        id = "copy-gone", sourceId = SERVER_SOURCE, nativeId = "s-gone", title = "Gone", searchKey = "gone", sortKey = "gone",
                        artist = "Band", artistId = "ar", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null, durationMs = 1,
                        addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = 0, relinkKey = "", genre = "", mergedId = "t-gone",
                    ),
                ),
            )
        }
        song("s-gone", "Gone", "Band")
        song("s-ok", "Fine", "Band")
        val spec = ListSpec.Online(OnlineKind.List, list(listOf("s-gone", "s-ok")))
        assertEquals(listOf(1L to "ok"), copied(spec))
        assertSame(spec, what = "a gone link")
    }

    @Test
    fun aFindDownloadedSinceAsItsSongThereAndGone() {
        library(listOf(libraryTrack("adopted", "Found then", "Band")))
        runBlocking {
            db.onlineDao().insert(
                listOf(
                    OnlineSongEntity("find:s-a", SERVER_SOURCE, "s-a", "Found then", "Band", "", null, null, 1, null, null, null, 1, adoptedId = "adopted"),
                    OnlineSongEntity("find:s-b", SERVER_SOURCE, "s-b", "Found gone", "Band", "", null, null, 1, null, null, null, 1, adoptedId = "missing"),
                ),
            )
        }
        song("s-a", "Found then", "Somebody else")
        song("s-b", "Found gone", "Band")
        val spec = ListSpec.Online(OnlineKind.List, list(listOf("s-a", "s-b")))
        assertEquals(listOf("adopted", "find:s-b"), copied(spec).map { it.second })
        assertSame(spec, what = "adopted finds")
    }

    // Randomized lists: links, links outside the library, title matches
    // through the pool, finds, repeats and finds downloaded since.
    @Test
    fun randomizedListsStartAsTheirCopy() {
        val random = Random(20261109)
        val tracks = (0 until 300).map { i -> libraryTrack("t$i", "Song ${wordOf(i)}", "Artist ${i % 9}", durationMs = (150 + i % 40) * 1000L) }
        // A third linked, a third matched by title alone, a third absent.
        library(tracks.filter { it.id.removePrefix("t").toInt() % 3 != 2 }) { t -> if (t.id.removePrefix("t").toInt() % 3 == 0) "s${t.id.removePrefix("t")}" else null }
        (0 until 400).forEach { i -> song("s$i", "Song ${wordOf(i)}", "Artist ${i % 9}", external = i % 17 == 5, seconds = 150 + i % 40) }
        repeat(12) { round ->
            val size = if (round % 3 == 0) 40 + random.nextInt(60) else 520 + random.nextInt(200)
            val entries = List(size) { "s${random.nextInt(400)}" }
            val spec = ListSpec.Online(OnlineKind.List, list(entries))
            assertSame(spec, what = "round $round")
            assertSame(spec.copy(libraryOnly = true), libraryOnly = true, what = "round $round, library only")
        }
    }

    // The play queue's own entry: the copy's first row of the song at its
    // place, and how far into it.
    @Test
    fun thePlayQueueStartsWhereItsCopyDoes() {
        val tracks = (0 until 900).map { libraryTrack("t$it", "Song ${wordOf(it)}", "Artist") }
        library(tracks.filter { it.id.removePrefix("t").toInt() % 2 == 0 }) { "s${it.id.removePrefix("t")}" }
        (0 until 900).forEach { song("s$it", "Song ${wordOf(it)}", "Artist") }
        server.queue = (0 until 800).map { "s${(it * 3) % 700}" }
        server.current = "s${(600 * 3) % 700}"
        server.position = 5_000
        val copy = copied(ListSpec.ServerQueue)
        val big = start(ListSpec.ServerQueue, big = true).expect<StartRead.Big>()
        val song = "t${(600 * 3) % 700}"
        assertEquals(song, big.trackId)
        assertEquals(copy.first { it.second == song }.first, placeOf(big))
        assertEquals(5_000L, big.positionMs)
    }

    private fun what(spec: ListSpec): Pair<ServerListKind, String?> = app.winters.octo.discovery.what(spec)
}
