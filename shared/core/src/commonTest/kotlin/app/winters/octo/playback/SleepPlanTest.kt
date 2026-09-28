package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepPlanTest {
    @Test
    fun theFadeIsFullUntilTheLastThirtySecondsThenSquared() {
        assertEquals(1f, sleepFade(10 * 60_000L), 0f)
        assertEquals(1f, sleepFade(SLEEP_FADE_MS), 0f)
        assertEquals(0.25f, sleepFade(15_000), 0.0001f)
        assertEquals(0.01f, sleepFade(3_000), 0.0001f)
        assertEquals(0f, sleepFade(0), 0f)
        assertEquals(0f, sleepFade(-5), 0f)
    }

    @Test
    fun ticksLandOnWholeSecondsThenTenTimesASecond() {
        assertEquals(1_000L, untilNextSleepTick(60_000))
        assertEquals(500L, untilNextSleepTick(59_500))
        assertEquals(1L, untilNextSleepTick(45_001))
        assertEquals(100L, untilNextSleepTick(SLEEP_FADE_MS))
        assertEquals(30L, untilNextSleepTick(1_030))
    }

    @Test
    fun aTimerReadsInPlainWords() {
        assertEquals("Off", sleepSummary(SleepState.Off))
        assertEquals("15 min left", sleepSummary(SleepState.Counting(14 * 60_000L + 1)))
        assertEquals("1 min left", sleepSummary(SleepState.Counting(5_000)))
        assertEquals("At the end of this song", sleepSummary(SleepState.EndOfSong))
        assertEquals("After 3 songs", sleepSummary(SleepState.Songs(3)))
        assertEquals("After Song 4", sleepSummary(SleepState.AfterSong("q:4", "Song 4")))
    }

    @Test
    fun theChoicesAreThePhones() {
        assertEquals(listOf(5, 15, 30, 45, 60), SLEEP_MINUTES)
        assertEquals(listOf(1, 2, 3, 5), SLEEP_SONG_COUNTS)
        assertEquals(720, MAX_SLEEP_MINUTES)
    }
}
