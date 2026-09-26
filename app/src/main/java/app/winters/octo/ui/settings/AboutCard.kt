package app.winters.octo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.winters.octo.BuildConfig
import app.winters.octo.ui.common.FloatingSheet
import app.winters.octo.whatsnew.WhatsNewList

// The app's version, and what is new in it.
@Composable
internal fun AboutCard(modifier: Modifier = Modifier) {
    var reading by remember { mutableStateOf(false) }
    Card("About", modifier) {
        Line("Octo", BuildConfig.VERSION_NAME)
        ChoiceLine("What's new", "The latest additions, in plain words") { reading = true }
    }
    FloatingSheet(visible = reading, onDismiss = { reading = false }) {
        WhatsNewList()
    }
}
