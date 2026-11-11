package app.winters.octo.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {
    @Test
    fun topFolderIsTheFirstLevel() {
        assertEquals("Music", topFolder("Music/Kavinsky/Nightcall/"))
        assertEquals("Download", topFolder("Download/"))
        assertEquals("", topFolder(null))
    }

    @Test
    fun switchedOffFoldersAreLeftOut() {
        val folders = listOf("Music/A/", "WhatsApp/Media/", "Music/")
        assertEquals(listOf("Music/A/", "Music/"), folders.filterNot { switchedOff(it, setOf("WhatsApp")) })
        assertTrue(folders.none { switchedOff(it, emptySet()) })
        assertFalse(switchedOff(null, setOf("WhatsApp")))
        assertTrue(switchedOff(null, setOf("")))
    }

    @Test
    fun folderListKeepsSwitchedOffFoldersAndCounts() {
        val counter = FolderCounter()
        listOf("Music/A/", "Music/B/", "WhatsApp/").forEach(counter::add)
        val folders = counter.folders(setOf("WhatsApp"))
        assertEquals(listOf("Music", "WhatsApp"), folders.map { it.name })
        assertEquals(listOf(2, 1), folders.map { it.songs })
        assertEquals(listOf(true, false), folders.map { it.included })
    }

    @Test
    fun foldersWithAsManySongsKeepTheOrderFirstSeen() {
        val counter = FolderCounter()
        listOf("Podcasts/", "Music/", "Download/", "Music/").forEach(counter::add)
        assertEquals(listOf("Music", "Podcasts", "Download"), counter.folders(emptySet()).map { it.name })
    }

    @Test
    fun pathsLeaveOutTheStorageWhenThereIsOnlyOne() {
        val paths = PhonePaths(listOf(null, "external_primary"))
        assertEquals(listOf("Music", "Kavinsky"), paths.of("Music/Kavinsky/", null))
        assertEquals(listOf("Music"), paths.of("Music/", "external_primary"))
    }

    @Test
    fun pathsStartWithTheStorageWhenThereAreSeveral() {
        val paths = PhonePaths(listOf("external_primary", "1a2b-3c4d"))
        assertEquals(listOf("Internal storage", "Music"), paths.of("Music/", "external_primary"))
        assertEquals(listOf("SD card", "Music"), paths.of("Music/", "1a2b-3c4d"))
    }

    @Test
    fun severalCardsAreToldApartByTheirIds() {
        assertEquals("SD card", storageName("1a2b-3c4d", cardCount = 1))
        assertEquals("SD card 1A2B-3C4D", storageName("1a2b-3c4d", cardCount = 2))
        assertEquals("Internal storage", storageName(null, cardCount = 2))
    }
}
