package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoType

// One thing on a header's control line, like a sort button, a country
// button or Play. It can be drawn `forms` ways, widest first (a country by
// its name, its code, then its globe alone); `content` draws the one asked
// for. `overflow` says when it moves into the page's More menu once the line
// is too narrow, lowest first; null never moves (Play and Shuffle, a page's
// main choice). `more` is the More row it becomes there, doing the same
// thing with its value in its words ("Sort: Date liked"). One that
// `squeezes` (a button whose words may end in an ellipsis, never a title)
// gives up room as the very last step, down to SqueezeMin.
class LineItem(
    val key: String,
    val overflow: Int? = null,
    val more: MoreItem? = null,
    val forms: Int = 1,
    val squeezes: Boolean = false,
    val content: @Composable (form: Int) -> Unit,
)

// The least a squeezed button keeps: a few letters and its chevron.
val SqueezeMin = 72.dp

// A page's main choice on its control line, as segments: never moved into
// More, only drawn smaller, and a dropdown of the same choices as the last
// resort.
class LineSegments(val options: List<String>, val selected: Int, val title: String, val onSelect: (Int) -> Unit)

// How the segments are drawn: with their words at full size, with their
// words stepped down (to SegmentMinText), or as a dropdown.
enum class SegmentsForm { Full, Small, Dropdown }

// What the planner knows of one item: its width in each form, widest
// first, when it moves into More (null: never), and the least it can be
// squeezed to (null: it is never squeezed).
class PlanItem(val widths: List<Int>, val overflow: Int?, val squeezeTo: Int? = null)

// What the segments need: their least width with full-size words, with
// stepped-down words, and the dropdown's width.
class PlanSegments(val full: Int, val small: Int, val dropdown: Int)

// The plan for one control line: each item's form (null when it moved into
// More), how the segments are drawn, whether the More button shows, and the
// width any squeezed item is held to, by its place.
data class LinePlan(val forms: List<Int?>, val segments: SegmentsForm, val more: Boolean, val squeezed: Map<Int, Int> = emptyMap())

// Fits a control line into `width`, all widths in the same unit, `gap`
// between any two things on it. Everything shows as it is when it fits.
// Otherwise, in turn: items with shorter forms take them, left to right;
// items move into More by `overflow`, lowest first (the More button then
// shows, if the page had none); the segments' words step down; and last the
// segments become a dropdown; and at the very end an item that squeezes
// gives up what is still over, down to its least. `lead` and `tail` are
// planned together, lead first; the plan's forms are in that order.
fun planLine(
    width: Int,
    gap: Int,
    lead: List<PlanItem>,
    segments: PlanSegments?,
    tail: List<PlanItem>,
    moreButton: Int,
    pageMore: Boolean,
): LinePlan {
    val items = lead + tail
    val forms = MutableList<Int?>(items.size) { 0 }
    var segmentsForm = SegmentsForm.Full
    fun moreShows() = pageMore || forms.any { it == null }
    fun total(): Int {
        val parts = items.indices.mapNotNull { index -> forms[index]?.let { items[index].widths[it] } } +
            listOfNotNull(
                segments?.let {
                    when (segmentsForm) {
                        SegmentsForm.Full -> it.full
                        SegmentsForm.Small -> it.small
                        SegmentsForm.Dropdown -> it.dropdown
                    }
                },
                moreButton.takeIf { moreShows() },
            )
        return parts.sum() + gap * (parts.size - 1).coerceAtLeast(0)
    }
    while (total() > width) {
        val shorter = items.indices.firstOrNull { index -> forms[index]?.let { it < items[index].widths.lastIndex } == true }
        if (shorter != null) {
            forms[shorter] = forms[shorter]!! + 1
            continue
        }
        val moving = items.indices.filter { forms[it] != null && items[it].overflow != null }.minByOrNull { items[it].overflow!! }
        if (moving != null) {
            forms[moving] = null
            continue
        }
        if (segments != null && segmentsForm != SegmentsForm.Dropdown) {
            segmentsForm = SegmentsForm.entries[segmentsForm.ordinal + 1]
            continue
        }
        break
    }
    val squeezed = mutableMapOf<Int, Int>()
    items.indices.forEach { index ->
        val given = squeezed.entries.sumOf { (at, held) -> items[at].widths[forms[at]!!] - held }
        val over = total() - given - width
        val form = forms[index]
        val least = items[index].squeezeTo
        if (over > 0 && form != null && least != null) {
            val now = items[index].widths[form]
            if (now > least) squeezed[index] = maxOf(least, now - over)
        }
    }
    return LinePlan(forms, segmentsForm, moreShows(), squeezed)
}

// The room between any two things on a control line.
val LineGap = 8.dp

// What segments need beyond their words: each keeps 4dp either side of its
// words, and the glaze 4dp inside each end.
fun segmentsWidth(count: Int, widest: Int, tabSides: Int, ends: Int): Int = count * (widest + tabSides) + ends

private sealed interface Slot {
    data class Form(val key: String, val form: Int) : Slot
    data object Dropdown : Slot
    data object Segments : Slot
    data object More : Slot
}

// A header's control line: `lead` from the start (the page's own controls,
// like its sort or country button), then the `segments` filling the room
// between, then `tail` at the end (Play, Shuffle) and the More button with
// `more` and anything moved into it. It is measured at its real width and
// planned with planLine, so on a narrow phone the lesser controls move into
// More before the main choice gives anything up.
@Composable
fun HeaderControlLine(
    modifier: Modifier = Modifier,
    lead: List<LineItem> = emptyList(),
    segments: LineSegments? = null,
    tail: List<LineItem> = emptyList(),
    more: List<MoreItem> = emptyList(),
) {
    val full = segments?.let { rememberWidestWords(it.options, OctoType.label) } ?: 0
    val small = segments?.let { rememberWidestWords(it.options) } ?: 0
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val gap = LineGap.roundToPx()
        val items = lead + tail
        val forms = items.map { item ->
            (0 until item.forms).map { form ->
                subcompose(Slot.Form(item.key, form)) { Box { item.content(form) } }.first()
            }
        }
        val dropdown = segments?.let { seg ->
            subcompose(Slot.Dropdown) {
                val sheet = LocalChoiceSheet.current
                HeaderDropdown(seg.options[seg.selected], onClick = { showDropdown(sheet, seg.title, seg.options, seg.selected, seg.onSelect) }, clickLabel = "Choose what to show")
            }.first()
        }
        val tab = 8.dp.roundToPx()
        val plan = planLine(
            width = width,
            gap = gap,
            lead = lead.indices.map { index ->
                PlanItem(
                    forms[index].map { m -> m.maxIntrinsicWidth(Constraints.Infinity) },
                    lead[index].overflow.takeIf { lead[index].more != null },
                    SqueezeMin.roundToPx().takeIf { lead[index].squeezes },
                )
            },
            segments = segments?.let {
                PlanSegments(
                    full = segmentsWidth(it.options.size, full.dp.roundToPx(), tab, tab),
                    small = segmentsWidth(it.options.size, small.dp.roundToPx(), tab, tab),
                    dropdown = dropdown!!.maxIntrinsicWidth(Constraints.Infinity),
                )
            },
            tail = tail.indices.map { index ->
                val at = lead.size + index
                PlanItem(
                    forms[at].map { m -> m.maxIntrinsicWidth(Constraints.Infinity) },
                    tail[index].overflow.takeIf { tail[index].more != null },
                    SqueezeMin.roundToPx().takeIf { tail[index].squeezes },
                )
            },
            moreButton = HeaderButtonSize.roundToPx(),
            pageMore = more.isNotEmpty(),
        )
        fun measured(index: Int): Placeable? = plan.forms[index]?.let { form ->
            forms[index][form].measure(plan.squeezed[index]?.let { Constraints(maxWidth = it) } ?: Constraints())
        }
        val leadShown = lead.indices.mapNotNull { measured(it) }
        val tailShown = tail.indices.mapNotNull { measured(lead.size + it) }
        val moved = items.indices.filter { plan.forms[it] == null }.mapNotNull { items[it].more }
        val moreButton = if (plan.more) subcompose(Slot.More) { HeaderMore(moved + more) }.firstOrNull()?.measure(Constraints()) else null
        val ends = tailShown + listOfNotNull(moreButton)
        val leadWidth = leadShown.sumOf { it.width } + gap * leadShown.size
        val endsWidth = ends.sumOf { it.width } + gap * (ends.size - 1).coerceAtLeast(0)
        val choice: Placeable? = when {
            segments == null -> null
            plan.segments == SegmentsForm.Dropdown -> dropdown!!.measure(Constraints())
            else -> {
                val room = (width - leadWidth - endsWidth - (if (ends.isEmpty()) 0 else gap)).coerceAtLeast(0)
                subcompose(Slot.Segments) { Segmented(segments.options, segments.selected, segments.onSelect, fit = true) }
                    .first().measure(Constraints.fixedWidth(room))
            }
        }
        val height = (leadShown + ends + listOfNotNull(choice)).maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            leadShown.forEach {
                it.place(x, (height - it.height) / 2)
                x += it.width + gap
            }
            choice?.place(x, (height - choice.height) / 2)
            var end = width
            ends.asReversed().forEach {
                end -= it.width
                it.place(end, (height - it.height) / 2)
                end -= gap
            }
        }
    }
}

// The same line with a fixed `control` first and fixed `actions` at the end,
// for the tops that have nothing to move, like an album's under its cover.
@Composable
fun HeaderControlLine(
    control: (@Composable () -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HeaderControlLine(modifier, lead = fixedLead(control), tail = fixedTail(actions))
}

internal fun fixedLead(control: (@Composable () -> Unit)?): List<LineItem> = listOfNotNull(control?.let { LineItem("control") { _ -> it() } })

internal fun fixedTail(actions: (@Composable RowScope.() -> Unit)?): List<LineItem> = listOfNotNull(
    actions?.let { content ->
        LineItem("actions") { _ -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() } }
    },
)

// Round Play and Shuffle as two items at the end of a control line; they
// never move. `loading` shows on Play, `shuffling` on Shuffle (a Shuffle
// waiting for its start), each keeping the other from a tap meanwhile.
fun playShuffleItems(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean = true, loading: Boolean = false, shuffling: Boolean = false): List<LineItem> = listOf(
    LineItem("play") { _ -> HeaderButton(app.winters.octo.design.OctoIcons.Play, "Play", onPlay, enabled = enabled && !shuffling, loading = loading) },
    LineItem("shuffle") { _ -> HeaderButton(app.winters.octo.design.OctoIcons.Shuffle, "Shuffle", onShuffle, enabled = enabled && !loading, loading = shuffling) },
)
