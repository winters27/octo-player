package app.winters.octo.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The sign-in form's looks that no unit row can see, read from the sources:
// Robolectric draws no frame to measure, the chevrons carry no semantics,
// and a border's color is not in the tree. Each check names the exact text.
class FormPolishSitesTest {
    private fun source(path: String) = File("src/main/java/app/winters/octo/$path").readText().replace("\r\n", "\n")

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("\"$from\" is missing", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("\"$to\" is missing after \"$from\"", end >= 0)
        return text.substring(start, end)
    }

    private val groups = source("ui/common/FormGroups.kt")
    private val screen = source("ui/signin/SignInScreen.kt")

    // Red when the callout ring goes back to the accent.
    @Test
    fun theCalloutRingIsTheErrorRed() {
        val card = between(groups, "fun FormGroupCard(", "\n}\n")
        assertTrue(card, card.contains("val ring = OctoColors.ErrorWords"))
        assertTrue(card, card.contains("Modifier.border(1.5.dp, ring, CardShape)"))
        assertFalse(card, card.contains("OctoColors.Accent"))
    }

    // Red when Advanced's head centers its chevron on its two lines again
    // (it sags under the title), or the chevron ends past the rows' right
    // edge.
    @Test
    fun advancedsChevronSitsOnItsTitleAndTheRowsEdge() {
        val head = between(groups, "fun FormSectionHead(", "\n}\n")
        assertTrue(head, head.contains(".padding(start = 4.dp, end = RowSides, top = 10.dp, bottom = 10.dp),"))
        assertTrue(head, head.contains("verticalAlignment = Alignment.Top,"))
        assertFalse(head, head.contains("Alignment.CenterVertically"))
    }

    // Red when a choice row centers its chevron on its lines again.
    @Test
    fun aChoicesChevronSitsOnItsName() {
        val choice = between(groups, "fun FormChoiceRow(", "\n}\n")
        assertTrue(choice, choice.contains("verticalAlignment = Alignment.Top,\n            horizontalArrangement = Arrangement.spacedBy(ChoiceGap),"))
        assertTrue(choice, choice.contains("modifier = Modifier.offset(y = -ChevronLift).size(ChoiceChevron)"))
    }

    // Red when the page's scroll loses its bottom fade, a fade pops in at full
    // strength, or the callout host is not told the fade.
    @Test
    fun thePageFadesAtBothEndsAndEasesIn() {
        val fades = between(screen, "private fun Modifier.pageFades(scroll: ScrollState): Modifier = this", "\n// How tall the soft edge")
        listOf(
            "val above = (scroll.value / top).coerceIn(0f, 1f)",
            "val below = ((scroll.maxValue - scroll.value) / bottom).coerceIn(0f, 1f)",
            "Brush.verticalGradient(*easedStops(1f, 1f - below), startY = size.height - bottom, endY = size.height)",
        ).forEach { assertTrue(it, fades.contains(it)) }
        assertTrue(screen.contains(".imePadding()\n                .pageFades(scroll)\n                .verticalScroll(scroll),"))
        assertFalse(screen.contains("topFade("))
        assertTrue(screen.contains("fade = with(density) { BottomFade.roundToPx() },"))
    }
}
