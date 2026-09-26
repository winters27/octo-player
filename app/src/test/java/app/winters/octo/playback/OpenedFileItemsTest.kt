package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenedFileItemsTest {
    @Test
    fun aFileRoundTrips() {
        val uri = "content://com.example.files/document/primary%3AMusic%2FA%20song.flac"
        val id = openedFileId(uri, "A song: live", "The Band")
        assertTrue(isOpenedFile(id))
        assertEquals(OpenedFile(uri, "A song: live", "The Band"), openedFileOf(id))
    }

    @Test
    fun colonsAreEncodedSoThePartsSplitCleanly() {
        val id = openedFileId("file:///sdcard/Music/x.mp3", "One: Two", "Three: Four")
        assertEquals(3, id.removePrefix(OPENED_FILE_PREFIX).split(':').size)
    }

    @Test
    fun theTitleAndArtistMayBeEmpty() {
        assertEquals(OpenedFile("content://a/b", "", ""), openedFileOf(openedFileId(" content://a/b ")))
    }

    @Test
    fun otherIdsAreNotFiles() {
        assertFalse(isOpenedFile("radio:http%3A%2F%2Fhost:"))
        assertFalse(isOpenedFile("device:12"))
        assertNull(openedFileOf("find:abc"))
        // An id with no address cannot play.
        assertNull(openedFileOf("file::title:artist"))
    }
}
