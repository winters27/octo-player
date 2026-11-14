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

// A tab's big title, on the same line and baseline as every page header's.
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    PageHeader(text, onBack = null, modifier = modifier)
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

// The round back button that floats over a detail page. While `enabled` is
// false it shows dimmed and takes no press.
@Composable
fun BackButton(onBack: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.statusBarsPadding().padding(start = 16.dp, top = 8.dp)) {
        GlazedIconButton(
            backdrop = LocalHaze.current,
            icon = LocalIconVectors.current(OctoIcons.Back),
            contentDescription = "Back",
            onClick = onBack,
            size = 40.dp,
            enabled = enabled,
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

// Album or playlist length in words, like 48 min, 1 hr 12 min or
// 7,489 hr 27 min.
fun Int.asLength(): String {
    val minutes = (this + 30) / 60
    return if (minutes >= 60) "%,d hr %d min".format(minutes / 60, minutes % 60) else "$minutes min"
}

// "1 song", "12 songs", "200,000 songs".
fun songs(count: Int) = if (count == 1) "1 song" else "%,d songs".format(count)

fun albums(count: Int) = if (count == 1) "1 album" else "%,d albums".format(count)

fun artists(count: Int) = if (count == 1) "1 artist" else "%,d artists".format(count)

// A fact with each number held to the word beside it ("56 min", "15 songs",
// "Rated 4.3"), so a line of facts only breaks between facts.
fun keepTogether(fact: String): String = fact.replace(NextToNumber, "\u00A0")

private val NextToNumber = Regex("(?<=\\d) | (?=\\d)")

// Facts joined into one quiet line that breaks only between facts.
fun factsLine(facts: List<String>, separator: String = " • "): String = facts.joinToString(separator) { keepTogether(it) }
