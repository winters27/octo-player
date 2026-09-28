package app.winters.octo.desktop.system

import app.winters.octo.desktop.nav.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenedFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun theCommandLineTakesFilesAndLinks() {
        val song = folder.newFile("Song One.flac")
        val other = folder.newFile("Two.MP3")
        val requests = parseLaunchArgs(listOf("-psn_0_1234", song.path, "--flag", other.toPath().toUri().toString(), "octo://album/9"))
        assertEquals(
            listOf(LaunchRequest.OpenFiles(listOf(song.absoluteFile, other.absoluteFile)), LaunchRequest.OpenLink("octo://album/9")),
            requests,
        )
    }

    @Test
    fun missingAndNonAudioFilesAreLeftOut() {
        val text = folder.newFile("notes.txt")
        val gone = File(folder.root, "gone.mp3")
        assertEquals(emptyList<LaunchRequest>(), parseLaunchArgs(listOf(text.path, gone.path, "", "   ")))
    }

    @Test
    fun linksAreToldApartFromFilesWhateverTheirCase() {
        assertEquals(listOf(LaunchRequest.OpenLink("OCTO://playlist/3")), parseLaunchArgs(listOf("OCTO://playlist/3"), isFile = { true }))
    }

    @Test
    fun linksNameAPage() {
        assertEquals(Page.Album("al-1"), pageForLink("octo://album/al-1"))
        assertEquals(Page.Artist("ar 2"), pageForLink("octo://artist/ar%202"))
        assertEquals(Page.Playlist("p3"), pageForLink("octo://playlist/p3?from=share"))
        assertEquals(Page.Search, pageForLink("octo://search"))
        assertNull(pageForLink("octo://album/"))
        assertNull(pageForLink("octo://something-else/1"))
        assertNull(pageForLink("octo:"))
    }

    // The same form the phone app writes, so an opened file means the same
    // thing to both.
    @Test
    fun openedFileIdsMatchThePhonesForm() {
        val id = openedFileId("file:///C:/Music/A:B.flac", "A:B", "Artist")
        assertEquals("file:file%3A%2F%2F%2FC%3A%2FMusic%2FA%3AB.flac:A%3AB:Artist", id)
        assertEquals(OpenedFile("file:///C:/Music/A:B.flac", "A:B", "Artist"), openedFileOf(id))
        assertNull(openedFileOf("s-123"))
        assertNull(openedFileOf("file:"))
    }

    @Test
    fun aFileBecomesAOneOffSong() {
        val file = folder.newFile("Weightless (Live).opus")
        file.writeBytes(ByteArray(10))
        val song = openedFileSong(file)
        assertTrue(isOpenedFile(song.id))
        assertEquals("Weightless (Live)", song.title)
        assertEquals("opus", song.suffix)
        assertEquals(10L, song.size)
        assertEquals("the player finds the file again from the id", file.absoluteFile, openedFileOf(song.id)?.path)
    }

    @Test
    fun dropsKeepOnlyLocalAudioFiles() {
        val a = folder.newFile("a.m4a")
        val files = droppedAudioFiles(listOf(a.toPath().toUri().toString(), folder.newFile("b.jpg").toPath().toUri().toString(), "https://example.invalid/c.mp3"))
        assertEquals(listOf(a.absoluteFile), files.map { it.absoluteFile })
    }

    @Test
    fun namesLoseTheirType() {
        assertEquals("Song", titleFromFileName(File("Song.mp3")))
        assertEquals("a.b", titleFromFileName(File("a.b.flac")))
        assertEquals("Audio file", titleFromFileName(null))
    }
}
