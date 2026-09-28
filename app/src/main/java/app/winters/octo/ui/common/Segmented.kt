package app.winters.octo.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.winters.octo.design.GlazeTabs
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType

// A few choices in one glaze, the chosen one in the darker pill that glides
// between them, like the tabs in the bar.
@Composable
internal fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlazeTabs(options.size, selected, onSelect, modifier.fillMaxWidth()) { index, chosen ->
        val tint by animateColorAsState(if (chosen) OctoColors.Accent else OctoColors.TextPrimary, label = "segment tint")
        Text(options[index], style = OctoType.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
