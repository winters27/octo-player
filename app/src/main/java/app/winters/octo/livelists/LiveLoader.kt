package app.winters.octo.livelists

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.query.LibraryQuery
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString

// The generation an owner's list shows, in one statement's subquery.
private const val SHOWN = "(SELECT gen FROM live_state WHERE owner = ?1)"

// One generation of an owner's list held by a reader (pinShown): its
// number, how many songs it holds, and how many times the owner's list was
// changed in place before the pin, so two pins of one generation with the
// same `changes` read the same songs.
class LivePinned(val gen: Long, val count: Int, val changes: Long = 0)

// A live list as one owner holds it, its songs (by id) in the list's
// order, keyed by their order keys. Each read is one statement over the
// generation published when it runs, so a page is never half of two; with
// `at`, every read is of that one generation, which its pin keeps.
class LiveLoader internal constructor(private val db: CatalogDatabase, val owner: String, private val at: LivePinned? = null) : ListLoader<String> {
    override val tables = arrayOf("live_song", "live_state")

    override val order: Comparator<RowKey> = SqlOrder.rows()

    // A generation holds each song once.
    override val songsOnce = true

    // The generation each statement reads.
    private val gen = at?.gen?.toString() ?: SHOWN

    override fun trackIdOf(row: String): String = row

    override suspend fun count(): Int = at?.count ?: io { db.readLong("SELECT count FROM live_state WHERE owner = ?", owner).toInt() }

    override suspend fun survey(anchor: RowKey?): Survey = io {
        val before = anchor?.let {
            db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?1 AND gen = $gen AND orderKey < ?2", owner, keyBytes(it)).toInt()
        }
        Survey(count(), null, before)
    }

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<String>> = io {
        db.read(
            "SELECT orderKey, trackId FROM live_song WHERE owner = ?1 AND gen = $gen AND orderKey > ?2 ORDER BY orderKey LIMIT ?3",
            owner, key?.let(::keyBytes) ?: ByteArray(0), limit,
        ) { Keyed(rowKey(it.getBlob(0), it.getString(1)), it.getString(1)) }
    }

    override suspend fun before(key: RowKey, limit: Int): List<Keyed<String>> = io {
        db.read(
            "SELECT orderKey, trackId FROM live_song WHERE owner = ?1 AND gen = $gen AND orderKey < ?2 ORDER BY orderKey DESC LIMIT ?3",
            owner, keyBytes(key), limit,
        ) { Keyed(rowKey(it.getBlob(0), it.getString(1)), it.getString(1)) }
    }

    override suspend fun keyAt(position: Int): RowKey? = io {
        db.read(
            "SELECT orderKey, trackId FROM live_song WHERE owner = ?1 AND gen = $gen ORDER BY orderKey LIMIT 1 OFFSET ?2",
            owner, position,
        ) { rowKey(it.getBlob(0), it.getString(1)) }.firstOrNull()
    }

    override suspend fun keyOf(trackId: String): RowKey? = io {
        db.read("SELECT orderKey, trackId FROM live_song WHERE owner = ?1 AND gen = $gen AND trackId = ?2", owner, trackId) {
            rowKey(it.getBlob(0), it.getString(1))
        }.firstOrNull()
    }

    // A key from another reader of the list: the same row when it is still
    // there, else the row its song has now.
    override suspend fun rekey(key: RowKey): RowKey? = io {
        val kept = db.readLong(
            "SELECT COUNT(*) FROM live_song WHERE owner = ?1 AND gen = $gen AND orderKey = ?2 AND trackId = ?3",
            owner, keyBytes(key), key.id,
        )
        if (kept > 0) key else keyOf(key.id)
    }

    override suspend fun present(ids: List<String>): Set<String> = io {
        val found = HashSet<String>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            found += db.read(
                "SELECT trackId FROM live_song WHERE owner = ?1 AND gen = $gen AND trackId IN (${marks(chunk.size)})",
                owner, *chunk.toTypedArray(),
            ) { it.getString(0) }
        }
        found
    }

    private fun keyBytes(key: RowKey): ByteArray = (key.values.single() as SqlValue.Blob).v.toByteArray()

    private fun rowKey(orderKey: ByteArray, trackId: String) = liveRowKey(orderKey, trackId)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

// A live list row's key: its order key, then its song.
internal fun liveRowKey(orderKey: ByteArray, trackId: String) = RowKey(0, listOf(SqlValue.Blob(orderKey.toByteString())), trackId)

// What a play of a live list no reader shows starts from, read from its
// rules with no write (LivePicks.firstPicks): its first rows in the list's
// order, keyed as the list's own rows are, how many songs it picks, and,
// when asked, one of them drawn at random.
class LiveStartPicks(val rows: List<Keyed<String>>, val count: Int, val drawn: Keyed<String>?)

// A play of a live list for a queue's fill. Until the fill's copy is made
// it reads the songs the list shows (its saved page's, an editor's preview
// of the same rules, or else `fallback`, the owner of the page the play was
// chosen from, such as the car's copy): the small check, the first song,
// the song at a place, each one statement, never waiting for the copy.
// With none of them shown, a play starts from firstPicks. `copy` makes the
// fill's own copy under its owner; from then on every read is of that
// copy, which nothing changes until the fill clears it.
class LiveSpecLoader internal constructor(
    private val picks: LivePicks,
    private val db: CatalogDatabase,
    private val listId: String?,
    private val query: LibraryQuery,
    val into: String,
    private val fallback: String? = null,
) : ListLoader<String> {
    @Volatile private var reading: LiveLoader? = null

    override val tables = arrayOf("live_song", "live_state")
    override val order: Comparator<RowKey> = SqlOrder.rows()
    override val songsOnce = true

    override fun trackIdOf(row: String): String = row

    // Whether the fill's copy is made.
    val copied: Boolean get() = reading?.owner == into

    // Whether reads answer from songs a list holds now: the copy, or the
    // list as a reader shows it.
    suspend fun shown(): Boolean = source() != null

    // What reads answer now: the copy once made, else the list as shown,
    // else nothing (no list shows these songs yet).
    private suspend fun source(): LiveLoader? {
        reading?.let { return it }
        return shownOwner()?.let { picks.loader(it) }
    }

    // The owner whose shown songs the list is read from: its own page's or
    // preview's, else the fallback's once it shows them.
    private suspend fun shownOwner(): String? = picks.sourceOf(listId, query) ?: fallback?.takeIf { picks.shown(it) != null }

    // Makes the fill's copy, from the songs the list shows when it runs
    // (or, with none shown, by a walk of its rules), and answers its swap
    // count and how many songs it holds.
    suspend fun copy(): LiveCopy {
        val made = picks.copy(shownOwner(), query, into)
        reading = picks.loader(into)
        return made
    }

    // The list's first `n` songs and how many it picks, worked out from its
    // rules with no write, for a play while no reader shows it; with `seed`,
    // one song drawn at random too.
    suspend fun firstPicks(n: Int, seed: Long?): LiveStartPicks = picks.firstPicks(query, n, seed)

    // Null while no list shows these songs, so a play never takes an empty
    // read for an empty list.
    override suspend fun small(): List<Keyed<String>>? = source()?.small()

    override suspend fun count(): Int = source()?.count() ?: 0
    override suspend fun survey(anchor: RowKey?): Survey = source()?.survey(anchor) ?: Survey(0, null, null)
    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<String>> = source()?.after(key, limit).orEmpty()
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<String>> = source()?.before(key, limit).orEmpty()
    override suspend fun keyAt(position: Int): RowKey? = source()?.keyAt(position)
    override suspend fun keyOf(trackId: String): RowKey? = source()?.keyOf(trackId)
    override suspend fun rekey(key: RowKey): RowKey? = source()?.rekey(key)
    override suspend fun present(ids: List<String>): Set<String> = source()?.present(ids).orEmpty()
}
