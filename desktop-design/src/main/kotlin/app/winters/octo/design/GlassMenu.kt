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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

// The film under a menu: darker than a bar's, since text sits on it.
val MenuFilm: Color @Composable @ReadOnlyComposable get() = glassFilm(0.52f)

// How hard a menu frosts what is behind it, as a CSS blur.
const val MenuFrost = 24f

// The floating player: lighter than a menu so the page reads through it as
// frosted glass, dark enough that its words stay clear over bright covers.
val IslandFilm: Color @Composable @ReadOnlyComposable get() = glassFilm(0.26f)
const val IslandFrost = 20f
// How much the colours under it deepen, so a cover's hues show through.
const val IslandSaturation = 1.4f

val MenuShape = RoundedCornerShape(14.dp)

// Where a pop-up of `size` goes in a window of `window`, keeping `margin`
// clear of the edges. At a point (the pointer, for a right click) it opens
// down and to the right of it, flipping up or left where it would not fit.
// Under a control (`anchor`) it opens below it, or above when only that
// fits, lined up with the control's start edge, or its end edge on the far
// half of the window. With neither it sits in the middle, of `within` when
// given (the part of the window it is about), else of the window.
fun placePopupAt(point: IntOffset?, anchor: IntRect?, size: IntSize, window: IntSize, margin: Int, gap: Int = 0, within: IntRect? = null): IntOffset {
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
        within != null -> IntOffset(within.left + (within.width - size.width) / 2, within.top + (within.height - size.height) / 2).let {
            IntOffset(it.x.coerceIn(margin, maxX), it.y.coerceIn(margin, maxY))
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
    // A question about one part of the window (a page): that part is
    // shaded while it is open, and it sits in the middle of it, frosted in
    // the page's own card color so it stands above the shade.
    val over: IntRect? = null,
    // What takes the keyboard first as it opens, rather than its first row.
    val first: FocusRequester? = null,
    // A sign-in in it that is committing, so closing waits for it to end.
    val busy: () -> Boolean = { false },
    // A sign-in open in it, so a link does not replace it.
    open: () -> Boolean = { false },
    // Where the words of a link refused while it is open go.
    noteLink: (String) -> Unit = {},
    // A family link opened it.
    link: Boolean = false,
    // A centered card whose height changes with what it shows keeps its top
    // where its tallest self would start, and grows downward from there.
    val holdTop: Boolean = false,
    // The pop-up it was opened over, kept as it is, out of sight, until
    // this one closes.
    val under: PopupRequest? = null,
    // What takes the keyboard once it closes (the control that opened it),
    // in the pop-up under it or on the page.
    val returnTo: FocusRequester? = null,
) {
    // What a link must not replace counts the pop-up under it too: a sign-in
    // open in either, the refused link's words shown in both, and a family
    // link's sheet still open under it.
    val open: () -> Boolean = if (under == null) open else ({ open() || under.open() })
    val noteLink: (String) -> Unit = if (under == null) noteLink else ({ words -> noteLink(words); under.noteLink(words) })
    val link: Boolean = link || under?.link == true
}

// The open pop-up and every one kept under it, the open one first.
private fun PopupRequest?.andUnder(): Sequence<PopupRequest> = generateSequence(this) { it.under }

// How dark the shade over the part of the window a question is about.
private const val OVER_SHADE = 0.40f

// What the tests call an open pop-up's card, and the shade under a question.
const val POPUP_TAG = "popup"
const val POPUP_SHADE_TAG = "popup-shade"

// How far the page behind a dialog blurs.
@Composable
private fun ScrimBlur(): HazeBlurStyle {
    val page = OctoColors.Background
    return remember(page) {
        HazeBlurStyle {
            backgroundColor(page)
            blurRadius(18.dp)
            noiseFactor(0f)
            fallbackColorEffect(HazeColorEffect.tint(Color.Black.copy(alpha = 0.35f)))
        }
    }
}

// How tall a menu may grow before it scrolls.
val PopupMaxHeight = 560.dp

// Shows floating glass pop-ups over the whole window: menus at the pointer,
// small forms, song details. One shows at a time, and a new one replaces
// the old, unless it is kept over it (`keepUnder`, a sheet's certificate
// asking for its password, while that sheet is the one on top): the old one
// then waits as it is, out of sight, and comes back once the new one closes.
@Stable
class PopupHost {
    var request by mutableStateOf<PopupRequest?>(null)
        private set

    // Opens at a point in the window, like where a right click landed.
    // Content that `scrollsItself` keeps a part in place (a search field)
    // while the rest scrolls. Nothing opens over a sign-in that is
    // committing (see busy).
    fun showAt(point: IntOffset, width: Dp? = 260.dp, scrollsItself: Boolean = false, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        if (busy) return
        request = PopupRequest(point, null, width, content, scrollsItself = scrollsItself)
    }

    // Opens under (or over) a control, given its bounds in the window.
    fun showUnder(anchor: IntRect, width: Dp? = 260.dp, scrollsItself: Boolean = false, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        if (busy) return
        request = PopupRequest(null, anchor, width, content, scrollsItself = scrollsItself)
    }

    // Opens in the middle of the window, for a small form, or of `over`
    // with that part shaded, for a question about it. A taller form may ask
    // for more room before it scrolls; `first` takes the keyboard first. A
    // sign-in form says when it is committing (`busy`, then nothing closes
    // it but its own buttons) and when a sign-in is open in it (`open`,
    // then a link must not replace it), where a refused link's words go
    // (`noteLink`), and whether a family link opened it (`link`). A card
    // that changes height as it loads can hold its top (`holdTop`). A question
    // a pop-up asks on its way (`keepUnder`, that pop-up's own request) keeps
    // that pop-up under it rather than replacing it, while it is still the
    // one on top; whatever else is on top by then is replaced. `returnTo`
    // takes the keyboard once it closes.
    fun showCentred(
        width: Dp? = 380.dp,
        maxHeight: Dp = PopupMaxHeight,
        scrim: Boolean = false,
        scrollsItself: Boolean = false,
        over: IntRect? = null,
        first: FocusRequester? = null,
        busy: () -> Boolean = { false },
        open: () -> Boolean = { false },
        noteLink: (String) -> Unit = {},
        link: Boolean = false,
        holdTop: Boolean = false,
        keepUnder: PopupRequest? = null,
        returnTo: FocusRequester? = null,
        content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    ) {
        if (this.busy) return
        val under = keepUnder?.takeIf { it === request }
        request = PopupRequest(null, null, width, content, maxHeight, scrim, scrollsItself, over, first, busy, open, noteLink, link, holdTop, under, returnTo)
    }

    // Closes the open pop-up; the one it was kept over comes back.
    fun close() {
        request = request?.under
    }

    // Closes `shown` and whatever was kept over it, as its own buttons do;
    // the one it was kept over comes back. Once it has gone, nothing.
    fun close(shown: PopupRequest) {
        if (request.andUnder().any { it === shown }) request = shown.under
    }

    // Whether a pop-up open, or one kept under it, holds a sign-in that is
    // committing.
    val busy: Boolean get() = request.andUnder().any { it.busy() }

    // Closes the open pop-up the way Escape and a press outside it do: not
    // while its sign-in is committing. The one it was kept over comes back.
    // False when it stays.
    fun dismiss(): Boolean {
        val top = request ?: return true
        if (top.busy()) return false
        request = top.under
        return true
    }

    // Closes every pop-up open the way Settings and the Mac menu do: not
    // while one holds a sign-in that is committing. False when they stay.
    fun dismissAll(): Boolean {
        if (busy) return false
        request = null
        return true
    }

    val open: Boolean get() = request != null

    // Set by the window: notes what has the keyboard as a pop-up opens, and
    // gives it back once it shuts.
    var saveFocus: (() -> Unit)? = null
    var returnFocus: (() -> Unit)? = null
}

val LocalPopups = staticCompositionLocalOf { PopupHost() }

// The pop-up the content under it is drawn in; none on a page.
val LocalPopupRequest = staticCompositionLocalOf<PopupRequest?> { null }

private const val POPUP_MS = OctoDuration.Hover

// The layer the pop-ups draw in. It covers the window while one is open,
// so a click anywhere off the card closes it, and Escape does too. The card
// frosts whatever is behind it through `backdrop`. The keyboard stays in
// the card while it is open: the arrow keys and Tab walk its rows, Enter
// picks one. Opened from the
// keyboard, its first row has the keyboard at once; once it shuts, what
// had it before gets it back. A pop-up kept under the open one stays drawn
// as it was, out of sight, and the page is shaded once under both.
@Composable
fun PopupLayer(host: PopupHost, backdrop: HazeState) {
    val request = host.request
    // Hands the keyboard back once the pop-up that had it has gone: to the
    // control that opened the last one, else to what had it before the
    // first.
    var held by remember { mutableStateOf<PopupRequest?>(null) }
    var handBack by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        if (request != null && held != null) held = request
        if (request == null && held != null) {
            val back = held?.returnTo
            held = null
            val returned = back != null && runCatching { back.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
            if (handBack && !returned) runCatching { host.returnFocus?.invoke() }
            handBack = false
        }
    }
    if (request == null) return
    val keyboard = LocalFocusVisibility.current.keyboard
    // The lowest first, so the open one draws over the one kept under it.
    val shown = remember(request) { request.andUnder().toList().asReversed() }
    // The page is shaded by the lowest that asks for it, so one kept over a
    // sheet lies on the sheet's own shade, never a second one.
    val shading = shown.firstOrNull { it.scrim }
    shown.forEachIndexed { index, layer ->
        key(layer) {
            PopupCard(host, layer, backdrop, above = shown.getOrNull(index + 1), shade = layer === shading) {
                // Only for the keyboard: after a click the keyboard stays where
                // the click put it, and an old field is never woken.
                if (held == null && keyboard) {
                    handBack = true
                    runCatching { host.saveFocus?.invoke() }
                }
                held = layer
            }
        }
    }
}

// One pop-up of the layer. `above` is the one kept over it, if any: it is
// then out of sight, and nothing in it is read out, takes the keyboard or
// hears the pointer, and once that one closes it comes back as it was, the
// keyboard on what opened that one. `shade` draws the page's scrim under
// it. `opened` hears it open.
@Composable
private fun PopupCard(host: PopupHost, request: PopupRequest, backdrop: HazeState, above: PopupRequest?, shade: Boolean, opened: () -> Unit) {
    val top = above == null
    val raised = CardFill
    val motion = motionScale()
    val grow = remember(request) { Animatable(0f) }
    val showing = remember(request) { Animatable(1f) }
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
    // The pop-up last kept over it, for what to give the keyboard back to.
    val kept = remember(request) { arrayOfNulls<PopupRequest>(1) }
    if (above != null) kept[0] = above
    fun takeKeyboard(rows: Boolean) {
        runCatching { focus.requestFocus() }
        if (rows) runCatching { card.requestFocus(FocusDirection.Enter) }
    }
    LaunchedEffect(request) {
        opened()
        takeKeyboard(rows = false)
        // Opened from the keyboard, its first row takes it once laid out;
        // a card naming what goes first gives it the keyboard however it
        // was opened (its ring still shows only for the keyboard).
        val first = request.first
        if (first != null) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        } else if (keyboard) {
            withFrameNanos { }
            runCatching { card.requestFocus(FocusDirection.Enter) }
        }
        grow.animateTo(1f, octoTween(motion, POPUP_MS))
    }
    LaunchedEffect(top) {
        if (!top) {
            showing.animateTo(0f, octoTween(motion, POPUP_MS))
            return@LaunchedEffect
        }
        val gone = kept[0] ?: return@LaunchedEffect
        kept[0] = null
        // Once it can take the keyboard again.
        withFrameNanos { }
        val back = gone.returnTo
        if (back == null || !runCatching { back.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) takeKeyboard(rows = keyboard)
        showing.animateTo(1f, octoTween(motion, POPUP_MS))
    }
    var origin by remember(request) { mutableStateOf(TransformOrigin(0f, 0f)) }
    if (shade) {
        // The page behind, dimmed and blurred, for a dialog.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = grow.value }
                .hazeBlur(input = HazeInput.Backdrop(backdrop), style = ScrimBlur())
                .background(Color.Black.copy(alpha = 0.55f)),
        )
    }
    request.over?.let { area ->
        // The part the question is about, shaded under it.
        Layout(
            content = { Box(Modifier.graphicsLayer { alpha = grow.value }.background(Color.Black.copy(alpha = OVER_SHADE)).testTag(POPUP_SHADE_TAG)) },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val shade = measurables.first().measure(Constraints.fixed(area.width.coerceAtLeast(0), area.height.coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) { shade.place(area.left, area.top) }
        }
    }
    Layout(
        content = {
            // The menu plate's halo and inner hairline keep its words
            // legible over a busy page, on the same floating glass.
            // A question over a shaded page is frosted from the page as it is
            // unshaded under a card's own film, so it stands as a solid card
            // of the page's card color above the shade.
            FloatingGlaze(
                backdrop = backdrop,
                shape = MenuShape,
                film = if (request.over != null) raised else MenuFilm,
                frost = MenuFrost,
                halo = true,
                modifier = Modifier
                    .graphicsLayer {
                        val shown = grow.value * showing.value
                        // With motion reduced it only fades.
                        val scale = motion.scale(0.94f + 0.06f * shown)
                        scaleX = scale
                        scaleY = scale
                        alpha = shown
                        transformOrigin = origin
                    }
                    .then(if (request.width != null) Modifier.width(request.width) else Modifier.widthIn(min = 200.dp, max = 420.dp))
                    // Clicks on the card stay on the card.
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                    .testTag(POPUP_TAG),
            ) {
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .focusRequester(card)
                        // The keyboard stays among the rows while the card is
                        // open, and never comes in while one is kept over it.
                        .focusProperties {
                            onExit = { if (host.request === request) cancelFocusChange() }
                            onEnter = { if (host.request !== request) cancelFocusChange() }
                        }
                        .focusGroup()
                        .heightIn(max = request.maxHeight)
                        .then(if (request.scrollsItself) Modifier else Modifier.scrollbar(scroll).verticalScroll(scroll))
                        .padding(vertical = 6.dp),
                ) {
                    CompositionLocalProvider(LocalPopupRequest provides request) { request.content(this) { host.close(request) } }
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            // Out of sight under one kept over it, it is not read out.
            .then(if (top) Modifier else Modifier.clearAndSetSemantics { })
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
            .focusProperties { canFocus = top }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> host.dismiss()
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                    else -> return@onKeyEvent false
                }
                true
            }
            // A press off the open card closes it; one kept under it hears none.
            .pointerInput(request, top) {
                if (!top) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = true)
                    host.dismiss()
                }
            },
    ) { measurables, constraints ->
        val window = IntSize(constraints.maxWidth, constraints.maxHeight)
        val margin = 10.dp.roundToPx()
        val card = measurables.first().measure(
            Constraints(maxWidth = (window.width - margin * 2).coerceAtLeast(0), maxHeight = (window.height - margin * 2).coerceAtLeast(0)),
        )
        val size = IntSize(card.width, card.height)
        val placed = placePopupAt(request.point, request.anchor, size, window, margin, gap = 6.dp.roundToPx(), within = request.over)
        val spot = if (request.holdTop && request.point == null && request.anchor == null && request.over == null) {
            // Where the card's tallest self would start (its full room, or
            // the window less its margins), so a taller state grows down.
            val tallest = minOf(request.maxHeight.roundToPx(), window.height - margin * 2)
            IntOffset(placed.x, ((window.height - tallest) / 2).coerceAtLeast(margin))
        } else {
            placed
        }
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
// an icon in its place (a row of stars); `checked` ticks a switch that is on
// or one of several picked. `selected` marks the one choice in use in a
// menu of choices with the accent pill and a heavier word; it is ticked too.
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
    selected: Boolean = false,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val press = OctoColors.MenuPress
    SelectedFill(selected) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .then(if (selected) Modifier.background(OctoColors.AccentSelected, MenuRowShape) else Modifier)
                .menuRowPress(MenuRowShape, press) { if ((hovered || pressed || focused) && enabled) 1f else 0f }
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
                    if (checked || selected) this.selected = true
                    if (more) stateDescription = "Opens more"
                }
                .height(MenuRowHeight.Pointer)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leading != null) leading() else if (icon != null) Glyph(icon, size = IconSize.Table, tint = if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
            Txt(
                text,
                if (selected) OctoType.bodySmall.copy(fontWeight = FontWeight.Medium) else OctoType.bodySmall,
                when {
                    !enabled -> OctoColors.TextMuted
                    destructive -> OctoColors.Destructive
                    else -> OctoColors.TextPrimary
                },
                Modifier.weight(1f),
            )
            if (detail != null) Txt(detail, OctoType.caption, OctoColors.TextMuted)
            if (checked || selected) Glyph(OctoIcons.Check, size = IconSize.Table, tint = OctoColors.TextPrimary)
            if (more) Glyph(OctoIcons.Chevron, size = IconSize.Table, tint = OctoColors.TextMuted)
        }
    }
}

// The words of a selected row's contents, in the colors solved for its
// fill, keeping the Row's scope; any other row's as they are.
@Composable
fun RowScope.SelectedRowWords(selected: Boolean, content: @Composable RowScope.() -> Unit) {
    val row = this
    SelectedFill(selected) { row.content() }
}

// A heading inside a menu or pop-up.
@Composable
fun MenuTitle(text: String, modifier: Modifier = Modifier) {
    Txt(text, OctoType.label, OctoColors.TextSecondary, modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
}

// A heading with a quiet line of its own after it on the same baseline
// (what the pop-up is about, or what to do in it), rather than under it.
@Composable
fun MenuTitle(text: String, detail: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(text, OctoType.label, OctoColors.TextSecondary, Modifier.alignByBaseline().semantics { heading() })
        Txt(detail, OctoType.caption, OctoColors.TextMuted, Modifier.weight(1f, fill = false).alignByBaseline(), maxLines = 2)
    }
}

// The top of a menu's inner page: its name after a back arrow ("‹ Rating"),
// going back to the page it opened from, then a hairline.
@Composable
fun MenuBack(title: String, onBack: () -> Unit) {
    MenuRow(title, onBack, OctoIcons.Back)
    MenuSeparator()
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
