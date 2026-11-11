package app.winters.octo.ui.album

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.Survey
import app.winters.octo.ui.paging.marks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// A big album's listing copy as its page reads it, kept under two owners:
// the page reads one copy while the next is written under the other, and
// turns to it once it is whole, so a rebuild keeps the row in view. A copy
// shows the library's songs and a find the library had taken in as they
// were when it was written; the next copy is written when one of the finds
// it shows is taken in later (`adopted`), and when a swap renamed, removed
// or added one of the album's songs (`swapped`).
class AlbumCopies(
    private val owners: List<String>,
    // Writes the album's listing under an owner; whether it holds a find.
    private val write: suspend (owner: String) -> Boolean,
    private val loaderOf: (owner: String) -> ListLoader<TrackEntity>,
    // Whether an owner's copy shows any of these finds as a find.
    private val shows: suspend (owner: String, finds: Collection<String>) -> Boolean,
    // Whether an owner's copy no longer holds the album's library songs as
    // the library has them.
    private val stale: suspend (owner: String) -> Boolean,
) {
    @Volatile private var now = 0
    private var taken: Set<String> = emptySet()
    private val inView = CopyInView(loaderOf(owners[0]))
    private val writing = Mutex()

    // The owner of the copy the page reads.
    val owner: String get() = owners[now]

    // The copy the page reads, whichever owner holds it.
    val loader: ListLoader<TrackEntity> get() = inView

    // Writes the first copy, with the finds taken in by now (`adopted`);
    // whether it holds a find.
    suspend fun build(adopted: Set<String>): Boolean {
        taken = adopted
        return write(owners[now])
    }

    // The finds taken in now: when the copy shows one taken in since it was
    // written, the next copy is written under the other owner and the page
    // turns to it. Whether the new copy holds a find; null when nothing was
    // written. A write cut short leaves the page on its copy, and the next
    // call writes again.
    suspend fun adopted(finds: Set<String>): Boolean? = writing.withLock {
        val since = finds - taken
        if (since.isEmpty() || !shows(owner, since)) {
            taken = finds
            return@withLock null
        }
        next().also { taken = finds }
    }

    // A swap has finished: when the copy holds a song the library no longer
    // has under its id, or lacks one of the album's songs, the next copy is
    // written and the page turns to it, as for a find taken in.
    suspend fun swapped(): Boolean? = writing.withLock { if (stale(owner)) next() else null }

    private suspend fun next(): Boolean {
        val next = 1 - now
        val held = write(owners[next])
        now = next
        inView.now = loaderOf(owners[next])
        return held
    }
}

// Whether an owner's copy of album `albumId` no longer holds the album's
// library songs as the library has them: a library row it shows whose song
// is gone (renamed or removed by a swap), or a song of the album it lacks
// (one a swap renamed or added). One walk of the copy and one of the
// album's songs, each stopping at the first it finds.
suspend fun copyIsStale(db: CatalogDatabase, owner: String, albumId: String): Boolean = db.listsDao().count(
    rawQuery(
        "SELECT EXISTS(SELECT 1 FROM lists_album_entry e WHERE e.owner = ? AND e.isFind = 0 AND NOT EXISTS (SELECT 1 FROM track t WHERE t.id = e.shownId)) " +
            "OR EXISTS(SELECT 1 FROM track t WHERE t.albumId = ? AND NOT EXISTS (SELECT 1 FROM lists_album_entry e WHERE e.owner = ? AND e.shownId = t.id))",
        listOf(SqlValue.Text(owner), SqlValue.Text(albumId), SqlValue.Text(owner)),
    ),
) == 1

// Whether an owner's album copy shows any of these finds as a find, 900 at
// a time on its shown-song index.
suspend fun copyShowsFinds(db: CatalogDatabase, owner: String, finds: Collection<String>): Boolean = finds.chunked(900).any { chunk ->
    db.listsDao().count(
        rawQuery(
            "SELECT EXISTS(SELECT 1 FROM lists_album_entry WHERE owner = ? AND shownId IN ${marks(chunk.size)} AND isFind = 1)",
            listOf(SqlValue.Text(owner)) + chunk.map { SqlValue.Text(it) },
        ),
    ) == 1
}

// The page's loader of a big album's copy: the copy last written whole. Its
// rows change only when the page turns it to a new copy, which the page then
// says, or when a song it shows changes.
private class CopyInView(@Volatile var now: ListLoader<TrackEntity>) : ListLoader<TrackEntity> {
    override val tables: Array<String> get() = now.tables.filter { it != "lists_album_entry" }.toTypedArray()
    override val order: Comparator<RowKey> get() = now.order
    override val songsOnce: Boolean get() = now.songsOnce
    override suspend fun count(): Int = now.count()
    override suspend fun survey(anchor: RowKey?): Survey = now.survey(anchor)
    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> = now.after(key, limit)
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> = now.before(key, limit)
    override suspend fun keyAt(position: Int): RowKey? = now.keyAt(position)
    override suspend fun keyOf(trackId: String): RowKey? = now.keyOf(trackId)
    override suspend fun rekey(key: RowKey): RowKey? = now.rekey(key)
    override suspend fun present(ids: List<String>): Set<String> = now.present(ids)
    override suspend fun small(): List<Keyed<TrackEntity>>? = now.small()
    override fun trackIdOf(row: TrackEntity): String? = row.id
}
