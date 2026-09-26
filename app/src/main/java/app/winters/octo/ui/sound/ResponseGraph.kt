package app.winters.octo.ui.sound

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.GAIN_RANGE_DB
import app.winters.octo.sound.GraphicBands
import app.winters.octo.sound.ResponseCurve
import app.winters.octo.sound.SoundSettings
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

// How many points the drawn curve has.
private const val CurvePoints = 192

private val PlotHeight = 220.dp
// Room under the plot for the frequency labels.
private val LabelBand = 22.dp
// Room around the plot so the end nodes are not cut off.
private val PadAcross = 10.dp
private val PadUpDown = 12.dp
// How near a node a finger has to land to take hold of it.
private val HitRadius = 30.dp
// The node sizes, at rest and while held or chosen.
private val NodeRadius = 5.5.dp
private val ActiveRadius = 8.dp

// The curve and nodes when the equalizer is off.
private val Muted = Color(0xFF6B7075)

// Where the gridlines fall, and the labels under a parametric curve.
private val GridHz = listOf(100f, 1_000f, 10_000f)
private val EdgeLabelsHz = listOf(20f, 100f, 1_000f, 10_000f, 20_000f)

// Where frequencies and gains land on the plot, in pixels: frequency on a
// log scale across, gain from +12 dB at the top to -12 dB at the bottom.
private class Plot(width: Float, height: Float, density: Density) {
    val left = with(density) { PadAcross.toPx() }
    val right = width - left
    val top = with(density) { PadUpDown.toPx() }
    val bottom = height - with(density) { (LabelBand + PadUpDown).toPx() }
    val pxPerDb = (bottom - top) / (2 * GAIN_RANGE_DB)

    fun x(hz: Float) = left + ln(hz / MIN_HZ) / ln(MAX_HZ / MIN_HZ) * (right - left)
    fun hz(x: Float) = MIN_HZ * (MAX_HZ / MIN_HZ).pow(((x - left) / (right - left)).coerceIn(0f, 1f))
    fun y(db: Float) = top + (GAIN_RANGE_DB - db.coerceIn(-GAIN_RANGE_DB, GAIN_RANGE_DB)) * pxPerDb
}

// A handle on the curve: where it is, and the gain it sets.
private data class Node(val hz: Float, val gainDb: Float)

private fun nodesOf(settings: SoundSettings): List<Node> = when (settings.mode) {
    EqMode.Graphic -> GraphicBands.zip(settings.graphicGains) { hz, gain -> Node(hz, gain) }
    EqMode.Parametric -> settings.filters.map { Node(it.frequency, it.gainDb) }
}

// The node nearest a touch, if one is close enough. The chosen node wins a
// tie, so stacked filters can still be told apart.
private fun nodeAt(points: List<Offset>, touch: Offset, reach: Float, preferred: Int?): Int? {
    if (preferred != null && preferred in points.indices && (points[preferred] - touch).getDistance() <= reach) return preferred
    return points.indices
        .map { it to (points[it] - touch).getDistance() }
        .filter { it.second <= reach }
        .minByOrNull { it.second }
        ?.first
}

// The same edit to a node, whichever kind of curve it is on. The equalizer
// comes on too, so the change is heard.
private fun moveNode(settings: SoundSettings, index: Int, hz: Float?, gainDb: Float): SoundSettings {
    val moved = when (settings.mode) {
        EqMode.Graphic -> withBandGain(settings, index, gainDb)
        EqMode.Parametric -> {
            val filter = settings.filters.getOrNull(index) ?: return settings
            withFilter(settings, index, filter.copy(frequency = hz ?: filter.frequency, gainDb = gainDb))
        }
    }
    return moved.copy(eqEnabled = true)
}

// The live frequency response: a white line with a soft glow and a glass
// tint under it while the equalizer is on, grey while it is off. Nodes sit
// on the curve: up and down sets their gain, and in parametric mode left
// and right sets their frequency. A double tap puts a node back to 0 dB.
@Composable
internal fun ResponseGraph(
    settings: SoundSettings,
    selected: Int,
    onSelect: (Int) -> Unit,
    onPreview: ((SoundSettings) -> SoundSettings) -> Unit,
    onSettle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val latest by rememberUpdatedState(settings)
    val chosen by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val preview by rememberUpdatedState(onPreview)
    val settle by rememberUpdatedState(onSettle)
    // The node under the finger, while one is dragged.
    var held by remember { mutableStateOf<Int?>(null) }

    // The shape the equalizer makes, or would make when switched on. Worked
    // out again only when the filters change.
    val filters = remember(settings) { settings.copy(eqEnabled = true).activeFilters() }
    val curve = remember(filters) { ResponseCurve.curve(filters, CurvePoints) }
    val nodes = nodesOf(settings)
    val nodeDb = remember(filters, nodes) { nodes.map { ResponseCurve.gainDb(filters, it.hz) } }
    val parametric = settings.mode == EqMode.Parametric

    val lit by animateFloatAsState(if (settings.eqEnabled) 1f else 0f, tween(250), label = "curve light")
    val line = lerp(Muted, Color.White, lit)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(PlotHeight + LabelBand)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { touch ->
                        if (latest.mode != EqMode.Parametric) return@detectTapGestures
                        val plot = Plot(size.width.toFloat(), size.height.toFloat(), this)
                        nodeAt(pointsOf(latest, plot), touch, HitRadius.toPx(), chosen)?.let(select)
                    },
                    onDoubleTap = { touch ->
                        val plot = Plot(size.width.toFloat(), size.height.toFloat(), this)
                        val index = nodeAt(pointsOf(latest, plot), touch, HitRadius.toPx(), chosen) ?: return@detectTapGestures
                        preview { moveNode(it, index, null, 0f) }
                        settle()
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = latest
                    val plot = Plot(size.width.toFloat(), size.height.toFloat(), this)
                    val free = start.mode == EqMode.Parametric
                    val index = nodeAt(pointsOf(start, plot), down.position, HitRadius.toPx(), if (free) chosen else null)
                        ?: return@awaitEachGesture
                    // Only a drag that starts on a node is taken; anything
                    // else scrolls the page.
                    val moving = if (free) {
                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    } else {
                        awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    } ?: return@awaitEachGesture
                    val node = nodesOf(start)[index]
                    var lastGain = node.gainDb
                    held = index
                    if (free) select(index)

                    fun follow(position: Offset) {
                        val gain = cleanGain(node.gainDb - (position.y - down.position.y) / plot.pxPerDb)
                        // A light tick on crossing 0 dB.
                        if ((lastGain != 0f && gain == 0f) || lastGain * gain < 0f) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        lastGain = gain
                        val hz = if (free) plot.hz(plot.x(node.hz) + position.x - down.position.x).roundToInt().toFloat() else null
                        preview { moveNode(it, index, hz, gain) }
                    }

                    follow(moving.position)
                    drag(moving.id) { change ->
                        follow(change.position)
                        change.consume()
                    }
                    held = null
                    settle()
                }
            },
    ) {
        val plot = with(density) { Plot(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), this) }
        val points = nodes.mapIndexed { i, node -> Offset(plot.x(node.hz), plot.y(nodeDb[i])) }
        val active = held ?: if (parametric) selected else null

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Equalizer curve" },
        ) {
            grid(plot)
            curve(plot, curve, line, lit)
            points.forEachIndexed { i, point ->
                node(point, line, lit, big = i == active)
            }
            labels(plot, measurer, if (parametric) EdgeLabelsHz else GraphicBands)
            held?.let { i ->
                val node = nodes.getOrNull(i) ?: return@let
                val text = if (parametric) "${readHz(node.hz)}   ${readDb(node.gainDb)}" else readDb(node.gainDb)
                reading(measurer, text, points[i])
            }
        }

        // One target per node for screen readers, with actions in place of
        // dragging.
        val target = 32.dp
        nodes.forEachIndexed { i, node ->
            Box(
                Modifier
                    .offset {
                        val half = target.toPx() / 2
                        IntOffset((points[i].x - half).roundToInt(), (points[i].y - half).roundToInt())
                    }
                    .size(target)
                    .semantics {
                        contentDescription = describeNode(node.hz, node.gainDb)
                        if (parametric) this.selected = i == selected
                        customActions = buildList {
                            if (parametric) add(CustomAccessibilityAction("Choose") { select(i); true })
                            add(CustomAccessibilityAction("Raise 1 dB") { preview { moveNode(it, i, null, node.gainDb + 1f) }; settle(); true })
                            add(CustomAccessibilityAction("Lower 1 dB") { preview { moveNode(it, i, null, node.gainDb - 1f) }; settle(); true })
                            add(CustomAccessibilityAction("Reset to 0 dB") { preview { moveNode(it, i, null, 0f) }; settle(); true })
                        }
                    },
            )
        }
    }
}

private fun pointsOf(settings: SoundSettings, plot: Plot): List<Offset> {
    val filters = settings.copy(eqEnabled = true).activeFilters()
    return nodesOf(settings).map { Offset(plot.x(it.hz), plot.y(ResponseCurve.gainDb(filters, it.hz))) }
}

// Faint lines at 100 Hz, 1 kHz and 10 kHz, and a clearer one at 0 dB.
private fun DrawScope.grid(plot: Plot) {
    val hair = 1f
    GridHz.forEach { hz ->
        val x = plot.x(hz)
        drawLine(Color.White.copy(alpha = 0.06f), Offset(x, plot.top), Offset(x, plot.bottom), hair)
    }
    val zero = plot.y(0f)
    drawLine(Color.White.copy(alpha = 0.14f), Offset(plot.left, zero), Offset(plot.right, zero), hair)
}

private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.STROKE
    strokeCap = android.graphics.Paint.Cap.ROUND
    strokeJoin = android.graphics.Paint.Join.ROUND
}

private fun DrawScope.curve(plot: Plot, gains: List<Float>, color: Color, lit: Float) {
    if (gains.isEmpty()) return
    val path = Path()
    val step = (plot.right - plot.left) / (gains.size - 1)
    gains.forEachIndexed { i, db ->
        val x = plot.left + step * i
        val y = plot.y(db)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    // The glass tint under the line.
    val fill = Path().apply {
        addPath(path)
        lineTo(plot.right, plot.bottom)
        lineTo(plot.left, plot.bottom)
        close()
    }
    drawPath(
        fill,
        Brush.verticalGradient(
            listOf(color.copy(alpha = 0.05f + 0.09f * lit), color.copy(alpha = 0.01f)),
            startY = plot.top,
            endY = plot.bottom,
        ),
    )
    val stroke = 2.dp.toPx()
    // The glow, only while the equalizer is on: the same three soft passes
    // the progress line uses.
    if (lit > 0f) {
        drawIntoCanvas { canvas ->
            for (spread in floatArrayOf(2.5f, 1f, 0.3f)) {
                glowPaint.color = Color.White.copy(alpha = 0.4f * lit / 3f).toArgb()
                glowPaint.strokeWidth = stroke
                glowPaint.maskFilter = BlurMaskFilter(12f * spread, BlurMaskFilter.Blur.NORMAL)
                canvas.nativeCanvas.drawPath(path.asAndroidPath(), glowPaint)
            }
        }
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.node(at: Offset, color: Color, lit: Float, big: Boolean) {
    val radius = (if (big) ActiveRadius else NodeRadius).toPx()
    if (big && lit > 0f) drawCircle(Color.White.copy(alpha = 0.18f * lit), radius * 2f, at)
    // A dark edge so a node stands out against the line through it.
    drawCircle(Color.Black.copy(alpha = 0.45f), radius + 1.5f, at)
    drawCircle(color, radius, at)
}

private fun DrawScope.labels(plot: Plot, measurer: androidx.compose.ui.text.TextMeasurer, frequencies: List<Float>) {
    val style = OctoType.caption.copy(fontSize = 10.sp, color = OctoColors.TextMuted, fontFeatureSettings = "tnum")
    val y = plot.bottom + PadUpDown.toPx() + 4.dp.toPx()
    frequencies.forEach { hz ->
        val text = measurer.measure(shortHz(hz), style)
        val x = (plot.x(hz) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
        drawText(text, topLeft = Offset(x, y))
    }
}

// The value of the node being dragged, in a small dark pill above it, or
// below it near the top.
private fun DrawScope.reading(measurer: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset) {
    val style = OctoType.caption.copy(fontSize = 11.sp, color = Color.White, fontFeatureSettings = "tnum")
    val measured = measurer.measure(text, style)
    val padX = 8.dp.toPx()
    val padY = 3.dp.toPx()
    val width = measured.size.width + padX * 2
    val height = measured.size.height + padY * 2
    val gap = 16.dp.toPx()
    val above = at.y - gap - height
    val top = if (above >= 0f) above else at.y + gap
    val left = (at.x - width / 2).coerceIn(0f, size.width - width)
    drawRoundRect(Color.Black.copy(alpha = 0.72f), Offset(left, top), Size(width, height), CornerRadius(height / 2))
    drawText(measured, topLeft = Offset(left + padX, top + padY))
}
