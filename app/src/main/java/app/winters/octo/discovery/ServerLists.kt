package app.winters.octo.discovery

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.winters.octo.animated.AnimatedArts
import app.winters.octo.catalog.AlbumEntryView
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.RENAME_SONG
import app.winters.octo.catalog.SERVER_ADOPT_SQL
import app.winters.octo.catalog.SERVER_COMPLETE_SQL
import app.winters.octo.catalog.SERVER_COPY_SQL
import app.winters.octo.catalog.SERVER_ENTRY_INSERT_SQL
import app.winters.octo.catalog.SERVER_FINDS_BY_SONG_SQL
import app.winters.octo.catalog.SERVER_FIRST_FINDS_SQL
import app.winters.octo.catalog.SERVER_MARKED_SQL
import app.winters.octo.catalog.SERVER_OWNED_SQL
import app.winters.octo.catalog.SERVER_FIRST_OF_SONG_SQL
import app.winters.octo.catalog.SERVER_GROUP_SQL
import app.winters.octo.catalog.SERVER_HEADS_SQL
import app.winters.octo.catalog.SERVER_HEAD_PUT_SQL
import app.winters.octo.catalog.SERVER_HEAD_SQL
import app.winters.octo.catalog.SERVER_KEY_SQL
import app.winters.octo.catalog.SERVER_LAST_POSITION_SQL
import app.winters.octo.catalog.SERVER_LINK_SQL
import app.winters.octo.catalog.SERVER_LOOKUP_SQL
import app.winters.octo.catalog.SERVER_NEWEST_SQL
import app.winters.octo.catalog.SERVER_NEW_GROUP_SQL
import app.winters.octo.catalog.SERVER_OPEN_GROUPS_SQL
import app.winters.octo.catalog.SERVER_PLACE_SQL
import app.winters.octo.catalog.SERVER_POOL_SQL
import app.winters.octo.catalog.SERVER_SETTLE_SQL
import app.winters.octo.catalog.SERVER_SUGGESTED_SQL
import app.winters.octo.catalog.SERVER_TABLES
import app.winters.octo.catalog.SERVER_TRACK_AT_SQL
import app.winters.octo.catalog.SERVER_UNLOOK_SQL
import app.winters.octo.catalog.SERVER_WAIT_SQL
import app.winters.octo.catalog.ServerHeadEntity
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.bindAll
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.logRoom
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.intOrNull
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.knownIds
import app.winters.octo.catalog.longOrNull
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.onlineArtwork
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.serverFactsSql
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.catalog.write
import app.winters.octo.data.Drains
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.device.explicitOf
import app.winters.octo.folders.LevelSongsLoader
import app.winters.octo.folders.SERVER_FOLDER_LIMIT
import app.winters.octo.folders.SERVER_LISTED
import app.winters.octo.folders.SERVER_SONG_LIMIT
import app.winters.octo.folders.ServerFolders
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.OnlineKind
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.AlbumWithSongs
import app.winters.octo.subsonic.DirectoryRef
import app.winters.octo.subsonic.INDEX_BASED_QUEUE
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.catalog.SERVER_ROWS_FROM
import app.winters.octo.catalog.SERVER_ROWS_WALK_FROM
import app.winters.octo.catalog.serverFoundWhere
import app.winters.octo.catalog.rawQuery
import app.winters.octo.catalog.serverShown
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
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// The disk one entry of a server list's rows takes with their indexes,
// measured as the file's pages once the log is checkpointed in, on a whole
// read of songs with long names that are all finds, under an owner led by
// the launch's mark at its longest, 19 digits (ServerListDiskConstantsTest:
// 1,317 bytes; a shorter mark takes a few bytes less). A folder
// level's row asks the same (it takes 959). The drained answer is on the
// disk already when its rows are asked for, so it is not asked again.
const val SERVER_BYTES_PER_ENTRY = 1_450L

// The room `entries` entries' rows take as a read writes them, `page` a
// transaction: their rows, and the log's room beside them.
internal fun serverRoom(entries: Long, page: Int): Long {
    val work = entries * SERVER_BYTES_PER_ENTRY
    return work + logRoom(work, minOf(entries, page.toLong()) * SERVER_BYTES_PER_ENTRY)
}

// The fewest bytes one entry of a drained answer takes: a song with short
// names (ServerListDiskConstantsTest: 320).
internal const val SERVER_JSON_BYTES_LEAST = 300L

// How many entries a drained answer of `written` bytes may hold, for the
// room its rows will need: never fewer than it holds, however short its
// songs' answers are.
internal fun drainedEntries(written: Long): Long = written / SERVER_JSON_BYTES_LEAST + 1

// How many entries one transaction of a read writes or resolves.
internal const val SERVER_PAGE = 500

// How many pool songs one transaction of the matching walk offers, and how
// many open groups one lookup reads at a time.
private const val POOL_PAGE = 50
private const val GROUP_PAGE = 200

// The most rows the links step writes for one entry: its own, a match
// group, two lookup keys and three pool keys, and one to spare.
private const val ENTRY_WRITES = 8

// How many pages a fill writes between asks for room.
private const val ROOM_EVERY_PAGES = 10

// The most radio sources one read notes, as many as the note keeps.
private const val SUGGESTIONS_NOTED = 5_000

// What a server list is.
enum class ServerListKind { Album, List, Station, Folder, PlayQueue }

val OnlineKind.listKind: ServerListKind
    get() = when (this) {
        OnlineKind.Album -> ServerListKind.Album
        OnlineKind.List -> ServerListKind.List
        OnlineKind.Station -> ServerListKind.Station
    }

// The server a read asks: its client, its source in the library and
// whether it keeps play queues by their place.
class ServerSide(val client: SubsonicClient, val sourceId: String, val indexedQueue: Boolean)

// The facts a list's heading shows: how many songs, how long they play in
// all (null when a length is not known), and how many are finds.
data class ServerFacts(val count: Int, val lengthMs: Long?, val finds: Int)

// Points in a read where a test can step in. Each does nothing in the app.
internal open class ServerHooks {
    // A page of entries was written.
    open suspend fun filled(owner: String, pages: Int) {}

    // A page of the resolution was written.
    open suspend fun resolved(owner: String, pages: Int) {}

    // A page of a whole read was copied into `owner`.
    open suspend fun copied(owner: String, pages: Int) {}

    // A start's answer for `owner` is drained, with nothing written.
    open suspend fun drained(owner: String) {}

    // A start resolved the entry at `position` alone, as one of its tries.
    open fun tried(position: Long) {}

    // A start resolved the entry at `position` to see whether it has the
    // song of a later entry.
    open fun checked(position: Long) {}

    // A start read two titles to compare them (sameSong).
    open fun parsed() {}
}

// Server lists (an album, an Octo list, a station, a folder's songs, the
// play queue) read into owner-keyed work tables and shown from them a page
// at a time, never held whole.
//
// A read drains the server's answer to a file first (Drains), then fills
// server_entry from the file a page at a time with the compact columns a
// playlist sync's read keeps, then works out once what each entry is, as
// Discovery's resolve does, over the whole read so a song can match through
// another entry's keys: an entry the library has from the server (unless
// the server marks it as outside the library) is that song; any other is
// matched by title against every library song filed under a key some
// unlinked entry looks up, in (key, id) order, each match group taking the
// first that is the same song; the rest are finds, kept once each, and a
// find the library downloaded since is the song it became. It all runs
// outside every lock: a swap between its transactions moves the ids already
// written with every other owner's (the relinks cover these tables), and
// the read is marked whole with the swap count it began at in its last
// transaction.
@Singleton
class ServerReads internal constructor(
    private val db: CatalogDatabase,
    private val drains: Drains,
    private val freeSpace: FreeSpace,
    private val owners: WorkOwners,
    private val merge: CatalogMerge,
    // The signed-in server, read once per read; null when none is.
    private val server: () -> ServerSide?,
    // Notes which radio source suggested each resolved song.
    private val suggested: (Map<String, String>) -> Unit = {},
    // Takes the server's own songs a read named that the copy lacks, on the
    // server of that source id (Discovery.ownedSongs).
    private val owned: (String, List<Song>) -> Unit = { _, _ -> },
    // Notes an album's animated art from its answer.
    private val animated: suspend (SubsonicClient, AlbumWithSongs) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val page: Int = SERVER_PAGE,
    // The server's folder levels, read as their page reads them.
    private val levels: Lazy<ServerFolders> = Lazy { error("no server folders") },
) {
    @Inject constructor(
        db: CatalogDatabase,
        drains: Drains,
        freeSpace: FreeSpace,
        owners: WorkOwners,
        merge: CatalogMerge,
        sessions: SessionRepository,
        discovery: Lazy<Discovery>,
        animatedArts: AnimatedArts,
        levels: Lazy<ServerFolders>,
    ) : this(
        db, drains, freeSpace, owners, merge,
        server = {
            (sessions.state.value as? SessionState.SignedIn)?.session?.let { session ->
                ServerSide(session.client, serverSourceId(session.client.primaryUrl), session.extensions.any { it.startsWith("$INDEX_BASED_QUEUE:") })
            }
        },
        suggested = { discovery.get().noteSuggestions(it) },
        owned = { sourceId, songs -> discovery.get().ownedSongs(sourceId, songs) },
        animated = { client, album -> animatedArts.note(client, album.id, album.animatedArt) },
        levels = levels,
    )

    internal var hooks = ServerHooks()

    private val spool = Json { ignoreUnknownKeys = true }

    // Reads list `id` of `kind` (a folder's id, null for the server's top;
    // the play queue takes none) into `owner`, in place of what the owner
    // held, and answers its head; null when no server is signed in. A read
    // that fails leaves the owner empty and throws what the server's own
    // call throws, or NotEnoughSpace when the phone runs short of room.
    suspend fun read(kind: ServerListKind, id: String?, owner: String): ServerHeadEntity? = withContext(Dispatchers.IO) {
        val side = server() ?: return@withContext null
        try {
            clear(owner)
            val seq = owners.next()
            val read = Reading(owner)
            val got = when (kind) {
                ServerListKind.List, ServerListKind.Station -> listInto(side, kind, id.orEmpty(), read)
                ServerListKind.Album -> albumInto(side, id.orEmpty(), read)
                ServerListKind.PlayQueue -> queueInto(side, read)
                ServerListKind.Folder -> gatherInto(side, id, read)
            }
            val head = got.copy(owner = owner, readSeq = seq)
            writing { putHead(head) }
            val baseline = resolve(read, side.sourceId)
            writing { db.write(SERVER_COMPLETE_SQL, baseline, head.currentAt, head.positionMs, owner) }
            head.copy(complete = true, swapCount = baseline)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { clear(owner) } }
            throw e
        }
    }

    // The newest whole read of a list by any owner, or null with none.
    suspend fun newest(kind: ServerListKind, id: String): String? = withContext(Dispatchers.IO) {
        db.read(SERVER_NEWEST_SQL, kind.name, id) { it.getString(0) }.firstOrNull()
    }

    suspend fun head(owner: String): ServerHeadEntity? = withContext(Dispatchers.IO) { headNow(owner) }

    private fun headNow(owner: String): ServerHeadEntity? = db.read(SERVER_HEAD_SQL, owner) { c ->
        ServerHeadEntity(
            owner = c.getString(0), kind = c.getString(1), listId = c.getString(2), title = c.getString(3),
            artist = c.stringOrNull(4), artistId = c.stringOrNull(5), year = c.intOrNull(6), songCount = c.intOrNull(7),
            cover = c.stringOrNull(8), cut = c.getInt(9) == 1, complete = c.getInt(10) == 1, readSeq = c.getLong(11),
            swapCount = c.getLong(12), currentAt = c.longOrNull(13), positionMs = c.getLong(14),
        )
    }.firstOrNull()

    // Copies the whole read `from` into `to`, a page to a transaction, with
    // its head; `from` is pinned meanwhile, so a clear of it waits. Null,
    // copying nothing, when `from` holds no whole read.
    suspend fun copy(from: String, to: String): ServerHeadEntity? = withContext(Dispatchers.IO) {
        owners.pin(from)
        try {
            val head = headNow(from)?.takeIf { it.complete } ?: return@withContext null
            clear(to)
            var after = -1L
            var pages = 0
            while (true) {
                val last = writing {
                    db.withTransaction {
                        db.write(SERVER_COPY_SQL, to, from, after, page)
                        db.readLong(SERVER_LAST_POSITION_SQL, to)
                    }
                }
                if (last <= after) break
                after = last
                hooks.copied(to, ++pages)
            }
            val copied = head.copy(owner = to, readSeq = owners.next())
            writing { putHead(copied) }
            copied
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { clear(to) } }
            throw e
        } finally {
            withContext(NonCancellable) { owners.unpin(from) }
        }
    }

    // The copy a queue's fill walks, under its own owner: the owner's whole
    // read when it has one, else the answer its play's start drained and
    // handed it, else a copy of the newest whole read of the list, else the
    // list read from the server. Answers its head.
    suspend fun fill(spec: ListSpec, owner: String): ServerHeadEntity {
        head(owner)?.takeIf { it.complete }?.let { return it }
        handedStarts.remove(owner)?.let { return copyDrained(it, owner) }
        val (kind, id) = what(spec)
        if (spec is ListSpec.Online) newest(kind, id.orEmpty())?.takeIf { it != owner }?.let { stored -> copy(stored, owner)?.let { return it } }
        return read(kind, id, owner) ?: throw NotSignedIn()
    }

    // The copy a queue's fill walks of a server folder's own songs, under
    // its own owner: the folder read anew as its page reads it, its songs
    // in the spec's order (the server's when it names none), and their
    // loader. A read that fails leaves the owner empty and throws.
    suspend fun fillLevel(spec: ListSpec.ServerFolder, owner: String): LevelSongsLoader {
        val folders = levels.get()
        folders.read(spec.folderId, owner, spec.order ?: SERVER_LISTED) ?: throw NotSignedIn()
        return folders.songs(owner)
    }

    // Lets an owner's rows go, a page at a time, and any answer a start
    // handed it; rows a copy pins go when the copy lets them go.
    suspend fun clear(owner: String) {
        dropStart(owner)
        owners.clear(owner) { for (table in SERVER_TABLES) db.deleteOwned(table, owner) }
    }

    // The rows an owner's read shows, in its order, keyed by place. A copy
    // made from the answer its play's start read keeps that answer's
    // places, so the start's key names its row by place alone.
    fun loader(owner: String, libraryOnly: Boolean = false): ServerRowsLoader = ServerRowsLoader(db, owner, libraryOnly, owner in samePlaces)

    // The facts of the rows an owner's read shows, in one statement.
    suspend fun facts(owner: String, libraryOnly: Boolean = false): ServerFacts = withContext(Dispatchers.IO) {
        db.read(serverFactsSql(libraryOnly), owner) { c -> ServerFacts(c.getInt(0), c.getLong(1).takeIf { _ -> c.getInt(2) == 0 }, c.getInt(3)) }.single()
    }

    // The shown row whose song hashes smallest under `seed`: a song listed
    // twice hashes the same both times, so each song the list shows is as
    // likely as any other, whatever its rows. A page at a time.
    suspend fun shuffledRow(loader: ListLoader<TrackEntity>, seed: Long): Keyed<TrackEntity>? {
        var best: Keyed<TrackEntity>? = null
        var low = 0L
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, page)
            for (row in rows) {
                val h = seededHash(seed, row.row.id)
                if (best == null || h < low) {
                    best = row
                    low = h
                }
            }
            if (rows.size < page) return best
            after = rows.last().key
        }
    }

    // The finds an owner's read shows, a page of songs at a time.
    suspend fun findPages(owner: String, each: suspend (List<TrackEntity>) -> Unit) {
        val loader = loader(owner)
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, page)
            val finds = rows.map { it.row }.filter { isFind(it.id) }
            if (finds.isNotEmpty()) each(finds)
            if (rows.size < page) return
            after = rows.last().key
        }
    }

    // Whether any find the read shows passes `test`, stopping at the first
    // that does, a page at a time.
    suspend fun anyFind(owner: String, test: (String) -> Boolean): Boolean {
        val loader = loader(owner)
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, page)
            if (rows.any { isFind(it.row.id) && test(it.row.id) }) return true
            if (rows.size < page) return false
            after = rows.last().key
        }
    }

    // ---- Reads ----

    // One read under way: its owner, how far its rows go, and the radio
    // sources its songs carry by server song, until it is resolved: the
    // last SUGGESTIONS_NOTED, as many as the note keeps.
    private class Reading(val owner: String) {
        var position = 0L
        var pages = 0
        val carried = ArrayDeque<Pair<String, String>>()

        fun carry(song: String, by: String) {
            if (carried.size == SUGGESTIONS_NOTED) carried.removeFirst()
            carried.addLast(song to by)
        }
    }

    // Writes a page of a read's songs and asks for room every few pages.
    private suspend fun fillPage(read: Reading, songs: List<Song>) = fillEntries(read, songs.map { drainedOf(it, keys = false) })

    private suspend fun fillEntries(read: Reading, entries: List<DrainedEntry>) {
        fill(read, entries)
        if (++read.pages % ROOM_EVERY_PAGES == 0) need(read.position)
        hooks.filled(read.owner, read.pages)
    }

    private suspend fun listInto(side: ServerSide, kind: ServerListKind, id: String, read: Reading): ServerHeadEntity {
        val answer = drains.use("server-${kind.name.lowercase()}-${owners.next()}.json") { file ->
            side.client.playlistInto(id, file, room = ::roomForDrained, pageSize = page) { songs -> fillPage(read, songs) }
        }
        return headOf(kind, id, answer.name, null, null, null, answer.songCount, onlineArtwork(side.sourceId, answer.coverArt))
    }

    private suspend fun albumInto(side: ServerSide, id: String, read: Reading): ServerHeadEntity {
        val album = drains.use("server-album-${owners.next()}.json") { file ->
            side.client.albumInto(id, file, room = ::roomForDrained, pageSize = page) { songs -> fillPage(read, songs) }
        }
        animated(side.client, album)
        return headOf(ServerListKind.Album, id, album.name, album.artist, album.artistId, album.year, album.songCount, onlineArtwork(side.sourceId, album.coverArt))
    }

    // The play queue saved on the server, and the entry it was on as
    // today's offer read it: by place, or the first entry with the current
    // song's id, else the first entry from its start.
    private suspend fun queueInto(side: ServerSide, read: Reading): ServerHeadEntity {
        val head = drains.use("server-queue-${owners.next()}.json") { file ->
            side.client.drainPlayQueue(side.indexedQueue, file, room = ::roomForDrained)
            side.client.readPlayQueue(file, side.indexedQueue, page) { songs -> fillPage(read, songs) }
        }
        val size = read.position
        val (at, ms) = if (side.indexedQueue) {
            (head.currentIndex ?: 0).toLong().coerceIn(0, (size - 1).coerceAtLeast(0)) to head.positionMs.coerceAtLeast(0)
        } else {
            val found = head.currentId?.let { db.read(SERVER_FIRST_OF_SONG_SQL, read.owner, it) { c -> c.longOrNull(0) }.firstOrNull() }
            if (found != null) found to head.positionMs.coerceAtLeast(0) else 0L to 0L
        }
        return headOf(ServerListKind.PlayQueue, side.sourceId, "", null, null, null, size.toInt(), null).copy(currentAt = at, positionMs = ms)
    }

    // Every song under a server folder, in the order today's gather plays
    // them: each folder inside it in turn, then its own songs, at most
    // SERVER_SONG_LIMIT songs and SERVER_FOLDER_LIMIT folders opened, a folder seen
    // twice opened once. Each folder's answer is drained to its own file and
    // read twice, once for its folders and, after them, for its own songs,
    // which go to the gather's file; only after the last folder are they
    // written into the owner's rows.
    private suspend fun gatherInto(side: ServerSide, folderId: String?, read: Reading): ServerHeadEntity {
        var walked: Pair<Int, Boolean> = 0 to false
        drains.use("server-gather-${owners.next()}.json") { out ->
            out.parentFile?.mkdirs()
            out.bufferedWriter().use { writer ->
                walked = gather(side, folderId) { song ->
                    writer.write(spool.encodeToString(Song.serializer(), song))
                    writer.write("\n")
                }
            }
            need(walked.first.toLong())
            out.bufferedReader().use { reader ->
                val buffer = ArrayList<Song>(page)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    buffer += spool.decodeFromString(Song.serializer(), line)
                    if (buffer.size == page) {
                        fillPage(read, buffer.toList())
                        buffer.clear()
                    }
                }
                if (buffer.isNotEmpty()) fillPage(read, buffer.toList())
            }
        }
        return headOf(ServerListKind.Folder, folderId.orEmpty(), "", null, null, null, walked.first, null).copy(cut = walked.second)
    }

    // The gather's walk, over the network alone: hands each song to `song`
    // in play order, and answers how many it gathered and whether it
    // stopped at a limit.
    private suspend fun gather(side: ServerSide, folderId: String?, song: suspend (Song) -> Unit): Pair<Int, Boolean> {
        var gathered = 0
        var opened = 0
        var cut = false
        val seen = HashSet<String>()
        suspend fun walk(id: String?) {
            drains.use("server-folder-${owners.next()}.json") { file ->
                side.client.drainFolder(id, file, room = ::roomForDrained)
                val folders = side.client.readFolder(file, id == null) { }
                for (folder in folders.map(DirectoryRef::id)) {
                    if (cut) return@use
                    if (!seen.add(folder)) continue
                    if (gathered >= SERVER_SONG_LIMIT || opened >= SERVER_FOLDER_LIMIT) {
                        cut = true
                        return@use
                    }
                    opened++
                    walk(folder)
                }
                if (cut) return@use
                side.client.readFolder(file, id == null) { found ->
                    if (gathered < SERVER_SONG_LIMIT) {
                        song(found)
                        gathered++
                    } else {
                        cut = true
                    }
                }
            }
        }
        walk(folderId)
        return gathered to cut
    }

    private fun headOf(kind: ServerListKind, id: String, title: String, artist: String?, artistId: String?, year: Int?, songCount: Int?, cover: String?) =
        ServerHeadEntity(
            owner = "", kind = kind.name, listId = id, title = title, artist = artist, artistId = artistId?.takeIf(String::isNotEmpty),
            year = year, songCount = songCount, cover = cover?.takeIf(String::isNotEmpty), readSeq = 0, swapCount = 0,
        )

    // Notes the radio sources a page's entries carry, then writes the page
    // after the read's rows.
    private suspend fun fill(read: Reading, entries: List<DrainedEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { e -> e.suggestedBy?.let { read.carry(e.songId, it) } }
        val from = read.position
        writing {
            db.withTransaction {
                db.openHelper.writableDatabase.compileStatement(SERVER_ENTRY_INSERT_SQL).use { st ->
                    entries.forEachIndexed { i, e ->
                        st.clearBindings()
                        st.bindAll(
                            arrayOf<Any?>(
                                read.owner, from + i, e.songId, e.external, e.title, e.artist, e.lengthMs, e.album, e.albumId, e.artistId,
                                e.coverId, e.mimeType, e.bitrate, e.explicit,
                            ),
                        )
                        st.executeInsert()
                    }
                }
            }
        }
        read.position += entries.size
    }

    private suspend fun putHead(head: ServerHeadEntity) = db.withTransaction {
        db.write(
            SERVER_HEAD_PUT_SQL, head.owner, head.kind, head.listId, head.title, head.artist, head.artistId, head.year, head.songCount,
            head.cover, head.cut, head.complete, head.readSeq, head.swapCount, head.currentAt, head.positionMs,
        )
    }

    // ---- Resolution, once per read ----

    // Answers the swap count it began at.
    private suspend fun resolve(read: Reading, sourceId: String): Long {
        val owner = read.owner
        val baseline = db.withTransaction { db.workDao().swapCount() }
        val at = now()
        val open = links(owner, sourceId)
        if (open > 0) pool(owner, open)
        settle(owner)
        finds(owner, sourceId, at)
        ownedSongs(owner, sourceId)
        note(owner, read.carried)
        withContext(NonCancellable) { for (table in listOf("server_key", "server_match", "server_lookup")) db.deleteOwned(table, owner) }
        return baseline
    }

    private class Head(val position: Long, val songId: String, val external: Boolean, val title: String, val artist: String, val lengthMs: Long)

    private fun heads(owner: String, from: Long) = db.read(SERVER_HEADS_SQL, owner, from, page) { c ->
        Head(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.getString(4), c.getLong(5))
    }

    // Step one: an entry the library has from the server is that song; any
    // other joins the match group of its title, artist and length, made
    // with its lookup keys when new; an entry with no link adds its title's
    // keys to the pool. Answers how many groups are open.
    private suspend fun links(owner: String, sourceId: String): Long {
        var open = 0L
        var from = 0L
        var pages = 0
        while (true) {
            val rows = heads(owner, from)
            if (rows.isEmpty()) return open
            for (part in rows.chunked((page / ENTRY_WRITES).coerceAtLeast(1))) {
                writing {
                    db.withTransaction {
                        val links = db.sourceDao().libraryLinks(sourceId, part.map { it.songId }.distinct()).associate { it.serverId to it.trackId }
                        val keys = HashMap<String, String>()
                        links.values.distinct().chunked(ID_CHUNK).forEach { chunk ->
                            db.read("SELECT id, relinkKey FROM track WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) to it.getString(1) }
                                .forEach { (id, key) -> keys[id] = key }
                        }
                        for (row in part) {
                            val link = links[row.songId]
                            if (!row.external && link != null) {
                                db.write(SERVER_LINK_SQL, link, keys[link].orEmpty(), owner, row.position)
                            } else {
                                val group = groupOf(owner, row) ?: run {
                                    db.write(SERVER_NEW_GROUP_SQL, owner, row.title, row.artist, row.lengthMs)
                                    val made = groupOf(owner, row)!!
                                    SongIdentity.titleLookupKeys(row.title, row.artist).forEach { db.write(SERVER_LOOKUP_SQL, owner, it, made) }
                                    open++
                                    made
                                }
                                db.write(SERVER_WAIT_SQL, group, owner, row.position)
                            }
                            if (link == null) titleKeys(row.title).forEach { db.write(SERVER_KEY_SQL, owner, it) }
                        }
                    }
                }
            }
            hooks.resolved(owner, ++pages)
            if (rows.size < page) return open
            from = rows.last().position + 1
        }
    }

    private fun groupOf(owner: String, row: Head): Long? = db.read(SERVER_GROUP_SQL, owner, row.title, row.artist, row.lengthMs) { it.getLong(0) }.firstOrNull()

    private class PoolSong(val key: String, val id: String, val title: String, val artist: String, val durationMs: Long, val relinkKey: String)

    private class Group(val id: Long, val title: String, val artist: String, val lengthMs: Long)

    // Step two: the pool in (key, id) order; each song is offered to every
    // open group sharing a lookup key with it, and a group takes the first
    // that is the same song. A page of pool songs is one transaction, which
    // offers a song only while it is still in the library. Stops once no
    // group is open.
    private suspend fun pool(owner: String, groups: Long) {
        var open = groups
        var lastKey = ""
        var lastId = ""
        var pages = 0
        while (open > 0) {
            val songs = db.read(SERVER_POOL_SQL, owner, lastKey, lastKey, lastId, POOL_PAGE) { c ->
                PoolSong(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5))
            }
            if (songs.isEmpty()) return
            writing {
                db.withTransaction {
                    for (song in songs) {
                        // A swap since the page was read may have renamed the
                        // song; it is offered again under its new id if the
                        // walk still comes to it.
                        if (db.readLong("SELECT EXISTS(SELECT 1 FROM track WHERE id = ?)", song.id) == 0L) continue
                        for (lookup in SongIdentity.titleLookupKeys(song.title, song.artist)) {
                            var after = Long.MIN_VALUE
                            while (true) {
                                val found = db.read(SERVER_OPEN_GROUPS_SQL, owner, lookup, after, GROUP_PAGE) { c ->
                                    Group(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
                                }
                                for (group in found) {
                                    if (!sameSong(group.title, group.artist, group.lengthMs, song.title, song.artist, song.durationMs)) continue
                                    if (db.write(SERVER_PLACE_SQL, song.id, song.relinkKey, group.id) == 1) {
                                        SongIdentity.titleLookupKeys(group.title, group.artist).forEach { db.write(SERVER_UNLOOK_SQL, owner, it, group.id) }
                                        open--
                                    }
                                }
                                if (found.size < GROUP_PAGE) break
                                after = found.last().id
                            }
                        }
                    }
                }
            }
            hooks.resolved(owner, ++pages)
            if (songs.size < POOL_PAGE) return
            lastKey = songs.last().key
            lastId = songs.last().id
        }
    }

    // Step three: each waiting entry takes its group's song, or is a find.
    private suspend fun settle(owner: String) {
        var from = 0L
        while (true) {
            val rows = heads(owner, from)
            if (rows.isEmpty()) return
            val to = rows.last().position + 1
            writing { db.withTransaction { db.write(SERVER_SETTLE_SQL, owner, from, to) } }
            if (rows.size < page) return
            from = to
        }
    }

    // Step four: each find is kept once, from its first entry, in the order
    // first entries come; a find the library downloaded since is shown as
    // the song it became.
    private suspend fun finds(owner: String, sourceId: String, at: Long) {
        var afterSong = ""
        var afterPosition = -1L
        var previous: String? = null
        while (true) {
            val rows = db.read(SERVER_FINDS_BY_SONG_SQL, owner, afterSong, afterSong, afterPosition, page) { c -> c.getString(0) to c.getLong(1) }
            if (rows.isEmpty()) break
            val first = rows.filter { (song, _) -> (song != previous).also { previous = song } }.map { it.second }
            if (first.isNotEmpty()) {
                writing {
                    db.withTransaction {
                        first.chunked(ID_CHUNK).forEach { chunk ->
                            db.write("UPDATE server_entry SET firstFind = 1 WHERE owner = ? AND position IN (${marks(chunk.size)})", owner, *chunk.toTypedArray())
                        }
                    }
                }
            }
            if (rows.size < page) break
            afterSong = rows.last().first
            afterPosition = rows.last().second
        }
        var after = -1L
        while (true) {
            val rows = db.read(SERVER_FIRST_FINDS_SQL, owner, after, page) { c ->
                c.getLong(0) to OnlineSongEntity(
                    id = findId(c.getString(1)), sourceId = sourceId, nativeId = c.getString(1), title = c.getString(2), artist = c.getString(3),
                    album = c.getString(5), albumId = c.stringOrNull(6), artistId = c.stringOrNull(7), durationMs = c.getLong(4),
                    coverId = c.stringOrNull(8), mimeType = c.stringOrNull(9), bitrate = c.intOrNull(10), seenAt = at,
                    explicit = if (c.isNull(11)) null else c.getInt(11) == 1,
                )
            }
            if (rows.isEmpty()) return
            val kept = writing { db.onlineDao().keep(rows.map { it.second }) }
            val adopted = kept.filter { it.adoptedId.isNotEmpty() }
            if (adopted.isNotEmpty()) {
                val present = db.knownIds(adopted.map { it.adoptedId })
                writing {
                    db.withTransaction {
                        adopted.filter { it.adoptedId in present }.forEach { find -> db.write(SERVER_ADOPT_SQL, find.adoptedId, owner, find.id) }
                    }
                }
            }
            if (rows.size < page) return
            after = rows.last().first
        }
    }

    // Step five: in a read that carries Octo's marks, a find the server did
    // not mark is a song of its own library the copy lacks (see
    // resolveSongs), handed on to be noted as owned and brought in.
    private fun ownedSongs(owner: String, sourceId: String) {
        if (db.readLong(SERVER_MARKED_SQL, owner) != 1L) return
        val songs = db.read(SERVER_OWNED_SQL, owner, OWNED_KEPT) { c ->
            Song(
                id = c.getString(0), title = c.getString(1), artist = c.getString(2), duration = (c.getLong(3) / 1000).toInt(),
                album = c.getString(4), albumId = c.stringOrNull(5), artistId = c.stringOrNull(6), coverArt = c.stringOrNull(7),
            )
        }
        if (songs.isNotEmpty()) owned(sourceId, songs)
    }

    // The radio sources the read's songs carry, noted by the songs they
    // resolved to.
    private fun note(owner: String, carried: List<Pair<String, String>>) {
        if (carried.isEmpty()) return
        val by = carried.toMap()
        val resolved = HashMap<String, String>()
        for ((song, source) in by) db.read(SERVER_SUGGESTED_SQL, owner, song) { it.getString(1) }.firstOrNull()?.let { resolved[it] = source }
        if (resolved.isNotEmpty()) suggested(resolved)
    }

    // ---- A play's start, read with no write ----

    // The answers starts drained, by the owner whose build copies each.
    private val handedStarts = ConcurrentHashMap<String, ServerStart>()

    // Owners whose copy was made from the answer their play's start read.
    private val samePlaces: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Reads list `id` of `kind` from the server for a play's start, into a
    // drained file and nothing else: its heading, its entries in order with
    // their keys, and, for the play queue, the entry it was on. Null when no
    // server is signed in. A start that fails leaves no file and throws
    // what the server's own call throws, or NotEnoughSpace.
    suspend fun drainStart(kind: ServerListKind, id: String?, owner: String): ServerStart? = withContext(Dispatchers.IO) {
        val side = server() ?: return@withContext null
        val file = drains.file("start-${owners.next()}.jsonl")
        try {
            file.parentFile?.mkdirs()
            val out = DrainWriter(file)
            val head = try {
                when (kind) {
                    ServerListKind.List, ServerListKind.Station -> {
                        val answer = drains.use("server-${kind.name.lowercase()}-${owners.next()}.json") { f ->
                            side.client.playlistInto(id.orEmpty(), f, room = ::roomForDrained, pageSize = page) { songs -> out.write(songs) }
                        }
                        headOf(kind, id.orEmpty(), answer.name, null, null, null, answer.songCount, onlineArtwork(side.sourceId, answer.coverArt))
                    }
                    ServerListKind.Album -> {
                        val album = drains.use("server-album-${owners.next()}.json") { f ->
                            side.client.albumInto(id.orEmpty(), f, room = ::roomForDrained, pageSize = page) { songs -> out.write(songs) }
                        }
                        animated(side.client, album)
                        headOf(ServerListKind.Album, id.orEmpty(), album.name, album.artist, album.artistId, album.year, album.songCount, onlineArtwork(side.sourceId, album.coverArt))
                    }
                    ServerListKind.PlayQueue -> {
                        val queue = drains.use("server-queue-${owners.next()}.json") { f ->
                            side.client.drainPlayQueue(side.indexedQueue, f, room = ::roomForDrained)
                            side.client.readPlayQueue(f, side.indexedQueue, page) { songs -> out.write(songs) }
                        }
                        out.close()
                        val size = out.count.toLong()
                        val (at, ms) = if (side.indexedQueue) {
                            (queue.currentIndex ?: 0).toLong().coerceIn(0, (size - 1).coerceAtLeast(0)) to queue.positionMs.coerceAtLeast(0)
                        } else {
                            val found = queue.currentId?.let { current -> firstOfSong(file, current) }
                            if (found != null) found to queue.positionMs.coerceAtLeast(0) else 0L to 0L
                        }
                        headOf(ServerListKind.PlayQueue, side.sourceId, "", null, null, null, size.toInt(), null).copy(currentAt = at, positionMs = ms)
                    }
                    ServerListKind.Folder -> {
                        val (gathered, cut) = gather(side, id) { song -> out.write(listOf(song)) }
                        headOf(ServerListKind.Folder, id.orEmpty(), "", null, null, null, gathered, null).copy(cut = cut)
                    }
                }
            } finally {
                out.close()
            }
            need(out.count.toLong())
            val start = ServerStart(head, out.count, file, out.offsets(), side.sourceId)
            hooks.drained(owner)
            start
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    // The first place a server song has in a drained file, read as strings.
    private fun firstOfSong(file: File, songId: String): Long? {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { i, line -> if (line.isNotEmpty() && drainJson.decodeFromString(DrainedEntry.serializer(), line).songId == songId) return i.toLong() }
        }
        return null
    }

    // How a start's entries are worked out, read-only, the copy's way.
    fun resolver(start: ServerStart): StartResolver = StartResolver(db, start, hooks, now())

    // The radio sources a start's songs carry, noted in memory as a read
    // notes them.
    fun noteStarted(entries: List<StartEntry>) {
        val by = entries.mapNotNull { e -> e.suggestedBy?.let { e.trackId to it } }.toMap()
        if (by.isNotEmpty()) suggested(by)
    }

    // `owner`'s build takes over a start's answer: its copy is made from
    // the same file, with no second request, and the file goes with it.
    fun hand(owner: String, start: ServerStart) {
        handedStarts.put(owner, start)?.takeIf { it !== start }?.file?.delete()
    }

    // The answer a start handed `owner`'s build, while no copy took it.
    fun handed(owner: String): ServerStart? = handedStarts[owner]

    // Lets the answer a start handed `owner` go unread: its play did not
    // start, or its build ended. Writes nothing.
    fun dropStart(owner: String) {
        handedStarts.remove(owner)?.file?.delete()
        samePlaces.remove(owner)
    }

    // The build's copy of the answer its play's start drained: the rows
    // written from the file a page at a time and resolved as any read is,
    // with no second request. The file goes once the copy ends, however it
    // ends.
    private suspend fun copyDrained(start: ServerStart, owner: String): ServerHeadEntity = withContext(Dispatchers.IO) {
        try {
            clear(owner)
            val seq = owners.next()
            val read = Reading(owner)
            start.pages(page) { entries -> fillEntries(read, entries) }
            val head = start.head.copy(owner = owner, readSeq = seq)
            writing { putHead(head) }
            val baseline = resolve(read, start.sourceId)
            writing { db.write(SERVER_COMPLETE_SQL, baseline, head.currentAt, head.positionMs, owner) }
            samePlaces += owner
            head.copy(complete = true, swapCount = baseline)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { clear(owner) } }
            throw e
        } finally {
            start.file.delete()
        }
    }

    // ---- Room ----

    // The room `entries` entries' rows need (serverRoom).
    private fun need(entries: Long) {
        val need = serverRoom(entries, page)
        if (freeSpace.bytes() < need) throw NotEnoughSpace(need, SpaceFor.List)
    }

    private fun roomForDrained(written: Long) = need(drainedEntries(written))

    private suspend fun <T> writing(block: suspend () -> T): T = withContext(WriteLabels.of("server list page")) {
        try {
            block()
        } catch (e: SQLiteFullException) {
            throw NotEnoughSpace(null, SpaceFor.List)
        }
    }
}

// No server is signed in to read a list from.
class NotSignedIn : Exception("no server is signed in")

// What a list spec reads, and the server's id for it.
internal fun what(spec: ListSpec): Pair<ServerListKind, String?> = when (spec) {
    is ListSpec.Online -> spec.kind.listKind to spec.id
    is ListSpec.ServerFolder -> if (spec.recursive) ServerListKind.Folder to spec.folderId else error("a server folder's own songs are read by fillLevel")
    ListSpec.ServerQueue -> ServerListKind.PlayQueue to null
    else -> error("not a server list: ${spec.javaClass.simpleName}")
}

// A hash of a song's id under a seed, spread evenly over every Long for
// any seed: FNV-1a over its characters, then two rounds of splitmix64's mix
// with the seed between, so a new seed orders songs anew.
internal fun seededHash(seed: Long, id: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (c in id) h = (h xor c.code.toLong()) * 0x100000001b3L
    return mix64(h xor mix64(seed))
}

private fun mix64(value: Long): Long {
    var z = value + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

// The rows one owner's read shows, keyed by their place in the read: each
// row whose song is resolved and still there, the first of the owner's rows
// with its song. A play picks its start and a fill walks it here.
class ServerRowsLoader internal constructor(
    private val db: CatalogDatabase,
    private val owner: String,
    libraryOnly: Boolean,
    // The read was copied from the answer its play's start read.
    private val samePlaces: Boolean = false,
) : ListLoader<TrackEntity> {
    private val reads = object : PartReads<AlbumEntryView> {
        override suspend fun rows(sql: String, args: List<SqlValue>) = db.listsDao().albumRows(rawQuery(sql, args))
        override suspend fun count(sql: String, args: List<SqlValue>) = db.listsDao().count(rawQuery(sql, args))
        override suspend fun ids(sql: String, args: List<SqlValue>) = db.listsDao().ids(rawQuery(sql, args))
    }
    private val select = "e.position AS position, e.trackId AS shownId, (t.id IS NULL) AS isFind, NULL AS entryOrder, " + songColumns()
    private val keyOf: (AlbumEntryView) -> RowKey = { row -> RowKey(0, listOf(SqlValue.Int64(row.position)), row.shownId) }
    private val shown = serverFoundWhere(libraryOnly)
    private val part = IndexPart(
        0,
        SERVER_ROWS_WALK_FROM,
        serverShown() + if (libraryOnly) listOf("e.kind = 1") else emptyList(),
        listOf(SqlValue.Text(owner)),
        emptyList(),
        listOf("e.position"),
        false,
        select,
        reads,
        keyOf,
        idColumn = "e.trackId",
    )
    private val inner = PartsLoader<AlbumEntryView, TrackEntity>(
        { listOf(part) },
        arrayOf("server_entry", "track", "online_song"),
        SqlOrder.rows(),
        songsOnce = true,
        rowOf = { it.track },
        idOf = { it.id },
        smallRows = { sql, args -> db.listsDao().albumRows(rawQuery(sql, args)) },
        smallKey = keyOf,
    )

    override val tables get() = inner.tables
    override val order get() = inner.order
    override val songsOnce get() = true
    override suspend fun count() = inner.count()
    override suspend fun survey(anchor: RowKey?): Survey = inner.survey(anchor)
    override suspend fun after(key: RowKey?, limit: Int) = inner.after(key, limit)
    override suspend fun before(key: RowKey, limit: Int) = inner.before(key, limit)
    override suspend fun keyAt(position: Int) = inner.keyAt(position)
    override suspend fun small() = inner.small()

    // A song's row, and which songs the rows hold, are found on the song
    // index rather than walked to.
    override suspend fun keyOf(trackId: String): RowKey? = withContext(Dispatchers.IO) {
        db.read("SELECT e.position FROM $SERVER_ROWS_FROM WHERE $shown AND e.trackId = ? LIMIT 1", owner, trackId) { it.getLong(0) }
            .firstOrNull()?.let { RowKey(0, listOf(SqlValue.Int64(it)), trackId) }
    }

    override suspend fun present(ids: List<String>): Set<String> = withContext(Dispatchers.IO) {
        ids.distinct().chunked(ID_CHUNK).flatMapTo(HashSet()) { chunk ->
            db.read("SELECT e.trackId FROM $SERVER_ROWS_FROM WHERE $shown AND e.trackId IN (${marks(chunk.size)})", owner, *chunk.toTypedArray()) { it.getString(0) }
        }
    }
    override fun trackIdOf(row: TrackEntity) = row.id

    // A key another reader of the same read handed out names the row still
    // at its place when that row still holds its song; else that song's
    // first row here, or null when the read no longer shows it. A copy of
    // the answer the key's start read keeps its places: the key names its
    // place whatever the copy shows there now, so the song playing stays
    // the start's and the list goes on from its place.
    override suspend fun rekey(key: RowKey): RowKey? {
        val at = (key.values.firstOrNull() as? SqlValue.Int64)?.v
        if (at != null) {
            val there = withContext(Dispatchers.IO) { db.read(SERVER_TRACK_AT_SQL, owner, at) { it.stringOrNull(0) }.firstOrNull() }
            if (there == key.id) return key
            // The same answer: the place names the row, with the song the
            // copy has there now.
            if (samePlaces) return key.copy(id = there ?: key.id)
        }
        return keyOf(key.id)
    }
}

// ---- A play's start, read with no write ----

private val drainJson = Json { ignoreUnknownKeys = true }

// One entry of a server's answer as a start drains it, with the keys its
// title is looked up by (titleKeys for the pool, titleLookupKeys with its
// artist for its match group), worked out once as it is drained, so no
// pass before sound reads a title again. The build's copy writes its rows
// from the same lines.
@Serializable
internal class DrainedEntry(
    @SerialName("i") val songId: String,
    @SerialName("x") val external: Boolean = false,
    @SerialName("t") val title: String = "",
    @SerialName("a") val artist: String = "",
    @SerialName("l") val lengthMs: Long = 0,
    @SerialName("b") val album: String = "",
    @SerialName("bi") val albumId: String? = null,
    @SerialName("ai") val artistId: String? = null,
    @SerialName("c") val coverId: String? = null,
    @SerialName("m") val mimeType: String? = null,
    @SerialName("r") val bitrate: Int? = null,
    @SerialName("e") val explicit: Boolean? = null,
    @SerialName("s") val suggestedBy: String? = null,
    @SerialName("k") val titleKeys: List<String> = emptyList(),
    @SerialName("u") val lookupKeys: List<String> = emptyList(),
)

// A server song as a read writes it, with its keys when `keys`.
internal fun drainedOf(song: Song, keys: Boolean): DrainedEntry = DrainedEntry(
    songId = song.id,
    external = song.isExternal,
    title = song.title,
    artist = song.artist.orEmpty(),
    lengthMs = knownLengthMs(song),
    album = song.album.orEmpty(),
    albumId = song.albumId?.takeIf(String::isNotEmpty),
    artistId = song.artistId?.takeIf(String::isNotEmpty),
    coverId = song.coverArt?.takeIf(String::isNotEmpty),
    mimeType = mimeTypeOf(song),
    bitrate = song.bitRate?.takeIf { it > 0 },
    explicit = explicitOf(song.explicitStatus.orEmpty()),
    suggestedBy = song.octoSuggestedBy?.trim()?.takeIf(String::isNotEmpty),
    titleKeys = if (keys) titleKeys(song.title) else emptyList(),
    lookupKeys = if (keys) SongIdentity.titleLookupKeys(song.title, song.artist.orEmpty()).toList() else emptyList(),
)

// The find an entry is, as the copy keeps it (ServerReads.finds).
internal fun DrainedEntry.toFind(sourceId: String, at: Long) = OnlineSongEntity(
    id = findId(songId), sourceId = sourceId, nativeId = songId, title = title, artist = artist, album = album, albumId = albumId,
    artistId = artistId, durationMs = lengthMs, coverId = coverId, mimeType = mimeType, bitrate = bitrate, seenAt = at, explicit = explicit,
)

// Writes a start's entries a line each, noting where each line begins.
private class DrainWriter(file: File) : java.io.Closeable {
    private val out = java.io.BufferedOutputStream(java.io.FileOutputStream(file))
    private var places = LongArray(1_024)
    private var written = 0L
    private var closed = false
    var count = 0
        private set

    fun write(songs: List<Song>) {
        for (song in songs) {
            val line = (drainJson.encodeToString(DrainedEntry.serializer(), drainedOf(song, keys = true)) + "\n").toByteArray(Charsets.UTF_8)
            if (count == places.size) places = places.copyOf(count * 2)
            places[count++] = written
            out.write(line)
            written += line.size
        }
    }

    fun offsets(): LongArray = places.copyOf(count)

    override fun close() {
        if (closed) return
        closed = true
        out.close()
    }
}

// A server's answer drained for a play's start, with nothing written to
// the library: the list's heading (with, for the play queue, the entry it
// was on), how many entries it has, and the file holding them in order
// with their keys. The build that copies it owns the file; a start that
// hands it to no build deletes it.
class ServerStart internal constructor(
    val head: ServerHeadEntity,
    val count: Int,
    internal val file: File,
    private val places: LongArray,
    val sourceId: String,
) {
    // Lets the file go, for a start no build took.
    fun delete() {
        file.delete()
    }

    // The entry at `position`, read alone.
    internal fun at(position: Long): DrainedEntry? {
        if (position < 0 || position >= count) return null
        val from = places[position.toInt()]
        val to = if (position + 1 < count) places[position.toInt() + 1] else file.length()
        val bytes = ByteArray((to - from - 1).toInt().coerceAtLeast(0))
        java.io.RandomAccessFile(file, "r").use { f ->
            f.seek(from)
            f.readFully(bytes)
        }
        return drainJson.decodeFromString(DrainedEntry.serializer(), String(bytes, Charsets.UTF_8))
    }

    // Every entry in order, for a start small enough to hold whole.
    internal fun all(): List<DrainedEntry> = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.filter(String::isNotEmpty).map { drainJson.decodeFromString(DrainedEntry.serializer(), it) }.toList()
    }

    // The entries before `until` in order, while `each` answers true. A
    // pass reads the file alone and compares strings.
    internal suspend fun pass(until: Long = count.toLong(), each: suspend (Long, DrainedEntry) -> Boolean) {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var position = 0L
            while (position < until) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) continue
                if (!each(position, drainJson.decodeFromString(DrainedEntry.serializer(), line))) return
                position++
            }
        }
    }

    // The entries a page at a time, for the build's copy.
    internal suspend fun pages(size: Int, each: suspend (List<DrainedEntry>) -> Unit) {
        val buffer = ArrayList<DrainedEntry>(size)
        pass { _, entry ->
            buffer += entry
            if (buffer.size == size) {
                each(buffer.toList())
                buffer.clear()
            }
            true
        }
        if (buffer.isNotEmpty()) each(buffer.toList())
    }
}

// What one entry of a drained start is, as the build's copy shows it: its
// place, its song (a library song's id after the renames in force, or a
// find's), whether that is a library song, whether a row would show for it
// at all (its song still there), the find as the copy keeps it, and the
// radio source it came with.
class StartEntry internal constructor(
    val position: Long,
    val trackId: String,
    val library: Boolean,
    val present: Boolean,
    val find: OnlineSongEntity?,
    val suggestedBy: String?,
) {
    // Whether a row shows for it in a list that keeps `libraryOnly`.
    fun shows(libraryOnly: Boolean): Boolean = present && (library || !libraryOnly)
}

// The entries a list shows, in order: each song at its first entry, while
// it is there, and library songs alone for `libraryOnly`.
fun List<StartEntry>.shownRows(libraryOnly: Boolean): List<StartEntry> {
    val seen = HashSet<String>()
    return filter { it.present && seen.add(it.trackId) && it.shows(libraryOnly) }
}

// Places 0 until `count` in a seeded uniform order, each once: a lazy
// Fisher-Yates draw, holding only the places it has moved.
internal class SeededPlaces(private val count: Long, seed: Long) {
    private val random = kotlin.random.Random(seed)
    private val moved = HashMap<Long, Long>()
    private var drawn = 0L

    fun next(): Long? {
        if (drawn >= count) return null
        val j = drawn + random.nextLong(count - drawn)
        val at = moved[j] ?: j
        moved[j] = moved.remove(drawn) ?: drawn
        if (j == drawn) moved.remove(j)
        drawn++
        return at
    }
}

// A server song's library link as the copy writes it: the library id
// (`mergedId`, else the copy's own), the relink key the copy gives the row
// (the song's, or none when it is gone), the id the row ends on after the
// renames in force, and whether the library has that one.
internal fun startLinksSql(n: Int) = """
    SELECT l.serverId, l.trackId, COALESCE(t.relinkKey, ''), COALESCE(r.new, l.trackId),
        EXISTS (SELECT 1 FROM track f WHERE f.id = COALESCE(r.new, l.trackId))
    FROM (SELECT nativeId AS serverId, CASE WHEN mergedId = '' THEN id ELSE mergedId END AS trackId
          FROM source_track WHERE sourceId = ? AND nativeId IN (${marks(n)})) l
    LEFT JOIN track t ON t.id = l.trackId
    LEFT JOIN save_rename r ON r.kind = '$RENAME_SONG' AND r.old = l.trackId AND (r.byKey = 0 OR r.key = COALESCE(t.relinkKey, ''))
"""

// The library songs filed under any of these lookup keys, on the two key
// indexes, with the id each ends on after the renames in force.
internal fun startCandidatesSql(n: Int) = """
    SELECT t.id, t.title, t.artist, t.durationMs, t.searchKey, t.lookupKey, t.looseKey, COALESCE(r.new, t.id),
        EXISTS (SELECT 1 FROM track f WHERE f.id = COALESCE(r.new, t.id))
    FROM track t LEFT JOIN save_rename r ON r.kind = '$RENAME_SONG' AND r.old = t.id AND (r.byKey = 0 OR r.key = t.relinkKey)
    WHERE t.lookupKey IN (${marks(n)}) OR t.looseKey IN (${marks(n)})
"""

// What the app already keeps of these finds, and the library song each
// became once downloaded, after the renames in force, when the library has it.
internal fun startFindsSql(n: Int) = """
    SELECT o.id, o.requestedAt, o.adoptedId, o.durationMs, COALESCE(r.new, o.adoptedId),
        EXISTS (SELECT 1 FROM track t WHERE t.id = COALESCE(r.new, o.adoptedId))
    FROM online_song o LEFT JOIN save_rename r ON r.kind = '$RENAME_SONG' AND r.old = o.adoptedId AND r.byKey = 0
    WHERE o.id IN (${marks(n)})
"""

// What can reach a library song, for the check of an earlier entry: the
// old ids renamed to it, their keys, the server songs linked to them and
// the finds that became them.
internal const val START_RENAMED_TO_SQL = "SELECT old FROM save_rename WHERE kind = '$RENAME_SONG' AND new = ?"

internal fun startEndsSql(n: Int) = "SELECT id, title, artist, durationMs, searchKey, lookupKey, looseKey FROM track WHERE id IN (${marks(n)})"

internal fun startLinkedToSql(n: Int) =
    "SELECT nativeId FROM source_track WHERE +sourceId = ? AND mergedId IN (${marks(n)}) " +
        "UNION ALL SELECT nativeId FROM source_track WHERE +sourceId = ? AND +mergedId = '' AND id IN (${marks(n)})"

internal fun startAdoptedAsSql(n: Int) = "SELECT nativeId FROM online_song WHERE sourceId = ? AND adoptedId IN (${marks(n)})"

// A drained start's entries worked out the way the build's copy works them
// out (ServerReads.resolve), with reads alone: each statement one SELECT
// outside any transaction, every id through the renames in force. A song
// the library has from the server is that song, unless the server marks it
// outside the library; any other entry joins the match group of its title,
// artist and length, which takes the first library song in (searchKey, id)
// order that some unlinked entry's title keys file and that is the same
// song; the rest are finds, shown as the library song they became once
// downloaded. Each song shows at its first entry. `parses` counts the
// titles read; every key was worked out as the answer was drained.
class StartResolver internal constructor(
    private val db: CatalogDatabase,
    val start: ServerStart,
    private val hooks: ServerHooks,
    private val at: Long,
) {
    var parses = 0L
        private set

    private class Link(val final: String, val present: Boolean)

    private class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val searchKey: String,
        val lookupKey: String,
        val looseKey: String,
        val final: String,
        val present: Boolean,
    )

    private class Kept(val requestedAt: Long, val adoptedId: String, val durationMs: Long, val adoptedPresent: Boolean)

    // The pool's order, as SQLite's BINARY collation orders keys and ids.
    private val poolOrder = Comparator<Candidate> { a, b -> SqlOrder.text(a.searchKey, b.searchKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }

    private val links = HashMap<String, Link?>()

    // ---- A start small enough to hold whole ----

    // Every entry worked out, in order, the shown and the hidden
    // (shownRows picks the shown).
    fun all(): List<StartEntry> {
        val entries = start.all()
        readLinks(entries.map { it.songId }.distinct())
        val pool = HashSet<String>()
        for (e in entries) if (links[e.songId] == null) pool += e.titleKeys
        val groups = LinkedHashMap<Triple<String, String, Long>, List<String>>()
        for (e in entries) if (e.external || links[e.songId] == null) groups.getOrPut(Triple(e.title, e.artist, e.lengthMs)) { e.lookupKeys }
        val candidates = candidates(groups.values.flatten().distinct())
        val picks = groups.mapValues { (g, keys) ->
            candidates.filter { it.searchKey in pool && (it.lookupKey in keys || it.looseKey in keys) }.sortedWith(poolOrder)
                .firstOrNull { same(g.first, g.second, g.third, it) }
        }
        val settled = entries.mapIndexed { i, e ->
            val link = links[e.songId]
            when {
                !e.external && link != null -> StartEntry(i.toLong(), link.final, true, link.present, null, e.suggestedBy)
                else -> picks[Triple(e.title, e.artist, e.lengthMs)]?.let { StartEntry(i.toLong(), it.final, true, it.present, null, e.suggestedBy) }
            }
        }
        val kept = keptFinds(entries.indices.filter { settled[it] == null }.map { findId(entries[it].songId) }.distinct())
        return entries.mapIndexed { i, e -> settled[i] ?: findOf(i.toLong(), e, kept[findId(e.songId)]) }
    }

    // The `index`-th entry a row shows for, in order, or null: every entry
    // before it is worked out, each song counted at its first entry.
    suspend fun nth(index: Int, libraryOnly: Boolean): StartEntry? {
        if (index < 0) return null
        val seen = HashSet<String>()
        var shown = 0
        var found: StartEntry? = null
        start.pass { p, e ->
            hooks.tried(p)
            val entry = alone(p, e)
            if (entry.present && seen.add(entry.trackId) && entry.shows(libraryOnly)) {
                if (shown == index) found = entry
                shown++
            }
            found == null
        }
        return found
    }

    // ---- A big start, an entry at a time ----

    // The entry at `position` alone, with the first earlier entry of its
    // song (its own place when there is none). Null past the end.
    suspend fun at(position: Long): Pair<StartEntry, Long>? {
        val e = start.at(position) ?: return null
        val entry = alone(position, e)
        return entry to (if (entry.present) earlier(entry, e) ?: position else position)
    }

    // The first entry in order a row shows for: every entry before it was
    // tried, so the first whose song is there (a library song's, for
    // `libraryOnly`) is its song's first. Null when none shows.
    suspend fun first(libraryOnly: Boolean): StartEntry? {
        var found: StartEntry? = null
        start.pass { p, e ->
            hooks.tried(p)
            val entry = alone(p, e)
            if (entry.shows(libraryOnly)) found = entry
            found == null
        }
        return found
    }

    // A seeded walk of the places without replacement: each place's entry
    // alone, and whether an earlier entry has its song. The first entry a
    // row shows for wins; with none, the start is empty after every place.
    suspend fun shuffled(seed: Long, libraryOnly: Boolean): StartEntry? {
        val places = SeededPlaces(start.count.toLong(), seed)
        while (true) {
            val p = places.next() ?: return null
            val e = start.at(p) ?: continue
            hooks.tried(p)
            val entry = alone(p, e)
            if (entry.shows(libraryOnly) && earlier(entry, e) == null) return entry
        }
    }

    // One entry alone: its link, else its match group's pick, whose pool is
    // checked with one strings-only pass over the file that reads a link
    // only for an entry whose keys hit a candidate's, else a find.
    private suspend fun alone(position: Long, e: DrainedEntry): StartEntry {
        val link = linkOf(e.songId)
        if (!e.external && link != null) return StartEntry(position, link.final, true, link.present, null, e.suggestedBy)
        val same = candidates(e.lookupKeys).filter { same(e.title, e.artist, e.lengthMs, it) }.sortedWith(poolOrder)
        poolPick(same, e, link)?.let { return StartEntry(position, it.final, true, it.present, null, e.suggestedBy) }
        val id = findId(e.songId)
        return findOf(position, e, keptFinds(listOf(id))[id])
    }

    // The first of `same` (in pool order) some unlinked entry's title keys
    // file: the pass stops once the first of them is found filed.
    private suspend fun poolPick(same: List<Candidate>, e: DrainedEntry, ownLink: Link?): Candidate? {
        if (same.isEmpty()) return null
        val wanted = same.mapTo(HashSet()) { it.searchKey }
        val filed = HashSet<String>()
        if (ownLink == null) e.titleKeys.filterTo(filed) { it in wanted }
        val lowest = same.first().searchKey
        if (lowest !in filed) {
            start.pass { _, other ->
                val hits = other.titleKeys.filter { it in wanted && it !in filed }
                if (hits.isNotEmpty() && linkOf(other.songId) == null) filed += hits
                lowest !in filed
            }
        }
        return same.firstOrNull { it.searchKey in filed }
    }

    // The first entry before `entry`'s that resolves to its song, or null.
    // Only entries that can reach that song are resolved, found with
    // strings: one of the server songs linked to it or that became it, or
    // one whose keys meet one of the library songs that end on it and that
    // is the same song as one of them.
    private suspend fun earlier(entry: StartEntry, e: DrainedEntry): Long? {
        if (entry.position == 0L) return null
        val songs = hashSetOf(e.songId)
        val ends = ArrayList<Candidate>()
        if (entry.library) {
            val ids = listOf(entry.trackId) + db.read(START_RENAMED_TO_SQL, entry.trackId) { it.getString(0) }
            db.read(startEndsSql(ids.size), *ids.toTypedArray()) { c ->
                Candidate(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6), entry.trackId, true)
            }.let(ends::addAll)
            songs += db.read(startLinkedToSql(ids.size), start.sourceId, *ids.toTypedArray(), start.sourceId, *ids.toTypedArray()) { it.getString(0) }
            songs += db.read(startAdoptedAsSql(ids.size), start.sourceId, *ids.toTypedArray()) { it.getString(0) }
        }
        val keys = ends.flatMapTo(HashSet()) { listOf(it.lookupKey, it.looseKey) }.apply { remove("") }
        var found: Long? = null
        start.pass(until = entry.position) { q, other ->
            val reaches = other.songId in songs ||
                (other.lookupKeys.any { it in keys } && ends.any { c -> (c.lookupKey in other.lookupKeys || c.looseKey in other.lookupKeys) && same(other.title, other.artist, other.lengthMs, c) })
            if (reaches) {
                hooks.checked(q)
                if (alone(q, other).trackId == entry.trackId) found = q
            }
            found == null
        }
        return found
    }

    // ---- Reads ----

    private fun linkOf(songId: String): Link? {
        if (songId !in links) readLinks(listOf(songId))
        return links[songId]
    }

    private fun readLinks(songIds: List<String>) {
        val wanted = songIds.filter { it !in links }
        wanted.forEach { links[it] = null }
        wanted.chunked(ID_CHUNK - 1).forEach { chunk ->
            db.read(startLinksSql(chunk.size), start.sourceId, *chunk.toTypedArray()) { c -> c.getString(0) to Link(c.getString(3), c.getInt(4) == 1) }
                .forEach { (serverId, link) -> links[serverId] = link }
        }
    }

    private fun candidates(keys: List<String>): List<Candidate> {
        if (keys.isEmpty()) return emptyList()
        return keys.distinct().chunked(ID_CHUNK / 2).flatMap { chunk ->
            db.read(startCandidatesSql(chunk.size), *chunk.toTypedArray(), *chunk.toTypedArray()) { c ->
                Candidate(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getInt(8) == 1)
            }
        }.distinctBy { it.id }
    }

    private fun keptFinds(ids: List<String>): Map<String, Kept> = ids.chunked(ID_CHUNK).flatMap { chunk ->
        db.read(startFindsSql(chunk.size), *chunk.toTypedArray()) { c -> c.getString(0) to Kept(c.getLong(1), c.getString(4), c.getLong(3), c.getInt(5) == 1) }
    }.toMap()

    // A find, as the library song it became when the library has that, or
    // as the copy keeps it, with what the app already knew of it.
    private fun findOf(position: Long, e: DrainedEntry, kept: Kept?): StartEntry {
        if (kept != null && kept.adoptedPresent) return StartEntry(position, kept.adoptedId, true, true, null, e.suggestedBy)
        val now = e.toFind(start.sourceId, at)
        val find = if (kept == null) now else now.copy(requestedAt = kept.requestedAt, adoptedId = kept.adoptedId, durationMs = if (now.durationMs > 0) now.durationMs else kept.durationMs)
        return StartEntry(position, find.id, false, true, find, e.suggestedBy)
    }

    private fun same(title: String, artist: String, lengthMs: Long, c: Candidate): Boolean {
        parses++
        hooks.parsed()
        return sameSong(title, artist, lengthMs, c.title, c.artist, c.durationMs)
    }
}
