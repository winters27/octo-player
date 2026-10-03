package app.winters.octo.desktop.upgrade

import app.winters.octo.desktop.FakeServer
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// "Find higher quality" against a pretend Octo server: the live check for the
// action, asking for songs, following them only while any is still on,
// reading the library again and the words said.
class UpgradeModelTest {
    private val server = FakeServer()
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val reloads = AtomicInteger()
    private val notices = CopyOnWriteArrayList<String>()

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun model(recheckMs: Long = 60_000, reloadGapMs: Long = 30_000) =
        UpgradeModel(server.client(), scope, reload = { reloads.incrementAndGet() }, notify = { notices += it }, pollMs = 30, recheckMs = recheckMs, reloadGapMs = reloadGapMs)

    private fun versions(vararg versions: Int) =
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoLibraryActions","versions":[${versions.joinToString(",")}]}]""", type = "octo")

    private fun gate(actions: String = """["remove","upgrade"]""", dryRun: Boolean = false, source: String = "\"Soulseek\"") =
        server.answer(
            "getLibraryActions",
            """"libraryActions":{"enabled":true,"allowed":true,"dryRun":$dryRun,"actions":$actions,"keepDays":30,"parallel":3,"upgradeSource":$source}""",
            type = "octo",
        )

    private fun upgrades(vararg rows: String) = server.answer("getUpgrades", """"upgrades":[${rows.joinToString(",")}]""", type = "octo")

    private fun row(id: String, state: String, title: String = "Song $id") =
        """{"id":"$id","title":"$title","artist":"Bon Iver","album":"Bon Iver","state":"$state","detail":null,"progress":null,"updatedAt":"2026-10-03T12:00:00Z"}"""

    private fun queueEverything() = server.answerBy("libraryAction") { request ->
        val id = request.url.queryParameter("id")
        server.ok(""""libraryAction":{"id":"$id","action":"upgrade","state":"queued","detail":null}""", type = "octo")
    }

    private fun song(id: String, title: String = "Song $id") = Song(id, title, suffix = "mp3")

    private fun calls(endpoint: String) = server.endpoints().count { it == endpoint }

    private suspend fun until(what: String, test: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!test()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private suspend fun ready(): UpgradeModel {
        versions(1, 2)
        gate()
        upgrades()
        return model().also {
            it.start()
            until("the action is known") { it.canUpgrade }
        }
    }

    @Test
    fun theActionIsAskedForLiveNotFromTheExtensionsSavedAtSignIn() = runBlocking {
        val model = ready()
        assertTrue(model.canUpgrade)
        assertEquals(3, model.actions?.parallel)
        assertEquals(1, calls("getLibraryActions"))
    }

    @Test
    fun theSourceIsTheOneTheServerNames() = runBlocking {
        versions(1, 2)
        gate(source = "\"Lidarr\"")
        upgrades()
        val model = model()
        model.start()
        until("the action is known") { model.canUpgrade }
        assertEquals("Lidarr", model.source)
    }

    @Test
    fun noSourceNamedMeansNone() = runBlocking {
        versions(1, 2)
        gate(source = "null")
        upgrades()
        val model = model()
        model.start()
        until("the action is known") { model.canUpgrade }
        assertNull(model.source)
    }

    @Test
    fun anOlderServerNeverGetsAskedForItsActions() = runBlocking {
        versions(1)
        gate(actions = """["remove"]""")
        val model = model()
        model.start()
        until("the extensions were read") { calls("getOpenSubsonicExtensions") > 0 }
        delay(100)
        assertFalse(model.canUpgrade)
        assertEquals(0, calls("getLibraryActions"))
    }

    @Test
    fun aServerUpdatedWhileOpenGainsTheActionAtTheNextCheck() = runBlocking {
        versions(1)
        gate()
        upgrades()
        val model = model(recheckMs = 50)
        model.start()
        until("the first check") { calls("getOpenSubsonicExtensions") > 0 }
        assertFalse(model.canUpgrade)
        versions(1, 2)
        until("the action is known") { model.canUpgrade }
    }

    @Test
    fun aRehearsingServerCannotUpgrade() = runBlocking {
        versions(1, 2)
        gate(dryRun = true)
        val model = model()
        model.start()
        until("the actions were read") { model.actions != null }
        assertFalse(model.canUpgrade)
        // Asking does nothing then.
        model.request(listOf(song("a")))
        delay(100)
        assertEquals(0, calls("libraryAction"))
    }

    @Test
    fun askingSendsEachSongAndFollowsItOnlyWhileItIsOn() = runBlocking {
        val model = ready()
        queueEverything()
        upgrades(row("a", "working"))
        model.request(listOf(song("a", "Holocene")))
        assertTrue("a" in model.pending)
        until("the song was asked for") { calls("libraryAction") == 1 }
        val asked = server.calls.first { it.url.pathSegments.last() == "libraryAction" }
        assertEquals("a", asked.url.queryParameter("id"))
        assertEquals("upgrade", asked.url.queryParameter("action"))
        until("the server's list was read") { model.pending["a"]?.state == "working" }
        assertEquals("Looking for higher quality for 1 song", notices.first())

        upgrades(row("a", "upgraded", "Holocene"))
        until("it was done") { model.pending.isEmpty() }
        until("the line was said") { "Found higher quality for 1 song" in notices }
        assertEquals(1, reloads.get())
        // Nothing still on, so the list is no longer asked for.
        val looks = calls("getUpgrades")
        delay(200)
        assertEquals(looks, calls("getUpgrades"))
    }

    @Test
    fun noFlacFoundIsSaidWithTheTitleAndReadsNothing() = runBlocking {
        val model = ready()
        queueEverything()
        upgrades(row("a", "notFound", "Towers"))
        model.request(listOf(song("a", "Towers")))
        until("the line was said") { "No higher quality copy found for: Towers" in notices }
        assertEquals(0, reloads.get())
    }

    @Test
    fun waitingForSoulseekIsSaid() = runBlocking {
        val model = ready()
        queueEverything()
        upgrades(row("a", "waiting"))
        model.request(listOf(song("a")))
        until("the line was said") { "Waiting for Soulseek" in notices }
        assertTrue("a" in model.pending)
    }

    @Test
    fun theLibraryIsReadAgainOnceWhileFlacsLandAndOnceAtTheEnd() = runBlocking {
        versions(1, 2)
        gate()
        upgrades()
        val model = model(reloadGapMs = 60_000)
        model.start()
        until("the action is known") { model.canUpgrade }
        queueEverything()
        upgrades(row("a", "working"), row("b", "working"), row("c", "working"))
        model.request(listOf(song("a"), song("b"), song("c")))
        until("all three are followed") { model.pending.values.all { it.state == "working" } && calls("getUpgrades") > 1 }
        upgrades(row("a", "upgraded"), row("b", "working"), row("c", "working"))
        until("the first landed") { reloads.get() == 1 }
        upgrades(row("a", "upgraded"), row("b", "upgraded"), row("c", "working"))
        until("the second landed") { "b" !in model.pending }
        delay(150)
        // Held back: the gap has not passed.
        assertEquals(1, reloads.get())
        upgrades(row("a", "upgraded"), row("b", "upgraded"), row("c", "upgraded"))
        until("the last one ended") { model.pending.isEmpty() }
        until("read once more") { reloads.get() == 2 }
        delay(150)
        assertEquals(2, reloads.get())
    }

    @Test
    fun atMostFiftySongsGoInOneGo() = runBlocking {
        val model = ready()
        queueEverything()
        upgrades()
        model.request((1..60).map { song("s$it") })
        until("the line was said") { notices.isNotEmpty() }
        assertEquals(50, calls("libraryAction"))
        assertEquals("Looking for higher quality for 50 songs. At most 50 songs at a time, so 10 songs were left out", notices.first())
    }

    @Test
    fun aSongTheServerWillNotDoIsNotFollowed() = runBlocking {
        val model = ready()
        server.answer("libraryAction", """"libraryAction":{"id":"a","action":"upgrade","state":"skipped","detail":"It is lossless already."}""", type = "octo")
        model.request(listOf(song("a", "Holocene")))
        until("the line was said") { notices.isNotEmpty() }
        assertEquals("Could not look for higher quality for Holocene: It is lossless already", notices.first())
        assertTrue(model.pending.isEmpty())
    }

    @Test
    fun songsStillOnFromBeforeAreFollowedFromTheStart() = runBlocking {
        versions(1, 2)
        gate()
        upgrades(row("a", "working"), row("b", "upgraded"))
        val model = model()
        model.start()
        until("the song from before is followed") { "a" in model.pending }
        assertNull(model.pending["b"])
        upgrades(row("a", "upgraded"), row("b", "upgraded"))
        until("it was done") { model.pending.isEmpty() }
        until("the line was said") { "Found higher quality for 1 song" in notices }
    }

    @Test
    fun closingStopsAskingTheServer() = runBlocking {
        val model = ready()
        queueEverything()
        upgrades(row("a", "working"))
        model.request(listOf(song("a")))
        until("it is followed") { model.pending["a"]?.state == "working" }
        model.close()
        val looks = calls("getUpgrades")
        delay(200)
        assertEquals(looks, calls("getUpgrades"))
        assertFalse(model.canUpgrade)
        assertTrue(model.pending.isEmpty())
    }
}
