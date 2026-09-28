package app.winters.octo.desktop.nav

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    @Test
    fun backAndForwardWalkTheHistory() {
        val nav = Navigator()
        nav.go(Page.Albums)
        nav.go(Page.Album("a1"))
        assertTrue(nav.back())
        assertEquals(Page.Albums, nav.current.page)
        assertTrue(nav.back())
        assertEquals(Page.Home, nav.current.page)
        assertFalse(nav.back())
        assertTrue(nav.forward())
        assertTrue(nav.forward())
        assertEquals(Page.Album("a1"), nav.current.page)
        assertFalse(nav.forward())
    }

    @Test
    fun goingSomewhereNewDropsWhatWasAhead() {
        val nav = Navigator()
        nav.go(Page.Albums)
        nav.go(Page.Artists)
        nav.back()
        nav.go(Page.Songs)
        assertFalse(nav.canGoForward)
        nav.back()
        assertEquals(Page.Albums, nav.current.page)
    }

    @Test
    fun goingToThePageAlreadyShownAddsNothing() {
        val nav = Navigator()
        nav.go(Page.Albums)
        nav.go(Page.Albums)
        assertEquals(1 to 0, nav.depth)
    }

    @Test
    fun aPageOpenedFromTheSidebarKeepsItsItemLit() {
        val nav = Navigator()
        nav.go(Page.Albums)
        nav.go(Page.Album("a1"))
        assertEquals(SidebarItem.Top(Page.Albums), nav.sidebarItem)
        nav.go(Page.Artist("r1"))
        assertEquals(SidebarItem.Top(Page.Albums), nav.sidebarItem)
        nav.go(Page.Playlist("p1"))
        assertEquals(SidebarItem.PlaylistItem("p1"), nav.sidebarItem)
        nav.go(Page.Album("a2"))
        assertEquals(SidebarItem.PlaylistItem("p1"), nav.sidebarItem)
        nav.back()
        nav.back()
        nav.back()
        assertEquals(SidebarItem.Top(Page.Albums), nav.sidebarItem)
    }

    @Test
    fun eachVisitRemembersItsOwnScroll() {
        val nav = Navigator()
        nav.go(Page.Albums)
        val first = nav.current
        nav.keepScroll(first, ScrollSpot(40, 12))
        nav.go(Page.Album("a1"))
        nav.go(Page.Albums)
        assertEquals("a new visit starts at the top", ScrollSpot(), nav.scrollOf(nav.current))
        nav.back()
        nav.back()
        assertEquals(ScrollSpot(40, 12), nav.scrollOf(nav.current))
    }

    @Test
    fun aVisitKeepsItsTabAndEachOfItsListsScroll() {
        val nav = Navigator()
        nav.go(Page.Favourites)
        val favourites = nav.current
        nav.keepTab(favourites, "Albums")
        nav.keepScroll(favourites, ScrollSpot(3, 0))
        nav.keepScroll(favourites, ScrollSpot(9, 4), "grid")
        nav.go(Page.Album("a1"))
        nav.back()
        assertEquals("Albums", nav.tabOf(nav.current))
        assertEquals(ScrollSpot(3, 0), nav.scrollOf(nav.current))
        assertEquals(ScrollSpot(9, 4), nav.scrollOf(nav.current, "grid"))
        nav.go(Page.Songs)
        nav.go(Page.Favourites)
        assertNull("a new visit starts on the first tab", nav.tabOf(nav.current))
    }

    @Test
    fun startingOverForgetsEveryPageAndScroll() {
        val nav = Navigator()
        nav.go(Page.Albums)
        val albums = nav.current
        nav.keepScroll(albums, ScrollSpot(40, 12))
        nav.go(Page.Album("a1"))
        nav.back()
        nav.startOver()
        assertEquals(Page.Home, nav.current.page)
        assertEquals(0 to 0, nav.depth)
        nav.keepScroll(albums, ScrollSpot(5, 5))
        nav.go(Page.Albums)
        assertEquals(ScrollSpot(), nav.scrollOf(nav.current))
        assertEquals("a visit from before is not kept", ScrollSpot(), nav.scrollOf(albums))
    }

    @Test
    fun aLongHistoryForgetsItsOldestPages() {
        val nav = Navigator(limit = 5)
        repeat(10) { nav.go(Page.Album("a$it")) }
        assertEquals(4 to 0, nav.depth)
    }

    @Test
    fun onlyTopLevelPagesAreInTheSidebar() {
        assertNull(sidebarItemOf(Page.Album("a")))
        assertNull(sidebarItemOf(Page.Genre("Rock")))
        assertEquals(SidebarItem.Top(Page.Settings), sidebarItemOf(Page.Settings))
    }
}

class ShortcutsTest {
    private fun on(key: Key, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false, mac: Boolean = false, typing: Boolean = false) =
        shortcutFor(KeyPress(key, ctrl = ctrl, alt = alt, meta = meta), mac, typing)

    @Test
    fun theListedShortcutsDoWhatTheySay() {
        assertEquals(Shortcut.PlayPause, on(Key.Spacebar))
        assertEquals(Shortcut.SeekBack, on(Key.DirectionLeft))
        assertEquals(Shortcut.SeekForward, on(Key.DirectionRight))
        assertEquals(Shortcut.VolumeUp, on(Key.DirectionUp))
        assertEquals(Shortcut.VolumeDown, on(Key.DirectionDown))
        assertEquals(Shortcut.Search, on(Key.F, ctrl = true))
        assertEquals(Shortcut.Lyrics, on(Key.L, ctrl = true))
        assertEquals(Shortcut.Settings, on(Key.Comma, ctrl = true))
        assertEquals(Shortcut.Back, on(Key.DirectionLeft, alt = true))
        assertEquals(Shortcut.Forward, on(Key.DirectionRight, alt = true))
    }

    @Test
    fun onAMacCommandTakesCtrlsPlace() {
        assertEquals(Shortcut.Search, on(Key.F, meta = true, mac = true))
        assertNull(on(Key.F, ctrl = true, mac = true))
        assertEquals(Shortcut.Back, on(Key.LeftBracket, meta = true, mac = true))
        assertNull("Windows keeps Ctrl", on(Key.F, meta = true))
    }

    @Test
    fun whileTypingSpaceAndArrowsBelongToTheField() {
        assertNull(on(Key.Spacebar, typing = true))
        assertNull(on(Key.DirectionLeft, typing = true))
        assertEquals(Shortcut.Search, on(Key.F, ctrl = true, typing = true))
        assertEquals(Shortcut.CloseLayer, on(Key.Escape, typing = true))
    }

    @Test
    fun whileTypingAltArrowsMoveByWordsOnEverySystem() {
        for (mac in listOf(false, true)) {
            assertNull(on(Key.DirectionLeft, alt = true, mac = mac, typing = true))
            assertNull(on(Key.DirectionRight, alt = true, mac = mac, typing = true))
            assertEquals(Shortcut.Back, on(Key.DirectionLeft, alt = true, mac = mac))
        }
        assertEquals(Shortcut.Back, on(Key.LeftBracket, meta = true, mac = true, typing = true))
    }

    @Test
    fun modifiedArrowsAreNotSeeks() {
        assertNull(on(Key.DirectionLeft, ctrl = true))
        assertNull(on(Key.Spacebar, ctrl = true))
    }

    @Test
    fun theSettingsListNamesThisSystemsKeys() {
        assertTrue(shortcutList(mac = true).any { it.second == "Cmd+F or Cmd+K" })
        assertTrue(shortcutList(mac = false).any { it.second == "Ctrl+F or Ctrl+K" })
    }

    @Test
    fun aTableWithTheKeyboardKeepsItsKeys() {
        fun inList(key: Key) = shortcutFor(KeyPress(key), mac = false, typing = false, list = true)
        assertNull(inList(Key.DirectionUp))
        assertNull(inList(Key.DirectionDown))
        assertNull(inList(Key.Enter))
        // Seeking with Left and Right, and Space, still work over a list.
        assertEquals(Shortcut.SeekForward, inList(Key.DirectionRight))
        assertEquals(Shortcut.PlayPause, inList(Key.Spacebar))
    }

    @Test
    fun theFramesShortcuts() {
        assertEquals(Shortcut.Search, on(Key.K, ctrl = true))
        assertEquals(Shortcut.Info, on(Key.I, ctrl = true))
        assertEquals(Shortcut.Sidebar, on(Key.B, ctrl = true))
        assertEquals(Shortcut.MiniPlayer, on(Key.M, meta = true, mac = true))
        assertNull("a plain letter is typing, not a shortcut", on(Key.B))
    }
}
