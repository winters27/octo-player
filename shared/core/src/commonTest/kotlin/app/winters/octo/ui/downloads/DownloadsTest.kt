package app.winters.octo.ui.downloads

import app.winters.octo.subsonic.Acquisition
import app.winters.octo.subsonic.FoundCandidate
import app.winters.octo.subsonic.FoundSongs
import app.winters.octo.subsonic.PickResult
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.Upgrade
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsTest {
    private val now = Instant.parse("2026-10-04T18:00:00Z").toEpochMilli()

    private fun download(
        key: String,
        state: String,
        startedAt: String = "2026-10-04T17:59:00Z",
        updatedAt: String = startedAt,
        kind: String? = "download",
        progress: Float? = null,
        note: String? = null,
        error: String? = null,
        ahead: Int? = null,
    ) = Acquisition(
        id = key.substringAfter(':'), artist = "Air", title = "Song $key", state = state, progress = progress, source = "Soulseek",
        startedAt = startedAt, updatedAt = updatedAt, error = error, note = note, ahead = ahead, key = key, kind = kind,
    )

    private fun upgrade(id: String, state: String, acquisition: String? = null, detail: String? = null, updatedAt: String = "2026-10-04T17:58:00Z") =
        Upgrade(id = id, title = "Up $id", artist = "Air", state = state, detail = detail, updatedAt = updatedAt, acquisition = acquisition)

    @Test
    fun eachStageReadsAsOneLine() {
        assertEquals("Waiting, 3 ahead", statusOf(download("s:a", "queued", ahead = 3)))
        assertEquals("Waiting its turn", statusOf(download("s:a", "queued")))
        assertEquals("Soulseek couldn't get it, trying YouTube", statusOf(download("s:a", "searching", note = "Soulseek couldn't get it, trying YouTube.")))
        assertEquals("Looking on Soulseek", statusOf(download("s:a", "searching")))
        assertEquals("Downloading from Soulseek, 42%", statusOf(download("s:a", "downloading", progress = 0.42f)))
        assertEquals("Checking the file", statusOf(download("s:a", "verifying")))
        assertEquals("Adding to your library", statusOf(download("s:a", "importing")))
        assertEquals("In your library", statusOf(download("s:a", "done")))
        assertEquals("No source had this song", statusOf(download("s:a", "failed", error = "No source had this song.")))
    }

    @Test
    fun runningRowsComeFirst_ThenTheNewestFinished() {
        val rows = drawerRows(
            listOf(
                download("s:old", "done", startedAt = "2026-10-04T17:00:00Z", updatedAt = "2026-10-04T17:05:00Z"),
                download("s:run", "downloading", progress = 0.5f),
                download("s:new", "failed", startedAt = "2026-10-04T17:30:00Z", updatedAt = "2026-10-04T17:40:00Z"),
            ),
            emptyList(),
            now,
        )
        assertEquals(listOf("s:run", "s:new", "s:old"), rows.map { it.key })
        assertEquals(0.5f, rows[0].fraction!!, 0f)
        assertEquals(1, runningCount(rows))
        assertEquals(0.5f, overallFraction(rows)!!, 0f)
    }

    @Test
    fun anUpgradeJoinsItsDownload_AndItsVerdictIsTheLastWord() {
        val rows = drawerRows(
            listOf(download("s:x", "done", kind = "upgrade")),
            listOf(upgrade("nd-1", "upgraded", acquisition = "s:x", detail = "Now FLAC 16-bit 44.1 kHz, 31.0 MB, was MP3 220 kbps.")),
            now,
        )
        val row = rows.single()
        assertEquals(RowKind.Upgrade, row.kind)
        assertEquals(RowPhase.Done, row.phase)
        assertEquals("Now FLAC 16-bit 44.1 kHz, 31.0 MB, was MP3 220 kbps", row.status)
        assertEquals("nd-1", row.findId)
        assertEquals("s:x", row.logKey)
    }

    @Test
    fun aDownloadedUpgradeIsStillCheckedBeforeItCountsAsDone() {
        val row = drawerRows(listOf(download("s:x", "done", kind = "upgrade")), listOf(upgrade("nd-1", "working", acquisition = "s:x")), now).single()
        assertEquals(RowPhase.Checking, row.phase)
        assertFalse(row.finished)
    }

    @Test
    fun anUpgradeWithoutADownloadIsARowOfItsOwn_AndAnOldFinishedOneLeaves() {
        val rows = drawerRows(
            emptyList(),
            listOf(
                upgrade("nd-1", "waiting"),
                upgrade("nd-2", "notFound", updatedAt = "2026-10-04T17:00:00Z"),
                upgrade("nd-3", "upgraded", updatedAt = "2026-10-03T10:00:00.1234567Z"),
            ),
            now,
        )
        assertEquals(listOf("upgrade:nd-1", "upgrade:nd-2"), rows.map { it.key })
        assertEquals("Waiting for Soulseek", rows[0].status)
        assertEquals("No higher quality copy found", rows[1].status)
        assertNull(rows[0].logKey)
    }

    @Test
    fun aClearedRowStaysHidden() {
        val rows = drawerRows(listOf(download("s:a", "done")), listOf(upgrade("nd-2", "notFound")), now, hidden = setOf("upgrade:nd-2", "s:a"))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun aCopyReadsBestFactFirst() {
        val copy = FoundCandidate(
            source = "Soulseek", peer = "peer1", file = "03 - Da Funk.flac", title = "Da Funk", format = "flac",
            quality = "FLAC 16-bit 44.1 kHz", size = 31_000_000, length = 330, freeSlot = true, speed = 1_500_000, rank = 1,
        )
        assertEquals(listOf("FLAC 16-bit 44.1 kHz", "29.6 MB", "5:30", "Soulseek", "peer1", "Free to send now", "1.4 MB/s"), candidateFacts(copy, Locale.US))
        assertEquals("Da Funk", candidateTitle(copy))
        assertEquals("Octo's first choice", candidateVerdict(copy))
        assertEquals("Octo's choice 3", candidateVerdict(copy.copy(rank = 3)))
        assertEquals("From a live album", candidateVerdict(copy.copy(rank = null, note = "From a live album.")))
        assertTrue(isLosslessCopy(copy))
        assertTrue(isLosslessCopy(copy.copy(format = "FLAC 24bit")))
        assertFalse(isLosslessCopy(copy.copy(format = "MP3-320")))
        assertEquals(listOf("MP3", "3 in queue"), candidateFacts(FoundCandidate(source = "", format = "mp3", queueLength = 3), Locale.US))
    }

    @Test
    fun theSummaryCountsWhatRunsAndWhatFinished() {
        val rows = drawerRows(listOf(download("s:a", "done"), download("s:b", "searching"), download("s:c", "failed")), emptyList(), now)
        assertEquals("1 on the way, 2 finished", drawerSummary(rows))
        assertEquals("", drawerSummary(emptyList()))
        assertEquals("Your copy: MP3 220 kbps, 6.7 MB", ownedCopyText("MP3 220 kbps", 7_000_000, Locale.US))
        assertNull(ownedCopyText(null, null))
    }

    @Test
    fun logLinesShowTheClockTime() {
        assertEquals("18:04:31", logTime("2026-10-04T18:04:31Z", ZoneOffset.UTC))
        assertEquals("", logTime(null))
        assertEquals("", logTime("not a time"))
    }

    // ---------------------------------------------------------------------------

    private class FakeDownloads : DownloadsSource {
        var list = listOf<Acquisition>()
        var calls = 0
        val cleared = mutableListOf<String?>()
        var fail = false

        override suspend fun acquisitions(): List<Acquisition> {
            calls++
            if (fail) throw SubsonicException.Server(0, "down")
            return list
        }

        override suspend fun upgrades(): List<Upgrade> = emptyList()

        override suspend fun clear(key: String?) {
            cleared += key
            list = list.filter { !(it.stage.name == "Done" && (key == null || it.key == key)) }
        }
    }

    @Test
    fun theDrawerIsAskedOftenWhileOpenOrBusy_AndSeldomOtherwise() = runTest {
        val source = FakeDownloads().apply { list = listOf(download("s:a", "done")) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadsWatch(source, scope, clock = { now })
        watch.start()
        scope.runCurrent()
        assertEquals(1, source.calls)
        assertTrue(watch.loaded.value)

        scope.advanceTimeBy(DOWNLOADS_OPEN_POLL_MS + 1)
        assertEquals("Idle and closed: not asked again yet", 1, source.calls)

        watch.open = true
        scope.runCurrent()
        assertEquals(2, source.calls)
        scope.advanceTimeBy(DOWNLOADS_OPEN_POLL_MS + 1)
        assertEquals(3, source.calls)

        // A moment without the server keeps the rows.
        source.fail = true
        scope.advanceTimeBy(DOWNLOADS_OPEN_POLL_MS + 1)
        assertEquals(1, watch.rows.value.size)
        watch.stop()
    }

    @Test
    fun clearingHidesAtOnceAndAsksTheServer() = runTest {
        val source = FakeDownloads().apply { list = listOf(download("s:a", "done"), download("s:b", "downloading")) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadsWatch(source, scope, clock = { now })
        watch.poll()
        watch.clear()
        assertEquals(listOf("s:b"), watch.rows.value.map { it.key })
        assertEquals(listOf<String?>(null), source.cleared)

        watch.poll()
        watch.clear("s:b")
        assertEquals("A running row is never cleared", listOf("s:b"), watch.rows.value.map { it.key })
    }

    @Test
    fun aFindSearchIsFollowedUntilItEnds_ThenPicked() = runTest {
        var asks = 0
        val source = object : FindSource {
            override suspend fun start(id: String) = FoundSongs(id = "f1", state = "searching")
            override suspend fun get(search: String): FoundSongs {
                asks++
                return FoundSongs(id = search, state = if (asks < 2) "searching" else "done", candidate = listOf(FoundCandidate(source = "Soulseek", index = 0)))
            }
            override suspend fun pick(search: String, index: Int) = PickResult("queued", "Getting it.", "soulseek:x")
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = FindSongsWatch(source, scope)
        watch.search("nd-1")
        scope.runCurrent()
        assertTrue(watch.found.value!!.searching)
        scope.advanceTimeBy(FIND_POLL_MS * 3)
        assertEquals("done", watch.found.value!!.state)
        assertEquals(2, asks)

        val picked = watch.pick(0)
        assertTrue(picked.queued)
        assertEquals("soulseek:x", watch.picked.value!!.key)
    }
}
