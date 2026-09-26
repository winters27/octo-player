package app.winters.octo.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeSelected
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
    Glaze(modifier.fillMaxWidth().height(40.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val segment = maxWidth / options.size
            val x by animateDpAsState(segment * selected, spring(dampingRatio = 0.8f, stiffness = 350f), label = "segment")
            GlazeSelected(
                Modifier
                    .offset { IntOffset(x.roundToPx(), 0) }
                    .width(segment)
                    .fillMaxHeight(),
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                options.forEachIndexed { index, label ->
                    val chosen = index == selected
                    val tint by animateColorAsState(if (chosen) OctoColors.Accent else OctoColors.TextPrimary, label = "segment tint")
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = chosen,
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                            ) { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = OctoType.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
