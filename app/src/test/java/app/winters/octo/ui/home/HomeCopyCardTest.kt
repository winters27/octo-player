package app.winters.octo.ui.home

import app.winters.octo.server.CopyOutcome
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import app.winters.octo.server.CopyWait
import app.winters.octo.ui.common.CopyNoteState
import app.winters.octo.ui.common.copyNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Which copy card Home shows, for every case of a first copy and a copy again.
class HomeCopyCardTest {
    private val coming = CopyWait(awaiting = true, phoneMusic = false)
    private val comingBesidePhone = CopyWait(awaiting = true, phoneMusic = true)
    private val here = CopyWait(awaiting = false, phoneMusic = false)

    private fun run(step: CopyStep = CopyStep.Read, arriving: Boolean = true, account: String = "acc", dismissed: Boolean = false) =
        CopyRunState(7, 1, account, "Home", arriving, whole = true, step = step, done = 10, of = 100, fraction = 0.1f, artwork = null, songs = 120, albums = 9, dismissed = dismissed)

    private val failed = CopyOutcome.Failed(5, "acc", "Home", "Couldn't reach the server.", CopyStep.Read, 10)
    private val arrived = CopyOutcome.Arrived(5, "acc", "Home", 120, 9, null)

    @Test
    fun nothingShowsUntilWhetherMusicIsComingIsRead() {
        assertNull(homeCopyCard(null, run(), null, "acc", "Home"))
        assertNull(homeCopyCard(coming, run(), null, null, "Home"))
    }

    @Test
    fun aFirstCopyFillsAnEmptyHomeAndIsABannerBesidePhoneMusic() {
        assertEquals(HomeCopyCard.Coming(run(), "Home", big = true), homeCopyCard(coming, run(), null, "acc", "Home"))
        assertEquals(HomeCopyCard.Coming(run(), "Home", big = false), homeCopyCard(comingBesidePhone, run(), null, "acc", "Home"))
    }

    @Test
    fun withNoCopyRunningYetTheCardIsDrawnStill() {
        assertEquals(HomeCopyCard.Coming(null, "Home", big = true), homeCopyCard(coming, null, null, "acc", "Home"))
    }

    @Test
    fun onceTheMusicIsInTheDoneCardShowsWhileTheStepsRun() {
        val settling = run(CopyStep.Settle)
        assertEquals(HomeCopyCard.Here(7, 120, 9, "Home", settling = true, stepsProblem = null), homeCopyCard(here, settling, null, "acc", "Home"))
        // The swap's mark in the database can land before the copy says so.
        assertEquals(HomeCopyCard.Here(7, 120, 9, "Home", settling = true, stepsProblem = null), homeCopyCard(here, run(CopyStep.Save), null, "acc", "Home"))
        assertEquals(HomeCopyCard.Here(7, 120, 9, "Home", settling = true, stepsProblem = null), homeCopyCard(coming, settling, null, "acc", "Home"))
        assertNull(homeCopyCard(here, run(CopyStep.Settle, dismissed = true), null, "acc", "Home"))
    }

    @Test
    fun aCopyAgainNeverShowsOnHome() {
        assertNull(homeCopyCard(here, run(arriving = false), null, "acc", "Home"))
    }

    @Test
    fun aStoppedFirstCopyShowsWhileTheMusicIsStillToCome() {
        assertEquals(HomeCopyCard.Stopped(failed, big = true), homeCopyCard(coming, null, failed, "acc", "Home"))
        assertNull(homeCopyCard(here, null, failed, "acc", "Home"))
        // Try again's run takes the slot over.
        assertEquals(HomeCopyCard.Coming(run(), "Home", big = true), homeCopyCard(coming, run(), failed, "acc", "Home"))
    }

    @Test
    fun aFinishedFirstCopyShowsTheDoneCard() {
        assertEquals(HomeCopyCard.Here(5, 120, 9, "Home", settling = false, stepsProblem = null), homeCopyCard(here, null, arrived, "acc", "Home"))
    }

    @Test
    fun anotherAccountsCopyAndOutcomeAreLeftOut() {
        assertEquals(HomeCopyCard.Coming(null, "Home", big = true), homeCopyCard(coming, run(account = "other"), failed.copy(account = "other"), "acc", "Home"))
        assertNull(homeCopyCard(here, null, arrived.copy(account = "other"), "acc", "Home"))
    }

    // A take-in before the first copy (a search, a download) puts songs in
    // the library but leaves the music still to come, so the card stays.
    @Test
    fun aTakeInBeforeTheFirstCopyKeepsTheCard() {
        assertEquals(HomeCopyCard.Coming(null, "Home", big = true), homeCopyCard(coming, null, null, "acc", "Home"))
    }

    @Test
    fun leavingHomePutsTheDoneCardAwayButTurningDoesNot() {
        val seen = DoneCardSeen()
        seen.shown(5)
        assertNull(seen.paused(turning = true))
        assertEquals(5L, seen.paused(turning = false))
        assertNull(seen.paused(turning = false))
        seen.shown(null)
        assertNull(seen.paused(turning = false))
    }

    @Test
    fun emptyListsSayTheMusicIsOnItsWay() {
        assertNull(copyNote(here, null, null, "acc"))
        assertNull(copyNote(null, null, null, "acc"))
        assertEquals(CopyNoteState.Coming(run()), copyNote(coming, run(), null, "acc"))
        assertEquals(CopyNoteState.Coming(null), copyNote(coming, run(account = "other"), null, "acc"))
        assertEquals(CopyNoteState.Stopped, copyNote(coming, null, failed, "acc"))
    }
}
