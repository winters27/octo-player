package app.winters.octo.radio

import org.junit.Assert.assertEquals
import org.junit.Test

// Outside and library songs that just fill a radio's slots leave one share
// to pick, however the division rounds: 1 outside song and 49 library songs
// for 50 picks is a share of 0.02. Before, rounding put the least a hair
// above the most, and the radio threw instead of playing.
class PacedShareRoundingTest {
    @Test
    fun songsThatJustFillTheSlotsLeaveOneShare() {
        assertEquals(0.02, pacedShare(0.35, 1, 49, 50), 1e-12)
        for (outside in 0..50) {
            val share = pacedShare(0.35, outside, 50 - outside, 50)
            assertEquals(outside / 50.0, share, 1e-9)
        }
    }
}
