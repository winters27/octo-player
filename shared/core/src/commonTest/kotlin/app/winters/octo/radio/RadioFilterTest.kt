package app.winters.octo.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFilterTest {
    @Test
    fun tooShortOrTooLongIsLeftOut() {
        assertTrue(isRadioFiller("Song", 30_000))
        assertTrue(isRadioFiller("Song", 50 * 60_000L))
        assertFalse(isRadioFiller("Song", 200_000))
        assertFalse(isRadioFiller("Song", 0))
    }

    @Test
    fun shortIntrosSkitsAndInterludesAreLeftOut() {
        assertTrue(isRadioFiller("Intro", 60_000))
        assertTrue(isRadioFiller("Interlude No. 2", 90_000))
        assertTrue(isRadioFiller("Skit (Live)", 50_000))
        assertFalse(isRadioFiller("Intro", 5 * 60_000L))
        assertFalse(isRadioFiller("Introducing the Band", 180_000))
        assertFalse(isRadioFiller("Intro", 0))
        // A letter of any alphabet joined to the word makes it another word.
        assertFalse(isRadioFiller("Skité", 60_000))
        assertTrue(isRadioFiller("Café Intro", 60_000))
    }

    @Test
    fun talkIsLeftOut() {
        assertTrue(isRadioFiller("Interview with the Band", 600_000))
        assertTrue(isRadioFiller("Song", 600_000, listOf("Podcast")))
    }
}
