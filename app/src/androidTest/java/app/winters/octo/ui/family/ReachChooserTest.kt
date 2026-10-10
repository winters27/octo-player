package app.winters.octo.ui.family

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.winters.octo.subsonic.FamilyPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The Anywhere / At home choice in its real sheets: a radio group whose
// buttons say whether they are chosen, words that never clip on a narrow
// phone, and Anywhere without an outside address explaining itself when
// picked.
@RunWith(AndroidJUnit4::class)
class ReachChooserTest {
    @get:Rule val rule = createComposeRule()
    private val server = FamilyPhoneServer()
    private val model = server.model()

    @After
    fun stop() {
        model.handOver.close()
        server.close()
    }

    private fun openHandOver(anywhere: Boolean = true, fontScale: Float = 1f, widthDp: Int? = null) {
        server.answerStart(anywhere)
        rule.setContent { FamilySheetsAsShipped(model, fontScale, widthDp) }
        model.handOver.open()
        rule.waitUntil(10_000) { model.handOver.sheet?.start != null }
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
        openHandOver()
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
        openHandOver(widthDp = 320)
        assertWhole("reach-Anywhere-label")
        assertWhole("reach-Home-label")
        // At the usual text size the words keep their full 15 sp.
        assertEquals(15f, layout("reach-Anywhere-label").layoutInput.style.fontSize.value, 0.01f)
        assertEquals(15f, layout("reach-Home-label").layoutInput.style.fontSize.value, 0.01f)
    }

    @Test
    fun theWordsNeverClipAt320dpWithLargerText() {
        openHandOver(fontScale = 1.3f, widthDp = 320)
        assertWhole("reach-Anywhere-label")
        assertWhole("reach-Home-label")
    }

    @Test
    fun anywhereWithoutAnOutsideAddressExplainsWhenPicked() {
        openHandOver(anywhere = false)
        rule.onNodeWithTag("reach-Home").assertIsSelected()
        rule.onNodeWithTag("reach-Anywhere").performClick()
        rule.onNodeWithTag("reach-Anywhere").assertIsSelected()
        rule.onNodeWithText("Set your outside address first").assertExists()
        rule.onNodeWithText("Ask your family owner to set an outside address").assertExists()
    }

    @Test
    fun theInviteOffersTheSameChoice() {
        server.answer("members") {
            """{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=t9",
            "links":{"anywhere":"https://music.example.com/family/join#invite=t9","home":"http://192.168.1.20:4533/family/join#invite=t9"},"anywhereAvailable":true}"""
        }
        server.answer("sam") { """{"username":"sam","displayName":"Sam","role":"Kid"}""" }
        rule.setContent { FamilySheetsAsShipped(model) }
        model.addMember("sam", "Sam", FamilyPreset.Kid)
        rule.waitUntil(10_000) { model.invite != null }
        rule.waitForIdle()
        rule.onNodeWithText("Invite Sam").assertExists()
        rule.onNodeWithText("Where will they use it?").assertExists()
        rule.onNodeWithTag("reach-Anywhere").assertIsSelected()
    }
}
