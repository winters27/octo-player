package app.winters.octo.desktop.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.LineSlider
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
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
import app.winters.octo.desktop.ui.rememberListState
import kotlinx.coroutines.launch

// The parts the Settings and Sound pages are made of: the list of sections
// beside the page, the sections, and one kind of row for every setting.

// One section of a page: its name, which the list beside the page shows
// too, a line under the name, and something beside it (the equalizer's
// switch).
class PageSection(
    val key: String,
    val name: String,
    val detail: String? = null,
    val trailing: (@Composable () -> Unit)? = null,
    val content: @Composable ColumnScope.() -> Unit,
)

// A page of settings: its title and the list of its sections on the left,
// and the sections themselves in a column centred in the rest of the page.
// A click in the list scrolls to that section; the section being read is
// the one marked. On a narrow window the list is left out.
@Composable
internal fun SectionedPage(app: AppState, visit: Visit, title: String, sections: List<PageSection>) {
    val list = rememberListState(app.navigator, visit)
    val scope = rememberCoroutineScope()
    val still = LocalReduceMotion.current
    // The section last picked in the list, until the reader scrolls.
    var jumped by remember { mutableStateOf<Int?>(null) }
    var jumping by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving -> if (moving && !jumping) jumped = null }
    }
    val current by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val spans = info.visibleItemsInfo.map { Span(it.index, it.offset, it.offset + it.size) }
            sectionInView(spans, info.viewportSize.height, atEnd = !list.canScrollForward, jumped = jumped)
        }
    }
    fun jump(index: Int) {
        jumped = index
        scope.launch {
            jumping = true
            try {
                if (still) list.scrollToItem(index) else list.animateScrollToItem(index)
            } finally {
                jumping = false
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val withList = maxWidth >= PageSide * 2 + SettingsSize.Nav + Space.Wide + SettingsSize.ColumnMin
        Column(Modifier.fillMaxSize()) {
            Txt(title, DesktopType.pageTitle, modifier = Modifier.padding(start = PageSide, end = PageSide, top = Space.Xxl, bottom = Space.Xs))
            Row(Modifier.fillMaxSize()) {
                if (withList) SectionList(sections, current, ::jump)
                SectionColumn(list, sections, Modifier.weight(1f).fillMaxHeight(), start = if (withList) Space.Wide else PageSide)
            }
        }
    }
}

// The names of the page's sections, the one being read on the darker pill.
@Composable
private fun SectionList(sections: List<PageSection>, current: Int, onPick: (Int) -> Unit) {
    Column(Modifier.padding(start = PageSide - Space.M, top = Space.Xl - Space.S).width(SettingsSize.Nav), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
        sections.forEachIndexed { index, section ->
            val chosen = index == current
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RowHeight.Nav)
                    .hoverLift(Corner.ControlShape, lifted = false)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onPick(index) },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (chosen) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
                Txt(section.name, DesktopType.body, if (chosen) OctoColors.TextPrimary else OctoColors.TextSecondary, Modifier.padding(horizontal = Space.M))
            }
        }
    }
}

// The sections, one under another, in a column no wider than reads well,
// with room at the foot so the last of them clears the floating player.
@Composable
private fun SectionColumn(list: LazyListState, sections: List<PageSection>, modifier: Modifier, start: Dp) {
    val bottom = LocalBottomRoom.current + Space.Section
    LazyColumn(
        modifier.topFade(Space.Xl),
        state = list,
        contentPadding = PaddingValues(start = start, end = PageSide, top = Space.Xl, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sections.forEach { section ->
            item(key = section.key) { Section(section, Modifier.widthIn(max = SettingsSize.Column).fillMaxWidth()) }
        }
    }
}

// The top edge fades out, so words scrolled up melt away under the title
// rather than being cut off. At rest nothing sits in it.
private fun Modifier.topFade(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = height.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = edge), size = Size(size.width, edge), blendMode = BlendMode.DstIn)
    }

@Composable
private fun Section(section: PageSection, modifier: Modifier) {
    Column(modifier.padding(bottom = Space.Section + Space.M)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.M).padding(bottom = Space.S), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(section.name, DesktopType.section)
                section.detail?.let { Txt(it, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(top = Space.Xxs), maxLines = 2) }
            }
            section.trailing?.invoke()
        }
        section.content(this)
    }
}

// Where a section sits in the list, in pixels from the top of what shows.
internal data class Span(val index: Int, val top: Int, val bottom: Int)

// The section being read: at the very top the first, at the very end the
// last, and otherwise the one a line a tenth of the way down the list
// crosses. One just picked in the list stays marked while it is in sight,
// since a short section near the end can never reach the top.
internal fun sectionInView(spans: List<Span>, height: Int, atEnd: Boolean, jumped: Int?): Int {
    if (spans.isEmpty()) return jumped ?: 0
    if (jumped != null && spans.any { it.index == jumped && it.top < height && it.bottom > 0 }) return jumped
    val first = spans.first()
    if (first.index == 0 && first.top >= 0) return 0
    if (atEnd) return spans.last().index
    val line = height / 10
    return spans.firstOrNull { line >= it.top && line < it.bottom }?.index
        ?: spans.lastOrNull { it.top <= line }?.index
        ?: spans.first().index
}

// A group of rows inside a section, with its name above when it has one.
@Composable
internal fun Group(name: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (name != null) Space.L else Space.None)) {
        if (name != null) Txt(name.uppercase(), DesktopType.label, OctoColors.TextMuted, Modifier.padding(start = Space.M, bottom = Space.Xs))
        Rows(content)
    }
}

// Rows one under another with a hairline between each. A row that draws
// nothing takes no place and gets no line.
@Composable
internal fun Rows(content: @Composable () -> Unit) {
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val rows = subcompose("rows", content).map { it.measure(loose) }.filter { it.height > 0 }
        val lines = subcompose("lines") {
            repeat((rows.size - 1).coerceAtLeast(0)) {
                Box(Modifier.padding(horizontal = Space.M).fillMaxWidth().height(onePixel).background(SeparatorColor))
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

// Every setting's row: its name, a line on when you would want it, and
// its control on the right.
@Composable
internal fun SettingRow(title: String, caption: String?, modifier: Modifier = Modifier, dim: Boolean = false, control: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .padding(horizontal = Space.M, vertical = Space.L),
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
            .hoverLift(Corner.RowShape)
            .toggleable(value = on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch, onValueChange = change),
    ) {
        OctoSwitch(on, change)
    }
}

// A setting with a few choices, the chosen one on the darker pill.
@Composable
internal fun <T> ChoiceRow(title: String, caption: String?, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SettingRow(title, caption) {
        GlazeSegments(options, selected, label, onSelect)
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
            )
            Txt(reading, DesktopType.meta.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary, Modifier.width(SettingsSize.Reading))
        }
    }
}

// A fact, with its value on the right.
@Composable
internal fun InfoRow(title: String, caption: String?, value: String) {
    SettingRow(title, caption) {
        Txt(value, DesktopType.body, OctoColors.TextSecondary)
    }
}

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
    TextAction(text, onClick, Modifier.offset(x = Space.L), enabled = enabled, icon = icon)
}
