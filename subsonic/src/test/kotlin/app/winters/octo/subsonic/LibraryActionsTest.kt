package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Octo's library actions: what the user may do, and taking a song out of
// the library, never by a rating.
class LibraryActionsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,$inner}}"""

    @Test
    fun readsWhatTheUserMayDo() = runTest {
        answer(ok(""""libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove"],"keepDays":30}"""))
        val actions = client().libraryActions()

        val request = server.takeRequest()
        assertEquals("/rest/getLibraryActions", request.url.encodedPath)
        assertEquals("winters", request.url.queryParameter("u"))
        assertEquals(LibraryActions(enabled = true, allowed = true, dryRun = false, actions = listOf("remove"), keepDays = 30), actions)
        assertTrue(actions.canRemove)
    }

    @Test
    fun removingNeedsTheSwitchTheAllowlistARealRunAndTheAction() {
        val all = LibraryActions(enabled = true, allowed = true, dryRun = false, actions = listOf("remove"), keepDays = 30)
        assertTrue(all.canRemove)
        assertFalse(all.copy(enabled = false).canRemove)
        assertFalse(all.copy(allowed = false).canRemove)
        assertFalse(all.copy(dryRun = true).canRemove)
        assertFalse(all.copy(actions = emptyList()).canRemove)
        assertFalse(LibraryActions().canRemove)
    }

    @Test
    fun aMissingAnswerMeansNothingIsAllowed() = runTest {
        answer(ok(""""x":1"""))
        assertFalse(client().libraryActions().canRemove)
    }

    @Test
    fun removeAsksForTheSongByIdAndReadsTheOutcome() = runTest {
        answer(ok(""""libraryAction":{"id":"abc","action":"remove","state":"applied","detail":"Removed. It will not be downloaded again."}"""))
        val result = client().libraryAction("abc")

        val request = server.takeRequest()
        assertEquals("/rest/libraryAction", request.url.encodedPath)
        assertEquals("abc", request.url.queryParameter("id"))
        assertEquals("remove", request.url.queryParameter("action"))
        // Nothing about ratings goes with it.
        assertEquals(null, request.url.queryParameter("rating"))
        assertEquals(LibraryActionState.Applied, result.outcome)
        assertEquals("Removed. It will not be downloaded again.", result.detail)
    }

    @Test
    fun readsEveryStateAndToleratesNewOnes() {
        assertEquals(LibraryActionState.Rehearsed, LibraryActionState.of("rehearsed"))
        assertEquals(LibraryActionState.Skipped, LibraryActionState.of(" Skipped "))
        assertEquals(LibraryActionState.Unresolved, LibraryActionState.of("unresolved"))
        assertEquals(LibraryActionState.Failed, LibraryActionState.of("failed"))
        assertEquals(LibraryActionState.Unknown, LibraryActionState.of("pending"))
        assertEquals(LibraryActionState.Unknown, LibraryActionState.of(null))
    }

    @Test
    fun readsTheUpgradeActionAndHowManyRunAtOnce() = runTest {
        answer(ok(""""libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove","upgrade"],"keepDays":30,"parallel":3}"""))
        val actions = client().libraryActions()

        assertEquals(listOf("remove", "upgrade"), actions.actions)
        assertEquals(3, actions.parallel)
        assertTrue(actions.canUpgrade)
    }

    @Test
    fun readsWhereTheServerLooksForABetterCopy() = runTest {
        answer(ok(""""libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove","upgrade"],"keepDays":30,"parallel":3,"upgradeSource":"Lidarr"}"""))
        assertEquals("Lidarr", client().libraryActions().upgradeSource)
    }

    @Test
    fun noUpgradeSourceMeansNoneIsNamed() = runTest {
        answer(ok(""""libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove"],"keepDays":30,"upgradeSource":null}"""))
        assertEquals(null, client().libraryActions().upgradeSource)
        answer(ok(""""libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove"],"keepDays":30}"""))
        assertEquals(null, client().libraryActions().upgradeSource)
    }

    @Test
    fun anOlderServerRunsOneAtATimeAndCannotUpgrade() = runTest {
        answer(ok(""""libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove"],"keepDays":30}"""))
        val actions = client().libraryActions()

        assertEquals(1, actions.parallel)
        assertTrue(actions.canRemove)
        assertFalse(actions.canUpgrade)
    }

    @Test
    fun upgradingNeedsTheSwitchTheAllowlistARealRunAndTheAction() {
        val all = LibraryActions(enabled = true, allowed = true, dryRun = false, actions = listOf("upgrade"))
        assertTrue(all.canUpgrade)
        assertFalse(all.canRemove)
        assertFalse(all.copy(enabled = false).canUpgrade)
        assertFalse(all.copy(allowed = false).canUpgrade)
        // A rehearsal would only fill the queue with songs never swapped.
        assertFalse(all.copy(dryRun = true).canUpgrade)
        assertFalse(all.copy(actions = listOf("remove")).canUpgrade)
        assertFalse(LibraryActions().canUpgrade)
    }

    @Test
    fun anUpgradeIsAskedForByIdAndAnsweredAtOnce() = runTest {
        answer(ok(""""libraryAction":{"id":"abc","action":"upgrade","state":"queued","detail":"Queued."}"""))
        val result = client().libraryAction("abc", LIBRARY_ACTION_UPGRADE)

        val request = server.takeRequest()
        assertEquals("/rest/libraryAction", request.url.encodedPath)
        assertEquals("abc", request.url.queryParameter("id"))
        assertEquals("upgrade", request.url.queryParameter("action"))
        assertEquals(LibraryActionState.Queued, result.outcome)
    }

    @Test
    fun anUpgradeTheServerWillNotDoSaysWhy() = runTest {
        answer(ok(""""libraryAction":{"id":"abc","action":"upgrade","state":"skipped","detail":"It is lossless already."}"""))
        val result = client().libraryAction("abc", LIBRARY_ACTION_UPGRADE)

        assertEquals(LibraryActionState.Skipped, result.outcome)
        assertEquals("It is lossless already.", result.detail)
    }

    @Test
    fun readsTheUpgradesAskedFor() = runTest {
        answer(
            ok(
                """"upgrades":[""" +
                    """{"id":"a","title":"Holocene","artist":"Bon Iver","album":"Bon Iver","state":"working","detail":null,"progress":0.25,"updatedAt":"2026-10-03T12:00:00Z"},""" +
                    """{"id":"b","title":"Towers","artist":"Bon Iver","album":"Bon Iver","state":"notFound","detail":"No Soulseek result was lossless.","progress":null,"updatedAt":"2026-10-03T12:01:00Z"}""" +
                    """]""",
            ),
        )
        val upgrades = client().upgrades()

        val request = server.takeRequest()
        assertEquals("/rest/getUpgrades", request.url.encodedPath)
        assertEquals("winters", request.url.queryParameter("u"))
        assertEquals(2, upgrades.size)
        val (working, missing) = upgrades
        assertEquals(UpgradeStage.Working, working.stage)
        assertEquals(0.25f, working.fraction)
        assertTrue(working.stage.pending)
        assertEquals(UpgradeStage.NotFound, missing.stage)
        assertEquals(null, missing.fraction)
        assertFalse(missing.stage.pending)
        assertEquals("No Soulseek result was lossless.", missing.detail)
    }

    @Test
    fun noUpgradesListedMeansNone() = runTest {
        answer(ok(""""x":1"""))
        assertEquals(emptyList<Upgrade>(), client().upgrades())
    }

    @Test
    fun readsEveryUpgradeStateAndToleratesNewOnes() {
        assertEquals(UpgradeStage.Queued, UpgradeStage.of("queued"))
        assertEquals(UpgradeStage.Waiting, UpgradeStage.of("waiting"))
        assertEquals(UpgradeStage.Working, UpgradeStage.of(" Working "))
        assertEquals(UpgradeStage.Upgraded, UpgradeStage.of("upgraded"))
        assertEquals(UpgradeStage.NotFound, UpgradeStage.of("notFound"))
        assertEquals(UpgradeStage.NotFound, UpgradeStage.of("notfound"))
        assertEquals(UpgradeStage.Rehearsed, UpgradeStage.of("rehearsed"))
        assertEquals(UpgradeStage.Skipped, UpgradeStage.of("skipped"))
        assertEquals(UpgradeStage.Failed, UpgradeStage.of("failed"))
        assertEquals(UpgradeStage.Unknown, UpgradeStage.of("paused"))
        assertEquals(UpgradeStage.Unknown, UpgradeStage.of(null))
        // Only the three the server is still on are worth asking about again.
        assertEquals(
            setOf(UpgradeStage.Queued, UpgradeStage.Waiting, UpgradeStage.Working),
            UpgradeStage.entries.filter { it.pending }.toSet(),
        )
    }

    @Test
    fun aProgressOutsideZeroToOneIsKeptInside() {
        assertEquals(1f, Upgrade(progress = 1.5).fraction)
        assertEquals(0f, Upgrade(progress = -0.5).fraction)
        assertEquals(null, Upgrade(progress = Double.NaN).fraction)
    }

    @Test
    fun aServerErrorIsThrown() = runTest {
        answer("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":10,"message":"Required parameter is missing: id"}}}""")
        try {
            client().libraryAction("")
            fail("expected an error")
        } catch (e: SubsonicException) {
            // expected
        }
    }
}
