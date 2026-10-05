package app.winters.octo.ui.settings.rows

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding

// A settings page: the back button, the large title beside the page's
// icon on a tile (as the desktop heads each section), and its groups one
// under another. A `brand` icon is a service's own mark, kept in its
// colours. `highlight` is the row a search result pointed at.
@Composable
fun SettingsPageFrame(
    title: String,
    onBack: () -> Unit,
    highlight: String? = null,
    @DrawableRes icon: Int? = null,
    brand: Boolean = false,
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
                if (icon == null) {
                    ScreenTitle(title)
                } else {
                    Row(
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier
                                .size(PageIconTile)
                                .clip(TileShape)
                                .background(OctoColors.TextPrimary.copy(alpha = 0.07f))
                                .border(1.dp, OctoColors.TextPrimary.copy(alpha = 0.06f), TileShape),
                            contentAlignment = Alignment.Center,
                        ) { MarkOrIcon(icon, brand, OctoColors.TextPrimary, 24.dp) }
                        // Never cut short: a long title takes another line, at
                        // large text sizes as many as it needs.
                        Text(
                            title,
                            style = OctoType.display,
                            color = OctoColors.TextPrimary,
                            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
            }
            BackButton(onBack)
        }
    }
}

// The tile a page's icon sits on beside its title.
private val PageIconTile = 46.dp
private val TileShape = RoundedCornerShape(13.dp)
