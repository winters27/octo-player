package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// One choice of many that can be scrolled through (the Charts page's
// genres): a pill, filled with the accent when it is the chosen one, with
// a `count` after its words in a softer ink when it has one.
@Composable
fun GlazeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: String? = null) {
    val onAccent = OctoColors.OnAccent
    Row(
        modifier
            .clip(CircleShape)
            .background(if (selected) OctoColors.Accent else OctoColors.TextPrimary.copy(alpha = 0.08f))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(text, OctoType.label, if (selected) onAccent else OctoColors.TextSecondary)
        if (count != null) Txt(count, OctoType.label, if (selected) onAccent.copy(alpha = 0.62f) else OctoColors.TextMuted)
    }
}

// A row of chips that scrolls sideways when they do not fit, its ends
// fading while there are more chips that way.
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    count: ((T) -> String?)? = null,
) {
    val state = rememberLazyListState()
    LazyRow(modifier.rowFades(state), state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option -> GlazeChip(label(option), option == selected, { onSelect(option) }, count = count?.invoke(option)) }
    }
}

// Controls on one line that scrolls sideways when they do not fit (a
// page's filters on its control line), its ends fading while there is
// more of it that way.
@Composable
fun SideScroll(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable RowScope.() -> Unit) {
    val state = rememberScrollState()
    Row(
        modifier.rowFades(state).horizontalScroll(state),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// `wide` when the whole of it fits the room it is given, else `narrow` in
// its place (a row of choices, else one button opening them).
@Composable
fun FitOrElse(wide: @Composable () -> Unit, narrow: @Composable () -> Unit, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0)
        // The wide form is measured first, with nothing in it reachable by a
        // screen reader; if it fits it is composed again to be shown.
        val probe = subcompose("probe") { Box(Modifier.clearAndSetSemantics {}) { wide() } }.map { it.measure(loose.copy(maxWidth = Constraints.Infinity)) }
        val fits = (probe.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth
        val shown = if (fits) subcompose("wide", wide).map { it.measure(loose.copy(maxWidth = Constraints.Infinity)) } else subcompose("narrow", narrow).map { it.measure(loose) }
        val width = shown.maxOfOrNull { it.width } ?: 0
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(width, height) { shown.forEach { it.place(0, (height - it.height) / 2) } }
    }
}
