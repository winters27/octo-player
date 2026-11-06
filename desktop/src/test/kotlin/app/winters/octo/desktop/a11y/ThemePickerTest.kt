package app.winters.octo.desktop.a11y

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import app.winters.octo.desktop.pages.MATCH_THE_SYSTEM
import app.winters.octo.desktop.pages.themeTag
import app.winters.octo.desktop.window.MIN_WIDTH
import app.winters.octo.theme.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The Theme card on Appearance: five themes to pick from, the one showing
// checked, Match the system under them, and the names drawn whole.
class ThemePickerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun A11yScene.card(choice: ThemeChoice): SemanticsNode =
        nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == themeTag(choice) }

    private fun A11yScene.openAppearance() {
        onUi { app.openSettings("look") }
        render(6)
    }

    @Test(timeout = 120_000)
    fun fiveThemesAreOfferedAndPickingOneRedrawsTheApp() {
        A11yScene(folder.newFolder()).use { s ->
            s.openAppearance()
            ThemeChoice.entries.forEach { assertNotNull(s.card(it).config.getOrNull(SemanticsProperties.Selected)) }
            assertEquals(true, s.card(ThemeChoice.Octo).config.getOrNull(SemanticsProperties.Selected))
            assertEquals(false, s.card(ThemeChoice.Paper).config.getOrNull(SemanticsProperties.Selected))
            assertFalse(s.app.palette.value.light)

            s.onUi { s.card(ThemeChoice.Paper).config[SemanticsActions.OnClick].action?.invoke() }
            s.render(6)
            assertEquals(ThemeChoice.Paper, s.app.settings.current.appearance.theme)
            assertEquals(ThemeChoice.Paper, s.app.settings.current.appearance.themeLight)
            assertTrue(s.app.palette.value.light)
            assertEquals(true, s.card(ThemeChoice.Paper).config.getOrNull(SemanticsProperties.Selected))
            assertEquals(false, s.card(ThemeChoice.Octo).config.getOrNull(SemanticsProperties.Selected))
        }
    }

    // Match on keeps the theme showing for the computer's mode; the Day and
    // Night themes follow it.
    @Test(timeout = 120_000)
    fun matchTheSystemFollowsTheDeviceMode() {
        A11yScene(folder.newFolder()).use { s ->
            s.openAppearance()
            s.onUi { s.app.settings.pickTheme(ThemeChoice.Paper) }
            s.onUi { s.app.settings.pickTheme(ThemeChoice.Dusk) }
            val match = s.nodes(merged = true).first { it.clickable && it.name().startsWith(MATCH_THE_SYSTEM) }
            s.onUi { match.config[SemanticsActions.OnClick].action?.invoke() }
            s.render(6)
            assertTrue(s.app.settings.current.appearance.themeMatch)
            // The test device is dark, so the Night theme shows.
            assertEquals(ThemeChoice.Dusk, s.app.palette.value.theme)
            assertTrue(s.nodes().any { it.name().contains("By day: Paper. By night: Dusk.") })
            s.onUi { s.app.theme.deviceDark.value = false }
            s.render(6)
            assertEquals(ThemeChoice.Paper, s.app.palette.value.theme)
            assertEquals(true, s.card(ThemeChoice.Paper).config.getOrNull(SemanticsProperties.Selected))
        }
    }

    // At the least width and the largest text, every name is drawn whole.
    @Test(timeout = 120_000)
    fun everyNameIsDrawnWholeAtTheLeastWidthAndLargeText() {
        A11yScene(folder.newFolder(), width = MIN_WIDTH.toInt(), textScale = 1.3f).use { s ->
            s.openAppearance()
            ThemeChoice.entries.forEach { choice ->
                val card = s.card(choice)
                val name = s.nodes().first { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } == choice.label &&
                        card.boundsInRoot.contains(node.boundsInRoot.center)
                }
                val results = mutableListOf<TextLayoutResult>()
                name.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                val layout = results.firstOrNull()
                assertNotNull("${choice.label} has a layout", layout)
                assertFalse("${choice.label} is cut", layout!!.hasVisualOverflow)
                assertTrue("${choice.label} is on at most two lines", layout.lineCount <= 2)
            }
        }
    }
}
