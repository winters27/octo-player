package app.winters.octo.desktop.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import app.winters.octo.design.CardFit
import app.winters.octo.design.Corner
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors

// Settings' rows and cards found by id. Each registers where it sits in its
// page's scroll content once laid out, so a row asked for (by a link or the
// settings search) is brought into view and flashed. A row that a setting
// keeps hidden hands its id to the row that shows it; a row asked for that is
// never laid out falls back to its card.

// What a row is called: the title as shown, and a stable form of it. For a
// title with parts filled in (a name, a count) the stable form keeps the
// fixed words with the parts as "..." or a word in angle brackets; for a row
// whose title is data (a server, a station) it is that kind of row in
// brackets. Every other row's stable form is its title.
data class RowTitleInfo(val title: String, val stable: String)

// Every setting row and every card title holding a switch carries its title.
val RowTitle = SemanticsPropertyKey<RowTitleInfo>("RowTitle")

// A card's title and the icon its title line draws, which the tests read to
// tell which cards share an icon.
data class CardIconInfo(val title: String, val icon: ImageVector)

val CardIcon = SemanticsPropertyKey<CardIconInfo>("CardIcon")

// The row or card lit because it was asked for, by the ask's nonce; absent
// while nothing is lit there.
val RowFlash = SemanticsPropertyKey<Long>("RowFlash")

// The id a row or card is found by on its page.
val RowId = SemanticsPropertyKey<String>("RowId")

// How long a row asked for stays lit, by default.
const val ROW_FLASH_MS = 1_600L

// How long a row asked for stays lit on this window; the tests set it.
val LocalRowFlashMs = staticCompositionLocalOf { ROW_FLASH_MS }

// Words made into an id: lower case, each run of other characters one dash.
fun settingsSlug(words: String): String = words.lowercase().replace(NotIdWord, "-").trim('-')

private val NotIdWord = Regex("[^a-z0-9]+")

// A row's id on its page: its card's id, a dot, and the row's own.
fun settingsRowId(card: String?, row: String): String = if (card == null) row else "$card.$row"

// The card a row's id belongs to, or null for a card's own id.
fun settingsCardOf(id: String): String? = id.substringBefore('.', "").ifEmpty { null }

// The id lit, and the nonce of the ask that lit it, so the same row asked
// for again lights again.
data class SettingsFlash(val id: String, val nonce: Long)

// The rows and cards of the Settings page on screen, by id.
@Stable
class SettingsAnchors {
    private val placed = mutableStateMapOf<String, LayoutCoordinates>()
    private val keyboard = HashMap<String, FocusRequester>()
    private val standIns = mutableStateMapOf<String, String>()

    // The page's scroll content, which every place is measured in.
    internal var content: LayoutCoordinates? = null

    // Whether the page has been laid out once.
    var laidOut by mutableStateOf(false)
        internal set

    // What is lit now.
    var flash by mutableStateOf<SettingsFlash?>(null)
        internal set

    // The ids laid out now.
    val ids: Set<String> get() = placed.keys

    internal fun place(id: String, at: LayoutCoordinates, focus: FocusRequester) {
        if (placed[id] !== at) placed[id] = at
        keyboard[id] = focus
    }

    internal fun drop(id: String, at: LayoutCoordinates) {
        if (placed[id] === at) {
            placed.remove(id)
            keyboard.remove(id)
        }
    }

    // Takes the keyboard to `id`'s first control, once laid out. A row with
    // no control leaves the keyboard where it is.
    fun focus(id: String) {
        if (placed[id]?.isAttached != true) return
        val focus = keyboard[id] ?: return
        // A requester whose node has just left throws; the keyboard stays.
        runCatching { focus.requestFocus() }
    }

    internal fun standIn(hidden: String, by: String) {
        if (standIns[hidden] != by) standIns[hidden] = by
    }

    internal fun dropStandIn(hidden: String, by: String) {
        if (standIns[hidden] == by) standIns.remove(hidden)
    }

    // The row standing in for a hidden one: the row that shows it.
    fun standInFor(hidden: String): String? = standIns[hidden]

    // The id that answers an ask for `id` now: the row itself once laid out,
    // or the laid-out row a hidden one names, else null.
    fun found(id: String): String? {
        if (id in placed) return id
        var by = standIns[id]
        // A stand-in may itself be hidden behind another row.
        repeat(STAND_IN_DEPTH) {
            val next = by ?: return null
            if (next in placed) return next
            by = standIns[next]
        }
        return null
    }

    // The card of `id`, once laid out.
    fun cardFor(id: String): String? = settingsCardOf(id)?.takeIf { it in placed }

    // Where `id` sits in the page's scroll content, once laid out.
    fun placeOf(id: String): Rect? {
        val inside = content?.takeIf { it.isAttached } ?: return null
        val at = placed[id]?.takeIf { it.isAttached } ?: return null
        return inside.localBoundingBoxOf(at, clipBounds = false)
    }
}

private const val STAND_IN_DEPTH = 4

// The anchors of the Settings page being drawn; null on other pages, where
// rows register nothing.
val LocalSettingsAnchors = compositionLocalOf<SettingsAnchors?> { null }

// The id of the card a row is in.
val LocalSettingsCard = compositionLocalOf<String?> { null }

// Marks a row: its title for the tree, its place on the page under its id,
// and its flash while asked for. The id is the card's and the row's own,
// `id`, or else the stable title made into one.
@Composable
internal fun Modifier.settingsRow(title: String, stable: String? = null, id: String? = null): Modifier {
    val key = settingsRowId(LocalSettingsCard.current, id ?: settingsSlug(stable ?: title))
    return semantics { this[RowTitle] = RowTitleInfo(title, stable ?: title) }.settingsPlace(key, inset = true)
}

// Registers `id` where this sits, and lights it while it is asked for.
// `inset` lights a row as a chosen row is lit, inside its card's edge;
// without `light` the light is drawn elsewhere (a card lights its glaze,
// not its title).
@Composable
internal fun Modifier.settingsPlace(id: String, inset: Boolean = false, light: Boolean = true): Modifier {
    val anchors = LocalSettingsAnchors.current ?: return this
    val mine = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val focus = remember { FocusRequester() }
    DisposableEffect(anchors, id) {
        onDispose {
            mine[0]?.let { anchors.drop(id, it) }
            mine[0] = null
        }
    }
    val flash = anchors.flash?.takeIf { it.id == id }
    return this
        .semantics {
            this[RowId] = id
            if (flash != null) this[RowFlash] = flash.nonce
        }
        .onGloballyPositioned {
            mine[0] = it
            anchors.place(id, it, focus)
        }
        .focusRequester(focus)
        .then(if (light) Modifier.flashFill(flash, inset) else Modifier)
}

// What is lit under `id` now, if anything.
@Composable
internal fun flashOf(id: String?): SettingsFlash? = LocalSettingsAnchors.current?.flash?.takeIf { id != null && it.id == id }

// A row hidden by a setting: an ask for it lights `by`, the row that shows
// it: a row of the same card by its own id, the card itself by the card's
// id (its title's switch), or any other row by its whole id.
@Composable
internal fun HiddenRow(row: String, by: String) {
    val anchors = LocalSettingsAnchors.current ?: return
    val card = LocalSettingsCard.current
    val hidden = settingsRowId(card, row)
    val shownBy = if ('.' in by || card == null || by == card) by else settingsRowId(card, by)
    DisposableEffect(anchors, hidden, shownBy) {
        anchors.standIn(hidden, shownBy)
        onDispose { anchors.dropStandIn(hidden, shownBy) }
    }
}

// The accent light behind what was asked for: up quickly, held, and let go
// slowly; at once while motion is reduced.
@Composable
internal fun Modifier.flashFill(flash: SettingsFlash?, inset: Boolean): Modifier {
    val glow = remember { Animatable(0f) }
    val still = LocalReduceMotion.current
    LaunchedEffect(flash?.nonce, still) {
        if (flash != null) {
            if (still) glow.snapTo(1f) else {
                glow.snapTo(0f)
                glow.animateTo(1f, tween(FLASH_IN_MS))
            }
        } else if (still) {
            glow.snapTo(0f)
        } else {
            glow.animateTo(0f, tween(FLASH_OUT_MS))
        }
    }
    val accent = OctoColors.Accent
    return drawBehind {
        val lit = glow.value
        if (lit <= 0f) return@drawBehind
        val pad = if (inset) CardFit.Fill.toPx() else 0f
        val corner = (if (inset) Corner.Row else Corner.Panel).toPx()
        drawRoundRect(
            accent.copy(alpha = FLASH_ALPHA * lit),
            topLeft = Offset(pad, pad),
            size = Size(size.width - pad * 2, size.height - pad * 2),
            cornerRadius = CornerRadius(corner),
        )
    }
}

private const val FLASH_IN_MS = 180
private const val FLASH_OUT_MS = 700
private const val FLASH_ALPHA = 0.20f
