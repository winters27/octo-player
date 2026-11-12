package app.winters.octo.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import app.winters.octo.server.CopyWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The car's row for a first copy on its way, and when its lists hear of it.
@RunWith(RobolectricTestRunner::class)
class CarCopyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val coming = CopyWait(awaiting = true, phoneMusic = false)

    private fun run(step: CopyStep = CopyStep.Read, done: Long = 48_210, of: Long? = 200_000, fraction: Float? = 0.13f, arriving: Boolean = true) =
        CopyRunState(1, 1, "acc", "Home", arriving, true, step, done, of, fraction, "server:h|al-1", 0, 0, false)

    @Test
    fun theRowNeitherOpensNorPlaysAndSaysHowFarTheCopyIs() {
        val item = CarLook(context).copyStatus(run())
        val meta = item.mediaMetadata
        assertEquals(COPY_STATUS_ID, item.mediaId)
        assertNull(parseCarId(item.mediaId))
        assertEquals(false, meta.isBrowsable)
        assertEquals(false, meta.isPlayable)
        assertEquals("Your music is on its way", meta.displayTitle.toString())
        assertEquals("Reading your songs, 48,210 of 200,000", meta.subtitle.toString())
        assertNull(meta.artist)
        assertNotNull(meta.artworkUri)
        // Before a copy runs it still has a picture.
        assertNotNull(CarLook(context).copyStatus(null).mediaMetadata.artworkUri)
    }

    @Test
    fun openListsHearOfAStepOrAWholePercent() {
        val base = carCopyKey(coming, run())
        assertEquals(base, carCopyKey(coming, run(done = 48_700, fraction = 0.134f)))
        assertNotEquals(base, carCopyKey(coming, run(fraction = 0.14f)))
        assertNotEquals(base, carCopyKey(coming, run(step = CopyStep.Build)))
        assertNull(carCopyKey(CopyWait(awaiting = false, phoneMusic = false), run()))
    }

    @Test
    fun theEmptyLibraryListsAndHomeHearOfTheCopy() {
        for (node in listOf(CarNode.Home, CarNode.Songs, CarNode.Albums, CarNode.Artists, CarNode.Genres)) {
            assertTrue("$node", CarInput.Copy in carInputsOf(node))
        }
        assertFalse(CarInput.Copy in carInputsOf(CarNode.Radio))
    }

    @Test
    fun anEmptySongsListAnswersTheStatusRow() {
        val row = CarLook(context).copyStatus(run())
        assertEquals(row, carCopyAnswer(CarNode.Songs, 0) { row })
        assertNull(carCopyAnswer(CarNode.Songs, 3) { row })
        assertNull(carCopyAnswer(CarNode.Radio, 0) { row })
        assertNull(carCopyAnswer(CarNode.Albums, 0) { null })
    }

    // A first copy's music reaches the car at the swap, before likes and
    // playlists finish.
    @Test
    fun theLibraryIsToldAtAFirstCopysSwap() {
        assertTrue(carHoldsLibrary(true, run()))
        assertFalse(carHoldsLibrary(true, run(step = CopyStep.Settle)))
        assertTrue(carHoldsLibrary(true, run(step = CopyStep.Settle, arriving = false)))
        assertFalse(carHoldsLibrary(false, null))
    }
}
