package app.winters.octo.folders

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.ListsServerDirEntity
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.ServerDirView
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.bindAll
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.rawQuery
import app.winters.octo.catalog.read
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.data.Drains
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.asTrack
import app.winters.octo.discovery.drainedEntries
import app.winters.octo.discovery.serverRoom
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.DirectoryRef
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.ui.paging.FolderKeys
import app.winters.octo.ui.paging.IndexPart
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.PartReads
import app.winters.octo.ui.paging.PartsLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.Survey
import app.winters.octo.ui.paging.songColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import javax.inject.Inject
import javax.inject.Singleton

// Most songs one Play or Shuffle gathers from under a server folder.
const val SERVER_SONG_LIMIT = 2_000

// Most folders one Play or Shuffle opens on the way.
const val SERVER_FOLDER_LIMIT = 400

// How many of a level's rows one transaction writes or keys anew, and how
// many of its songs are resolved at once.
internal const val LEVEL_PAGE = 500

// What a level's row is.
internal const val LEVEL_FOLDER = 0
internal const val LEVEL_SONG = 1

// A level's songs as the server lists them, the order a level is read in
// when none is asked for.
val SERVER_LISTED = SortOrder(SongSort.FolderOrder, false)

// One level of the server's folders as a page reads it: whose rows hold
// it, how many folders it lists (they come first), and how many songs.
data class ServerLevel(val owner: String, val folders: Int, val songs: Int)

// A row of a level: one of its folders, or one of its songs as the library
// has it, or as a find.
sealed interface LevelRow {
    data class Folder(val folder: DirectoryRef) : LevelRow

    data class Song(val track: TrackEntity) : LevelRow
}

// Browses the signed-in server by folder, live. A level (the server's top,
// or one folder) is read into rows of its own under the page's owner: its
// folders, in the order the server lists them, then its songs, keyed for
// the order the page shows them in, never held whole. Songs come back as
// the library has them when it does, so they play the phone's copy, and as
// finds otherwise. Reads throw SubsonicException when the server cannot
// answer, and answer null when no server is signed in.
@Singleton
class ServerFolders internal constructor(
    // What to call the signed-in server, or null while none is. Unknown
    // while the saved sign-in is still being read.
    val name: Flow<ServerName>,
    private val db: CatalogDatabase,
    private val drains: Drains,
    private val freeSpace: FreeSpace,
    private val owners: WorkOwners,
    // The signed-in server, read once per read; null when none is.
    private val client: () -> SubsonicClient?,
    // What each of a page of songs is, in its place: the library's song,
    // the one a find became, or the find; null where the library's song
    // is gone.
    private val resolve: suspend (List<Song>) -> List<TrackEntity?>,
    private val page: Int = LEVEL_PAGE,
) {
    @Inject constructor(
        sessions: SessionRepository,
        discovery: Discovery,
        db: CatalogDatabase,
        drains: Drains,
        freeSpace: FreeSpace,
        owners: WorkOwners,
    ) : this(
        name = sessions.state.map { state ->
            when (state) {
                SessionState.Loading -> ServerName.Unknown
                is SessionState.SignedIn -> ServerName.Known(
                    serverName(state.session.isOcto, state.session.serverType, state.session.client.primaryUrl.host),
                )
                else -> ServerName.None
            }
        },
        db, drains, freeSpace, owners,
        client = { (sessions.state.value as? SessionState.SignedIn)?.session?.client },
        resolve = { songs -> discovery.resolveInPlace(songs) },
    )

    // Reads one level, the top when `id` is null, otherwise that folder,
    // into `owner`, in place of what it held, its songs keyed for `order`.
    // The answer is drained to a file first, then written from the file a
    // page at a time: its folders, then its songs, each page of songs
    // resolved as it is written. Null when no server is signed in. A read
    // that fails leaves the owner empty and throws what the server's own
    // call throws, or NotEnoughSpace when the phone runs short of room.
    suspend fun read(id: String?, owner: String, order: SortOrder = SERVER_LISTED): ServerLevel? = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext null
        try {
            release(owner)
            drains.use("server-level-${owners.next()}.json") { file ->
                client.drainFolder(id, file, room = ::roomForDrained)
                val folders = client.readFolder(file, id == null) { }
                need(folders.size.toLong())
                var position = 0L
                for (chunk in folders.chunked(page)) {
                    val rows = chunk.map { f ->
                        ListsServerDirEntity(owner, position++, LEVEL_FOLDER, f.id, f.name, f.coverArt, f.songCount, null, NO_KEY, NO_KEY)
                    }
                    writing { db.listsDao().insertServerDirRows(rows) }
                }
                val waiting = ArrayList<Song>(page)
                var songs = 0
                client.readFolder(file, id == null) { song ->
                    waiting += song
                    if (waiting.size == page) {
                        songsAt(owner, position, waiting.toList(), order)
                        position += waiting.size
                        songs += waiting.size
                        waiting.clear()
                        need(position)
                    }
                }
                if (waiting.isNotEmpty()) {
                    songsAt(owner, position, waiting.toList(), order)
                    songs += waiting.size
                }
                ServerLevel(owner, folders.size, songs)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { release(owner) } }
            throw e
        }
    }

    // Writes a page of a level's songs from `from` on, each as it resolves
    // now and keyed for `order`. A save that lands while they resolve moves
    // the rows already written, and a rename it left in force moves these as
    // they go in. When one landed, this page's rows whose song is gone all
    // the same (its rename gone with a later save's start, say) are followed
    // or relinked by key too: only this page's rows.
    private suspend fun songsAt(owner: String, from: Long, songs: List<Song>, order: SortOrder) {
        val baseline = db.withTransaction { db.workDao().swapCount() }
        val shown = resolve(songs)
        val rows = songs.mapIndexed { i, s ->
            val song = shown.getOrNull(i)
            val (lead, ties) = song?.let { FolderKeys.listed(it, from + i, order) } ?: (NO_KEY to NO_KEY)
            ListsServerDirEntity(owner, from + i, LEVEL_SONG, s.id, s.title, null, null, song?.id, lead, ties, song?.relinkKey.orEmpty())
        }
        writing {
            db.withTransaction {
                db.listsDao().insertServerDirRows(rows)
                if (db.workDao().swapCount() != baseline) {
                    db.userDao().followMergedServerDirs(owner, from, from + rows.size)
                    db.userDao().relinkServerDirs(owner, from, from + rows.size)
                }
            }
        }
    }

    // Keys a level's songs anew for `order`, in place, a page at a time,
    // with no new read: each page's songs as the level shows them now (the
    // library's song, or the find as kept), read in the transaction that
    // writes their keys, so a swap lands between pages, never inside one.
    // A row whose song is gone keeps its keys; the level does not show it.
    suspend fun rekey(level: ServerLevel, order: SortOrder) = withContext(Dispatchers.IO) {
        val end = level.folders.toLong() + level.songs
        var from = level.folders.toLong()
        while (from < end) {
            val to = minOf(from + page, end)
            writing {
                db.withTransaction {
                    val rows = db.read(LEVEL_SONGS_AT_SQL, level.owner, from, to) { it.getLong(0) to it.stringOrNull(1) }
                    val songs = songsOf(rows.mapNotNull { it.second })
                    db.openHelper.writableDatabase.compileStatement(LEVEL_KEYS_SQL).use { st ->
                        for ((position, trackId) in rows) {
                            val song = songs[trackId] ?: continue
                            val (lead, ties) = FolderKeys.listed(song, position, order)
                            st.clearBindings()
                            st.bindAll(arrayOf(lead, ties, level.owner, position))
                            st.executeUpdateDelete()
                        }
                    }
                }
            }
            from = to
        }
    }

    // The songs these rows show, by id: library songs as the library has
    // them, finds as kept.
    private suspend fun songsOf(ids: List<String>): Map<String, TrackEntity> {
        val (finds, library) = ids.distinct().partition(::isFind)
        val out = HashMap<String, TrackEntity>()
        library.chunked(ID_CHUNK).forEach { chunk -> db.dao().tracksByIdsUnordered(chunk).forEach { out[it.id] = it } }
        finds.chunked(ID_CHUNK).forEach { chunk -> db.onlineDao().byIds(chunk).forEach { out[it.id] = it.asTrack() } }
        return out
    }

    // Lets a level's rows go, a page at a time.
    suspend fun release(owner: String) {
        db.deleteOwned(LEVEL_TABLE, owner)
    }

    // The rows a level shows: its folders, then its songs in their order.
    fun loader(owner: String): ServerLevelLoader = ServerLevelLoader(db, owner)

    // A level's songs alone, in their order: what a play of the level walks.
    fun songs(owner: String): LevelSongsLoader = LevelSongsLoader(db, owner)

    // The room `rows` of a level need, as a server list's entries take
    // (serverRoom).
    private fun need(rows: Long) {
        val need = serverRoom(rows, page)
        if (freeSpace.bytes() < need) throw NotEnoughSpace(need, SpaceFor.List)
    }

    private fun roomForDrained(written: Long) = need(drainedEntries(written))

    private suspend fun <T> writing(block: suspend () -> T): T = withContext(WriteLabels.of("server folder page")) {
        try {
            block()
        } catch (e: SQLiteFullException) {
            throw NotEnoughSpace(null, SpaceFor.List)
        }
    }
}

// The table a server folder level is kept in.
internal const val LEVEL_TABLE = "lists_server_dir"

// A folder's keys, and those of a song with nothing to show.
private val NO_KEY = ByteArray(0)

// A level's song rows in one stretch of places, `from` up to `to`.
private const val LEVEL_SONGS_AT_SQL = "SELECT position, trackId FROM lists_server_dir WHERE owner = ? AND position >= ? AND position < ? AND kind = 1"

private const val LEVEL_KEYS_SQL = "UPDATE lists_server_dir SET lead = ?, ties = ? WHERE owner = ? AND position = ?"

// The rows of a level a page shows: every folder, and each song whose song
// is still there, the first of the level's rows with it (the song index
// finds an earlier one).
internal const val LEVEL_SHOWN =
    "(d.kind = 0 OR (d.trackId IS NOT NULL AND (t.id IS NOT NULL OR o.id IS NOT NULL) AND NOT EXISTS " +
        "(SELECT 1 FROM lists_server_dir f WHERE f.owner = d.owner AND f.trackId = d.trackId AND f.position < d.position)))"

// A level's rows walked in the page's order on their own index: its
// folders first (their keys are empty), as the server lists them, then its
// songs by their keys.
internal const val LEVEL_WALK_FROM =
    "lists_server_dir d INDEXED BY index_lists_server_dir_order LEFT JOIN track t ON t.id = d.trackId LEFT JOIN online_song o ON o.id = d.trackId"

internal const val LEVEL_FROM =
    "lists_server_dir d LEFT JOIN track t ON t.id = d.trackId LEFT JOIN online_song o ON o.id = d.trackId"

private const val LEVEL_SELECT = "d.position AS position, d.kind AS kind, d.id AS dirId, d.name AS name, d.coverArt AS coverArt, " +
    "d.songCount AS songCount, d.lead AS lead, d.ties AS ties, "

// One level's rows as the page shows them, keyed by their place in the
// order index: a folder by its id, a song by the song it shows. A page
// reads them, and a tap plays its songs from here.
class ServerLevelLoader internal constructor(private val db: CatalogDatabase, val owner: String) : ListLoader<LevelRow> {
    private val dao = db.listsDao()
    private val reads = levelReads(db)
    private val keyOf: (ServerDirView) -> RowKey = { row -> levelKey(row.kind, row.lead, row.ties, row.position, row.keyId()) }
    private val part = IndexPart(
        0,
        LEVEL_WALK_FROM,
        listOf("d.owner = ?", LEVEL_SHOWN),
        listOf(SqlValue.Text(owner)),
        emptyList(),
        listOf("d.kind", "d.lead", "d.ties", "d.position"),
        false,
        LEVEL_SELECT + songColumns(prefix = "s_", none = "d.kind = 0"),
        reads,
        keyOf,
        idColumn = "d.trackId",
    )
    private val inner = PartsLoader<ServerDirView, LevelRow>(
        { listOf(part) },
        arrayOf(LEVEL_TABLE, "track", "online_song"),
        SqlOrder.rows(),
        songsOnce = true,
        rowOf = { it.row() },
        idOf = { (it as? LevelRow.Song)?.track?.id },
        smallRows = { sql, args -> dao.serverDirRows(rawQuery(sql, args)) },
        smallKey = keyOf,
    )

    override val tables get() = inner.tables
    override val order get() = inner.order
    override val songsOnce get() = true
    override suspend fun count() = inner.count()
    override suspend fun survey(anchor: RowKey?): Survey = inner.survey(anchor)
    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<LevelRow>> = inner.after(key, limit)
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<LevelRow>> = inner.before(key, limit)
    override suspend fun keyAt(position: Int) = inner.keyAt(position)
    override suspend fun small() = inner.small()

    // A song's row is found on the song index rather than walked to.
    override suspend fun keyOf(trackId: String): RowKey? = songKeyOf(db, owner, trackId) { kind, lead, ties, at -> levelKey(kind, lead, ties, at, trackId) }

    override suspend fun present(ids: List<String>): Set<String> = presentIn(db, owner, ids)

    override fun trackIdOf(row: LevelRow): String? = (row as? LevelRow.Song)?.track?.id

    // A key another read of the level handed out, or this read before its
    // songs were keyed anew, names the row still at its place when that row
    // holds what it named, as it is keyed now; else a song's first row
    // here, or null.
    override suspend fun rekey(key: RowKey): RowKey? {
        val at = (key.values.lastOrNull() as? SqlValue.Int64)?.v
        if (at != null) {
            val there = withContext(Dispatchers.IO) {
                db.read(LEVEL_ROW_AT_SQL, owner, at) { c ->
                    val kind = c.getInt(0)
                    levelKey(kind, c.getBlob(3), c.getBlob(4), at, if (kind == LEVEL_FOLDER) folderKey(c.getString(1)) else c.stringOrNull(2).orEmpty())
                }.firstOrNull()
            }
            if (there != null && there.id == key.id) return there
        }
        return if (key.id.startsWith(FOLDER_KEY)) null else keyOf(key.id)
    }
}

// A level's songs alone, as its page shows them and in the same order,
// keyed by their keys and place: what a play of the level walks.
class LevelSongsLoader internal constructor(private val db: CatalogDatabase, val owner: String) : ListLoader<TrackEntity> {
    private val dao = db.listsDao()
    private val keyOf: (ServerDirView) -> RowKey = { row -> songKey(row.lead, row.ties, row.position, row.song?.id.orEmpty()) }
    private val part = IndexPart(
        0,
        LEVEL_WALK_FROM,
        listOf("d.owner = ?", "d.kind = 1", LEVEL_SHOWN),
        listOf(SqlValue.Text(owner)),
        emptyList(),
        listOf("d.lead", "d.ties", "d.position"),
        false,
        LEVEL_SELECT + songColumns(prefix = "s_"),
        levelReads(db),
        keyOf,
        idColumn = "d.trackId",
    )
    private val inner = PartsLoader<ServerDirView, TrackEntity>(
        { listOf(part) },
        arrayOf(LEVEL_TABLE, "track", "online_song"),
        SqlOrder.rows(),
        songsOnce = true,
        rowOf = { it.song!! },
        idOf = { it.id },
        smallRows = { sql, args -> dao.serverDirRows(rawQuery(sql, args)) },
        smallKey = keyOf,
    )

    override val tables get() = inner.tables
    override val order get() = inner.order
    override val songsOnce get() = true
    override suspend fun count() = inner.count()
    override suspend fun survey(anchor: RowKey?): Survey = inner.survey(anchor)
    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> = inner.after(key, limit)
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> = inner.before(key, limit)
    override suspend fun keyAt(position: Int) = inner.keyAt(position)
    override suspend fun small() = inner.small()

    override suspend fun keyOf(trackId: String): RowKey? = songKeyOf(db, owner, trackId) { _, lead, ties, at -> songKey(lead, ties, at, trackId) }

    override suspend fun present(ids: List<String>): Set<String> = presentIn(db, owner, ids)

    override fun trackIdOf(row: TrackEntity): String = row.id

    // A key the level's page handed out (from another read of the level,
    // or with the page's own shape) names the song still at its place, as
    // this read keys it; else that song's first row here, or null.
    override suspend fun rekey(key: RowKey): RowKey? {
        val at = (key.values.lastOrNull() as? SqlValue.Int64)?.v
        if (at != null) {
            val there = withContext(Dispatchers.IO) {
                db.read(LEVEL_ROW_AT_SQL, owner, at) { c ->
                    c.stringOrNull(2)?.takeIf { c.getInt(0) == LEVEL_SONG }?.let { id -> songKey(c.getBlob(3), c.getBlob(4), at, id) }
                }.firstOrNull()
            }
            if (there != null && there.id == key.id) return there
        }
        return keyOf(key.id)
    }
}

private const val LEVEL_ROW_AT_SQL = "SELECT kind, id, trackId, lead, ties FROM lists_server_dir WHERE owner = ? AND position = ?"

private fun levelReads(db: CatalogDatabase) = object : PartReads<ServerDirView> {
    override suspend fun rows(sql: String, args: List<SqlValue>) = db.listsDao().serverDirRows(rawQuery(sql, args))
    override suspend fun count(sql: String, args: List<SqlValue>) = db.listsDao().count(rawQuery(sql, args))
    override suspend fun ids(sql: String, args: List<SqlValue>) = db.listsDao().ids(rawQuery(sql, args))
}

// A song's first shown row of a level, found on the song index, keyed by
// `key` from its kind, keys and place.
private suspend fun songKeyOf(db: CatalogDatabase, owner: String, trackId: String, key: (Int, ByteArray, ByteArray, Long) -> RowKey): RowKey? =
    withContext(Dispatchers.IO) {
        db.read("SELECT d.kind, d.lead, d.ties, d.position FROM $LEVEL_FROM WHERE d.owner = ? AND d.kind = 1 AND $LEVEL_SHOWN AND d.trackId = ? LIMIT 1", owner, trackId) { c ->
            key(c.getInt(0), c.getBlob(1), c.getBlob(2), c.getLong(3))
        }.firstOrNull()
    }

// Which of these songs a level shows, on the song index.
private suspend fun presentIn(db: CatalogDatabase, owner: String, ids: List<String>): Set<String> = withContext(Dispatchers.IO) {
    ids.distinct().chunked(ID_CHUNK).flatMapTo(HashSet()) { chunk ->
        db.read(
            "SELECT d.trackId FROM $LEVEL_FROM WHERE d.owner = ? AND d.kind = 1 AND $LEVEL_SHOWN AND d.trackId IN (${marks(chunk.size)})",
            owner, *chunk.toTypedArray(),
        ) { it.getString(0) }
    }
}

// A page's key of a level's row: its kind, keys and place, naming a folder
// or the song it shows.
private fun levelKey(kind: Int, lead: ByteArray, ties: ByteArray, position: Long, id: String) =
    RowKey(0, listOf(SqlValue.Int64(kind.toLong()), SqlValue.Blob(lead.toByteString()), SqlValue.Blob(ties.toByteString()), SqlValue.Int64(position)), id)

// A play's key of a level's song: its keys and place.
private fun songKey(lead: ByteArray, ties: ByteArray, position: Long, id: String) =
    RowKey(0, listOf(SqlValue.Blob(lead.toByteString()), SqlValue.Blob(ties.toByteString()), SqlValue.Int64(position)), id)

// A folder row's key names its folder, apart from every song's.
private const val FOLDER_KEY = "folder:"

private fun folderKey(id: String) = FOLDER_KEY + id

private fun ServerDirView.keyId(): String = if (kind == LEVEL_FOLDER) folderKey(dirId) else song?.id.orEmpty()

private fun ServerDirView.row(): LevelRow =
    song?.takeIf { kind == LEVEL_SONG }?.let(LevelRow::Song) ?: LevelRow.Folder(DirectoryRef(dirId, name, coverArt = coverArt, songCount = songCount))

sealed interface ServerName {
    data object Unknown : ServerName
    data object None : ServerName
    data class Known(val name: String) : ServerName
}

// A name for the server that people would recognise: Octo, the kind of
// server it says it is, or failing both its address.
fun serverName(isOcto: Boolean, type: String?, host: String): String = when {
    isOcto -> "Octo"
    !type.isNullOrBlank() -> type.trim().replaceFirstChar { it.uppercaseChar() }
    else -> host
}
