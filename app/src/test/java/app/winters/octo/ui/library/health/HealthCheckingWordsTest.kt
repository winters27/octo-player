package app.winters.octo.ui.library.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthCheckingWordsTest {
    // The wait before the first report says how many songs it goes through.
    @Test
    fun theWaitSaysHowManySongs() {
        assertEquals("Checking your 200,000 songs", healthCheckingWords(200_000))
        assertEquals("Checking your 1 song", healthCheckingWords(1))
    }

    // Before the count is read there is nothing to say.
    @Test
    fun nothingBeforeTheCount() {
        assertNull(healthCheckingWords(0))
    }
}
