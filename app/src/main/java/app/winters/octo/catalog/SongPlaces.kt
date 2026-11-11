package app.winters.octo.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// How far apart a copy places rows that have nothing after them: room for
// about twenty rows to come between two of them in later copies before the
// places between run out.
internal const val SEQ_SPAN = 1L shl 20

// How many of a source's rows one look at its old order reads at a time,
// and how far a batch's last rows look for room above them.
private const val ORDER_WALK = 500
private const val ROOM_WALK = 2_000

// A source's album or artist still to come in a copy: not yet in the stage.
internal const val STAGE_ALBUM_TO_COME = "NOT EXISTS (SELECT 1 FROM stage_album g WHERE g.id = s.id)"
internal const val STAGE_ARTIST_TO_COME = "NOT EXISTS (SELECT 1 FROM stage_artist g WHERE g.id = s.id)"

// Where a copy puts the rows of one of its source's tables (songs, albums
// or artists) in the source's order (seq), a batch at a time in the order
// it builds them: each place is past every place given before it, so the
// places' order is the build's order and only that order is read from
// them.
internal interface Places {
    // Places for the rows with `ids`, in this order.
    suspend fun of(ids: List<String>): LongArray

    // How many rows were placed since the last call other than where their
    // source rows are, or more: the stage keeps the sum, and the swap writes
    // a table's places only when its copy moved any.
    fun takeMoved(): Int
}

internal suspend fun Places.placeSongs(rows: List<SourceTrackEntity>): List<SourceTrackEntity> {
    val seqs = of(rows.map { it.id })
    return rows.mapIndexed { i, row -> row.copy(seq = seqs[i]) }
}

internal suspend fun Places.placeAlbums(rows: List<SourceAlbumEntity>): List<SourceAlbumEntity> {
    val seqs = of(rows.map { it.id })
    return rows.mapIndexed { i, row -> row.copy(seq = seqs[i]) }
}

internal suspend fun Places.placeArtists(rows: List<SourceArtistEntity>): List<SourceArtistEntity> {
    val seqs = of(rows.map { it.id })
    return rows.mapIndexed { i, row -> row.copy(seq = seqs[i]) }
}

// Places one after another from `first`, each copy anew. Every row placed
// counts as moved.
internal class DensePlaces(first: Long = 0) : Places {
    private var next = first
    private var placed = 0

    override suspend fun of(ids: List<String>): LongArray {
        placed += ids.size
        return LongArray(ids.size) { next++ }
    }

    override fun takeMoved(): Int = placed.also { placed = 0 }
}

// The source's last copy's places in `table` (source_track, source_album or
// source_artist) kept where its order still holds, so a copy rewrites only
// the rows that changed: a row whose place among the others is unchanged
// keeps its place, a new row takes one between its neighbors', and so does
// a row that moved. Where the places between two neighbors run out, the
// rows after them take new places too, as few as make room. Every place
// given is past `after`.
//
// A batch's rows keep the longest rising run of their old places that
// comes after every place given so far, weighed against the rows still to
// come (`toCome`, a condition on the source's row `s`) whose old places the
// run would pass, which would then need new places: a run is kept only
// where it keeps more places than it costs. So rows taken out or added
// cost no other row its place, and of a moved row and the rows it moved
// past, the fewer take new places.
internal class KeptPlaces(
    private val db: CatalogDatabase,
    table: String,
    private val sourceId: String,
    toCome: String,
    after: Long = -1L,
) : Places {
    private val oldSql = oldPlacesSql(table)
    private val betweenSql = toComeBetweenSql(table, toCome)
    private val afterSql = toComeAfterSql(table, toCome)

    // The last place given; the next batch's places are past it.
    private var last = after
    private var moved = 0

    override fun takeMoved(): Int = moved.also { moved = 0 }

    override suspend fun of(ids: List<String>): LongArray = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext LongArray(0)
        val old = HashMap<String, Long>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            db.read(oldSql.format(marks(chunk.size)), sourceId, *chunk.toTypedArray()) { it.getString(0) to it.getLong(1) }
                .forEach { (id, seq) -> old[id] = seq }
        }
        val was = LongArray(ids.size) { old[ids[it]] ?: NONE }
        val batch = ids.toHashSet()
        val kept = kept(was, batch)
        val seqs = LongArray(ids.size)
        var low = last
        var from = 0
        for (i in ids.indices) {
            if (!kept[i]) continue
            // The rows since the last kept one take places below this one's;
            // with too few left between, this one takes a new place too.
            if (was[i] - low - 1 < i - from) continue
            spread(seqs, from, i, low, was[i])
            seqs[i] = was[i]
            low = was[i]
            from = i + 1
        }
        if (from < ids.size) spread(seqs, from, ids.size, low, ceiling(low, ids.size - from, batch))
        for (i in ids.indices) if (was[i] != NONE && seqs[i] != was[i]) moved++
        last = seqs.last()
        seqs
    }

    // Which of the batch's rows keep their old places: the longest rising
    // run ending at each row whose old place is past `last`, less the rows
    // to come whose old places it would pass, the best one kept when it
    // keeps more than it costs.
    private fun kept(was: LongArray, batch: Set<String>): BooleanArray {
        val n = was.size
        val kept = BooleanArray(n)
        val ends = IntArray(n)
        val before = IntArray(n) { -1 }
        val run = IntArray(n)
        var runs = 0
        var top = NONE
        for (i in 0 until n) {
            val at = was[i]
            if (at <= last) continue
            var lo = 0
            var hi = runs
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (was[ends[mid]] < at) lo = mid + 1 else hi = mid
            }
            if (lo > 0) before[i] = ends[lo - 1]
            run[i] = lo + 1
            ends[lo] = i
            if (lo == runs) runs++
            if (at > top) top = at
        }
        if (runs == 0) return kept
        // The old places the batch could pass, up to one more than it holds:
        // a run passing that many keeps fewer than it costs.
        val passed = toComeBetween(last, top, n + 1, batch)
        var best = -1
        var bestGain = 0
        var bestCost = Int.MAX_VALUE
        for (i in 0 until n) {
            if (run[i] == 0) continue
            val cost = below(passed, was[i])
            val gain = run[i] - cost
            if (gain > bestGain || (gain == bestGain && gain > 0 && cost < bestCost)) {
                best = i
                bestGain = gain
                bestCost = cost
            }
        }
        var i = best
        while (i >= 0) {
            kept[i] = true
            i = before[i]
        }
        return kept
    }

    // Old places, rising, of rows to come and not in the batch, between
    // `after` and `before`; at most `most` of them.
    private fun toComeBetween(after: Long, before: Long, most: Int, batch: Set<String>): LongArray {
        val found = ArrayList<Long>()
        var from = after
        while (found.size < most) {
            val page = db.read(betweenSql, sourceId, from, before, ORDER_WALK) { it.getLong(0) to it.getString(1) }
            for ((seq, id) in page) {
                if (id in batch) continue
                found += seq
                if (found.size >= most) break
            }
            if (page.size < ORDER_WALK) break
            from = page.last().first
        }
        return found.toLongArray()
    }

    // The old place the batch's last `count` rows are placed below: the
    // first of a row to come (not in the batch) past `low` with room below
    // it for them, and between them and it for each row to come they pass,
    // which takes a new place when its batch comes. Null when none is found
    // within ROOM_WALK rows, so they take places on whole spans past `low`:
    // places one apart (the order of a copy made before places were kept)
    // are spaced out once, by the copy that first adds a row among them.
    private fun ceiling(low: Long, count: Int, batch: Set<String>): Long? {
        var from = low
        var passed = 0L
        var walked = 0
        while (walked < ROOM_WALK) {
            val page = db.read(afterSql, sourceId, from, ORDER_WALK) { it.getLong(0) to it.getString(1) }
            for ((seq, id) in page) {
                if (id in batch) continue
                if ((seq - low) / (count + 1) >= passed + 1) return seq
                passed++
            }
            walked += page.size
            if (page.size < ORDER_WALK) return null
            from = page.last().first
        }
        return null
    }

    private companion object {
        // No old place: a row new to the source.
        const val NONE = Long.MIN_VALUE

        // How many of `sorted` are below `value`.
        fun below(sorted: LongArray, value: Long): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < value) lo = mid + 1 else hi = mid
            }
            return lo
        }

        // Places for the rows `from` until `to`, rising from `low` toward
        // `high`, which leaves room for them all: evenly between the two,
        // SEQ_SPAN apart at most. With nothing above (null), places run on
        // whole spans past `low`, from SEQ_SPAN at the least, so a row that
        // later comes before the first has room too.
        fun spread(seqs: LongArray, from: Int, to: Int, low: Long, high: Long?) {
            val count = to - from
            if (count == 0) return
            if (high == null) {
                val base = Math.floorDiv(maxOf(low, 0L), SEQ_SPAN) * SEQ_SPAN
                for (j in 0 until count) seqs[from + j] = base + SEQ_SPAN * (j + 1)
                return
            }
            val step = minOf(SEQ_SPAN, (high - low) / (count + 1))
            for (j in 0 until count) seqs[from + j] = low + step * (j + 1)
        }
    }
}

// A batch's rows' places in the source's last copy, by id; `%s` is the
// batch's marks.
internal fun oldPlacesSql(table: String) = "SELECT id, seq FROM `$table` WHERE sourceId = ? AND id IN (%s)"

// A page of the source's rows still to come (`toCome`, a condition on the
// source's row `s`), in its old order, between two places or after one.
internal fun toComeBetweenSql(table: String, toCome: String) =
    "SELECT s.seq, s.id FROM `$table` s INDEXED BY index_${table}_sourceId_seq " +
        "WHERE s.sourceId = ? AND s.seq > ? AND s.seq < ? AND $toCome ORDER BY s.seq LIMIT ?"

internal fun toComeAfterSql(table: String, toCome: String) =
    "SELECT s.seq, s.id FROM `$table` s INDEXED BY index_${table}_sourceId_seq " +
        "WHERE s.sourceId = ? AND s.seq > ? AND $toCome ORDER BY s.seq LIMIT ?"
