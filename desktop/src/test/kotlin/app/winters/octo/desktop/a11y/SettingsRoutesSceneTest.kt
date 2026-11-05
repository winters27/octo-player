package app.winters.octo.desktop.a11y

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.home.EDIT_HOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Settings reached and left from the real window: the sidebar's Sound and
// Settings rows, Home's Edit link, and the player's panel buttons, which
// show no panel lit while Settings holds the window.
class SettingsRoutesSceneTest {
    @get:Rule val folder = TemporaryFolder()

    private fun A11yScene.click(node: SemanticsNode) {
        onUi { node.config[SemanticsActions.OnClick].action?.invoke() }
        render(4)
    }

    private fun A11yScene.button(name: String): SemanticsNode =
        nodes(merged = true).first { it.name() == name && it.clickable }

    @Test(timeout = 120_000)
    fun theSidebarRowsAndHomesEditLinkOpenTheirPages() {
        A11yScene(folder.root).use { s ->
            s.click(s.button("Sound"))
            assertTrue(s.app.inSettings)
            assertEquals(SettingsKey.Sound, s.app.settingsUi.page)
            s.onUi { s.app.leaveSettings() }
            s.render(4)
            assertEquals(Page.Home, s.app.navigator.current.page)
            s.click(s.button(EDIT_HOME))
            assertTrue(s.app.inSettings)
            assertEquals(SettingsKey.Home, s.app.settingsUi.page)
            s.onUi { s.app.leaveSettings() }
            s.render(4)
            s.click(s.button("Settings"))
            assertTrue(s.app.inSettings)
            assertEquals("Settings opens on the page last shown", SettingsKey.Home, s.app.settingsUi.page)
        }
    }

    @Test(timeout = 120_000)
    fun thePanelButtonsAreUnlitInSettingsAndShowThePanelOnLeaving() {
        A11yScene(folder.root).use { s ->
            s.onUi {
                s.app.play(s.app.library!!.index!!.songs, 0)
                s.app.showSidePanel(SidePanel.Lyrics)
                s.app.openSettings()
            }
            s.render(6)
            fun lyrics() = s.nodes(merged = true).first { it.name() == "Lyrics" && it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            assertEquals("no panel is drawn in Settings", ToggleableState.Off, lyrics().config.getOrNull(SemanticsProperties.ToggleableState))
            assertFalse(s.nodes().any { it.name() == "Close the panel" })
            s.click(lyrics())
            assertFalse("the button leaves Settings", s.app.inSettings)
            assertEquals(SidePanel.Lyrics, s.app.shownPanel)
            assertEquals(ToggleableState.On, lyrics().config.getOrNull(SemanticsProperties.ToggleableState))
            assertTrue(s.nodes().any { it.name() == "Close the panel" })
        }
    }
}
