package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType

// One answer to a choice: its name, and a line about it.
data class Choice(val label: String, val detail: String? = null)

// A question with a few answers, and what to do with the one picked.
class ChoiceRequest(
    val title: String,
    val choices: List<Choice>,
    val selected: Int,
    val onPick: (Int) -> Unit,
)

// A sheet asking one question, drawn over everything, the bar included,
// so any page can open one.
class ChoiceSheet {
    var open by mutableStateOf<ChoiceRequest?>(null)
        private set

    // The last question shown, kept after closing so it can slide away.
    var last by mutableStateOf<ChoiceRequest?>(null)
        private set

    fun show(request: ChoiceRequest) {
        open = request
        last = request
    }

    fun close() {
        open = null
    }
}

val LocalChoiceSheet = staticCompositionLocalOf<ChoiceSheet> { error("No choice sheet") }

// The answers as lines, the current one ticked. Picking one closes the sheet.
@Composable
fun ChoiceSheetHost(sheet: ChoiceSheet) {
    GlassSheet(visible = sheet.open != null, onDismiss = sheet::close) {
        val request = sheet.last ?: return@GlassSheet
        Text(
            request.title,
            style = OctoType.section,
            color = OctoColors.TextPrimary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        request.choices.forEachIndexed { index, choice ->
            ChoiceLine(choice, selected = index == request.selected) {
                request.onPick(index)
                sheet.close()
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ChoiceLine(choice: Choice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(choice.label, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            choice.detail?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
        }
        if (selected) {
            Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(22.dp))
        }
    }
}
