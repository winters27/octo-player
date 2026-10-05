package app.winters.octo.desktop.downloads

import app.winters.octo.desktop.FakeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The downloads drawer against a pretend Octo server: asked live whether it
// keeps logs, the list, a log, Find songs and a pick.
class DownloadsModelTest {
    private val server = FakeServer()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private suspend fun until(what: String, test: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!test()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private fun extensions(acquisitions: String) = server.answer(
        "getOpenSubsonicExtensions",
        """"openSubsonicExtensions":[{"name":"octoAcquisitions","versions":$acquisitions},{"name":"octoLibraryActions","versions":[1,2]}]""",
        type = "octo",
    )

    private fun downloads() {
        server.answer(
            "getAcquisitions",
            """"acquisitions":{"acquisition":[{"id":"ab","artist":"Air","title":"Sexy Boy","state":"downloading","progress":0.5,"source":"Soulseek","startedAt":"2026-10-04T18:00:00Z","updatedAt":"2026-10-04T18:00:05Z","key":"soulseek:ab","kind":"download","logLines":3}]}""",
            type = "octo",
        )
        server.answer("getUpgrades", """"upgrades":[{"id":"nd-1","title":"Holocene","artist":"Bon Iver","state":"queued","updatedAt":"2026-10-04T17:59:00Z"}]""", type = "octo")
    }

    @Test
    fun aServerThatKeepsLogsListsItsDownloadsAndUpgrades() = runBlocking {
        extensions("[1,2]")
        downloads()
        val model = DownloadsModel(server.client(), scope)
        model.start()
        until("the list was read") { model.rows.value.size == 2 }
        assertEquals(true, model.supported)
        assertEquals(listOf("soulseek:ab", "upgrade:nd-1"), model.rows.value.map { it.key })
        assertEquals("Downloading from Soulseek, 50%", model.rows.value[0].status)
        model.close()
    }

    @Test
    fun anOlderServerIsNotAskedForTheList() = runBlocking {
        extensions("[1]")
        val model = DownloadsModel(server.client(), scope)
        model.start()
        until("the server said") { model.supported != null }
        assertEquals(false, model.supported)
        delay(100)
        assertTrue("getAcquisitions" !in server.endpoints())
        model.close()
    }

    @Test
    fun aLogOpensFindSongsSearchesAndAPickFollowsItsDownload() = runBlocking {
        extensions("[1,2]")
        downloads()
        server.answer(
            "getAcquisition",
            """"acquisition":{"id":"ab","title":"Sexy Boy","state":"failed","key":"soulseek:ab","event":[{"at":"2026-10-04T18:00:00Z","kind":"queued","text":"Asked for"},{"at":"2026-10-04T18:00:09Z","kind":"failed","text":"Could not get it","detail":"No copy fits"}]}""",
            type = "octo",
        )
        val model = DownloadsModel(server.client(), scope)
        model.start()
        model.showLog("soulseek:ab")
        until("the log was read") { model.log.value?.event?.size == 2 }
        assertEquals(DrawerView.Log("soulseek:ab"), model.view)

        server.answer("findSongs", """"foundSongs":{"id":"f1","state":"searching","song":{"title":"Sexy Boy","artist":"Air"}}""", type = "octo")
        server.answer(
            "getFoundSongs",
            """"foundSongs":{"id":"f1","state":"done","song":{"title":"Sexy Boy","artist":"Air"},"candidate":[{"source":"Soulseek","peer":"p1","file":"Sexy Boy.flac","format":"flac","rank":1,"index":0,"id":"c-1"}]}""",
            type = "octo",
        )
        model.find("ab", "Sexy Boy", from = "soulseek:ab")
        until("the search ended") { model.found.value?.state == "done" }
        assertEquals("ab", server.calls.last { it.url.pathSegments.last() == "findSongs" }.url.queryParameter("id"))

        server.answer("pickFoundSong", """"pick":{"state":"queued","detail":"Getting FLAC from p1.","key":"soulseek:ab"}""", type = "octo")
        model.pick("f1", model.found.value!!.candidate.single())
        until("the pick opened its log") { model.view == DrawerView.Log("soulseek:ab") }
        val pick = server.calls.last { it.url.pathSegments.last() == "pickFoundSong" }
        assertEquals("f1", pick.url.queryParameter("search"))
        assertEquals("c-1", pick.url.queryParameter("copy"))
        model.close()
    }
}
