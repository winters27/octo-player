package app.winters.octo.desktop.server

import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.HeldDisk
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsHold
import app.winters.octo.desktop.settings.SettingsOpen
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.name
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class CertificateOrderTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer(CertFixtures.serverContext().socketFactory)
    private val secrets = CertStore()
    private val settingsFile get() = File(folder.root, "settings.json")
    private val json = Json { ignoreUnknownKeys = true }

    // The disk the row's settings are written through; a row refuses the
    // settings file, or the pending file beside it, here.
    private val disk = HeldDisk()

    // Each write and delete, with the settings file as it was on disk then.
    private val events = CopyOnWriteArrayList<Pair<String, String?>>()

    private val a by lazy { CertFixtures.copy("rsa2048.p12", folder.root) }
    private val b by lazy { CertFixtures.copy("second-rsa.p12", folder.root) }
    private val token = listOf(HeaderDraft("X-Token", "t"))
    private val savedToken = listOf(HeaderDraft("X-Token", "", saved = true))

    @Before
    fun answer() {
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
    }

    @After
    fun stop() = server.close()

    // A row over a store whose timed write and retry both wait a minute, so
    // only flushNow and flush write the files. Whatever the body does, every
    // refusal is lifted and what waits is written after it, so the store's
    // later write and its exit write find nothing to do.
    private fun row(body: suspend (SettingsStore) -> Unit) {
        val settings = (SettingsStore.openOn(disk, settingsFile, writeDelayMs = 60_000, writeRetryMs = 60_000) as SettingsOpen.Opened).store
        try {
            runTest { body(settings) }
        } finally {
            disk.refuseReplace.clear()
            settings.flush()
        }
        assertEquals(SettingsHold.Free, settings.hold.value)
    }

    // Both settings files refuse writes: the file and the pending file
    // beside it.
    private fun refuseBoth() {
        disk.refuseReplace += setOf("settings.json", "settings.pending.json")
    }

    // k: winters, with a header and certificate A, its settings on disk.
    private suspend fun kept(settings: SettingsStore): Pair<Accounts, SavedServer> {
        val accounts = tlsAccounts(settings, secrets)
        val done = accounts.signIn(SignInRequest(server.address, "winters", "pw", headers = token, certificate = CertificateDraft.Use(a.path, PASSWORD))) as SignInOutcome.Done
        settings.flush()
        address = done.connection.server.address
        events.clear()
        secrets.onChange = { event -> events += event to runCatching { settingsFile.readText() }.getOrNull() }
        return accounts to done.connection.server
    }

    // The changes that found the settings on disk already naming the end
    // state: every server's id and certificate, and the one in use.
    private fun onDisk(settings: SettingsStore): Set<String> {
        val want = settings.current.servers.map { it.id to it.certificate } to settings.current.activeServer
        fun names(text: String?): Boolean {
            val onFile = text?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: return false
            return (onFile.servers.map { it.id to it.certificate } to onFile.activeServer) == want
        }
        return events.filter { names(it.second) }.map { it.first }.toSet()
    }

    private fun all() = events.map { it.first }.toSet()

    // The saved address, as the store names its items by it.
    private var address = ""

    private fun password(username: String) = secretAccount(username, address)

    private val addressHeaders get() = "headers@$address"

    // Red when the certificate write or the address headers' delete runs
    // before the flush, or the password and header writes after it.
    @Test
    fun aNewFileIsWrittenOnceTheSettingsNameIt() = row { settings ->
        val (accounts, k) = kept(settings)
        accounts.edit(k.id, SignInRequest(server.address, "winters", "", headers = savedToken, certificate = CertificateDraft.Use(b.path, PASSWORD))) as SignInOutcome.Done
        assertEquals(setOf("write certificate@${k.id}", "delete $addressHeaders"), onDisk(settings))
        assertEquals(setOf("write headers@${k.id}", "write ${password("winters")}", "write certificate@${k.id}", "delete $addressHeaders"), all())
    }

    @Test
    fun anIdMoveWritesTheNewItemAndDeletesTheOldOnesOnceTheSettingsNameTheNewId() = row { settings ->
        val (accounts, k) = kept(settings)
        val moved = (accounts.edit(k.id, SignInRequest(server.address, "brandon", "pw2", headers = token, certificate = CertificateDraft.Keep(k.id))) as SignInOutcome.Done).connection.server
        val ordered = setOf(
            "delete ${password("winters")}",
            "delete headers@${k.id}",
            "write certificate@${moved.id}",
            "delete certificate@${k.id}",
            "delete $addressHeaders",
        )
        assertEquals(ordered, onDisk(settings))
        assertEquals(ordered + setOf("write headers@${moved.id}", "write ${password("brandon")}"), all())
    }

    @Test
    fun removeDeletesTheItemOnceTheSettingsNameNone() = row { settings ->
        val (accounts, k) = kept(settings)
        accounts.edit(k.id, SignInRequest(server.address, "winters", "", headers = savedToken, certificate = CertificateDraft.Remove(k.id))) as SignInOutcome.Done
        assertEquals(setOf("delete certificate@${k.id}", "delete $addressHeaders"), onDisk(settings))
    }

    @Test
    fun removingTheServerDeletesEverythingOnceTheListWithoutItIsOnDisk() = row { settings ->
        val (accounts, k) = kept(settings)
        accounts.remove(k.id)
        val ordered = setOf("delete ${password("winters")}", "delete headers@${k.id}", "delete certificate@${k.id}", "delete $addressHeaders")
        assertEquals(ordered, onDisk(settings))
        assertEquals(ordered, all())
    }

    @Test
    fun aNewFileWithAnotherUsernameFollowsTheSameOrder() = row { settings ->
        val (accounts, k) = kept(settings)
        val moved = (accounts.edit(k.id, SignInRequest(server.address, "brandon", "pw2", headers = token, certificate = CertificateDraft.Use(b.path, PASSWORD))) as SignInOutcome.Done).connection.server
        assertEquals(
            setOf("delete ${password("winters")}", "delete headers@${k.id}", "write certificate@${moved.id}", "delete certificate@${k.id}", "delete $addressHeaders"),
            onDisk(settings),
        )
    }

    // Both settings files refused until Octo closes: Use still succeeds and
    // says so, and the next start finds the old file named and asks for
    // its password.
    @Test
    fun aWriteThatNeverLandsAsksForTheOldFileAtTheNextStart() = row { settings ->
        val (accounts, k) = kept(settings)
        refuseBoth()
        val done = accounts.edit(k.id, SignInRequest(server.address, "winters", "", headers = savedToken, certificate = CertificateDraft.Use(b.path, PASSWORD))) as SignInOutcome.Done
        assertTrue(done.note!!.contains(CertificateWords.UNSAVED_KEEP))
        assertEquals(SettingsHold.Unkept, settings.hold.value)
        assertEquals(CertSecret(b.path, PASSWORD), secrets.item(k.id))
        // Closed, with both files still refused.
        settings.flush()
        val later = tlsAccounts(startOn(settingsFile), secrets)
        assertEquals(CertFile(a.path, "rsa2048.p12"), later.find(k.id)!!.certificate)
        val load = later.loadChoice(CertificateChoice.Saved(k.id, later.find(k.id)!!.certificate!!)) as CertificateLoad.Unreadable
        assertEquals(CertificateWords.needsPassword("rsa2048.p12"), load.words)
    }

    // Only the settings file refused until Octo closes: the pending file
    // beside it holds the change, so no note is shown, and the next start
    // reads it and loads the new file. Red when flushNow is false while the
    // pending file holds the settings.
    @Test
    fun aChangeKeptBesideAHeldFileLoadsTheNewFileAtTheNextStart() = row { settings ->
        val (accounts, k) = kept(settings)
        disk.refuseReplace += "settings.json"
        val done = accounts.edit(k.id, SignInRequest(server.address, "winters", "", headers = savedToken, certificate = CertificateDraft.Use(b.path, PASSWORD))) as SignInOutcome.Done
        assertNull(done.note)
        assertEquals(SettingsHold.Kept, settings.hold.value)
        // Closed, with the settings file still refused.
        settings.flush()
        val later = tlsAccounts(startOn(settingsFile), secrets)
        assertEquals(CertFile(b.path, "second-rsa.p12"), later.find(k.id)!!.certificate)
        val load = later.loadChoice(CertificateChoice.Saved(k.id, later.find(k.id)!!.certificate!!)) as CertificateLoad.Found
        assertEquals(CertFixtures.leaf("second-rsa.p12"), load.certificate.fingerprint)
    }

    @Test
    fun aWriteThatLandsBeforeOctoClosesLoadsTheNewFile() = row { settings ->
        val (accounts, k) = kept(settings)
        refuseBoth()
        accounts.edit(k.id, SignInRequest(server.address, "winters", "", headers = savedToken, certificate = CertificateDraft.Use(b.path, PASSWORD))) as SignInOutcome.Done
        disk.refuseReplace.clear()
        settings.flush()
        val later = tlsAccounts(startOn(settingsFile), secrets)
        val load = later.loadChoice(CertificateChoice.Saved(k.id, later.find(k.id)!!.certificate!!)) as CertificateLoad.Found
        assertEquals(CertFixtures.leaf("second-rsa.p12"), load.certificate.fingerprint)
    }

    @Test
    fun anIdMoveWhoseWriteLandsLaterLeavesNoItemUnderTheOldId() = row { settings ->
        val (accounts, k) = kept(settings)
        refuseBoth()
        val moved = (accounts.edit(k.id, SignInRequest(server.address, "brandon", "pw2", headers = token, certificate = CertificateDraft.Keep(k.id))) as SignInOutcome.Done).connection.server
        disk.refuseReplace.clear()
        settings.flush()
        assertNull(secrets.item(k.id))
        val later = tlsAccounts(startOn(settingsFile), secrets)
        assertNull(later.find(k.id))
        assertNotNull(later.restore())
        assertEquals(moved.id, later.active!!.id)
    }

    @Test
    fun aRemoveWhileWritesFailSaysToChooseRemoveAgain() = row { settings ->
        val (accounts, k) = kept(settings)
        refuseBoth()
        val done = accounts.edit(k.id, SignInRequest(server.address, "winters", "", headers = savedToken, certificate = CertificateDraft.Remove(k.id))) as SignInOutcome.Done
        assertTrue(done.note!!.contains(CertificateWords.unsavedRemove(done.connection.server.name)))
    }

    @Test
    fun anAddWhileWritesFailSaysTheServerMayNotBeThereNextTime() = row { settings ->
        val (accounts, _) = kept(settings)
        refuseBoth()
        val saved = accounts.add(SignInRequest(server.address, "brandon", "pw2", certificate = CertificateDraft.Use(b.path, PASSWORD))) as SignInOutcome.Saved
        assertTrue(saved.note!!.contains(CertificateWords.unsavedAdd(saved.server.name)))
    }
}
