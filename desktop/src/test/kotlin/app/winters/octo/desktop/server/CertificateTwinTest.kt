package app.winters.octo.desktop.server

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class CertificateTwinTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer(CertFixtures.serverContext().socketFactory)
    private val another = FakeServer(CertFixtures.serverContext().socketFactory)
    private val secrets = CertStore()
    private val settingsFile get() = File(folder.root, "settings.json")
    private val reads = CopyOnWriteArrayList<Path>()
    private val release = CountDownLatch(1)

    // While set, reading this file hangs until the test ends.
    @Volatile private var hung: Path? = null

    private val reader: (Path) -> ByteArray = { path ->
        reads.add(path)
        if (path == hung) release.await()
        readCapped(path)
    }

    private val a by lazy { CertFixtures.copy("rsa2048.p12", folder.root) }
    private val b by lazy { CertFixtures.copy("second-rsa.p12", folder.root) }
    private val leafA get() = CertFixtures.leaf("rsa2048.p12")
    private val leafB get() = CertFixtures.leaf("second-rsa.p12")

    @Before
    fun answer() {
        for (each in listOf(server, another)) {
            each.answer("ping", type = "octo")
            each.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
        }
    }

    @After
    fun stop() {
        release.countDown()
        server.close()
        another.close()
    }

    private fun accounts(boundMs: Long = 3_000) = tlsAccounts(SettingsStore(settingsFile), secrets, read = reader, boundMs = boundMs)

    private suspend fun signInWith(accounts: Accounts, username: String, file: File?, at: FakeServer = server): SavedServer {
        val certificate = file?.let { CertificateDraft.Use(it.path, PASSWORD) } ?: CertificateDraft.None
        return (accounts.signIn(SignInRequest(at.address, username, "pw", certificate = certificate)) as SignInOutcome.Done).connection.server
    }

    // k (winters, with A) signed out of, as the page has it, and T (brandon)
    // kept beside it with `tFile`.
    private suspend fun kAndTwin(accounts: Accounts, tFile: File?, kFile: File? = a, kAt: FakeServer = server): Pair<SavedServer, SavedServer> {
        val t = signInWith(accounts, "brandon", tFile)
        val k = signInWith(accounts, "winters", kFile, kAt)
        accounts.signOut()
        return k to t
    }

    private suspend fun onTwin(accounts: Accounts, draft: CertificateDraft, at: FakeServer = server) =
        accounts.signIn(SignInRequest(at.address, "brandon", "pw", certificate = draft)) as SignInOutcome.Done

    @Test
    fun keepOnATwinWithItsOwnReadableCertificateKeepsItsOwn() = runTest {
        val accounts = accounts()
        val (k, t) = kAndTwin(accounts, b)
        val done = onTwin(accounts, CertificateDraft.Keep(k.id))
        assertEquals(t.id, done.connection.server.id)
        assertEquals(CertFile(b.path, "second-rsa.p12"), done.connection.server.certificate)
        assertEquals(CertSecret(b.path, PASSWORD), secrets.item(t.id))
        assertEquals(leafB, accounts.security.identityFingerprint)
    }

    @Test
    fun keepOnATwinWhoseItemIsGoneTakesTheOneThatJustWorked() = runTest {
        val accounts = accounts()
        val (k, t) = kAndTwin(accounts, b)
        secrets.kept.remove("certificate@${t.id}")
        val done = onTwin(accounts, CertificateDraft.Keep(k.id))
        assertEquals(CertFile(a.path, "rsa2048.p12"), done.connection.server.certificate)
        assertEquals(CertSecret(a.path, PASSWORD), secrets.item(t.id))
        assertEquals(leafA, accounts.security.identityFingerprint)
        assertEquals("k keeps its own", CertSecret(a.path, PASSWORD), secrets.item(k.id))
    }

    @Test
    fun keepOnATwinWithNoCertificateTakesTheOneThatJustWorked() = runTest {
        val accounts = accounts()
        val (k, t) = kAndTwin(accounts, null)
        val done = onTwin(accounts, CertificateDraft.Keep(k.id))
        assertEquals(CertFile(a.path, "rsa2048.p12"), done.connection.server.certificate)
        assertEquals(CertSecret(a.path, PASSWORD), secrets.item(t.id))
        assertEquals(leafA, accounts.security.identityFingerprint)
    }

    // k has none, so the try sent none: it acts like None, and T keeps its
    // file even though its item is gone.
    @Test
    fun keepOfAServerWithNoneOnATwinWhoseItemIsGoneKeepsTheTwinsFile() = runTest {
        val accounts = accounts()
        val (k, t) = kAndTwin(accounts, b, kFile = null)
        secrets.kept.remove("certificate@${t.id}")
        val done = onTwin(accounts, CertificateDraft.Keep(k.id))
        assertEquals(CertFile(b.path, "second-rsa.p12"), done.connection.server.certificate)
        assertNull(accounts.security.identityFingerprint)
        assertTrue(done.note!!.contains(CertificateWords.needsPassword("second-rsa.p12")))
    }

    // k's certificate is for another port, so the try sent none: it acts
    // like None.
    @Test
    fun keepWhoseOriginDidNotMatchOnATwinWhoseItemIsGoneKeepsTheTwinsFile() = runTest {
        val accounts = accounts()
        val t = signInWith(accounts, "brandon", b, another)
        val k = signInWith(accounts, "winters", a)
        accounts.signOut()
        secrets.kept.remove("certificate@${t.id}")
        val done = onTwin(accounts, CertificateDraft.Keep(k.id), at = another)
        assertEquals(t.id, done.connection.server.id)
        assertEquals(CertFile(b.path, "second-rsa.p12"), done.connection.server.certificate)
        assertNull(secrets.item(t.id))
        assertNull(accounts.security.identityFingerprint)
    }

    // T's file on a drive that hangs: T keeps its file and item, this run
    // goes on with k's, and the note says why.
    @Test
    fun keepOnATwinWhoseFileIsOutOfReachGoesOnWithTheOneThatWorkedForThisRun() = runTest {
        val accounts = accounts(boundMs = 50)
        val (k, t) = kAndTwin(accounts, b)
        hung = Path.of(b.path)
        val done = onTwin(accounts, CertificateDraft.Keep(k.id))
        assertEquals(leafA, accounts.security.identityFingerprint)
        assertTrue(done.note!!.contains(CertificateWords.notFound("second-rsa.p12")))
        val later = accounts()
        assertEquals(CertFile(b.path, "second-rsa.p12"), later.find(t.id)!!.certificate)
        assertEquals(CertSecret(b.path, PASSWORD), secrets.item(t.id))
    }

    @Test
    fun keepOnATwinWhoseStoreFailsToReadGoesOnWithTheOneThatWorkedForThisRun() = runTest {
        val accounts = accounts()
        val (k, t) = kAndTwin(accounts, b)
        secrets.failReads = { it == "certificate@${t.id}" }
        val done = onTwin(accounts, CertificateDraft.Keep(k.id))
        assertEquals(leafA, accounts.security.identityFingerprint)
        assertTrue(done.note!!.contains(CertificateWords.storeUnread("Test store", "locked")))
        secrets.failReads = { false }
        assertEquals(CertFile(b.path, "second-rsa.p12"), accounts().find(t.id)!!.certificate)
        assertEquals(CertSecret(b.path, PASSWORD), secrets.item(t.id))
    }

    @Test
    fun noneOnATwinWithAReadableCertificateUsesTheTwinsOwn() = runTest {
        val accounts = accounts()
        kAndTwin(accounts, b)
        onTwin(accounts, CertificateDraft.None)
        assertEquals(leafB, accounts.security.identityFingerprint)
    }

    @Test
    fun noneOnATwinWhoseFileIsGoneConnectsWithoutAndSaysWhy() = runTest {
        val accounts = accounts()
        val (_, t) = kAndTwin(accounts, b)
        assertTrue(b.delete())
        val done = onTwin(accounts, CertificateDraft.None)
        assertNull(accounts.security.identityFingerprint)
        assertEquals(CertFile(b.path, "second-rsa.p12"), done.connection.server.certificate)
        assertTrue(done.note!!.contains(CertificateWords.notFound("second-rsa.p12")))
        assertEquals(CertSecret(b.path, PASSWORD), secrets.item(t.id))
    }

    // Remove names k; on T it acts like None.
    @Test
    fun removeOnThePageThenAnotherUsernameLandingOnATwinLeavesTheTwinsOwn() = runTest {
        val accounts = accounts()
        val (k, t) = kAndTwin(accounts, b)
        val done = onTwin(accounts, CertificateDraft.Remove(k.id))
        assertEquals(CertFile(b.path, "second-rsa.p12"), done.connection.server.certificate)
        assertEquals(CertSecret(b.path, PASSWORD), secrets.item(t.id))
        assertEquals(leafB, accounts.security.identityFingerprint)
        assertEquals("k keeps its own", CertSecret(a.path, PASSWORD), secrets.item(k.id))
    }

    // Red when the twin found after the try loads k's certificate a second
    // time.
    @Test
    fun aPageSignInToTheSameAccountAfterASignOutReadsTheFileOnce() = runTest {
        val accounts = accounts()
        val k = signInWith(accounts, "winters", a)
        accounts.signOut()
        reads.clear()
        accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = CertificateDraft.Keep(k.id))) as SignInOutcome.Done
        assertEquals(listOf(Path.of(a.path)), reads.toList())
        assertEquals(leafA, accounts.security.identityFingerprint)
    }

    @Test
    fun anAddLandingOnATwinReadsNothing() = runTest {
        val accounts = accounts()
        kAndTwin(accounts, b)
        reads.clear()
        accounts.add(SignInRequest(server.address, "brandon", "pw")) as SignInOutcome.Saved
        assertTrue(reads.isEmpty())
    }
}
