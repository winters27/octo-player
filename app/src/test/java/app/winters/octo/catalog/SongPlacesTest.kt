package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.device.SCAN_TO_COME
import app.winters.octo.listening.timed
import app.winters.octo.server.COPY_TO_COME
import app.winters.octo.server.LISTED_TO_COME
import app.winters.octo.server.StagedCopy
import app.winters.octo.server.UNLISTED_TO_COME
import app.winters.octo.server.inAlbumOrder
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.Starred
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.FakeSubsonic
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import kotlin.random.Random

private const val SOURCE = "server:places.test"
private const val ACCOUNT = "acc-1"

// A copy keeps the places (seq) of its source's last copy where their order
// still holds, for songs, albums (listed ones in the list's order, the
// others after them) and artists. Run over the same changes as a copy
// numbering every row anew from 0, it makes exactly the same library, the
// same copies and the same order of songs, albums and artists in every
// source: only the numbers differ. An everyday copy rewrites no row's place
// but the rows that moved, and its save runs no statement for places when
// none moved; songs added again and again between two neighbors take
// places between them until the places run out, and then only the songs
// right after them take new ones.
@RunWith(RobolectricTestRunner::class)
class SongPlacesTest {
    private val worlds = ArrayList<World>()

    @After
    fun close() = worlds.forEach { it.close() }

    // A database copying one server, each save's steps counted over its
    // groups, with the phone's rows beside it when given.
    private inner class World(dense: Boolean, phone: SourceCatalog?) {
        val db = openTestCatalog()
        val steps: MutableList<Map<String, Long>> = Collections.synchronizedList(ArrayList())
        private val current = Collections.synchronizedMap(LinkedHashMap<String, Long>())
        @Volatile private var claimed = false
        val merge = db.testMerge(
            SOURCE,
            object : MergeHooks() {
                override suspend fun inSwap() {
                    claimed = true
                }

                override fun groupStep(turn: Long, step: String, rows: Long) {
                    current.merge(step, rows, Long::plus)
                }

                override fun applied(turns: Long, rows: Long, millis: Long) {
                    if (claimed) steps += LinkedHashMap(current)
                    claimed = false
                    current.clear()
                }
            },
        )
        val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE }).also { it.keepPlaces = !dense }

        init {
            worlds += this
            if (phone != null) kotlinx.coroutines.runBlocking { db.put(phone) }
        }

        suspend fun copyOf(library: Library) {
            val fake = FakeSubsonic(library, Starred())
            try {
                copy.copy(ACCOUNT, SOURCE, fake.client())
            } finally {
                fake.server.close()
            }
            merge.awaitStageClear(SOURCE)
        }

        // The library, and every source's songs, albums and artists in the
        // source's order, with their places left out.
        suspend fun view(): View {
            val work = db.workDao()
            return View(
                db.library(),
                work.trackPage(androidx.room.RoomRawQuery("SELECT * FROM source_track ORDER BY sourceId, seq")).map { it.copy(seq = 0) },
                work.albumPage(androidx.room.RoomRawQuery("SELECT * FROM source_album ORDER BY sourceId, seq")).map { it.copy(seq = 0) },
                work.artistPage(androidx.room.RoomRawQuery("SELECT * FROM source_artist ORDER BY sourceId, seq")).map { it.copy(seq = 0) },
            )
        }

        fun places(table: String = "source_track"): Map<String, Long> =
            db.query("SELECT id, seq FROM `$table` WHERE sourceId = ?", arrayOf<Any?>(SOURCE)).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
            }

        // The copy's albums' server ids, in the source's order.
        fun albumOrder(): List<String> =
            db.query("SELECT nativeId FROM source_album WHERE sourceId = ? ORDER BY seq", arrayOf<Any?>(SOURCE)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }

        fun close() = db.close()
    }

    // The same copies into a database keeping places and one numbering
    // anew, compared after each: the same library, copies and order.
    private suspend fun assertSameAsDense(seed: Long, libraries: List<Library>, phone: SourceCatalog?) {
        val kept = World(dense = false, phone)
        val dense = World(dense = true, phone)
        libraries.forEachIndexed { i, library ->
            kept.copyOf(library)
            dense.copyOf(library)
            val keptView = kept.view()
            val denseView = dense.view()
            val denseLibrary = denseView.library
            val keptLibrary = keptView.library
            assertTrue("seed $seed copy $i: the library is empty", denseLibrary.tracks.isNotEmpty())
            assertEquals("seed $seed copy $i: songs", denseLibrary.tracks, keptLibrary.tracks)
            assertEquals("seed $seed copy $i: albums", denseLibrary.albums, keptLibrary.albums)
            assertEquals("seed $seed copy $i: artists", denseLibrary.artists, keptLibrary.artists)
            assertEquals("seed $seed copy $i: each copy's library song", denseLibrary.mergedIds, keptLibrary.mergedIds)
            assertEquals("seed $seed copy $i: every source's songs in its order", denseView.tracks, keptView.tracks)
            assertEquals("seed $seed copy $i: every source's albums in its order", denseView.albums, keptView.albums)
            assertEquals("seed $seed copy $i: every source's artists in its order", denseView.artists, keptView.artists)
        }
    }

    private data class View(
        val library: MergedCatalog,
        val tracks: List<SourceTrackEntity>,
        val albums: List<SourceAlbumEntity>,
        val artists: List<SourceArtistEntity>,
    )

    // A changed library: songs taken out, added (into its albums and in a
    // new album, which the server lists anywhere in its list, by a new
    // artist), retitled (some moving within their album), moved to another
    // album or another track, and moved in the server's list (which moves
    // their album when they lead it).
    private fun changed(r: Random, library: Library, round: Int): Library {
        val songs = library.songs.toMutableList()
        repeat(songs.size / 40) { songs.removeAt(r.nextInt(songs.size)) }
        repeat(songs.size / 50) {
            val i = r.nextInt(songs.size)
            songs[i] = songs[i].copy(title = if (r.nextBoolean()) songs[i].title + " (Live)" else "Aa " + songs[i].title)
        }
        repeat(songs.size / 100) {
            val i = r.nextInt(songs.size)
            val to = library.albums[r.nextInt(library.albums.size)]
            songs[i] = songs[i].copy(albumId = to.id, album = to.name, artist = to.artist, artistId = to.artistId)
        }
        repeat(songs.size / 100) {
            val i = r.nextInt(songs.size)
            songs[i] = songs[i].copy(track = 1 + r.nextInt(30))
        }
        repeat(8) {
            val moved = songs.removeAt(r.nextInt(songs.size))
            songs.add(r.nextInt(songs.size + 1), moved)
        }
        repeat(songs.size / 30) { n ->
            val base = songs[r.nextInt(songs.size)]
            songs.add(r.nextInt(songs.size + 1), base.copy(id = "new$round-$n", title = "New Song $round $n"))
        }
        val album = Album(id = "al-new$round", name = "New Album $round", artist = "Fresh Band", artistId = "ar-fresh", songCount = 12, duration = 2_400)
        repeat(12) { n -> songs.add(r.nextInt(songs.size + 1), Song(id = "fresh$round-$n", title = "Fresh $n", album = album.name, albumId = album.id, artist = album.artist, artistId = album.artistId, track = n + 1, duration = 200)) }
        val albums = library.albums.toMutableList().apply { add(r.nextInt(size + 1), album) }
        return Library(songs, albums, library.artists + Artist(id = "ar-fresh", name = "Fresh Band", albumCount = 1))
    }

    @Test
    fun theMergeEqualsTheDenseNumberings() = timed(600) {
        for (seed in 1L..4L) {
            val r = Random(seed)
            val shape = GenShape(songs = 400 + 150 * seed.toInt(), albums = 40, artists = 12, giantAlbum = if (seed == 2L) 2_100 else 0)
            val first = LibraryGen.server(seed, shape)
            val phone = LibraryGen.phone(seed, first, shape).let { SourceCatalog(DEVICE, true, it.tracks, it.albums, it.artists) }
            val second = changed(r, first, 1)
            val third = changed(r, second, 2)
            val shuffled = LibraryGen.secondCopy(seed, third)
            assertSameAsDense(seed, listOf(first, second, third, shuffled), phone)
        }
    }

    // An everyday copy: songs taken out ahead of the rest (one of the giant
    // album's too), three retitled, five added in a new album at the end.
    // Every song in both copies keeps its place, so the swap moves none.
    // Only listed albums lose or change songs: an album the server does not
    // list is made up from its songs, so taking one out changes the rest.
    @Test
    fun anEverydayCopyKeepsEveryUnmovedSongsPlace() = timed(300) {
        val made = LibraryGen.server(5, GenShape(songs = 1_500, albums = 120, artists = 30, giantAlbum = 2_100, untitled = 0.0, noTrackNo = 0.0))
        val giant = made.songs.first { it.albumId == "al-giant" }
        val giantAlbum = Album(id = "al-giant", name = giant.album.orEmpty(), artist = giant.artist.orEmpty(), artistId = giant.artistId, songCount = 2_100, duration = 0, year = 2001)
        val first = Library(made.songs, made.albums + giantAlbum, made.artists)
        val listed = first.albums.mapTo(HashSet()) { it.id }
        val world = World(dense = false, phone = null)
        world.copyOf(first)
        val before = world.places()
        assertTrue("the places are spaced", before.values.sorted().zipWithNext().all { (a, b) -> b - a >= SEQ_SPAN })
        // Songs that do not lead their album in the server's list, so no
        // album moves, each the last of its album, so no other song's place
        // in its album moves either.
        val leads = first.songs.distinctBy { it.albumId ?: it.album }.mapTo(HashSet()) { it.id }
        val lasts = first.songs.filter { it.albumId in listed }.groupBy { it.albumId!! }.filterValues { it.size > 2 }.mapValues { (_, songs) -> inAlbumOrder(songs).last() }
        val out = (lasts.filterKeys { it != "al-giant" }.values.filter { it.id !in leads }.take(9) + lasts.getValue("al-giant")).mapTo(HashSet()) { it.id }
        assertEquals(10, out.size)
        val retitled = first.songs.filter { it.albumId in listed && it.id !in leads && it.id !in out && it.albumId != "al-giant" }.drop(20).take(3).mapTo(HashSet()) { it.id }
        val album = Album(id = "al-late", name = "Late Album", artist = "Late Band", artistId = "ar-late", songCount = 5, duration = 1_000)
        val late = (1..5).map { n -> Song(id = "late$n", title = "Late $n", album = album.name, albumId = album.id, artist = album.artist, artistId = album.artistId, track = n, duration = 200) }
        val second = Library(
            first.songs.filter { it.id !in out }.map { if (it.id in retitled) it.copy(title = it.title + " (Live)") else it } + late,
            first.albums + album,
            first.artists + Artist(id = "ar-late", name = "Late Band", albumCount = 1),
        )
        world.copyOf(second)
        val after = world.places()
        val kept = before.keys.filter { it in after }
        assertTrue("songs in both copies", kept.size > 3_000)
        val moved = kept.filter { before[it] != after[it] }
        assertEquals("songs that took a new place", emptyList<String>(), moved)
        val everyday = world.steps.last()
        assertTrue("$everyday", everyday.isNotEmpty())
        assertEquals("no statement for places runs: $everyday", emptyList<String>(), everyday.keys.filter { it.startsWith("update seq of") })
        assertEquals("$everyday", 3L, everyday["update source_track"])
        assertEquals("$everyday", 10L, everyday["delete source_track"])
        assertEquals("$everyday", 5L, everyday["insert source_track"])
    }

    // An everyday copy that adds an album the server lists first of all, by
    // an artist of its own: its album, its artist and its songs are the only
    // rows written, and no row of any table takes a new place. The library
    // and every order are the dense numbering's.
    @Test
    fun anEverydayCopyAddingAnAlbumListedFirstWritesOnlyItsRows() = timed(300) {
        val first = LibraryGen.server(14, GenShape(songs = 900, albums = 80, artists = 20, untitled = 0.0, noTrackNo = 0.0))
        val album = Album(id = "al-lead", name = "Aa Lead", artist = "Aaron Lead", artistId = "ar-lead", songCount = 5, duration = 1_000)
        val lead = (1..5).map { n -> Song(id = "lead$n", title = "Lead $n", album = album.name, albumId = album.id, artist = album.artist, artistId = album.artistId, track = n, duration = 200) }
        val second = Library(first.songs + lead, listOf(album) + first.albums, listOf(Artist(id = "ar-lead", name = "Aaron Lead", albumCount = 1)) + first.artists)
        val kept = World(dense = false, phone = null)
        val dense = World(dense = true, phone = null)
        for (library in listOf(first, second)) {
            kept.copyOf(library)
            dense.copyOf(library)
            assertEquals(dense.view(), kept.view())
        }
        val everyday = kept.steps.last()
        assertEquals("no statement for places runs: $everyday", emptyList<String>(), everyday.keys.filter { it.startsWith("update seq of") })
        assertEquals("$everyday", 1L, everyday["insert source_album"])
        assertEquals("$everyday", 1L, everyday["insert source_artist"])
        assertEquals("$everyday", 5L, everyday["insert source_track"])
        for (table in listOf("source_track", "source_album", "source_artist")) {
            assertEquals("$everyday", 0L, everyday["update $table"] ?: 0L)
            assertEquals("$everyday", 0L, everyday["delete $table"] ?: 0L)
        }
        assertEquals("the new album leads the source's albums", "al-lead", kept.albumOrder().first())
        val artists = kept.places("source_artist")
        assertTrue("the new artist leads the source's artists", artists.minBy { it.value }.key.endsWith(":ar-lead"))
    }

    // A listed album added in a later copy, at the end of the server's list,
    // takes its place among the listed albums by the list's order, though
    // the build reaches it first, and the albums the list does not hold
    // still follow every listed one.
    @Test
    fun aListedAlbumAddedLaterSortsAmongTheListed() = timed(300) {
        val first = LibraryGen.server(15, GenShape(songs = 700, albums = 60, artists = 15, unlisted = 0.2))
        val listedFirst = first.albums.mapTo(HashSet()) { it.id }
        val unlisted = first.songs.mapNotNull { it.albumId }.distinct().filter { it !in listedFirst }
        assertTrue("albums the list does not hold: $unlisted", unlisted.size >= 3)
        val late = Album(id = "al-late", name = "Zz Late", artist = "Late Band", artistId = "ar-late", songCount = 3, duration = 600)
        val songs = (1..3).map { n -> Song(id = "late$n", title = "Late $n", album = late.name, albumId = late.id, artist = late.artist, artistId = late.artistId, track = n, duration = 200) }
        val second = Library(songs + first.songs, first.albums + late, first.artists + Artist(id = "ar-late", name = "Late Band", albumCount = 1))
        val kept = World(dense = false, phone = null)
        val dense = World(dense = true, phone = null)
        for (library in listOf(first, second)) {
            kept.copyOf(library)
            dense.copyOf(library)
            assertEquals(dense.view(), kept.view())
        }
        val order = kept.albumOrder()
        val listed = second.albums.map { it.id }
        assertEquals("the listed albums in the list's order, first", listed, order.take(listed.size))
        assertTrue("then the albums the list does not hold", order.drop(listed.size).none { it in listed } && order.size > listed.size)
    }

    // A source whose places are one apart, as a copy made before places were
    // kept left them (and the upgrade, which numbers rows as they were): the
    // first copy to add a song among them spaces out the songs after it
    // once, and the next copy to add one there keeps every other place.
    @Test
    fun placesOneApartAreSpacedOutOnce() = timed(300) {
        val first = LibraryGen.server(12, GenShape(songs = 600, albums = 40, artists = 10, untitled = 0.0, noTrackNo = 0.0))
        val world = World(dense = true, phone = null)
        world.copyOf(first)
        assertEquals((0L until 600L).toList(), world.places().values.sorted())
        world.copy.keepPlaces = true
        // The album the copy builds first, so most songs come after it.
        val album = first.songs.firstNotNullOf { song -> first.albums.firstOrNull { it.id == song.albumId } }
        fun added(n: Int) = Song(id = "add$n", title = "Added $n", album = album.name, albumId = album.id, artist = album.artist, artistId = album.artistId, track = 90 + n, duration = 200)
        val second = Library(first.songs + added(1), first.albums, first.artists)
        world.copyOf(second)
        val spaced = world.places()
        assertTrue("songs spaced out", spaced.values.sorted().zipWithNext().count { (a, b) -> b - a >= SEQ_SPAN } > 300)
        val spacing = world.steps.last()
        assertTrue("the swap writes the new places: $spacing", (spacing["update seq of source_track"] ?: 0L) > 300)
        val third = Library(second.songs + added(2), second.albums, second.artists)
        world.copyOf(third)
        val after = world.places()
        assertEquals("songs that took a new place", emptyList<String>(), spaced.keys.filter { spaced[it] != after[it] })
        assertTrue(after.getValue("$SOURCE:add1") < after.getValue("$SOURCE:add2"))
    }

    // Four songs added copy after copy between the same two neighbors (the
    // fifth and sixth tracks of one album) take places between them; once
    // the places between run out, the songs after them take new places,
    // and never more than a few in one copy. The order stays the dense
    // numbering's throughout.
    @Test
    fun songsAddedBetweenTheSameNeighborsRenumberOnlyNearThem() = timed(600) {
        val hits = Album(id = "al-hits", name = "Hits", artist = "Band", artistId = "ar-band", songCount = 9, duration = 1_800)
        val base = (1..9).map { n -> Song(id = "hit$n", title = "Song ${'a' + n - 1}", album = hits.name, albumId = hits.id, artist = hits.artist, artistId = hits.artistId, track = n, duration = 200) }
        val rest = LibraryGen.server(9, GenShape(songs = 200, albums = 20, artists = 5))
        var library = Library(base + rest.songs, listOf(hits) + rest.albums, listOf(Artist(id = "ar-band", name = "Band", albumCount = 1)) + rest.artists)
        val kept = World(dense = false, phone = null)
        val dense = World(dense = true, phone = null)
        kept.copyOf(library)
        dense.copyOf(library)
        var renumbered = 0
        for (round in 1..25) {
            val added = (1..4).map { k ->
                Song(id = "in$round-$k", title = "Song e %02d-%d".format(round, k), album = hits.name, albumId = hits.id, artist = hits.artist, artistId = hits.artistId, track = 5, duration = 200)
            }
            library = Library(library.songs + added, library.albums, library.artists)
            val before = kept.places()
            kept.copyOf(library)
            dense.copyOf(library)
            val after = kept.places()
            val lost = before.keys.count { it in after && before[it] != after[it] }
            assertTrue("round $round: $lost songs took new places", lost <= 2)
            // The swap writes exactly the songs' new places, and runs no
            // statement for them when there are none.
            val step = kept.steps.last()["update seq of source_track"]
            assertEquals("round $round: ${kept.steps.last()}", if (lost == 0) null else lost.toLong(), step)
            renumbered += lost
            assertEquals("round $round", dense.view(), kept.view())
        }
        assertTrue("the places between ran out at least once", renumbered > 0)
    }

    // The walks of each table's old order run on (sourceId, seq), and the
    // look-up of a batch's old places on the key, on SQLite 3.21 and on
    // current SQLite: no sort, no index made on the fly, no scan.
    @Test
    fun theOldOrderIsWalkedOnItsIndex() {
        val walks = listOf(
            Triple("a copy's songs", "source_track", COPY_TO_COME),
            Triple("a scan's songs", "source_track", SCAN_TO_COME),
            Triple("a copy's listed albums", "source_album", LISTED_TO_COME),
            Triple("a copy's other albums", "source_album", UNLISTED_TO_COME),
            Triple("a scan's albums", "source_album", STAGE_ALBUM_TO_COME),
            Triple("artists", "source_artist", STAGE_ARTIST_TO_COME),
        )
        val statements = ArrayList<Triple<String, String, String?>>()
        for (table in listOf("source_track", "source_album", "source_artist")) statements += Triple("old places in $table", oldPlacesSql(table).format("?, ?, ?"), null)
        for ((name, table, toCome) in walks) {
            statements += Triple("$name to come between", toComeBetweenSql(table, toCome), "index_${table}_sourceId_seq")
            statements += Triple("$name first to come", toComeAfterSql(table, toCome), "index_${table}_sourceId_seq")
        }
        val problems = ArrayList<String>()
        for ((name, sql, index) in statements) {
            val args = List(sql.count { it == '?' }) { null }
            val lines = OldSqlite.open().use { conn -> OldSqlite.plan(conn, sql, args) } + CurrentSqlite.plan(sql, args)
            for (line in lines) {
                if ("TEMP B-TREE" in line || "AUTOMATIC" in line || (line.trimStart().startsWith("SCAN") && "USING" !in line)) problems += "$name: $line"
            }
            if (index != null && lines.none { index in it }) problems += "$name: not on $index: $lines"
        }
        assertTrue("${statements.size} statements planned", statements.size == 15)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
