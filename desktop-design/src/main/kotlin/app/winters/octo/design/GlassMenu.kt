package app.winters.octo.design

import androidx.compose.foundation.background
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.HazeInput
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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

// The floating player: lighter than a menu so the page reads through it as
// frosted glass, dark enough that its words stay clear over bright covers.
val IslandFilm = Color.Black.copy(alpha = 0.26f)
const val IslandFrost = 20f
// How much the colours under it deepen, so a cover's hues show through.
const val IslandSaturation = 1.4f

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
    // How tall it may grow before it scrolls; never past the window.
    val maxHeight: Dp = PopupMaxHeight,
    // A dialog: the window behind dims and blurs, and clicks there do not
    // reach the page.
    val scrim: Boolean = false,
    // The content scrolls itself (to keep a footer in place), so the card
    // only gives it the room there is.
    val scrollsItself: Boolean = false,
)

// How far the page behind a dialog blurs.
private val ScrimBlur = HazeBlurStyle {
    backgroundColor(OctoColors.Background)
    blurRadius(18.dp)
    noiseFactor(0f)
    fallbackColorEffect(HazeColorEffect.tint(Color.Black.copy(alpha = 0.35f)))
}

// How tall a menu may grow before it scrolls.
val PopupMaxHeight = 560.dp

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

    // Opens in the middle of the window, for a small form. A taller form
    // may ask for more room before it scrolls.
    fun showCentred(
        width: Dp? = 380.dp,
        maxHeight: Dp = PopupMaxHeight,
        scrim: Boolean = false,
        scrollsItself: Boolean = false,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    ) {
        request = PopupRequest(null, null, width, content, maxHeight, scrim, scrollsItself)
    }

    fun close() {
        request = null
    }

    val open: Boolean get() = request != null

    // Set by the window: notes what has the keyboard as a pop-up opens, and
    // gives it back once it shuts.
    var saveFocus: (() -> Unit)? = null
    var returnFocus: (() -> Unit)? = null
}

val LocalPopups = staticCompositionLocalOf { PopupHost() }

private const val POPUP_MS = OctoDuration.Hover

// The layer the pop-ups draw in. It covers the window while one is open,
// so a click anywhere off the card closes it, and Escape does too. The card
// frosts whatever is behind it through `backdrop`. The keyboard stays in
// the card while it is open: the arrow keys and Tab walk its rows, Enter
// picks one. Opened from the
// keyboard, its first row has the keyboard at once; once it shuts, what
// had it before gets it back.
@Composable
fun PopupLayer(host: PopupHost, backdrop: HazeState) {
    val request = host.request
    // Hands the keyboard back once the pop-up that had it has gone.
    var held by remember { mutableStateOf<PopupRequest?>(null) }
    var handBack by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        if (request == null && held != null) {
            held = null
            if (handBack) runCatching { host.returnFocus?.invoke() }
            handBack = false
        }
    }
    if (request == null) return
    val motion = motionScale()
    val grow = remember(request) { Animatable(0f) }
    val focus = remember(request) { FocusRequester() }
    val card = remember(request) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalFocusVisibility.current.keyboard
    val scope = rememberCoroutineScope()
    // Whether the card has had the keyboard, so a row that goes away (a
    // menu turning to its next page) hands it back to the card; a few
    // tries in a row at most, so nothing can bounce for ever.
    var had by remember(request) { mutableStateOf(false) }
    var tries by remember(request) { mutableIntStateOf(0) }
    fun takeKeyboard(rows: Boolean) {
        runCatching { focus.requestFocus() }
        if (rows) runCatching { card.requestFocus(FocusDirection.Enter) }
    }
    LaunchedEffect(request) {
        // Only for the keyboard: after a click the keyboard stays where the
        // click put it, and an old field is never woken.
        if (held == null && keyboard) {
            handBack = true
            runCatching { host.saveFocus?.invoke() }
        }
        held = request
        takeKeyboard(rows = false)
        // Opened from the keyboard, its first row takes it once laid out.
        if (keyboard) {
            withFrameNanos { }
            runCatching { card.requestFocus(FocusDirection.Enter) }
        }
        grow.animateTo(1f, octoTween(motion, POPUP_MS))
    }
    var origin by remember(request) { mutableStateOf(TransformOrigin(0f, 0f)) }
    if (request.scrim) {
        // The page behind, dimmed and blurred, for a dialog.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = grow.value }
                .hazeBlur(input = HazeInput.Backdrop(backdrop), style = ScrimBlur)
                .background(Color.Black.copy(alpha = 0.55f)),
        )
    }
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
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .focusRequester(card)
                        // The keyboard stays among the rows while the card is open.
                        .focusProperties { onExit = { if (host.request === request) cancelFocusChange() } }
                        .focusGroup()
                        .heightIn(max = request.maxHeight)
                        .then(if (request.scrollsItself) Modifier else Modifier.scrollbar(scroll).verticalScroll(scroll))
                        .padding(vertical = 6.dp),
                ) {
                    request.content(this) { host.close() }
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                when {
                    it.hasFocus && !it.isFocused -> {
                        had = true
                        tries = 0
                    }
                    it.hasFocus -> had = true
                    had && host.request === request && tries < 3 -> {
                        tries++
                        // On the next frame: a row that went away is still
                        // being taken down now, and moving the keyboard in the
                        // middle of that breaks the pop-up.
                        scope.launch {
                            withFrameNanos { }
                            takeKeyboard(rows = true)
                        }
                    }
                }
            }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> host.close()
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                    else -> return@onKeyEvent false
                }
                true
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
// icon stays white like every other. `leading` draws something wider than
// an icon in its place (a row of stars); `checked` ticks the choice in use.
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
    leading: (@Composable () -> Unit)? = null,
    checked: Boolean = false,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .menuRowPress(MenuRowShape) { if ((hovered || pressed || focused) && enabled) 1f else 0f }
            .hoverable(interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = FocusRing(MenuRowShape),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                if (checked) selected = true
                if (more) stateDescription = "Opens more"
            }
            .height(MenuRowHeight.Pointer)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) leading() else if (icon != null) Glyph(icon, size = 16.dp, tint = if (enabled) Color.White else OctoColors.TextMuted)
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
        if (checked) Glyph(OctoIcons.Check, size = 16.dp, tint = OctoColors.TextPrimary)
        if (more) Glyph(OctoIcons.Chevron, size = 16.dp, tint = OctoColors.TextMuted)
    }
}

// A heading inside a menu or pop-up.
@Composable
fun MenuTitle(text: String, modifier: Modifier = Modifier) {
    Txt(text, OctoType.label, OctoColors.TextSecondary, modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
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
