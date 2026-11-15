package app.winters.octo.desktop.server

import app.winters.octo.connection.SignInWords
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.CertFixtures.AFTER
import app.winters.octo.desktop.server.CertFixtures.BEFORE
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.currentOs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.NoSuchFileException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ClientCertificatesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun open(name: String, password: String = PASSWORD, now: Instant = CertFixtures.NOW, problem: (List<ByteArray>, ByteArray) -> String? = { _, _ -> null }) =
        openCertificate(CertFixtures.file(name).readBytes(), password, name, now, problem)

    private fun found(name: String, password: String = PASSWORD): LoadedCertificate = (open(name, password) as CertificateOpened.Found).certificate

    private fun refused(name: String, password: String = PASSWORD, now: Instant = CertFixtures.NOW) = open(name, password, now) as CertificateOpened.Refused

    // Red when the leaf, name or key of the entry taken is not the file's.
    @Test
    fun aClientCertificateOpensWithItsPassword() {
        val certificate = found("rsa2048.p12")
        assertEquals(CertFixtures.leaf("rsa2048.p12"), certificate.fingerprint)
        assertEquals("rsa2048.p12", certificate.name)
        assertEquals(1, certificate.chain.size)
        assertEquals("RSA", certificate.key.algorithm)
    }

    @Test
    fun theWrongPasswordAsksForItAgain() {
        val wrong = refused("rsa2048.p12", "not it")
        assertTrue(wrong.wrongPassword)
        assertEquals(SignInWords.CERT_WRONG_PASSWORD, wrong.words)
    }

    // The form tries an empty password first: it opens a file saved with
    // none, and asks for the password of any other.
    @Test
    fun anEmptyPasswordOpensAFileSavedWithNoneAndAsksForAnyOther() {
        assertEquals(CertFixtures.leaf("empty-password.p12", ""), found("empty-password.p12", "").fingerprint)
        assertTrue(refused("rsa2048.p12", "").wrongPassword)
    }

    @Test
    fun anOpenSslFileWithOldEncryptionOpens() {
        assertEquals(CertFixtures.leaf("legacy.p12"), found("legacy.p12").fingerprint)
    }

    @Test
    fun aKeytoolEcFileOpensWithItsWholeChain() {
        val certificate = found("keytool-ec.p12")
        assertEquals("EC", certificate.key.algorithm)
        assertEquals(2, certificate.chain.size)
    }

    @Test
    fun aFileThatIsNotACertificateIsRefused() {
        val text = refused("not-a-certificate.p12")
        assertFalse(text.wrongPassword)
        assertEquals(SignInWords.CERT_NOT_A_FILE, text.words)
    }

    @Test
    fun aFileWithNoKeyIsRefused() {
        assertEquals(SignInWords.CERT_NO_KEY, refused("no-key.p12").words)
    }

    @Test
    fun aCertificateOnlyForServersIsRefused() {
        assertEquals(SignInWords.CERT_NOT_FOR_CLIENTS, refused("server-auth-only.p12").words)
    }

    // Red when the first entry is taken whatever it is for.
    @Test
    fun ofSeveralEntriesTheOneForClientsIsTaken() {
        assertEquals(CertFixtures.leaf("two-entries.p12", alias = "client"), found("two-entries.p12").fingerprint)
    }

    @Test
    fun anExpiredCertificateIsRefused() {
        assertEquals(SignInWords.CERT_EXPIRED, refused("rsa2048.p12", now = AFTER).words)
    }

    @Test
    fun aCertificateNotYetValidIsRefused() {
        assertEquals(SignInWords.CERT_NOT_YET_VALID, refused("rsa2048.p12", now = BEFORE).words)
    }

    // The engine is asked with the whole chain and the key in its own form,
    // and a key it turns down is refused when it is picked.
    @Test
    fun aKeyTheEngineTurnsDownIsRefusedWhenPicked() {
        var asked: Pair<Int, ByteArray>? = null
        val engineSays = open("chain.p12") { chain, key -> asked = chain.size to key; "no" } as CertificateOpened.Refused
        assertEquals(SignInWords.CERT_KEY_KIND, engineSays.words)
        assertEquals(2, asked!!.first)
        val taken = found("chain.p12")
        assertArrayEquals(taken.engineKey, asked!!.second)
    }

    @Test
    fun aFileOverTheCapIsRefusedUnread() {
        val big = openCertificate(ByteArray(CERT_FILE_CAP + 1), "", "big.p12", CertFixtures.NOW) { _, _ -> null } as CertificateOpened.Refused
        assertEquals(SignInWords.CERT_TOO_BIG, big.words)
        val file = File(folder.root, "big.p12").apply { writeBytes(ByteArray(CERT_FILE_CAP + 1)) }
        assertThrows(CertificateTooBig::class.java) { readCapped(file.toPath()) }
        val exact = File(folder.root, "exact.p12").apply { writeBytes(ByteArray(CERT_FILE_CAP)) }
        assertEquals(CERT_FILE_CAP, readCapped(exact.toPath()).size)
        assertThrows(NoSuchFileException::class.java) { readCapped(File(folder.root, "gone.p12").toPath()) }
    }

    // Neither a password nor key bytes ever print.
    @Test
    fun noSecretEverPrints() {
        val certificate = found("rsa2048.p12")
        val keyHex = certificate.key.encoded.joinToString("") { "%02x".format(it) }.take(40)
        val engineHex = certificate.engineKey.joinToString("") { "%02x".format(it) }.take(40)
        val texts = listOf(
            certificate.toString(),
            PickedCertificate("C:/certs/x.p12", "hunter22", certificate).toString(),
            CertificateDraft.Use("C:/certs/x.p12", "hunter22").toString(),
            CertificateChoice.Picked("C:/certs/x.p12", "hunter22").toString(),
            CertSecret("C:/certs/x.p12", "hunter22").toString(),
            SignInRequest("https://music.test", "winters", "hunter22", certificate = CertificateDraft.Use("C:/certs/x.p12", "hunter22")).toString(),
            CertificateTry(CertificateChoice.Picked("C:/certs/x.p12", "hunter22"), CertificateLoad.Found(certificate)).toString(),
            EngineIdentity().apply { use(certificate, certificateOrigins("https://music.test/".toHttpUrl(), null)) }.toString(),
        )
        for (text in texts) {
            assertFalse(text, text.contains("hunter22"))
            assertFalse(text, text.contains(keyHex))
            assertFalse(text, text.contains(engineHex))
        }
    }

    private val file = CertFile("C:/certs/k.p12", "k.p12")
    private val k = SavedServer("https://music.test/", "winters", home = "http://192.168.1.20:4533/", id = "k", certificate = file)

    private fun accountsWith(vararg servers: SavedServer): Accounts {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        settings.update { it.copy(servers = servers.toList()) }
        return Accounts(settings, SessionOnlySecrets(), OkHttpClient())
    }

    private fun url(text: String) = text.toHttpUrl()

    @Test
    fun keepGoesToTheReplacedServerWhateverItsAddressIsNow() {
        val accounts = accountsWith(k)
        assertEquals(CertificateChoice.Saved("k", file), accounts.certificateFor(CertificateDraft.Keep("k"), "k", url("https://other.test/"), null))
    }

    // With nothing replaced, only the first https address the request goes
    // to counts, and only when it is one of k's own: host and port.
    @Test
    fun keepWithNothingReplacedGoesOnlyToTheServersOwnHttpsOrigin() {
        val accounts = accountsWith(k)
        val keep = CertificateDraft.Keep("k")
        assertEquals(CertificateChoice.Saved("k", file), accounts.certificateFor(keep, null, url("https://music.test/"), null))
        assertEquals("the default port is the port", CertificateChoice.Saved("k", file), accounts.certificateFor(keep, null, url("https://MUSIC.test:443/"), null))
        assertEquals("another port", CertificateChoice.None, accounts.certificateFor(keep, null, url("https://music.test:8443/"), null))
        assertEquals("another host", CertificateChoice.None, accounts.certificateFor(keep, null, url("https://other.test/"), null))
        assertEquals("a server that is not kept", CertificateChoice.None, accounts.certificateFor(CertificateDraft.Keep("gone"), null, url("https://music.test/"), null))
    }

    // Red when an http address counts as a mismatch (the usual http LAN home
    // would stop k's certificate) or carries one.
    @Test
    fun plainHttpNeverCarriesOneAndNeverStopsAMatch() {
        val accounts = accountsWith(k)
        val keep = CertificateDraft.Keep("k")
        assertEquals(CertificateChoice.Saved("k", file), accounts.certificateFor(keep, null, url("https://music.test/"), url("http://192.168.1.20:4533/")))
        assertEquals(CertificateChoice.None, accounts.certificateFor(keep, "k", url("http://music.test/"), null))
        assertEquals(CertificateChoice.None, accounts.certificateFor(CertificateDraft.Use("C:/certs/x.p12", ""), null, url("http://music.test/"), url("http://192.168.1.20/")))
        assertEquals("no address yet", CertificateChoice.None, accounts.certificateFor(keep, "k", null, url("https://music.test/")))
    }

    // A server with an http main and an https home still carries its
    // certificate to that home; with an https main, the main is looked at
    // first, so a foreign home never matches.
    @Test
    fun anHttpMainWithAnHttpsHomeMatchesAtTheHome() {
        val h = k.copy(address = "http://music.test/", home = "https://home.test/", id = "h")
        val accounts = accountsWith(h)
        val keep = CertificateDraft.Keep("h")
        assertEquals(CertificateChoice.Saved("h", file), accounts.certificateFor(keep, null, url("http://music.test/"), url("https://home.test/")))
        assertEquals(CertificateChoice.None, accounts.certificateFor(keep, null, url("https://elsewhere.test/"), url("https://home.test/")))
    }

    // A link's home is no reason to hold the certificate back: it becomes
    // part of the record the keep saves.
    @Test
    fun aForeignHomeDoesNotStopTheMatchOnTheMain() {
        val accounts = accountsWith(k)
        assertEquals(CertificateChoice.Saved("k", file), accounts.certificateFor(CertificateDraft.Keep("k"), null, url("https://music.test/"), url("https://far.test/")))
    }

    @Test
    fun noneAndRemoveSendNothingAndUseSendsThePickedFile() {
        val accounts = accountsWith(k)
        val https = url("https://music.test/")
        assertEquals(CertificateChoice.None, accounts.certificateFor(CertificateDraft.None, "k", https, null))
        assertEquals(CertificateChoice.None, accounts.certificateFor(CertificateDraft.Remove("k"), "k", https, null))
        assertEquals(CertificateChoice.Picked("C:/certs/x.p12", "pw"), accounts.certificateFor(CertificateDraft.Use("C:/certs/x.p12", "pw"), null, https, null))
    }

    @Test
    fun ipv6AddressesCompareAsHttpUrlNamesThem() {
        val six = k.copy(address = "https://[::1]:8443/", home = null, id = "six")
        val accounts = accountsWith(six)
        assertEquals(TlsOrigin("::1", 8443), TlsOrigin.of(url("https://[::1]:8443/")))
        assertEquals(CertificateChoice.Saved("six", file), accounts.certificateFor(CertificateDraft.Keep("six"), null, url("https://[0:0:0:0:0:0:0:1]:8443/"), null))
    }

    // The row shows what the request sends; with no https address it shows
    // a file the record keeps only for Use and for the replaced server.
    @Test
    fun theRowShowsWhatTheRequestSendsAndAFileOnlyHttpsCarries() {
        val accounts = accountsWith(k)
        val keep = CertificateDraft.Keep("k")
        assertEquals(CertificateShown.Sends(CertificateChoice.Saved("k", file)), accounts.certificateShown(keep, null, url("https://music.test/"), null))
        assertEquals(CertificateShown.HttpsOnly("k.p12"), accounts.certificateShown(keep, "k", url("http://music.test/"), null))
        assertEquals("not the record the keep lands on", CertificateShown.None, accounts.certificateShown(keep, null, url("http://music.test/"), null))
        assertEquals(CertificateShown.HttpsOnly("x.p12"), accounts.certificateShown(CertificateDraft.Use("C:/certs/x.p12", ""), null, url("http://music.test/"), null))
        assertEquals("an https address that matches nothing", CertificateShown.None, accounts.certificateShown(keep, null, url("https://other.test/"), null))
        assertEquals("no address", CertificateShown.None, accounts.certificateShown(CertificateDraft.Use("C:/certs/x.p12", ""), null, null, null))
    }

    @Test
    fun aCertificateGoesOnlyWhereAnAddressIsHttps() {
        assertTrue(sendsCertificate(k, url("https://music.test/"), url("http://192.168.1.20/")))
        assertTrue(sendsCertificate(k, url("http://music.test/"), url("https://home.test/")))
        assertFalse(sendsCertificate(k, url("http://music.test/"), url("http://192.168.1.20/")))
        assertFalse(sendsCertificate(k.copy(certificate = null), url("https://music.test/"), null))
        assertEquals(setOf(TlsOrigin("music.test", 443)), certificateOrigins(url("https://music.test/"), url("http://192.168.1.20:4533/")))
    }

    // One read per path at a time, and a finished read lets go of the path.
    @Test
    fun aReadUnderWayIsSharedAndAFinishedOneLetsGo() = runTest {
        val started = AtomicInteger()
        val release = CountDownLatch(1)
        val path = File(folder.root, "slow.p12").toPath()
        val reads = CertificateReads({ started.incrementAndGet(); release.await(5, TimeUnit.SECONDS); byteArrayOf(1, 2, 3) }, 5_000)
        val first = async(Dispatchers.IO) { reads.bytes(path) }
        val second = async(Dispatchers.IO) { reads.bytes(path) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (started.get() == 0) {
            check(System.nanoTime() < deadline) { "the read never started" }
            Thread.onSpinWait()
        }
        while (reads.joins == 0) {
            check(System.nanoTime() < deadline) { "the second read never joined" }
            Thread.onSpinWait()
        }
        assertEquals(setOf(path), reads.reading)
        release.countDown()
        assertArrayEquals(byteArrayOf(1, 2, 3), first.await())
        assertArrayEquals(byteArrayOf(1, 2, 3), second.await())
        assertEquals(1, started.get())
        assertEquals(1, reads.joins)
        assertTrue(reads.reading.isEmpty())
    }

    @Test
    fun aRunWithAFolderOfItsOwnFilesItsItemsApart() {
        val usual = certificateAccountFor(null, currentOs())
        assertEquals("certificate@k", usual("k"))
        val profile = File(folder.root, "profile").apply { mkdirs() }
        val apart = certificateAccountFor(profile, currentOs())
        assertNotEquals(usual("k"), apart("k"))
        assertEquals(apart("k"), certificateAccountFor(profile, currentOs())("k"))
        assertNotEquals(apart("k"), certificateAccountFor(File(folder.root, "another"), currentOs())("k"))
    }

    // Red when the name is made from the path as typed, not the real one
    // lowercased: two names for one folder would file two items.
    @Test
    fun twoSpellingsOfOneFolderOnWindowsShareAnAccountName() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        File(folder.root, "Prof").mkdirs()
        val one = certificateAccountFor(File(folder.root, "Prof"), DesktopOs.Windows)("k")
        val other = certificateAccountFor(File(folder.root, "PROF"), DesktopOs.Windows)("k")
        assertEquals(one, other)
    }
}
