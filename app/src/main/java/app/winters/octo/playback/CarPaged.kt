@file:OptIn(UnstableApi::class)

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.stableRead
import app.winters.octo.listening.PlayTotals
import app.winters.octo.ui.common.LetterRun
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.PLAY_TOTAL
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// How many rows one read of a long list asks for.
internal const val CAR_PAGE = 200

// A row of a long list as a car shows it, with the cover its letter's or
// part's picture may take its colors from.
class CarRow(val item: MediaItem, val cover: String?)

// A long list a car may open, read a page at a time from the phone's own
// loader of it, so the car shows the phone's order and nothing holds the
// list: what comes before its rows (a genre's albums), how its items show,
// how a number of them is said ("12 albums"), whether it files under the
// phone's letters (a list sorted by name), a page of its rows as the car
// shows them, null for a row it shows nothing for, and where the parts its
// passes cut are kept for the one state of the list the loader reads (none:
// every answer cuts them again).
class CarPagedListing<T : Any>(
    val loader: ListLoader<T>,
    val style: CarStyle? = null,
    val countLine: ((Int) -> String)? = null,
    val lettered: Boolean = false,
    val lead: List<CarRow> = emptyList(),
    val kept: CarKeptCut? = null,
    val rows: suspend (List<T>) -> List<CarRow?>,
)

// The parts one pass over a list cut, kept for the state of the list they
// were cut from, so another answer reading the list in that same state cuts
// nothing again: the list's own answer, and each of its parts', read only
// the rows they send. A part is a few numbers and covers, so a list's parts
// are small beside its rows. Only the parts of the state read last are
// kept.
class CarKeptParts {
    private val lock = Mutex()
    private var at: Any? = null
    private val cut = HashMap<Pair<List<CarSpan>, Int>, List<CarPart>>()

    // The parts for an answer that reads the list in `state`, which must
    // name rows that never change while it is that state.
    fun at(state: Any): CarKeptCut = CarKeptCut(this, state)

    internal suspend fun parts(state: Any, spans: List<CarSpan>, budget: Int, pass: suspend () -> List<CarPart>): List<CarPart> {
        lock.withLock { if (at == state) cut[spans to budget]?.let { return it } }
        val parts = pass()
        lock.withLock {
            if (at != state) {
                at = state
                cut.clear()
            }
            cut[spans to budget] = parts
        }
        return parts
    }
}

// The kept parts of one state of a list, for an answer that reads it.
class CarKeptCut internal constructor(private val parts: CarKeptParts, private val state: Any) {
    internal suspend fun parts(spans: List<CarSpan>, budget: Int, pass: suspend () -> List<CarPart>): List<CarPart> = parts.parts(state, spans, budget, pass)
}

// Where a walk of a list's rows starts and how far it goes: after `after`
// (from the top for null), at most `count` rows (to the end for null).
internal data class CarSpan(val after: RowKey?, val count: Int?)

// Where a walk stands: at item `lead` of the lead, or once past it in span
// `span`, after the row `after`, with `left` of its rows to go.
internal data class CarAt(val lead: Int, val span: Int, val after: RowKey?, val left: Int?)

// What an answer sends, worked out before it is read: the items a walk
// from `from` reads (at most `count`, all for null), or rows already built.
internal sealed interface CarPlan {
    class Items(val spans: List<CarSpan>, val from: CarAt?, val count: Int?) : CarPlan

    class Rows(val items: List<MediaItem>) : CarPlan
}

// Each shown row of `spans` in order, from `from` (the top for null, the
// lead first), until `each` answers false. Reads a page at a time.
internal suspend fun <T : Any> CarPagedListing<T>.walk(
    spans: List<CarSpan>,
    from: CarAt? = null,
    each: suspend (CarRow, CarAt) -> Boolean,
) {
    val first = spans.firstOrNull()
    val start = from ?: CarAt(0, 0, first?.after, first?.count)
    var leadAt = start.lead
    while (leadAt < lead.size) {
        if (!each(lead[leadAt], CarAt(leadAt, 0, first?.after, first?.count))) return
        leadAt++
    }
    var span = start.span
    var after = start.after
    var left = start.left
    while (span < spans.size) {
        while (left == null || left > 0) {
            val asked = if (left == null) CAR_PAGE else minOf(CAR_PAGE, left)
            val page = loader.after(after, asked)
            if (page.isEmpty()) break
            val shown = rows(page.map { it.row })
            for (i in page.indices) {
                val at = CarAt(lead.size, span, after, left)
                after = page[i].key
                left = left?.minus(1)
                val row = shown.getOrNull(i) ?: continue
                if (!each(row, at)) return
            }
            if (page.size < asked) break
        }
        span++
        if (span < spans.size) {
            after = spans[span].after
            left = spans[span].count
        }
    }
}

// Whether every item of `spans` fits one answer, adding up sizes only until
// they pass the budget.
internal suspend fun <T : Any> CarPagedListing<T>.fits(spans: List<CarSpan>, budget: Int): Boolean {
    var used = 0
    walk(spans) { row, _ ->
        used += carBytes(row.item)
        used <= budget
    }
    return used <= budget
}

// The first covers among the items of `spans`, each once, stopping at
// SPLIT_COVERS.
internal suspend fun <T : Any> CarPagedListing<T>.coversOf(spans: List<CarSpan>): List<String> {
    val covers = LinkedHashSet<String>()
    walk(spans) { row, _ ->
        row.cover?.let(covers::add)
        covers.size < SPLIT_COVERS
    }
    return covers.toList()
}

// Up to `count` items read from `from` (all of them for null), stopping
// before the one that would pass the budget, so an answer never does.
internal suspend fun <T : Any> CarPagedListing<T>.itemsFrom(spans: List<CarSpan>, from: CarAt?, count: Int?, budget: Int): List<MediaItem> {
    val out = ArrayList<MediaItem>()
    var used = 0
    walk(spans, from) { row, _ ->
        if (count != null && out.size >= count) return@walk false
        val size = carBytes(row.item)
        if (out.isNotEmpty() && used + size > budget) return@walk false
        used += size
        out += row.item
        true
    }
    return out
}

// One part of a list as carParts cuts it: where it runs in the list's items,
// its first item's title, its first covers, and where a read of it starts.
internal class CarPart(val index: Int, val first: Int, val last: Int, val firstTitle: String?, val covers: List<String>, val start: CarAt) {
    val range: IntRange get() = first..last
    val count: Int get() = last - first + 1
}

// What one pass over a list finds of its parts, for the part rows of `node`
// (the list or one of its letters): the first-level rows when they fit one
// answer together, else the rows of a second level that packs them by
// their own sizes; and for the part asked for (`wanted`, as either level),
// what opening it answers.
internal class CarParts(
    val rowsFit: Boolean,
    val firstRows: List<MediaItem>?,
    val groupRows: List<MediaItem>,
    val part: CarPart?,
    val partGroup: Int?,
    val groupMembers: List<MediaItem>,
)

// Packs the items of `spans` into parts exactly as carParts does, one item
// at a time, and the part rows into a second level the same way when they
// do not fit one answer together. A part row's size is the row as sent:
// under `node` at the first level, under its group's row at the second.
// The parts are cut by one pass over the items, or taken from the kept
// ones while the list stands as it was when they were cut.
internal suspend fun <T : Any> CarPagedListing<T>.packParts(
    node: CarNode,
    spans: List<CarSpan>,
    rows: CarSplitRows,
    budget: Int,
    wanted: Int? = null,
): CarParts {
    val parts = kept?.parts(spans, budget) { cutParts(spans, budget) } ?: cutParts(spans, budget)
    var firstRows: MutableList<MediaItem>? = ArrayList()
    var firstUsed = 0
    val groupRows = ArrayList<MediaItem>()
    val groupMembers = ArrayList<MediaItem>()
    var found: CarPart? = null
    var foundGroup: Int? = null

    // The second level as it fills: the group being packed, its rows' size,
    // its first part and its covers.
    var group = 0
    var groupUsed = 0
    var groupFirst: CarPart? = null
    var groupLast: CarPart? = null
    val groupCovers = LinkedHashSet<String>()

    fun closeGroup() {
        val head = groupFirst ?: return
        val tail = groupLast ?: return
        groupRows += rows.part(CarNode.Part(group, node), head.first..tail.last, head.firstTitle, CarStyle.List, groupCovers.toList())
        group++
        groupUsed = 0
        groupFirst = null
        groupLast = null
        groupCovers.clear()
    }

    fun nestedRow(part: CarPart, at: Int) = rows.part(CarNode.Part(part.index, CarNode.Part(at, node)), part.range, part.firstTitle, style, part.covers)

    fun closed(part: CarPart) {
        val row = rows.part(CarNode.Part(part.index, node), part.range, part.firstTitle, style, part.covers)
        firstUsed += carBytes(row)
        firstRows = firstRows?.takeIf { firstUsed <= budget }?.also { it += row }
        var nested = nestedRow(part, group)
        if (groupFirst != null && groupUsed + carBytes(nested) > budget) {
            closeGroup()
            nested = nestedRow(part, group)
        }
        groupUsed += carBytes(nested)
        if (groupFirst == null) groupFirst = part
        groupLast = part
        for (cover in part.covers) if (groupCovers.size < SPLIT_COVERS) groupCovers += cover
        if (part.index == wanted) {
            found = part
            foundGroup = group
        }
        if (group == wanted) groupMembers += nested
    }

    parts.forEach(::closed)
    closeGroup()
    val rowsFit = firstRows != null
    return CarParts(rowsFit, firstRows, groupRows, found, foundGroup, groupMembers)
}

// The parts one pass over the items of `spans` cuts: each as many items in
// a row as fit one answer.
private suspend fun <T : Any> CarPagedListing<T>.cutParts(spans: List<CarSpan>, budget: Int): List<CarPart> {
    val parts = ArrayList<CarPart>()
    var index = 0
    var partFirst = 0
    var used = 0
    var start: CarAt? = null
    var title: String? = null
    val covers = LinkedHashSet<String>()
    walk(spans) { row, at ->
        val size = carBytes(row.item)
        if (index > partFirst && used + size > budget) {
            parts += CarPart(parts.size, partFirst, index - 1, title, covers.toList(), start!!)
            partFirst = index
            used = 0
            covers.clear()
            start = null
        }
        if (start == null) {
            start = at
            title = row.item.mediaMetadata.title?.toString()
        }
        used += size
        row.cover?.let { if (covers.size < SPLIT_COVERS) covers += it }
        index++
        true
    }
    if (start != null) parts += CarPart(parts.size, partFirst, index - 1, title, covers.toList(), start!!)
    return parts
}

// The answer for one node of a long list a car opened, worked out by passes
// over the list, never holding it: the list, one of its letters, one of
// its parts, or a part of a second level. `base` is the list's own node;
// `spans` gives the rows of the list (null) or of one of its letters.
// Whole when it fits one answer; otherwise the list's letters, when it
// files under more than one, else its parts, and a second level of parts
// only when the part rows themselves do not fit. A part past the end
// answers nothing.
internal suspend fun <T : Any> carPagedPlan(
    node: CarNode,
    base: CarNode,
    listing: CarPagedListing<T>,
    rows: CarSplitRows,
    budget: Int = CAR_ANSWER_BUDGET,
): CarPlan {
    val whole = listOf(CarSpan(null, null))
    suspend fun spansOf(at: CarNode): List<CarSpan>? =
        if (at is CarNode.Heading) listing.loader.survey(null).runs?.let { letterSpans(listing, it, at.heading) } else whole

    // A part of a second level: part `node.index` when it is in group
    // `node.parent.index`.
    val parent = (node as? CarNode.Part)?.parent
    if (node is CarNode.Part && parent is CarNode.Part) {
        val spans = spansOf(parent.parent) ?: return CarPlan.Rows(emptyList())
        val parts = listing.packParts(parent.parent, spans, rows, budget, wanted = node.index)
        val part = parts.part
        if (parts.rowsFit || part == null || parts.partGroup != parent.index) return CarPlan.Rows(emptyList())
        return CarPlan.Items(spans, part.start, part.count)
    }
    if (node is CarNode.Part && parent != null) {
        val spans = spansOf(parent) ?: return CarPlan.Rows(emptyList())
        val parts = listing.packParts(parent, spans, rows, budget, wanted = node.index)
        if (!parts.rowsFit) return CarPlan.Rows(parts.groupMembers)
        val part = parts.part ?: return CarPlan.Rows(emptyList())
        return CarPlan.Items(spans, part.start, part.count)
    }
    val spans = spansOf(node) ?: return CarPlan.Rows(emptyList())
    if (listing.fits(spans, budget)) return CarPlan.Items(spans, null, null)
    if (node == base && listing.lettered) {
        val letters = letterRows(node, listing, rows)
        if (letters != null) return CarPlan.Rows(letters)
    }
    val parts = listing.packParts(node, spans, rows, budget)
    return CarPlan.Rows(if (parts.rowsFit) parts.firstRows.orEmpty() else parts.groupRows)
}

// What a plan sends: its rows, or the items it reads, kept within budget.
internal suspend fun <T : Any> carPagedSend(listing: CarPagedListing<T>, plan: CarPlan, budget: Int = CAR_ANSWER_BUDGET): List<MediaItem> = when (plan) {
    is CarPlan.Rows -> plan.items
    is CarPlan.Items -> listing.itemsFrom(plan.spans, plan.from, plan.count, budget)
}

// Answers a car's long lists as one state of the library. Every pass over
// a list (whether it fits, its letters, its parts and their covers) runs
// before each try, outside every lock and hold, and leaves only where the
// answer's rows start; a try reads just the rows it sends, so a last try
// holds the merge lock (a Read, which waits at most for the swap running)
// no longer than one answer's reads. A list that reads the stored play
// counts reads them under their rule, holding them still in its last try,
// and ends with TotalsFailed at once while they cannot be brought up to
// date.
class CarReads(private val merge: CatalogMerge, private val totals: PlayTotals) {
    suspend fun <T : Any> answer(
        node: CarNode,
        base: CarNode,
        listing: CarPagedListing<T>,
        rows: CarSplitRows,
        budget: Int = CAR_ANSWER_BUDGET,
    ): List<MediaItem> {
        var planned: CarPlan? = null
        val prepare: suspend () -> Unit = { planned = carPagedPlan(node, base, listing, rows, budget) }
        val block: suspend () -> List<MediaItem> = { carPagedSend(listing, planned ?: carPagedPlan(node, base, listing, rows, budget), budget) }
        return withContext(Dispatchers.IO) {
            if (PLAY_TOTAL in listing.loader.tables) totals.read(HoldKind.Read, prepare, block) else stableRead(merge, HoldKind.Read, prepare, block)
        }
    }
}

// A row for each letter the list files under, in list order, with how many
// it holds and its first covers; null when it files under one at most.
private suspend fun <T : Any> letterRows(node: CarNode, listing: CarPagedListing<T>, rows: CarSplitRows): List<MediaItem>? {
    val runs = listing.loader.survey(null).runs ?: return null
    val letters = runs.map { it.letter }.distinct()
    if (letters.size < 2) return null
    return letters.map { letter ->
        val heading = letter.toString()
        val spans = letterSpans(listing, runs, heading)
        val count = runs.filter { it.letter == letter }.sumOf { it.count }
        rows.letter(CarNode.Heading(heading, node), listing.style, listing.coversOf(spans), listing.countLine?.invoke(count))
    }
}

// The rows under one of the list's letters, run by run in list order: each
// run from the row before its first, by one probe.
private suspend fun <T : Any> letterSpans(listing: CarPagedListing<T>, runs: List<LetterRun>, heading: String): List<CarSpan> {
    return runs.filter { it.letter.toString() == heading }.map { run ->
        CarSpan(if (run.start == 0) null else listing.loader.keyAt(run.start - 1), run.count)
    }
}
