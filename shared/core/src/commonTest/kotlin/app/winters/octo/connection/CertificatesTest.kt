package app.winters.octo.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.tls.HeldCertificate
import org.junit.Test
import java.io.File
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
        val pins = mapOf("music.test" to cert.pin())
        assertTrue(pinAllows(pins, "music.test", cert))
        assertTrue(pinAllows(pins, "MUSIC.test", cert))
        assertFalse(pinAllows(pins, "other.test", cert))
        assertFalse(pinAllows(pins, null, cert))
        assertFalse(pinAllows(mapOf("music.test" to KEY_PIN_MARK + "00".repeat(32)), "music.test", cert))
    }

    // Pins kept before key pins, the SHA-256 of the whole certificate in
    // either way it was written, still load and count, for that
    // certificate only.
    @Test
    fun anOldWholeCertificatePinStillCounts() {
        assertTrue(pinAllows(mapOf("music.test" to cert.fingerprint()), "music.test", cert))
        assertTrue(pinAllows(mapOf("music.test" to formatFingerprint(cert.fingerprint())), "music.test", cert))
        val renewed = HeldCertificate.Builder().commonName("music.test").build().certificate
        assertFalse(pinAllows(mapOf("music.test" to cert.fingerprint()), "music.test", renewed))
    }

    // A server that renews its certificate with the same key (step-ca's
    // renew does) stays trusted; a new key does not.
    @Test
    fun aKeyPinSurvivesARenewalWithTheSameKey() {
        val first = HeldCertificate.Builder().commonName("music.test").ecdsa256().serialNumber(1).build()
        val renewed = HeldCertificate.Builder().commonName("music.test").keyPair(first.keyPair).serialNumber(2).build()
        val newKey = HeldCertificate.Builder().commonName("music.test").serialNumber(3).build()
        val pins = mapOf("music.test" to first.certificate.pin())
        assertTrue(first.certificate.fingerprint() != renewed.certificate.fingerprint())
        assertTrue(pinAllows(pins, "music.test", renewed.certificate))
        assertFalse(pinAllows(pins, "music.test", newKey.certificate))
    }

    // The same pins every app keeps, from fixtures/certificate-pins (made
    // with openssl; the iPhone's tests read it too).
    @Test
    fun pinsMatchTheSharedFixture() {
        val folder = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/certificate-pins") }
            .first { it.isDirectory }
        val certificates = Json.parseToJsonElement(File(folder, "pins.json").readText()).jsonObject.getValue("certificates").jsonObject
        val read = { name: String ->
            File(folder, "$name.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        }
        for ((name, values) in certificates) {
            val leaf = read(name)
            assertEquals(name, values.jsonObject.getValue("fingerprint").jsonPrimitive.content, leaf.fingerprint())
            assertEquals(name, values.jsonObject.getValue("pin").jsonPrimitive.content, leaf.pin())
        }
        assertTrue(pinAllows(mapOf("music.example.com" to read("first").pin()), "music.example.com", read("renewed")))
        assertFalse(pinAllows(mapOf("music.example.com" to read("first").pin()), "music.example.com", read("new-key")))
    }

    @Test
    fun aKeyPinKeepsItsMark() {
        val hex = "ab".repeat(32)
        assertEquals(KEY_PIN_MARK + hex, normalizePin(" SPKI:" + formatFingerprint(hex)))
        assertEquals(hex, normalizePin(formatFingerprint(hex)))
        assertTrue(cert.pin().startsWith(KEY_PIN_MARK))
        assertEquals(cert.pin(), normalizePin(cert.pin()))
    }

    // A pinned host showing another certificate is turned down and told
    // about, and reads as a changed certificate rather than a stranger.
    @Test
    fun aChangedCertificateOnAPinnedHostIsTurnedDownAndKnownAsAChange() {
        val pins = mapOf("music.test" to cert.pin())
        val changed = HeldCertificate.Builder().commonName("music.test").build().certificate
        var told: Pair<String, X509Certificate>? = null
        val manager = PinningTrustManager(distrustful, { pins }) { host, leaf -> told = host to leaf }
        try {
            manager.checkServerTrusted(arrayOf(changed), "EC", engineFor("music.test"))
            fail("A changed certificate is never let through by the old pin")
        } catch (_: CertificateException) {
        }
        assertEquals("music.test" to changed, told)
        assertTrue(isPinnedHost(pins, "MUSIC.test"))
        assertFalse(isPinnedHost(pins, "other.test"))
        assertFalse(isPinnedHost(pins, null))
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
