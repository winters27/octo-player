package app.winters.octo.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class CertificatesTest {
    // A self-made certificate for "music.test", made for this test only.
    private val cert: X509Certificate = requireNotNull(javaClass.getResourceAsStream("/certs/self-signed.pem")).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    // What openssl reports for the same file.
    private val expected = "D6:93:F0:76:D6:D6:5F:CB:02:3D:D0:52:E3:63:75:61:A6:77:2C:9C:B3:C5:1F:97:99:D3:B9:4E:3C:65:54:43"

    // Stands in for the system's trust, which never trusts a self-made certificate.
    private val distrustful = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun engineFor(host: String) = SSLContext.getDefault().createSSLEngine(host, 443)

    @Test
    fun fingerprintMatchesOpenssl() {
        assertEquals(expected, formatFingerprint(cert.fingerprint()))
        assertEquals(64, cert.fingerprint().length)
    }

    @Test
    fun fingerprintFormatting() {
        assertEquals("AB:CD:EF:01", formatFingerprint("abcdef01"))
        // Marks and spaces from a pasted fingerprint are ignored.
        assertEquals("abcdef01", normalizeFingerprint("AB:CD EF-01"))
        assertEquals("", formatFingerprint(""))
        // The SHA-256 of nothing, a known value.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Fingerprint(ByteArray(0)))
    }

    @Test
    fun aPinCountsOnlyForItsOwnHost() {
        val pins = mapOf("music.test" to cert.fingerprint())
        assertTrue(pinAllows(pins, "music.test", cert.fingerprint()))
        assertTrue(pinAllows(pins, "MUSIC.test", formatFingerprint(cert.fingerprint())))
        assertFalse(pinAllows(pins, "other.test", cert.fingerprint()))
        assertFalse(pinAllows(pins, null, cert.fingerprint()))
        assertFalse(pinAllows(pins, "music.test", "00".repeat(32)))
    }

    @Test
    fun pinnedCertificateIsTrustedForItsHost() {
        var rejected: String? = null
        val manager = PinningTrustManager(distrustful, { mapOf("music.test" to cert.fingerprint()) }) { host, _ -> rejected = host }
        manager.checkServerTrusted(arrayOf(cert), "EC", engineFor("music.test"))
        assertNull(rejected)
    }

    @Test
    fun pinnedCertificateIsRefusedOnAnotherHost() {
        var rejected: String? = null
        val manager = PinningTrustManager(distrustful, { mapOf("music.test" to cert.fingerprint()) }) { host, _ -> rejected = host }
        try {
            manager.checkServerTrusted(arrayOf(cert), "EC", engineFor("elsewhere.test"))
            fail("A pin must not carry over to another host")
        } catch (e: CertificateException) {
            assertEquals("elsewhere.test", rejected)
        }
    }

    @Test
    fun unpinnedCertificateIsRefusedAndRemembered() {
        var seen: X509Certificate? = null
        val manager = PinningTrustManager(distrustful, { emptyMap() }) { _, leaf -> seen = leaf }
        try {
            manager.checkServerTrusted(arrayOf(cert), "EC", engineFor("music.test"))
            fail("Nothing was trusted")
        } catch (e: CertificateException) {
            assertEquals(cert, seen)
        }
    }

    @Test
    fun withNoHostOnlyThePhonesTrustCounts() {
        val manager = PinningTrustManager(distrustful, { mapOf("music.test" to cert.fingerprint()) }) { _, _ -> }
        try {
            manager.checkServerTrusted(arrayOf(cert), "EC")
            fail("A pin needs a host")
        } catch (_: CertificateException) {
        }
    }
}
