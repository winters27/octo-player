package app.winters.octo.connection

import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.SubsonicException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class LegacySignInTest {
    private val noTokens = SubsonicException.AuthNotSupported(41, "Token authentication not supported")

    @Test
    fun aServerThatCannotCheckTokensIsTriedAgainWhereThePasswordIsSafe() {
        assertEquals(LegacyRetry.Retry, legacyRetry(noTokens, AuthMode.Token, "https://music.example.com/".toHttpUrl()))
        assertEquals(LegacyRetry.Retry, legacyRetry(noTokens, AuthMode.Token, "http://192.168.1.20:4533/".toHttpUrl()))
        assertEquals(LegacyRetry.Retry, legacyRetry(noTokens, AuthMode.Token, "http://nas.local/".toHttpUrl()))
    }

    @Test
    fun plainHttpOutsideHomeIsNeverTriedWithThePassword() {
        assertEquals(LegacyRetry.Unsafe, legacyRetry(noTokens, AuthMode.Token, "http://music.example.com/".toHttpUrl()))
    }

    @Test
    fun anythingElseChangesNothing() {
        val url = "https://music.example.com/".toHttpUrl()
        assertEquals(LegacyRetry.None, legacyRetry(SubsonicException.AuthNotSupported(42, "no"), AuthMode.Token, url))
        assertEquals(LegacyRetry.None, legacyRetry(SubsonicException.WrongCredentials("no"), AuthMode.Token, url))
        assertEquals(LegacyRetry.None, legacyRetry(SubsonicException.Unreachable(IOException("down")), AuthMode.Token, url))
        assertEquals(LegacyRetry.None, legacyRetry(null, AuthMode.Token, url))
        // Already sending the password, or a key: nothing to try.
        assertEquals(LegacyRetry.None, legacyRetry(noTokens, AuthMode.LegacyPassword, url))
        assertEquals(LegacyRetry.None, legacyRetry(noTokens, AuthMode.ApiKey, url))
    }
}
