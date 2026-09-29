package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlazedIconButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType

// Content starts below the status bar and ends above the floating bar.
@Composable
fun screenPadding(extraTop: Dp = 0.dp): PaddingValues {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(top = top + 8.dp + extraTop, bottom = bottom + 120.dp)
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OctoType.display,
        color = OctoColors.TextPrimary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OctoType.section,
        color = OctoColors.TextPrimary,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

// Detail pages start low enough to clear the floating back button.
val DetailTopGap = 56.dp

// The round back button that floats over a detail page.
@Composable
fun BackButton(onBack: () -> Unit) {
    Box(Modifier.statusBarsPadding().padding(start = 16.dp, top = 8.dp)) {
        GlazedIconButton(
            backdrop = LocalHaze.current,
            icon = ImageVector.vectorResource(OctoIcons.Back),
            contentDescription = "Back",
            onClick = onBack,
            size = 40.dp,
        )
    }
}

// Song length as a clock, like 3:07 or 1:02:45.
fun Int.asClock(): String {
    val h = this / 3600
    val m = (this % 3600) / 60
    val s = this % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// Album or playlist length in words, like 48 min or 1 hr 12 min.
fun Int.asLength(): String {
    val minutes = (this + 30) / 60
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

fun songs(count: Int) = if (count == 1) "1 song" else "$count songs"

fun albums(count: Int) = if (count == 1) "1 album" else "$count albums"
