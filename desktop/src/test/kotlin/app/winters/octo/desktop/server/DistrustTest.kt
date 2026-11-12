package app.winters.octo.desktop.server

import app.winters.octo.connection.DistrustedCertificateException
import app.winters.octo.connection.DistrustingTrustManager
import app.winters.octo.connection.distrustedIssuers
import app.winters.octo.connection.distrustedKeys
import app.winters.octo.connection.isDistrusted
import app.winters.octo.connection.keyFingerprint
import app.winters.octo.connection.platformTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

// The desktop's side of the distrust: the certificates the packaged
// runtime refuses (security/distrusted-certificates.pem, read by the jlink
// step) match the shared lists, and the JDK's own trust is cut by them.
class DistrustTest {
    private val factory = CertificateFactory.getInstance("X.509")

    // The certificates the packaged runtime refuses, as the build reads them.
    private val bundled: List<X509Certificate> = File("security/distrusted-certificates.pem").inputStream().use {
        factory.generateCertificates(it).map { cert -> cert as X509Certificate }
    }

    // The chains the public test hosts for the eMudhra keys sent, leaf first:
    // through emSign ECC Root CA - G3, and through the Sectigo cross-signs.
    private val chains: Map<String, Array<X509Certificate>> = HOSTS.flatMap { listOf(it, it.replace("-emudhra", "-aaa-emudhra")) }.associateWith { host ->
        requireNotNull(javaClass.getResourceAsStream("/certs/distrusted-chains/$host.pem")).use {
            factory.generateCertificates(it).map { cert -> cert as X509Certificate }.toTypedArray()
        }
    }

    @Test
    fun theKeysAndNamesAreExactlyThoseOfTheBundledCertificates() {
        // The eight intermediates as eMudhra issued them and as Sectigo
        // cross-signed them, and the root.
        assertEquals(17, bundled.size)
        assertEquals(distrustedKeys, bundled.map { it.keyFingerprint() }.toSet())
        assertEquals(distrustedIssuers, bundled.map { it.subjectX500Principal }.toSet())
    }

    @Test
    fun keyFingerprintsMatchWhatWasPublished() {
        val dvG3b = bundled.first { it.subjectX500Principal.name.contains("EM DV TLS CA - G3B") }
        assertEquals("ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105", dvG3b.keyFingerprint())
    }

    @Test
    fun everyBundledCertificateIsDistrusted() {
        bundled.forEach { assertTrue(it.subjectX500Principal.name, isDistrusted(it)) }
    }

    @Test
    fun theSystemsTrustedRootsLeaveOutTheDistrustedOnes() {
        val platform = platformTrustManager()
        assumeTrue("this JDK no longer carries the root", platform.acceptedIssuers.any { it.keyFingerprint() in distrustedKeys })
        val issuers = DistrustingTrustManager(platform).acceptedIssuers
        assertFalse(issuers.any { it.keyFingerprint() in distrustedKeys })
        assertEquals(platform.acceptedIssuers.size - 1, issuers.size)
    }

    @Test
    fun theSystemsOwnTrustNeverSeesADistrustedChain() {
        assertEquals(12, chains.size)
        val trust = DistrustingTrustManager(platformTrustManager())
        chains.values.forEach { chain ->
            assertThrows(DistrustedCertificateException::class.java) { trust.checkServerTrusted(chain, "ECDHE_ECDSA") }
        }
    }

    private companion object {
        val HOSTS = listOf("dv", "ov", "ev").flatMap { kind -> listOf("g3b", "g3c").map { "$kind-$it-emudhra.we-have-a-problem.com" } }
    }
}
