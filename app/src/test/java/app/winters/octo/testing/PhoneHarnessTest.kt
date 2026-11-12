package app.winters.octo.testing

import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneHarnessTest {
    // The fake media library answers the phone's scan with every file, made
    // as the cursor reaches it, and a scan puts them in the library.
    @Test
    fun aPhoneScanReadsTheFakeMediaLibrary() = runBlocking {
        val files = PhoneFiles.albums(files = 30, perAlbum = 10, unknownAlbum = 5)
        val db = openTestCatalog()
        val phone = db.phoneLibrary(files)
        val listed = DeviceScanner(ApplicationProvider.getApplicationContext()).list()
        assertEquals(35, listed.size)
        assertEquals("Music/Artist 0/Album 0/", listed.first().folder)
        assertEquals("Song 0", listed.first().fallback.title)
        assertEquals("${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/1", listed.first().uri)

        phone.rescan()
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM track WHERE sourceId = ?", arrayOf<Any?>(DEVICE)).use {
            it.moveToFirst()
            assertEquals(35, it.getInt(0))
        }
        db.close()
    }
}
