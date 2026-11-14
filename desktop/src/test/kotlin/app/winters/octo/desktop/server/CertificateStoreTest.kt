package app.winters.octo.desktop.server

import app.winters.octo.connection.SignInWords
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.server.Reach
import app.winters.octo.server.freshServerId
import app.winters.octo.subsonic.AuthMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CertificateStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer(CertFixtures.serverContext().socketFactory)
    private val secrets = CertStore()
    private val settingsFile get() = File(folder.root, "settings.json")
    private val reads = CopyOnWriteArrayList<Path>()
    private val counting: (Path) -> ByteArray = { reads.add(it); readCapped(it) }
    private val release = CountDownLatch(1)

    @Before
    fun answer() {
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
    }

    @After
    fun stop() {
        release.countDown()
        server.close()
    }

    private fun accounts(
        settings: SettingsStore = SettingsStore(settingsFile),
        store: SecretStore = secrets,
        read: (Path) -> ByteArray = counting,
        boundMs: Long = 3_000,
    ) = tlsAccounts(settings, store, read = read, boundMs = boundMs)

    private fun file(name: String = "rsa2048.p12", named: String = name) = CertFixtures.copy(name, folder.root, named)

    private fun use(file: File, password: String = PASSWORD) = CertificateDraft.Use(file.path, password)

    private suspend fun signInWith(accounts: Accounts, file: File, password: String = PASSWORD, username: String = "winters"): SignInOutcome.Done =
        accounts.signIn(SignInRequest(server.address, username, "pw", certificate = use(file, password))) as SignInOutcome.Done

    private suspend fun loaded(accounts: Accounts, id: String): CertificateLoad? =
        accounts.loadChoice(CertificateChoice.Saved(id, accounts.find(id)!!.certificate!!))

    private fun unreadable(load: CertificateLoad?) = load as CertificateLoad.Unreadable

    private val rsa get() = CertFixtures.leaf("rsa2048.p12")

    @Test
    fun aPickedFileAndItsPasswordSurviveARestart() = runTest {
        val cert = file()
        val done = signInWith(accounts(), cert)
        val id = done.connection.server.id
        assertEquals(CertFile(cert.path, "rsa2048.p12"), done.connection.server.certificate)
        assertEquals(CertSecret(cert.path, PASSWORD), secrets.item(id))
        assertFalse("never in the settings", settingsFile.readText().contains(PASSWORD))
        val later = accounts()
        assertEquals(id, later.restore()!!.server.id)
        assertEquals(rsa, later.security.identityFingerprint)
    }

    @Test
    fun anEmptyPasswordSurvivesARestartToo() = runTest {
        val cert = file("empty-password.p12")
        val id = signInWith(accounts(), cert, password = "").connection.server.id
        assertEquals(CertSecret(cert.path, ""), secrets.item(id))
        val later = accounts()
        assertNotNull(later.restore())
        assertEquals(CertFixtures.leaf("empty-password.p12", ""), later.security.identityFingerprint)
    }

    @Test
    fun aSignedOutServerStillLoadsItsCertificateAfterARestart() = runTest {
        val cert = file()
        val accounts = accounts()
        val id = signInWith(accounts, cert).connection.server.id
        accounts.signOut()
        assertEquals("a sign-out keeps the item", CertSecret(cert.path, PASSWORD), secrets.item(id))
        val later = accounts()
        assertTrue(later.find(id)!!.signedOut)
        assertEquals(CertFile(cert.path, "rsa2048.p12"), later.find(id)!!.certificate)
        assertTrue(loaded(later, id) is CertificateLoad.Found)
    }

    // Red when a sign-out clears this run's copy.
    @Test
    fun aSignOutKeepsTheRunsOwnCopy() = runTest {
        secrets.refuseWrites = { it.startsWith("certificate@") }
        val cert = file()
        val accounts = accounts()
        val id = signInWith(accounts, cert).connection.server.id
        assertNull(secrets.item(id))
        accounts.signOut()
        assertTrue(loaded(accounts, id) is CertificateLoad.Found)
    }

    @Test
    fun rememberPasswordOffStillKeepsTheCertificate() = runTest {
        val cert = file()
        val done = accounts().signIn(SignInRequest(server.address, "winters", "pw", rememberPassword = false, certificate = use(cert))) as SignInOutcome.Done
        val id = done.connection.server.id
        assertNull(secrets.kept[secretAccount("winters", done.connection.server.address)])
        assertEquals(CertSecret(cert.path, PASSWORD), secrets.item(id))
        assertTrue(loaded(accounts(), id) is CertificateLoad.Found)
    }

    // A file out of reach at the start: not signed in, still the one in
    // use, the password and item kept, and the next start with the file
    // back signs in.
    @Test
    fun aFileOutOfReachAtTheStartAsksAndComesBackWithTheFile() = runTest {
        val cert = file()
        val id = signInWith(accounts(), cert).connection.server.id
        val away = File(folder.root, "away.p12")
        assertTrue(cert.renameTo(away))
        val later = accounts()
        assertNull(later.restore())
        val ask = later.takeStartAsk()!!
        assertEquals(id, ask.id)
        assertTrue(ask.certificate)
        assertEquals(CertificateWords.notFound("rsa2048.p12"), ask.words)
        assertEquals("still the one in use", id, SettingsStore(settingsFile).current.activeServer)
        assertEquals(CertFile(cert.path, "rsa2048.p12"), later.find(id)!!.certificate)
        assertEquals(CertSecret(cert.path, PASSWORD), secrets.item(id))
        assertEquals("pw", secrets.kept[secretAccount("winters", later.find(id)!!.address)])
        assertTrue(away.renameTo(cert))
        val again = accounts()
        assertNotNull(again.restore())
        assertEquals(rsa, again.security.identityFingerprint)
    }

    @Test
    fun aFileThatMayNotBeReadGivesThePermissionWords() = runTest {
        val id = signInWith(accounts(), file()).connection.server.id
        val later = accounts(read = { throw AccessDeniedException(it.toString()) })
        val load = unreadable(loaded(later, id))
        assertEquals(CertificateWords.notAllowed("rsa2048.p12"), load.words)
        assertFalse(load.again)
        assertNotNull(secrets.item(id))
    }

    // A drive that hangs: the drive words once the bound is up, and a second
    // load while the read is still stuck waits on it rather than starting
    // another.
    @Test
    fun aHungDriveGivesTheDriveWordsAndIsReadOnce() = runTest {
        val cert = file()
        val id = signInWith(accounts(), cert).connection.server.id
        val started = AtomicInteger()
        val threads = CopyOnWriteArrayList<String>()
        val later = accounts(read = { started.incrementAndGet(); threads += Thread.currentThread().name; release.await(); readCapped(it) }, boundMs = 50)
        assertEquals(CertificateWords.notFound("rsa2048.p12"), unreadable(loaded(later, id)).words)
        assertEquals(CertificateWords.notFound("rsa2048.p12"), unreadable(loaded(later, id)).words)
        assertEquals(1, started.get())
        assertEquals(listOf("octo-cert-read"), threads)
        assertEquals(setOf(Path.of(cert.path)), later.certificateReading)
    }

    @Test
    fun aFileOverTheCapIsRefused() = runTest {
        val big = File(folder.root, "big.p12").apply { writeBytes(ByteArray(CERT_FILE_CAP + 1)) }
        val refused = accounts().openPicked(big.path, "") as CertificateOpened.Refused
        assertEquals(SignInWords.CERT_TOO_BIG, refused.words)
    }

    @Test
    fun aWrongStoredPasswordAsksForTheFileAgain() = runTest {
        val cert = file()
        val id = signInWith(accounts(), cert).connection.server.id
        secrets.putItem(id, CertSecret(cert.path, "not it"))
        val load = unreadable(loaded(accounts(), id))
        assertEquals(CertificateWords.needsPassword("rsa2048.p12"), load.words)
        assertTrue(load.again)
    }

    // Red when an item naming another file is opened: after a crash between
    // the settings and the store, that would be a file the person did not
    // pick last.
    @Test
    fun anItemForAnotherFileAsksForTheFileAgain() = runTest {
        val cert = file()
        val id = signInWith(accounts(), cert).connection.server.id
        secrets.putItem(id, CertSecret(file("second-rsa.p12").path, PASSWORD))
        val load = unreadable(loaded(accounts(), id))
        assertEquals(CertificateWords.needsPassword("rsa2048.p12"), load.words)
        assertTrue(load.again)
    }

    @Test
    fun aStoreThatFailsToReadGivesTheUnlockWordsAndDeletesNothing() = runTest {
        val id = signInWith(accounts(), file()).connection.server.id
        secrets.failReads = { it.startsWith("certificate@") }
        val load = unreadable(loaded(accounts(), id))
        assertEquals(CertificateWords.storeUnread("Test store", "locked"), load.words)
        assertFalse(load.again)
        assertNotNull(secrets.kept["certificate@$id"])
    }

    @Test
    fun removeTakesTheCertificateOffItsServer() = runTest {
        val cert = file()
        val accounts = accounts()
        val first = signInWith(accounts, cert).connection.server
        val done = accounts.edit(first.id, SignInRequest(server.address, "winters", "", certificate = CertificateDraft.Remove(first.id))) as SignInOutcome.Done
        assertNull(done.connection.server.certificate)
        assertNull(secrets.item(first.id))
        assertNull(accounts.security.identityFingerprint)
        assertTrue("the person's file stays", cert.exists())
    }

    @Test
    fun removingTheServerDeletesItsItem() = runTest {
        val cert = file()
        val accounts = accounts()
        val id = signInWith(accounts, cert).connection.server.id
        accounts.remove(id)
        assertNull(secrets.item(id))
        assertTrue(cert.exists())
    }

    @Test
    fun removingAServerWithNoCertificateDeletesAnItemLeftUnderItsId() = runTest {
        val accounts = accounts()
        val done = accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        val id = done.connection.server.id
        assertNull(done.connection.server.certificate)
        secrets.putItem(id, CertSecret("left.p12", "x"))
        accounts.remove(id)
        assertNull(secrets.item(id))
    }

    @Test
    fun anEditToAnotherUsernameCarriesTheCertificateToTheNewId() = runTest {
        val cert = file()
        val accounts = accounts()
        val first = signInWith(accounts, cert).connection.server
        val done = accounts.edit(first.id, SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Keep(first.id))) as SignInOutcome.Done
        val moved = done.connection.server
        assertNotEquals(first.id, moved.id)
        assertEquals(CertFile(cert.path, "rsa2048.p12"), moved.certificate)
        assertEquals(CertSecret(cert.path, PASSWORD), secrets.item(moved.id))
        assertNull("the old item goes", secrets.item(first.id))
        val later = accounts()
        assertEquals(moved.id, later.restore()!!.server.id)
        assertEquals(rsa, later.security.identityFingerprint)
    }

    // The file's drive is not connected during the move: the new id still
    // gets the file and the item, and loads it once the drive is back.
    @Test
    fun anIdMoveWithTheFileOutOfReachStillCarriesIt() = runTest {
        val cert = file()
        val accounts = accounts()
        val first = signInWith(accounts, cert).connection.server
        val away = File(folder.root, "away.p12")
        assertTrue(cert.renameTo(away))
        val done = accounts.edit(first.id, SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Keep(first.id))) as SignInOutcome.Done
        assertEquals(CertFile(cert.path, "rsa2048.p12"), done.connection.server.certificate)
        assertNull("signed in without it for now", accounts.security.identityFingerprint)
        assertTrue(done.note!!.contains(CertificateWords.notFound("rsa2048.p12")))
        assertTrue(away.renameTo(cert))
        val later = accounts()
        assertEquals(done.connection.server.id, later.restore()!!.server.id)
        assertEquals(rsa, later.security.identityFingerprint)
    }

    @Test
    fun aNewFileWithAnotherUsernameGoesUnderTheNewIdAndTheOldItemGoes() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        val second = file("second-rsa.p12")
        val done = accounts.edit(first.id, SignInRequest(server.address, "brandon", "pw2", certificate = use(second))) as SignInOutcome.Done
        assertEquals(CertSecret(second.path, PASSWORD), secrets.item(done.connection.server.id))
        assertNull(secrets.item(first.id))
        assertEquals(CertFixtures.leaf("second-rsa.p12"), accounts.security.identityFingerprint)
    }

    @Test
    fun removeWithAnotherUsernameLeavesNoItemAnywhere() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        val done = accounts.edit(first.id, SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Remove(first.id))) as SignInOutcome.Done
        assertNull(done.connection.server.certificate)
        assertNull(secrets.item(done.connection.server.id))
        assertNull(secrets.item(first.id))
    }

    // Red when a failed read is taken for "no item" and the move goes on,
    // leaving the new id with nothing to read.
    @Test
    fun aStoreThatFailsDuringAnIdMoveChangesNothing() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        secrets.failReads = { it == "certificate@${first.id}" }
        val failed = accounts.edit(first.id, SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Keep(first.id))) as SignInOutcome.Failed
        assertEquals(CertificateWords.CARRY_UNREAD, failed.message)
        assertEquals(listOf(first.id), accounts.servers.map { it.id })
        assertFalse(secrets.writes.any { it.startsWith("brandon@") })
        secrets.failReads = { false }
        assertNotNull("the old item stays", secrets.item(first.id))
    }

    @Test
    fun anApiKeyOfAnotherAccountTakesTheCertificateToItsOwnServer() = runTest {
        server.answer("tokenInfo", """"tokenInfo":{"username":"other"}""", type = "octo")
        val accounts = accounts()
        val cert = file()
        val first = signInWith(accounts, cert).connection.server
        val again = SignInRequest(server.address, "", "key-of-other", mode = AuthMode.ApiKey, certificate = CertificateDraft.Keep(first.id))
        val other = (accounts.signInAgain(first.id, again, sameAccount = true) as SignInOutcome.Done).connection.server
        assertNotEquals(first.id, other.id)
        assertEquals("other", other.username)
        assertEquals(CertFile(cert.path, "rsa2048.p12"), other.certificate)
        assertEquals(CertSecret(cert.path, PASSWORD), secrets.item(other.id))
        assertEquals("the first keeps its own", CertSecret(cert.path, PASSWORD), secrets.item(first.id))
        assertEquals(CertFile(cert.path, "rsa2048.p12"), accounts.find(first.id)!!.certificate)
    }

    @Test
    fun theSameWithAStoreThatKeepsOnlyForTheRun() = runTest {
        server.answer("tokenInfo", """"tokenInfo":{"username":"other"}""", type = "octo")
        val accounts = accounts(store = SessionOnlySecrets())
        val first = signInWith(accounts, file()).connection.server
        val again = SignInRequest(server.address, "", "key-of-other", mode = AuthMode.ApiKey, certificate = CertificateDraft.Keep(first.id))
        val other = (accounts.signInAgain(first.id, again, sameAccount = true) as SignInOutcome.Done).connection.server
        assertTrue(loaded(accounts, first.id) is CertificateLoad.Found)
        assertTrue(loaded(accounts, other.id) is CertificateLoad.Found)
    }

    // A brand new id at k's origin with k's item unreadable: refused, with
    // nothing kept.
    @Test
    fun aNewServerWhoseCarriedItemCannotBeReadIsRefused() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        accounts.signOut()
        secrets.failReads = { it == "certificate@${first.id}" }
        val failed = accounts.signIn(SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Keep(first.id))) as SignInOutcome.Failed
        assertEquals(CertificateWords.CARRY_UNREAD, failed.message)
        assertEquals(listOf(first.id), accounts.servers.map { it.id })
    }

    // k's item gone: the new server gets the file alone and asks for it as k
    // does; k still has its file.
    @Test
    fun aNewServerWhoseCarriedItemIsGoneGetsTheFileAndAsksAsTheFirstDoes() = runTest {
        val cert = file()
        val accounts = accounts()
        val first = signInWith(accounts, cert).connection.server
        accounts.signOut()
        secrets.kept.remove("certificate@${first.id}")
        val made = (accounts.signIn(SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Keep(first.id))) as SignInOutcome.Done).connection.server
        assertEquals(CertFile(cert.path, "rsa2048.p12"), made.certificate)
        assertNull(secrets.item(made.id))
        assertEquals(CertificateWords.needsPassword("rsa2048.p12"), unreadable(loaded(accounts, made.id)).words)
        assertEquals(CertificateWords.needsPassword("rsa2048.p12"), unreadable(loaded(accounts, first.id)).words)
    }

    // Red when the carry leaves an item an earlier Add left under the new
    // id, which would then be read as this one.
    @Test
    fun aCarryWithNothingToCopyDeletesWhatSitsUnderTheNewId() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        accounts.signOut()
        secrets.kept.remove("certificate@${first.id}")
        val coming = freshServerId("brandon", first.address, accounts.servers.map { it.id }.toSet())
        secrets.putItem(coming, CertSecret(file("second-rsa.p12").path, PASSWORD))
        val made = (accounts.signIn(SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Keep(first.id))) as SignInOutcome.Done).connection.server
        assertEquals(coming, made.id)
        assertNull(secrets.item(coming))
    }

    @Test
    fun aRefusedWriteKeepsTheCertificateForTheRunAndSaysSo() = runTest {
        secrets.refuseWrites = { it.startsWith("certificate@") }
        val accounts = accounts()
        val done = signInWith(accounts, file())
        assertTrue(done.note!!.contains(CertificateWords.writeRefused("full")))
        assertTrue("a password that was kept is still remembered", done.remembered)
        assertTrue(loaded(accounts, done.connection.server.id) is CertificateLoad.Found)
    }

    @Test
    fun aRefusedWriteThenAnotherFileThatSaves() = runTest {
        secrets.refuseWrites = { it.startsWith("certificate@") }
        val accounts = accounts()
        val id = signInWith(accounts, file()).connection.server.id
        secrets.refuseWrites = { false }
        val second = file("second-rsa.p12")
        accounts.edit(id, SignInRequest(server.address, "winters", "", certificate = use(second))) as SignInOutcome.Done
        assertEquals(CertSecret(second.path, PASSWORD), secrets.item(id))
        val load = loaded(accounts(), id) as CertificateLoad.Found
        assertEquals(CertFixtures.leaf("second-rsa.p12"), load.certificate.fingerprint)
    }

    @Test
    fun aSessionOnlyStoreSaysTheCertificateIsAskedForEachTime() = runTest {
        val done = signInWith(accounts(store = SessionOnlySecrets()), file())
        assertTrue(done.note!!.contains(CertificateWords.SESSION_ONLY))
    }

    @Test
    fun aDeleteTheStoreRefusesSaysSo() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        secrets.refuseDeletes = { it.startsWith("certificate@") }
        val done = accounts.edit(first.id, SignInRequest(server.address, "winters", "", certificate = CertificateDraft.Remove(first.id))) as SignInOutcome.Done
        assertTrue(done.note!!.contains(CertificateWords.deleteRefused("Test store", "locked")))
        assertNull(done.connection.server.certificate)
    }

    // Red when the Test of a sheet switched to API key sends the saved
    // password as the key.
    @Test
    fun aSheetSwitchedToAnApiKeyWithTheKeyEmptySendsNoSavedPassword() = runTest {
        val accounts = accounts()
        val first = (accounts.signIn(SignInRequest(server.address, "winters", "secret-pw")) as SignInOutcome.Done).connection.server
        val asked = server.calls.size
        val tested = accounts.test(SignInRequest(server.address, "winters", "", mode = AuthMode.ApiKey), keptId = first.id) as TestOutcome.Failed
        assertEquals("Enter the API key.", tested.message)
        assertEquals(asked, server.calls.size)
    }

    @Test
    fun aTestOfAKeptServerWithThePasswordLeftEmptyUsesTheSavedOne() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        assertTrue(accounts.test(SignInRequest(server.address, "winters", "", certificate = CertificateDraft.Keep(first.id)), keptId = first.id) is TestOutcome.Reached)
    }

    // A switch to a server whose certificate can't be read opens its form on
    // the certificate; choosing the file again signs in with the saved
    // password.
    @Test
    fun aSwitchToAServerWhoseCertificateIsOutOfReachAsksForItAndChoosingItWorks() = runTest {
        val cert = file()
        val accounts = accounts()
        val first = signInWith(accounts, cert).connection.server
        accounts.signIn(SignInRequest(server.address, "brandon", "pw2")) as SignInOutcome.Done
        val away = File(folder.root, "away.p12")
        assertTrue(cert.renameTo(away))
        val asked = accounts.switchTo(first.id) as SwitchOutcome.NeedsCertificate
        assertEquals(first.id, asked.server.id)
        assertEquals(CertificateWords.notFound("rsa2048.p12"), asked.words)
        val again = accounts.signInAgain(first.id, SignInRequest(first.address, "winters", "", certificate = use(away)), sameAccount = true) as SignInOutcome.Done
        assertEquals(first.id, again.connection.server.id)
        assertEquals(CertFile(away.path, "away.p12"), again.connection.server.certificate)
        assertEquals(rsa, accounts.security.identityFingerprint)
    }

    @Test
    fun aGlanceAtAServerWhoseCertificateIsOutOfReachAsksNothing() = runTest {
        val cert = file()
        val accounts = accounts()
        val first = signInWith(accounts, cert).connection.server
        accounts.signIn(SignInRequest(server.address, "brandon", "pw2")) as SignInOutcome.Done
        assertTrue(cert.delete())
        val asked = server.calls.size
        assertEquals(Reach.Unknown, accounts.check(first.id).reach)
        assertEquals(asked, server.calls.size)
    }

    // A keep for a server removed while it ran writes nothing of the
    // certificate; one signed out of meanwhile writes as usual.
    @Test
    fun aKeepOfAServerRemovedMeanwhileWritesNoItem() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        val written = secrets.writes.count { it == "certificate@${first.id}" }
        val asked = CountDownLatch(1)
        server.answerBy("ping") { asked.countDown(); release.await(5, TimeUnit.SECONDS); server.ok(type = "octo") }
        val second = file("second-rsa.p12")
        val edit = async(Dispatchers.IO) { accounts.edit(first.id, SignInRequest(server.address, "winters", "", certificate = use(second))) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        val out = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { accounts.remove(first.id) }
        release.countDown()
        assertTrue(edit.await() is SignInOutcome.Failed)
        out.join()
        assertEquals("no item written for it", written, secrets.writes.count { it == "certificate@${first.id}" })
        assertNull(secrets.item(first.id))
    }

    @Test
    fun aKeepOfAServerSignedOutOfMeanwhileStillWritesItsItem() = runTest {
        val accounts = accounts()
        val first = signInWith(accounts, file()).connection.server
        val asked = CountDownLatch(1)
        server.answerBy("ping") { asked.countDown(); release.await(5, TimeUnit.SECONDS); server.ok(type = "octo") }
        val second = file("second-rsa.p12")
        val ticket = accounts.ticket()
        val edit = async(Dispatchers.IO) { accounts.edit(first.id, SignInRequest(server.address, "winters", "", certificate = use(second)), generation = ticket) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        val stop = accounts.stopTicket(first.id)
        val out = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { accounts.signOut(first.id, stop) }
        release.countDown()
        val saved = edit.await() as SignInOutcome.Saved
        out.join()
        assertTrue(saved.server.signedOut)
        assertEquals(CertSecret(second.path, PASSWORD), secrets.item(first.id))
    }

    // A failed read of the settings file is not the file being missing.
    @Test
    fun aStoreThatCannotBeReadIsNotTakenForNoCertificate() = runTest {
        val id = signInWith(accounts(), file()).connection.server.id
        secrets.failReads = { it.startsWith("certificate@") }
        val later = accounts()
        assertNull(later.restore())
        assertEquals(CertificateWords.storeUnread("Test store", "locked"), later.takeStartAsk()!!.words)
        secrets.failReads = { false }
        assertNotNull(secrets.item(id))
    }
}
