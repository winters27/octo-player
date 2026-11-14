package app.winters.octo

import androidx.media3.common.PlaybackException
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.SignInWords
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
            "GatewayWords.kindLabel(",
            "GatewayWords.kindHelper(",
            "GatewayWords.fieldLabels(vm.gatewayKind, vm.gateway)",
            "SignInWords.UNCHANGED",
            "GatewayWords.hint(",
            "GatewayWords.tip(",
            "GatewayWords.POINTER",
            "GatewayWords.inGroup(",
            "GatewayWords.JOIN_HINT",
            "GatewayWords.NEEDS_HTTPS",
            "SignInWords.API_KEY_IN_THE_OPEN",
            "SignInWords.HOME_NOTE",
            "SignInWords.HEADERS_DESCRIPTION",
        ).forEach { assertTrue(it, form.contains(it)) }
    }

    // Red when any sentence of the shared words (the gateway words and the
    // sign-in words) is typed out in the app instead of read from
    // GatewayWords or SignInWords (words that drift apart). Templates, which
    // hold `{name}`, are never typed out whole.
    @Test
    fun noSentenceOfTheSharedWordsIsWrittenOutInTheApp() {
        val sentences = listOf("words.json", "signin-words.json")
            .flatMap { Json.parseToJsonElement(File("../fixtures/gateway/$it").readText()).jsonObject.values }
            .map { it.jsonPrimitive.content }
            .filter { it.length >= 30 && '{' !in it }
        val app = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.path to it.readText() }.toList()
        assertTrue(sentences.any { it == SignInWords.LEGACY_HINT })
        for (sentence in sentences) {
            val written = app.filter { (_, text) -> sentence in text }.map { it.first }
            assertTrue("\"$sentence\" is written out in $written", written.isEmpty())
        }
    }

    // Red when a sign-in error is typed out again instead of read from the
    // shared words, or when the legacy words still name the old switch.
    @Test
    fun everySignInErrorIsSaidInTheSharedWords() {
        assertEquals(SignInWords.UNREACHABLE, SubsonicException.Unreachable(IOException("down")).userMessage())
        assertEquals(SignInWords.RESTARTING, SubsonicException.NotSubsonic("HTTP 502 from ping", 502).userMessage())
        assertEquals(SignInWords.NOT_MUSIC, SubsonicException.NotSubsonic("HTTP 404 from ping", 404).userMessage())
        assertEquals(SignInWords.WRONG_PASSWORD, SubsonicException.WrongCredentials("x").userMessage())
        assertEquals(SignInWords.LEGACY_HINT, SubsonicException.AuthNotSupported(41, "x").userMessage())
        assertEquals(SignInWords.WAY_REFUSED, SubsonicException.AuthNotSupported(42, "x").userMessage())
        assertEquals(SignInWords.SOMETHING_WRONG, IllegalStateException("x").userMessage())
        assertEquals(SignInWords.BAD_ADDRESS, SignInError.BadAddress.userMessage())
        assertEquals(SignInWords.BAD_HOME_ADDRESS, SignInError.BadHomeAddress.userMessage())
        assertEquals(SignInWords.MISSING_SECRET, SignInError.MissingSecret.userMessage())
        assertEquals(SignInWords.alreadyKept("Home"), SignInError.AlreadyKept("Home").userMessage())
        assertEquals(SignInWords.GONE, SignInError.Gone.userMessage())
        assertTrue(source("ui/signin/SignInViewModel.kt").contains("SignInWords.LEGACY_UNSAFE"))
        assertTrue(source("data/SessionRepository.kt").contains("SignInWords.GONE"))
        val sites = listOf("data/UserMessages.kt", "ui/signin/SignInViewModel.kt", "ui/signin/SignInScreen.kt").map(::source)
        sites.forEach { assertFalse(it.contains("Turn on Legacy sign-in")) }
    }
}
