package app.winters.octo.device

import app.winters.octo.catalog.TestHooks
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A phone scan that changed the library is a swap like a server copy's: the
// persisted swap count goes up by one and the stamp moves by two, ending
// even. A scan that changed nothing asks only for a merge that is skipped,
// which moves the stamp and, with nothing to relink, not the count.
@RunWith(RobolectricTestRunner::class)
class PhoneSwapCountTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aChangedScanCountsAsASwapAndAQuietOneDoesNot() = runBlocking {
        val hooks = TestHooks()
        val merge = db.testMerge(null, hooks)
        val files = PhoneFiles.albums(files = 60, perAlbum = 10)
        val phone = db.phoneLibrary(files, merge)
        phone.rescan()
        val count = db.workDao().swapCount()
        val stamp = merge.swapStamp.value

        FakeMediaStore.current!!.files = PhoneFiles(61) { if (it < 60) files.file(it) else files.file(0).copy(id = 900, displayName = "new.flac", title = "New") }
        phone.rescan()
        assertEquals(count + 1, db.workDao().swapCount())
        assertEquals(stamp + 2, merge.swapStamp.value)
        assertEquals(listOf("swap", "swap"), hooks.events.toList())

        phone.rescan()
        assertEquals(count + 1, db.workDao().swapCount())
        assertEquals(stamp + 4, merge.swapStamp.value)
        assertEquals(1, hooks.skips.get())
    }
}
