package app.winters.octo

import androidx.media3.common.PlaybackException
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.hasGatewaySignIn
import app.winters.octo.data.SignInError
import app.winters.octo.data.userMessage
import app.winters.octo.playback.PlayFailure
import app.winters.octo.playback.playFailureWords
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.SignInPageAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

// Every place the Android app speaks of a sign-in page in front of the
// server says it in GatewayWords, the words all three apps share.
@RunWith(RobolectricTestRunner::class)
class GatewayWordSitesTest {
    // Red when userMessage loses its gateway branch.
    @Test
    fun aSignInPageAnswerIsNamedInTheSharedWords() {
        for (gateway in Gateway.entries) {
            for (sent in listOf(false, true)) {
                val answer = SubsonicException.NotSubsonic("HTTP 401 from ping", 401, gateway, sent)
                assertEquals(GatewayWords.failure(gateway, sent), answer.userMessage())
                assertEquals(GatewayWords.failure(gateway, sent), SignInError.Failed(answer).userMessage())
            }
        }
    }

    // Red when the form's problem is reworded.
    @Test
    fun theFormsProblemIsShownAsItIs() {
        assertEquals(GatewayWords.NEEDS_HTTPS, SignInError.Problem(GatewayWords.NEEDS_HTTPS).userMessage())
    }

    // Red when playFailureWords does not read the cause chain, or when an
    // ordinary failure loses its own words.
    @Test
    fun aSongAnsweredByASignInPageSaysSo() {
        val error = PlaybackException("Source error", IOException("open", SignInPageAnswer(null, 200)), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(GatewayWords.PLAY_FAILURE, playFailureWords(error))
        val gone = PlaybackException("Source error", FileNotFoundException("gone"), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertEquals(PlayFailure.Missing.words, playFailureWords(gone))
    }

    // Red when the share line follows any header, or only exact-case names.
    @Test
    fun theShareLineFollowsTheServersGatewayHeaders() {
        assertTrue(ConnectionSettings(headers = listOf(ServerHeader("cf-access-client-id", "id"))).hasGatewaySignIn)
        assertFalse(ConnectionSettings(headers = listOf(ServerHeader("X-Token", "abc"))).hasGatewaySignIn)
    }

    private fun source(path: String) = File("src/main/java/app/winters/octo/$path").readText()

    // Red when a word site stops using GatewayWords (the share line or a
    // form line removed, say).
    @Test
    fun everyAndroidWordSiteReadsGatewayWords() {
        assertTrue(source("data/UserMessages.kt").contains("GatewayWords.failureFor("))
        assertTrue(source("ui/admin/OctoAdminViewModel.kt").contains("GatewayWords.failure("))
        assertTrue(source("playback/OctoPlaybackService.kt").contains("playFailureWords("))
        assertTrue(source("ui/server/ShareSheet.kt").contains("GatewayWords.SHARE_LINE"))
        val form = source("ui/signin/SignInScreen.kt")
        listOf(
            "GatewayWords.LABEL_SECTION",
            "GatewayWords.LABEL_CAPTION",
            "GatewayWords::kindLabel",
            "GatewayWords.LABEL_SAVED",
            "GatewayWords.hint(",
            "GatewayWords.JOIN_HINT",
            "GatewayWords.NEEDS_HTTPS",
        ).forEach { assertTrue(it, form.contains(it)) }
    }

    // Red when any sentence of the shared words is typed out in the app
    // instead of read from GatewayWords (words that drift apart).
    @Test
    fun noSentenceOfTheSharedWordsIsWrittenOutInTheApp() {
        val sentences = Json.parseToJsonElement(File("../fixtures/gateway/words.json").readText()).jsonObject.values
            .map { it.jsonPrimitive.content }
            .filter { it.length >= 30 }
        val app = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.path to it.readText() }.toList()
        assertTrue(sentences.isNotEmpty())
        for (sentence in sentences) {
            val written = app.filter { (_, text) -> sentence in text }.map { it.first }
            assertTrue("\"$sentence\" is written out in $written", written.isEmpty())
        }
    }
}
