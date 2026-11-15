package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassSheet
import app.winters.octo.testing.RobolectricComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val BODY = "sheet-body"
private val BODY_HEIGHT = 200.dp

// The handle is 28 tall, right above the body.
private val HANDLE_HALF = 14.dp

// How far below its rest the pull takes the sheet: past the 96 that asks it
// to close.
private val PULL = 200.dp

// A sheet pulled down by its handle past the line that asks it to close. One
// whose dismiss refuses (Family's Change password while a change runs)
// springs back to where it rests; one that closes slides away from where the
// pull left it, never rising first.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class GlassSheetPullTest {
    @get:Rule
    val ui = RobolectricComposeRule()

    private val shown = mutableStateOf(true)
    private var asked = 0

    // The sheet as Family draws Change password: tall, a body that keeps its
    // own room, and a dismiss that lets go only when nothing is running.
    private fun show(running: Boolean) {
        ui.compose.setContent {
            Box(Modifier.fillMaxSize()) {
                GlassSheet(
                    visible = shown.value,
                    onDismiss = {
                        asked++
                        if (!running) shown.value = false
                    },
                    tall = true,
                    scrolls = false,
                ) {
                    Box(Modifier.fillMaxWidth().height(BODY_HEIGHT).testTag(BODY))
                }
            }
        }
        ui.compose.waitForIdle()
    }

    // The sheet's own click merges what it holds, so the body is found in
    // the unmerged tree.
    private fun bodyTop(): Dp = ui.compose.onNodeWithTag(BODY, useUnmergedTree = true).getBoundsInRoot().top

    private fun bodies() = ui.compose.onAllNodesWithTag(BODY, useUnmergedTree = true).fetchSemanticsNodes()

    // A finger on the handle, moved down 10 at a time until the sheet itself
    // is PULL below its rest, and kept on the glass. Under Robolectric a node
    // that moves with the finger takes about half of each move, so the finger
    // goes about twice as far; the sheet's own place is what counts.
    private fun pullHandle(rest: Dp) {
        ui.compose.onRoot().performTouchInput { down(Offset(centerX, (rest - HANDLE_HALF).toPx())) }
        var moves = 0
        while (bodyTop() - rest < PULL && moves < 100) {
            ui.compose.onRoot().performTouchInput { moveBy(Offset(0f, 10.dp.toPx())) }
            moves++
        }
        val pulled = bodyTop() - rest
        assertTrue("the sheet followed the finger: $pulled after $moves moves", pulled >= PULL)
    }

    private fun letGo() = ui.compose.onRoot().performTouchInput { up() }

    // Red when a pull past the closing line on a sheet that refuses leaves it
    // where the finger let go: the branch that asks to close never springs
    // the pull back.
    @Test
    fun aRefusedPullSpringsBackToRest() {
        show(running = true)
        val rest = bodyTop()
        pullHandle(rest)
        letGo()
        ui.compose.waitForIdle()
        assertEquals("the pull asked the sheet to close", 1, asked)
        assertTrue("the sheet stayed open", shown.value)
        assertEquals("the sheet is back where it rests", rest.value, bodyTop().value, 0.5f)
    }

    // Red when a sheet that closes also springs back as it slides away: the
    // spring outruns the slide's slow start, so it rises before it falls.
    @Test
    fun aClosingSheetSlidesAwayFromWhereThePullLeftIt() {
        show(running = false)
        val rest = bodyTop()
        pullHandle(rest)
        val pulledTo = with(ui.compose.density) { (rest + PULL).toPx() }
        // The clock is held from the release on, to read every frame of the
        // slide.
        ui.compose.mainClock.autoAdvance = false
        letGo()
        ui.compose.waitForIdle()
        // With the clock held, the writes reach the sheet only once sent.
        Snapshot.sendApplyNotifications()
        val tops = mutableListOf<Float>()
        repeat(40) {
            ui.compose.mainClock.advanceTimeByFrame()
            bodies().singleOrNull()?.let { tops += it.boundsInRoot.top }
        }
        ui.compose.mainClock.autoAdvance = true
        ui.compose.waitForIdle()

        assertEquals("the pull asked the sheet to close", 1, asked)
        assertTrue("the sheet is gone", bodies().isEmpty())
        assertTrue("the sheet was drawn as it slid: $tops", tops.size >= 3)
        assertTrue("the slide starts where the pull left it: $tops", tops.first() >= pulledTo - 0.5f)
        tops.zipWithNext().forEach { (before, after) -> assertTrue("the sheet rose on its way out: $tops", after >= before - 0.5f) }
    }
}
