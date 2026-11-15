package app.winters.octo.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The sign-in form's looks that no unit row can see, read from the sources:
// Robolectric draws no frame to measure, the chevrons carry no semantics,
// and a border's color is not in the tree. Each check names the exact text.
class FormPolishSitesTest {
    private fun source(path: String) = File("src/main/java/app/winters/octo/$path").readText().replace("\r\n", "\n")

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("\"$from\" is missing", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("\"$to\" is missing after \"$from\"", end >= 0)
        return text.substring(start, end)
    }

    private val groups = source("ui/common/FormGroups.kt")
    private val screen = source("ui/signin/SignInScreen.kt")

    // Red when the callout ring goes back to the accent.
    @Test
    fun theCalloutRingIsTheErrorRed() {
        val card = between(groups, "fun FormGroupCard(", "\n}\n")
        assertTrue(card, card.contains("val ring = OctoColors.ErrorWords"))
        assertTrue(card, card.contains("Modifier.border(1.5.dp, ring, CardShape)"))
        assertFalse(card, card.contains("OctoColors.Accent"))
    }

    // Red when Advanced's head centers its chevron on its two lines again (it
    // sags under the title), sets it a fixed distance from the title's line
    // (it rides high at a larger font scale), or ends it past the rows' right
    // edge.
    @Test
    fun advancedsChevronSitsOnItsTitleAndTheRowsEdge() {
        val head = between(groups, "fun FormSectionHead(", "\n}\n")
        listOf(
            ".padding(start = 4.dp, end = RowSides, top = 10.dp, bottom = 10.dp),",
            "val cap = rememberCapHeightPx(OctoType.formGroup)",
            "Column(Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }) {",
            "modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(20.dp).rotate(turn))",
        ).forEach { assertTrue(it, head.contains(it)) }
        assertFalse(head, head.contains("verticalAlignment"))
    }

    // Red when a choice row centers its chevron on its lines again, or lifts
    // it a fixed distance over the name's line.
    @Test
    fun aChoicesChevronSitsOnItsName() {
        val choice = between(groups, "fun FormChoiceRow(", "\n}\n")
        listOf(
            "val cap = rememberCapHeightPx(OctoType.formName)",
            "Column(Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }) {",
            "modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(ChoiceChevron))",
        ).forEach { assertTrue(it, choice.contains(it)) }
        assertFalse(choice, choice.contains("ChevronLift"))
    }

    // Red when a note's or an error's glyph goes back to a fixed distance under
    // its words' first line.
    @Test
    fun aNotesGlyphSitsOnItsFirstLine() {
        for (start in listOf("fun FormNote(", "fun FormError(")) {
            val note = between(groups, start, "\n}\n")
            listOf(
                "val cap = rememberCapHeightPx(OctoType.formNote)",
                ".alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }",
                "modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(16.dp))",
            ).forEach { assertTrue("$start $it", note.contains(it)) }
            assertFalse(note, note.contains("padding(top = 1.dp)"))
        }
    }

    // Red when the page's scroll loses its bottom fade, a fade pops in at full
    // strength, or the callout host is not told the fade.
    @Test
    fun thePageFadesAtBothEndsAndEasesIn() {
        val fades = between(screen, "private fun Modifier.pageFades(scroll: ScrollState): Modifier = this", "\n// How tall the soft edge")
        listOf(
            "val above = (scroll.value / top).coerceIn(0f, 1f)",
            "val below = ((scroll.maxValue - scroll.value) / bottom).coerceIn(0f, 1f)",
            "Brush.verticalGradient(*easedStops(1f, 1f - below), startY = size.height - bottom, endY = size.height)",
        ).forEach { assertTrue(it, fades.contains(it)) }
        assertTrue(screen.contains(".imePadding()\n                .pageFades(scroll)\n                .verticalScroll(scroll),"))
        assertFalse(screen.contains("topFade("))
        assertTrue(screen.contains("fade = with(density) { BottomFade.roundToPx() },"))
    }

    // Every sign-in pop-up that pairs a lit answer with Cancel draws the two
    // through the one answer row (side by side while they fit, stacked
    // otherwise): the questions (Trust, Sign out, Remove) and Change
    // password, whose form cannot be drawn without its server. Red when
    // either goes back to a plain Row, where large text cuts Cancel.
    @Test
    fun everySignInPopupsAnswersShareOneRow() {
        val question = between(source("ui/common/PopupForms.kt"), "fun PopupQuestion(", "\n}\n")
        assertTrue(question, question.contains("AnswerRow(\n            main = { AccentButton(confirm, onClick = onConfirm) },\n            other = { GlazeButton(cancel, onClick = onCancel) },"))
        assertFalse(question, question.contains("Row(Modifier"))
        val password = between(source("ui/settings/ServerPage.kt"), "private fun PasswordForm(", "\n}\n")
        assertTrue(password, password.contains("AnswerRow(\n            main = { AccentButton(\"Change password\", onClick = ::change, enabled = draft.current.isNotEmpty() && draft.new.isNotEmpty() && draft.confirm.isNotEmpty(), loading = busy) },\n            other = { GlazeButton(\"Cancel\", onClick = close, enabled = !busy) },"))
        assertFalse(password, password.contains("Row(horizontalArrangement"))
    }

    // Change password's lit answer waits until all three fields hold words,
    // as every other sign-in form's does, while Enter in the last field still
    // runs the change, which names what is missing. Red when the answer is
    // ready over empty fields again, or Enter stops running the change.
    @Test
    fun changePasswordsAnswerWaitsForItsThreeFields() {
        val password = between(source("ui/settings/ServerPage.kt"), "private fun PasswordForm(", "\n}\n")
        assertTrue(password, password.contains("enabled = draft.current.isNotEmpty() && draft.new.isNotEmpty() && draft.confirm.isNotEmpty(), loading = busy"))
        assertTrue(password, password.contains("last = true, onDone = ::change"))
        assertTrue(password, password.contains("passwordDraftProblem(draft)?.let { problem = it; return }"))
    }

    // Change password's title is its pop-up's group title, as Advanced's head
    // is: the group-title size, heard as a heading. Red with body text again.
    @Test
    fun changePasswordsTitleIsAGroupTitle() {
        val password = between(source("ui/settings/ServerPage.kt"), "private fun PasswordForm(", "\n}\n")
        assertTrue(password, password.contains("Text(SettingsIndex.ChangePassword.title, style = OctoType.formGroup, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })"))
        assertFalse(password, password.contains("style = OctoType.body,"))
    }

    // Red when a password field's name lives only in its placeholder again
    // (three typed fields would be three rows of dots), in Settings or Family,
    // or Family's footer puts Cancel before the main answer.
    @Test
    fun passwordFieldsKeepTheirNamesOverThem() {
        val server = between(source("ui/settings/ServerPage.kt"), "private fun PasswordInput(", "\n}\n")
        val sheets = source("ui/family/FamilySheets.kt")
        val family = between(sheets, "private fun PasswordField(", "\n}\n")
        for (field in listOf(server, family)) {
            assertTrue(field, field.contains("Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {\n        Text(name, style = OctoType.formName, color = OctoColors.TextPrimary)"))
        }
        assertTrue(server, server.contains("placeholder = SignInWords.REQUIRED,"))
        assertTrue(family, family.contains("placeholder: String = SignInWords.REQUIRED,"))
        assertTrue(sheets, sheets.contains("PasswordField(draft.new, \"New password\", placeholder = \"At least \$MIN_PASSWORD characters\")"))
        val footer = between(sheets, "    // The main answer first and Cancel second", "\n    }\n")
        assertTrue(footer, footer.indexOf("AccentButton(CHANGE_PASSWORD") in 0 until footer.indexOf("GlazeButton(\"Cancel\""))
    }

    // Red when the join note stumbles again ("on another device on your other
    // phone"), or its last word can wrap alone.
    @Test
    fun theJoinNoteReadsInOneGo() {
        assertTrue(screen.contains("\"Your invite shows a QR code. So does Sign in on another device, in Octo on your other phone or computer. Scan it, or open its link on this${Char(0xA0)}phone.\""))
        assertFalse(screen.contains("on another device on your other phone"))
    }
}
