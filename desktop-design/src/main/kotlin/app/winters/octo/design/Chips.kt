package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// One choice of many that can be scrolled through (the Charts page's
// genres): a pill, filled with the accent when it is the chosen one.
@Composable
fun GlazeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (selected) OctoColors.AccentSelected else Color.White.copy(alpha = 0.08f))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Txt(text, OctoType.label, if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary)
    }
}

// A row of chips that scrolls sideways when they do not fit.
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option -> GlazeChip(label(option), option == selected, { onSelect(option) }) }
    }
}
