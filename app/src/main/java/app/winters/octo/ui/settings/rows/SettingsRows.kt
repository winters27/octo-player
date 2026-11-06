package app.winters.octo.ui.settings.rows

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.SliderLook
import app.winters.octo.design.raisedPanel
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.choiceAnchor
import app.winters.octo.ui.settings.SettingEntry
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val GroupShape = RoundedCornerShape(20.dp)

// The row a search result pointed at, lit briefly once its page opens.
// Shown once, then done, so coming back to the page does not light it again.
class SettingsHighlight(val id: String?, val onShown: () -> Unit)

val LocalSettingsHighlight = compositionLocalOf { SettingsHighlight(null) {} }

// How long the pointed-at row stays lit, after the page has settled.
private const val SETTLE_MS = 350L
private const val HOLD_MS = 900L

// Rows in one raised panel, a hairline between each. A row that draws
// nothing takes no place and gets no line. The lines start under the text,
// so rows with icons pass a deeper inset. A title sits above the panel as
// a quiet capitalised label, as the desktop names its groups, led by the
// group's `icon` when it has one (a `brand` mark keeps its own colours).
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    separatorInset: Dp = 16.dp,
    @DrawableRes icon: Int? = null,
    brand: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        title?.let {
            Row(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                icon?.let { MarkOrIcon(it, brand, OctoColors.TextMuted, 15.dp) }
                Text(
                    it.uppercase(),
                    style = OctoType.label.copy(letterSpacing = 0.6.sp),
                    color = OctoColors.TextMuted,
                    modifier = Modifier.semantics { contentDescription = it },
                )
            }
        }
        SubcomposeLayout(Modifier.fillMaxWidth().raisedPanel(GroupShape)) { constraints ->
            val loose = constraints.copy(minHeight = 0)
            val rows = subcompose("rows", content).map { it.measure(loose) }.filter { it.height > 0 }
            val lines = subcompose("lines") {
                repeat((rows.size - 1).coerceAtLeast(0)) { Hairline(separatorInset) }
            }.map { it.measure(loose) }
            val height = rows.sumOf { it.height } + lines.sumOf { it.height }
            layout(constraints.maxWidth, height) {
                var y = 0
                rows.forEachIndexed { index, row ->
                    row.place(0, y)
                    y += row.height
                    lines.getOrNull(index)?.let { line ->
                        line.place(0, y)
                        y += line.height
                    }
                }
            }
        }
        footer?.let {
            Text(it, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

// One physical pixel, starting under the rows' text.
@Composable
private fun Hairline(inset: Dp) {
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(onePixel)
            .background(OctoColors.TextPrimary.copy(alpha = 0.10f)),
    )
}

// What every row shares: its height and padding, a tap when it has one,
// and the brief light when search pointed at it.
@Composable
internal fun RowFrame(
    entry: SettingEntry?,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val highlight = LocalSettingsHighlight.current
    val glow = remember { Animatable(0f) }
    val requester = remember { BringIntoViewRequester() }
    if (entry != null && highlight.id == entry.id) {
        LaunchedEffect(highlight) {
            highlight.onShown()
            delay(SETTLE_MS)
            requester.bringIntoView()
            glow.animateTo(1f, tween(250))
            delay(HOLD_MS)
            glow.animateTo(0f, tween(700))
        }
    }
    val glowInk = OctoColors.Accent
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .drawBehind { if (glow.value > 0f) drawRect(glowInk.copy(alpha = 0.14f * glow.value)) }
            .then(modifier)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// A title with an optional line under it, taking the row's spare width.
@Composable
private fun RowScope.Label(title: String, below: String?, enabled: Boolean = true, belowColor: Color = OctoColors.TextMuted, titleColor: Color = OctoColors.TextPrimary) {
    Column(Modifier.weight(1f)) {
        Text(title, style = OctoType.body, color = if (enabled) titleColor else OctoColors.TextMuted)
        below?.let { Text(it, style = OctoType.caption, color = belowColor, modifier = Modifier.padding(top = 2.dp)) }
    }
}

// A setting that is on or off. The whole row flips it.
@Composable
fun SwitchRow(
    entry: SettingEntry?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    helper: String? = null,
    enabled: Boolean = true,
    title: String = entry?.title.orEmpty(),
) {
    RowFrame(entry, Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Label(title, helper, enabled)
        OctoSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.clearAndSetSemantics { })
    }
}

// A setting with a few answers: its title on the left, the current one on
// the right, as the desktop reads them, and a chevron. Tapping it pops the
// answers up beside the row. `answers` names every answer, so the value
// keeps the width of the widest and the row never changes height between
// them.
// `helpers` names every answer's own line when the line under the title is the chosen answer's.
@Composable
fun ChoiceRow(
    entry: SettingEntry?,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    title: String = entry?.title.orEmpty(),
    helper: String? = null,
    answers: List<String> = emptyList(),
    helpers: List<String> = emptyList(),
) {
    val sheet = LocalChoiceSheet.current
    RowFrame(entry, Modifier.choiceAnchor(sheet).clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        TitleValue(title, helper, value, enabled, answers = answers, belows = if (helper != null) helpers else emptyList())
        Icon(
            painterResource(OctoIcons.Chevron),
            contentDescription = null,
            tint = OctoColors.TextMuted,
            modifier = Modifier.size(18.dp).alpha(if (enabled) 1f else 0.5f),
        )
    }
}

// A row's title, with the line under it, beside its current value. The
// value is quieter, kept to the end of the line, at most two lines, and
// takes only the room its words need (the widest of `answers` when given),
// up to its share of the row (0.8 to the title's 1), or up to all the room
// the title leaves when the title fits on one line beside it. The title
// takes the rest, so a short value never leaves it wrapping beside an
// empty gap.
@Composable
private fun RowScope.TitleValue(
    title: String,
    below: String?,
    value: String,
    enabled: Boolean = true,
    titleColor: Color = OctoColors.TextPrimary,
    answers: List<String> = emptyList(),
    belows: List<String> = emptyList(),
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    Layout(
        content = {
            Column {
                Text(title, style = OctoType.body, color = if (enabled) titleColor else OctoColors.TextMuted)
                below?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 2.dp)) }
            }
            Text(
                value,
                style = OctoType.bodySmall,
                color = if (enabled) OctoColors.TextSecondary else OctoColors.TextMuted,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.weight(1f),
    ) { (label, reading), constraints ->
        val room = (constraints.maxWidth - TitleValueGap.roundToPx()).coerceAtLeast(0)
        val widest = answers.maxOfOrNull { measurer.measure(it, OctoType.bodySmall, maxLines = 1, softWrap = false).size.width } ?: 0
        val need = maxOf(reading.maxIntrinsicWidth(Constraints.Infinity), widest)
        // Its share of the row, or more when the title, and the widest of the answers' lines under it, still
        // fit on one line beside it.
        val labelWide = maxOf(
            label.maxIntrinsicWidth(Constraints.Infinity),
            belows.maxOfOrNull { measurer.measure(it, OctoType.caption, maxLines = 1, softWrap = false).size.width } ?: 0,
        )
        val most = maxOf((room * ValueShare).roundToInt(), room - labelWide)
        val wide = minOf(need, most)
        // A row tall anyway beside a long line under its title may take a narrower value, its widest
        // answers wrapping to two lines at a space: as wide as its 2nd widest answer, then its 3rd, and
        // so on, taking the first step that takes a line off the title's side with the row no taller.
        // Only when the value shown is one of the answers, so it never depends on the answer shown:
        // nothing moves between answers, and no wide empty gap sits beside a short value.
        fun oneLine(text: String) = measurer.measure(text, OctoType.bodySmall, maxLines = 1, softWrap = false).size.width
        fun twoLines(text: String): Int {
            val words = text.split(" ")
            if (words.size < 2) return oneLine(text)
            return (1 until words.size).minOf { cut ->
                maxOf(oneLine(words.subList(0, cut).joinToString(" ")), oneLine(words.subList(cut, words.size).joinToString(" ")))
            }
        }
        fun labelHeight(w: Int): Int {
            val lines = room - w
            val lineShown = below?.let { measurer.measure(it, OctoType.caption, constraints = Constraints(maxWidth = lines)).size.height } ?: 0
            val lineTallest = belows.maxOfOrNull { measurer.measure(it, OctoType.caption, constraints = Constraints(maxWidth = lines)).size.height } ?: 0
            return label.minIntrinsicHeight(lines) + (lineTallest - lineShown).coerceAtLeast(0)
        }
        fun valueHeight(w: Int): Int = answers.maxOf {
            measurer.measure(it, OctoType.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = w)).size.height
        }
        val ones = answers.associateWith(::oneLine)
        val width = if (value !in answers || ones.size < 2) {
            wide
        } else {
            val byWidth = ones.entries.sortedByDescending { it.value }
            narrowValue(wide, byWidth.map { it.value }, byWidth.map { twoLines(it.key) }, ::labelHeight, ::valueHeight)
        }
        val shown = reading.measure(Constraints.fixedWidth(width))
        val words = label.measure(Constraints(maxWidth = room - width))
        // As tall as the tallest answer at this width, so the row keeps one height for all of them.
        val tallest = answers.maxOfOrNull {
            measurer.measure(it, OctoType.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = width)).size.height
        } ?: 0
        // Room for the tallest of `belows` under the title, the title held at the top of it, so the row
        // keeps one height and its title one place whichever answer's line shows.
        val lineWidth = room - width
        val lineNow = below?.let { measurer.measure(it, OctoType.caption, constraints = Constraints(maxWidth = lineWidth)).size.height } ?: 0
        val lineMost = belows.maxOfOrNull { measurer.measure(it, OctoType.caption, constraints = Constraints(maxWidth = lineWidth)).size.height } ?: 0
        val labelHeight = words.height + (lineMost - lineNow).coerceAtLeast(0)
        val height = maxOf(labelHeight, shown.height, tallest, constraints.minHeight)
        layout(constraints.maxWidth, height) {
            words.placeRelative(0, (height - labelHeight) / 2)
            shown.placeRelative(constraints.maxWidth - shown.width, (height - shown.height) / 2)
        }
    }
}

// The gap between a row's title and its value, as the row spaces its parts,
// and the most of the row a value may take.
private val TitleValueGap = 12.dp
private const val ValueShare = 0.8f / 1.8f

// The value width a row settles on when its value may narrow: `ones` and
// `twos` are its answers' one-line and two-line widths, widest first, and
// `label` and `value` give the title side's and the value's height at a
// value width. Each step is as wide as the next answer down, the wider
// ones wrapping to two lines; the first step that takes a line off the
// title's side with the row no taller wins, else `wide`.
internal fun narrowValue(wide: Int, ones: List<Int>, twos: List<Int>, label: (Int) -> Int, value: (Int) -> Int): Int {
    val labelAtWide = label(wide)
    val rowAtWide = maxOf(labelAtWide, value(wide))
    return (1 until ones.size).asSequence()
        .map { k -> maxOf(ones[k], twos.take(k).max()) }
        .filter { it < wide }
        .distinct()
        .firstOrNull { step ->
            val labelAtStep = label(step)
            labelAtStep < labelAtWide && maxOf(labelAtStep, value(step)) <= rowAtWide
        } ?: wide
}

// An icon tinted to its place, or a brand's mark in its own colours.
@Composable
internal fun MarkOrIcon(@DrawableRes icon: Int, brand: Boolean, tint: Color, size: Dp) {
    if (brand) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(size))
    } else {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

// A setting along a range: its title and value, and the line to drag.
@Composable
fun SliderRow(
    entry: SettingEntry?,
    value: String,
    fraction: () -> Float,
    onSeek: (Float) -> Unit,
    title: String = entry?.title.orEmpty(),
) {
    RowFrame(entry) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(value, style = OctoType.bodySmall.copy(fontFeatureSettings = "tnum"), color = OctoColors.TextSecondary)
            }
            LineSlider(
                fraction = fraction,
                onSeek = onSeek,
                live = true,
                look = SliderLook.Jewel,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }
}

// Something to do, or another page to open: its title, and a chevron,
// a word on the right, or a spinner while it works.
@Composable
fun ActionRow(
    entry: SettingEntry?,
    onClick: () -> Unit,
    helper: String? = null,
    trailing: String? = null,
    chevron: Boolean = trailing == null,
    busy: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    title: String = entry?.title.orEmpty(),
) {
    RowFrame(entry, Modifier.clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)) {
        val titleColor = if (destructive) OctoColors.Error else OctoColors.TextPrimary
        if (trailing != null && !busy) {
            TitleValue(title, helper, trailing, enabled, titleColor)
        } else {
            Label(title, helper, enabled, titleColor = titleColor)
        }
        if (busy) CircularProgressIndicator(color = OctoColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        if (chevron && !busy) {
            Icon(
                painterResource(OctoIcons.Chevron),
                contentDescription = null,
                tint = OctoColors.TextMuted,
                modifier = Modifier.size(20.dp).alpha(if (enabled) 1f else 0.5f),
            )
        }
    }
}

// A fact to read: a title and its value.
@Composable
fun InfoRow(entry: SettingEntry?, value: String, title: String = entry?.title.orEmpty(), helper: String? = null) {
    RowFrame(entry) {
        TitleValue(title, helper, value)
    }
}

// A few words inside a group: what a part does, or what went wrong.
@Composable
fun NoteRow(text: String, entry: SettingEntry? = null, color: Color = OctoColors.TextMuted) {
    RowFrame(entry, minHeight = 0.dp) {
        Text(text, style = OctoType.caption, color = color, modifier = Modifier.weight(1f))
    }
}

// A way into a page: its icon, title, and one line on how it is set now.
@Composable
fun CategoryRow(@DrawableRes icon: Int, title: String, summary: String, mark: String? = null, onClick: () -> Unit) {
    RowFrame(null, Modifier.clickable(role = Role.Button, onClick = onClick)) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = OctoType.body, color = OctoColors.TextPrimary)
            if (summary.isNotEmpty()) {
                Text(summary, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // A small dot when something waits on the page, `mark` saying what.
        if (mark != null) {
            Box(Modifier.size(8.dp).background(OctoColors.Accent, CircleShape).semantics { contentDescription = mark })
        }
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}

// A row's icon on a small tinted tile, so a list of pages reads at a glance.
@Composable
fun IconTile(@DrawableRes icon: Int) {
    Box(
        Modifier
            .size(IconTileSize)
            .background(OctoColors.Accent.copy(alpha = 0.14f), IconTileShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(20.dp))
    }
}

private val IconTileSize = 34.dp
private val IconTileShape = RoundedCornerShape(10.dp)

// The inset for a group of rows with icons: the lines start under the titles.
val IconRowInset = 66.dp
