package app.winters.octo.design

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.launch

// What a sheet's line asked for: a share of the room left, and where it sits
// across the sheet.
private data class SheetChild(val weight: Float = 0f, val fill: Boolean = true, val align: Alignment.Horizontal? = null)

private data class SheetWeightElement(val weight: Float, val fill: Boolean) : ModifierNodeElement<SheetWeightNode>() {
    override fun create() = SheetWeightNode(weight, fill)
    override fun update(node: SheetWeightNode) {
        node.weight = weight
        node.fill = fill
    }
}

private class SheetWeightNode(var weight: Float, var fill: Boolean) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any =
        ((parentData as? SheetChild) ?: SheetChild()).copy(weight = weight, fill = fill)
}

private data class SheetAlignElement(val align: Alignment.Horizontal) : ModifierNodeElement<SheetAlignNode>() {
    override fun create() = SheetAlignNode(align)
    override fun update(node: SheetAlignNode) {
        node.align = align
    }
}

private class SheetAlignNode(var align: Alignment.Horizontal) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any =
        ((parentData as? SheetChild) ?: SheetChild()).copy(align = align)
}

// The sheet's lines, laid out as a column would. A line with a weight (a
// list that scrolls itself, say) shares the room the others leave, so it
// still gets a height it can fill. When the lines are taller than the room,
// the column scrolls instead of cutting off the last ones.
private object SheetColumnScope : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier {
        require(weight > 0f) { "A weight must be above zero" }
        return this.then(SheetWeightElement(weight, fill))
    }

    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this.then(SheetAlignElement(alignment))

    // Lines in a sheet do not line up by text baselines.
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

// How far the lines are scrolled, and how far they can go.
private class SheetScroll {
    var offset by mutableFloatStateOf(0f)
    var max = 0

    // Moves by a finger's drag, and answers how much of it was used.
    fun drag(delta: Float): Float {
        val next = (offset - delta).coerceIn(0f, max.toFloat())
        val used = offset - next
        offset = next
        return used
    }
}

@Composable
internal fun SheetColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = remember { SheetScroll() }
    val scrollable = rememberScrollableState { delta -> scroll.drag(delta) }
    val scope = rememberCoroutineScope()
    Layout(
        content = { SheetColumnScope.content() },
        modifier = modifier
            .clipToBounds()
            .scrollable(scrollable, Orientation.Vertical)
            // Lets a screen reader scroll the lines too.
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange(value = { scroll.offset }, maxValue = { scroll.max.toFloat() })
                scrollBy { _, y ->
                    scope.launch { scrollable.scrollBy(-y) }
                    true
                }
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val room = constraints.maxHeight
        val children = measurables.map { it.parentData as? SheetChild }
        val placeables = arrayOfNulls<Placeable>(measurables.size)

        // Plain lines first, each at most the sheet's height, never unbounded,
        // so a list inside that scrolls itself still measures.
        var used = 0
        measurables.forEachIndexed { index, measurable ->
            if ((children[index]?.weight ?: 0f) > 0f) return@forEachIndexed
            val placeable = measurable.measure(Constraints(maxWidth = width, maxHeight = room))
            placeables[index] = placeable
            used += placeable.height
        }

        // Weighted lines share what is left.
        val totalWeight = children.sumOf { (it?.weight ?: 0f).toDouble() }.toFloat()
        val left = (room - used).coerceAtLeast(0)
        measurables.forEachIndexed { index, measurable ->
            val child = children[index] ?: return@forEachIndexed
            if (child.weight <= 0f) return@forEachIndexed
            val placeable = if (constraints.hasBoundedHeight) {
                val share = (left * child.weight / totalWeight).toInt()
                measurable.measure(Constraints(maxWidth = width, minHeight = if (child.fill) share else 0, maxHeight = share))
            } else {
                measurable.measure(Constraints(maxWidth = width))
            }
            placeables[index] = placeable
            used += placeable.height
        }

        val height = used.coerceAtMost(room)
        scroll.max = used - height
        layout(width, height) {
            // Read here, so a scroll only places the lines again.
            var y = -scroll.offset.coerceAtMost(scroll.max.toFloat()).toInt()
            placeables.forEachIndexed { index, placeable ->
                placeable ?: return@forEachIndexed
                val align = children[index]?.align ?: Alignment.Start
                placeable.place(align.align(placeable.width, width, layoutDirection), y)
                y += placeable.height
            }
        }
    }
}
