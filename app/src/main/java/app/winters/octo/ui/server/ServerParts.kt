package app.winters.octo.ui.server

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType

// A quiet line of text on a server page.
@Composable
internal fun Note(text: String, color: Color = OctoColors.TextMuted) {
    Text(text, style = OctoType.bodySmall, color = color, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

// A small spinner while a list loads.
@Composable
internal fun Spinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = OctoColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}
