package app.winters.octo.desktop.server

import app.winters.octo.connection.SignInWords
import app.winters.octo.subsonic.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

// The desktop's sign-in sites read the shared sign-in words where they used
// to type them out.
class SignInWordSitesTest {
    // Red when userMessage types a sign-in error out again, or the legacy
    // words name the old switch.
    @Test
    fun aFailedRequestIsSaidInTheSharedWords() {
        assertEquals(SignInWords.UNREACHABLE, SubsonicException.Unreachable(IOException("down")).userMessage())
        assertEquals(SignInWords.RESTARTING, SubsonicException.NotSubsonic("HTTP 502 from ping", 502).userMessage())
        assertEquals(SignInWords.NOT_MUSIC, SubsonicException.NotSubsonic("HTTP 404 from ping", 404).userMessage())
        assertEquals(SignInWords.WRONG_PASSWORD, SubsonicException.WrongCredentials("x").userMessage())
        assertEquals(SignInWords.LEGACY_HINT, SubsonicException.AuthNotSupported(41, "x").userMessage())
        assertEquals(SignInWords.WAY_REFUSED, SubsonicException.AuthNotSupported(42, "x").userMessage())
        assertEquals(SignInWords.SOMETHING_WRONG, IllegalStateException("x").userMessage())
    }

    private fun source(path: String) = File("src/main/kotlin/app/winters/octo/desktop/$path").readText().replace("\r\n", "\n")

    // Red when a sign-in site types the old legacy words, or the sign-in
    // page fields lose the names of the sign-in page met.
    @Test
    fun theSignInSitesReadTheSharedWords() {
        val accounts = source("server/Accounts.kt")
        assertTrue(accounts.contains("SignInWords.LEGACY_UNSAFE"))
        assertTrue(accounts.contains("SignInWords.LEGACY_HINT"))
        assertFalse(accounts.contains("Turn on Legacy sign-in"))
        val page = source("pages/SignInPage.kt")
        assertTrue(page.contains("GatewayWords.fieldLabels(form.gatewayKind, form.detected)"))
        assertTrue(page.contains("SignInWords.UNCHANGED"))
    }
}
