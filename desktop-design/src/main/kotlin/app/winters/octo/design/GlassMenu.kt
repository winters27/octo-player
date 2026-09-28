package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

// The film under a menu: darker than a bar's, since text sits on it.
val MenuFilm = Color.Black.copy(alpha = 0.52f)

// How hard a menu frosts what is behind it, as a CSS blur.
const val MenuFrost = 24f

val MenuShape = RoundedCornerShape(14.dp)

// Where a pop-up of `size` goes in a window of `window`, keeping `margin`
// clear of the edges. At a point (the pointer, for a right click) it opens
// down and to the right of it, flipping up or left where it would not fit.
// Under a control (`anchor`) it opens below it, or above when only that
// fits, lined up with the control's start edge, or its end edge on the far
// half of the window. With neither it sits in the middle.
fun placePopupAt(point: IntOffset?, anchor: IntRect?, size: IntSize, window: IntSize, margin: Int, gap: Int = 0): IntOffset {
    val maxX = maxOf(margin, window.width - margin - size.width)
    val maxY = maxOf(margin, window.height - margin - size.height)
    return when {
        anchor != null -> {
            val fromEnd = anchor.left + anchor.right >= window.width
            val x = if (fromEnd) anchor.right - size.width else anchor.left
            val below = anchor.bottom + gap
            val above = anchor.top - gap - size.height
            val y = if (below + size.height <= window.height - margin || above < margin) below else above
            IntOffset(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
        }
        point != null -> {
            val x = if (point.x + size.width <= window.width - margin) point.x else point.x - size.width
            val y = if (point.y + size.height <= window.height - margin) point.y else point.y - size.height
            IntOffset(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
        }
        else -> IntOffset((window.width - size.width) / 2, (window.height - size.height) / 2).let {
            IntOffset(it.x.coerceIn(margin, maxX), it.y.coerceIn(margin, maxY))
        }
    }
}

// One pop-up on screen: where it opens and what it holds.
class PopupRequest(
    val point: IntOffset?,
    val anchor: IntRect?,
    val width: Dp?,
    val content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
)

// Shows floating glass pop-ups over the whole window: menus at the pointer,
// small forms, song details. One at a time; a new one replaces the old.
@Stable
class PopupHost {
    var request by mutableStateOf<PopupRequest?>(null)
        private set

    // Opens at a point in the window, like where a right click landed.
    fun showAt(point: IntOffset, width: Dp? = 260.dp, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        request = PopupRequest(point, null, width, content)
    }

    // Opens under (or over) a control, given its bounds in the window.
    fun showUnder(anchor: IntRect, width: Dp? = 260.dp, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        request = PopupRequest(null, anchor, width, content)
    }

    // Opens in the middle of the window, for a small form.
    fun showCentred(width: Dp? = 380.dp, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        request = PopupRequest(null, null, width, content)
    }

    fun close() {
        request = null
    }

    val open: Boolean get() = request != null
}

val LocalPopups = staticCompositionLocalOf { PopupHost() }

private const val POPUP_MS = OctoDuration.Hover

// The layer the pop-ups draw in. It covers the window while one is open,
// so a click anywhere off the card closes it, and Escape does too. The card
// frosts whatever is behind it through `backdrop`.
@Composable
fun PopupLayer(host: PopupHost, backdrop: HazeState) {
    val request = host.request ?: return
    val motion = motionScale()
    val grow = remember(request) { Animatable(0f) }
    val focus = remember(request) { FocusRequester() }
    LaunchedEffect(request) {
        runCatching { focus.requestFocus() }
        grow.animateTo(1f, octoTween(motion, POPUP_MS))
    }
    var origin by remember(request) { mutableStateOf(TransformOrigin(0f, 0f)) }
    Layout(
        content = {
            // The menu plate's halo and inner hairline keep its words
            // legible over a busy page, on the same floating glass.
            FloatingGlaze(
                backdrop = backdrop,
                shape = MenuShape,
                film = MenuFilm,
                frost = MenuFrost,
                halo = true,
                modifier = Modifier
                    .graphicsLayer {
                        val shown = grow.value
                        // With motion reduced it only fades.
                        val scale = motion.scale(0.94f + 0.06f * shown)
                        scaleX = scale
                        scaleY = scale
                        alpha = shown
                        transformOrigin = origin
                    }
                    .then(if (request.width != null) Modifier.width(request.width) else Modifier.widthIn(min = 200.dp, max = 420.dp))
                    // Clicks on the card stay on the card.
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } },
            ) {
                Column(
                    Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    request.content(this) { host.close() }
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    host.close()
                    true
                } else {
                    false
                }
            }
            .pointerInput(request) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = true)
                    host.close()
                }
            },
    ) { measurables, constraints ->
        val window = IntSize(constraints.maxWidth, constraints.maxHeight)
        val margin = 10.dp.roundToPx()
        val card = measurables.first().measure(
            Constraints(maxWidth = (window.width - margin * 2).coerceAtLeast(0), maxHeight = (window.height - margin * 2).coerceAtLeast(0)),
        )
        val size = IntSize(card.width, card.height)
        val spot = placePopupAt(request.point, request.anchor, size, window, margin, gap = 6.dp.roundToPx())
        val point = request.point
        origin = when {
            point != null -> TransformOrigin(if (spot.x < point.x) 1f else 0f, if (spot.y < point.y) 1f else 0f)
            request.anchor != null -> TransformOrigin(0.5f, if (spot.y < request.anchor.top) 1f else 0f)
            else -> TransformOrigin.Center
        }
        layout(window.width, window.height) { card.place(spot.x, spot.y) }
    }
}

// The corners of a menu row: the card's, less its 6 dp gutter.
private val MenuRowShape = RoundedCornerShape(8.dp)

// One line of a menu: an icon, the words, and a chevron when it opens more.
// Under the pointer it sits in a quiet accent-tinted pill, at once. A
// `destructive` one (deleting, removing) says so in soft red words; its
// icon stays white like every other.
@Composable
fun MenuRow(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    more: Boolean = false,
    detail: String? = null,
    destructive: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .menuRowPress(MenuRowShape) { if ((hovered || pressed) && enabled) 1f else 0f }
            .hoverable(interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .height(MenuRowHeight.Pointer)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Glyph(icon, size = 16.dp, tint = if (enabled) Color.White else OctoColors.TextMuted)
        Txt(
            text,
            OctoType.bodySmall,
            when {
                !enabled -> OctoColors.TextMuted
                destructive -> OctoColors.Destructive
                else -> OctoColors.TextPrimary
            },
            Modifier.weight(1f),
        )
        if (detail != null) Txt(detail, OctoType.caption, OctoColors.TextMuted)
        if (more) Glyph(OctoIcons.Chevron, size = 16.dp, tint = OctoColors.TextMuted)
    }
}

// A heading inside a menu or pop-up.
@Composable
fun MenuTitle(text: String, modifier: Modifier = Modifier) {
    Txt(text, OctoType.label, OctoColors.TextSecondary, modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

// The hairline between groups of menu rows.
@Composable
fun MenuSeparator() {
    Separator(Modifier.padding(vertical = 4.dp))
}

// Room at the bottom of a pop-up form.
@Composable
fun PopupGap(height: Dp = 8.dp) {
    Spacer(Modifier.height(height).size(1.dp))
}

// Keeps a pop-up form's content off the card's edges.
@Composable
fun PopupPadding(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

// Lets a click on the card's empty parts pass without closing it.
@Composable
fun PopupBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) { content() }
}
