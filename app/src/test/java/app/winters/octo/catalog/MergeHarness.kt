package app.winters.octo.catalog

import androidx.room.withTransaction
import app.winters.octo.device.DEVICE
import app.winters.octo.server.isServerSource
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// A test's body run with a guard: a test that waits past `seconds` fails
// instead of holding up every test after it.
internal fun guarded(seconds: Long = 120, block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(seconds * 1_000) { block() } }

// Everything one source has, as the old merge took it.
data class SourceCatalog(
    val sourceId: String,
    val onPhone: Boolean,
    val tracks: List<SourceTrackEntity>,
    val albums: List<SourceAlbumEntity>,
    val artists: List<SourceArtistEntity>,
)

// The library as the screens read it, and which library song each source
// copy became.
data class MergedCatalog(
    val tracks: List<TrackEntity>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
    val mergedIds: Map<String, String>,
)

// Writes the sources to a fresh database, merges them there and reads the
// library back. The server in use is the first server source given.
fun mergeThroughDatabase(sources: List<SourceCatalog>): MergedCatalog = runBlocking {
    val db = openTestCatalog()
    try {
        sources.forEach { db.put(it) }
        val server = sources.firstOrNull { isServerSource(it.sourceId) }?.sourceId
        db.testMerge(server).rebuild()
        db.library()
    } finally {
        db.close()
    }
}

internal suspend fun CatalogDatabase.put(source: SourceCatalog) =
    sourceDao().replaceSource(source.sourceId, source.tracks, source.albums, source.artists)

// A copy of a source waiting to be swapped in, as a server copy leaves it,
// every row numbered anew and so counted as placed elsewhere.
internal suspend fun CatalogDatabase.stage(source: SourceCatalog, generation: Long, owner: String? = null) {
    val work = workDao()
    work.insertStageTracks(source.tracks.mapIndexed { i, t -> StageTrackEntity(t.copy(seq = i.toLong()).withKeys()) })
    work.insertStageAlbums(source.albums.mapIndexed { i, a -> StageAlbumEntity(a.copy(seq = i.toLong())) })
    work.insertStageArtists(source.artists.mapIndexed { i, a -> StageArtistEntity(a.copy(seq = i.toLong())) })
    val stamp = work.metas().firstOrNull { it.sourceId == source.sourceId }?.stamp ?: 0
    work.putMeta(
        SourceMetaEntity(
            source.sourceId, null, owner, stamp, STAGE_READY, generation, source.tracks.size, source.albums.size, source.artists.size,
            movedTracks = source.tracks.size, movedAlbums = source.albums.size, movedArtists = source.artists.size,
        ),
    )
}

internal suspend fun CatalogDatabase.library(): MergedCatalog {
    val catalog = dao()
    val ids = HashMap<String, String>()
    query("SELECT id, mergedId FROM source_track WHERE mergedId != ''", null).use { c ->
        while (c.moveToNext()) ids[c.getString(0)] = c.getString(1)
    }
    return MergedCatalog(catalog.tracks().first(), catalog.albums().first(), catalog.artists().first(), ids)
}

internal fun CatalogDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

internal fun CatalogDatabase.longOf(sql: String, vararg args: Any?): Long =
    query(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

internal fun CatalogDatabase.stringOf(sql: String, vararg args: Any?): String? =
    query(sql, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

internal fun song(source: String, native: String, title: String, album: String, ms: Long = 200_000, no: Int? = null, order: Int = 0, rating: Int? = null) =
    SourceTrackEntity(
        id = "$source:$native", sourceId = source, nativeId = native, title = title, searchKey = title.lowercase(),
        sortKey = title.lowercase(), artist = "Drake", artistId = "$source:artist", album = "Views", albumId = "$source:$album",
        trackNo = no, discNo = null, year = null, durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null,
        artwork = null, uri = null, albumOrder = order, relinkKey = "drake|${title.lowercase()}", genre = "", rating = rating,
    )

internal fun albumRow(source: String, native: String, title: String = "Views") =
    SourceAlbumEntity("$source:$native", source, native, title, title.lowercase(), title.lowercase(), "Drake", "$source:artist", null, 0, 0, 0, null)

internal fun artistRow(source: String) = SourceArtistEntity("$source:artist", source, "Drake", "drake", "drake", 1, 1, null)

// A source of `songs` songs, ten an album, the same titles in every source
// so copies merge.
internal fun library(source: String, songs: Int, msShift: Long = 0) = SourceCatalog(
    source,
    source == DEVICE,
    (0 until songs).map { song(source, "s$it", "Song $it", "album${it / 10}", ms = 200_000L + it + msShift, no = it % 10 + 1, order = it % 10) },
    (0 until (songs + 9) / 10).map { albumRow(source, "album$it", "Album $it") },
    listOf(artistRow(source)),
)

// Stops a merge at one point until the test lets it go.
internal class Pause {
    val reached = CompletableDeferred<Unit>()
    private val go = CompletableDeferred<Unit>()

    suspend fun hit() {
        if (reached.complete(Unit)) go.await()
    }

    fun release() {
        go.complete(Unit)
    }
}

internal class TestHooks(
    private val atClaim: Pause? = null,
    private val atFirstOutput: Pause? = null,
    private val atSwap: Pause? = null,
    private val firstOutput: (() -> Unit)? = null,
) : MergeHooks() {
    val merges = AtomicInteger()
    val skips = AtomicInteger()
    val events = CopyOnWriteArrayList<String>()

    // How many times a claim's copies were checked before a save's first
    // group.
    val checks = AtomicInteger()

    override suspend fun claimed() {
        atClaim?.hit()
    }

    override suspend fun checking() {
        checks.incrementAndGet()
    }

    override suspend fun firstOutputWritten() {
        firstOutput?.invoke()
        atFirstOutput?.hit()
    }

    override suspend fun inSwap() {
        events += "swap"
        atSwap?.hit()
    }

    override fun merged() {
        merges.incrementAndGet()
    }

    override fun skipped() {
        skips.incrementAndGet()
    }
}

// Notes the size of every page the merge reads.
internal class CountingWorkDao(private val inner: WorkDao) : WorkDao by inner {
    val pages = CopyOnWriteArrayList<Int>()

    override suspend fun trackPage(query: androidx.room.RoomRawQuery) = inner.trackPage(query).also { pages += it.size }

    override suspend fun albumPage(query: androidx.room.RoomRawQuery) = inner.albumPage(query).also { pages += it.size }

    override suspend fun artistPage(query: androidx.room.RoomRawQuery) = inner.artistPage(query).also { pages += it.size }
}

internal fun reopen(db: CatalogDatabase, dir: File): CatalogDatabase {
    db.close()
    return openTestCatalog(dir)
}

// A library's file and its log as they are at this moment, copied into a
// folder of their own, as the app's end would leave them (no checkpoint,
// no close): open it with openTestCatalog, after LaunchMark.next() for the
// new launch.
internal fun killImage(db: CatalogDatabase): File {
    val from = File(db.openHelper.writableDatabase.path!!)
    val dir = java.nio.file.Files.createTempDirectory("kill").toFile()
    from.copyTo(File(dir, CATALOG_FILE))
    File(from.path + "-wal").takeIf { it.exists() }?.copyTo(File(dir, "$CATALOG_FILE-wal"))
    return dir
}

// A real merge over this database with a save bound of its own.
internal fun CatalogDatabase.boundMerge(server: String?, bound: Long, hooks: MergeHooks = MergeHooks(), space: Long = Long.MAX_VALUE): CatalogMerge =
    CatalogMerge(this, { space }, { server }, workDao(), bound).also { it.hooks = hooks }

// What a save's groups did: each group's steps with the rows they wrote,
// the groups each save took, and a test's own step at each point between
// groups (`between`, given the group's number in its save) and inside each
// group before it writes (`inside`).
internal open class GroupHooks(private val db: CatalogDatabase? = null) : MergeHooks() {
    val turns = AtomicInteger()

    // With `db`, what the find planned (each found row's table, op, id,
    // ids, set and group), as it stood before the first group.
    val planned: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun checking() {
        db?.query("SELECT tbl, op, id, oldId, newId, setId, groupId FROM save_found", null)?.use { c ->
            while (c.moveToNext()) planned += (0 until 7).joinToString(" ") { c.getString(it) ?: "-" }
        }
        db?.query("SELECT 'ref', tbl, oldId, target, setId, groupId, keyText, keyNum, keyNum2, seq FROM save_ref", null)?.use { c ->
            while (c.moveToNext()) planned += (0 until 10).joinToString(" ") { c.getString(it) ?: "-" }
        }
    }

    // The planned rows naming any of `ids`.
    fun plannedFor(ids: Collection<String>): List<String> = planned.filter { row -> row.split(' ').let { it.size > 4 && ids.any { id -> id == it[2] || id == it[3] || id == it[4] } } }
    val saves: MutableList<Long> = CopyOnWriteArrayList()
    val steps: MutableList<Map<String, Long>> = CopyOnWriteArrayList()
    private val current = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val events = CopyOnWriteArrayList<String>()

    @Volatile var between: suspend (Long) -> Unit = {}
    @Volatile var inside: suspend (Long) -> Unit = {}

    override suspend fun inGroup(turn: Long, group: Long) {
        turns.incrementAndGet()
        inside(turn)
    }

    override fun groupStep(turn: Long, step: String, rows: Long) {
        current.merge(step, rows, Long::plus)
    }

    override suspend fun betweenGroups(turn: Long) {
        between(turn)
    }

    override fun applied(turns: Long, rows: Long, millis: Long) {
        saves += turns
        steps += HashMap(current)
        current.clear()
    }

    override suspend fun inSwap() {
        events += "swap"
    }
}

// Every library song's play total as a recount gives it (totalOf over its
// own rows), by song.
internal suspend fun CatalogDatabase.recountTotals(account: String?): Map<String, app.winters.octo.listening.PlayTotalEntity> {
    val dao = playTotalsDao()
    val ids = query("SELECT id FROM track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    return ids.mapNotNull { id ->
        app.winters.octo.listening.totalOf(app.winters.octo.listening.SongPlays(id, dao.phonePlaysOf(id), dao.copiesOf(id, account)))
    }.associateBy { it.trackId }
}

// The stored play totals, by song.
internal fun CatalogDatabase.storedTotals(): Map<String, app.winters.octo.listening.PlayTotalEntity> =
    query("SELECT trackId, albumId, artistId, plays, lastPlayedAt, recentAt, phonePlays, phoneLastAt, sortKey, artistSortKey FROM play_total", null).use { c ->
        buildMap {
            while (c.moveToNext()) {
                val row = app.winters.octo.listening.PlayTotalEntity(
                    c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), if (c.isNull(5)) null else c.getLong(5),
                    c.getInt(6), if (c.isNull(7)) null else c.getLong(7), c.getString(8), c.getString(9),
                )
                put(row.trackId, row)
            }
        }
    }

// The stored play totals made what a recount gives, and the account they
// count for written, as a finished rebuild leaves them.
internal suspend fun CatalogDatabase.settleTotals(account: String?) {
    val now = recountTotals(account)
    val dao = playTotalsDao()
    withTransaction {
        exec("DELETE FROM play_total")
        if (now.isNotEmpty()) now.values.chunked(500).forEach { dao.putTotals(it) }
        dao.ensureMergeState()
        dao.setTotalsAccount(account)
    }
}

// Songs whose stored play total differs from a recount, with both.
internal suspend fun CatalogDatabase.totalsOff(account: String?): List<String> {
    val counted = recountTotals(account)
    val stored = storedTotals()
    return (counted.keys + stored.keys).filter { counted[it] != stored[it] }.sorted().map { "$it: stored ${stored[it]} counted ${counted[it]}" }
}

// Rows of every kind that follow library ids, on library songs, server
// copies' ids, ids no source has and ids with no song's key.
internal suspend fun followingRows(db: CatalogDatabase, random: kotlin.random.Random, server: String) {
    val songs = db.query("SELECT id, relinkKey FROM track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    val copies = db.query("SELECT id, relinkKey FROM source_track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    val albums = db.query("SELECT id, searchKey FROM album ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    val artists = db.query("SELECT id, searchKey FROM artist ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    val ids = songs + copies + listOf("gone:1" to "drake|song 1", "gone:2" to "", "gone:3" to "nobody|nothing")
    fun pick() = ids[random.nextInt(ids.size)]
    insertRow(db, "playlist", mapOf("id" to "p", "name" to "P", "createdAt" to 1, "updatedAt" to 1))
    val liked = HashSet<String>()
    val rated = HashSet<String>()
    repeat(40) { n ->
        val (id, key) = pick()
        if (liked.add(id)) insertRow(db, "liked_track", mapOf("trackId" to id, "relinkKey" to key, "likedAt" to n))
        val (rid, rkey) = pick()
        if (rated.add(rid)) insertRow(db, "track_rating", mapOf("trackId" to rid, "relinkKey" to rkey, "rating" to 1 + n % 5, "ratedAt" to n))
        val (pid, pkey) = pick()
        insertRow(db, "play_event", mapOf("trackId" to pid, "relinkKey" to pkey, "startedAt" to n, "playedMs" to 1, "durationMs" to 1))
        val (iid, ikey) = pick()
        insertRow(db, "playlist_item", mapOf("playlistId" to "p", "itemSet" to 0, "trackId" to iid, "relinkKey" to ikey, "ord" to n, "itemKey" to n))
        val (eid, ekey) = pick()
        insertRow(db, "playlist_edit_log", mapOf("playlistId" to "p", "itemSet" to 0, "op" to 0, "itemKey" to n, "ord" to n, "trackId" to eid, "relinkKey" to ekey))
        val (qid, _) = pick()
        insertRow(db, "queue_entry", mapOf("queueId" to 1 + n % 2, "entry" to n, "trackId" to qid, "listId" to n % 3))
        val (sid, skey) = pick()
        insertRow(db, "server_entry", mapOf("owner" to WorkOwners.marked("vm:${n % 2}"), "position" to n, "trackId" to sid, "relinkKey" to skey))
        val (lid, _) = pick()
        insertRow(db, "lists_album_entry", mapOf("owner" to WorkOwners.marked("vm:0"), "position" to n, "shownId" to lid, "isFind" to n % 4 / 3))
        val (did, dkey) = pick()
        insertRow(db, "lists_server_dir", mapOf("owner" to WorkOwners.marked("vm:0"), "position" to n, "kind" to n % 3 / 2, "trackId" to did, "relinkKey" to dkey))
        val (aid, _) = pick()
        insertRow(db, "online_song", mapOf("id" to "find:$n", "sourceId" to server, "nativeId" to "$n", "title" to "F", "artist" to "A", "album" to "", "durationMs" to 1, "seenAt" to 1, "adoptedId" to if (n % 2 == 0) aid else ""))
    }
    for ((n, album) in (albums + ("gone:album" to "album 2")).withIndex()) {
        if (n % 2 == 0) insertRow(db, "liked_album", mapOf("albumId" to album.first, "relinkKey" to album.second, "likedAt" to n))
        if (n % 3 == 0) insertRow(db, "pinned_item", mapOf("kind" to "album", "itemId" to album.first, "relinkKey" to album.second, "position" to n))
    }
    for ((n, artist) in artists.withIndex()) {
        insertRow(db, "liked_artist", mapOf("artistId" to artist.first, "relinkKey" to artist.second, "likedAt" to n))
        insertRow(db, "pinned_item", mapOf("kind" to "artist", "itemId" to artist.first, "relinkKey" to artist.second, "position" to 100 + n))
    }
}

// Each of the 18 tables that follow ids, the rows whose id names no song,
// album or artist of the library: "table key -> id".
internal fun CatalogDatabase.dangling(): Set<String> {
    val out = HashSet<String>()
    for (t in SAVE_RENAME_TARGETS) {
        val kinds = if (t.kind == null) listOf(RENAME_ALBUM, RENAME_ARTIST) else listOf(t.kind)
        for (kind in kinds) {
            val live = SaveFindCheck.liveOf(kind)
            val pin = if (t.kind == null) " AND o.kind = '$kind'" else ""
            val only = t.only?.let { (name, value) -> " AND o.`$name` = $value" }.orEmpty()
            val blank = if (t.table == "online_song") " AND o.adoptedId != ''" else ""
            query("SELECT o.rowid, o.`${t.column}` FROM `${t.table}` o WHERE NOT EXISTS (SELECT 1 FROM `$live` l WHERE l.id = o.`${t.column}`)$pin$only$blank", null).use { c ->
                while (c.moveToNext()) out += "${t.table} ${c.getLong(0)} -> ${c.getString(1)}"
            }
        }
    }
    return out
}
