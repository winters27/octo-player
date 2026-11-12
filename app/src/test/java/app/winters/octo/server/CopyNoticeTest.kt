package app.winters.octo.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// What the copy's notice says, when it is posted again, and when the
// service for it stays.
class CopyNoticeTest {
    private fun run(step: CopyStep, done: Long = 0, of: Long? = null, whole: Boolean = true, arriving: Boolean = true) =
        CopyRunState(1, 1, "acc", "Home", arriving, whole, step, done, of, copyFraction(step, done, of), null, 0, 0, false)

    @Test
    fun theNoticeSaysTheStepAndCountWithABarOfTheWholeCopy() {
        val reading = copyNotice(run(CopyStep.Read, 48_210, 200_000))
        assertEquals("Bringing in your music", reading.title)
        assertEquals("Reading your songs, 48,210 of 200,000", reading.text)
        assertTrue(reading.bar)
        assertFalse(reading.indeterminate)
        assertEquals((copyFraction(CopyStep.Read, 48_210, 200_000)!! * NOTICE_MAX).toInt(), reading.progress)
        assertEquals("Updating your library", copyNotice(run(CopyStep.Read, arriving = false)).title)
    }

    @Test
    fun theBarMovesOnItsOwnWhileHowFarCannotBeTold() {
        assertTrue(copyNotice(run(CopyStep.Connect)).indeterminate)
        assertTrue(copyNotice(run(CopyStep.Read, 500, null)).indeterminate)
        assertTrue(copyNotice(run(CopyStep.Save)).indeterminate)
        assertTrue(copyNotice(run(CopyStep.Settle)).indeterminate)
    }

    // Saving's bar stands for how much of the save is in once its groups
    // are planned.
    @Test
    fun theBarShowsHowMuchOfTheSaveIsIn() {
        val saving = copyNotice(run(CopyStep.Save, SAVE_PARTS / 2, SAVE_PARTS))
        assertFalse(saving.indeterminate)
        assertEquals((copyFraction(CopyStep.Save, SAVE_PARTS / 2, SAVE_PARTS)!! * NOTICE_MAX).toInt(), saving.progress)
        assertTrue(saving.progress > (copyFraction(CopyStep.Save, 0, null)!! * NOTICE_MAX).toInt())
    }

    @Test
    fun theNoticeIsPostedAgainOnAStepAWholePercentOrEveryTenthPage() {
        val base = run(CopyStep.Read, 1_000, 200_000)
        assertEquals(noticeKey(base), noticeKey(base.copy(done = 1_100, fraction = copyFraction(CopyStep.Read, 1_100, 200_000))))
        val farther = 10_000L
        assertNotEquals(noticeKey(base), noticeKey(base.copy(done = farther, fraction = copyFraction(CopyStep.Read, farther, 200_000))))
        assertNotEquals(noticeKey(base), noticeKey(base.copy(step = CopyStep.Build)))
        val unknown = run(CopyStep.Read, 500, null)
        assertEquals(noticeKey(unknown), noticeKey(unknown.copy(done = 4_500)))
        assertNotEquals(noticeKey(unknown), noticeKey(unknown.copy(done = 5_000)))
    }

    @Test
    fun theServiceStaysOnlyForAWholeCopyWhileTheAppIsAway() {
        assertEquals(ServiceAction.Keep, serviceAction(run(CopyStep.Read), inFront = false))
        assertEquals(ServiceAction.Keep, serviceAction(run(CopyStep.Settle), inFront = false))
        assertEquals(ServiceAction.Stop, serviceAction(run(CopyStep.Read), inFront = true))
        assertEquals(ServiceAction.Stop, serviceAction(run(CopyStep.Settle, whole = false), inFront = false))
        assertEquals(ServiceAction.Stop, serviceAction(null, inFront = false))
    }

    @Test
    fun leavingDuringAWholeCopyStartsItsNotice() {
        assertTrue(leaveStartsNotice(run(CopyStep.Read)))
        assertFalse(leaveStartsNotice(run(CopyStep.Settle, whole = false)))
        assertFalse(leaveStartsNotice(null))
        assertTrue(plannedStartsNotice(whole = true, away = true))
        assertFalse(plannedStartsNotice(whole = true, away = false))
        assertFalse(plannedStartsNotice(whole = false, away = true))
    }

    @Test
    fun theAskShowsOnceWhileNotificationsWouldNotShow() {
        assertTrue(noticeAsk(enabled = false, asked = false))
        assertFalse(noticeAsk(enabled = true, asked = false))
        assertFalse(noticeAsk(enabled = false, asked = true))
        assertFalse(noticeAsk(enabled = false, asked = null))
        assertEquals(NoticeAskWay.System, noticeAskWay(33, permissionGranted = false))
        assertEquals(NoticeAskWay.Settings, noticeAskWay(33, permissionGranted = true))
        assertEquals(NoticeAskWay.Settings, noticeAskWay(31, permissionGranted = true))
    }

    // A refusal for good shows no dialog: the system gives no reason to ask
    // before the ask or after it.
    @Test
    fun aRefusalForGoodOpensTheSettingsPage() {
        assertTrue(askShowedNothing(granted = false, rationaleBefore = false, rationaleAfter = false))
        assertFalse(askShowedNothing(granted = false, rationaleBefore = false, rationaleAfter = true))
        assertFalse(askShowedNothing(granted = false, rationaleBefore = true, rationaleAfter = false))
        assertFalse(askShowedNothing(granted = true, rationaleBefore = false, rationaleAfter = false))
    }

    // The app only ever starts the service; the service alone stops itself.
    @Test
    fun theAppNeverStopsTheCopysService() {
        val sources = java.io.File(System.getProperty("octo.mainSources")!!, "app/winters/octo")
        val stops = sources.walkTopDown().filter { it.extension == "kt" }.filter { "stopService(" in it.readText() && "CopyService" in it.readText() }.toList()
        assertEquals(emptyList<java.io.File>(), stops)
        val service = java.io.File(sources, "server/CopyService.kt").readText()
        assertFalse("stopSelf()" in service)
    }
}
