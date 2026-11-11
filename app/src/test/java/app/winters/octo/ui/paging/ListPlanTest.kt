package app.winters.octo.ui.paging

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.PlaylistEdits
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SongToAdd
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Every statement the list loaders run, gathered by walking each loader at
// the database, is planned on SQLite 3.21 (the oldest the app runs on): no
// sort in memory and no index built on the fly, no list built from a
// subquery, no walk of a song-sized table outside an index, and each walk
// led by its named index. Search is the one statement allowed a sorter: a
// full scan keeping only its LIMIT, as the search it replaces.
@RunWith(RobolectricTestRunner::class)
class ListPlanTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun track(n: Int, rating: Int = n % 6) = TrackEntity(
        id = "device:$n", sourceId = "device", nativeId = "$n", title = "Song $n", searchKey = "song $n", sortKey = "song $n",
        artist = "Artist ${n % 3}", artistId = "ar${n % 3}", album = "Album ${n % 4}", albumId = "al${n % 4}",
        trackNo = n % 10, discNo = 1, year = if (n % 5 == 0) null else 1990 + n % 7, durationMs = 1_000L * n, addedAt = n.toLong(),
        mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = n, relinkKey = "k$n",
        genre = if (n % 2 == 0) "Rock" else "Jazz", rating = rating, artistSortKey = "artist ${n % 3}", albumSortKey = "album ${n % 4}",
    )

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private suspend fun seed() {
        db.dao().insertTracks((1..40).map(::track))
        db.dao().insertAlbums(
            (0 until 4).map {
                app.winters.octo.catalog.AlbumEntity(
                    id = "al$it", sourceId = "device", nativeId = "$it", title = "Album $it", searchKey = "album $it", sortKey = "album $it",
                    artist = "Artist 1", artistId = "ar1", year = 1990 + it, songCount = 10, durationMs = 0, addedAt = 0, artwork = null,
                )
            },
        )
        (1..40 step 3).forEach { exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('device:$it', 'k$it', ${it % 4})") }
        (1..40 step 2).forEach {
            exec(
                "INSERT INTO play_total (trackId, albumId, artistId, plays, lastPlayedAt, recentAt, phonePlays, phoneLastAt, sortKey, artistSortKey) " +
                    "VALUES ('device:$it', 'al${it % 4}', 'ar${it % 3}', ${it % 5}, ${it * 10}, NULL, 0, NULL, 'song $it', 'artist ${it % 3}')",
            )
        }
        (1..20).forEach {
            val state = listOf("Done", "Queued", "Downloading", "Failed")[it % 4]
            exec(
                "INSERT INTO download (trackId, sourceId, serverId, path, sizeBytes, format, state, progress, addedAt, reason, title, artist, fileSize, titleKey) " +
                    "VALUES ('device:$it', 'server:h', 's$it', '', ${it * 100}, NULL, '$state', 0, $it, 'manual', 'Song $it', '', NULL, X'${"%04x".format(it)}')",
            )
        }
        db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
        PlaylistEdits(db, PlaylistSets(db)).add("p", { emit -> emit((1..30).map { SongToAdd("device:$it", "k$it") }) }, distinct = false, newOnly = false, now = 2)
    }

    // Runs a loader's every read once and answers the statements they ran.
    private suspend fun <T : Any> walk(loader: ListLoader<T>, idOf: (T) -> String?): List<String> {
        val log = db.counted
        log.reset()
        log.recording = true
        try {
            loader.count()
            loader.survey(null)
            val first = loader.after(null, 3)
            if (first.isNotEmpty()) {
                val key = first.last().key
                loader.survey(key)
                loader.after(key, 3)
                loader.before(key, 3)
                loader.keyAt(2)
                loader.rekey(key)
                idOf(first.first().row)?.let { id ->
                    loader.keyOf(id)
                    loader.present(listOf(id, "device:missing"))
                }
            }
        } finally {
            log.recording = false
        }
        return log.own.map { it.sql }.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }.distinct()
    }

    private val songSized = Regex("""\b(track|play_total|liked_track|download|playlist_item|source_track|lists_\w+)\b""")
    private val listFromRows = Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE)

    // A one-row probe inside a statement, such as an EXISTS beside a column.
    private val innerSelect = Regex("""\(SELECT 1 FROM [^()]*\)""", RegexOption.IGNORE_CASE)

    // What a statement filters its rows on, up to its LIMIT.
    private val scopeFilter = Regex("""\bWHERE\s+(.*?)\s+LIMIT\b""", RegexOption.IGNORE_CASE)

    private fun problems(name: String, statements: List<String>, lead: String?, sorterAllowed: Boolean = false): List<String> =
        OldSqlite.open().use { conn -> problemsIn(name, statements, lead, sorterAllowed) { sql -> OldSqlite.plan(conn, sql, nulls(sql)) } }

    private fun nulls(sql: String): List<Any?> = List(sql.count { it == '?' }) { null }

    // The statements' plans as `plan` gives them, checked line by line. With
    // `boundedScans`, a statement with no WHERE may walk a table outside an
    // index, since its LIMIT ends the walk.
    private fun problemsIn(
        name: String,
        statements: List<String>,
        lead: String?,
        sorterAllowed: Boolean,
        boundedScans: Boolean = false,
        plan: (String) -> List<String>,
    ): List<String> {
        val out = ArrayList<String>()
        var led = lead == null
        for (sql in statements) {
            val lines = plan(sql)
            if (lead != null && lines.any { lead in it }) led = true
            val outer = innerSelect.replace(sql, "()")
            val bounded = boundedScans && " LIMIT " in sql && !Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE).containsMatchIn(outer)
            for (line in lines) {
                val sorter = "USE TEMP B-TREE" in line
                val scan = line.startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                val bad = "AUTOMATIC" in line || (sorter && !(sorterAllowed && "LIMIT" in sql)) || (scan && !sorterAllowed && !bounded) ||
                    ("LIST SUBQUERY" in line && listFromRows.containsMatchIn(sql))
                if (bad) out += "$name: $line\n    $sql\n      $lines"
            }
        }
        if (!led) out += "$name: no statement led by $lead\n    " + statements.joinToString("\n    ") { "$it\n      ${plan(it)}" }
        return out
    }

    // A tap's small check, run once: the statements it ran.
    private suspend fun smallCheck(loader: ListLoader<*>): List<String> {
        val log = db.counted
        log.reset()
        log.recording = true
        try {
            loader.small()
        } finally {
            log.recording = false
        }
        return log.own.map { it.sql }.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) || it.trimStart().startsWith("WITH", ignoreCase = true) }.distinct()
    }

    private val trackId: (TrackEntity) -> String = { it.id }

    @Test
    fun songListsPlanOnTheirIndexes() = runBlocking {
        seed()
        val lists = SongLists(db.listsDao())
        val leads = mapOf(
            SongSort.Title to "index_track_title",
            SongSort.Artist to "index_track_by_artist",
            SongSort.Album to "index_track_by_album",
            SongSort.RecentlyAdded to "index_track_added",
            SongSort.Length to "index_track_length",
            SongSort.Year to "index_track_year",
            SongSort.Rating to "index_track_rating",
            SongSort.MostPlayed to "index_play_total_most",
            SongSort.RecentlyPlayed to "index_play_total_",
            SongSort.Liked to null,
            SongSort.DateLiked to null,
        )
        val found = ArrayList<String>()
        for ((sort, lead) in leads) {
            for (down in listOf(false, true)) {
                for (scope in listOf(SongScope.All, SongScope.Genre("Rock"), SongScope.Liked)) {
                    if (sort == SongSort.DateLiked && scope != SongScope.Liked) continue
                    val name = "$scope $sort ${if (down) "desc" else "asc"}"
                    val statements = walk(lists.loader(scope, SortOrder(sort, down)), trackId)
                    // A Genre list in a name order walks the genre's own index.
                    val want = if (scope is SongScope.Genre && sort == SongSort.Title) "index_track_genre" else lead.takeIf { scope == SongScope.All }
                    found += problems(name, statements, want)
                }
            }
        }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun pageListsPlanOnTheirIndexes() = runBlocking {
        seed()
        val dao = db.listsDao()
        val found = ArrayList<String>()
        found += problems("album", walk(albumLibraryLoader(dao, "al1"), trackId), "index_track_album_songs")
        // The page's order walks the artist's songs; album by album walks
        // each album's own songs.
        for (order in ArtistOrder.entries) {
            val lead = if (order == ArtistOrder.Page) "index_track_artist_songs" else "index_track_album_songs"
            found += problems("artist $order", walk(ArtistLoaderSource(dao).loader("ar1", order), trackId), lead)
        }
        val sets = PlaylistSets(db)
        val playlist = PlaylistLoader(db, sets, "p", WorkOwners.marked("vm:1"))
        found += problems("playlist", walk(playlist) { it.track.id }, "index_playlist_item_set")
        playlist.retire()
        for (sort in DownloadSort.entries) {
            for (down in listOf(false, true)) {
                val loader = DownloadsLists(dao).loader("server:h", SortOrder(sort, down))
                found += problems("downloads $sort $down", walk(loader) { it.trackId }, "index_download_")
            }
        }
        found += problems("album copy", walk(albumCopyLoader(dao, WorkOwners.marked("vm:2")), trackId), null)
        val folders = PhoneFolders(db, db.testMerge(null), FreeSpace { Long.MAX_VALUE })
        found += problems("folder copy", walk(folders.loader(WorkOwners.marked("vm:3")), trackId), null)
        found += problems("search", walk(SearchLoader(dao, "song", 1_000), trackId), null, sorterAllowed = true)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    // A tap's small check is one bounded statement a list (two for a list
    // filtered in Kotlin, and for a playlist, whose set is counted on its
    // index before any row is read), planned on 3.21 and on the SQLite of
    // newer phones alike: no sort in memory, no index built on the fly, no
    // walk of a song-sized table outside an index unless nothing filters it
    // (its LIMIT ends the walk), and each led by the index its list reads: a
    // genre its genre's index, liked songs their likes, an album or an
    // artist its own songs, a playlist its item set in play order,
    // downloads their done index. A list of the whole library reads it in
    // table order, whatever its sort: its parts (liked or not, played or
    // not, dated or not) are told by each row's own columns, never walked
    // for one by one. Search is again the one statement allowed a sorter.
    @Test
    fun smallChecksPlanOnTheirIndexes() = runBlocking {
        seed()
        val dao = db.listsDao()
        val lists = SongLists(dao)
        // `scope`, for a song list: the one filter its statement may hold
        // outside a one-row probe, so no part of it is walked for apart.
        class Case(val name: String, val loader: ListLoader<*>, val lead: String?, val sorter: Boolean = false, val scope: String? = null)
        val cases = ArrayList<Case>()
        for (sort in SongSort.entries) {
            for (down in listOf(false, true)) {
                for (scope in listOf(SongScope.All, SongScope.Genre("Rock"), SongScope.Liked)) {
                    if (sort == SongSort.DateLiked && scope != SongScope.Liked) continue
                    val (lead, filter) = when (scope) {
                        is SongScope.Genre -> "index_track_genre" to "t.genre = ?"
                        SongScope.Liked -> "liked_track" to ""
                        else -> null to ""
                    }
                    cases += Case("$scope $sort ${if (down) "desc" else "asc"}", lists.loader(scope, SortOrder(sort, down)), lead, scope = filter)
                }
            }
        }
        cases += Case("album", albumLibraryLoader(dao, "al1"), "index_track_album_songs")
        cases += Case("album copy", albumCopyLoader(dao, WorkOwners.marked("vm:2")), "lists_album_entry")
        cases += Case("artist ${ArtistOrder.Page}", ArtistLoaderSource(dao).loader("ar1", ArtistOrder.Page), "index_track_artist_songs")
        cases += Case("artist ${ArtistOrder.Albums}", ArtistLoaderSource(dao).loader("ar1", ArtistOrder.Albums), "index_track_album_songs")
        cases += Case("albums", GroupsLoader(dao, artists = false, groupIds = { listOf("al2", "al1") }, tablesRead = arrayOf("track")), null)
        cases += Case("artists", GroupsLoader(dao, artists = true, groupIds = { listOf("ar1") }, tablesRead = arrayOf("track")), null)
        val playlist = PlaylistLoader(db, PlaylistSets(db), "p", WorkOwners.marked("vm:1"))
        // The set's index by place on the one set's range, not one of the
        // set's other indexes nor a range over the playlist's every set.
        cases += Case("playlist", playlist, "index_playlist_item_set (playlistId=? AND itemSet=?)")
        for (sort in DownloadSort.entries) {
            for (down in listOf(false, true)) cases += Case("downloads $sort $down", DownloadsLists(dao).loader("server:h", SortOrder(sort, down)), "index_download_")
        }
        val folders = PhoneFolders(db, db.testMerge(null), FreeSpace { Long.MAX_VALUE })
        cases += Case("folder copy", folders.loader(WorkOwners.marked("vm:3")), null)
        cases += Case("search", SearchLoader(dao, "song", 1_000), null, sorter = true)
        val kept = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.IsNot, text = "Pop")))
        val inputs = FilterInputs(liked = { emptySet() }, plays = { emptyMap() }, songs = { ids -> db.dao().tracksByIdsUnordered(ids) })
        cases += Case("filtered", FilteredLoader(lists.loader(SongScope.All, SortOrder(SongSort.Title, false)), kept, inputs), "index_track_title")
        val found = ArrayList<String>()
        OldSqlite.open().use { conn ->
            for (case in cases) {
                val statements = smallCheck(case.loader)
                if (statements.isEmpty()) found += "${case.name}: its small check ran no statement"
                case.scope?.let { scope ->
                    for (sql in statements) {
                        val filters = scopeFilter.findAll(innerSelect.replace(sql, "()")).map { it.groupValues[1] }.toList()
                        if (filters.any { it != scope }) found += "${case.name}: filters on more than its scope ($filters)\n    $sql"
                    }
                }
                found += problemsIn("${case.name} on 3.21", statements, case.lead, case.sorter, boundedScans = true) { sql -> OldSqlite.plan(conn, sql, nulls(sql)) }
                found += problemsIn("${case.name} on current SQLite", statements, case.lead, case.sorter, boundedScans = true) { sql -> CurrentSqlite.plan(sql, nulls(sql)) }
            }
        }
        playlist.retire()
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    // Shuffle's pick in a phone folder reads index entries only.
    @Test
    fun aFoldersShuffleProbeReadsTheFolderIndexAlone() = runBlocking {
        val folders = PhoneFolders(db, db.testMerge(null), FreeSpace { Long.MAX_VALUE })
        val log = db.counted
        log.reset()
        log.recording = true
        folders.fileAt(PhoneFolder("Music", emptyList(), 1, listOf(FolderPlace(null, "Music/")), null), 0)
        log.recording = false
        val probe = log.own.map { it.sql }.first { "OFFSET" in it }
        val lines = OldSqlite.open().use { OldSqlite.plan(it, probe, List(probe.count { c -> c == '?' }) { null }) }
        assertTrue(lines.toString(), lines.any { "USING COVERING INDEX index_source_track_folder" in it })
    }
}
