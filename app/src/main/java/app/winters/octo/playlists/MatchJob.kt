package app.winters.octo.playlists

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.MatchLookupEntity
import app.winters.octo.catalog.MatchQueryEntity
import app.winters.octo.catalog.MatchTitleEntity
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SongIdentity.ParsedSong
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.followAll
import app.winters.octo.catalog.intOrNull
import app.winters.octo.catalog.longOrNull
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.matchKey
import app.winters.octo.catalog.read
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.catalog.trackOrderPage
import app.winters.octo.catalog.write
import app.winters.octo.device.relativeFile
import app.winters.octo.discovery.parsedSong
import app.winters.octo.discovery.sameSong
import app.winters.octo.discovery.sameSongLengths
import app.winters.octo.discovery.titleKeys
import app.winters.octo.discovery.versionOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

// The disk one staged entry takes with its keys, its title questions and
// their lookups and the words a missed entry shows, measured on long titles
// and paths (MatchDiskTest: 3,973). Every owner leads with the launch's mark
// (WorkOwners), and a staged entry repeats its owner in each of its keys and
// index entries. A job asks for twice that free, for the write-ahead log,
// with a tenth more to spare.
internal const val MATCH_BYTES_PER_ENTRY = 4_150L

// How many rows each step of a job reads or writes at a time.
internal const val MATCH_PAGE = 500

// Which file a job matches.
enum class MatchKind(val code: Int) { Backup(0), Playlist(1) }

// One song a backup names, as it names it, and what it carries beside it
// (a rating's stars).
data class MatchSong(
    val relinkKey: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val value: Int = 0,
)

// One entry of a list a job matched, in file order: what it carries beside
// the song, and the song it is now.
data class MatchRow(val entry: Long, val value: Int, val trackId: String)

// A page of a list's rows, and the entry the next page starts after (null
// at the end).
data class MatchPage(val rows: List<MatchRow>, val next: Long?)

// Points a test can stop a job at. Each does nothing in the app.
internal open class MatchHooks {
    // After each page of the library walk is written.
    open suspend fun page(n: Int) {}

    // A page of a backup's walk read its songs and the entries they reached
    // for the same song: how many of each it parsed for that.
    open fun parsed(songs: Int, entries: Int) {}

    // A page of the walk compared `pairs` of a song and an entry (a
    // backup's) or a song and a title question (a playlist file's).
    open fun compared(pairs: Int) {}
}

// Matching jobs, one at a time: a backup's songs or a playlist file's
// entries found in the library. A job stages the file's entries in work
// tables under its own owner, walks the library once in library order,
// checking each song against them, and finishes each entry's answer. The
// job's rows are cleared when it ends. Taken before the source-write gate
// and the merge lock, never inside them or a transaction; a playlist made
// from a job is written under it.
@Singleton
class MatchJobs internal constructor(
    private val db: CatalogDatabase,
    private val owners: WorkOwners,
    private val freeSpace: FreeSpace,
    private val page: Int,
) {
    @Inject constructor(db: CatalogDatabase, owners: WorkOwners, freeSpace: FreeSpace) : this(db, owners, freeSpace, MATCH_PAGE)

    private val lock = Mutex()

    internal var hooks = MatchHooks()

    // Runs `block` as the one job. `doing` words a full disk; `entries` is
    // how many entries the job will stage, for the space it needs. Throws
    // NotEnoughSpace when the disk is short, before staging anything, or
    // when it fills while the job writes.
    suspend fun <T> run(kind: MatchKind, doing: SpaceFor, entries: Long, block: suspend (MatchJob) -> T): T = lock.withLock {
        val needed = entries * MATCH_BYTES_PER_ENTRY * 2 * 11 / 10
        if (freeSpace.bytes() < needed) throw NotEnoughSpace(needed, doing)
        val job = MatchJob(db, owners.match(), kind, page, hooks)
        withContext(Dispatchers.IO) {
            try {
                block(job)
            } catch (e: SQLiteFullException) {
                throw NotEnoughSpace(null, doing)
            } finally {
                withContext(NonCancellable) { job.clear() }
            }
        }
    }

    // A job whose steps the listener takes one at a time (a backup read,
    // then planned, then restored): its rows stay between them, until
    // `close`. Each step runs as the one job at that moment.
    fun open(kind: MatchKind): MatchJob = MatchJob(db, owners.match(), kind, page, hooks)

    // One step of an open job, as `run` runs a job: `entries` it will stage
    // sizes the room asked for first; a full disk while it writes throws
    // NotEnoughSpace with `doing`.
    suspend fun <T> step(doing: SpaceFor, entries: Long = 0, block: suspend () -> T): T = lock.withLock {
        val needed = entries * MATCH_BYTES_PER_ENTRY * 2 * 11 / 10
        if (needed > 0 && freeSpace.bytes() < needed) throw NotEnoughSpace(needed, doing)
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: SQLiteFullException) {
                throw NotEnoughSpace(null, doing)
            }
        }
    }

    // Lets an open job's rows go.
    suspend fun close(job: MatchJob) = withContext(NonCancellable + Dispatchers.IO) { job.clear() }
}

// One job's staged entries, its walk and its answers.
class MatchJob internal constructor(
    private val db: CatalogDatabase,
    val owner: String,
    val kind: MatchKind,
    private val page: Int,
    private val hooks: MatchHooks,
) {
    private val reader = db.readerDao()
    private val work = db.workDao()
    private var next = 0L

    // Whether a swap landed during the walk.
    var crossed = false
        private set

    // Stages a page of a backup list's songs, in file order.
    suspend fun stageSongs(list: Int, songs: List<MatchSong>) {
        check(kind == MatchKind.Backup)
        val queries = ArrayList<MatchQueryEntity>(songs.size)
        val lookups = ArrayList<MatchLookupEntity>()
        for (song in songs) {
            val id = next++
            queries += MatchQueryEntity(
                owner, id, kind.code, list, song.value, song.relinkKey, song.title, song.artist, song.album, song.durationMs,
                wholeKey = wholeKey(song.title, song.artist, song.album), nameKey = "", parts = "",
            )
            if (song.title.isNotBlank() && song.artist.isNotBlank()) {
                SongIdentity.titleLookupKeys(song.title, song.artist).forEach { lookups += MatchLookupEntity(owner, it, target(id, 0), 0) }
            }
        }
        insert(queries, emptyList(), lookups)
    }

    // Stages a page of a playlist file's entries, in file order. An entry
    // keeps the words its file's missed lines show it by where a backup's
    // song keeps its album, which only a backup's matching reads.
    suspend fun stageEntries(entries: List<M3uEntry>) {
        check(kind == MatchKind.Playlist)
        val queries = ArrayList<MatchQueryEntity>(entries.size)
        val titles = ArrayList<MatchTitleEntity>()
        val lookups = ArrayList<MatchLookupEntity>()
        for (entry in entries) {
            val id = next++
            val parts = pathParts(entry.location).map { it.lowercase() }
            queries += MatchQueryEntity(
                owner, id, kind.code, 0, entry.line, "", entry.title.orEmpty(), entry.artist.orEmpty(), entry.shown(), (entry.seconds ?: 0) * 1000L,
                wholeKey = "", nameKey = parts.lastOrNull().orEmpty(), parts = parts.joinToString(PART_BREAK),
            )
            m3uQuestions(entry).forEachIndexed { at, question ->
                val rank = at + 1
                titles += MatchTitleEntity(
                    owner, id, rank, question.title, question.artist.orEmpty(), question.lengthMs,
                    versions = versionOf(question.title).sorted().joinToString(PART_BREAK), hasArtist = question.hasArtist,
                )
                titleKeys(question.title).forEachIndexed { keyRank, key -> lookups += MatchLookupEntity(owner, key, target(id, rank), keyRank) }
            }
        }
        insert(queries, titles, lookups)
    }

    private suspend fun insert(queries: List<MatchQueryEntity>, titles: List<MatchTitleEntity>, lookups: List<MatchLookupEntity>) =
        withContext(WriteLabels.of("match stage")) {
            db.withTransaction {
                reader.putQueries(queries)
                if (titles.isNotEmpty()) reader.putTitles(titles)
                lookups.chunked(page).forEach { reader.putLookups(it) }
            }
        }

    // The one walk of the library, in library order, then of the phone's
    // files for a playlist file's paths. Each page's answers are written in
    // one transaction; a swap that landed since the walk began only marks
    // the job crossed, and its found ids are followed when read.
    suspend fun walk() {
        val baseline = db.withTransaction { work.swapCount() }
        var after = "" to ""
        var n = 0
        while (true) {
            val songs = reader.trackOrderPage(after.first, after.second, page)
            if (songs.isEmpty()) break
            val write = if (kind == MatchKind.Backup) backupPage(songs) else playlistPage(songs)
            commit(baseline, write)
            hooks.page(++n)
            if (songs.size < page) break
            after = songs.last().let { it.sortKey to it.id }
        }
        if (kind == MatchKind.Playlist) walkFiles(baseline)
    }

    private suspend fun commit(baseline: Long, write: () -> Unit) = withContext(WriteLabels.of("match page")) {
        db.withTransaction {
            if (work.swapCount() != baseline) crossed = true
            write()
        }
    }

    // A page of the library against a backup's entries: the first song
    // with each relink key, the closest with each whole key, and the
    // closest of the same song by title among those sharing a title key.
    // Each of the page's songs, and each entry they reach, is parsed once
    // for the page, however many of the others it is compared with: a title
    // many songs share ("Intro", "Untitled") meets thousands of entries, so
    // a song meets only those whose lengths sameSong can still accept beside
    // it (sameSongLengths of its parsed form: any, when it carries an ISRC).
    private fun backupPage(songs: List<TrackEntity>): () -> Unit {
        val relinks = LinkedHashMap<String, String>()
        songs.forEach { if (it.relinkKey.isNotEmpty()) relinks.putIfAbsent(it.relinkKey, it.id) }
        val wholes = songs.map { Triple(wholeKey(it.title, it.artist, it.album), it.id, it.durationMs) }
        // The title answers: each entry reached by a song's title keys, its
        // best so far, in song order.
        val parsed = songs.associate { it.id to parsedSong(it.title, it.artist, it.durationMs) }
        val keysOf = songs.associate { it.id to SongIdentity.titleLookupKeys(parsed.getValue(it.id)) }
        val reached = targetsOf(keysOf.values.flatten().toSet())
        val entries = queriesById(reached.values.flatten().map { it.first / 4 }.toSet())
        val byKey = reached.mapValues { (_, targets) ->
            ByLength(targets.map { it.first / 4 }.distinct()) { entries[it]?.durationMs ?: 0L }
        }
        val forms = HashMap<Long, ParsedSong>()
        val best = HashMap<Long, Pair<String, Long>>()
        entries.values.forEach { e -> e.titleId?.let { best[e.id] = it to (e.titleDist ?: 0) } }
        val changed = HashSet<Long>()
        var pairs = 0
        for (song in songs) {
            val form = parsed.getValue(song.id)
            val lengths = sameSongLengths(form).near
            val targets = keysOf.getValue(song.id).flatMap { byKey[it]?.near(lengths).orEmpty() }.distinct()
            for (id in targets) {
                val e = entries[id] ?: continue
                pairs++
                if (!sameSong(forms.getOrPut(id) { parsedSong(e.title, e.artist, e.durationMs) }, form)) continue
                val dist = abs(song.durationMs - e.durationMs)
                val now = best[id]
                if (now == null || (e.durationMs > 0 && dist < now.second)) {
                    best[id] = song.id to dist
                    changed += id
                }
            }
        }
        hooks.parsed(parsed.size, forms.size)
        hooks.compared(pairs)
        return {
            relinks.forEach { (key, id) ->
                db.write("UPDATE match_query SET relinkId = ? WHERE owner = ? AND relinkKey = ? AND relinkId IS NULL", id, owner, key)
            }
            for ((key, id, ms) in wholes) {
                db.write(
                    "UPDATE match_query SET wholeId = ?, wholeDist = abs(durationMs - ?) WHERE owner = ? AND wholeKey = ? " +
                        "AND (wholeId IS NULL OR (durationMs > 0 AND abs(durationMs - ?) < wholeDist))",
                    id, ms, owner, key, ms,
                )
            }
            changed.forEach { id ->
                val (track, dist) = best.getValue(id)
                db.write("UPDATE match_query SET titleId = ?, titleDist = ? WHERE owner = ? AND id = ?", track, dist, owner, id)
            }
        }
    }

    // A page of the library against a playlist file's title questions: each
    // question reached by a song's title keys, at the song's best rank, its
    // first song by artist and title, its first by artist, and with no
    // artist how many songs answer (stopping at two). A song meets only the
    // questions whose lengths m3uFits can still let agree with its own.
    private fun playlistPage(songs: List<TrackEntity>): () -> Unit {
        val keysOf = songs.associate { it.id to titleKeys(it.title) }
        val reached = targetsOf(keysOf.values.flatten().toSet())
        val questions = titlesByTarget(reached.values.flatten().map { it.first }.toSet())
        val byKey = reached.mapValues { (_, targets) -> ByLength(targets) { questions[it.first]?.lengthMs ?: 0L } }
        val changed = HashSet<Long>()
        var pairs = 0
        for (song in songs) {
            val m3u = M3uSong(song.id, song.title, song.artist, song.durationMs)
            // Each question this song reaches, at the lowest rank of the
            // keys that reach it.
            val ranks = HashMap<Long, Int>()
            val lengths = if (song.durationMs <= 0) null else song.durationMs - M3U_SAME_LENGTH_MS..song.durationMs + M3U_SAME_LENGTH_MS
            for (key in keysOf.getValue(song.id)) for ((target, rank) in byKey[key]?.near(lengths).orEmpty()) ranks.merge(target, rank, ::minOf)
            for ((target, rank) in ranks) {
                val q = questions[target] ?: continue
                pairs++
                val question = M3uQuestion(q.title, q.artist.takeIf { q.hasArtist }, q.lengthMs)
                if (!m3uFits(question, q.versions, m3u)) continue
                if (question.hasArtist) {
                    if (!m3uSameArtist(question, m3u)) continue
                    if ((q.p2Id == null || rank < q.p2Rank!!)) {
                        q.p2Id = song.id
                        q.p2Rank = rank
                        changed += target
                    }
                    if ((q.p1Id == null || rank < q.p1Rank!!) && m3uSameSong(question, m3u)) {
                        q.p1Id = song.id
                        q.p1Rank = rank
                        changed += target
                    }
                } else if (q.soleCount < 2 && q.soleId != song.id) {
                    if (q.soleCount == 0) {
                        q.soleId = song.id
                        q.soleCount = 1
                        changed += target
                    } else {
                        when (another(q.soleId!!, song.id)) {
                            Another.Same -> Unit
                            Another.Instead -> q.soleId = song.id
                            Another.Second -> q.soleCount = 2
                        }
                        changed += target
                    }
                }
            }
        }
        hooks.compared(pairs)
        return {
            changed.forEach { target ->
                val q = questions.getValue(target)
                db.write(
                    "UPDATE match_title SET p1Id = ?, p1Rank = ?, p2Id = ?, p2Rank = ?, soleId = ?, soleCount = ? WHERE owner = ? AND query = ? AND rank = ?",
                    q.p1Id, q.p1Rank, q.p2Id, q.p2Rank, q.soleId, q.soleCount, owner, target / 4, (target % 4).toInt(),
                )
            }
        }
    }

    // What a second answer to a question with no artist is, beside the
    // first: a swap during the walk can show the walk one song under its old
    // id and then under its new one, so each is followed to the song it is
    // now. One song is one answer; a first answer gone from the library
    // gives way to the second.
    private enum class Another { Same, Instead, Second }

    private fun another(first: String, second: String): Another {
        val now = db.followAll(listOf(first, second))
        return when {
            now[second] == null -> Another.Same
            now[first] == null -> Another.Instead
            now[first] == now[second] -> Another.Same
            else -> Another.Second
        }
    }

    // The phone's files in the order the scan found them, one per library
    // song (its first), against the entries by file name: the song whose
    // path shares the most folders from the end, the first on a tie. Each
    // file name's entries are read a page at a time on the name index, in
    // the order it keeps them, and the answers are written a page of them
    // at a time, so a list naming one file thousands of times is never
    // held whole.
    private suspend fun walkFiles(baseline: Long) {
        var after = -1L
        while (true) {
            val files = db.read(PHONE_FILE_PAGE_SQL, after, page) { PhoneFile(it.getLong(0), it.getString(1), it.stringOrNull(2), it.getString(3)) }
            if (files.isEmpty()) break
            val parts = files.associateWith { pathParts(relativeFile(it.folder, it.fileName)).map(String::lowercase) }
            val byName = files.groupBy { parts.getValue(it).lastOrNull() }
            // The answers found so far, written once a page of them is.
            val best = HashMap<Long, Pair<String, Int>>()
            suspend fun flush() {
                if (best.isEmpty()) return
                commit(baseline) {
                    best.forEach { (id, found) -> db.write("UPDATE match_query SET pathId = ?, pathEnd = ? WHERE owner = ? AND id = ?", found.first, found.second, owner, id) }
                }
                best.clear()
            }
            for ((name, named) in byName) {
                if (name == null) continue
                var afterRow = -1L
                while (true) {
                    val entries = db.read(MATCH_NAME_PAGE_SQL, owner, name, afterRow, page) { c ->
                        NameEntry(c.getLong(0), c.getLong(1), c.getString(2).split(PART_BREAK), c.getInt(3))
                    }
                    for (entry in entries) {
                        for (file in named) {
                            val ending = sharedEnding(entry.parts, parts.getValue(file))
                            if (ending > (best[entry.id]?.second ?: entry.stored)) best[entry.id] = file.mergedId to ending
                        }
                    }
                    if (best.size >= page) flush()
                    if (entries.size < page) break
                    afterRow = entries.last().row
                }
            }
            flush()
            if (files.size < page) break
            after = files.last().seq
        }
    }

    // An entry a file name leads to: its row, its id, its path's parts and
    // the ending it has matched so far.
    private class NameEntry(val row: Long, val id: Long, val parts: List<String>, val stored: Int)

    // Each entry's answer, in order of the ways of finding it, then each
    // found entry's place in its list.
    suspend fun finish() {
        var after = -1L
        while (true) {
            val rows = db.read(
                "SELECT id, relinkKey, title, artist, relinkId, wholeId, titleId, pathId FROM match_query WHERE owner = ? AND id > ? ORDER BY id LIMIT ?",
                owner, after, page,
            ) { c -> FinishRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.stringOrNull(4), c.stringOrNull(5), c.stringOrNull(6), c.stringOrNull(7)) }
            if (rows.isEmpty()) break
            val answers = if (kind == MatchKind.Playlist) playlistAnswers(rows) else rows.associate { it.id to backupAnswer(it) }
            withContext(WriteLabels.of("match finish")) {
                db.withTransaction { answers.forEach { (id, found) -> db.write("UPDATE match_query SET foundId = ? WHERE owner = ? AND id = ?", found, owner, id) } }
            }
            if (rows.size < page) break
            after = rows.last().id
        }
        places()
    }

    private fun backupAnswer(row: FinishRow): String? = when {
        row.relinkKey.isNotEmpty() && row.relinkId != null -> row.relinkId
        row.title.isBlank() -> null
        row.wholeId != null -> row.wholeId
        row.artist.isBlank() -> null
        else -> row.titleId
    }

    private fun playlistAnswers(rows: List<FinishRow>): Map<Long, String?> {
        val titles = HashMap<Long, MutableList<Title>>()
        db.read(
            "SELECT query, rank, hasArtist, p1Id, p2Id, soleId, soleCount FROM match_title WHERE owner = ? AND query >= ? AND query <= ? ORDER BY query, rank",
            owner, rows.first().id, rows.last().id,
        ) { c -> c.getLong(0) to Title(c.getInt(1), c.getInt(2) == 1, c.stringOrNull(3), c.stringOrNull(4), c.stringOrNull(5), c.getInt(6)) }
            .forEach { (query, title) -> titles.getOrPut(query) { ArrayList() } += title }
        return rows.associate { row ->
            row.id to (row.pathId ?: titles[row.id].orEmpty().firstNotNullOfOrNull { it.answer })
        }
    }

    // Numbers each list's found entries from 0, in file order.
    private suspend fun places() {
        var list = -1
        var pos = 0L
        var after = Pair(-1, -1L)
        while (true) {
            val rows = db.read(
                "SELECT list, id FROM match_query WHERE owner = ? AND kind = ? AND (list, id) > (?, ?) AND foundId IS NOT NULL ORDER BY list, id LIMIT ?",
                owner, kind.code, after.first, after.second, page,
            ) { it.getInt(0) to it.getLong(1) }
            if (rows.isEmpty()) break
            withContext(WriteLabels.of("match places")) {
                db.withTransaction {
                    for ((l, id) in rows) {
                        if (l != list) {
                            list = l
                            pos = 0
                        }
                        db.write("UPDATE match_query SET pos = ? WHERE owner = ? AND id = ?", pos++, owner, id)
                    }
                }
            }
            if (rows.size < page) break
            after = rows.last()
        }
    }

    // A page of a list's found entries after `afterEntry`, in file order,
    // each found song followed to the song it is now; one gone from the
    // library is left out. `next` is where the next page starts, null at
    // the end.
    suspend fun rows(list: Int, afterEntry: Long = -1, limit: Int = page): MatchPage {
        val found = db.read(
            "SELECT id, value, foundId FROM match_query WHERE owner = ? AND kind = ? AND list = ? AND id > ? AND foundId IS NOT NULL ORDER BY id LIMIT ?",
            owner, kind.code, list, afterEntry, limit,
        ) { Triple(it.getLong(0), it.getInt(1), it.getString(2)) }
        val now = db.followAll(found.map { it.third })
        val rows = found.mapNotNull { (entry, value, id) -> now[id]?.let { MatchRow(entry, value, it) } }
        return MatchPage(rows, found.lastOrNull()?.first?.takeIf { found.size == limit })
    }

    // How many entries of a list were found, and how many it has.
    fun counts(list: Int): Pair<Int, Int> = db.read(
        "SELECT COUNT(foundId), COUNT(*) FROM match_query WHERE owner = ? AND kind = ? AND list = ?",
        owner, kind.code, list,
    ) { it.getInt(0) to it.getInt(1) }.single()

    // The entries of a list not found, in file order, after `afterEntry`:
    // their ids and, for a playlist file, their line numbers.
    fun missed(list: Int, afterEntry: Long = -1, limit: Int = page): List<Pair<Long, Int>> = db.read(
        "SELECT id, value FROM match_query WHERE owner = ? AND kind = ? AND list = ? AND id > ? AND foundId IS NULL ORDER BY id LIMIT ?",
        owner, kind.code, list, afterEntry, limit,
    ) { it.getLong(0) to it.getInt(1) }

    // The same for a playlist file, each entry by the words it is shown by.
    fun missedShown(afterEntry: Long = -1, limit: Int = page): List<Pair<Long, String>> = db.read(
        "SELECT id, album FROM match_query WHERE owner = ? AND kind = ? AND list = 0 AND id > ? AND foundId IS NULL ORDER BY id LIMIT ?",
        owner, kind.code, afterEntry, limit,
    ) { it.getLong(0) to it.getString(1) }

    internal suspend fun clear() {
        listOf("match_query", "match_title", "match_lookup").forEach { db.deleteOwned(it, owner) }
    }

    // A list given again in a file replaces its first: its staged entries
    // go, a page at a time, with their lookups.
    suspend fun dropList(list: Int) {
        while (true) {
            val ids = db.read("SELECT id FROM match_query WHERE owner = ? AND kind = ? AND list = ? ORDER BY id LIMIT ?", owner, kind.code, list, page) { it.getLong(0) }
            if (ids.isEmpty()) return
            withContext(WriteLabels.of("match stage")) {
                db.withTransaction {
                    db.write(
                        "DELETE FROM match_lookup WHERE owner = ? AND target / 4 IN (${marks(ids.size)})",
                        owner, *ids.toTypedArray(),
                    )
                    db.write("DELETE FROM match_title WHERE owner = ? AND query IN (${marks(ids.size)})", owner, *ids.toTypedArray())
                    db.write("DELETE FROM match_query WHERE owner = ? AND id IN (${marks(ids.size)})", owner, *ids.toTypedArray())
                }
            }
            if (ids.size < page) return
        }
    }

    // How many entries of a list were found, counting each song once, and
    // only those whose value lies in `values` when given: the first entry
    // of each song counted, in one walk of the list's index.
    fun distinctFound(list: Int, values: IntRange? = null): Int {
        val (sql, args) = firstFoundSql("COUNT(*)", list, -1, values, null)
        return db.read(sql, *args) { it.getInt(0) }.single()
    }

    // The songs a list found, each once, in the order each first comes,
    // with the value of the list's last entry for it, among the entries
    // whose value lies in `values` when given: a page after entry `after`
    // (-1 from the top), and the entry the next page starts after (null at
    // the end). Ids are as matched: the caller follows them where it writes
    // them.
    fun firstFound(list: Int, after: Long, limit: Int = page, values: IntRange? = null): Pair<List<Pair<String, Int>>, Long?> {
        val (sql, args) = firstFoundSql("q.id, q.foundId", list, after, values, limit)
        val firsts = db.read(sql, *args) { it.getLong(0) to it.getString(1) }
        val rows = firsts.map { (_, id) ->
            val range = if (values != null) " AND value BETWEEN ${values.first} AND ${values.last}" else ""
            id to db.read("$MATCH_LAST_VALUE_SQL$range ORDER BY id DESC LIMIT 1", owner, kind.code, id, list) { it.getInt(0) }.single()
        }
        return rows to firsts.lastOrNull()?.first?.takeIf { firsts.size == limit }
    }

    private fun firstFoundSql(select: String, list: Int, after: Long, values: IntRange?, limit: Int?): Pair<String, Array<Any?>> =
        matchFirstFoundSql(select, values, limit) to arrayOf(owner, kind.code, list, after)

    // Each title key's questions, as (target, rank), for these keys.
    private fun targetsOf(keys: Set<String>): Map<String, List<Pair<Long, Int>>> {
        val found = HashMap<String, MutableList<Pair<Long, Int>>>()
        for (key in keys) {
            var after = -1L
            while (true) {
                val rows = db.read(
                    "SELECT target, rank FROM match_lookup WHERE owner = ? AND key = ? AND target > ? ORDER BY target LIMIT ?",
                    owner, key, after, page,
                ) { it.getLong(0) to it.getInt(1) }
                if (rows.isEmpty()) break
                found.getOrPut(key) { ArrayList() } += rows
                if (rows.size < page) break
                after = rows.last().first
            }
        }
        return found
    }

    private fun queriesById(ids: Set<Long>): Map<Long, Entry> {
        val found = HashMap<Long, Entry>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            db.read(
                "SELECT id, title, artist, durationMs, titleId, titleDist FROM match_query WHERE owner = ? AND id IN (${marks(chunk.size)})",
                owner, *chunk.toTypedArray(),
            ) { Entry(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.stringOrNull(4), it.longOrNull(5)) }
                .forEach { found[it.id] = it }
        }
        return found
    }

    private fun titlesByTarget(targets: Set<Long>): Map<Long, Question> {
        val found = HashMap<Long, Question>()
        targets.groupBy { it / 4 }.keys.chunked(ID_CHUNK).forEach { chunk ->
            db.read(
                "SELECT query, rank, title, artist, lengthMs, versions, hasArtist, p1Id, p1Rank, p2Id, p2Rank, soleId, soleCount " +
                    "FROM match_title WHERE owner = ? AND query IN (${marks(chunk.size)})",
                owner, *chunk.toTypedArray(),
            ) { c ->
                Question(
                    c.getLong(0), c.getInt(1), c.getString(2), c.getString(3), c.getLong(4),
                    c.getString(5).split(PART_BREAK).filter(String::isNotEmpty).toSet(), c.getInt(6) == 1,
                    c.stringOrNull(7), c.intOrNull(8), c.stringOrNull(9), c.intOrNull(10), c.stringOrNull(11), c.getInt(12),
                )
            }.forEach { found[target(it.query, it.rank)] = it }
        }
        return found.filterKeys { it in targets }
    }

    private class Entry(val id: Long, val title: String, val artist: String, val durationMs: Long, val titleId: String?, val titleDist: Long?)

    private class Question(
        val query: Long,
        val rank: Int,
        val title: String,
        val artist: String,
        val lengthMs: Long,
        val versions: Set<String>,
        val hasArtist: Boolean,
        var p1Id: String?,
        var p1Rank: Int?,
        var p2Id: String?,
        var p2Rank: Int?,
        var soleId: String?,
        var soleCount: Int,
    )

    private class Title(val rank: Int, val hasArtist: Boolean, val p1Id: String?, val p2Id: String?, val soleId: String?, val soleCount: Int) {
        val answer: String? get() = if (hasArtist) p1Id ?: p2Id else soleId.takeIf { soleCount == 1 }
    }

    private class FinishRow(
        val id: Long,
        val relinkKey: String,
        val title: String,
        val artist: String,
        val relinkId: String?,
        val wholeId: String?,
        val titleId: String?,
        val pathId: String?,
    )

    private class PhoneFile(val seq: Long, val mergedId: String, val folder: String?, val fileName: String)
}

// A title question's place: its entry times four, plus which question.
private fun target(entry: Long, question: Int): Long = entry * 4 + question

// What one title key reaches, by length, so a song is offered only what
// can still be it: everything with no length (0 or less) and everything
// whose length is in `range`, the lengths the compare can still accept;
// everything when `range` is null.
private class ByLength<T>(private val all: List<T>, private val lengthOf: (T) -> Long) {
    private val unknown = all.filter { lengthOf(it) <= 0 }
    private val known = all.filter { lengthOf(it) > 0 }.sortedBy(lengthOf)

    fun near(range: LongRange?): List<T> {
        if (range == null) return all
        val from = firstFrom { lengthOf(it) >= range.first }
        val to = firstFrom { lengthOf(it) > range.last }
        return if (from >= to) unknown else unknown + known.subList(from, to)
    }

    // The first of the known lengths, shortest first, from which `holds`
    // holds for every one after.
    private fun firstFrom(holds: (T) -> Boolean): Int {
        var lo = 0
        var hi = known.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (holds(known[mid])) hi = mid else lo = mid + 1
        }
        return lo
    }
}

// What separates a path's parts and a question's versions in one column.
private const val PART_BREAK = "\u0000"

// A backup song's whole key: its title, artist and album as one.
internal fun wholeKey(title: String, artist: String, album: String): String =
    "${SongIdentity.songKeys(artist, title).title}|${matchKey(artist)}|${matchKey(album)}"

// A list's found entries after an entry, in file order, each the first of
// the list's entries with its song (among those whose value lies in
// `values`): the list walked on its index, each row's earlier twins probed
// on the found index (`+` keeps the probe off the list's own index, which
// would walk every earlier entry of the list for each row). Its marks:
// owner, kind, list, the entry after.
internal fun matchFirstFoundSql(select: String, values: IntRange?, limit: Int?): String {
    val range = if (values != null) " AND %s.value BETWEEN ${values.first} AND ${values.last}" else ""
    return "SELECT $select FROM match_query q WHERE q.owner = ? AND q.kind = ? AND q.list = ? AND q.id > ? AND q.foundId IS NOT NULL" +
        range.format("q") +
        " AND NOT EXISTS (SELECT 1 FROM match_query p WHERE p.owner = q.owner AND p.kind = q.kind AND p.foundId = q.foundId " +
        "AND p.id < q.id AND +p.list = q.list${range.format("p")})" +
        (if (limit != null) " ORDER BY q.id LIMIT $limit" else "")
}

// The value of a list's entries with a song, on the found index (the list
// kept off its own index, which would walk the whole list); the caller
// adds its range and takes the last.
internal const val MATCH_LAST_VALUE_SQL =
    "SELECT value FROM match_query WHERE owner = ? AND kind = ? AND foundId = ? AND +list = ?"

// A page of the entries one file name leads to, after a row, on the name
// index in the order it keeps them.
internal const val MATCH_NAME_PAGE_SQL =
    "SELECT rowid, id, parts, pathEnd FROM match_query WHERE owner = ? AND nameKey = ? AND rowid > ? ORDER BY rowid LIMIT ?"

// The phone's files in the order the scan found them, one a library song:
// the first with a file name. The subquery stays on the copies of one song.
internal const val PHONE_FILE_PAGE_SQL =
    "SELECT seq, mergedId, folder, fileName FROM source_track s WHERE sourceId = 'device' AND seq > ? AND mergedId != '' " +
        "AND fileName IS NOT NULL AND NOT EXISTS (SELECT 1 FROM source_track d INDEXED BY index_source_track_mergedId " +
        "WHERE d.mergedId = s.mergedId AND +d.sourceId = 'device' AND +d.seq < s.seq AND d.fileName IS NOT NULL) ORDER BY seq LIMIT ?"
