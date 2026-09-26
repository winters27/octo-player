package app.winters.octo.connection

import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.SignInRequest
import app.winters.octo.data.resolveHeaders
import app.winters.octo.subsonic.AuthMode
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSettingsTest {
    private val settings = ConnectionSettings(
        home = "http://192.168.1.20:4533/".toHttpUrl(),
        authMode = AuthMode.LegacyPassword,
        headers = listOf(ServerHeader("CF-Access-Client-Id", "id-value"), ServerHeader("CF-Access-Client-Secret", "top-secret")),
        pins = mapOf("music.test" to "ab".repeat(32)),
        clientCertAlias = "my-phone-cert",
        folder = FolderChoice("2", "Audiobooks"),
    )

    // Stands in for the vault: reversible, and plainly not the input.
    private fun seal(plain: String) = "sealed:" + plain.reversed()
    private fun open(sealed: String) = sealed.removePrefix("sealed:").reversed()

    @Test
    fun roundTripsThroughDisk() {
        val text = encodeConnection(settings.sealed(::seal))
        val back = decodeConnection(text)?.opened(::open)
        assertEquals(settings, back)
    }

    @Test
    fun secretsNeverReachDiskInPlain() {
        val text = encodeConnection(settings.sealed(::seal))
        listOf("id-value", "top-secret", "my-phone-cert", "CF-Access-Client-Secret").forEach {
            assertFalse("$it is on disk in plain", it in text)
        }
        // What is not secret stays readable.
        assertTrue("192.168.1.20" in text)
        assertTrue("music.test" in text)
        assertTrue("Audiobooks" in text)
    }

    @Test
    fun emptySettingsStoreNoSecrets() {
        val stored = ConnectionSettings().sealed(::seal)
        assertNull(stored.headersSealed)
        assertNull(stored.clientCertSealed)
        assertEquals(ConnectionSettings(), decodeConnection(encodeConnection(stored))?.opened(::open))
    }

    @Test
    fun aSecretThatCannotBeOpenedLosesTheWholeConnection() {
        val stored = settings.sealed(::seal)
        assertNull(stored.opened { null })
    }

    @Test
    fun olderOrDamagedTextIsHandled() {
        // Written before a field existed: the rest still reads.
        assertEquals(AuthMode.Token, decodeConnection("""{"homeUrl":null}""")?.authMode)
        assertNull(decodeConnection("not json"))
    }

    @Test
    fun maskHidesEverythingIncludingLength() {
        assertEquals(mask("a"), mask("a much longer secret value"))
        assertFalse("secret" in mask("secret"))
        assertEquals("", mask(""))
    }

    @Test
    fun printingNeverShowsSecrets() {
        val printed = listOf(
            settings.toString(),
            ServerHeader("X-Token", "top-secret").toString(),
            HeaderDraft("X-Token", "top-secret").toString(),
            SignInRequest("music.test", "me", "top-secret").toString(),
            ClientCertChoice("my-phone-cert", setOf("music.test")).toString(),
        )
        printed.forEach { line ->
            assertFalse(line, "top-secret" in line)
            assertFalse(line, "id-value" in line)
            assertFalse(line, "my-phone-cert" in line)
        }
    }

    @Test
    fun headersAreCleanedBeforeUse() {
        val typed = listOf(
            ServerHeader(" X-Token ", " abc "),
            ServerHeader("", "no name"),
            ServerHeader("No-Value", ""),
            ServerHeader("Bad Name", "x"),
            ServerHeader("X-Line", "two\nlines"),
            ServerHeader("x-token", "later"),
        )
        assertEquals(listOf(ServerHeader("x-token", "later")), cleanHeaders(typed))
    }

    @Test
    fun savedHeaderValuesAreKeptWhenLeftEmpty() {
        val saved = listOf(ServerHeader("X-Token", "old"))
        val drafts = listOf(
            HeaderDraft("X-Token", "", saved = true),
            HeaderDraft("X-New", "new"),
        )
        assertEquals(listOf(ServerHeader("X-Token", "old"), ServerHeader("X-New", "new")), resolveHeaders(drafts, saved))
        // A saved header given a new value takes it.
        assertEquals(listOf(ServerHeader("X-Token", "fresh")), resolveHeaders(listOf(HeaderDraft("X-Token", "fresh", saved = true)), saved))
    }
}
