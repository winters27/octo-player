package app.winters.octo.ui.settings.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding

// A settings page: the back button, the large title, and its groups one
// under another. `highlight` is the row a search result pointed at.
@Composable
fun SettingsPageFrame(
    title: String,
    onBack: () -> Unit,
    highlight: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Lit once per visit from search, not again on coming back to the page.
    var shown by rememberSaveable { mutableStateOf(false) }
    val target = remember(highlight) { SettingsHighlight(highlight.takeUnless { shown }) { shown = true } }

    CompositionLocalProvider(LocalSettingsHighlight provides target) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(screenPadding(extraTop = DetailTopGap)),
            ) {
                ScreenTitle(title)
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
            }
            BackButton(onBack)
        }
    }
}
