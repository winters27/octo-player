package app.winters.octo.connection

import app.winters.octo.data.userMessage
import app.winters.octo.subsonic.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.net.ssl.SSLHandshakeException

class PhoneTrustTest {
    // Ways to reach the network without the app's trust. Android fills in
    // certificates a server left out from those any client in the app saw,
    // so one client without the distrust check is enough to let a
    // distrusted authority through for all of them.
    private val unchecked = listOf(
        Regex("""OkHttpClient\.Builder\(\)"""),
        Regex("""OkHttpClient\(\)"""),
        Regex("""SSLContext\.getInstance"""),
        Regex("""\.sslSocketFactory\("""),
        Regex("""HttpsURLConnection|HttpURLConnection|openConnection\("""),
        Regex("""DefaultHttpDataSource"""),
        Regex("""DefaultDataSource\.Factory\(context\)"""),
    )

    // Where those are allowed, and why.
    private val allowed = mapOf(
        // The shared client and its kin, set up by ConnectionSecurity.
        "di/NetworkModule.kt" to "security::install",
        "connection/ServerClients.kt" to "security::install",
        "connection/ConnectionSecurity.kt" to "DistrustingTrustManager(",
        "connection/PhoneTrust.kt" to "DistrustingTrustManager(",
        // Clients of their own, with the phone's trust.
        "update/AppUpdates.kt" to ".phoneTls()",
        "di/LiveRadioModule.kt" to ".phoneTls()",
        // Plays kept files only.
        "animated/LoopPlayers.kt" to """address.scheme == "file"""",
    )

    @Test
    fun everyClientUsesTheAppsTrust() {
        val root = File("src/main/java/app/winters/octo")
        val found = root.walk().filter { it.extension == "kt" }.mapNotNull { file ->
            val text = file.readText()
            val hits = unchecked.filter { it.containsMatchIn(text) }
            if (hits.isEmpty()) return@mapNotNull null
            val name = file.relativeTo(root).invariantSeparatorsPath
            val reason = allowed[name]
            if (reason != null && reason in text) null else "$name: ${hits.joinToString { it.pattern }}"
        }.toList()
        assertTrue("A network client without the app's trust:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun theCheckStillSeesTheFiles() {
        // A moved source folder would make the check above pass on nothing.
        assertTrue(File("src/main/java/app/winters/octo/connection/PhoneTrust.kt").isFile)
    }

    @Test
    fun aDistrustedServerSaysSo() {
        val handshake = SSLHandshakeException("handshake failed").apply { initCause(DistrustedCertificateException()) }
        assertEquals(DISTRUSTED_MESSAGE, SubsonicException.Unreachable(handshake).userMessage())
        assertEquals(
            "Couldn't reach the server. Check the address, and that you're on the right network.",
            SubsonicException.Unreachable(SSLHandshakeException("other")).userMessage(),
        )
    }
}
