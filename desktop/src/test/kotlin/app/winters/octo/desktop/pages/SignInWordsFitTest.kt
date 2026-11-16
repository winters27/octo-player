package app.winters.octo.desktop.pages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.desktop.a11y.A11yScene
import app.winters.octo.subsonic.gateway.Gateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The shared sign-in words as the desktop page lays them out: Advanced's
// line keeps to one line, and the pointer under Sign in, which wraps, never
// ends a line on one word.
class SignInWordsFitTest {
    @get:Rule val folder = TemporaryFolder()

    // How the node showing exactly `words` lays them out.
    private fun A11yScene.laidOut(words: String): TextLayoutResult {
        val node = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == words } == true }
        val laid = mutableListOf<TextLayoutResult>()
        onUi { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(laid) }
        return laid.single()
    }

    // The words on a layout's last line.
    private fun TextLayoutResult.lastLineWords(): List<String> {
        val last = lineCount - 1
        return layoutInput.text.text.substring(getLineStart(last), getLineEnd(last)).split(' ', ' ').filter { it.isNotBlank() }
    }

    // The page draws in the system's own face. In Windows' Segoe UI the line
    // keeps to one line on the card; in Linux's wider faces (DejaVu Sans,
    // Noto Sans) it runs about 10 past the card's 358 and wraps, its last two
    // words kept together. Red with "sign-in method" in the line: two lines
    // on the page's card in Segoe UI, "certificate" alone on the second.
    @Test(timeout = 120_000)
    fun advancedsLineKeepsToOneLineOnThePage() {
        A11yScene(folder.newFolder(), reduceMotion = true, signedOut = true).use { s ->
            s.settle(600).close()
            val line = s.laidOut(SignInWords.ADVANCED_LINE)
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertEquals(1, line.lineCount)
            } else {
                assertTrue("${line.lineCount} lines, the last ${line.lastLineWords()}", line.lineCount == 1 || (line.lineCount == 2 && line.lastLineWords().size >= 2))
            }
        }
    }

    // The pointer under Sign in, calling the sign-in page group out, wraps on
    // the page's card, its last line two words or more. Red when its last two
    // words are not kept together ("Advanced." alone).
    @Test(timeout = 120_000)
    fun thePointerNeverEndsALineOnOneWord() {
        A11yScene(folder.newFolder(), height = 1_200, reduceMotion = true, signedOut = true).use { s ->
            s.onUi {
                val form = s.app.signInForm
                form.typeAddress("https://music.example.com")
                form.result = false to GatewayWords.failure(Gateway.Authentik, false)
                form.failed(form.attemptAddress, Gateway.Authentik)
            }
            s.settle(800).close()
            val pointer = s.laidOut(GatewayWords.POINTER)
            assertTrue("${pointer.lineCount} lines, the last ${pointer.lastLineWords()}", pointer.lineCount == 1 || pointer.lastLineWords().size >= 2)
        }
    }
}
