package app.winters.octo.desktop.server

import app.winters.octo.audio.ClientCertificate
import app.winters.octo.connection.SignInWords
import app.winters.octo.desktop.audio.AudioEngine
import app.winters.octo.desktop.audio.FakeEngine
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.SavedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

class EngineCertificateTest {
    @get:Rule val folder = TemporaryFolder()

    private fun open(name: String) =
        openCertificate(CertFixtures.file(name).readBytes(), PASSWORD, name, CertFixtures.NOW, ::engineKeyProblem)

    // Red when a key the engine can't present is taken at pick time and
    // only fails when a song plays. The engine's library answers with no
    // engine open.
    @Test
    fun theEnginesLibraryTakesTheKeysItCanPresentAndTheOthersAreRefusedWhenPicked() {
        for (name in listOf("rsa2048.p12", "p256.p12", "p384.p12", "ed25519.p12", "keytool-ec.p12")) {
            assertTrue(name, open(name) is CertificateOpened.Found)
        }
        for (name in listOf("p521.p12", "rsapss.p12", "rsa1024.p12")) {
            assertEquals(name, SignInWords.CERT_KEY_KIND, (open(name) as CertificateOpened.Refused).words)
        }
    }

    // The server in use when Octo last closed, kept with the certificate
    // file `name` and its password, as the next start finds it.
    private fun keptWith(name: String): Accounts {
        val dir = folder.newFolder()
        val settings = startOn(File(dir, "settings.json"))
        val secrets = CertStore()
        val file = CertFixtures.copy(name, dir)
        val kept = SavedServer("https://music.test/", "winters", id = "k", certificate = CertFile(file.path, name))
        settings.update { it.copy(servers = listOf(kept), activeServer = "k") }
        secrets.kept[secretAccount("winters", kept.address)] = "pw"
        secrets.putItem("k", CertSecret(file.path, PASSWORD))
        return Accounts(settings, secrets, OkHttpClient())
    }

    // The start checks the kept certificate with the engine's own library,
    // before any engine is attached: a key the JDK opens but the engine
    // cannot sign with (P-521) keeps the server from opening, and its
    // sign-in page asks for the certificate; a key the engine takes opens.
    // Red when the start asks an engine that is not attached yet, which
    // takes every key, so each song fails later with nothing said.
    @Test
    fun theStartChecksTheKeptKeyWithTheEnginesOwnLibrary() {
        val refused = keptWith("p521.p12")
        assertNull("the server with a key the engine cannot sign with is not opened", refused.restore())
        val ask = refused.takeStartAsk() ?: throw AssertionError("no sign-in page asks at the start")
        assertEquals("k", ask.id)
        assertEquals(SignInWords.CERT_KEY_KIND, ask.words)
        assertTrue("it asks for the certificate", ask.certificate)
        assertNotNull("a key the engine takes opens", keptWith("rsa2048.p12").restore())
    }

    // A chosen file, and the certificate the row shows, are opened off the
    // window's thread: the key's check, made once the file is open, runs
    // there. Red when the file is opened on the thread that asked for it.
    @Test
    fun aCertificateIsOpenedOffTheWindowsThread() {
        val onWindow = CopyOnWriteArrayList<Boolean>()
        val accounts = Accounts(
            startOn(File(folder.newFolder(), "settings.json")),
            CertStore(),
            OkHttpClient(),
            engineCheck = { _, _ ->
                onWindow.add(SwingUtilities.isEventDispatchThread())
                null
            },
        )
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        runBlocking(Dispatchers.Swing) {
            assertTrue("asked from the window's thread", SwingUtilities.isEventDispatchThread())
            assertTrue(accounts.openPicked(file.path, PASSWORD) is CertificateOpened.Found)
            assertTrue(accounts.loadChoice(CertificateChoice.Picked(file.path, PASSWORD)) is CertificateLoad.Found)
        }
        assertEquals("opened twice, neither on the window's thread", listOf(false, false), onWindow.toList())
    }

    @Test
    fun theEngineIsToldOnceAndAgainOnlyOnAChange() {
        val certificate = (open("rsa2048.p12") as CertificateOpened.Found).certificate
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
        val certificate = (open("rsa2048.p12") as CertificateOpened.Found).certificate
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
        val certificate = (open("rsa2048.p12") as CertificateOpened.Found).certificate
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
