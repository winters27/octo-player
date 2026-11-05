package app.winters.octo.desktop.pages

import app.winters.octo.design.WholeTxt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.winters.octo.design.CardEdge
import app.winters.octo.design.scrollbar
import app.winters.octo.design.topFade
import app.winters.octo.design.CardFill
import app.winters.octo.design.Corner
import app.winters.octo.design.crispPainter
import app.winters.octo.design.exactPainter
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FocusRing
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.RowHeight
import app.winters.octo.design.SeparatorColor
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.SliderLook
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.design.FitOrElse
import app.winters.octo.desktop.ui.ChoiceButton
import app.winters.octo.desktop.ui.PageTitle

// The parts the Settings and Sound pages are made of: the list of sections
// beside the page, one section at a time, its settings in cards, and one
// kind of row for every setting.

// One section of a page: its name and icon, which the list beside the page
// shows, a line about it, and something for the title's line while it is
// shown (the equalizer's switch). A brand's own mark keeps its own colours.
class PageSection(
    val key: String,
    val name: String,
    val icon: ImageVector? = null,
    val detail: String? = null,
    val brand: Boolean = false,
    val trailing: (@Composable () -> Unit)? = null,
    // What waits in the section, which puts a small dot by its name.
    val mark: String? = null,
    val content: @Composable ColumnScope.() -> Unit,
)

// How far a row's words sit in from its card's edge.
internal val RowInset = Space.Xl

// The section each page last showed, by page, for the rest of the session.
// Settings keeps its own page in the app's SettingsUi instead.
private val shownSections = mutableStateMapOf<String, String>()

// Shows a page's section by its key, as a click in the list would: for a
// link straight to it, and for the tests' pictures, on Family and Import.
fun showSection(page: String, key: String) {
    shownSections[page] = key
}

// Which section to show: the one last shown on this page while it is still
// there, else the first.
internal fun shownSection(keys: List<String>, remembered: String?): Int =
    keys.indexOf(remembered).takeIf { it >= 0 } ?: 0

// A page of settings: its title, the list of its sections on the left, and
// the section picked, alone, in a column beside it. The highlighted name in
// the list is the section's heading: the column starts with the section's
// cards, its switch sits at the end of the title's line and its line about
// it goes under the cards. On a narrow window the list becomes a button
// over the section, naming it and opening the others.
// `detail` is a quiet word after the title (the output Sound is for).
// With `onPick`, the caller keeps the section picked and gives it as
// `shown`; without it the page remembers it by its title.
@Composable
internal fun SectionedPage(
    app: AppState,
    visit: Visit,
    title: String,
    sections: List<PageSection>,
    detail: String? = null,
    shown: String? = null,
    onPick: ((String) -> Unit)? = null,
) {
    val index = shownSection(sections.map { it.key }, if (onPick != null) shown else shownSections[title])
    val section = sections.getOrNull(index) ?: return
    fun pick(at: Int) {
        val key = sections[at].key
        if (onPick != null) onPick(key) else shownSections[title] = key
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val withList = maxWidth >= PageSide * 2 + SettingsSize.Nav + Space.Wide + SettingsSize.ColumnMin
        // With the list, the title, the list and the column are one group
        // centred in the page; without it, the column starts at the margin.
        val side = if (withList) groupSide(maxWidth) else PageSide
        Column(Modifier.fillMaxSize()) {
            PageTitle(
                title,
                Modifier.padding(start = side, end = if (withList) side else PageSide, top = Space.Xxl),
                detail = detail,
                bottom = Space.Xs,
                trailing = section.trailing,
            )
            if (withList) {
                Row(Modifier.fillMaxSize()) {
                    SectionList(sections, index, ::pick, Modifier.padding(start = side - Space.M))
                    SectionColumn(section, Modifier.weight(1f), start = Space.Wide - Space.M, end = side - Space.M)
                }
            } else {
                Box(Modifier.padding(start = PageSide, end = PageSide, top = Space.S)) {
                    ChoiceButton(sections.indices.toList(), index, { sections[it].name }, ::pick, title = title, icon = section.icon.takeIf { !section.brand })
                }
                SectionColumn(section, Modifier.weight(1f), start = PageSide - Space.M, end = PageSide - Space.M)
            }
        }
    }
}

// How far in from each side the list and the column start, so the two sit
// together in the middle of a page this wide. The words of the list and of
// the rows are both a little in from their edges, so the group is measured
// by its words: that inset is taken back on each side.
internal fun groupSide(width: Dp): Dp =
    maxOf(PageSide, (width - SettingsSize.Nav - Space.Wide - SettingsSize.Column) / 2 + Space.M)

// The page's sections, each with its icon, the one shown on the darker pill.
@Composable
private fun SectionList(sections: List<PageSection>, current: Int, onPick: (Int) -> Unit, modifier: Modifier) {
    Column(modifier.padding(top = Space.Xl).width(SettingsSize.Nav), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
        sections.forEachIndexed { index, section ->
            SectionPick(section, index == current, { onPick(index) }, Modifier.fillMaxWidth())
        }
    }
}

// The accent fill behind the chosen row of a choice, drawn only when it is
// the chosen one.
@Composable
internal fun ChosenFill(chosen: Boolean, modifier: Modifier = Modifier, shape: Shape = Corner.ControlShape) {
    if (chosen) Box(modifier.background(OctoColors.AccentSelected, shape))
}

// The words of a row in a choice: a heavier weight for the chosen one.
internal val ChosenWords = DesktopType.body.copy(fontWeight = FontWeight.Medium)

// One section to pick: its icon and name, the chosen one on the accent
// fill with a check at its end.
@Composable
internal fun SectionPick(section: PageSection, chosen: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(RowHeight.Nav)
            .hoverLift(Corner.ControlShape, lifted = false)
            .clickable(role = Role.Tab, onClick = onPick)
            .semantics { selected = chosen },
        contentAlignment = Alignment.CenterStart,
    ) {
        ChosenFill(chosen, Modifier.matchParentSize())
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            section.icon?.let { SectionIcon(it, section.brand, IconSize.Toolbar, if (chosen) OctoColors.TextPrimary else OctoColors.TextSecondary) }
            Txt(section.name, if (chosen) ChosenWords else DesktopType.body, if (chosen) OctoColors.TextPrimary else OctoColors.TextSecondary)
            section.mark?.let { what ->
                Box(Modifier.size(FrameSize.NavMark).background(OctoColors.Accent, CircleShape).semantics { contentDescription = what })
            }
            if (chosen) {
                Spacer(Modifier.weight(1f))
                Glyph(OctoIcons.Check, size = IconSize.Toolbar, tint = OctoColors.TextPrimary)
            }
        }
    }
}

// A section's icon: tinted like the words beside it and on whole pixels,
// or a brand's mark exactly as drawn, in its own colors.
@Composable
private fun SectionIcon(icon: ImageVector, brand: Boolean, size: Dp, tint: Color) {
    Image(
        if (brand) exactPainter(icon) else crispPainter(icon),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = if (brand) null else ColorFilter.tint(tint),
    )
}

// The section shown, in a column no wider than reads well, scrolling on
// its own, with room at the foot so its last card clears the floating
// player. Each section starts at its top.
@Composable
private fun SectionColumn(section: PageSection, modifier: Modifier, start: Dp, end: Dp) {
    val bottom = LocalBottomRoom.current + Space.Section
    key(section.key) {
        Box(modifier.fillMaxSize().topFade(Space.Xl)) {
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().scrollbar(scroll, LocalBottomRoom.current).verticalScroll(scroll).padding(start = start, end = end, top = Space.Xl, bottom = bottom),
            ) {
                Column(Modifier.widthIn(max = SettingsSize.Column).fillMaxWidth()) { Section(section) }
            }
        }
    }
}

// A section: its cards, and its line about it under them.
@Composable
private fun ColumnScope.Section(section: PageSection) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Xl)) {
        section.content(this)
    }
    section.detail?.let {
        Txt(it, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(start = RowInset, end = RowInset, top = Space.L), maxLines = 3)
    }
}

// A group of rows, in a card, with its name above when it has one (and
// `action`, a quiet word for the whole group, at the end of that line), and
// a line on what the group is under it when it has one.
@Composable
internal fun Group(name: String? = null, footer: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (name != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = if (action != null) Space.None else Space.S), verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    name.uppercase(),
                    DesktopType.label,
                    OctoColors.TextMuted,
                    Modifier.weight(1f).padding(start = RowInset).semantics {
                        heading()
                        contentDescription = name
                    },
                )
                action?.invoke()
            }
        }
        Rows(content)
        if (footer != null) Txt(footer, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(start = RowInset, end = RowInset, top = Space.S), maxLines = 3)
    }
}

// A card: a faint panel with a hairline edge, holding whatever is put in it.
// A card with nothing in it draws nothing.
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().cardSurface(), content = content)
}

private fun Modifier.cardSurface(): Modifier = this
    .drawBehind {
        if (size.height <= 0f) return@drawBehind
        val radius = CornerRadius(Corner.Panel.toPx())
        val edge = FrameSize.Hairline.toPx()
        drawRoundRect(CardFill, cornerRadius = radius)
        drawRoundRect(CardEdge, topLeft = Offset(edge / 2, edge / 2), size = Size(size.width - edge, size.height - edge), cornerRadius = radius, style = Stroke(edge))
    }
    .clip(Corner.PanelShape)

// Rows one under another in a card, with a hairline between each. A row
// that draws nothing takes no place and gets no line; with no rows at all
// there is no card.
@Composable
internal fun Rows(content: @Composable () -> Unit) {
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    SubcomposeLayout(Modifier.fillMaxWidth().cardSurface()) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val rows = subcompose("rows", content).map { it.measure(loose) }.filter { it.height > 0 }
        val lines = subcompose("lines") {
            repeat((rows.size - 1).coerceAtLeast(0)) {
                Box(Modifier.padding(horizontal = RowInset).fillMaxWidth().height(onePixel).background(SeparatorColor))
            }
        }.map { it.measure(loose) }
        layout(constraints.maxWidth, rows.sumOf { it.height } + lines.sumOf { it.height }) {
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
}

// Every setting's row: its name, a short line when the name needs one, and
// its control on the right.
@Composable
internal fun SettingRow(title: String, caption: String?, modifier: Modifier = Modifier, dim: Boolean = false, control: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .padding(horizontal = RowInset, vertical = Space.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Xxl),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, DesktopType.body, if (dim) OctoColors.TextMuted else OctoColors.TextPrimary)
            if (caption != null) Txt(caption, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(top = Space.Xxs), maxLines = 3)
        }
        control()
    }
}

// A setting that is on or off. The whole row flips it.
@Composable
internal fun SwitchRow(title: String, caption: String?, on: Boolean, change: (Boolean) -> Unit) {
    SettingRow(
        title,
        caption,
        Modifier
            .hoverLift()
            .toggleable(value = on, interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.Switch, onValueChange = change),
    ) {
        // The row is the one stop and the one switch a screen reader hears;
        // the drawn switch inside only shows it.
        OctoSwitch(on, change, Modifier.focusProperties { canFocus = false }.semantics { hideFromAccessibility() })
    }
}

// A setting with a few choices, the chosen one on the darker pill. When
// the pills would take more than their share of the row and crowd its
// words, the choices are one button that opens them instead.
@Composable
internal fun <T> ChoiceRow(title: String, caption: String?, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth * ChoiceShare
        SettingRow(title, caption) {
            FitOrElse(
                { GlazeSegments(options, selected, label, onSelect) },
                { ChoiceButton(options, selected, label, onSelect, title = title) },
                Modifier.widthIn(max = room),
            )
        }
    }
}

// The most of a row its choices may take beside its words.
private const val ChoiceShare = 0.6f

// A value along a line of a fixed width, with what it reads right beside
// it. `live` sends the value while dragging; `onRelease` hears when a
// change is done.
@Composable
internal fun SliderRow(
    title: String,
    caption: String?,
    reading: String,
    fraction: Float,
    onChange: (Float) -> Unit,
    live: Boolean = true,
    onRelease: () -> Unit = {},
    wheelStep: Float? = null,
) {
    SettingRow(title, caption) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
            LineSlider(
                fraction = { fraction },
                onSeek = onChange,
                modifier = Modifier.width(SettingsSize.Slider),
                live = live,
                onRelease = onRelease,
                wheelStep = wheelStep,
                look = SliderLook.Jewel,
                label = title,
                reading = { reading },
            )
            // Its end lines up with the switches' and actions' edge.
            Txt(reading, DesktopType.meta.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary, Modifier.width(SettingsSize.Reading), align = TextAlign.End)
        }
    }
}

// A fact, with its value on the right.
@Composable
internal fun InfoRow(title: String, caption: String?, value: String) {
    SettingRow(title, caption) {
        Txt(value, DesktopType.body, OctoColors.TextSecondary, Modifier.padding(end = RowValueEnd))
    }
}

// The room after a value at a row's end, so its words end where a text
// button's words do (the button's own padding, less the offset RowAction
// gives it).
internal val RowValueEnd = Space.Xs

// Something to do, with its action in words on the right.
@Composable
internal fun ActionRow(title: String, caption: String?, action: String, onClick: () -> Unit, enabled: Boolean = true) {
    SettingRow(title, caption) {
        RowAction(action, onClick, enabled = enabled)
    }
}

// An action in words at a row's end. Its words line up with the switches'
// edge; the lift under the pointer reaches a little past it.
@Composable
internal fun RowAction(text: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    TextAction(text, onClick, Modifier.offset(x = Space.M), enabled = enabled, icon = icon)
}
