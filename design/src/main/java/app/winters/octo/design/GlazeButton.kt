package app.winters.octo.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// A quieter button to sit beside the accent one: the lit glaze with a label.
@Composable
fun GlazeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Glaze(
        modifier
            .height(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Text(text, style = OctoType.label, color = OctoColors.TextPrimary, modifier = Modifier.padding(horizontal = 24.dp))
    }
}
