package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.winters.octo.BuildConfig
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.InfoRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.whatsnew.WhatsNewPanel

// The app's version, what is new in it, and who made it.
@Composable
fun AboutPage(onBack: () -> Unit, highlight: String?) {
    var reading by rememberSaveable { mutableStateOf(false) }

    SettingsPageFrame("About", onBack, highlight) {
        SettingsGroup {
            InfoRow(SettingsIndex.Version, BuildConfig.VERSION_NAME)
            ActionRow(SettingsIndex.WhatsNew, onClick = { reading = true }, helper = "The latest additions, in plain words")
        }
        Credit()
    }
    WhatsNewPanel(visible = reading, onDismiss = { reading = false })
}

// One quiet line.
@Composable
private fun Credit() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Made by Winters with love" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Made by Winters with",
            style = OctoType.caption.copy(fontStyle = FontStyle.Italic),
            color = OctoColors.TextMuted,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Icon(
            painterResource(OctoIcons.Liked),
            contentDescription = null,
            tint = OctoColors.TextMuted,
            modifier = Modifier.padding(start = 4.dp).size(12.dp),
        )
    }
}
