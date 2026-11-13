package app.winters.octo.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WaitOnWorkTest {
    // Work that keeps moving is waited out past the quiet time.
    @Test
    fun workThatKeepsMovingIsWaitedOut() {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        waitOnWork("the work ends", quietMillis = 300, progress = { elapsed() / 50 }, step = {}) { elapsed() >= 1_000 }
    }

    // Work that stands still fails once it has been still for the quiet time.
    @Test
    fun workThatStopsFails() {
        val error = assertThrows(AssertionError::class.java) {
            waitOnWork("the work ends", quietMillis = 300, progress = { 0 }, step = {}) { false }
        }
        assertEquals("never: the work ends", error.message)
    }
}
