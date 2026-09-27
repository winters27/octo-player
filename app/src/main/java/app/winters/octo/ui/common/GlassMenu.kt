package app.winters.octo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType

// How one option in a glass menu is drawn: whether it is the chosen one, and
// whether a line parts it from the option above. No line touches the chosen
// option's pill, so the pill reads as set into the glass.
data class MenuRowLook(val selected: Boolean, val lineAbove: Boolean)

fun menuRowLooks(count: Int, selected: Int): List<MenuRowLook> = List(count) { index ->
    MenuRowLook(
        selected = index == selected,
        lineAbove = index > 0 && index != selected && index - 1 != selected,
    )
}

// The card is padded 6dp, so the pill's corners follow the card's 20dp.
private val OptionShape = RoundedCornerShape(14.dp)

private val LineColour = OctoColors.TextPrimary.copy(alpha = 0.08f)

// The options of a menu, one above the other with hairlines between, the
// chosen one in the darker pill with a white check. `selected` below zero
// means none is chosen, as when the menu asks for an action.
@Composable
fun GlassMenuOptions(options: List<Choice>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val looks = remember(options.size, selected) { menuRowLooks(options.size, selected) }
    Column(if (selected >= 0) modifier.selectableGroup() else modifier) {
        options.forEachIndexed { index, option ->
            // Always 1dp, drawn or not, so the rows never shift.
            if (index > 0) MenuLine(looks[index].lineAbove)
            MenuOption(option, looks[index].selected, pickable = selected >= 0) { onPick(index) }
        }
    }
}

// A quiet heading at the top of a menu.
@Composable
fun GlassMenuTitle(text: String) {
    Text(
        text,
        style = OctoType.label,
        color = OctoColors.TextSecondary,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun MenuLine(drawn: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(if (drawn) LineColour else Color.Transparent),
    )
}

@Composable
private fun MenuOption(option: Choice, selected: Boolean, pickable: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(OptionShape)
            .then(
                if (pickable) {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (selected) GlazeSelected(Modifier.matchParentSize(), shape = OptionShape)
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(option.label, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
                option.detail?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
            }
            // The check's room is kept on every line, so the menu's width
            // does not depend on which one is chosen.
            if (selected) {
                Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            } else if (pickable) {
                Spacer(Modifier.size(18.dp))
            }
        }
    }
}
