package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

// Big counts and lengths read with their thousands marks, as a 200,000-song
// library's album and playlist headers show them.
class CountWordsTest {
    @Test
    fun bigCountsAndLengthsCarryTheirCommas() {
        val was = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("1 song", songs(1))
            assertEquals("200,000 songs", songs(200_000))
            assertEquals("20,001 albums", albums(20_001))
            assertEquals("7,489 hr 27 min", (7_489 * 3600 + 27 * 60).asLength())
            assertEquals("48 min", (48 * 60).asLength())
        } finally {
            Locale.setDefault(was)
        }
    }
}
