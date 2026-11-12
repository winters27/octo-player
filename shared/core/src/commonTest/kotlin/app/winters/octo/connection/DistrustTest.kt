package app.winters.octo.connection

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

class DistrustTest {
    private val factory = CertificateFactory.getInstance("X.509")

    private fun certs(path: String): List<X509Certificate> = requireNotNull(javaClass.getResourceAsStream(path)).use {
        factory.generateCertificates(it).map { cert -> cert as X509Certificate }
    }

    // The chains the public test hosts for the eMudhra keys sent, leaf first:
    // through emSign ECC Root CA - G3, and through the Sectigo cross-signs.
    private val chains: Map<String, Array<X509Certificate>> = HOSTS.flatMap { listOf(it, it.replace("-emudhra", "-aaa-emudhra")) }.associateWith { host ->
        certs("/certs/distrusted-chains/$host.pem").toTypedArray()
    }

    private val root = certs("/certs/distrusted-chains/emsign-ecc-root-ca-g3.pem").single()

    // A self-made certificate for "music.test", nothing to do with eMudhra.
    private val ordinary = certs("/certs/self-signed.pem").single()

    // Stands in for a system that trusts every chain, so only the distrust is tested.
    private val trusting = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(root, ordinary)
    }

    // Stands in for a system that trusts nothing.
    private val distrustful = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun engineFor(host: String) = SSLContext.getDefault().createSSLEngine(host, 443)

    private fun refuses(check: () -> Unit) {
        assertThrows(DistrustedCertificateException::class.java) { check() }
    }

    @Test
    fun theKeysAreThoseTheTestHostsUse() {
        val intermediates = chains.values.flatMap { it.drop(1) }
        // Each host's issuing certificate, whichever way it is signed.
        for (chain in chains.values) assertTrue(chain[1].subjectX500Principal.name, chain[1].keyFingerprint() in distrustedKeys)
        assertTrue(root.keyFingerprint() in distrustedKeys)
        // The six TLS keys and the root are all used by the hosts; the two
        // code signing keys are not, so the list holds those beside them.
        assertEquals(7, (intermediates.map { it.keyFingerprint() }.filter { it in distrustedKeys } + root.keyFingerprint()).toSet().size)
        // The cross-sign the direct chains use is caught by its issuer's name.
        assertTrue(intermediates.filter { it.keyFingerprint() !in distrustedKeys }.all(::isDistrusted))
        assertEquals(9, distrustedKeys.size)
    }

    @Test
    fun everyTestChainIsRefusedEvenWhenTheSystemTrustsIt() {
        val trust = DistrustingTrustManager(trusting)
        for ((host, chain) in chains) {
            refuses { trust.checkServerTrusted(chain, "ECDHE_ECDSA") }
            refuses { trust.checkServerTrusted(chain, "ECDHE_ECDSA", engineFor(host)) }
        }
    }

    @Test
    fun aLeafSentAloneIsRefusedByItsIssuersName() {
        val trust = DistrustingTrustManager(trusting)
        for (chain in chains.values) refuses { trust.checkServerTrusted(arrayOf(chain[0]), "ECDHE_ECDSA") }
    }

    @Test
    fun theChainTheSystemBuiltIsCheckedToo() {
        // The server sent an ordinary looking chain; the system completed it
        // up to the distrusted root from certificates it kept.
        val trust = DistrustingTrustManager(trusting) { chain, _, _ -> chain.toList() + root }
        refuses { trust.checkServerTrusted(arrayOf(ordinary), "ECDHE_ECDSA", engineFor("music.test")) }
    }

    @Test
    fun anOrdinaryChainPasses() {
        var asked = 0
        val trust = DistrustingTrustManager(trusting) { chain, _, _ -> chain.toList().also { asked++ } }
        trust.checkServerTrusted(arrayOf(ordinary), "ECDHE_ECDSA", engineFor("music.test"))
        trust.checkServerTrusted(arrayOf(ordinary), "ECDHE_ECDSA")
        assertEquals(2, asked)
    }

    @Test
    fun aPinCannotLetOneThroughOrBeAskedAbout() {
        for ((host, chain) in chains) {
            val asked = mutableListOf<String>()
            val pins = mapOf(pinKey(host) to chain[0].fingerprint())
            val trust = DistrustingTrustManager(PinningTrustManager(distrustful, { pins }) { at, _ -> asked += at })
            refuses { trust.checkServerTrusted(chain, "ECDHE_ECDSA", engineFor(host)) }
            assertTrue(asked.isEmpty())
        }
    }

    @Test
    fun pinningNeverTurnsADistrustedRefusalIntoAQuestion() {
        // However the two are put together, a refusal for a distrusted
        // authority comes back as it is: no pin, no question.
        val refusing = object : X509TrustManager by distrustful {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = throw DistrustedCertificateException()
        }
        val asked = mutableListOf<String>()
        val pins = mapOf(pinKey("music.test") to ordinary.fingerprint())
        val trust = PinningTrustManager(refusing, { pins }) { at, _ -> asked += at }
        refuses { trust.checkServerTrusted(arrayOf(ordinary), "ECDHE_ECDSA", engineFor("music.test")) }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aPinnedCertificateStillWorks() {
        // The system does not trust a self-made certificate, so there is no
        // built chain; the user's pin for exactly this one still counts.
        val pins = mapOf(pinKey("music.test") to ordinary.fingerprint())
        val trust = DistrustingTrustManager(PinningTrustManager(distrustful, { pins }) { _, _ -> }) { _, _, _ -> throw CertificateException("not trusted") }
        trust.checkServerTrusted(arrayOf(ordinary), "ECDHE_ECDSA", engineFor("music.test"))
    }

    @Test
    fun theDistrustedRootIsNeverOffered() {
        val issuers = DistrustingTrustManager(trusting).acceptedIssuers.toList()
        assertEquals(listOf(ordinary), issuers)
    }

    @Test
    fun anOwnClientUsesTheDistrustingTrust() {
        val client = OkHttpClient.Builder().distrustingTls().build()
        val trust = client.x509TrustManager as X509ExtendedTrustManager
        refuses { trust.checkServerTrusted(chains.values.first(), "ECDHE_ECDSA", engineFor("music.test")) }
    }

    @Test
    fun aDistrustedRefusalIsFoundHoweverItWasWrapped() {
        val handshake = SSLHandshakeException("handshake failed").apply { initCause(DistrustedCertificateException()) }
        assertTrue(handshake.isDistrusted())
        assertTrue(IOException("route 2 failed").apply { addSuppressed(handshake) }.isDistrusted())
        assertTrue(IOException(IOException(handshake)).isDistrusted())
        assertFalse(SSLHandshakeException("other").apply { initCause(CertificateException("no")) }.isDistrusted())
    }

    private companion object {
        val HOSTS = listOf("dv", "ov", "ev").flatMap { kind -> listOf("g3b", "g3c").map { "$kind-$it-emudhra.we-have-a-problem.com" } }
    }
}
