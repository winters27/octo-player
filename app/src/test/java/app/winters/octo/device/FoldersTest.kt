package app.winters.octo.device

import org.junit.Assert.assertEquals
import org.junit.Test

class FoldersTest {
    private fun file(id: Long, folder: String?) = DeviceFile(
        id = id,
        uri = "content://media/external/audio/media/$id",
        fileName = "$id.flac",
        folder = folder,
        modifiedAt = 0,
        sizeBytes = 0,
        durationMs = 0,
        addedAtSeconds = 0,
        mimeType = null,
        fallback = FileTags(),
    )

    @Test
    fun topFolderIsTheFirstLevel() {
        assertEquals("Music", topFolder("Music/Kavinsky/Nightcall/"))
        assertEquals("Download", topFolder("Download/"))
        assertEquals("", topFolder(null))
    }

    @Test
    fun switchedOffFoldersAreLeftOut() {
        val files = listOf(file(1, "Music/A/"), file(2, "WhatsApp/Media/"), file(3, "Music/"))
        assertEquals(listOf(1L, 3L), files.withoutFolders(setOf("WhatsApp")).map { it.id })
        assertEquals(3, files.withoutFolders(emptySet()).size)
    }

    @Test
    fun folderListKeepsSwitchedOffFoldersAndCounts() {
        val files = listOf(file(1, "Music/A/"), file(2, "Music/B/"), file(3, "WhatsApp/"))
        val folders = files.folders(setOf("WhatsApp"))
        assertEquals(listOf("Music", "WhatsApp"), folders.map { it.name })
        assertEquals(listOf(2, 1), folders.map { it.songs })
        assertEquals(listOf(true, false), folders.map { it.included })
    }

    @Test
    fun pathsLeaveOutTheStorageWhenThereIsOnlyOne() {
        val files = listOf(file(1, "Music/Kavinsky/"), file(2, "Music/").copy(volume = "external_primary"))
        assertEquals(
            mapOf(1L to listOf("Music", "Kavinsky"), 2L to listOf("Music")),
            files.folderPaths(),
        )
    }

    @Test
    fun pathsStartWithTheStorageWhenThereAreSeveral() {
        val files = listOf(
            file(1, "Music/").copy(volume = "external_primary"),
            file(2, "Music/").copy(volume = "1a2b-3c4d"),
        )
        assertEquals(
            mapOf(1L to listOf("Internal storage", "Music"), 2L to listOf("SD card", "Music")),
            files.folderPaths(),
        )
    }

    @Test
    fun severalCardsAreToldApartByTheirIds() {
        assertEquals("SD card", storageName("1a2b-3c4d", cardCount = 1))
        assertEquals("SD card 1A2B-3C4D", storageName("1a2b-3c4d", cardCount = 2))
        assertEquals("Internal storage", storageName(null, cardCount = 2))
    }
}
