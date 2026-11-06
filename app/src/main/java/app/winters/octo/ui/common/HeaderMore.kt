package app.winters.octo.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.MenuRowHeight
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.SwitchSize
import dev.chrisbanes.haze.HazeState

// One line in a page header's More menu: what it does, a quiet line about it
// (how it stands, or what it does when that is not plain), and its icon.
// With `checked` it is a switch that stays in the menu as it flips; without,
// a tap closes the menu and does it. One not `enabled` is dimmed.
class MoreItem(
    val label: String,
    @DrawableRes val icon: Int? = null,
    val detail: String? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val destructive: Boolean = false,
    val opensPage: Boolean = false,
    val onClick: () -> Unit,
)

// A page header's More button: the round glass button with the three dots,
// opening a glass menu of the page's lesser actions beside it. With nothing
// to offer it is not drawn. A `quiet` one is the plain icon with no glass,
// for a sheet's top, where the glass would frost the page under the sheet;
// its menu frosts `backdrop`. `icon` puts another mark on the button, like
// the plus of a menu of things to make.
@Composable
fun HeaderMore(
    items: List<MoreItem>,
    modifier: Modifier = Modifier,
    title: String = "More",
    quiet: Boolean = false,
    backdrop: HazeState = LocalHaze.current,
    @DrawableRes icon: Int = OctoIcons.More,
) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val spot = modifier.pressSpot(LocalPressSpot.current)
    if (quiet) QuietIconAction(icon, title, { open = true }, spot) else HeaderButton(icon, title, { open = true }, spot)
    GlassPopup(
        visible = open,
        anchor = rememberOpenedBeside(open),
        onDismiss = { open = false },
        backdrop = backdrop,
        title = title,
    ) {
        GlassMenuPage {
            items.forEach { item ->
                if (item.checked != null) {
                    GlassMenuSwitch(item)
                } else {
                    GlassMenuAction(
                        item.icon,
                        item.label,
                        onClick = {
                            open = false
                            item.onClick()
                        },
                        detail = item.detail,
                        enabled = item.enabled,
                        opensPage = item.opensPage,
                        destructive = item.destructive,
                    )
                }
            }
        }
    }
}

private val RowShape = RoundedCornerShape(14.dp)

// A switch in a glass menu: the icon, what it does with its line, and the
// switch at the end. The whole line flips it.
@Composable
fun GlassMenuSwitch(item: MoreItem) {
    val checked = item.checked == true
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MenuRowHeight.Touch)
            .clip(RowShape)
            .toggleable(value = checked, enabled = item.enabled, interactionSource = interaction, indication = null, role = Role.Switch) { item.onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (item.icon != null) {
            Icon(painterResource(item.icon), contentDescription = null, tint = if (item.enabled) OctoColors.TextPrimary else OctoColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.label, style = OctoType.bodySmall, color = if (item.enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
            item.detail?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
        }
        OctoSwitch(
            checked = checked,
            onCheckedChange = { item.onClick() },
            modifier = Modifier.clearAndSetSemantics {},
            enabled = item.enabled,
            size = SwitchSize.Small,
            interactionSource = interaction,
        )
    }
}
