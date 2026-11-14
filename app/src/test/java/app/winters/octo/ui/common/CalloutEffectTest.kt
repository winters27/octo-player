package app.winters.octo.ui.common

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.winters.octo.testing.DeviceFocusRule
import app.winters.octo.testing.RobolectricComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A callout, run on a page in touch mode: it waits for the keyboard and for
// the group to settle, never leaves anything focused, scrolls the group's
// title to the top inset (or the scroll's end, or the last note 8 above the
// bottom for a tall group), stops when a field takes focus, and keeps its
// flag through a host that leaves, while a drag that cuts the scroll short
// still ends it. Every row first checks the page is in touch mode, since out
// of it Android hands focus back to the first field after a clear.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CalloutEffectTest {
    @get:Rule
    val ui = RobolectricComposeRule()

    @get:Rule
    val focusRule = DeviceFocusRule()

    private val page = CalloutPage()

    private fun show() {
        ui.compose.setContent { page.Draw() }
        ui.compose.waitForIdle()
        assertEquals(InputMode.Touch, page.modes!!.inputMode)
    }

    private fun idle() = ui.compose.waitForIdle()

    private fun field(name: String) = ui.compose.onNode(hasContentDescription(name) and hasSetTextAction())

    private fun assertGroupAtTop() = assertEquals(page.topInset.toFloat(), ui.compose.runOnIdle { page.groupTop }, 1f)

    // Red when the scroll runs before the transition settles.
    @Test
    fun theGroupComesIntoViewOnceItsPartHasSettled() {
        show()
        page.settled = false
        page.pending = true
        idle()
        assertEquals(0, page.scroll.value)
        assertTrue(page.pending)
        page.settled = true
        idle()
        assertGroupAtTop()
        assertFalse(page.pending)
        assertEquals(1, page.shownCount)
    }

    // Red when the effect leaves focus where it was in touch mode.
    @Test
    fun nothingStaysFocusedAfterIt() {
        show()
        field(ADDRESS).performClick()
        idle()
        field(ADDRESS).assertIsFocused()
        page.pending = true
        idle()
        assertGroupAtTop()
        ui.compose.onAllNodes(isFocused()).assertCountEquals(0)
    }

    // Red when the effect runs with nothing pending.
    @Test
    fun nothingMovesWhenNothingIsPending() {
        show()
        idle()
        assertEquals(0, page.scroll.value)
        assertEquals(0, page.shownCount)
    }

    // Red when the scroll does not wait for the keyboard to close.
    @Test
    fun theScrollWaitsForTheKeyboardToClose() {
        show()
        page.ime = 300
        page.pending = true
        idle()
        assertEquals(0, page.scroll.value)
        assertTrue(page.pending)
        page.ime = 0
        idle()
        assertGroupAtTop()
        assertFalse(page.pending)
    }

    // Red when a target past the end is not left to the scroll's own clamp,
    // or the group ends out of view.
    @Test
    fun shortContentEndsAtTheScrollsEndWithTheGroupInView() {
        page.above = 150.dp
        page.below = 0.dp
        show()
        page.pending = true
        idle()
        assertTrue(page.scroll.maxValue > 0)
        assertEquals(page.scroll.maxValue, page.scroll.value)
        val (top, bottom) = ui.compose.runOnIdle { page.groupTop to page.groupBottom }
        assertTrue("the group's top at $top", top >= 0f)
        assertTrue("the group's bottom at $bottom past ${page.viewport}", bottom <= page.viewport + 1f)
        assertFalse(page.pending)
    }

    // Red when closing the part that holds the group throws, scrolls, or
    // loses the flag, or when reopening it does not scroll.
    @Test
    fun closingTheGroupsPartDuringTheWaitScrollsOnlyOnceItReopens() {
        show()
        page.ime = 300
        page.pending = true
        idle()
        page.open = false
        page.settled = false
        idle()
        page.ime = 0
        idle()
        assertEquals(0, page.scroll.value)
        assertTrue(page.pending)
        assertEquals(0, page.shownCount)
        page.open = true
        page.settled = true
        idle()
        assertGroupAtTop()
        assertFalse(page.pending)
    }

    // Red when a drag that cancels the scroll leaves the flag set (the
    // cancel is taken for the effect's own).
    @Test
    fun aDragThatCutsTheScrollShortStillEndsTheCallout() {
        show()
        ui.compose.mainClock.autoAdvance = false
        page.pending = true
        // With the clock held, the write reaches the page only once sent.
        Snapshot.sendApplyNotifications()
        repeat(20) { if (!page.scroll.isScrollInProgress) ui.compose.mainClock.advanceTimeByFrame() }
        assertTrue("the scroll never started", page.scroll.isScrollInProgress)
        ui.compose.onNodeWithTag("page").performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
            moveBy(Offset(0f, 60f))
            up()
        }
        ui.compose.mainClock.advanceTimeBy(1_000)
        ui.compose.mainClock.autoAdvance = true
        idle()
        assertFalse(page.pending)
        assertEquals(1, page.shownCount)
    }

    // Red when a group taller than the room under the top inset is scrolled
    // to its title (its notes would be cut off).
    @Test
    fun aGroupTallerThanTheViewPutsItsLastNote8AboveTheBottom() {
        page.groupHeight = 600.dp
        show()
        page.pending = true
        idle()
        assertEquals(page.viewport.toFloat(), ui.compose.runOnIdle { page.groupBottom } + page.gap, 1f)
        assertFalse(page.pending)
    }

    // A tall group's last note ends 8 above where the scroll's soft bottom
    // edge starts, not inside it. Red when the fade is left out of the
    // scroll (the note ends 8 above the bottom).
    @Test
    fun aTallGroupsLastNoteEndsAboveTheBottomFade() {
        page.groupHeight = 600.dp
        page.fadeDp = 24.dp
        show()
        page.pending = true
        idle()
        assertEquals(page.viewport.toFloat(), ui.compose.runOnIdle { page.groupBottom } + page.gap + page.fade, 1f)
        assertFalse(page.pending)
    }

    // Red when focus already held counts as a field taking it.
    @Test
    fun aFieldFocusedBeforeTheCalloutStillScrolls() {
        show()
        field(ADDRESS).performClick()
        idle()
        page.pending = true
        idle()
        assertGroupAtTop()
    }

    // Red when a field focused during the keyboard wait does not end the
    // callout, or when the next pointer press does not bring it back.
    @Test
    fun aFieldFocusedDuringTheKeyboardWaitEndsItAndThePointerBringsItBack() {
        show()
        page.ime = 300
        page.pending = true
        idle()
        field(NAME).performClick()
        idle()
        assertFalse(page.pending)
        assertEquals(1, page.shownCount)
        assertEquals(0, page.scroll.value)
        page.ime = 0
        page.pending = true
        idle()
        assertGroupAtTop()
        assertEquals(2, page.shownCount)
    }

    // Red when the effect's own cancel (its host leaving) ends the callout.
    @Test
    fun theHostLeavingDuringTheWaitKeepsTheCalloutForItsReturn() {
        show()
        page.ime = 300
        page.pending = true
        idle()
        page.shown = false
        idle()
        page.ime = 0
        idle()
        assertTrue(page.pending)
        assertEquals(0, page.shownCount)
        page.shown = true
        idle()
        assertGroupAtTop()
        assertFalse(page.pending)
    }

    // Red when the host leaving mid-scroll ends the callout.
    @Test
    fun theHostLeavingMidScrollKeepsTheCalloutForItsReturn() {
        show()
        ui.compose.mainClock.autoAdvance = false
        page.pending = true
        // With the clock held, the write reaches the page only once sent.
        Snapshot.sendApplyNotifications()
        repeat(20) { if (!page.scroll.isScrollInProgress) ui.compose.mainClock.advanceTimeByFrame() }
        assertTrue("the scroll never started", page.scroll.isScrollInProgress)
        page.shown = false
        ui.compose.mainClock.advanceTimeBy(1_000)
        ui.compose.mainClock.autoAdvance = true
        idle()
        assertTrue(page.pending)
        assertEquals(0, page.shownCount)
        page.shown = true
        idle()
        assertGroupAtTop()
        assertFalse(page.pending)
    }

    // Red when a field focused while the group's part is still moving does
    // not end the callout.
    @Test
    fun aFieldFocusedWhileThePartMovesEndsItWithNoScroll() {
        show()
        page.settled = false
        page.pending = true
        idle()
        field(NAME).performClick()
        idle()
        assertFalse(page.pending)
        page.settled = true
        idle()
        assertEquals(0, page.scroll.value)
    }

    // The key row's callout waits for the keyboard and nothing else: closing
    // the group's part during the wait does not hold it. Red when the key
    // host reads the group's settling.
    @Test
    fun theKeyRowComesIntoViewWhileTheGroupsPartCloses() {
        show()
        ui.compose.runOnIdle { page.scroll.dispatchRawDelta(10_000f) }
        idle()
        assertTrue(page.scroll.value > 0)
        page.ime = 300
        page.keyPending = true
        idle()
        page.open = false
        page.settled = false
        page.ime = 0
        idle()
        assertEquals(page.topInset.toFloat(), ui.compose.runOnIdle { page.keyTop }, 1f)
        assertFalse(page.keyPending)
        assertEquals(1, page.keyShownCount)
    }
}
