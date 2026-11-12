package app.winters.octo.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.winters.octo.design.GlazedIconButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoInk
import app.winters.octo.design.OctoType
import app.winters.octo.player.capMiddle
import app.winters.octo.player.rememberCapHeightPx
import kotlin.math.max
import kotlin.math.roundToInt

// The title of a page with a header: the big page title.
val HeaderTitle = OctoType.display

// How big the header's round buttons are: the back button and a page's
// actions, like Play and Shuffle.
val HeaderButtonSize = 40.dp

// The room between the back button and the title.
private val Gap = 10.dp

// Half the height of the title's line, which holds a 48dp touch target
// centered on the title's capitals.
private val LineHalf = 24.dp

// Where the title's line puts the title, in pixels: after the back button
// (`back` wide, 0 without one) and its gap, at the title's own width. The
// title is always one whole line: never shortened, cut or wrapped.
internal fun titlePlace(back: Int, title: Int, gap: Int): Pair<Int, Int> = (if (back > 0) back + gap else 0) to title

// How much the title's type shrinks so the whole title fits on its one line
// after the back button, in pixels: not at all when it fits at full size,
// and just enough when it is wider than the room.
internal fun titleScale(title: Int, room: Int): Float = if (title <= 0 || room <= 0 || title <= room) 1f else room.toFloat() / title

// Whether a page's count ("120 songs") fits whole at the end of the title's
// line, in pixels: where the title ends, the count's width, the room
// before it and the line's width. One that does not fit is left off the
// line rather than cut.
internal fun countFits(titleEnd: Int, count: Int, gap: Int, width: Int): Boolean = count > 0 && titleEnd + gap + count <= width

// The top of a page that is not a tab, on two lines at most. The first
// holds the back button and the big page title alone, the button on the
// middle of the title's capitals. The second, the control line, holds
// everything else (see HeaderControlLine): the page's own controls first
// (`lead`, or one fixed `control`), its main choice as `segments`, then
// Play and Shuffle (`tail`, or fixed `actions`) and More with `more` at the
// end. On a narrow phone the lesser controls move into More. A page with
// none of those has the first line only. A `count` ("120 songs") sits muted
// at the end of the title's line when it fits there whole; when it does not,
// its `shortCount` ("120 songs" for "120 songs · 1.2 GB") stands in where
// that fits, so a count whose width changes as it goes never blinks out.
// A page without a way back (a tab) keeps `trailing` at the end of the
// title's line, for a quiet page action like Edit Home.
@Composable
fun PageHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    count: String? = null,
    shortCount: String? = null,
    control: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    lead: List<LineItem> = emptyList(),
    segments: LineSegments? = null,
    tail: List<LineItem> = emptyList(),
    more: List<MoreItem> = emptyList(),
) {
    val leading = fixedLead(control) + lead
    val ending = tail + fixedTail(actions)
    val hasLine = leading.isNotEmpty() || segments != null || ending.isNotEmpty() || more.isNotEmpty()
    Column(modifier.fillMaxWidth()) {
        TitleLine(
            title,
            onBack,
            count,
            shortCount.takeIf { count != null },
            trailing,
            Modifier.padding(start = if (onBack != null) 12.dp else 20.dp, end = 16.dp, top = 8.dp, bottom = if (hasLine) 4.dp else 12.dp),
        )
        if (hasLine) {
            HeaderControlLine(Modifier.padding(start = 12.dp, end = 16.dp, bottom = 12.dp), lead = leading, segments = segments, tail = ending, more = more)
        }
    }
}

// The room between the title and its count.
private val CountGap = 10.dp

// The title's line: the back button and the title on one line, the button
// on the middle of the title's capitals. A title wider than the room after
// the button is set just small enough to fit whole.
@Composable
private fun TitleLine(title: String, onBack: (() -> Unit)?, count: String?, shortCount: String?, trailing: (@Composable () -> Unit)?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val room = constraints.maxWidth - if (onBack != null) with(density) { (HeaderButtonSize + Gap).roundToPx() } else 0
        val style = remember(title, room, measurer) {
            val full = measurer.measure(title, HeaderTitle, maxLines = 1, softWrap = false).size.width
            // A hair under the exact fit, so rounding never pushes the last letter past the edge.
            val scale = titleScale(full, room).let { if (it < 1f) it * 0.99f else it }
            if (scale >= 1f) {
                HeaderTitle
            } else {
                HeaderTitle.copy(
                    fontSize = HeaderTitle.fontSize * scale,
                    lineHeight = if (HeaderTitle.lineHeight.isSpecified) HeaderTitle.lineHeight * scale else HeaderTitle.lineHeight,
                )
            }
        }
        TitleLineLayout(title, style, onBack, count, shortCount, trailing)
    }
}

@Composable
private fun TitleLineLayout(title: String, style: TextStyle, onBack: (() -> Unit)?, count: String?, shortCount: String?, trailing: (@Composable () -> Unit)?) {
    val capHeight = rememberCapHeightPx(style)
    Layout(
        content = {
            if (onBack != null) HeaderButton(OctoIcons.Back, "Back", onBack)
            Text(title, style = style, color = OctoColors.TextPrimary, maxLines = 1, softWrap = false)
            if (count != null) Text(count, style = OctoType.bodySmall, color = OctoColors.TextMuted, maxLines = 1, softWrap = false)
            if (shortCount != null) Text(shortCount, style = OctoType.bodySmall, color = OctoColors.TextMuted, maxLines = 1, softWrap = false)
            if (trailing != null) Box { trailing() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        var next = 0
        val back = if (onBack != null) measurables[next++].measure(Constraints()) else null
        val name = measurables[next++].measure(Constraints())
        val whole = if (count != null) measurables[next++].measure(Constraints()) else null
        val short = if (shortCount != null) measurables[next++].measure(Constraints()) else null
        val end = if (trailing != null) measurables[next].measure(Constraints()) else null
        val (x, _) = titlePlace(back?.width ?: 0, name.width, Gap.roundToPx())
        // What follows the title goes only where it fits whole: the page
        // action at the far end, then the count after the title.
        val endRoom = end?.let { it.width + Gap.roundToPx() } ?: 0
        val shownEnd = end?.takeIf { x + name.width + endRoom <= width }
        val countRoom = width - (if (shownEnd != null) endRoom else 0)
        val shownCount = listOfNotNull(whole, short).firstOrNull { countFits(x + name.width, it.width, CountGap.roundToPx(), countRoom) }
        // The line is as tall on every page: the title where a 48dp target
        // centered on its capitals fits.
        val half = LineHalf.roundToPx()
        val capitals = capMiddle(name[FirstBaseline].toFloat(), capHeight)
        val lift = max(0, (half - capitals).roundToInt())
        val middle = capitals + lift
        val height = max(name.height + lift, (middle + half).roundToInt())
        layout(width, height) {
            back?.place(0, (middle - back.height / 2f).roundToInt())
            name.place(x, lift)
            // The count sits on the title's baseline.
            shownCount?.place(x + name.width + CountGap.roundToPx(), lift + name[FirstBaseline] - shownCount[FirstBaseline])
            shownEnd?.place(width - shownEnd.width, (middle - shownEnd.height / 2f).roundToInt())
        }
    }
}

// One of the header's round glass buttons: the back button, or a page's
// action like Play or Shuffle. One not `enabled` is dimmed and does nothing.
// While `loading` (Play gathering a folder's songs) a small spinner turns
// over it and it does nothing.
@Composable
fun HeaderButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        GlazedIconButton(
            backdrop = LocalHaze.current,
            icon = ImageVector.vectorResource(icon),
            contentDescription = description,
            onClick = { if (enabled && !loading) onClick() },
            modifier = Modifier.alpha(if (enabled && !loading) 1f else OctoInk.DisabledAlpha),
            size = HeaderButtonSize,
        )
        if (loading) CircularProgressIndicator(color = OctoColors.TextPrimary, strokeWidth = 2.dp, modifier = Modifier.size(HeaderButtonSize - 12.dp))
    }
}

// Play and Shuffle as the header's two round buttons, for a page's actions.
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    HeaderButton(OctoIcons.Play, "Play", onPlay, enabled = enabled, loading = loading)
    HeaderButton(OctoIcons.Shuffle, "Shuffle", onShuffle, enabled = enabled && !loading)
}

// Soft 16dp fades at the ends of a control line's filters that scroll
// sideways, each only while there is more that way, so a chip running
// under the actions melts away instead of being cut.
fun Modifier.filterFades(state: LazyListState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = 16.dp.toPx()
        if (state.canScrollBackward) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = edge), size = Size(edge, size.height), blendMode = BlendMode.DstIn)
        }
        if (state.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - edge, endX = size.width),
                topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
