package app.winters.octo.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.roundToInt

// A page's title, always whole: never cut short with an ellipsis and
// never broken inside a word. A long title wraps between its words onto
// as many lines as it needs. A name joined with underscores, as files and
// folders often are, may wrap after them as after a hyphen. A single word
// still too wide for the line on its own is set smaller, just enough to
// fit, rather than split.
@Composable
fun WholeTxt(text: String, style: TextStyle, color: Color = OctoColors.TextPrimary, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val shown = remember(text) { breakableText(text) }
    // The words as last laid out, for drawing. Only drawing reads it, so
    // a new layout redraws the words without composing them again.
    val drawn = remember { mutableStateOf<TextLayoutResult?>(null) }
    val policy = remember(shown, style, measurer) { WholeTextPolicy(measurer, shown, style) { drawn.value = it } }
    Layout(
        modifier = modifier
            .semantics {
                if (isHeading(style)) heading()
                this.text = AnnotatedString(text)
            }
            .drawBehind { drawn.value?.let { drawText(it, color) } },
        measurePolicy = policy,
    )
}

private class WholeTextPolicy(
    private val measurer: TextMeasurer,
    private val text: String,
    private val style: TextStyle,
    private val onLaidOut: (TextLayoutResult) -> Unit,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val laid = wholeLayout(measurer, text, style, constraints.maxWidth)
        onLaidOut(laid)
        val width = laid.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = laid.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val lines = mapOf<AlignmentLine, Int>(FirstBaseline to laid.firstBaseline.roundToInt(), LastBaseline to laid.lastBaseline.roundToInt())
        return layout(width, height, lines) {}
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = oneLineWidth(measurer, text, style)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = widestWord(measurer, text, style)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        wholeLayout(measurer, text, style, width).size.height

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        wholeLayout(measurer, text, style, width).size.height
}

// The title laid out in `width`: wrapped between words, and set smaller
// when its widest word would not fit a line by itself. The type never
// shrinks below half its size; a word wider than that still wraps.
internal fun wholeLayout(measurer: TextMeasurer, text: String, style: TextStyle, width: Int): TextLayoutResult {
    var shown = style
    if (width != Constraints.Infinity && width > 0) {
        // Type does not scale quite evenly, so it is measured again after
        // each step, and a few steps always fit.
        for (step in 1..ShrinkSteps) {
            val scale = fitScale(widestWord(measurer, text, shown), width)
            if (scale >= 1f) break
            val least = style.fontSize * LeastShare
            val smaller = shown.fontSize * scale
            shown = shown.copy(fontSize = if (smaller.value < least.value) least else smaller)
            if (smaller.value < least.value) break
        }
    }
    return measurer.measure(text, shown, constraints = Constraints(maxWidth = width))
}

// How much to scale type down so a word `widest` wide fits a line `width`
// wide: not at all when it fits, else a touch under the exact share, so
// rounding never leaves it a pixel over.
fun fitScale(widest: Int, width: Int): Float =
    if (widest <= width || widest <= 0 || width <= 0) 1f else width.toFloat() / widest * 0.98f

private const val ShrinkSteps = 3
private const val LeastShare = 0.5f

// The title with a place a line may end after each underscore that joins
// two words: an invisible zero-width space, which the reader never sees
// and a screen reader never hears (it reads the title as given).
fun breakableText(text: String): String = text.replace(Underscores, "_" + ZeroWidthSpace)

private val ZeroWidthSpace = Char(0x200B)

private val Underscores = Regex("""_(?=[^\s_])""")

// The title on one line, as wide as it is.
internal fun oneLineWidth(measurer: TextMeasurer, text: String, style: TextStyle): Int =
    measurer.measure(text, style, softWrap = false, maxLines = 1).size.width

// The widest stretch of the title that may not be broken: a word, or a
// part of one a line may end after (a hyphen's), or a single character
// in a script written without spaces.
internal fun widestWord(measurer: TextMeasurer, text: String, style: TextStyle): Int {
    if (text.isEmpty()) return 0
    val line = measurer.measure(text, style, softWrap = false, maxLines = 1)
    return wordSpans(text).maxOfOrNull { span ->
        abs(line.getHorizontalPosition(span.last + 1, true) - line.getHorizontalPosition(span.first, true)).roundToInt()
    } ?: 0
}

// Where a line may break in `text`: each stretch between two places it
// may, without the spaces at its end.
fun wordSpans(text: String): List<IntRange> {
    val iterator = BreakIterator.getLineInstance().apply { setText(text) }
    // The JDK's rules miss the zero-width space the text drawing breaks
    // after, so those places are added by hand.
    val breaks = sortedSetOf<Int>()
    var at = iterator.first()
    while (at != BreakIterator.DONE) {
        breaks += at
        at = iterator.next()
    }
    text.forEachIndexed { index, c -> if (c == ZeroWidthSpace) breaks += index + 1 }
    val spans = mutableListOf<IntRange>()
    breaks.zipWithNext { start, end ->
        var last = end
        while (last > start && (text[last - 1].isWhitespace() || text[last - 1] == ZeroWidthSpace)) last--
        if (last > start) spans += start until last
    }
    return spans
}

// A title and its buttons, such as a page's sort or Play and Shuffle: on
// one line, the buttons at its end, only when the whole title fits beside
// them on one line; otherwise the buttons go on a line of their own under
// it, so the title keeps the whole width and is never squeezed. `heading`
// draws the title (and any line under it); `title` and `style` are its
// words, which decide whether the two share the line.
@Composable
fun TitleAndActions(
    title: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    gap: Dp = Space.L,
    heading: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    Layout(
        contents = listOf(heading, actions),
        modifier = modifier,
    ) { (headings, buttons), constraints ->
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val buttonsPlaced = buttons.map { it.measure(Constraints(maxWidth = width)) }
        val buttonsWidth = buttonsPlaced.maxOfOrNull { it.width } ?: 0
        val buttonsHeight = buttonsPlaced.maxOfOrNull { it.height } ?: 0
        if (actionsBeside(oneLineWidth(measurer, title, style), buttonsWidth, space, width)) {
            val headingPlaced = headings.map { it.measure(Constraints(maxWidth = width - buttonsWidth - space)) }
            val headingHeight = headingPlaced.maxOfOrNull { it.height } ?: 0
            val height = maxOf(headingHeight, buttonsHeight)
            layout(width, height) {
                headingPlaced.forEach { it.place(0, (height - headingHeight) / 2) }
                buttonsPlaced.forEach { it.place(width - it.width, (height - it.height) / 2) }
            }
        } else {
            val headingPlaced = headings.map { it.measure(Constraints(maxWidth = width)) }
            val headingHeight = headingPlaced.maxOfOrNull { it.height } ?: 0
            layout(width, headingHeight + space + buttonsHeight) {
                headingPlaced.forEach { it.place(0, 0) }
                buttonsPlaced.forEach { it.place(0, headingHeight + space) }
            }
        }
    }
}

// Whether a title `titleWidth` wide on one line fits beside buttons
// `actionsWidth` wide, `gap` apart, on a line `width` wide.
fun actionsBeside(titleWidth: Int, actionsWidth: Int, gap: Int, width: Int): Boolean =
    width == Constraints.Infinity || titleWidth + gap + actionsWidth <= width
