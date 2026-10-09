package app.winters.octo.ui.family

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// "Sign in on another device" opened the way the Family screen opens it,
// in its real sheet, on this phone (360 dp wide): the QR code and Done are
// both in sight without scrolling. At a large text size, where it can't all
// fit, the "More below" cue shows, and tapping it brings the rest into view.
@RunWith(AndroidJUnit4::class)
class FamilySheetFitTest {
    @get:Rule val rule = createComposeRule()
    private val server = FamilyPhoneServer().apply { answerStart() }
    private val model = server.model()

    @After
    fun stop() {
        model.handOver.close()
        server.close()
    }

    private fun open(fontScale: Float) {
        rule.setContent { FamilySheetsAsShipped(model, fontScale) }
        model.handOver.open()
        rule.waitUntil(10_000) { model.handOver.sheet?.start != null }
        rule.waitForIdle()
    }

    @Test
    fun theQrCodeAndDoneFitOneScreen() {
        open(1f)
        val screen = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("a phone about 360 dp wide: $screen", (screen.right - screen.left) in 350.dp..420.dp)
        val qr = rule.onNodeWithTag(SHEET_QR_TAG, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val done = rule.onNodeWithTag(SHEET_DONE_TAG, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("the QR code ends above the footer: $qr, $done", qr.bottom <= done.top)
        assertTrue("Done is on the screen: $done, $screen", done.bottom <= screen.bottom)
        rule.onNodeWithTag(SHEET_MORE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun atALargeTextSizeTheCueShowsAndBringsTheRestIntoView() {
        open(1.5f)
        rule.onNodeWithTag(SHEET_DONE_TAG, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(SHEET_MORE_TAG, useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag(SHEET_MORE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
