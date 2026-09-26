package app.winters.octo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel

// A titled card with lines, as the Sound page draws its parts. The settings
// pages use the rows in `rows/` instead.

private val CardShape = RoundedCornerShape(20.dp)

@Composable
internal fun Card(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .glassPanel(CardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = OctoType.section, color = OctoColors.TextPrimary)
        content()
    }
}

@Composable
internal fun SwitchLine(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = OctoType.bodySmall, color = if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
            Text(detail, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        OctoSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// A setting and its current choice; tapping it opens the options.
@Composable
internal fun ChoiceLine(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            Text(value, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}
