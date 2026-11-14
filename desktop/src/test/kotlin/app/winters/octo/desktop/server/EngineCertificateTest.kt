package app.winters.octo.desktop.server

import app.winters.octo.audio.ClientCertificate
import app.winters.octo.connection.SignInWords
import app.winters.octo.desktop.audio.AudioEngine
import app.winters.octo.desktop.audio.FakeEngine
import app.winters.octo.desktop.audio.NativeAudioEngine
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCertificateTest {
    private fun open(name: String, identity: EngineIdentity) =
        openCertificate(CertFixtures.file(name).readBytes(), PASSWORD, name, CertFixtures.NOW, identity::problemWith)

    // Red when a key the engine can't present is taken at pick time and
    // only fails when a song plays.
    @Test
    fun theRealEngineTakesTheKeysItCanPresentAndTheOthersAreRefusedWhenPicked() {
        NativeAudioEngine.open(silent = true).use { engine ->
            val identity = EngineIdentity().apply { attach(engine) }
            for (name in listOf("rsa2048.p12", "p256.p12", "p384.p12", "ed25519.p12", "keytool-ec.p12")) {
                assertTrue(name, open(name, identity) is CertificateOpened.Found)
            }
            for (name in listOf("p521.p12", "rsapss.p12", "rsa1024.p12")) {
                assertEquals(name, SignInWords.CERT_KEY_KIND, (open(name, identity) as CertificateOpened.Refused).words)
            }
        }
    }

    @Test
    fun theEngineIsToldOnceAndAgainOnlyOnAChange() {
        val certificate = (open("rsa2048.p12", EngineIdentity()) as CertificateOpened.Found).certificate
        val origins = certificateOrigins("https://music.test/".toHttpUrl(), null)
        val engine = FakeEngine()
        val identity = EngineIdentity()
        identity.use(certificate, origins)
        identity.attach(engine)
        assertEquals(listOf("certificate 1"), engine.calls.filter { it.startsWith("certificate") })
        identity.use(certificate, origins)
        assertEquals(1, engine.calls.count { it.startsWith("certificate") })
        identity.use(certificate, certificateOrigins("https://other.test/".toHttpUrl(), null))
        assertEquals(2, engine.calls.count { it.startsWith("certificate") })
        assertEquals("other.test", engine.certificate!!.origins.single().host)
        identity.use(null, emptySet())
        assertEquals("certificate none", engine.calls.last())
    }

    @Test
    fun anIpv6OriginGoesToTheEngineInBrackets() {
        val certificate = (open("rsa2048.p12", EngineIdentity()) as CertificateOpened.Found).certificate
        val engine = FakeEngine()
        EngineIdentity().apply { attach(engine) }.use(certificate, certificateOrigins("https://[::1]:8443/".toHttpUrl(), null))
        val origin = engine.certificate!!.origins.single()
        assertEquals("[::1]", origin.host)
        assertEquals(8443, origin.port.toInt())
        assertEquals("https", origin.scheme)
    }

    // A refusal never escapes, and the next try asks again.
    @Test
    fun aCertificateTheEngineRefusesLeavesItWithNone() {
        val certificate = (open("rsa2048.p12", EngineIdentity()) as CertificateOpened.Found).certificate
        val inner = FakeEngine()
        val refusing = object : AudioEngine by inner {
            var asked = 0

            override fun setClientCertificate(certificate: ClientCertificate?) {
                asked++
                if (certificate != null) throw IllegalStateException("refused")
                inner.setClientCertificate(null)
            }
        }
        val identity = EngineIdentity().apply { attach(refusing) }
        identity.use(certificate, certificateOrigins("https://music.test/".toHttpUrl(), null))
        identity.use(certificate, certificateOrigins("https://music.test/".toHttpUrl(), null))
        assertEquals("asked again after a refusal", 3, refusing.asked)
    }
}
