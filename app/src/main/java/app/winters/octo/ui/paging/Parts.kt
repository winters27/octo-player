package app.winters.octo.ui.paging

import app.winters.octo.playback.QUEUE_WINDOW

// The largest group of rows tied on their lead that is read whole and
// ordered in memory; a bigger one is walked on its own index.
const val TIE_GROUP_MAX = 2_000

// How a part reads: rows, a count, or ids, from a statement and its values.
interface PartReads<R> {
    suspend fun rows(sql: String, args: List<SqlValue>): List<R>

    suspend fun count(sql: String, args: List<SqlValue>): Int

    suspend fun ids(sql: String, args: List<SqlValue>): List<String>
}

// One stretch of a list that one index walks in the list's order: a
// segment, or one group of a segment.
interface Part<R> {
    // Where this part's rows stand against `key`: below 0 when they all come
    // before it, 0 when the key is one of this part's, above 0 when they all
    // come after it.
    fun place(key: RowKey): Int

    // Up to `n` rows after `key` (a key of this part), or from the top.
    suspend fun after(key: RowKey?, n: Int): List<Keyed<R>>

    // Up to `n` rows before `key`, nearest first, or from the bottom.
    suspend fun before(key: RowKey?, n: Int): List<Keyed<R>>

    suspend fun count(): Int

    // How many of this part's rows come before `key` (a key of this part).
    suspend fun countBefore(key: RowKey): Int

    // The key of the part's row at `position`, or null past its end.
    suspend fun keyAt(position: Int): RowKey?

    // The key of the part's row holding this id, or null.
    suspend fun find(id: String): RowKey?

    suspend fun present(ids: List<String>): Set<String>

    // How many of the part's names fall in each of LetterRanges' ranges,
    // for a part ordered by a name; null otherwise.
    suspend fun rangeCounts(): List<Int>? = null

    // The part's rows, at most `limit`, in any order, as one arm of a
    // UNION ALL: its text and values.
    fun arm(limit: Int): Pair<String, List<SqlValue>>
}

// SQL text for row values and orders.
internal fun tuple(columns: List<String>) = columns.joinToString(", ", "(", ")")

internal fun marks(n: Int) = List(n) { "?" }.joinToString(", ", "(", ")")

internal fun orderBy(columns: List<String>, descending: Boolean) = columns.joinToString(", ") { if (descending) "$it DESC" else it }

// A key's values as a statement binds them: its sort values, then its id.
internal fun bound(key: RowKey): List<SqlValue> = key.values + SqlValue.Text(key.id)

// One stretch of a list on one index. Its key columns are `lead`, which
// run the way the list runs, then `ties`, always ascending, ending in the
// row's id. Read ascending, the index is walked forward. Read descending,
// the same index is walked backward and each run of rows tied on the lead
// is turned around, so the ties still read ascending; a run bigger than a
// page is walked forward on its own. A key holds every key column but the
// id as its values, then the id.
class IndexPart<R>(
    val segment: Int,
    private val from: String,
    private val where: List<String>,
    private val args: List<SqlValue>,
    private val lead: List<String>,
    private val ties: List<String>,
    private val descending: Boolean,
    private val select: String,
    private val reads: PartReads<R>,
    private val keyOf: (R) -> RowKey,
    // The name column a letter rail counts by, when the part has one.
    private val heading: String? = null,
    // How many of a key's first values the part holds fixed rather than
    // reads from a column (a rating and a like, for one group of a
    // segment), and where the part stands against a key.
    private val fixed: Int = 0,
    private val placeOf: ((RowKey) -> Int)? = null,
    // The column holding a row's id; when it is not the last key column,
    // a key's values hold every key column and its id only names the row.
    private val idColumn: String? = null,
    // A key's value for each key column, in order, when the key holds them
    // otherwise than as its values then its id.
    private val columnsOf: ((RowKey) -> List<SqlValue>)? = null,
    // The same filter as `where`, with the same marks, written so the whole
    // part's count reads the filter's own index (a scope the walks keep off
    // its index so the order's index leads them).
    private val wholeWhere: List<String>? = null,
) : Part<R> {
    private val columns = lead + ties
    private val flips = descending && lead.isNotEmpty()
    private val id = idColumn ?: ties.last()
    private val idIsKey = idColumn == null

    override fun place(key: RowKey): Int = placeOf?.invoke(key) ?: segment.compareTo(key.segment)

    private fun bound(key: RowKey): List<SqlValue> =
        columnsOf?.invoke(key) ?: (key.values.drop(fixed) + if (idIsKey) listOf(SqlValue.Text(key.id)) else emptyList())

    private fun sql(conditions: List<String>, order: String, limit: String, select: String = this.select): String {
        val all = where + conditions
        val filter = if (all.isEmpty()) "" else " WHERE " + all.joinToString(" AND ")
        return "SELECT $select FROM $from$filter" + (if (order.isEmpty()) "" else " ORDER BY $order") + " $limit"
    }

    private suspend fun rows(conditions: List<String>, values: List<SqlValue>, order: String, limit: String): List<Keyed<R>> =
        reads.rows(sql(conditions, order, limit), args + values).map { Keyed(keyOf(it), it) }

    private suspend fun count(conditions: List<String>, values: List<SqlValue>): Int {
        val all = (if (conditions.isEmpty()) wholeWhere ?: where else where) + conditions
        val filter = if (all.isEmpty()) "" else " WHERE " + all.joinToString(" AND ")
        return reads.count("SELECT COUNT(*) FROM $from$filter", args + values)
    }

    private fun leadOf(key: RowKey) = bound(key).take(lead.size)

    private fun tiesOf(key: RowKey) = bound(key).drop(lead.size)

    private fun sameLead(a: RowKey, b: RowKey) = leadOf(a).zip(leadOf(b)).all { (x, y) -> SqlOrder.compare(x, y) == 0 }

    override suspend fun after(key: RowKey?, n: Int): List<Keyed<R>> {
        if (n <= 0) return emptyList()
        if (!flips) {
            val cond = if (key == null) emptyList() else listOf("${tuple(columns)} > ${marks(columns.size)}")
            return rows(cond, if (key == null) emptyList() else bound(key), orderBy(columns, false), "LIMIT $n")
        }
        val out = ArrayList<Keyed<R>>()
        var below: List<SqlValue>? = null
        if (key != null) {
            out += inGroup(leadOf(key), tiesOf(key), forward = true, n)
            if (out.size >= n) return out
            below = leadOf(key)
        }
        walkRuns(out, n, below, forward = true)
        return out
    }

    override suspend fun before(key: RowKey?, n: Int): List<Keyed<R>> {
        if (n <= 0) return emptyList()
        if (!flips) {
            val cond = if (key == null) emptyList() else listOf("${tuple(columns)} < ${marks(columns.size)}")
            return rows(cond, if (key == null) emptyList() else bound(key), orderBy(columns, true), "LIMIT $n")
        }
        val out = ArrayList<Keyed<R>>()
        var above: List<SqlValue>? = null
        if (key != null) {
            out += inGroup(leadOf(key), tiesOf(key), forward = false, n)
            if (out.size >= n) return out
            above = leadOf(key)
        }
        walkRuns(out, n, above, forward = false)
        return out
    }

    // In a list that runs descending, the rows of one lead group after (or
    // before) a key's ties, in the list's order of that way.
    private suspend fun inGroup(group: List<SqlValue>, after: List<SqlValue>?, forward: Boolean, n: Int): List<Keyed<R>> {
        val cond = mutableListOf("${tuple(lead)} = ${marks(lead.size)}")
        val values = ArrayList(group)
        if (after != null) {
            cond += "${tuple(ties)} ${if (forward) ">" else "<"} ${marks(ties.size)}"
            values += after
        }
        return rows(cond, values, orderBy(ties, !forward), "LIMIT $n")
    }

    // The lead groups beyond `from` (past it in the list's order of that
    // way), read a block at a time backward on the index for `forward`;
    // each complete group turned to read its ties the list's way; a group
    // cut by the block is read again from its start next time, and one
    // bigger than the room left is walked on its own.
    private suspend fun walkRuns(out: MutableList<Keyed<R>>, n: Int, start: List<SqlValue>?, forward: Boolean) {
        var from = start
        while (out.size < n) {
            val room = n - out.size
            val cond = if (from == null) emptyList() else listOf("${tuple(lead)} ${if (forward) "<" else ">"} ${marks(lead.size)}")
            val block = rows(cond, from ?: emptyList(), orderBy(columns, forward), "LIMIT ${room + 1}")
            if (block.isEmpty()) return
            val runs = runsOf(block)
            val more = block.size > room
            val complete = if (more) runs.dropLast(1) else runs
            if (complete.isEmpty()) {
                out += inGroup(leadOf(runs[0][0].key), null, forward, room)
                return
            }
            complete.forEach { out += it.asReversed() }
            if (!more) return
            from = leadOf(complete.last().last().key)
        }
    }

    private fun runsOf(block: List<Keyed<R>>): List<List<Keyed<R>>> {
        val runs = ArrayList<MutableList<Keyed<R>>>()
        for (row in block) {
            val last = runs.lastOrNull()
            if (last != null && sameLead(last[0].key, row.key)) last += row else runs += mutableListOf(row)
        }
        return runs
    }

    override suspend fun count(): Int = count(emptyList(), emptyList())

    override suspend fun countBefore(key: RowKey): Int {
        if (!flips) return count(listOf("${tuple(columns)} < ${marks(columns.size)}"), bound(key))
        val above = count(listOf("${tuple(lead)} > ${marks(lead.size)}"), leadOf(key))
        val inGroup = count(listOf("${tuple(lead)} = ${marks(lead.size)}", "${tuple(ties)} < ${marks(ties.size)}"), leadOf(key) + tiesOf(key))
        return above + inGroup
    }

    override suspend fun keyAt(position: Int): RowKey? {
        if (position < 0) return null
        if (!flips) return rows(emptyList(), emptyList(), orderBy(columns, false), "LIMIT 1 OFFSET $position").firstOrNull()?.key
        // The row at that place walking the index backward has the right
        // lead; its group's rows run the other way in the list.
        val back = rows(emptyList(), emptyList(), orderBy(columns, true), "LIMIT 1 OFFSET $position").firstOrNull() ?: return null
        val group = leadOf(back.key)
        val above = count(listOf("${tuple(lead)} > ${marks(lead.size)}"), group)
        return rows(listOf("${tuple(lead)} = ${marks(lead.size)}"), group, orderBy(ties, false), "LIMIT 1 OFFSET ${position - above}").firstOrNull()?.key
    }

    override suspend fun find(id: String): RowKey? = rows(listOf("${this.id} = ?"), listOf(SqlValue.Text(id)), "", "LIMIT 1").firstOrNull()?.key

    override suspend fun present(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val all = where + "$id IN ${marks(ids.size)}"
        return reads.ids("SELECT $id FROM $from WHERE ${all.joinToString(" AND ")}", args + ids.map { SqlValue.Text(it) }).toSet()
    }

    override suspend fun rangeCounts(): List<Int>? {
        val name = heading ?: return null
        return LetterRanges.ranges.map { range ->
            val cond = ArrayList<String>()
            val values = ArrayList<SqlValue>()
            range.lo?.let {
                cond += "$name >= ?"
                values += SqlValue.Text(it)
            }
            range.hi?.let {
                cond += "$name < ?"
                values += SqlValue.Text(it)
            }
            count(cond, values)
        }
    }

    override fun arm(limit: Int): Pair<String, List<SqlValue>> = sql(emptyList(), "", "LIMIT $limit") to args
}

// A segment whose rows tie on a lead from another table than the one that
// orders the ties: songs liked at one moment, or played as often and as
// lately. Groups are read a block at a time in the lead's order on the
// lead's own index, and each complete group is ordered in memory by its
// ties; a group bigger than TIE_GROUP_MAX is walked on the ties' own
// index, filtered to the group (`groupWalk`). A key holds the lead, then
// the ties, then the id.
class TieGroupPart<R>(
    val segment: Int,
    private val from: String,
    private val where: List<String>,
    private val args: List<SqlValue>,
    private val lead: List<String>,
    // Columns after the lead that make the lead index's order unique.
    private val tail: List<String>,
    private val idColumn: String,
    private val descending: Boolean,
    private val select: String,
    private val reads: PartReads<R>,
    private val keyOf: (R) -> RowKey,
    // The order of a group's rows by their ties (every key value after the
    // lead, then the id).
    private val ties: Comparator<RowKey>,
    // A big group's rows in tie order after (or before) a key, a page at a
    // time: its statement and values for a group, a key or null, the way
    // and what follows its LIMIT (a count of rows, or `1 OFFSET k` for the
    // one row at a place).
    private val groupWalk: (group: List<SqlValue>, from: RowKey?, forward: Boolean, limit: String) -> Pair<String, List<SqlValue>>,
    private val groupCount: (group: List<SqlValue>, before: RowKey) -> Pair<String, List<SqlValue>>,
    // The order a block of groups is read in, the list's way (`forward`)
    // or back, matching the lead index's own order.
    private val blockOrder: ((forward: Boolean) -> String)? = null,
) : Part<R> {
    override fun place(key: RowKey): Int = segment.compareTo(key.segment)

    private fun leadOf(key: RowKey) = key.values.take(lead.size)

    private fun same(a: List<SqlValue>, b: List<SqlValue>) = a.zip(b).all { (x, y) -> SqlOrder.compare(x, y) == 0 }

    private fun filter(conditions: List<String>) = (where + conditions).let { if (it.isEmpty()) "" else " WHERE " + it.joinToString(" AND ") }

    private suspend fun rows(conditions: List<String>, values: List<SqlValue>, order: String, limit: String): List<Keyed<R>> =
        reads.rows("SELECT $select FROM $from${filter(conditions)}" + (if (order.isEmpty()) "" else " ORDER BY $order") + " $limit", args + values)
            .map { Keyed(keyOf(it), it) }

    private suspend fun count(conditions: List<String>, values: List<SqlValue>): Int =
        reads.count("SELECT COUNT(*) FROM $from${filter(conditions)}", args + values)

    // Lead groups past `group` in the list's order: below it for a list
    // that runs descending.
    private fun past(forward: Boolean) = if (descending == forward) "<" else ">"

    private fun leadOrder(forward: Boolean) = blockOrder?.invoke(forward) ?: orderBy(lead + tail, descending == forward)

    private suspend fun groupSize(group: List<SqlValue>) = count(listOf("${tuple(lead)} = ${marks(lead.size)}"), group)

    private suspend fun wholeGroup(group: List<SqlValue>) =
        rows(listOf("${tuple(lead)} = ${marks(lead.size)}"), group, "", "LIMIT ${TIE_GROUP_MAX + 1}").sortedWith { a, b -> ties.compare(a.key, b.key) }

    private suspend fun walkGroup(group: List<SqlValue>, from: RowKey?, forward: Boolean, n: Int): List<Keyed<R>> {
        val (sql, values) = groupWalk(group, from, forward, "$n")
        return reads.rows(sql, values).map { Keyed(keyOf(it), it) }
    }

    // A group's rows after (or before) `from` in tie order, the list's way.
    private suspend fun inGroup(group: List<SqlValue>, from: RowKey?, forward: Boolean, n: Int): List<Keyed<R>> {
        if (groupSize(group) > TIE_GROUP_MAX) return walkGroup(group, from, forward, n)
        val all = wholeGroup(group)
        val way = if (forward) all else all.asReversed()
        val past = if (from == null) way else way.filter { (ties.compare(it.key, from) > 0) == forward && ties.compare(it.key, from) != 0 }
        return past.take(n)
    }

    override suspend fun after(key: RowKey?, n: Int) = walk(key, n, forward = true)

    override suspend fun before(key: RowKey?, n: Int) = walk(key, n, forward = false)

    private suspend fun walk(key: RowKey?, n: Int, forward: Boolean): List<Keyed<R>> {
        if (n <= 0) return emptyList()
        val out = ArrayList<Keyed<R>>()
        var from: List<SqlValue>? = null
        if (key != null) {
            out += inGroup(leadOf(key), key, forward, n)
            if (out.size >= n) return out
            from = leadOf(key)
        }
        while (out.size < n) {
            val room = n - out.size
            val cond = if (from == null) emptyList() else listOf("${tuple(lead)} ${past(forward)} ${marks(lead.size)}")
            val block = rows(cond, from ?: emptyList(), leadOrder(forward), "LIMIT ${room + 1}")
            if (block.isEmpty()) break
            val runs = ArrayList<MutableList<Keyed<R>>>()
            for (row in block) {
                val last = runs.lastOrNull()
                if (last != null && same(leadOf(last[0].key), leadOf(row.key))) last += row else runs += mutableListOf(row)
            }
            val more = block.size > room
            val complete = if (more) runs.dropLast(1) else runs
            if (complete.isEmpty()) {
                out += inGroup(leadOf(runs[0][0].key), null, forward, room)
                break
            }
            for (run in complete) {
                val sorted = run.sortedWith { a, b -> ties.compare(a.key, b.key) }
                out += if (forward) sorted else sorted.asReversed()
            }
            if (!more) break
            from = leadOf(complete.last()[0].key)
        }
        return out
    }

    override suspend fun count(): Int = count(emptyList(), emptyList())

    override suspend fun countBefore(key: RowKey): Int {
        val group = leadOf(key)
        val before = count(listOf("${tuple(lead)} ${if (descending) ">" else "<"} ${marks(lead.size)}"), group)
        val inGroup = if (groupSize(group) > TIE_GROUP_MAX) {
            val (sql, values) = groupCount(group, key)
            reads.count(sql, values)
        } else {
            wholeGroup(group).count { ties.compare(it.key, key) < 0 }
        }
        return before + inGroup
    }

    override suspend fun keyAt(position: Int): RowKey? {
        if (position < 0) return null
        val probe = rows(emptyList(), emptyList(), leadOrder(true), "LIMIT 1 OFFSET $position").firstOrNull() ?: return null
        val group = leadOf(probe.key)
        val before = count(listOf("${tuple(lead)} ${if (descending) ">" else "<"} ${marks(lead.size)}"), group)
        val within = position - before
        return if (groupSize(group) > TIE_GROUP_MAX) {
            // The one row at its place in the group, read alone.
            val (sql, values) = groupWalk(group, null, true, "1 OFFSET $within")
            reads.rows(sql, values).firstOrNull()?.let(keyOf)
        } else {
            wholeGroup(group).getOrNull(within)?.key
        }
    }

    override suspend fun find(id: String): RowKey? = rows(listOf("$idColumn = ?"), listOf(SqlValue.Text(id)), "", "LIMIT 1").firstOrNull()?.key

    override suspend fun present(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return reads.ids("SELECT $idColumn FROM $from${filter(listOf("$idColumn IN ${marks(ids.size)}"))}", args + ids.map { SqlValue.Text(it) }).toSet()
    }

    override fun arm(limit: Int): Pair<String, List<SqlValue>> = "SELECT $select FROM $from${filter(emptyList())} LIMIT $limit" to args
}

// A list made of parts in list order, each read on its own index: the
// segments of a sort (played then unplayed songs, rated then unrated). A
// part list may change between reads (a rating group that comes or goes);
// keys find their part by their values.
open class PartsLoader<R, T : Any>(
    private val parts: suspend () -> List<Part<R>>,
    override val tables: Array<String>,
    override val order: Comparator<RowKey>,
    override val songsOnce: Boolean,
    private val rowOf: (R) -> T,
    private val idOf: (T) -> String?,
    // For a list with a letter rail (one part, ordered by a name): whether
    // the names run Z to A.
    private val railDescending: Boolean? = null,
    // How the small check reads: one statement over every part's arm, each
    // row's key told by the part it says it came from.
    private val smallRows: (suspend (String, List<SqlValue>) -> List<R>)? = null,
    private val smallKey: ((R) -> RowKey)? = null,
    // The small check's arms, for a list whose parts take reads to find
    // (the ratings a library holds, an artist's albums): one statement over
    // the list's scope, its rows' keys read from their own columns.
    private val smallArms: List<Pair<String, List<SqlValue>>>? = null,
) : ListLoader<T> {
    private fun keyed(row: Keyed<R>) = Keyed(row.key, rowOf(row.row))

    // The first part whose rows do not all come before `key`.
    private fun startOf(ps: List<Part<R>>, key: RowKey) = ps.indexOfFirst { it.place(key) >= 0 }.let { if (it < 0) ps.size else it }

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<T>> {
        val ps = parts()
        val out = ArrayList<Keyed<R>>()
        var i = if (key == null) 0 else startOf(ps, key)
        var from = key
        while (i < ps.size && out.size < limit) {
            val part = ps[i]
            out += part.after(from?.takeIf { part.place(it) == 0 }, limit - out.size)
            from = null
            i++
        }
        return out.map(::keyed)
    }

    override suspend fun before(key: RowKey, limit: Int): List<Keyed<T>> {
        val ps = parts()
        val out = ArrayList<Keyed<R>>()
        // The last part whose rows do not all come after `key`.
        var i = ps.indexOfLast { it.place(key) <= 0 }
        var from: RowKey? = key
        while (i >= 0 && out.size < limit) {
            val part = ps[i]
            out += part.before(from?.takeIf { part.place(it) == 0 }, limit - out.size)
            from = null
            i--
        }
        return out.map(::keyed)
    }

    override suspend fun count(): Int = parts().sumOf { it.count() }

    override suspend fun survey(anchor: RowKey?): Survey {
        val ps = parts()
        val counts = ps.map { it.count() }
        var runs: List<app.winters.octo.ui.common.LetterRun>? = null
        var count = counts.sum()
        if (railDescending != null && ps.size == 1) {
            val ranges = ps[0].rangeCounts()
            if (ranges != null) {
                runs = LetterRanges.runs(ranges, railDescending)
                count = ranges.sum()
            }
        }
        val at = anchor?.let { key ->
            var before = 0
            for ((i, part) in ps.withIndex()) {
                val place = part.place(key)
                if (place < 0) before += counts[i]
                if (place == 0) before += part.countBefore(key)
            }
            before
        }
        return Survey(count, runs, at)
    }

    override suspend fun keyAt(position: Int): RowKey? {
        if (position < 0) return null
        var left = position
        for (part in parts()) {
            val n = part.count()
            if (left < n) return part.keyAt(left)
            left -= n
        }
        return null
    }

    override suspend fun keyOf(trackId: String): RowKey? {
        for (part in parts()) part.find(trackId)?.let { return it }
        return null
    }

    override suspend fun rekey(key: RowKey): RowKey? = key

    override suspend fun present(ids: List<String>): Set<String> {
        val found = HashSet<String>()
        for (part in parts()) found += part.present(ids)
        return found
    }

    // One statement: every part's rows, each part at most QUEUE_WINDOW + 1,
    // in index order; at most QUEUE_WINDOW in all are the whole list, put
    // in list order here.
    override suspend fun small(): List<Keyed<T>>? {
        val read = smallRows ?: return super.small()
        val key = smallKey ?: return super.small()
        val arms = smallArms ?: parts().map { it.arm(QUEUE_WINDOW + 1) }
        if (arms.isEmpty()) return emptyList()
        val sql = arms.joinToString(" UNION ALL ") { "SELECT * FROM (${it.first})" }
        val rows = read(sql, arms.flatMap { it.second })
        if (rows.size > QUEUE_WINDOW) return null
        return rows.map { Keyed(key(it), rowOf(it)) }.sortedWith { a, b -> order.compare(a.key, b.key) }
    }

    override fun trackIdOf(row: T): String? = idOf(row)
}
