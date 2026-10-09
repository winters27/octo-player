package app.winters.octo.ui.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.winters.octo.design.OctoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The Anywhere / At home choice: a radio group whose buttons say whether
// they are chosen, words that never clip on a narrow phone, and Anywhere
// without an outside address explaining itself when picked.
@RunWith(AndroidJUnit4::class)
class ReachChooserTest {
    @get:Rule val rule = createComposeRule()

    private val home = "http://192.168.1.20:4533/family/join#u=sam&c=482913"
    private val anywhere = "https://music.example.com/family/join#u=sam&c=482913"

    private fun show(options: LinkOptions, width: Int = 360, fontScale: Float = 1f) {
        rule.setContent {
            var reach by mutableStateOf(options.default)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OctoTheme {
                    Box(Modifier.width(width.dp)) {
                        ReachChooser(reachQuestion(own = false), reach, options, owner = false, server = "https://music.example.com", actions = PhoneSheetActions()) { reach = it }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun layout(tag: String): TextLayoutResult {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun assertWhole(tag: String) {
        val text = layout(tag)
        assertFalse("$tag: lines ${text.lineCount}, size ${text.size}, wanted ${text.multiParagraph.width}x${text.multiParagraph.height}", text.hasVisualOverflow)
    }

    @Test
    fun eachChoiceSaysWhetherItIsChosen() {
        show(LinkOptions(anywhere, home))
        val any = rule.onNodeWithTag("reach-Anywhere")
        val atHome = rule.onNodeWithTag("reach-Home")
        any.assertIsSelected()
        atHome.assertIsNotSelected()
        assertEquals(Role.RadioButton, any.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        rule.onNodeWithText("Uses https://music.example.com").assertExists()
        atHome.performClick()
        atHome.assertIsSelected()
        any.assertIsNotSelected()
        rule.onNodeWithText("Uses your home network (192.168.1.20:4533)").assertExists()
    }

    @Test
    fun theWordsNeverClipAt320dp() {
        show(LinkOptions(anywhere, home), width = 320)
        assertWhole("reach-Anywhere-label")
        assertWhole("reach-Home-label")
        // At the usual text size the words keep their full 15 sp.
        assertEquals(15f, layout("reach-Anywhere-label").layoutInput.style.fontSize.value, 0.01f)
        assertEquals(15f, layout("reach-Home-label").layoutInput.style.fontSize.value, 0.01f)
    }

    @Test
    fun theWordsNeverClipAt320dpWithLargerText() {
        show(LinkOptions(anywhere, home), width = 320, fontScale = 1.3f)
        assertWhole("reach-Anywhere-label")
        assertWhole("reach-Home-label")
    }

    @Test
    fun anywhereWithoutAnOutsideAddressExplainsWhenPicked() {
        show(LinkOptions(null, home, anywhereAvailable = false))
        rule.onNodeWithTag("reach-Home").assertIsSelected()
        rule.onNodeWithTag("reach-Anywhere").performClick()
        rule.onNodeWithTag("reach-Anywhere").assertIsSelected()
        rule.onNodeWithText("Set your outside address first").assertExists()
        rule.onNodeWithText("Ask your family owner to set an outside address").assertExists()
    }
}
