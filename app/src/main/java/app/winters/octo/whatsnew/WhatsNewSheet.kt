package app.winters.octo.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.BuildConfig
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType

// The list of what is new, inside a sheet, a heading for each part of the
// app. It scrolls when it is long.
@Composable
fun WhatsNewList() {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("What's new", style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("Version ${BuildConfig.VERSION_NAME}", style = OctoType.caption, color = OctoColors.TextMuted)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            WhatsNewGroups.forEach { group ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(group.title, style = OctoType.label, color = OctoColors.TextPrimary)
                    group.lines.forEach { line -> Text(line, style = OctoType.bodySmall, color = OctoColors.TextMuted) }
                }
            }
        }
    }
}
