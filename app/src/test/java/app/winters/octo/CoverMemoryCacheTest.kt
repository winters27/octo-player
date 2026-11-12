package app.winters.octo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The cover memory cache keeps to its share of the heap, so a big library
// leaves the rest of the app room.
@RunWith(RobolectricTestRunner::class)
class CoverMemoryCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mb = 1024L * 1024

    private fun loaderCacheBytes(heapBytes: Long): Long? =
        ImageLoader.Builder(context).coverMemoryCache(heapBytes).build().memoryCache?.maxSize

    @Test
    fun aBigHeapStopsAtTheCeiling() {
        assertEquals(32 * mb, loaderCacheBytes(256 * mb))
        assertEquals(32 * mb, loaderCacheBytes(512 * mb))
    }

    @Test
    fun aSmallHeapKeepsAnEighth() {
        assertEquals(16 * mb, loaderCacheBytes(128 * mb))
        assertEquals(24 * mb, coverMemoryBytes(192 * mb))
    }
}
