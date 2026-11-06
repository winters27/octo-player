package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Indication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// How strongly a tab the selection passes over flashes.
const val GearShiftTint = 0.22f

// How strongly a tab at `index` flashes while the selection pill, at
// `pill` (in tabs from the first), passes over it: most as the pill's
// middle crosses it, none once the pill is a whole tab away. The tab the
// pill is heading for does not flash; it is where the pill lands.
fun gearShiftTint(index: Int, pill: Float, selected: Int): Float {
    if (index == selected) return 0f
    return GearShiftTint * (1f - abs(pill - index)).coerceIn(0f, 1f)
}

// A few choices in one glaze tray, the chosen one in a pill filled with the
// accent that glides between them. The tray is recessed, with a hairline of
// accent at its edge; the tabs the pill passes on its way flash a little
// accent, like gears going by. The chosen tab's words belong in
// OctoColors.OnAccent. With `fillWidth` the tabs share the width given;
// without, each is as wide as the widest label. With `labelWidth` (and no
// `fillWidth`) each is instead as wide as its own words, and the pill
// takes the width of the tab it is on. `tabPadding` is the air either side
// of a tab's words.
@Composable
fun GlazeTabs(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    fillWidth: Boolean = true,
    // What a tab shows while it has the keyboard; none by default.
    indication: Indication? = null,
    // Whether a tab can be picked; a tab that cannot is shown but ignores
    // presses.
    enabled: (Int) -> Boolean = { true },
    labelWidth: Boolean = false,
    tabPadding: Dp = if (fillWidth) 4.dp else 14.dp,
    tab: @Composable (index: Int, chosen: Boolean) -> Unit,
) {
    val motion = motionScale()
    val pill = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        if (motion.still) pill.snapTo(selected.toFloat()) else pill.animateTo(selected.toFloat(), octoTween(motion, OctoDuration.Neutral, OctoEasing.Smooth))
    }
    var cell by remember { mutableFloatStateOf(0f) }
    // Where each tab starts and how wide it is, when tabs differ in width.
    var starts by remember { mutableStateOf(emptyList<Float>()) }
    var widths by remember { mutableStateOf(emptyList<Float>()) }
    val pillColor = OctoColors.Accent
    val gearColor = OctoColors.GlassAccent

    Glaze(
        modifier
            .height(height)
            .dropShadow(CircleShape, Shadow(radius = shadowBlur(10f), color = shadowColor(0.20f), offset = DpOffset(0.dp, 2.dp))),
    ) {
        // Recessed: a shade falling from the top inside edge, and a hairline
        // of accent round the rim.
        Box(
            Modifier
                .matchParentSize()
                .innerShadow(CircleShape, Shadow(radius = shadowBlur(2f), spread = 1.6.dp, color = shadowColor(0.15f), offset = DpOffset(0.dp, 3.dp)))
                .innerShadow(CircleShape, Shadow(radius = 0.dp, spread = 0.5.dp, color = OctoColors.GlassAccent.copy(alpha = 0.25f))),
        )
        Layout(
            content = {
                repeat(count) { index ->
                    val chosen = index == selected
                    Box(
                        Modifier.selectable(
                            selected = chosen,
                            enabled = enabled(index),
                            interactionSource = null,
                            indication = indication,
                            role = Role.Tab,
                        ) { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Sharing a width, the words may use nearly all of
                        // their tab; hugging them, the tabs keep some air.
                        Box(Modifier.padding(horizontal = tabPadding)) { tab(index, chosen) }
                    }
                }
            },
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .fillMaxHeight()
                .padding(4.dp)
                .selectableGroup()
                .drawBehind {
                    val at = pill.value
                    val corner = CornerRadius(size.height / 2f)
                    if (labelWidth && !fillWidth && starts.size == count && count > 0) {
                        val from = at.toInt().coerceIn(0, count - 1)
                        val to = (from + 1).coerceAtMost(count - 1)
                        val part = (at - from).coerceIn(0f, 1f)
                        val left = starts[from] + (starts[to] - starts[from]) * part
                        val wide = widths[from] + (widths[to] - widths[from]) * part
                        drawRoundRect(pillColor, Offset(left, 0f), Size(wide, size.height), corner)
                        for (index in 0 until count) {
                            val tint = if (pill.isRunning) gearShiftTint(index, at, selected) else 0f
                            if (tint > 0f) {
                                drawRoundRect(gearColor.copy(alpha = tint), Offset(starts[index], 0f), Size(widths[index], size.height), corner)
                            }
                        }
                        return@drawBehind
                    }
                    if (cell <= 0f) return@drawBehind
                    val left = at * cell
                    drawRoundRect(pillColor, Offset(left, 0f), Size(cell, size.height), corner)
                    // Gears going by.
                    for (index in 0 until count) {
                        val tint = if (pill.isRunning) gearShiftTint(index, at, selected) else 0f
                        if (tint > 0f) {
                            drawRoundRect(gearColor.copy(alpha = tint), Offset(index * cell, 0f), Size(cell, size.height), corner)
                        }
                    }
                },
        ) { measurables, constraints ->
            val tall = constraints.maxHeight
            if (labelWidth && !fillWidth) {
                val own = measurables.map { it.maxIntrinsicWidth(tall) }
                val total = own.sum()
                val limit = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
                val whole = total.coerceIn(constraints.minWidth, limit)
                val scale = if (total > 0) whole.toFloat() / total else 0f
                val fit = own.map { it * scale }
                val edges = fit.runningFold(0f) { sum, each -> sum + each }
                starts = edges.dropLast(1)
                widths = fit
                val placed = fit.mapIndexed { i, each -> measurables[i].measure(Constraints.fixed(edges[i + 1].roundToInt() - edges[i].roundToInt(), tall)) }
                return@Layout layout(whole, tall) {
                    placed.forEachIndexed { index, placeable -> placeable.place(edges[index].roundToInt(), 0) }
                }
            }
            val widest = measurables.maxOfOrNull { it.maxIntrinsicWidth(tall) } ?: 0
            val width = if (fillWidth && constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                (widest * count).coerceIn(constraints.minWidth, if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE)
            }
            val each = if (count > 0) width.toFloat() / count else 0f
            cell = each
            val placeables = measurables.map { it.measure(Constraints.fixed(each.roundToInt(), tall)) }
            layout(width, tall) {
                placeables.forEachIndexed { index, placeable -> placeable.place((index * each).roundToInt(), 0) }
            }
        }
    }
}
