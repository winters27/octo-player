package app.winters.octo.desktop.system

import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.ui.CollectionAction
import app.winters.octo.desktop.ui.albumMenuActions
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JumpListTest {
    @get:Rule val folder = TemporaryFolder()

    private val okComputer = JumpTarget(JumpKind.Album, "al-1", "OK Computer", "Radiohead")
    private val kidA = JumpTarget(JumpKind.Album, "al-2", "Kid A", "Radiohead")
    private val roadTrip = JumpTarget(JumpKind.Playlist, "pl 7/x", "Road trip")
    private val lossless = JumpTarget(JumpKind.LiveList, "live-3", "Lossless favorites")

    @Test
    fun playLinksNameWhatToPlay() {
        assertEquals("octo://play/album/al-1", okComputer.link)
        assertEquals("octo://play/playlist/pl+7%2Fx", roadTrip.link)
        assertEquals(JumpKind.Album to "al-1", playLinkOf(okComputer.link))
        assertEquals(JumpKind.Playlist to "pl 7/x", playLinkOf(roadTrip.link), "an id with a slash and a space comes back whole")
        assertEquals(JumpKind.LiveList to "live-3", playLinkOf("OCTO://Play/LiveList/live-3/"))
        assertNull(playLinkOf("octo://album/al-1"), "a page link, not a play link")
        assertNull(playLinkOf("octo://play/artist/ar-1"))
        assertNull(playLinkOf("octo://play/album/"))
        assertNull(playLinkOf("octo://play/album/a/b"))
    }

    @Test
    fun tipsSayWhatAClickDoes() {
        assertEquals("Play OK Computer by Radiohead", okComputer.tip)
        assertEquals("Play Untitled", JumpTarget(JumpKind.Album, "x", "Untitled").tip)
        assertEquals("Play the playlist Road trip", roadTrip.tip)
        assertEquals("Play the live list Lossless favorites", lossless.tip)
    }

    @Test
    fun aJumpListLaunchReachesTheRunningOctoWhole() {
        // The second launch's command line as it crosses to the running one.
        val secret = ByteArray(32) { it.toByte() }
        val args = listOf(roadTrip.link)
        val sent = SingleInstance.encodeMessage(absoluteLaunchArgs(args), secret)
        val read = SingleInstance.readMessage(DataInputStream(ByteArrayInputStream(sent)), secret)
        assertEquals(args, read, "a link is not taken for a file")
        assertEquals(listOf(LaunchRequest.OpenLink(roadTrip.link)), parseLaunchArgs(read!!))
        assertEquals(JumpKind.Playlist to "pl 7/x", playLinkOf((parseLaunchArgs(read).single() as LaunchRequest.OpenLink).link))
    }

    @Test
    fun anAlbumsMenuOffersTheJumpListWhereThereIsOne() {
        val keeping = albumMenuActions(jumpList = true)[1]
        assertEquals(listOf(CollectionAction.AddToPlaylist, CollectionAction.Favourite, CollectionAction.JumpList), keeping)
        assertFalse(CollectionAction.JumpList in albumMenuActions().flatten())
        assertFalse(CollectionAction.JumpList in albumMenuActions(outside = true, jumpList = true).flatten(), "not an album found online")
    }

    @Test
    fun onlyAPlayOrATrayStartLeavesTheWindowBe() {
        assertFalse(launchWantsWindow(listOf(okComputer.link)))
        assertFalse(launchWantsWindow(listOf(TRAY_FLAG)))
        assertTrue(launchWantsWindow(emptyList()), "starting Octo again brings it forward")
        assertTrue(launchWantsWindow(listOf("octo://album/al-1")))
        assertTrue(launchWantsWindow(listOf(okComputer.link, "C:\\Music\\a.flac")))
    }

    private fun song(id: String, album: String?, albumId: String?) =
        Song(id, "Song $id", album = album, albumId = albumId, artist = "Radiohead")

    @Test
    fun whatWasPlayedFromAPageIsSomethingTheJumpListCanPlayAgain() {
        val names = mapOf("pl-1" to "Road trip", "live-3" to "Lossless favorites")
        val album = listOf(song("1", "OK Computer", "al-1"), song("2", "OK Computer", "al-1"))
        assertEquals(JumpTarget(JumpKind.Album, "al-1", "OK Computer", "Radiohead"), jumpTargetFor(Page.Home, album, names::get))
        // One song from an album counts only on the album's own page.
        assertNull(jumpTargetFor(Page.Search, album.take(1), names::get))
        assertEquals("al-1", jumpTargetFor(Page.Album("al-1"), album.take(1), names::get)?.id)
        // Songs from many albums are no album.
        assertNull(jumpTargetFor(Page.Songs, album + song("3", "Kid A", "al-2"), names::get))
        assertNull(jumpTargetFor(Page.Songs, listOf(song("4", null, null), song("5", null, null)), names::get))
        assertEquals(JumpTarget(JumpKind.Playlist, "pl-1", "Road trip"), jumpTargetFor(Page.Playlist("pl-1"), album, names::get))
        assertEquals(JumpTarget(JumpKind.LiveList, "live-3", "Lossless favorites"), jumpTargetFor(Page.LiveList("live-3"), album, names::get))
        assertNull(jumpTargetFor(Page.Playlist("gone"), album, names::get), "a list with no name yet")
    }

    @Test
    fun theLatestPlayedComesFirstAndPinnedOnesComeBeforeThem() {
        val entries = JumpEntries()
            .played(okComputer)
            .played(roadTrip)
            .played(kidA)
            .played(okComputer.copy(name = "OK Computer OKNOTOK"))
            .pinning(kidA, pin = true)
        assertEquals(listOf("al-1", "al-2", "pl 7/x"), entries.recent.map { it.id })
        assertEquals("OK Computer OKNOTOK", entries.recent.first().name, "the newest name")
        val items = entries.items()
        assertEquals(listOf(JumpEntries.PINNED_HEADING, JumpEntries.RECENT_HEADING, JumpEntries.RECENT_HEADING), items.map { it.heading })
        assertEquals(listOf("Kid A", "OK Computer OKNOTOK", "Road trip"), items.map { it.target.name }, "a pinned album is not listed twice")
        assertTrue(entries.isPinned(kidA.copy(name = "renamed")))
        assertFalse(entries.pinning(kidA, pin = false).isPinned(kidA))
        // Playing a pinned album brings its name up to date there too.
        assertEquals("Kid A (2000)", entries.played(kidA.copy(name = "Kid A (2000)")).pinned.single().name)
    }

    @Test
    fun theJumpListKeepsAFewAndShowsFewer() {
        var entries = JumpEntries()
        repeat(30) { entries = entries.played(JumpTarget(JumpKind.Album, "a$it", "Album $it")) }
        repeat(30) { entries = entries.pinning(JumpTarget(JumpKind.Album, "p$it", "Pinned $it"), pin = true) }
        assertEquals(JumpEntries.RECENT_KEPT, entries.recent.size)
        assertEquals(JumpEntries.PINNED_KEPT, entries.pinned.size)
        assertEquals("p29", entries.pinned.last().id, "the latest pin is kept")
        assertEquals(JumpEntries.RECENT_SHOWN, entries.items().count { it.heading == JumpEntries.RECENT_HEADING })
    }

    @Test
    fun whatTheListenerTookOutOfTheJumpListLeavesTheRecordToo() {
        val entries = JumpEntries().played(okComputer).played(roadTrip).pinning(kidA, pin = true)
        val left = entries.without(listOf(kidA.link, roadTrip.link))
        assertEquals(emptyList(), left.pinned)
        assertEquals(listOf(okComputer), left.recent)
        assertEquals(entries, entries.without(emptyList()))
        // The library's answer: the links a line, in a buffer with room to spare.
        val answer = "${kidA.link}\n${roadTrip.link}"
        assertEquals(listOf(kidA.link, roadTrip.link), removedLinks(answer.toByteArray() + ByteArray(8), answer.length.toLong()))
        assertEquals(listOf(kidA.link), removedLinks(kidA.link.toByteArray(), 500), "an answer longer than the buffer keeps what came")
    }

    @Test
    fun theEntriesGoToTheLibraryOneALineWithNoStrayTabs() {
        val odd = JumpTarget(JumpKind.Album, "x", "Tabs\tand\nlines", "Someone")
        val text = jumpListText(JumpEntries().played(odd).played(okComputer).items())
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertEquals(listOf("Recently played", "OK Computer", "octo://play/album/al-1", "Play OK Computer by Radiohead", "", "0"), lines[0].split('\t'))
        assertEquals("Tabs and lines", lines[1].split('\t')[1])
        assertEquals(6, lines[1].split('\t').size)
    }

    @Test
    fun theRecordSurvivesARestartAndNonsenseStartsEmpty() {
        val entries = JumpEntries().played(okComputer).played(lossless).pinning(roadTrip, pin = true)
        assertEquals(entries, decodeJumpEntries(encodeJumpEntries(entries)))
        assertEquals(JumpEntries(), decodeJumpEntries("{not json"))
        assertEquals(JumpEntries(), decodeJumpEntries("""{"version":1,"recent":[{"kind":"Radio","id":"r","name":"R"}]}"""))
    }

    @Test
    fun theStoreKeepsEachAccountsRecordInItsFile() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val store = JumpListStore(this, io)
        val file = File(folder.root, "listening/abc/${JumpListStore.FILE_NAME}")
        store.open(file)
        store.played(okComputer)
        store.setPinned(kidA, pin = true)
        advanceUntilIdle()
        assertTrue(file.isFile)
        val again = JumpListStore(this, io).apply { open(file) }
        assertEquals(listOf(okComputer), again.entries.value.recent)
        assertEquals(listOf(kidA), again.entries.value.pinned)
        again.forget(listOf(okComputer.link))
        advanceUntilIdle()
        assertEquals(emptyList(), JumpListStore(this, io).apply { open(file) }.entries.value.recent)
        // Signed out: an empty record, kept nowhere.
        again.open(null)
        assertEquals(JumpEntries(), again.entries.value)
    }
}
