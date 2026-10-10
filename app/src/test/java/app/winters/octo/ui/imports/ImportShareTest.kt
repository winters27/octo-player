package app.winters.octo.ui.imports

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// "Share to Octo" for a list: which shares Import takes, what each becomes,
// and the name a file is sent under.
class ImportShareTest {
    private val send = "android.intent.action.SEND"

    @Test
    fun aSharedFileIsTakenWithItsType() {
        assertEquals(SharedImport.File("content://downloads/1", "text/csv"), sharedImportOf(send, "text/csv", "content://downloads/1", null, null))
        assertEquals(
            SharedImport.File("content://files/2", "text/comma-separated-values"),
            sharedImportOf(send, "text/comma-separated-values", "content://files/2", null, null),
        )
        assertEquals(SharedImport.File("content://files/3", "text/plain"), sharedImportOf(send, "Text/Plain; charset=utf-8", "content://files/3", null, null))
    }

    @Test
    fun aFileWinsOverTheTextSharedWithIt() {
        assertEquals(SharedImport.File("content://f", "text/plain"), sharedImportOf(send, "text/plain", "content://f", "Here is my playlist", "Gym"))
    }

    @Test
    fun sharedTextIsTheListItself() {
        assertEquals(
            SharedImport.Text("Massive Attack - Angel\nMGMT - Kids", "Road trip"),
            sharedImportOf<String>(send, "text/plain", null, "Massive Attack - Angel\nMGMT - Kids", " Road trip "),
        )
        assertEquals(SharedImport.Text("Massive Attack - Angel", null), sharedImportOf<String>(send, "text/plain", null, "Massive Attack - Angel", "  "))
    }

    @Test
    fun otherSharesAreNotImports() {
        assertNull(sharedImportOf<String>(send, "text/plain", null, "   ", null))
        assertNull(sharedImportOf<String>(send, "text/plain", null, null, null))
        assertNull(sharedImportOf(send, "image/png", "content://photo", null, null))
        assertNull(sharedImportOf(send, null, "content://x", null, null))
        assertNull(sharedImportOf("android.intent.action.VIEW", "text/csv", "content://x", null, null))
        assertNull(sharedImportOf("android.intent.action.SEND_MULTIPLE", "text/csv", "content://x", null, null))
        assertNull(sharedImportOf(null, "text/csv", "content://x", null, null))
    }

    @Test
    fun aFileIsNamedSoTheServerKnowsHowToReadIt() {
        assertEquals("My Spotify Library.csv", importFileName("My Spotify Library.csv", "text/plain"))
        assertEquals("export.zip", importFileName("export.zip", null))
        assertEquals("Liked.csv", importFileName("Liked", "text/csv"))
        assertEquals("Liked.csv", importFileName("Liked", "text/comma-separated-values"))
        assertEquals("notes.txt.txt", importFileName("notes.txt.txt", "text/plain"))
        assertEquals("Shared list.txt", importFileName(null, "text/plain"))
        assertEquals("Shared list.json", importFileName("  ", "application/json"))
        assertEquals("song.mp3.txt", importFileName("song.mp3", null))
    }

    @Test
    fun aFileIsReadUpToTheLimitAndNoFurther() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, readAtMost(ByteArrayInputStream(bytes), 200_000))
        assertNull(readAtMost(ByteArrayInputStream(bytes), 199_999))
        assertArrayEquals(ByteArray(0), readAtMost(ByteArrayInputStream(ByteArray(0)), 10))
    }

    @Test
    fun thePickerOffersEveryKindTheServerReads() {
        assertEquals(
            listOf("text/plain", "text/csv", "text/comma-separated-values", "application/zip", "application/json"),
            IMPORT_PICK_TYPES.toList(),
        )
    }
}
