package app.winters.octo.catalog

import app.winters.octo.data.FixRuns
import app.winters.octo.data.stepsOf
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.findDuplicates
import app.winters.octo.listening.PlayedIn
import app.winters.octo.listening.timed
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveRig
import app.winters.octo.livelists.TRACK_ID_PAGE_SQL
import app.winters.octo.livelists.Watching
import app.winters.octo.livelists.change
import app.winters.octo.livelists.liveLibrary
import app.winters.octo.playback.ARTIST_PAIR_PAGE_SQL
import app.winters.octo.playback.ARTIST_ROW_PAGE_SQL
import app.winters.octo.playback.GENRE_CLASS_PAGE_SQL
import app.winters.octo.playback.GENRE_ROW_PAGE_SQL
import app.winters.octo.playback.LIBRARY_SIZE_SQL
import app.winters.octo.playback.RadioRig
import app.winters.octo.playback.RadioSeeds
import app.winters.octo.playback.putTracks
import app.winters.octo.playback.radioTrack
import app.winters.octo.playlists.MatchKind
import app.winters.octo.playlists.MatchSong
import app.winters.octo.playlists.PHONE_FILE_PAGE_SQL
import app.winters.octo.playlists.allRows
import app.winters.octo.playlists.matchJobs
import app.winters.octo.playlists.matchLibrary
import app.winters.octo.playlists.M3uEntry
import app.winters.octo.playlists.phonePaths
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.SongCopyRule
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.library.health.FixSet
import app.winters.octo.ui.library.health.FixWalk
import app.winters.octo.ui.library.health.HealthLoader
import app.winters.octo.ui.library.health.HealthRecheck
import app.winters.octo.ui.library.health.HealthRig
import app.winters.octo.ui.library.health.HealthRow
import app.winters.octo.ui.library.health.SourceCopyHealth
import app.winters.octo.ui.library.health.healthLibrary
import app.winters.octo.ui.library.health.splitAlbumOf
import app.winters.octo.ui.paging.ListLoader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

// The library's other readers (the radio, live lists, Library health, the
// matching of files): every statement over a song-sized table is led by an
// index on SQLite 3.21, the oldest the app runs on, and on a current one,
// with no sort or temporary index in memory, no list built from a subquery
// and no walk of a whole table outside an index's order.
@RunWith(RobolectricTestRunner::class)
class QueryReadersPlanTest {
    // Each statement and the index that must lead it (null: any index).
    private val statements = mapOf(
        "library size" to (LIBRARY_SIZE_SQL to null),
        "genre class page" to (GENRE_CLASS_PAGE_SQL to "index_track_genre"),
        "genre row page" to (GENRE_ROW_PAGE_SQL to "index_track_genre"),
        "artist pair page" to (ARTIST_PAIR_PAGE_SQL to "index_track_artist_artistId_rating"),
        "artist row page" to (ARTIST_ROW_PAGE_SQL to "index_track_artist_artistId_rating"),
        "album first" to (ALBUM_FIRST_SQL to "index_track_album_songs"),
        "library order page" to (TRACK_ORDER_PAGE_SQL to "index_track_sortKey_id"),
        "library id page" to (TRACK_ID_PAGE_SQL to null),
        "live list page" to (
            "SELECT orderKey, trackId FROM live_song WHERE owner = ? AND gen = (SELECT gen FROM live_state WHERE owner = ?) AND orderKey > ? ORDER BY orderKey LIMIT ?" to null
            ),
        "live list by song" to ("SELECT orderKey, trackId, durationMs FROM live_song WHERE owner = ? AND gen = ? AND trackId > ? ORDER BY trackId LIMIT ?" to "index_live_song_track"),
        "health songs of a check" to (
            "SELECT k1, k2, albumId, disc, trackKey, k3, trackId FROM health_song WHERE owner = ? AND gen = ? AND (mask & ?) != 0 " +
                "AND (k1, k2, albumId, disc, trackKey, k3, trackId) > (?, ?, ?, ?, ?, ?, ?) ORDER BY k1, k2, albumId, disc, trackKey, k3, trackId LIMIT ?" to
                "index_health_song_order"
            ),
        "health bucket page" to (
            "SELECT rowid AS node, * FROM source_track WHERE sourceId = ? AND matchKey = ? AND (durationMs, rowid) > (?, ?) AND mergedId != '' " +
                "ORDER BY durationMs, rowid LIMIT ?" to "index_source_track_sourceId_matchKey"
            ),
        "match lookups of a key" to ("SELECT target, rank FROM match_lookup WHERE owner = ? AND key = ? AND target > ? ORDER BY target LIMIT ?" to null),
        "a fix's undo page" to (FIX_UNDO_PAGE_SQL to "sqlite_autoindex_fix_undo_1"),
    )

    // Album-sized, sorted keeping only their limit.
    private val albumSized = mapOf("artist's first album" to ARTIST_FIRST_ALBUM_SQL)

    private val listFromRows = Regex("""IN\s*\(\s*SELECT""", RegexOption.IGNORE_CASE)

    private val songSized = Regex(
        """\b(source_track|track|play_total|liked_track|live_song|health_song|health_member|health_node|health_group|health_tag|health_part|health_code|""" +
            """health_tally|health_quality|health_split|health_split_song|match_query|match_title|match_lookup|fix_undo)\b""",
    )

    private fun positional(sql: String): String =
        sql.replace(Regex("""IN \(:\w+\)"""), "IN (?, ?, ?)").replace(Regex(""":\w+"""), "?").replace(Regex("""\?\d+"""), "?")

    private fun plans(sql: String): List<String> {
        val text = positional(sql)
        val args = List(text.count { it == '?' }) { null }
        return OldSqlite.open().use { conn -> OldSqlite.plan(conn, text, args) } + CurrentSqlite.plan(text, args)
    }

    @Test
    fun everyReaderStatementIsLedByAnIndex() {
        val problems = mutableListOf<String>()
        for ((name, pair) in statements) {
            val (sql, index) = pair
            val lines = plans(sql)
            for (line in lines) {
                val wholeScan = line.startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                val bad = "TEMP B-TREE" in line || "AUTOMATIC" in line || wholeScan ||
                    ("LIST SUBQUERY" in line && listFromRows.containsMatchIn(sql))
                if (bad) problems += "$name: $line"
            }
            if (index != null && lines.none { index in it }) problems += "$name: not led by $index: $lines"
            problems += SongCopyRule.problems(sql, lines).map { "$name: a song's copies not looked up by the song: $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The phone's files, one a song, walk the source's (sourceId, seq) order;
    // the check for an earlier copy stays on the copies of one song.
    @Test
    fun phoneFilesWalkInScanOrder() {
        val lines = plans(PHONE_FILE_PAGE_SQL)
        assertTrue(lines.joinToString(), lines.none { "TEMP B-TREE" in it || "AUTOMATIC" in it })
        assertTrue(lines.joinToString(), lines.any { "index_source_track_sourceId_seq" in it })
        val subquery = Regex("""^SEARCH (TABLE source_track AS )?d USING INDEX index_source_track_mergedId \(mergedId=\?\)$""")
        assertTrue(lines.joinToString(), lines.any(subquery::matches))
    }

    // Every statement these files write, run: each reader is driven over a
    // small library with every statement recorded at the database, each
    // statement written in the files must have run at least once, and every
    // one that ran is planned as above. Sorts that stay within one album,
    // one set or the few saved lists are named with why.
    @Test
    fun everyStatementTheReadersRunIsLedByAnIndex() = timed(240) {
        val written = READER_FILES.flatMap { path -> statementsIn(File(mainSources, path)).map { path to it } }
        assertTrue("no statements found", written.size > 60)
        val ran = LinkedHashSet<String>()
        val dbs = List(5) { openTestCatalog() }
        try {
            dbs.forEach { it.counted.recording = true }
            driveHealth(dbs[0])
            driveLive(dbs[1])
            driveMatching(dbs[2])
            driveRadioAndSeeds(dbs[3])
            driveFixRuns(dbs[4])
            dbs.forEach { db -> db.counted.statements.filterNot { it.bookkeeping }.forEach { ran += it.sql.trim() } }
        } finally {
            dbs.forEach { it.close() }
        }
        val notRun = written.filter { (_, site) -> ran.none { site.pattern.containsMatchIn(it) } }
        assertTrue("never ran:\n" + notRun.joinToString("\n") { (path, site) -> "$path: ${site.text}" }, notRun.isEmpty())
        val problems = mutableListOf<String>()
        for (sql in ran) {
            val site = written.firstOrNull { (_, site) -> site.pattern.containsMatchIn(sql) } ?: continue
            if (!songSized.containsMatchIn(sql)) continue
            val lines = plans(sql)
            for (line in lines) {
                val wholeScan = line.startsWith("SCAN") && "USING" !in line && songSized.containsMatchIn(line)
                val sorts = "TEMP B-TREE" in line || "AUTOMATIC" in line
                val small = SMALL_SORTS.any { (start, plan, _) -> sql.startsWith(start) && line == plan }
                if (wholeScan || (sorts && !small)) problems += "${site.first}: $line\n    $sql"
            }
            problems += SongCopyRule.problems(sql, lines).map { "${site.first}: a song's copies not looked up by the song: $it\n    $sql" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun albumSizedSortsKeepTheirLimit() {
        val problems = mutableListOf<String>()
        for ((name, sql) in albumSized) {
            for (line in plans(sql)) {
                if ("AUTOMATIC" in line || (line.startsWith("SCAN") && songSized.containsMatchIn(line) && "USING" !in line)) problems += "$name: $line"
            }
            if ("LIMIT" !in sql) problems += "$name: no limit"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private val mainSources = File(System.getProperty("octo.mainSources")!!)

    // Library health: a build, each check's reads from the report, its copy
    // and the copy's reads, the copy cleared, a second build that lets the
    // first go, and the re-checks of a fix.
    private suspend fun driveHealth(db: CatalogDatabase) {
        db.healthLibrary(albums = 40)
        // Pages of two, so each page of the build reads on from the last.
        val rig = HealthRig(db, page = 2, cache = 16).keys()
        rig.built()
        for (check in HealthCheck.entries) {
            val loader = rig.build.loader(check, WorkOwners.marked("queue:${100 + check.ordinal}"))
            readAll(loader)
            loader.copy()
            readAll(loader)
            loader.clear()
            // Its page, with a server's ids and with none.
            for (source in listOf("device", null)) {
                val page = rig.build.lines(check, source)
                readAll(page) { (it as? HealthRow.Song)?.track?.id }
                rig.page(check, source, size = 13)
            }
            // Its Fix all: counted, previewed and sent.
            if (check != HealthCheck.NoLength && check != HealthCheck.NoTrackNumber) {
                rig.plans.count(check, "device", edit = true)
                rig.steps(rig.plans.open(check, "device", edit = true)!!)
            }
        }
        rig.plans.lookUpBatch(HealthCheck.NoTrackNumber, "device")
        // Fix all's walks a few sets at a time, so each reads on from a set.
        val gen = rig.build.shown()!!.gen
        for (check in listOf(HealthCheck.Duplicates, HealthCheck.SplitAlbums)) {
            val walk = FixWalk(db, rig.build, check, gen, "device", edit = true, page = 2)
            while (walk.next() != null) Unit
        }
        // No length's lines from the length index, and by the library's order.
        readAll(HealthLoader(rig.build, db, HealthCheck.NoLength, WorkOwners.marked("queue:99"), sortedMost = 0))
        val split = rig.page(HealthCheck.SplitAlbums, "device").firstNotNullOf { it.row as? HealthRow.Split }
        splitAlbumOf(db, split.gen, split.setId)
        rig.built()
        val recheck = HealthRecheck(db, rig.merge)
        val groups = findDuplicates(db.sourceDao().tracks("device").sortedBy { it.seq }, SourceCopyHealth)
        val splits = rig.today().report.splitAlbums
        assertTrue(groups.isNotEmpty() && splits.isNotEmpty())
        recheck.fixSheet("A", groups[0], null)
        recheck.joinSheet("B", splits[0]) { ids -> ids.associateWith { it } }
        recheck.samePage(listOf(FixSet(group = groups[0], steps = emptyList()), FixSet(album = splits[0], steps = emptyList())))
        // A song the library no longer has, followed through its copies.
        db.followAll(listOf(groups[0].copies[0].mergedId, groups[0].copies[0].id, "gone"))
    }

    // Live lists: one ordered by plays, one limited, each walked again
    // after a change; a change of rules; an editor's preview; more saved
    // lists than are kept; every read of a list; copies for a queue, from a
    // shown list, a preview and rules nothing shows, and for a playlist.
    private suspend fun driveLive(db: CatalogDatabase) {
        val tracks = db.liveLibrary(60)
        val rig = LiveRig(db, page = 5).started()
        coroutineScope {
            val byPlays = MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true)))
            val a = Watching(this, rig.picks, WorkOwners.marked("live:a"), "a", byPlays)
            val b = Watching(this, rig.picks, WorkOwners.marked("live:b"), "b", MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.Title.id), limit = 10)))
            a.after(0)
            b.after(0)
            db.change("UPDATE track SET title = 'Aaa moved', durationMs = 1234 WHERE id = ?", tracks[30].id)
            db.change("DELETE FROM track WHERE id = ?", tracks[7].id)
            a.after(1)
            b.after(1)
            byPlays.value = LibraryQuery(sort = QuerySort(SongSort.Length.id))
            a.after(2)
            val draftQuery = LibraryQuery(sort = QuerySort(SongSort.Year.id))
            val draft = Watching(this, rig.picks, rig.owners.draft(), null, MutableStateFlow(draftQuery))
            draft.after(0)
            val more = listOf(SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded).map { sort ->
                Watching(this, rig.picks, WorkOwners.marked("live:${sort.id}"), sort.id, MutableStateFlow(LibraryQuery(sort = QuerySort(sort.id))))
            }
            more.forEach { it.after(0) }
            readAll(rig.picks.loader(WorkOwners.marked("live:a")))
            rig.picks.copy(WorkOwners.marked("live:a"), LibraryQuery(), WorkOwners.marked("queue:1"))
            // A start's let-go asks whether its owner holds rows of a work
            // table before it clears them.
            for (table in OWNED_WORK_TABLES) db.holdsOwned(table, WorkOwners.marked("queue:1"))
            rig.picks.copy(null, LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true)), WorkOwners.marked("queue:2"))
            rig.picks.forSpec(null, draftQuery, WorkOwners.marked("queue:3")).also { spec ->
                readAll(spec)
                spec.copy()
                readAll(spec)
            }
            val playlist = rig.picks.copyForPlaylist("a", byPlays.value)
            rig.picks.rows(playlist).page(null, 5)
            rig.picks.clear(playlist)
            // The screens' reads: covers by every walk and from a shown
            // list, the editor's choices, a page and a Copy.
            val songs = LiveListSongs(rig.picks, db, rig.owners)
            for (query in listOf(LibraryQuery(), LibraryQuery(limit = 5), LibraryQuery(sort = QuerySort(SongSort.Title.id), limit = 5), LibraryQuery(sort = QuerySort(SongSort.Year.id)))) {
                songs.covers(null, query)
            }
            songs.covers("a", byPlays.value)
            songs.choices().first()
            readAll(songs.pages(WorkOwners.marked("live:a"))) { it.id }
            // One generation held for a reader, as the car reads it.
            songs.pinShown(WorkOwners.marked("live:a"))?.let { held ->
                readAll(songs.pages(WorkOwners.marked("live:a"), held)) { it.id }
                songs.unpinShown(WorkOwners.marked("live:a"), held.gen)
            }
            songs.copy(LiveList.new("A", byPlays.value, 0, "a")) { pages -> pages { } }
            songs.copy(LiveList.new("Z", LibraryQuery(sort = QuerySort(SongSort.Album.id)), 0, "z")) { pages -> pages { } }
            (listOf(a, b, draft) + more).forEach { it.job.cancel() }
        }
    }

    // Matching: a backup's songs and a playlist file's entries, each staged,
    // walked, finished and read back.
    private suspend fun driveMatching(db: CatalogDatabase) {
        val tracks = db.matchLibrary(120)
        val paths = db.phonePaths()
        matchJobs(db).run(MatchKind.Backup, SpaceFor.Restore, 60) { job ->
            job.stageSongs(0, tracks.take(30).mapIndexed { i, t -> MatchSong(t.relinkKey, t.title, t.artist, t.album, t.durationMs, value = i % 5) })
            job.stageSongs(1, tracks.takeLast(30).map { t -> MatchSong(title = t.title, artist = "") } + MatchSong(title = "Nothing like it"))
            job.walk()
            job.finish()
            job.allRows(0)
            job.allRows(1)
            job.counts(1)
            job.missed(1)
            // A restore's pages: each song once, with and without a range of
            // values, and a list read again dropped first.
            job.distinctFound(0)
            job.distinctFound(0, 1..5)
            job.firstFound(0, -1, 10)
            job.firstFound(0, -1, 10, 1..5)
            job.dropList(1)
        }
        matchJobs(db).run(MatchKind.Playlist, SpaceFor.KeepList, 40) { job ->
            val entries = paths.values.take(20).mapIndexed { i, path -> M3uEntry(path, line = i) } +
                tracks.take(20).mapIndexed { i, t -> M3uEntry("x/$i.mp3", seconds = (t.durationMs / 1000).toInt(), artist = t.artist.ifBlank { null }, title = t.title, line = 20 + i) }
            job.stageEntries(entries)
            job.walk()
            job.finish()
            job.allRows(0)
            job.missed(0)
            job.missedShown()
        }
    }

    // The radio's walks, and a radio's seed with and without stored plays.
    private suspend fun driveRadioAndSeeds(db: CatalogDatabase) {
        db.putTracks((0 until 40).map { i -> radioTrack("r$i", sortKey = "k%02d".format(i), artist = "Artist ${i % 4}", artistId = "ar${i % 4}", genre = listOf("Rock", "rock", "Jazz")[i % 3], albumId = "al${i % 5}") })
        val rig = RadioRig(db).started()
        rig.radio.songsLike(listOf(db.dao().tracksByIdsUnordered(listOf("r1")).single()), 10, random = Random(1))
        RadioSeeds(db).of(PlayedIn.Album("al1"))
        RadioSeeds(db).of(PlayedIn.Artist("ar2"))
    }

    // A fix run and its undo.
    private suspend fun driveFixRuns(db: CatalogDatabase) {
        val runs = FixRuns(db, WorkOwners(), 5)
        val run = runs.run(stepsOf((0 until 12).map { FixStep.Remove("s$it", "Song $it") }), send = { LibraryActionResult(it.id, it.action, "applied") })
        runs.run(runs.steps(run.undo!!), send = { LibraryActionResult(it.id, it.action, "applied") }, keepUndo = false, putsBack = run.undo)
        runs.forget()
    }

    // Every read a list loader answers.
    private suspend fun readAll(loader: ListLoader<String>) = readAll(loader) { it }

    private suspend fun <T : Any> readAll(loader: ListLoader<T>, idOf: (T) -> String?) {
        loader.count()
        loader.survey(null)
        val page = loader.after(null, 5)
        val last = page.lastOrNull() ?: return
        loader.before(last.key, 3)
        loader.survey(last.key)
        loader.keyAt(2)
        page.firstNotNullOfOrNull { idOf(it.row) }?.let { loader.keyOf(it) }
        loader.rekey(last.key)
        loader.present(page.mapNotNull { idOf(it.row) })
        loader.after(last.key, 5)
    }
}

// The files whose statements are checked.
private val READER_FILES = listOf(
    "app/winters/octo/livelists/LivePicks.kt",
    "app/winters/octo/livelists/LiveLoader.kt",
    "app/winters/octo/livelists/LiveListSongs.kt",
    "app/winters/octo/ui/library/health/FixAllPlans.kt",
    "app/winters/octo/ui/library/health/HealthBuild.kt",
    "app/winters/octo/ui/library/health/HealthLines.kt",
    "app/winters/octo/ui/library/health/HealthLoader.kt",
    "app/winters/octo/ui/library/health/HealthRecheck.kt",
    "app/winters/octo/playlists/MatchJob.kt",
    "app/winters/octo/playback/LibraryRadio.kt",
    "app/winters/octo/catalog/ReaderDao.kt",
    "app/winters/octo/catalog/ReaderSql.kt",
    "app/winters/octo/catalog/WorkOwners.kt",
)

// Sorts that stay small: how the statement starts, the plan's line, and why.
private val SMALL_SORTS = listOf(
    Triple(
        "SELECT g.k1, g.k2, g.bestId, -m.sound, -m.tags, m.copyId, m.trackId FROM health_group g",
        "USE TEMP B-TREE FOR RIGHT PART OF ORDER BY",
        "the sets come in their index's order, and each set's own copies are sorted: one set at a time",
    ),
    Triple(
        "SELECT s.k1, s.k2, s.leadAlbum, ss.part, ss.disc, ss.trackKey, ss.titleKey, ss.trackId FROM health_split s",
        "USE TEMP B-TREE FOR RIGHT PART OF ORDER BY",
        "the split albums come in their index's order, and each album's own songs are sorted: one album at a time",
    ),
    Triple("SELECT value FROM health_tally WHERE gen = ? AND albumId = ?", "USE TEMP B-TREE FOR ORDER BY", "one album's credited artists"),
    Triple("SELECT a.id FROM album a WHERE a.artistId = ?", "USE TEMP B-TREE FOR ORDER BY", "one artist's albums"),
    Triple(
        "SELECT sortKey, id FROM track INDEXED BY index_track_length",
        "USE TEMP B-TREE FOR ORDER BY",
        "No length's lines read this way only while a count that stops one past it finds at most NO_LENGTH_SORTED_MOST songs with no length: " +
            "a bounded sort of those few, where the library's order would walk every song for them",
    ),
)

// One statement a file writes, as written, and a pattern that finds it at
// the start of a statement run: each template in it stands for any text,
// each named argument for `?`, each run of spaces for any.
private class Site(val text: String, val pattern: Regex)

private val STATEMENT_START = Regex("""^\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\b""")

// The statements in a Kotlin file: each string literal that opens with a
// statement's first word, joined with the literals `+` adds after it.
private fun statementsIn(file: File): List<Site> {
    val text = file.readText()
    val sites = ArrayList<Site>()
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            text.startsWith("/*", i) -> i = text.indexOf("*/", i) + 2
            text[i] == '\'' -> i = text.indexOf('\'', i + if (text[i + 1] == '\\') 2 else 1) + 1
            text[i] == '"' -> {
                require(!text.startsWith("\"\"\"", i)) { "a raw string in $file" }
                val (pieces, end) = literal(text, i)
                i = end
                val head = pieces.firstOrNull() as? String
                if (head == null || !STATEMENT_START.containsMatchIn(head)) continue
                while (true) {
                    var k = i
                    while (k < text.length && text[k].isWhitespace()) k++
                    if (k >= text.length || text[k] != '+') break
                    k++
                    while (k < text.length && text[k].isWhitespace()) k++
                    if (k >= text.length || text[k] != '"') break
                    val (more, next) = literal(text, k)
                    pieces += more
                    i = next
                }
                sites += Site(pieces.joinToString("") { it as? String ?: "\${}" }, patternOf(pieces))
            }
            else -> i++
        }
    }
    return sites
}

// A string literal from its opening quote: its text and templates (null
// for a template), and where it ends.
private fun literal(text: String, start: Int): Pair<MutableList<String?>, Int> {
    val pieces = ArrayList<String?>()
    val run = StringBuilder()
    fun flush() {
        if (run.isNotEmpty()) pieces += run.toString()
        run.clear()
    }
    var i = start + 1
    while (true) {
        val c = text[i]
        when {
            c == '\\' -> {
                if (text[i + 1] == 'u') {
                    run.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 6
                } else {
                    run.append(
                        when (val e = text[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> e
                        },
                    )
                    i += 2
                }
            }
            c == '"' -> {
                flush()
                return pieces to i + 1
            }
            c == '$' && text[i + 1] == '{' -> {
                flush()
                pieces += null
                i = afterBraces(text, i + 1)
            }
            c == '$' && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                flush()
                pieces += null
                i++
                while (text[i].isLetterOrDigit() || text[i] == '_') i++
            }
            else -> {
                run.append(c)
                i++
            }
        }
    }
}

// Past the brace that closes the one at `open`, skipping strings inside.
private fun afterBraces(text: String, open: Int): Int {
    var depth = 0
    var i = open
    while (true) {
        when (text[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return i + 1
            '"' -> {
                i = literal(text, i).second
                continue
            }
        }
        i++
    }
}

private fun patternOf(pieces: List<String?>): Regex {
    val out = StringBuilder("^")
    for (piece in pieces) {
        if (piece == null) {
            out.append(".*?")
            continue
        }
        Regex("""\s+|:[A-Za-z_]\w*|\?\d*|[^\s:?]+|[:?]""").findAll(piece).forEach { m ->
            val t = m.value
            when {
                t.isBlank() -> out.append("""\s*""")
                t.startsWith(":") && t.length > 1 -> out.append("""\?\d*""")
                t.startsWith("?") -> out.append("""\?\d*""")
                else -> out.append(Regex.escape(t))
            }
        }
    }
    return Regex(out.toString())
}
