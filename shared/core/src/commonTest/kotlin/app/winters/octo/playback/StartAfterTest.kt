package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartAfterTest {
    @Test
    fun theChoicesGrowAndTheDefaultIsOneSecond() {
        assertEquals(listOf(250, 1_000, 2_500, 5_000, 10_000), StartAfter.entries.map { it.ms })
        assertEquals("1 second", StartAfter.Short.label)
    }

    @Test
    fun theWordsHaveNoDashes() {
        for (words in StartAfter.entries.map { it.label } + START_AFTER_SETTING + START_AFTER_HELP) {
            assertTrue(words.none { it.code == 0x2014 || it.code == 0x2013 })
        }
    }
}
