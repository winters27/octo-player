package app.winters.octo.desktop.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// List files dropped on the Import page, and where its file window starts.
class ImportFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyListFilesOnThisComputerAreTakenFromADrop() {
        val csv = folder.newFile("My Spotify Library.csv")
        val zip = folder.newFile("export.ZIP")
        val dropped = droppedImportFiles(
            listOf(
                csv.toURI().toString(),
                zip.path,
                folder.newFile("song.flac").path,
                "https://www.tunemymusic.com/list.csv",
            ),
        )
        assertEquals(listOf(csv.canonicalFile, zip.canonicalFile), dropped.map(File::getCanonicalFile))
    }

    @Test
    fun theFileWindowStartsInDownloadsWhenThereIsOne() {
        val home = folder.newFolder("home")
        assertNull(downloadsFolder(home.path))
        val downloads = File(home, "Downloads").apply { mkdirs() }
        assertEquals(downloads, downloadsFolder(home.path))
    }
}
