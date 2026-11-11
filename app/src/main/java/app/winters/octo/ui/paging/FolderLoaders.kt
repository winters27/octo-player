package app.winters.octo.ui.paging

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FolderRowView
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.ListsFolderOrderEntity
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.rawQuery
import app.winters.octo.catalog.sortKey
import app.winters.octo.device.PhonePaths
import app.winters.octo.folders.NaturalOrder
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import java.io.ByteArrayOutputStream
import okio.ByteString.Companion.toByteString

// The disk one row of a phone folder's ordered copy (lists_folder_order)
// takes in the file with its keys and indexes, under an owner led by the
// launch's mark (ListsDiskConstantsTest: 686). A copy asks for twice this a
// row, the write-ahead log's share the second half.
const val FOLDER_ORDER_ROW_BYTES = 715L

// How many rows one page of a list's copy writes.
private const val COPY_PAGE = 500

// How often, in pages, a copy checks for room again.
private const val ROOM_EVERY = 10

// A song row's columns from a left-joined library song `t`: a row whose
// song is gone keeps `idColumn` as its id, empty words and no length.
internal fun keptTrackColumns(idColumn: String): String {
    val texts = listOf(
        "sourceId", "nativeId", "title", "searchKey", "sortKey", "artist", "artistId", "album", "albumId", "relinkKey", "genre",
        "artistSortKey", "albumSortKey", "lookupKey", "looseKey",
    )
    val numbers = listOf("durationMs", "addedAt", "albumOrder", "onPhone", "rating")
    val nullable = listOf("trackNo", "discNo", "year", "mimeType", "sizeBytes", "artwork", "uri", "explicit")
    return (
        listOf("COALESCE(t.id, $idColumn) AS id") +
            texts.map { "COALESCE(t.$it, '') AS $it" } +
            numbers.map { "COALESCE(t.$it, 0) AS $it" } +
            nullable.map { "t.$it AS $it" }
        ).joinToString(", ")
}

// A folder's own songs in its order: by album (named in lower case), then
// the album's own order, then title, then id.
val phoneSongOrder: Comparator<TrackEntity> =
    compareBy<TrackEntity>({ it.album.lowercase() }, { it.albumId }, { it.albumOrder }, { it.sortKey }).thenBy { it.id }

// Keys of a folder's songs as bytes the database orders the way the
// folder page orders them in Kotlin. A text is its UTF-16 code units, each
// one more than itself in three bytes, then three zero bytes, so a shorter
// text sorts first and String.compareTo's order holds; a number is eight
// bytes with its sign flipped. A key that runs high to low has its bytes
// turned over. `lead` is the order's key, `ties` what breaks its ties, both
// ending so no two songs share them.
object FolderKeys {
    private fun ByteArrayOutputStream.text(s: String) {
        for (c in s) {
            val u = c.code + 1
            write(u shr 16)
            write(u shr 8 and 0xff)
            write(u and 0xff)
        }
        write(0)
        write(0)
        write(0)
    }

    private fun ByteArrayOutputStream.number(v: Long) {
        val x = v xor Long.MIN_VALUE
        for (shift in 56 downTo 0 step 8) write((x ushr shift).toInt() and 0xff)
    }

    private fun bytes(fill: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(fill).toByteArray()

    private fun ByteArray.turned() = ByteArray(size) { (255 - (this[it].toInt() and 0xff)).toByte() }

    private fun inAlbum(out: ByteArrayOutputStream, t: TrackEntity) {
        out.text(t.albumId)
        out.number(t.albumOrder.toLong())
        out.text(t.sortKey)
        out.text(t.id)
    }

    // The keys of one song in a folder order: `lead` then `ties`.
    fun of(t: TrackEntity, order: SortOrder): Pair<ByteArray, ByteArray> {
        val down = order.descending
        fun lead(fill: ByteArrayOutputStream.() -> Unit) = bytes(fill).let { if (down) it.turned() else it }
        return when (order.by) {
            SongSort.Title -> lead { text(t.sortKey) } to bytes { text(sortKey(t.artist)); text(t.id) }
            SongSort.Artist -> lead { text(sortKey(t.artist)) } to bytes { text(sortKey(t.album)); inAlbum(this, t) }
            SongSort.Album -> lead { text(sortKey(t.album)) } to bytes { text(sortKey(t.artist)); inAlbum(this, t) }
            SongSort.RecentlyAdded -> lead { number(t.addedAt) } to bytes { inAlbum(this, t) }
            // Songs with no year last, whichever way the list runs.
            SongSort.Year -> {
                val year = t.year
                val value = if (year == null) ByteArray(0) else bytes { number(year.toLong()) }.let { if (down) it.turned() else it }
                (byteArrayOf(if (year == null) 1 else 0) + value) to bytes { text(sortKey(t.album)); inAlbum(this, t) }
            }
            SongSort.Length -> lead { number(t.durationMs) } to bytes { text(t.sortKey); text(t.id) }
            // The folder's own order, or that turned around whole.
            else -> {
                val l = bytes { text(t.album.lowercase()); text(t.albumId); number(t.albumOrder.toLong()); text(t.sortKey) }
                val i = bytes { text(t.id) }
                if (down) l.turned() to i.turned() else l to i
            }
        }
    }

    // A recursive copy's lead: the folder's place in the walk, then the key.
    fun ranked(rank: Long, lead: ByteArray): ByteArray = bytes { number(rank) } + lead

    // The keys of one song of a server folder, at `position` in the
    // server's answer: a phone folder's song's in every order but the
    // folder's own, which is the server's, or that turned around whole.
    fun listed(t: TrackEntity, position: Long, order: SortOrder): Pair<ByteArray, ByteArray> = when (order.by) {
        SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.RecentlyAdded, SongSort.Year, SongSort.Length -> of(t, order)
        else -> bytes { number(position) }.let { if (order.descending) it.turned() else it } to ByteArray(0)
    }
}

// Where on the phone a folder is, as the library stores it.
data class FolderPlace(val volume: String?, val folder: String?)

// One phone folder of the library: its name, the folders inside it (in
// natural order), how many songs are its own and in all, where it is, and
// its first own song in its order, which a Play starts from.
class PhoneFolder(
    val name: String,
    val folders: List<PhoneFolder>,
    val own: Int,
    val places: List<FolderPlace>,
    val first: TrackEntity?,
) {
    val total: Int = own + folders.sumOf { it.total }

    fun at(path: List<String>): PhoneFolder? = path.fold(this as PhoneFolder?) { node, name -> node?.folders?.firstOrNull { it.name == name } }

    // The folder at `path` and every folder under it, in the order a Play
    // of it plays them: each inside folder in turn, then its own songs.
    fun inPlayOrder(): List<PhoneFolder> = folders.flatMap { it.inPlayOrder() } + this

    // The folder holding the `k`-th song of a Play of this one, and the
    // song's place among that folder's own.
    fun songAt(k: Int): Pair<PhoneFolder, Int>? {
        var left = k
        for (folder in inPlayOrder()) {
            if (left < folder.own) return folder to left
            left -= folder.own
        }
        return null
    }
}

// The phone's folders collapsed to where the music is, and the names
// skipped on the way.
class PhoneTree(val skipped: List<String>, val root: PhoneFolder) {
    val name: String get() = skipped.lastOrNull().orEmpty()
}

// A row's own file: a phone file whose row is the library song itself, never
// a second file merged into the same song, so each song is listed once.
private const val OWN_FILES = "s.sourceId = 'device' AND s.id = s.mergedId"

// The phone's folders and their songs, read from the library's stored
// places in pages, never holding the songs: the tree holds counts and each
// folder's first song; a folder's own songs are written in their order to
// a copy under an owner, which a page or a queue walks.
class PhoneFolders(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val freeSpace: FreeSpace,
) {
    private val dao = db.listsDao()

    // The tree, from counts per folder along the folder index and one walk
    // of the files keeping each folder's first song; null when the phone
    // has no songs in the library.
    suspend fun tree(): PhoneTree? = app.winters.octo.catalog.stableRead(merge, HoldKind.Walk) {
        val counts = dao.folderCounts()
        if (counts.isEmpty()) return@stableRead null
        val paths = PhonePaths(counts.map { it.storageVolume }.distinct())
        val firsts = HashMap<FolderPlace, TrackEntity>()
        var after = -1L
        while (true) {
            val page = dao.tracks(
                rawQuery(
                    "SELECT ${trackSelect(0, k0 = "s.seq", t0 = "s.storageVolume", t1 = "s.folder")} FROM source_track s CROSS JOIN track t ON t.id = s.id " +
                        "WHERE $OWN_FILES AND s.seq > ? ORDER BY s.seq LIMIT $COPY_PAGE",
                    listOf(SqlValue.Int64(after)),
                ),
            )
            for (row in page) {
                val place = FolderPlace(row.t0, row.t1)
                val known = firsts[place]
                if (known == null || phoneSongOrder.compare(row.track, known) < 0) firsts[place] = row.track
            }
            if (page.size < COPY_PAGE) break
            after = page.last().k0 ?: break
        }
        build(counts.map { Triple(paths.of(it.folder, it.storageVolume), FolderPlace(it.storageVolume, it.folder), it.n) }, firsts)
    }

    private fun build(rows: List<Triple<List<String>, FolderPlace, Int>>, firsts: Map<FolderPlace, TrackEntity>): PhoneTree {
        class Building {
            val folders = LinkedHashMap<String, Building>()
            var own = 0
            val places = ArrayList<FolderPlace>()
        }
        val root = Building()
        for ((path, place, n) in rows) {
            var node = root
            for (name in path) node = node.folders.getOrPut(name) { Building() }
            node.own += n
            node.places += place
        }
        fun finish(name: String, node: Building): PhoneFolder = PhoneFolder(
            name,
            node.folders.entries.sortedWith(compareBy(NaturalOrder) { it.key }).map { (child, built) -> finish(child, built) },
            node.own,
            node.places,
            node.places.mapNotNull { firsts[it] }.minWithOrNull(phoneSongOrder),
        )
        var top = finish("", root)
        val skipped = ArrayList<String>()
        while (top.own == 0 && top.folders.size == 1) {
            top = top.folders.single()
            skipped += top.name
        }
        return PhoneTree(skipped, top)
    }

    // The `k`-th own file of a folder in the folder index's order, by one
    // probe of the index alone.
    suspend fun fileAt(folder: PhoneFolder, k: Int): String? {
        var left = k
        for (place in folder.places) {
            val ids = dao.ids(
                rawQuery(
                    "SELECT s.id FROM source_track s WHERE s.sourceId = 'device' AND s.storageVolume IS ? AND s.folder IS ? AND s.id = s.mergedId " +
                        "LIMIT 1 OFFSET ?",
                    listOf(place.volume.sql(), place.folder.sql(), SqlValue.Int64(left.toLong())),
                ),
            )
            ids.firstOrNull()?.let { return it }
            left -= dao.count(rawQuery("SELECT COUNT(*) FROM source_track s WHERE s.sourceId = 'device' AND s.storageVolume IS ? AND s.folder IS ? AND s.id = s.mergedId", listOf(place.volume.sql(), place.folder.sql())))
            if (left < 0) return null
        }
        return null
    }

    // Writes `folders`' own songs under `owner` in `order`, each folder's
    // after the last's (its place leading the key when `ranked`), a page to
    // a transaction. A swap during it starts it again, twice at most; the
    // third runs inside the merge lock, as a walk. Short of room it stops
    // with NotEnoughSpace and leaves no rows.
    suspend fun copy(owner: String, folders: List<PhoneFolder>, order: SortOrder, ranked: Boolean) {
        val need = folders.sumOf { it.own }.toLong() * FOLDER_ORDER_ROW_BYTES * 2
        repeat(3) { attempt ->
            room(need)
            db.deleteOwned("lists_folder_order", owner)
            val done = if (attempt < 2) write(owner, folders, order, ranked) else merge.betweenRebuilds(HoldKind.Walk) { write(owner, folders, order, ranked) }
            if (done) return
        }
    }

    private fun room(bytes: Long) {
        if (freeSpace.bytes() < bytes) throw NotEnoughSpace(bytes, SpaceFor.List)
    }

    // One attempt: false when a swap landed meanwhile.
    private suspend fun write(owner: String, folders: List<PhoneFolder>, order: SortOrder, ranked: Boolean): Boolean {
        val baseline = db.withTransaction { db.workDao().swapCount() }
        var pages = 0
        for ((rank, folder) in folders.withIndex()) {
            for (place in folder.places) {
                var after = ""
                while (true) {
                    val page = dao.tracks(
                        rawQuery(
                            "SELECT ${trackSelect(0)} FROM source_track s CROSS JOIN track t ON t.id = s.id " +
                                "WHERE s.sourceId = 'device' AND s.storageVolume IS ? AND s.folder IS ? AND s.id = s.mergedId AND s.mergedId > ? " +
                                "ORDER BY s.mergedId LIMIT $COPY_PAGE",
                            listOf(place.volume.sql(), place.folder.sql(), SqlValue.Text(after)),
                        ),
                    )
                    if (page.isEmpty()) break
                    val rows = page.map { row ->
                        val (lead, ties) = FolderKeys.of(row.track, order)
                        ListsFolderOrderEntity(owner, if (ranked) FolderKeys.ranked(rank.toLong(), lead) else lead, ties, row.track.id)
                    }
                    val kept = try {
                        db.withTransaction {
                            if (db.workDao().swapCount() != baseline) return@withTransaction false
                            dao.insertFolderRows(rows)
                            true
                        }
                    } catch (e: SQLiteFullException) {
                        db.deleteOwned("lists_folder_order", owner)
                        throw NotEnoughSpace(null, SpaceFor.List)
                    }
                    if (!kept) return false
                    if (++pages % ROOM_EVERY == 0) room(pages.toLong() * COPY_PAGE * FOLDER_ORDER_ROW_BYTES * 2)
                    if (page.size < COPY_PAGE) break
                    after = page.last().track.id
                }
            }
        }
        return true
    }

    // A folder copy's rows in order, with their songs. With `keepGone` (a
    // queue's fill), a row whose song left the library since the copy was
    // made stays, with its copied id, for the fill to follow.
    fun loader(owner: String, keepGone: Boolean = false): ListLoader<TrackEntity> {
        val keys: (FolderRowView) -> RowKey = { row -> RowKey(0, listOf(SqlValue.Blob(row.lead.toByteString()), SqlValue.Blob(row.ties.toByteString())), row.track.id) }
        val part = IndexPart(
            0,
            if (keepGone) "lists_folder_order f LEFT JOIN track t ON t.id = f.trackId" else "lists_folder_order f CROSS JOIN track t ON t.id = f.trackId",
            listOf("f.owner = ?"),
            listOf(SqlValue.Text(owner)),
            emptyList(),
            listOf("f.lead", "f.ties"),
            false,
            (if (keepGone) keptTrackColumns("f.trackId") else "t.*") + ", f.lead AS lead, f.ties AS ties",
            object : PartReads<FolderRowView> {
                override suspend fun rows(sql: String, args: List<SqlValue>) = dao.folderRows(rawQuery(sql, args))
                override suspend fun count(sql: String, args: List<SqlValue>) = dao.count(rawQuery(sql, args))
                override suspend fun ids(sql: String, args: List<SqlValue>) = dao.ids(rawQuery(sql, args))
            },
            keys,
            idColumn = "f.trackId",
        )
        return PartsLoader(
            { listOf(part) },
            arrayOf("lists_folder_order", "track"),
            SqlOrder.rows(),
            songsOnce = true,
            rowOf = { it.track },
            idOf = { it.id },
        )
    }

    // A folder's songs read in memory, when it has at most `limit` of them
    // (its own, or with `recursive` every one under it), in the order a
    // page or a Play shows them; null when it has more. One statement per
    // folder place.
    suspend fun songs(folder: PhoneFolder, recursive: Boolean, order: SortOrder?, limit: Int): List<TrackEntity>? {
        val folders = if (recursive) folder.inPlayOrder() else listOf(folder)
        if (folders.sumOf { it.own } > limit) return null
        return folders.flatMap { f ->
            val songs = f.places.flatMap { place ->
                dao.tracks(
                    rawQuery(
                        "SELECT ${trackSelect(0)} FROM source_track s CROSS JOIN track t ON t.id = s.id " +
                            "WHERE s.sourceId = 'device' AND s.storageVolume IS ? AND s.folder IS ? AND s.id = s.mergedId LIMIT ${limit + 1}",
                        listOf(place.volume.sql(), place.folder.sql()),
                    ),
                ).map { it.track }
            }
            val by = if (recursive || order == null) SortOrder(SongSort.FolderOrder, false) else order
            songs.sortedWith { a, b -> compareKeys(FolderKeys.of(a, by), FolderKeys.of(b, by)) }
        }.takeIf { it.size <= limit }
    }

    private fun compareKeys(a: Pair<ByteArray, ByteArray>, b: Pair<ByteArray, ByteArray>): Int {
        val lead = SqlOrder.bytes(a.first.toByteString(), b.first.toByteString())
        return if (lead != 0) lead else SqlOrder.bytes(a.second.toByteString(), b.second.toByteString())
    }

    private fun String?.sql(): SqlValue = this?.let { SqlValue.Text(it) } ?: SqlValue.Null
}
