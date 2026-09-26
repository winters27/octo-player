package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.sort.SortOption
import app.winters.octo.sort.SortOrder

// One answer to a choice: its name, and a line about it.
data class Choice(val label: String, val detail: String? = null)

// What the sheet can ask.
sealed interface SheetRequest

// A question with a few answers, and what to do with the one picked.
class ChoiceRequest(
    val title: String,
    val choices: List<Choice>,
    val selected: Int,
    val onPick: (Int) -> Unit,
) : SheetRequest

// How to order a list: the options it offers, the order it is in, and what
// to do with a new one.
class SortRequest(
    val options: List<SortOption>,
    val order: SortOrder,
    val onChange: (SortOrder) -> Unit,
) : SheetRequest

// A sheet asking one question, drawn over everything, the bar included,
// so any page can open one.
class ChoiceSheet {
    var open by mutableStateOf<SheetRequest?>(null)
        private set

    // The last question shown, kept after closing so it can slide away.
    var last by mutableStateOf<SheetRequest?>(null)
        private set

    fun show(request: SheetRequest) {
        open = request
        last = request
    }

    fun close() {
        open = null
    }
}

val LocalChoiceSheet = staticCompositionLocalOf<ChoiceSheet> { error("No choice sheet") }

@Composable
fun ChoiceSheetHost(sheet: ChoiceSheet) {
    GlassSheet(visible = sheet.open != null, onDismiss = sheet::close) {
        when (val request = sheet.last ?: return@GlassSheet) {
            is ChoiceRequest -> Choices(request, sheet::close)
            is SortRequest -> SortChoices(request, sheet::close)
        }
    }
}

// The answers as lines, the current one ticked. Picking one closes the sheet.
@Composable
private fun Choices(request: ChoiceRequest, close: () -> Unit) {
    SheetTitle(request.title)
    request.choices.forEachIndexed { index, choice ->
        ChoiceLine(choice, selected = index == request.selected) {
            request.onPick(index)
            close()
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = OctoType.section,
        color = OctoColors.TextPrimary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
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

private val OptionsShape = RoundedCornerShape(24.dp)

// The pill sits 4dp inside the group, so its corners follow the group's.
private val OptionShape = RoundedCornerShape(20.dp)

// The direction first, then the options in one glaze with the current one in
// the darker pill. Flipping the direction reorders the list behind at once
// and keeps the sheet open; picking another option closes it.
@Composable
private fun ColumnScope.SortChoices(request: SortRequest, close: () -> Unit) {
    var descending by remember(request) { mutableStateOf(request.order.descending) }
    SheetTitle("Sort by")
    Segmented(
        options = listOf("Ascending", "Descending"),
        selected = if (descending) 1 else 0,
        onSelect = { index ->
            if ((index == 1) != descending) {
                descending = index == 1
                request.onChange(request.order.copy(descending = descending))
            }
        },
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
    )
    // Scrolls when the options do not all fit on a short screen.
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Glaze(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = OptionsShape) {
            Column(Modifier.fillMaxWidth().padding(4.dp).selectableGroup()) {
                request.options.forEach { option ->
                    SortLine(option.label, selected = option == request.order.by) {
                        if (option != request.order.by) request.onChange(request.order.picking(option))
                        close()
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SortLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(OptionShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (selected) GlazeSelected(Modifier.matchParentSize(), shape = OptionShape)
        Text(
            label,
            style = OctoType.bodySmall,
            color = if (selected) OctoColors.Accent else OctoColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
