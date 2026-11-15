package app.winters.octo.desktop.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.CardEdge
import app.winters.octo.design.CardFill
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FitOrElse
import app.winters.octo.design.Focus
import app.winters.octo.design.FocusRing
import app.winters.octo.design.FocusRingColor
import app.winters.octo.design.LocalFocusVisibility
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.LineSlider
import app.winters.octo.design.LocalTabStops
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.RowHeight
import app.winters.octo.design.SeparatorColor
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.SliderLook
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.crispPainter
import app.winters.octo.design.exactPainter
import app.winters.octo.design.glassRim
import app.winters.octo.design.hoverLift
import app.winters.octo.design.mix
import app.winters.octo.design.scrollbar
import app.winters.octo.design.tabStop
import app.winters.octo.design.topFade
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.ChoiceButton
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.PageTitle

// The parts settings are made of: titled glaze cards of rows, one kind of
// row for every setting, and, for the Family and Import pages, a list of
// sections beside the page with the section picked shown alone.

// One section of a sectioned page: its name and icon, which the list beside
// the page shows, a line about it, and something for the title's line while
// it is shown. A brand's own mark keeps its own colors.
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

// The section each sectioned page last showed, by page, for the rest of the
// session. Settings keeps its own page in the app's SettingsUi instead.
private val shownSections = mutableStateMapOf<String, String>()

// Shows a sectioned page's section by its key, as a click in the list would:
// for a link straight to it, and for the tests' pictures, on Family and Import.
fun showSection(page: String, key: String) {
    shownSections[page] = key
}

// Which section to show: the one last shown on this page while it is still
// there, else the first.
internal fun shownSection(keys: List<String>, remembered: String?): Int =
    keys.indexOf(remembered).takeIf { it >= 0 } ?: 0

// A page of sections: its title, the list of its sections on the left, and
// the section picked, alone, in a column beside it. The section's switch
// sits at the end of the title's line and its line about it goes under its
// cards. On a narrow window the list becomes a button over the section,
// naming it and opening the others. `detail` is a quiet word after the title.
@Composable
internal fun SectionedPage(
    app: AppState,
    visit: Visit,
    title: String,
    sections: List<PageSection>,
    detail: String? = null,
) {
    val index = shownSection(sections.map { it.key }, shownSections[title])
    val section = sections.getOrNull(index) ?: return
    fun pick(at: Int) {
        shownSections[title] = sections[at].key
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val withList = maxWidth >= PageSide * 2 + SettingsSize.Nav + Space.Wide + SettingsSize.ColumnMin
        // With the list, the title, the list and the column are one group
        // centered in the page; without it, the column starts at the margin.
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

// The page's sections, each with its icon.
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

// One section to pick, marking a place rather than a choice: its icon and
// name, the one shown lit by an accent-tinted fill with a fine accent edge
// and a brighter icon, never a check.
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
        if (chosen) PlaceFill(Modifier.matchParentSize())
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            section.icon?.let { SectionIcon(it, section.brand, settingsIconSize(), if (chosen) OctoColors.AccentHover else OctoColors.Accent) }
            Txt(section.name, DesktopType.body, if (chosen) OctoColors.TextPrimary else mix(OctoColors.TextSecondary, OctoColors.TextPrimary, 0.6f))
            section.mark?.let { what ->
                Box(Modifier.size(FrameSize.NavMark).background(OctoColors.Accent, CircleShape).semantics { contentDescription = what })
            }
        }
    }
}

// The lit fill of the place shown in a list: the accent fading across, with
// a hairline of the accent round it.
@Composable
internal fun PlaceFill(modifier: Modifier = Modifier) {
    val accent = OctoColors.Accent
    val fill = remember(accent) { Brush.horizontalGradient(listOf(accent.copy(alpha = PLACE_FROM), accent.copy(alpha = PLACE_TO))) }
    Box(modifier.background(fill, Corner.ControlShape).border(FrameSize.Hairline, accent.copy(alpha = PLACE_FROM), Corner.ControlShape))
}

private const val PLACE_FROM = 0.22f
private const val PLACE_TO = 0.08f

// A section's or card's icon: tinted and on whole pixels, or a brand's mark
// exactly as drawn, in its own colors.
@Composable
internal fun SectionIcon(icon: ImageVector, brand: Boolean, size: Dp, tint: Color) {
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

// A switch on a card's title line, turning everything in the card on or off.
class TitleSwitch(val on: Boolean, val change: (Boolean) -> Unit)

// A titled card of settings: its icon and title above the glaze, an action
// or a switch at the end of that line, its rows in the glaze, and a line
// about it under it when it has one. On a Settings page it registers under
// `id` (by default its title made into an id), and its rows under it.
@Composable
internal fun SettingsCard(
    title: String?,
    icon: ImageVector? = null,
    id: String? = null,
    brand: Boolean = false,
    footer: String? = null,
    toggle: TitleSwitch? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val key = id ?: title?.let(::settingsSlug)
    CompositionLocalProvider(LocalSettingsCard provides key) {
        Column(Modifier.fillMaxWidth().then(if (key != null) Modifier.settingsPlace(key, light = false) else Modifier)) {
            if (title != null) CardTitle(title, icon, brand, toggle, trailing)
            Rows(flash = flashOf(key), off = toggle?.on == false, content = content)
            if (footer != null) Txt(footer, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(start = RowInset, end = RowInset, top = Space.S), maxLines = 3)
        }
    }
}

// A card's title line: its icon in the accent, on the card's edge, and its
// title, then its action or switch ending where the rows' controls end. The
// line is as tall as its words whatever ends it: a taller control reaches
// out above and below it. A title holding a switch is a row of its own for
// the tree.
@Composable
private fun CardTitle(title: String, icon: ImageVector?, brand: Boolean, toggle: TitleSwitch?, trailing: (@Composable () -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (toggle != null) Modifier.semantics { this[RowTitle] = RowTitleInfo(title, title) } else Modifier)
            .then(if (icon != null) Modifier.semantics { this[CardIcon] = CardIconInfo(title, icon) } else Modifier)
            .padding(end = RowInset, bottom = Space.M)
            .heightIn(min = TitleLine),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        if (icon != null) SectionIcon(icon, brand, settingsIconSize(), OctoColors.Accent)
        Txt(
            title,
            DesktopType.emphasis,
            OctoColors.TextPrimary,
            Modifier.weight(1f).semantics {
                heading()
                contentDescription = title
            },
            maxLines = 2,
        )
        if (trailing != null) Box(Modifier.outward()) { trailing() }
        if (toggle != null) OctoSwitch(toggle.on, toggle.change, Modifier.outward().semantics { contentDescription = title })
    }
}

// The height of a card's title line, whatever ends it.
private val TitleLine = Space.Xxl

// A card with no title on a page, its glaze as far under the card above as
// a titled card's is, so it reads as a card of its own.
@Composable
internal fun UntitledCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = TitleLine + Space.M)) { Rows(content = content) }
}

// A control on a title's line, measured as tall as it likes but taking no
// more of the line's height than the line has: it is drawn centered on the
// line and reaches out above and below it, where it still takes clicks.
private fun Modifier.outward(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val line = TitleLine.roundToPx()
    val height = minOf(placeable.height, maxOf(line, constraints.minHeight))
    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
}

// The icon size for a settings list item, card title or search result:
// one size up once the text is 115% or more, so the icons keep up with
// the words on whole pixels.
@Composable
internal fun settingsIconSize(): Dp = settingsIconSize(LocalDensity.current.fontScale)

internal fun settingsIconSize(textScale: Float): Dp = if (textScale >= BIGGER_ICONS_FROM) IconSize.Transport else IconSize.Toolbar

// Whether the text is big enough for the next icon size up.
@Composable
internal fun biggerIcons(): Boolean = settingsIconSize() == IconSize.Transport

private const val BIGGER_ICONS_FROM = 1.15f

// A group of rows in a card, with its name above when it has one (and
// `action`, a quiet word for the whole group, at the end of that line), and
// a line on what the group is under it when it has one.
@Composable
internal fun Group(name: String? = null, footer: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    SettingsCard(name, footer = footer, trailing = action, content = content)
}

// Whether what is being drawn is already inside a card's glaze, so rows
// inside it do not draw a second one.
private val LocalInCard = staticCompositionLocalOf { false }

// Whether the cards being drawn sit in a sheet over the page. On a light
// theme the sheet shares the card color, so there a card is a tray of the
// words' ink a step darker than the sheet; Settings' cards keep their lift.
internal val LocalInSheet = staticCompositionLocalOf { false }

// A card: the glaze, holding whatever is put in it. A card with nothing in
// it draws nothing.
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val inside = LocalInCard.current
    Column(modifier.fillMaxWidth().then(if (inside) Modifier else Modifier.cardSurface())) {
        CompositionLocalProvider(LocalInCard provides true) { content() }
    }
}

// The glaze: a faint fill of the words' ink, a hairline edge, and a finer
// light along its top inside edge. In a sheet on a light theme the fill is
// the tray (LocalInSheet).
@Composable
private fun Modifier.cardSurface(flash: SettingsFlash? = null): Modifier {
    val fill = if (LocalInSheet.current && LocalOctoSurfaces.current.light) OctoColors.TextPrimary.copy(alpha = SHEET_TRAY) else CardFill
    val edge = CardEdge
    val top = glassRim(TOP_LIGHT)
    return this
        .drawBehind {
            if (size.height <= 0f) return@drawBehind
            val radius = CornerRadius(Corner.Panel.toPx())
            val line = FrameSize.Hairline.toPx()
            drawRoundRect(fill, cornerRadius = radius)
            drawRoundRect(edge, topLeft = Offset(line / 2, line / 2), size = Size(size.width - line, size.height - line), cornerRadius = radius, style = Stroke(line))
        }
        .innerShadow(Corner.PanelShape, Shadow(radius = Space.None, color = top, offset = DpOffset(Space.None, FrameSize.Hairline)))
        .flashFill(flash, inset = false)
        .clip(Corner.PanelShape)
}

private const val TOP_LIGHT = 0.055f

// How much of the words' ink a sheet's card takes on a light theme: Paper's
// and Daylight's trays sit 4.4 and 4.7 under their sheets in lightness.
private const val SHEET_TRAY = 0.06f

// Rows one under another in a card, with a hairline between each. A row
// that draws nothing takes no place and gets no line; with no rows at all
// there is no card. Rows already inside a card share its glaze. `off` rows
// (their card's switch is off) stay in place, dimmed and out of reach.
@Composable
internal fun Rows(flash: SettingsFlash? = null, off: Boolean = false, content: @Composable () -> Unit) {
    val inside = LocalInCard.current
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    val separator = SeparatorColor
    val quiet = off || LocalRowsOff.current
    // Provided around the layout, so the rows hear a change at once.
    CompositionLocalProvider(LocalRowsOff provides quiet) { RowsLayout(inside, flash, off, onePixel, separator, content) }
}

@Composable
private fun RowsLayout(inside: Boolean, flash: SettingsFlash?, off: Boolean, onePixel: Dp, separator: Color, content: @Composable () -> Unit) {
    SubcomposeLayout(Modifier.fillMaxWidth().then(if (inside) Modifier else Modifier.cardSurface(flash)).switchedOff(off)) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val rows = subcompose("rows") { CompositionLocalProvider(LocalInCard provides true, content = content) }.map { it.measure(loose) }.filter { it.height > 0 }
        val lines = subcompose("lines") {
            repeat((rows.size - 1).coerceAtLeast(0)) {
                Box(Modifier.padding(horizontal = RowInset).fillMaxWidth().height(onePixel).background(separator))
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

// Whether the rows being drawn are switched off: their controls then say
// nothing to a screen reader and take no wheel, and only their words are
// read.
internal val LocalRowsOff = compositionLocalOf { false }

// Rows switched off, while `off`: drawn faint, with no press, drag or
// keyboard reaching anything in them. The wheel still scrolls the page.
internal fun Modifier.switchedOff(off: Boolean): Modifier = if (!off) this else this
    .alpha(OFF_ALPHA)
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) event.changes.forEach { it.consume() }
            }
        }
    }
    .focusProperties { onEnter = { cancelFocusChange() } }
    .focusGroup()

// How strongly rows switched off are drawn.
internal const val OFF_ALPHA = 0.45f

// A setting's name.
internal val RowTitleWords = DesktopType.body.copy(fontWeight = FontWeight.Medium)

// Every setting's row: its name, a short line when the name needs one, and
// its control on the right. `stable` is the title's fixed form when parts
// of it are filled in; `id` names a row whose title is data.
@Composable
internal fun SettingRow(
    title: String,
    caption: String?,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    stable: String? = null,
    id: String? = null,
    // Every caption the row can show as its choice changes, so it keeps the
    // height of the longest and never moves when another is picked.
    captionRoom: List<String> = emptyList(),
    control: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .settingsRow(title, stable, id)
            .then(modifier)
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .padding(horizontal = RowInset, vertical = Space.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Xxl),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, RowTitleWords, if (dim) OctoColors.TextMuted else OctoColors.TextPrimary, maxLines = 2)
            if (caption != null && captionRoom.isEmpty()) {
                Txt(caption, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(top = Space.Xxs), maxLines = Int.MAX_VALUE)
            } else if (caption != null) {
                Box(Modifier.padding(top = Space.Xxs)) {
                    captionRoom.forEach { Txt(it, DesktopType.meta, Color.Transparent, Modifier.clearAndSetSemantics { }, maxLines = Int.MAX_VALUE) }
                    Txt(caption, DesktopType.meta, OctoColors.TextMuted, maxLines = Int.MAX_VALUE)
                }
            }
        }
        // The control is measured first, so it is held to its share of the
        // row and the words keep the rest; a picker too wide for that turns
        // into its one-button form. Switched off, it is only drawn.
        Row(
            Modifier.then(if (LocalRowsOff.current) Modifier.clearAndSetSemantics { } else Modifier).layout { measurable, constraints ->
                val most = if (constraints.hasBoundedWidth) (constraints.maxWidth * ControlShare).toInt() else constraints.maxWidth
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = most))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Xxl),
            content = control,
        )
    }
}

// The most of a row its control may take beside its words.
private const val ControlShare = 0.6f

// A setting that is on or off. The whole row flips it.
@Composable
internal fun SwitchRow(title: String, caption: String?, on: Boolean, id: String? = null, stable: String? = null, change: (Boolean) -> Unit) {
    val off = LocalRowsOff.current
    SettingRow(
        title,
        caption,
        if (off) {
            Modifier
        } else {
            Modifier
                .hoverLift()
                .toggleable(value = on, interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.Switch, onValueChange = change)
        },
        stable = stable,
        id = id,
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
internal fun <T> ChoiceRow(title: String, caption: String?, options: List<T>, selected: T, label: (T) -> String, stable: String? = null, onSelect: (T) -> Unit) {
    SettingRow(title, caption, stable = stable) {
        FitOrElse(
            { GlazeSegments(options, selected, label, onSelect) },
            { ChoiceButton(options, selected, label, onSelect, title = title) },
        )
    }
}

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
    id: String? = null,
) {
    SettingRow(title, caption, id = id) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
            LineSlider(
                fraction = { fraction },
                onSeek = onChange,
                modifier = Modifier.width(SettingsSize.Slider),
                live = live,
                onRelease = onRelease,
                wheelStep = wheelStep.takeIf { !LocalRowsOff.current },
                look = SliderLook.Jewel,
                label = title,
                reading = { reading },
            )
            // Its end lines up with the switches' and actions' edge.
            Txt(reading, DesktopType.meta.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary, Modifier.width(SettingsSize.Reading), align = TextAlign.End)
        }
    }
}

// A fact, with how it stands on the right in the captions' quiet words.
@Composable
internal fun InfoRow(title: String, caption: String?, value: String, stable: String? = null) {
    SettingRow(title, caption, stable = stable) {
        Txt(value, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(end = RowValueEnd))
    }
}

// The room after a value at a row's end: none, so values end on the same
// edge as the switches and the actions.
internal val RowValueEnd = Space.None

// Something to do, with its action on the right. A `danger` one (it
// removes something for good) has its action in red.
@Composable
internal fun ActionRow(title: String, caption: String?, action: String, onClick: () -> Unit, enabled: Boolean = true, stable: String? = null, danger: Boolean = false) {
    SettingRow(title, caption, stable = stable) {
        RowAction(action, onClick, enabled = enabled, danger = danger)
    }
}

// An action at a row's end: a quiet pill, a faint fill of the words' ink
// with the action in the accent (in a lighter red for a `danger` one),
// ending on the row's value edge. It has the dropdowns' and the segments'
// shape: as tall, fully round, the same room either side of its words. On a
// light theme a darker fill would dim the words, so the pill takes the raised
// surface and shows a hover with its edge line instead.
@Composable
internal fun RowAction(text: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null, danger: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink = if (danger) dangerOnPill() else OctoColors.AccentWords
    val light = LocalOctoSurfaces.current.light
    val lit = hovered && enabled
    val fill = if (light) OctoColors.SurfaceRaised else OctoColors.TextPrimary.copy(alpha = if (lit) PILL_HOVER else PILL_REST)
    val edge = if (light && lit) OctoColors.Edge else Color.Transparent
    Row(
        Modifier
            .alpha(if (enabled) 1f else DISABLED)
            .height(ControlHeight.L)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(FrameSize.Hairline, edge, CircleShape)
            .hoverable(interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .tabStop(LocalTabStops.current)
            .clickable(interactionSource = interaction, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text }
            .padding(horizontal = PillSide),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        if (icon != null) Glyph(icon, size = IconSize.Table, tint = ink)
        Txt(text, OctoType.label, ink)
    }
}

private const val PILL_REST = 0.06f
private const val PILL_HOVER = 0.10f

// The room either side of a pill's words.
private val PillSide = Space.L + Space.Xxs

// The danger red lifted toward the words' ink, so it reads on a quiet pill
// on any row; a danger action in plain words keeps the red itself.
@Composable
internal fun dangerOnPill(): Color = lerp(OctoColors.Error, OctoColors.TextPrimary, DANGER_LIFT)

private const val DANGER_LIFT = 0.5f

// The one action a card most wants taken: a pill of the accent with the
// page's dark words, lighter under the pointer, in a row action's shape.
@Composable
internal fun AccentPill(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = if (hovered && enabled) OctoColors.AccentHover else OctoColors.Accent
    Row(
        Modifier
            .alpha(if (enabled) 1f else DISABLED)
            .height(ControlHeight.L)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .hoverable(interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .tabStop(LocalTabStops.current)
            .clickable(interactionSource = interaction, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text }
            .padding(horizontal = PillSide),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, OctoType.label, OctoColors.Background)
    }
}

// An action that removes something for good: a text button in red.
@Composable
internal fun DangerAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val ink = OctoColors.Error
    Row(
        modifier
            .alpha(if (enabled) 1f else DISABLED)
            .hoverLift(CircleShape, clickable = enabled)
            .tabStop(LocalTabStops.current)
            .clickable(interactionSource = null, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.L, vertical = Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        if (icon != null) Glyph(icon, size = IconSize.Table, tint = ink)
        Txt(text, OctoType.label, ink)
    }
}

private const val DISABLED = 0.5f

// The keyboard's ring, `color`, drawn just outside what has it (rounded by
// `corner`) while the keyboard is in use, so it never reads as the part's
// own edge.
@Composable
internal fun Modifier.ringOutside(corner: Dp, color: Color = FocusRingColor): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalFocusVisibility.current.keyboard
    return this
        .drawWithContent {
            drawContent()
            if (focused && keyboard) {
                val width = Focus.Ring.toPx()
                drawRoundRect(
                    color,
                    topLeft = Offset(-width / 2, -width / 2),
                    size = Size(size.width + width, size.height + width),
                    cornerRadius = CornerRadius(corner.toPx() + width / 2),
                    style = Stroke(width),
                )
            }
        }
        .onFocusChanged { focused = it.isFocused }
}

// What a sheet asking before something goes says first: the question, then
// close under it what goes and what stays.
@Composable
internal fun SheetQuestion(question: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(top = Space.S), verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Txt(question, SheetTitleWords, OctoColors.TextPrimary, Modifier.semantics { heading() }, maxLines = 2)
        Txt(detail, DesktopType.table, OctoColors.TextSecondary, maxLines = 4)
    }
}

internal val SheetTitleWords = DesktopType.section.copy(fontWeight = FontWeight.SemiBold)

// Where the Settings page shown sits in the window while Settings is open,
// for a sheet asked from it to sit in the middle of and shade.
internal fun AppState.settingsColumn(): IntRect? = settingsUi.column.takeIf { inSettings }
