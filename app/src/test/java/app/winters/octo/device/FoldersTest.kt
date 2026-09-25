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
}
