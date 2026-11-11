package app.winters.octo.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The words and maths every app shows about a copy.
class CopyWordsTest {
    private fun run(step: CopyStep, done: Long = 0, of: Long? = null, whole: Boolean = true, fraction: Float? = copyFraction(step, done, of)) =
        CopyRunState(1, 1, "acc", "Home", arriving = true, whole = whole, step = step, done = done, of = of, fraction = fraction, artwork = null, songs = 0, albums = 0, dismissed = false)

    @Test
    fun countsAreGroupedUpToSevenDigits() {
        assertEquals("48,210 of 200,000", copyCount(CopyStep.Read, 48_210, 200_000))
        assertEquals("1,234,567 of 1,500,000", copyCount(CopyStep.Add, 1_234_567, 1_500_000))
        assertEquals("500 so far", copyCount(CopyStep.Read, 500, null))
        assertNull(copyCount(CopyStep.Connect, 0, null))
        assertNull(copyCount(CopyStep.Save, 0, null))
    }

    @Test
    fun theTotalShownIsNeverBelowWhatIsDone() {
        assertEquals("Reading your songs, 195,000 of 195,000", copyDescription(run(CopyStep.Read, 195_000, 190_000)))
        assertEquals("Connecting to Home", copyDescription(run(CopyStep.Connect)))
    }

    @Test
    fun stepsReadInPlainWords() {
        assertEquals("Connect to Home", copyStepLabel(CopyStep.Connect, "Home"))
        assertEquals("Add to your library", copyStepLabel(CopyStep.Save, "Home"))
        assertEquals("Saving your library", copyDoing(CopyStep.Save, "Home"))
        assertEquals("1,204 songs and 96 albums from Home.", copyDoneText(1_204, 96, "Home"))
        assertEquals("1 song and 1 album from Home.", copyDoneText(1, 1, "Home"))
        assertEquals("Stopped after reading 48,210 songs.", copyStoppedAt(CopyStep.Read, 48_210))
        assertNull(copyStoppedAt(CopyStep.Connect, 0))
    }

    @Test
    fun theSettingsLineSaysThePercentOfAWholeCopyOnly() {
        assertEquals("Syncing, 15%", copyStatus(run(CopyStep.Read, fraction = 0.15f)))
        assertEquals("Syncing, 29%", copyStatus(run(CopyStep.Read, fraction = 0.29f)))
        assertEquals("Syncing", copyStatus(run(CopyStep.Connect)))
        assertEquals("Syncing", copyStatus(run(CopyStep.Settle)))
        assertEquals("Syncing", copyStatus(run(CopyStep.Settle, whole = false)))
    }

    @Test
    fun stepsJoinWithoutAJumpAndOnlyGoForward() {
        assertEquals(copyFraction(CopyStep.Build, 0, 100)!!, READ_SHARE, 1e-6f)
        assertEquals(copyFraction(CopyStep.Build, 100, 100)!!, copyFraction(CopyStep.Add, 0, 100)!!, 1e-6f)
        assertEquals(copyFraction(CopyStep.Add, 100, 100)!!, copyFraction(CopyStep.Save, 0, null)!!, 1e-6f)
        assertNull(copyFraction(CopyStep.Connect, 0, null))
        assertNull(copyFraction(CopyStep.Read, 5_000, null))
        var last = 0f
        for (step in listOf(CopyStep.Read, CopyStep.Build, CopyStep.Add)) {
            for (done in 0L..1_000L step 100L) {
                val f = copyFraction(step, done, 1_000)!!
                assertTrue("$step $done: $f after $last", f >= last)
                last = f
            }
        }
        assertTrue(copyFraction(CopyStep.Save, 0, null)!! >= last)
        // Saving moves through the rest as its groups go in, and ends where
        // the steps after a copy begin.
        assertEquals(copyFraction(CopyStep.Save, 0, null)!!, copyFraction(CopyStep.Save, 0, SAVE_PARTS)!!, 1e-6f)
        last = copyFraction(CopyStep.Save, 0, SAVE_PARTS)!!
        for (done in 0L..SAVE_PARTS step SAVE_PARTS / 10) {
            val f = copyFraction(CopyStep.Save, done, SAVE_PARTS)!!
            assertTrue("save $done: $f after $last", f >= last)
            last = f
        }
        assertEquals(copyFraction(CopyStep.Settle, 0, null)!!, last, 1e-6f)
        assertNull("the Save step has no count", copyCount(CopyStep.Save, SAVE_PARTS / 2, SAVE_PARTS))
    }

    // Reading never reaches its end while a page may still come, even when
    // the server lists fewer songs than it gives.
    @Test
    fun readingMovesOnEveryPageEvenPastTheListedTotal() {
        var last = -1f
        for (done in 500L..20_000L step 500L) {
            val f = readFraction(done, 10_000)
            assertTrue("$done: $f after $last", f > last)
            assertTrue(f < 1f)
            last = f
        }
    }

    @Test
    fun stepStatesFollowTheCopy() {
        assertEquals(StepState.Now, stepState(CopyStep.Add, CopyStep.Save))
        assertEquals(StepState.Done, stepState(CopyStep.Read, CopyStep.Build))
        assertEquals(StepState.Later, stepState(CopyStep.Add, CopyStep.Read))
        assertTrue(COPY_STEPS.all { stepState(it, CopyStep.Settle) == StepState.Done })
    }

    @Test
    fun theWordsHaveNoDashesOrPreviews() {
        val words = listOf(
            copyTitle(true), copyTitle(false), copyHeroText("Home"), COPY_SAVING_TITLE, COPY_SAVING_TEXT, COPY_DONE_TITLE, COPY_FAILED_TITLE,
            COPY_ON_ITS_WAY, COPY_LIKES_ON_ITS_WAY, COPY_STILL_ON_ITS_WAY, COPY_OPEN_TO_FINISH, COPY_NOTICE_ASK,
        ) + CopyStep.entries.flatMap { listOf(copyStepLabel(it, "Home"), copyDoing(it, "Home")) } +
            CopyStep.entries.mapNotNull { copyStoppedAt(it, 2) }
        for (text in words) {
            assertFalse(text, Char(0x2014) in text || Char(0x2013) in text)
            assertFalse(text, "preview" in text.lowercase())
        }
    }
}
