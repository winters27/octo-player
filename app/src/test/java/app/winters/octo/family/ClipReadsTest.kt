package app.winters.octo.family

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A clip is read for a family link once, so Android's "pasted from your
// clipboard" shows at most once a clip, never on every return to Octo.
class ClipReadsTest {
    @Test
    fun aClipIsReadOnceThenNotAgain() {
        assertTrue(clipDue(stamp = 1_000, text = true, last = -1))
        assertFalse(clipDue(stamp = 1_000, text = true, last = 1_000))
        assertTrue(clipDue(stamp = 2_000, text = true, last = 1_000))
    }

    @Test
    fun onlyTextIsRead() {
        assertFalse(clipDue(stamp = 1_000, text = false, last = -1))
    }

    // A phone that does not say when a clip was put there is read as before.
    @Test
    fun aClipWithNoTimeIsReadAsBefore() {
        assertTrue(clipDue(stamp = 0, text = true, last = 0))
    }
}
