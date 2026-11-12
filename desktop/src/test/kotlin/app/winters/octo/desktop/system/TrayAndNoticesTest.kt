package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.DesktopOs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayAndNoticesTest {
    private fun now(key: Long = 1, playing: Boolean = true, canNext: Boolean = true, title: String = "Title", artist: String = "Artist") =
        NowPlaying(key, "s$key", title, artist, "Album", "", 200_000, null, null, null, emptyList(), playing, canPrevious = true, canNext = canNext)

    private fun actions(menu: List<TrayEntry>) = menu.filterIsInstance<TrayEntry.Action>().associateBy { it.action }

    @Test
    fun theMenuNamesTheSongAndOffersTheTransport() {
        val menu = trayMenu(now(), miniPlayerOpen = false)
        assertEquals(TrayEntry.Heading("Title · Artist"), menu.first())
        val byAction = actions(menu)
        assertEquals("Pause", byAction.getValue(TrayAction.PlayPause).label)
        assertEquals(true, byAction.getValue(TrayAction.Next).enabled)
        assertEquals("Show Octo", byAction.getValue(TrayAction.ShowWindow).label)
        assertEquals("Mini player", byAction.getValue(TrayAction.MiniPlayer).label)
        assertEquals("Quit Octo", byAction.getValue(TrayAction.Quit).label)
        assertEquals("the order of the menu", listOf(TrayAction.PlayPause, TrayAction.Next, TrayAction.Previous, TrayAction.ShowWindow, TrayAction.MiniPlayer, TrayAction.Quit), menu.filterIsInstance<TrayEntry.Action>().map { it.action })
    }

    @Test
    fun withNothingPlayingTheTransportIsOff() {
        val menu = trayMenu(null, miniPlayerOpen = true)
        assertEquals(TrayEntry.Heading("Nothing playing"), menu.first())
        val byAction = actions(menu)
        assertEquals("Play", byAction.getValue(TrayAction.PlayPause).label)
        listOf(TrayAction.PlayPause, TrayAction.Next, TrayAction.Previous).forEach { assertEquals(false, byAction.getValue(it).enabled) }
        assertEquals(true, byAction.getValue(TrayAction.Quit).enabled)
        assertEquals("Close the mini player", byAction.getValue(TrayAction.MiniPlayer).label)
    }

    @Test
    fun pausedOffersPlayAndTheLastSongHasNoNext() {
        val byAction = actions(trayMenu(now(playing = false, canNext = false), miniPlayerOpen = false))
        assertEquals("Play", byAction.getValue(TrayAction.PlayPause).label)
        assertEquals(false, byAction.getValue(TrayAction.Next).enabled)
        assertEquals(true, byAction.getValue(TrayAction.Previous).enabled)
    }

    @Test
    fun longNamesAreCut() {
        val heading = trayMenu(now(title = "A".repeat(80), artist = ""), false).first() as TrayEntry.Heading
        assertEquals(48, heading.text.length)
        assertEquals('…', heading.text.last())
        assertEquals("Octo", trayTooltip(null))
        assertEquals("Octo: Title · Artist", trayTooltip(now()))
    }

    @Test
    fun aNoticeComesOncePerSongWhilePlayingAndOnlyWhenWanted() {
        val notices = NowPlayingNotices()
        assertEquals("Title" to "Artist · Album", notices.noticeFor(now(1), enabled = true, windowInFront = false))
        assertNull("the same song again", notices.noticeFor(now(1), enabled = true, windowInFront = false))
        assertNull("a paused song", notices.noticeFor(now(2, playing = false), enabled = true, windowInFront = false))
        assertEquals("it plays now", "Title" to "Artist · Album", notices.noticeFor(now(2), enabled = true, windowInFront = false))
        assertNull("the window is in front", notices.noticeFor(now(3), enabled = true, windowInFront = true))
        assertNull("that song was seen", notices.noticeFor(now(3), enabled = true, windowInFront = false))
        assertNull("turned off", notices.noticeFor(now(4), enabled = false, windowInFront = false))
        assertNull(notices.noticeFor(null, enabled = true, windowInFront = false))
        assertEquals("Title" to "Album", notices.noticeFor(now(5, artist = ""), enabled = true, windowInFront = false))
    }

    // A tray that takes the icon while it `works`, and loses it when told.
    private class FakeTray(var works: Boolean = true) : TrayHost {
        var adds = 0
        var inTray = false

        override fun add(): Boolean {
            adds++
            if (!works) throw UnsupportedOperationException("no tray")
            inTray = true
            return true
        }

        override fun remove() {
            inTray = false
        }

        override fun present() = inTray
    }

    @Test
    fun aLostTrayIconIsMadeAgain() {
        val tray = FakeTray()
        val keeper = TrayKeeper(tray, retrySeconds = 2)
        keeper.check()
        assertTrue(keeper.showing)
        assertEquals("a new icon is looked at again", 2L, keeper.nextCheckSeconds())
        keeper.check()
        assertEquals("it stayed, so it is kept", 1, tray.adds)
        assertNull("then only the system's word wakes the keeper", keeper.nextCheckSeconds())
        // The tray goes away (GNOME reloading its extension) and comes back.
        tray.works = false
        tray.inTray = false
        keeper.told(false)
        keeper.check()
        assertFalse(keeper.showing)
        assertEquals("tried again later", 2L, keeper.nextCheckSeconds())
        tray.works = true
        keeper.told(true)
        keeper.check()
        assertTrue("back in", keeper.showing)
        assertEquals(3, tray.adds)
        // An icon taken out before it was looked at again is made again.
        tray.inTray = false
        keeper.check()
        assertEquals(4, tray.adds)
        assertTrue(keeper.showing)
    }

    @Test
    fun aTrayThatKeepsFailingIsGivenUpAndTheWindowComesBack() {
        val tray = FakeTray(works = false)
        var windowBack = 0
        val keeper = TrayKeeper(tray, retrySeconds = 1, tries = 3, onGaveUp = { windowBack++ })
        repeat(3) {
            keeper.check()
            assertEquals(1L, keeper.nextCheckSeconds())
        }
        assertEquals(0, windowBack)
        keeper.check()
        assertTrue(keeper.gaveUp)
        assertEquals("the window is brought back once", 1, windowBack)
        assertNull(keeper.nextCheckSeconds())
        repeat(3) { keeper.check() }
        assertEquals("no tries after the last", 3, tray.adds)
        assertFalse("closing the window quits instead of hiding it", closeKeepsInTray(setting = true, trayShowing = keeper.showing))
        // The system says a tray is there again: the tries start over.
        tray.works = true
        keeper.told(true)
        keeper.check()
        assertTrue(keeper.showing)
        assertFalse(keeper.gaveUp)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theKeeperTriesASetNumberOfSecondsApartAndWakesWhenTheTrayIsBack() = runTest {
        val tray = FakeTray(works = false)
        val keeper = TrayKeeper(tray, retrySeconds = 4, tries = 2)
        val keeping = launch { keeper.keep() }
        runCurrent()
        assertEquals(1, tray.adds)
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals("not before the wait", 1, tray.adds)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, tray.adds)
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(keeper.gaveUp)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("given up, it waits for the system's word", 2, tray.adds)
        tray.works = true
        keeper.hear(true)
        runCurrent()
        assertTrue(keeper.showing)
        keeping.cancel()
    }

    @Test
    fun closingTheWindowWithNoTrayIconDoesNotHideIt() {
        assertTrue(closeKeepsInTray(setting = true, trayShowing = true))
        assertFalse(closeKeepsInTray(setting = false, trayShowing = true))
        val none = TrayKeeper(FakeTray(works = false)).apply { check() }
        assertFalse("no icon could be made", closeKeepsInTray(setting = true, trayShowing = none.showing))
        val lost = TrayKeeper(FakeTray()).apply {
            check()
            told(false)
        }
        assertFalse("the icon went with the tray", closeKeepsInTray(setting = true, trayShowing = lost.showing))
    }

    @Test
    fun noticesGoOutOnEverySystemAndNeverThroughLinuxsTray() {
        assertEquals(NoticeRoute.Desktop, noticeRoute(DesktopOs.Linux, trayAvailable = true))
        assertEquals(NoticeRoute.Desktop, noticeRoute(DesktopOs.Linux, trayAvailable = false))
        assertEquals(NoticeRoute.Tray, noticeRoute(DesktopOs.Windows, trayAvailable = true))
        assertEquals(NoticeRoute.Tray, noticeRoute(DesktopOs.Mac, trayAvailable = true))
        assertNull(noticeRoute(DesktopOs.Windows, trayAvailable = false))
    }

    @Test
    fun theLibrarysEventNumbersBecomeEvents() {
        assertEquals(
            listOf(SystemEvent.Play, SystemEvent.Pause, SystemEvent.Toggle, SystemEvent.Next, SystemEvent.Previous, SystemEvent.Stop, SystemEvent.SeekTo(1234), SystemEvent.Sleep, SystemEvent.Wake),
            (1..9).map { systemEventOf(it, 1234) },
        )
        assertNull(systemEventOf(0, 0))
        assertEquals(SystemEvent.SeekTo(0), systemEventOf(7, -9))
    }
}
