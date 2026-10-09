package app.winters.octo.ui.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoTheme
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyLinkChoices
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

// The Add a device sheet on a typical phone (360 x 780 dp): the code and
// Done are both in sight without scrolling. At a large text size on a small
// phone (360 x 640 dp), where it can't all fit, the "More below" cue shows,
// and tapping it brings the code into view.
@RunWith(AndroidJUnit4::class)
class FamilySheetFitTest {
    @get:Rule val rule = createComposeRule()

    private val sheet = DeviceSheet(
        id = 1,
        code = FamilyDeviceAdded(
            deviceId = "d_1",
            kind = FamilyDeviceKind.OctoApp,
            pairCode = "482913",
            expires = Instant.now().plusSeconds(582).toString(),
            server = "https://music.example.com",
            username = "winters",
            links = FamilyLinkChoices("https://music.example.com/family/join#u=winters&c=482913", "http://192.168.1.20:4533/family/join#u=winters&c=482913"),
        ),
    )

    private fun show(fontScale: Float, height: Int = 780) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OctoTheme {
                    Box(Modifier.size(360.dp, height.dp)) {
                        GlassSheet(visible = true, onDismiss = {}, tall = true, scrolls = false) {
                            DeviceSheetContent(sheet, "https://music.example.com", "winters", "Jordan", owner = true, actions = PhoneSheetActions())
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theCodeAndDoneFitOneScreen() {
        show(1f)
        val code = rule.onNodeWithTag(SHEET_CODE_TAG, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val done = rule.onNodeWithTag(SHEET_DONE_TAG, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("the code card ends above the footer: $code, $done", code.bottom <= done.top)
        assertTrue("Done is on the screen: $done", done.bottom <= 780.dp)
        rule.onNodeWithTag(SHEET_MORE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun atALargeTextSizeTheCueShowsAndBringsTheCodeIntoView() {
        show(1.5f, height = 640)
        rule.onNodeWithTag(SHEET_DONE_TAG, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(SHEET_MORE_TAG, useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        val code = rule.onNodeWithTag(SHEET_CODE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val done = rule.onNodeWithTag(SHEET_DONE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the code is in view above the footer: $code, $done", code.bottom <= done.top && code.top >= 0.dp)
    }
}
