package app.winters.octo.desktop

import androidx.compose.ui.unit.IntOffset
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.SettingsPlace
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.nav.soundSectionPlace
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.search.commandsFor
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.ui.pillShows
import app.winters.octo.lyrics.OnlineLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The ways into and out of Settings: its own page and row, Back and
// Escape, Settings' shortcut, the side panel's shortcuts and buttons, and
// the command search.
class SettingsRoutesTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun app(): AppState {
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        return AppState(
            settings,
            Accounts(settings, SessionOnlySecrets(), http),
            http,
            scope,
            DesktopOs.Windows,
            SilentPlayer(),
            OnlineLyrics(http, server.address.toHttpUrl()),
            restored = server.connection(),
        )
    }

    private fun AppState.openMenu() = popups.showAt(IntOffset.Zero) { }

    @Test
    fun openingSettingsGoesToItsPageAndRowOrStaysAndMoves() {
        val app = app()
        app.fullPlayer = true
        app.openMenu()
        app.openSettings(SettingsKey.Sound, "eq")
        assertTrue(app.inSettings)
        assertFalse("the full player closes", app.fullPlayer)
        assertFalse("a menu closes", app.popups.open)
        assertEquals(SettingsKey.Sound, app.settingsUi.page)
        assertEquals("eq", app.settingsUi.target?.row)
        val first = app.settingsUi.target
        app.openSettings(SettingsKey.Sound, "eq")
        assertEquals("already there, no new visit", 1 to 0, app.navigator.depth)
        assertTrue("the same row asked again is asked anew", app.settingsUi.target != first)
        app.openSettings()
        assertEquals("with no page, the page last shown", SettingsKey.Sound, app.settingsUi.page)
        assertNull(app.settingsUi.target)
    }

    @Test
    fun settingsFirstOpensOnPlayback() {
        val app = app()
        app.openSettings()
        assertEquals(SettingsKey.Playback, app.settingsUi.page)
    }

    @Test
    fun leavingSettingsGoesBackWhereItWasOpenedFrom() {
        val app = app()
        app.navigator.go(Page.Albums)
        app.openSettings(SettingsKey.Look)
        app.leaveSettings()
        assertEquals(Page.Albums, app.navigator.current.page)
    }

    @Test
    fun leavingSettingsWithNothingBehindItGoesHome() {
        val app = app()
        app.navigator.replace(Page.Settings)
        assertEquals(0 to 0, app.navigator.depth)
        app.leaveSettings()
        assertEquals(Page.Home, app.navigator.current.page)
        assertEquals(0 to 0, app.navigator.depth)
    }

    @Test
    fun leavingSettingsClearsItsSearchAndRow() {
        val app = app()
        app.openSettings(SettingsKey.Sound, "eq")
        app.settingsUi.query = "bass"
        app.navigator.go(Page.Songs)
        assertEquals("", app.settingsUi.query)
        assertNull(app.settingsUi.target)
        assertEquals("the page is kept for next time", SettingsKey.Sound, app.settingsUi.page)
    }

    @Test
    fun settingsShortcutOpensAndLeavesSettings() {
        val app = app()
        app.navigator.go(Page.Albums)
        assertTrue(app.perform(Shortcut.Settings))
        assertTrue(app.inSettings)
        assertTrue(app.perform(Shortcut.Settings))
        assertEquals(Page.Albums, app.navigator.current.page)
    }

    @Test
    fun settingsShortcutClosesAMenuFirst() {
        val app = app()
        app.openSettings()
        app.openMenu()
        assertTrue(app.perform(Shortcut.Settings))
        assertFalse(app.popups.open)
        assertFalse("and still leaves", app.inSettings)
    }

    @Test
    fun escapeClosesAMenuBeforeLeavingSettings() {
        val app = app()
        app.openSettings()
        app.openMenu()
        assertTrue(app.perform(Shortcut.CloseLayer))
        assertFalse(app.popups.open)
        assertTrue(app.inSettings)
    }

    @Test
    fun escapeClosesTheFullPlayerBeforeLeavingSettings() {
        val app = app()
        app.openSettings()
        app.fullPlayer = true
        assertTrue(app.perform(Shortcut.CloseLayer))
        assertFalse(app.fullPlayer)
        assertTrue(app.inSettings)
    }

    @Test
    fun escapeInTheOpenSearchBoxIsLeftToIt() {
        val app = app()
        app.openSettings()
        app.omnibox.open = true
        // The box closes its own list, whatever the typing state says.
        assertFalse(app.perform(Shortcut.CloseLayer))
        assertTrue(app.inSettings)
    }

    @Test
    fun escapeClosesTheSidePanelHoldingTheKeyboard() {
        val app = app()
        app.showSidePanel(SidePanel.Queue)
        app.panelHasKeyboard = true
        assertTrue(app.perform(Shortcut.CloseLayer))
        assertNull(app.sidePanel)
    }

    @Test
    fun theHiddenPanelLetsGoOfTheKeyboardAndEscapeLeavesSettingsInstead() {
        val app = app()
        app.showSidePanel(SidePanel.Queue)
        app.panelHasKeyboard = true
        app.openSettings()
        assertFalse("entering Settings takes the keyboard off the panel", app.panelHasKeyboard)
        app.panelHasKeyboard = true
        assertTrue(app.perform(Shortcut.CloseLayer))
        assertFalse(app.inSettings)
        assertEquals("the panel waits, not closed", SidePanel.Queue, app.shownPanel)
    }

    @Test
    fun escapeInSettingsSearchClearsItThenLeaves() {
        val app = app()
        app.openSettings()
        app.settingsUi.searchHasKeyboard = true
        app.settingsUi.query = "eq"
        assertTrue(app.perform(Shortcut.CloseLayer, typing = true))
        assertEquals("", app.settingsUi.query)
        assertTrue(app.inSettings)
        assertTrue(app.perform(Shortcut.CloseLayer, typing = true))
        assertFalse(app.inSettings)
    }

    @Test
    fun escapeInAnotherTextFieldIsLeftToTheField() {
        val app = app()
        app.openSettings()
        assertFalse(app.perform(Shortcut.CloseLayer, typing = true))
        assertTrue(app.inSettings)
    }

    @Test
    fun escapeLeavesSettingsLast() {
        val app = app()
        app.navigator.go(Page.Albums)
        app.openSettings()
        assertTrue(app.perform(Shortcut.CloseLayer))
        assertEquals(Page.Albums, app.navigator.current.page)
        assertFalse("outside Settings with nothing open, Escape is not taken", app.perform(Shortcut.CloseLayer))
    }

    @Test
    fun foldingTheSidebarIsIgnoredInSettings() {
        val app = app()
        app.openSettings()
        assertFalse(app.perform(Shortcut.Sidebar))
        assertFalse(app.settings.current.frame.sidebarRail)
        app.leaveSettings()
        assertTrue(app.perform(Shortcut.Sidebar))
        assertTrue(app.settings.current.frame.sidebarRail)
    }

    @Test
    fun theFoldCommandIsHiddenInSettings() {
        val app = app()
        assertTrue(commandsFor(app).any { it.title == "Fold the sidebar to icons" })
        app.openSettings()
        assertFalse(commandsFor(app).any { it.title.contains("sidebar") })
    }

    @Test
    fun eachPanelShortcutInSettingsLeavesAndShowsThePanel() {
        for ((shortcut, panel) in listOf(Shortcut.Lyrics to SidePanel.Lyrics, Shortcut.Queue to SidePanel.Queue, Shortcut.Info to SidePanel.Info)) {
            val app = app()
            app.openSettings()
            assertTrue(app.perform(shortcut))
            assertFalse("$shortcut leaves Settings", app.inSettings)
            assertEquals(panel, app.shownPanel)
        }
    }

    @Test
    fun togglingThePanelKeptWhileInSettingsShowsIt() {
        val app = app()
        app.showSidePanel(SidePanel.Lyrics)
        app.openSettings()
        assertNull("not drawn in Settings", app.shownPanel)
        app.toggleSidePanel(SidePanel.Lyrics)
        assertFalse(app.inSettings)
        assertEquals(SidePanel.Lyrics, app.shownPanel)
    }

    @Test
    fun theDownloadsPillShowsInSettingsAndItsPanelLeavesSettings() {
        val app = app()
        app.showSidePanel(SidePanel.Downloads)
        assertFalse("the panel is drawn, so the pill steps aside", pillShows(app, 1))
        app.openSettings()
        assertTrue(pillShows(app, 1))
        app.showSidePanel(SidePanel.Downloads)
        assertFalse(app.inSettings)
        assertEquals(SidePanel.Downloads, app.shownPanel)
    }

    @Test
    fun closeTheSidePanelIsOfferedOnlyWhileItIsDrawn() {
        val app = app()
        app.showSidePanel(SidePanel.Queue)
        assertTrue(commandsFor(app).any { it.title == "Close the side panel" })
        app.openSettings()
        assertFalse(commandsFor(app).any { it.title == "Close the side panel" })
    }

    @Test
    fun theOldSoundSectionsLandOnTheirNewPlaces() {
        for (key in listOf("output", "eq", "loudness", "balance", "crossfeed", "correction")) {
            assertEquals(SettingsPlace(SettingsKey.Sound, key), soundSectionPlace(key))
        }
        assertEquals(SettingsPlace(SettingsKey.Playback, "crossfade"), soundSectionPlace("playback"))
    }

    @Test
    fun theCommandSearchOpensEachSettingsPageAndRow() {
        val app = app()
        app.navigator.go(Page.Albums)
        fun run(title: String) = commandsFor(app).first { it.title == title && it.group == "Settings" }.run()
        for (page in settingsPages(windows = true, discord = false)) {
            run(page.name)
            assertTrue(app.inSettings)
            assertEquals(page.key, app.settingsUi.page)
        }
        run("Crossfade")
        assertEquals(SettingsKey.Playback to "crossfade", app.settingsUi.page to app.settingsUi.target?.row)
        run("Equalizer")
        assertEquals(SettingsKey.Sound to "eq", app.settingsUi.page to app.settingsUi.target?.row)
        run("Accent color")
        assertEquals(SettingsKey.Look, app.settingsUi.page)
        assertTrue("colour still finds it", commandsFor(app).first { it.title == "Accent color" }.words.contains("colour"))
        app.leaveSettings()
        assertEquals(Page.Albums, app.navigator.current.page)
    }
}
