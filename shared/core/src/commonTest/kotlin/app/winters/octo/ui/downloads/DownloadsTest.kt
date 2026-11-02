package app.winters.octo.ui.downloads

import app.winters.octo.subsonic.Acquisition
import app.winters.octo.subsonic.AcquisitionEvent
import app.winters.octo.subsonic.FoundCandidate
import app.winters.octo.subsonic.FoundSong
import app.winters.octo.subsonic.FoundSongs
import app.winters.octo.subsonic.LIBRARY_ACTION_RESTORE
import app.winters.octo.subsonic.PickCompare
import app.winters.octo.subsonic.PickSide
import app.winters.octo.subsonic.PickResult
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.Upgrade
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    fun aWaitingUpgradeSaysTheServersOwnWordsForItsWait() {
        val mornye = "Waiting for Mornye's check, which it could not pass for example-ext; it allows its next try at 14:05 UTC."
        val rows = drawerRows(
            emptyList(),
            listOf(upgrade("nd-1", "waiting", detail = mornye), upgrade("nd-2", "waiting", detail = "  ")),
            now,
        )
        assertEquals(RowPhase.Waiting, rows.first { it.key == "upgrade:nd-1" }.phase)
        assertEquals(mornye.removeSuffix("."), rows.first { it.key == "upgrade:nd-1" }.status)
        // Blank words are no words: the old line stays.
        assertEquals("Waiting for Soulseek", rows.first { it.key == "upgrade:nd-2" }.status)
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
    fun aRowWaitingForThePersonIsNotOnTheWay_ButTheListIsStillAskedForOften() {
        val album = download("s:w", "searching", note = "2 tracks wait: play them once, or keep them anyway").copy(keepAnyway = true, heldTracks = 2)
        val off = download("s:x", "searching", note = "AcoustID is off, so Octo cannot check what played").copy(keepAnyway = true)
        val rows = drawerRows(listOf(download("s:a", "done"), download("s:b", "downloading", progress = 0.4f), album), emptyList(), now)
        assertEquals("1 on the way, 1 waits for you, 1 finished", drawerSummary(rows))
        assertEquals("1 on the way, 1 waits for you", drawerState(rows))
        assertEquals(DrawerMark.Ring, drawerMark(rows))
        val waiting = drawerRows(listOf(album, off), emptyList(), now)
        assertEquals("2 wait for you", drawerSummary(waiting))
        assertEquals("1 waits for you", drawerState(waiting.take(1)))
        assertEquals("Only rows waiting for the person: the dot", DrawerMark.Waits, drawerMark(waiting))
        assertEquals(DrawerMark.None, drawerMark(drawerRows(listOf(download("s:a", "done")), emptyList(), now)))
        assertNull(drawerState(drawerRows(listOf(download("s:a", "done")), emptyList(), now)))
        assertEquals(0, onTheWayCount(waiting))
        assertEquals("Still asked for at the open rows' pace", 2, runningCount(waiting))
        assertNull("The pill speaks for no waiting row", pillLead(waiting))
        assertEquals(0, pillCount(waiting, listOf(AskedSong("w", askedAt = now)), listedSince = now + 1, now = now + 2))
        assertEquals("s:b", pillLead(rows)?.key)
        assertEquals("Downloading 40%, Song s:b", pillSpoken(rows))
    }

    @Test
    fun anAlbumsRowIsAnAlbumInEveryState_AndItsLogLooksForNoSong() {
        val kept = rowOf(download("s:a", "done", kind = "album"))
        assertEquals(RowKind.Album, kept.kind)
        assertNull("No mark beside the title", kindLabel(kept.kind))
        assertNull("Kept and done, still an album", logFindId(kept))
        assertNull(logFindId(rowOf(download("s:a", "searching", kind = "album"))))
        assertEquals("a", logFindId(rowOf(download("s:a", "done"))))
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

        // Each Keep anyway sent, and what the server does with one: keeps
        // it (the list then shows it kept) or says no.
        val kept = mutableListOf<String>()
        var keepAnswer: suspend (String) -> Unit = { key ->
            list = list.map { if (it.key == key) it.copy(state = "done", keepAnyway = false) else it }
        }

        // When set, the next list asked for is the list as it is now,
        // answered only once this completes.
        var slowNext: CompletableDeferred<Unit>? = null

        override suspend fun acquisitions(): List<Acquisition> {
            calls++
            if (fail) throw SubsonicException.Server(0, "down")
            val now = list
            slowNext?.let { gate ->
                slowNext = null
                gate.await()
            }
            return now
        }

        override suspend fun upgrades(): List<Upgrade> = emptyList()

        override suspend fun clear(key: String?) {
            cleared += key
            list = list.filter { !(it.stage.name == "Done" && (key == null || it.key == key)) }
        }

        override suspend fun keepAnyway(key: String) {
            kept += key
            keepAnswer(key)
        }
    }

    private fun held(key: String, keepAnyway: Boolean = true) =
        download(key, "searching", note = "1 track waits: play it once, or keep it anyway").copy(keepAnyway = keepAnyway)

    @Test
    fun onlyARowTheServerMarksOffersKeepAnyway() {
        val offered = rowOf(held("s:a"))
        assertTrue(offered.keepAnyway)
        assertEquals(KEEP_ANYWAY, keepAnywayLabel(offered))
        assertEquals("1 track waits: play it once, or keep it anyway", offered.status)

        assertNull("Left out", keepAnywayLabel(rowOf(held("s:a", keepAnyway = false))))
        assertNull("A plain download", keepAnywayLabel(rowOf(download("s:a", "downloading"))))
        assertNull("No key to send back", keepAnywayLabel(rowOf(held("s:a").copy(key = null))))
        assertNull("A higher quality copy waiting its turn", keepAnywayLabel(rowOf(upgrade("nd-1", "queued"))))
    }

    @Test
    fun anAlbumHoldingTwoOrMoreSaysKeepTheseAnyway() {
        assertEquals("Keep anyway", keepAnywayWords(null))
        assertEquals("Keep anyway", keepAnywayWords(1))
        assertEquals("Keep these anyway", keepAnywayWords(2))
        assertEquals("Keep these anyway", keepAnywayWords(12))

        assertEquals(KEEP_ANYWAY, keepAnywayLabel(rowOf(held("s:a"))))
        assertEquals(KEEP_ANYWAY, keepAnywayLabel(rowOf(held("s:a").copy(heldTracks = 1))))
        assertEquals(KEEP_THESE_ANYWAY, keepAnywayLabel(rowOf(held("s:a").copy(heldTracks = 2))))
        assertNull("Tracks held, but nothing to keep anyway", keepAnywayLabel(rowOf(held("s:a", keepAnyway = false).copy(heldTracks = 2))))
    }

    @Test
    fun keepAnywayReadsAnOpenLogAgainBeforeTheButtonComesBack() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a")) }
        val watch = DownloadsWatch(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now })
        watch.poll()
        var also = 0
        watch.keepAnyway("s:a") {
            also++
            assertEquals("After the list came back", 2, source.calls)
            assertEquals("Still sending meanwhile", Keeping(sending = true), watch.keeping.value["s:a"])
        }
        assertEquals(1, also)
        assertNull(watch.keeping.value["s:a"])
    }

    @Test
    fun aRefusalForADownloadTheListDoesNotShowStaysForItsLog() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:b")) }
        source.keepAnswer = { throw SubsonicException.NotFound("Nothing is waiting to be kept") }
        val watch = DownloadsWatch(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now })
        watch.poll()
        watch.keepAnyway("s:x")
        assertEquals("Nothing is waiting to be kept", watch.keeping.value["s:x"]?.error)
        watch.poll()
        assertEquals("Nothing is waiting to be kept", watch.keeping.value["s:x"]?.error)
    }

    @Test
    fun aLogIsReadAgainAtOnce_OnlyWhileItIsTheOneFollowed() = runTest {
        var loads = 0
        var state = "searching"
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadLogWatch({ key -> loads++; download(key, state) }, scope, pollMs = 60_000, clock = { now })
        watch.follow("s:a")
        scope.runCurrent()
        assertEquals(1, loads)

        state = "done"
        watch.readAgain("s:a")
        assertEquals(2, loads)
        assertEquals("done", watch.row.value!!.state)

        watch.readAgain("s:b")
        assertEquals("Another download's log is not read", 2, loads)
        watch.close()
        watch.readAgain("s:a")
        assertEquals("A closed log is not read", 2, loads)
    }

    @Test
    fun keepAnywaySendsTheKey_ThenAsksForTheListAgain() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a"), download("s:b", "downloading")) }
        val watch = DownloadsWatch(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now })
        watch.poll()
        assertEquals(1, source.calls)

        watch.keepAnyway("s:a")
        assertEquals(listOf("s:a"), source.kept)
        assertEquals("Asked again once it answered", 2, source.calls)
        val row = watch.rows.value.first { it.key == "s:a" }
        assertEquals(RowPhase.Done, row.phase)
        assertNull("The refreshed row offers it no more", keepAnywayLabel(row))
        assertTrue("Nothing left on the row", watch.keeping.value.isEmpty())
    }

    @Test
    fun keepAnywayIsSendingUntilTheListCameBack_AndASecondTapSendsNothing() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a")) }
        val answer = CompletableDeferred<Unit>()
        source.keepAnswer = { answer.await() }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadsWatch(source, scope, clock = { now })
        watch.poll()

        scope.launch { watch.keepAnyway("s:a") }
        scope.runCurrent()
        assertEquals(Keeping(sending = true), watch.keeping.value["s:a"])
        watch.keepAnyway("s:a")
        assertEquals("A second tap while one is out", listOf("s:a"), source.kept)

        answer.complete(Unit)
        scope.runCurrent()
        assertEquals(2, source.calls)
        assertNull(watch.keeping.value["s:a"])
    }

    @Test
    fun aRefusalShowsTheServersWordsOnTheRow() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a"), held("s:b")) }
        source.keepAnswer = { throw SubsonicException.NotFound("Nothing is waiting to be kept") }
        val watch = DownloadsWatch(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now })
        watch.poll()

        watch.keepAnyway("s:a")
        assertEquals(Keeping(error = "Nothing is waiting to be kept"), watch.keeping.value["s:a"])
        assertNull("Only on its own row", watch.keeping.value["s:b"])
        assertEquals("The list is asked again all the same", 2, source.calls)

        // Still offered after the next look: the words stay.
        watch.poll()
        assertEquals("Nothing is waiting to be kept", watch.keeping.value["s:a"]?.error)

        // Tapped again and kept: the old words go with the button.
        source.keepAnswer = { key -> source.list = source.list.map { if (it.key == key) it.copy(keepAnyway = false) else it } }
        watch.keepAnyway("s:a")
        assertNull(watch.keeping.value["s:a"])
    }

    @Test
    fun aRefusalLeavesOnceTheRowNoLongerOffersKeepAnyway() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a")) }
        source.keepAnswer = { throw SubsonicException.Server(10, "Name the person whose copy to keep.") }
        val watch = DownloadsWatch(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now })
        watch.poll()
        watch.keepAnyway("s:a")
        assertEquals("Name the person whose copy to keep", watch.keeping.value["s:a"]?.error)

        source.list = listOf(download("s:a", "done"))
        watch.poll()
        assertTrue(watch.keeping.value.isEmpty())
    }

    @Test
    fun aServerOutOfReachSaysSoOnTheRow() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a")) }
        source.keepAnswer = { throw SubsonicException.Unreachable(java.io.IOException("Failed to connect to /10.0.0.2:4533")) }
        val watch = DownloadsWatch(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now })
        watch.poll()
        source.fail = true
        watch.keepAnyway("s:a")
        assertEquals(KEEP_ANYWAY_UNREACHABLE, watch.keeping.value["s:a"]?.error)
        assertTrue("The row stays as it was", keepAnywayLabel(watch.rows.value.single()) != null)

        assertEquals(KEEP_ANYWAY_UNREACHABLE, keepAnywayError(SubsonicException.NotSubsonic("HTTP 502 from keepAcquisitionAnyway", 502)))
        assertEquals("Not allowed", keepAnywayError(SubsonicException.Server(50, " Not allowed. ")))
    }

    @Test
    fun anOlderAnswerNeverLandsAfterANewerOne() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a")) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadsWatch(source, scope, clock = { now })
        // Asked first, with the list as it was, and answered only later.
        val slow = CompletableDeferred<Unit>()
        source.slowNext = slow
        scope.launch { watch.poll() }
        scope.runCurrent()
        assertEquals(1, source.calls)

        source.list = listOf(download("s:a", "done"))
        scope.launch { watch.poll() }
        scope.runCurrent()
        slow.complete(Unit)
        scope.runCurrent()
        assertEquals(RowPhase.Done, watch.rows.value.single().phase)
    }

    @Test
    fun aNewSignInForgetsEveryKeepAnyway() = runTest {
        val source = FakeDownloads().apply { list = listOf(held("s:a")) }
        source.keepAnswer = { throw SubsonicException.NotFound("Nothing is waiting to be kept") }
        val watch = DownloadsWatch(source, TestScope(StandardTestDispatcher(testScheduler)), clock = { now })
        watch.poll()
        watch.keepAnyway("s:a")
        assertTrue(watch.keeping.value.isNotEmpty())
        watch.reset()
        assertTrue(watch.keeping.value.isEmpty())
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
    fun aFinishedLogIsAskedForAMinuteMore_ThenNoLonger() = runTest {
        var loads = 0
        var state = "downloading"
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadLogWatch({ key -> loads++; download(key, state).copy(updatedAt = "2026-10-04T17:59:59Z") }, scope, clock = { now })
        watch.follow("s:a")
        scope.advanceTimeBy(LOG_POLL_MS * 3 + 1)
        assertEquals(4, loads)

        state = "done"
        scope.advanceTimeBy(LOG_FINISHED_GRACE_MS + LOG_POLL_MS * 3)
        val afterGrace = loads
        assertTrue("Still asked a while for the lyrics line", afterGrace > 4 + LOG_FINISHED_GRACE_MS / LOG_POLL_MS - 2)
        scope.advanceTimeBy(LOG_FINISHED_GRACE_MS * 10)
        assertEquals("Asked no more once it settled", afterGrace, loads)
        assertFalse(watch.following)
        assertEquals("done", watch.row.value!!.state)
    }

    @Test
    fun aLogThatFinishedLongAgoIsReadOnce() = runTest {
        var loads = 0
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadLogWatch({ key -> loads++; download(key, "failed").copy(updatedAt = "2026-10-04T15:00:00Z") }, scope, clock = { now })
        watch.follow("s:a")
        scope.advanceTimeBy(LOG_POLL_MS * 20)
        assertEquals(1, loads)
        assertFalse(watch.following)
    }

    @Test
    fun aLogWaitsWhileTheAppIsBehind() = runTest {
        var loads = 0
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadLogWatch({ key -> loads++; download(key, "downloading") }, scope, clock = { now })
        watch.follow("s:a")
        scope.runCurrent()
        assertEquals(1, loads)

        watch.foreground = false
        scope.advanceTimeBy(LOG_POLL_MS * 100)
        assertEquals("Not asked while behind", 1, loads)

        watch.foreground = true
        scope.runCurrent()
        assertEquals(2, loads)
        watch.close()
    }

    // A Find songs source whose search finishes on the second look, and
    // which notes each pick it is sent.
    private class FakeFind : FindSource {
        var asks = 0
        var searches = 0
        val picks = mutableListOf<Pair<String, FoundCandidate>>()

        // What each pick sent as `anyway`, null when it was left out.
        val anyways = mutableListOf<Boolean?>()
        var answer: suspend () -> PickResult = { PickResult("queued", "Getting it.", "soulseek:x") }

        // The library's copy of the song, when the search is for one.
        var libraryId: String? = null

        override suspend fun start(id: String): FoundSongs {
            searches++
            asks = 0
            return FoundSongs(id = "f$searches", state = "searching", candidate = listOf(copy))
        }

        override suspend fun get(search: String): FoundSongs {
            asks++
            return FoundSongs(
                id = search,
                state = if (asks < 2) "searching" else "done",
                song = FoundSong(title = "Holocene", libraryId = libraryId),
                candidate = listOf(copy, FoundCandidate(source = "Lidarr", index = 1, id = "c2")),
            )
        }

        override suspend fun pick(search: String, copy: FoundCandidate, anyway: Boolean?): PickResult {
            picks += search to copy
            anyways += anyway
            return answer()
        }

        companion object {
            val copy = FoundCandidate(source = "Soulseek", index = 0, id = "c1")
        }
    }

    @Test
    fun aFindSearchIsFollowedUntilItEnds_ThenPicked() = runTest {
        val source = FakeFind()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = FindSongsWatch(source, scope)
        watch.search("nd-1")
        scope.runCurrent()
        assertTrue(watch.found.value!!.searching)
        scope.advanceTimeBy(FIND_POLL_MS * 3)
        assertEquals("done", watch.found.value!!.state)
        assertEquals(2, source.asks)

        val picked = watch.pick("f1", FakeFind.copy)
        assertTrue(picked.queued)
        assertEquals("soulseek:x", watch.picked.value!!.key)
        assertEquals(listOf("f1" to FakeFind.copy), source.picks)
    }

    // A search that has finished, ready to pick from.
    private fun TestScope.doneWatch(source: FakeFind, asksFirst: Boolean): FindSongsWatch {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = FindSongsWatch(source, scope)
        watch.asksFirst = asksFirst
        watch.search("nd-1")
        scope.advanceTimeBy(FIND_POLL_MS * 3)
        assertEquals("done", watch.found.value!!.state)
        return watch
    }

    private val question = "This copy is MP3 320 and yours is FLAC, so it sounds worse. Replace yours anyway?"

    @Test
    fun anOlderServerIsNeverSentAnyway() = runTest {
        val source = FakeFind()
        source.answer = { PickResult("skipped", "Your copy is already FLAC, so nothing would be better.") }
        val watch = doneWatch(source, asksFirst = false)
        watch.pick("f1", FakeFind.copy)
        assertEquals(listOf<Boolean?>(null), source.anyways)
        assertNull(watch.question.value)
        assertEquals("Your copy is already FLAC, so nothing would be better.", watch.picked.value!!.detail)
        watch.close()
    }

    @Test
    fun aServerThatAsksFirstGetsAnywayFalse_AndItsQuestionWaits() = runTest {
        val source = FakeFind()
        source.answer = { PickResult("confirm", question) }
        val watch = doneWatch(source, asksFirst = true)
        val first = watch.pick("f1", FakeFind.copy)
        assertTrue(first.confirm)
        assertEquals(listOf<Boolean?>(false), source.anyways)
        assertEquals(PickQuestion("f1", FakeFind.copy, question, title = "Holocene"), watch.question.value)
        assertNull("A question is not a refusal", watch.picked.value)
        watch.close()
        assertNull(watch.question.value)
    }

    @Test
    fun replaceAnywaySendsTheSameCopyWithAnywayTrue() = runTest {
        val source = FakeFind()
        source.answer = { PickResult("confirm", question) }
        val watch = doneWatch(source, asksFirst = true)
        watch.pick("f1", FakeFind.copy)
        val asked = watch.question.value!!

        source.answer = { PickResult("queued", "Getting it.", "soulseek:x") }
        val result = watch.replaceAnyway(asked)!!
        assertTrue(result.queued)
        assertEquals(listOf("f1" to FakeFind.copy, "f1" to FakeFind.copy), source.picks)
        assertEquals(listOf<Boolean?>(false, true), source.anyways)
        assertNull(watch.question.value)
        assertEquals("soulseek:x", watch.picked.value!!.key)

        // Answered once: a second tap sends nothing.
        assertNull(watch.replaceAnyway(asked))
        assertEquals(2, source.picks.size)
        watch.close()
    }

    @Test
    fun keepMineSendsNothing() = runTest {
        val source = FakeFind()
        source.answer = { PickResult("confirm", "") }
        val watch = doneWatch(source, asksFirst = true)
        watch.pick("f1", FakeFind.copy)
        assertEquals("No words from the server: ours", REPLACE_ANYWAY_FALLBACK, watch.question.value!!.words)
        val asked = watch.question.value!!

        watch.keepMine()
        assertNull(watch.question.value)
        assertNull(watch.picked.value)
        assertEquals("f1", watch.found.value!!.id)
        assertNull("Kept: replacing is no longer on offer", watch.replaceAnyway(asked))
        assertEquals(1, source.picks.size)
        watch.close()
    }

    @Test
    fun aQuestionGoesWithItsSearch() = runTest {
        val source = FakeFind()
        source.answer = { PickResult("confirm", question) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = FindSongsWatch(source, scope)
        watch.asksFirst = true
        watch.search("nd-1")
        scope.advanceTimeBy(FIND_POLL_MS * 3)
        watch.pick("f1", FakeFind.copy)
        val asked = watch.question.value!!

        watch.search("nd-1")
        scope.runCurrent()
        assertNull(watch.question.value)
        assertNull(watch.replaceAnyway(asked))
        assertEquals(1, source.picks.size)
        watch.close()
    }

    @Test
    fun nothingIsPickedWhileTheSearchRuns() = runTest {
        val source = FakeFind()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = FindSongsWatch(source, scope)
        watch.search("nd-1")
        scope.runCurrent()
        assertTrue(watch.found.value!!.searching)
        assertFalse(canPick(watch.found.value, FakeFind.copy))

        val picked = watch.pick("f1", FakeFind.copy)
        assertFalse(picked.queued)
        assertEquals(FIND_PICK_WAIT, picked.detail)
        assertTrue("The server is not asked", source.picks.isEmpty())

        scope.advanceTimeBy(FIND_POLL_MS * 3)
        assertTrue(canPick(watch.found.value, FakeFind.copy))
        assertFalse("A copy the server did not name", canPick(watch.found.value, FoundCandidate(source = "Soulseek")))
        watch.close()
    }

    @Test
    fun aPickFromAReplacedListIsRefused_AndALateAnswerStaysWithItsSearch() = runTest {
        val source = FakeFind()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = FindSongsWatch(source, scope)
        watch.search("nd-1")
        scope.advanceTimeBy(FIND_POLL_MS * 3)
        assertEquals("f1", watch.found.value!!.id)

        // Search again: the old list's copies are not picked from the new one.
        watch.search("nd-1")
        scope.advanceTimeBy(FIND_POLL_MS * 3)
        assertEquals("f2", watch.found.value!!.id)
        assertEquals(FIND_LIST_CHANGED, watch.pick("f1", FakeFind.copy).detail)
        assertTrue(source.picks.isEmpty())

        // A pick answered after another search began says nothing on the new one.
        source.answer = {
            watch.search("nd-1")
            PickResult("skipped", "That copy is gone.")
        }
        watch.pick("f2", FakeFind.copy)
        assertEquals(listOf("f2" to FakeFind.copy), source.picks)
        assertNull(watch.picked.value)
        watch.close()
    }

    @Test
    fun aSearchIsShownUntilClosedOrReplaced() = runTest {
        val source = FakeFind()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = FindSongsWatch(source, scope)
        watch.search("nd-1")
        scope.runCurrent()
        assertTrue(watch.showing("f1"))
        watch.search("nd-1")
        scope.runCurrent()
        assertFalse(watch.showing("f1"))
        assertTrue(watch.showing("f2"))
        watch.close()
        assertFalse(watch.showing("f2"))
    }

    @Test
    fun aCopysKeyIsItsIdOrItsPlace() {
        assertEquals("id:c1", copyKey(FoundCandidate(index = 0, id = "c1")))
        assertEquals("at:0", copyKey(FoundCandidate(index = 0)))
    }

    @Test
    fun thePillSpeaksForTheSongFurthestAlong() {
        val rows = listOf(
            rowOf(download("s:a", "queued", startedAt = "2026-10-04T17:50:00Z")),
            rowOf(download("s:b", "downloading", startedAt = "2026-10-04T17:52:00Z", progress = 0.42f)),
            rowOf(download("s:c", "downloading", startedAt = "2026-10-04T17:51:00Z", progress = 0.9f)),
            rowOf(download("s:d", "done")),
        )
        // Two at the same step: the one that started first, not the one further in.
        assertEquals("s:c", pillLead(rows)?.key)
        assertEquals("Downloading 90%", stepWords(pillLead(rows)!!))
        assertEquals("Downloading 90%, Song s:c. 2 more on the way", pillSpoken(rows))
        assertNull(pillLead(listOf(rowOf(download("s:d", "done")))))
        assertEquals("Checking", stepWords(rowOf(download("s:a", "verifying"))))
        assertEquals("Adding", stepWords(rowOf(download("s:a", "importing"))))
        assertEquals("Searching", stepWords(rowOf(download("s:a", "searching"))))
        assertEquals("Downloading", stepWords(rowOf(download("s:a", "downloading"))))
    }

    @Test
    fun eachPhaseSitsOnItsStep_AndAFailureStopsWhereItsLogGot() {
        assertEquals(0, stepOf(rowOf(download("s:a", "queued"))))
        assertEquals(1, stepOf(rowOf(download("s:a", "downloading"))))
        assertEquals(2, stepOf(rowOf(download("s:a", "verifying"))))
        assertEquals(3, stepOf(rowOf(download("s:a", "importing"))))
        assertEquals(DownloadStep.entries.size, stepOf(rowOf(download("s:a", "done"))))
        val failed = rowOf(download("s:a", "failed"))
        val log = listOf(
            AcquisitionEvent(kind = "search", text = "Looking"),
            AcquisitionEvent(kind = "transfer", text = "Downloading"),
            AcquisitionEvent(kind = "check", text = "Checking the file"),
            AcquisitionEvent(kind = "failed", text = "Wrong song"),
            AcquisitionEvent(kind = "note", text = "Gave up"),
        )
        assertEquals(DownloadStep.Check.ordinal, stepOf(failed, log))
        assertEquals(DownloadStep.Find.ordinal, stepOf(failed))
    }

    @Test
    fun aRowThatAsksThePersonToPlayOrKeepWaitsForThem_AndOneWaitingOnAServiceDoesNot() {
        val album = rowOf(download("s:a", "searching", note = "2 tracks wait: play them once, or keep them anyway").copy(keepAnyway = true, heldTracks = 2))
        assertTrue("An album holding tracks", waitsForYou(album))
        assertTrue("An album seen by someone it does not offer Keep anyway", waitsForYou(album.copy(keepAnyway = false)))
        assertTrue("Its line alone", waitsForYou(rowOf(download("s:a", "searching", note = "1 track waits: play it once, or keep it anyway"))))
        val off = rowOf(download("s:a", "searching", note = "AcoustID is off, so Octo cannot check what played").copy(keepAnyway = true))
        assertTrue("A song held while AcoustID is off, with Keep anyway", waitsForYou(off))
        assertFalse("The same line without it waits for a key", waitsForYou(off.copy(keepAnyway = false)))
        assertTrue("A heart never played", waitsForYou(rowOf(download("s:a", "searching", note = "Play it once so Octo can keep the version you hear"))))
        assertTrue(
            "Another song stood in",
            waitsForYou(rowOf(download("s:a", "searching", note = "Octo played another song for this one. Play it once more so Octo can keep the version you hear"))),
        )
        listOf("Waiting for AcoustID", "Waiting for MusicBrainz", "Waiting for Lidarr's indexers", "Keeping the version you heard once YouTube answers").forEach { line ->
            assertFalse(line, waitsForYou(rowOf(download("s:a", "searching", note = line))))
        }
        assertFalse("A plain search", waitsForYou(rowOf(download("s:a", "searching"))))
        assertFalse("A download on its way", waitsForYou(rowOf(download("s:a", "downloading")).copy(keepAnyway = true)))
        assertFalse("Done", waitsForYou(rowOf(download("s:a", "done"))))
    }

    @Test
    fun anAlbumsLogOffersNoFindSongs_ASongsDoes() {
        val song = rowOf(download("s:a", "searching", note = "AcoustID is off, so Octo cannot check what played"))
        assertEquals("a", logFindId(song))
        assertNull(logFindId(rowOf(download("s:b", "searching", note = "2 tracks wait: play them once, or keep them anyway")).copy(heldTracks = 2)))
    }

    @Test
    fun aHeldDownloadWaitsOnTheStepItsLogReached_AndASearchBegunAgainIsBackOnFind() {
        val held = rowOf(download("s:a", "searching", note = "Waiting for AcoustID"))
        val log = listOf(
            AcquisitionEvent(kind = "search", text = "Looking on Soulseek"),
            AcquisitionEvent(kind = "transfer", text = "Downloading from Soulseek"),
            AcquisitionEvent(kind = "check", text = "Checking the file"),
            AcquisitionEvent(kind = "note", text = "Waiting for AcoustID"),
        )
        assertEquals(DownloadStep.Check.ordinal, stepOf(held, log))
        val album = log + AcquisitionEvent(kind = "library", text = "In the library folder") +
            AcquisitionEvent(kind = "note", text = "2 tracks wait: play them once, or keep them anyway")
        assertEquals("Some of an album in", DownloadStep.Add.ordinal, stepOf(held, album))
        assertEquals("Looking again", DownloadStep.Find.ordinal, stepOf(held, log + AcquisitionEvent(kind = "search", text = "Looking on YouTube")))
        assertEquals("No log yet", DownloadStep.Find.ordinal, stepOf(held))
        assertEquals("Waiting its turn", DownloadStep.Find.ordinal, stepOf(rowOf(download("s:a", "queued")), log))
    }

    @Test
    fun aCopysBadgeTakesItsKind_AndTheFactsKeepTheRest() {
        val flac = FoundCandidate(source = "Soulseek", format = "flac", quality = "FLAC 16-bit 44.1 kHz", size = 36_100_000, length = 329, freeSlot = true)
        assertEquals("FLAC", copyBadge(flac))
        assertEquals(listOf("16-bit 44.1 kHz", "34.4 MB", "5:29", "Soulseek", "Free to send now"), badgeFacts(flac, Locale.ROOT))
        val plain = FoundCandidate(source = "Soulseek", format = "flac", quality = "FLAC")
        assertEquals(listOf("Soulseek"), badgeFacts(plain, Locale.ROOT))
        assertNull(copyBadge(FoundCandidate(source = "Lidarr")))
        assertEquals(listOf("1st choice", "2nd choice", "3rd choice", "4th choice", "11th choice", "22nd choice"), listOf(1, 2, 3, 4, 11, 22).map(::rankWords))
    }

    // ---------------------------------------------------------------------
    // Replacing a library song's copy
    // ---------------------------------------------------------------------

    private val compare = PickCompare(PickSide("FLAC", 420, "live recording"), PickSide("FLAC", 298, "the plain song"))

    @Test
    fun aBetterCopyIsAskedTheUsualWay_ThenSentOnceWithAnywayTrue() = runTest {
        val source = FakeFind()
        source.libraryId = "nd-1"
        source.answer = { PickResult("ready", null, null, compare) }
        val watch = doneWatch(source, asksFirst = true)
        watch.pick("f1", FakeFind.copy)
        val asked = watch.question.value!!
        assertEquals(AskKind.Ready, asked.kind)
        assertEquals(replaceQuestion(FakeFind.copy), asked.words)
        assertEquals("nd-1", asked.songId)
        assertEquals("Holocene", asked.title)
        assertEquals(listOf<Boolean?>(false), source.anyways)

        source.answer = { PickResult("queued", "Getting it.", null) }
        assertTrue(watch.replaceAnyway(asked)!!.queued)
        assertNull(watch.replaceAnyway(asked))
        assertEquals(listOf<Boolean?>(false, true), source.anyways)
        watch.close()
    }

    @Test
    fun aCopyThatNeedsAWordCarriesTheServersSentenceAndBothSides() = runTest {
        val source = FakeFind()
        source.libraryId = "nd-1"
        source.answer = { PickResult("confirm", "Yours looks like a live recording and this copy is the plain song. Replace yours anyway?", null, compare) }
        val watch = doneWatch(source, asksFirst = true)
        watch.pick("f1", FakeFind.copy)
        val asked = watch.question.value!!
        assertEquals(AskKind.Confirm, asked.kind)
        assertEquals("Yours looks like a live recording and this copy is the plain song. Replace yours anyway?", asked.words)
        assertEquals(listOf("Yours: FLAC, 7:00, live recording", "This copy: FLAC, 4:58, the plain song"), compareLines(asked.compare))
        watch.close()
    }

    @Test
    fun anOutsideSongIsPickedAtOnceEvenWhenTheServerAsksFirst() = runTest {
        val source = FakeFind()
        val watch = doneWatch(source, asksFirst = true)
        assertTrue(watch.pick("f1", FakeFind.copy).queued)
        assertNull(watch.question.value)
        assertEquals(listOf<Boolean?>(false), source.anyways)
        watch.close()
    }

    @Test
    fun aComparisonLeavesOutWhatIsMissing() {
        assertEquals(listOf("Yours: MP3 320", "This copy: 4:58, the plain song"), compareLines(PickCompare(PickSide("MP3 320", null, null), PickSide(null, 298, "the plain song"))))
        assertEquals(emptyList<String>(), compareLines(PickCompare(PickSide(), null)))
        assertEquals(emptyList<String>(), compareLines(null))
    }

    @Test
    fun aCopysTagAndTheTakeLine() {
        val same = FoundCandidate(source = "Soulseek", vsYours = "same", vsYoursText = "Same as yours")
        assertEquals("Same as yours", vsYoursTag(same))
        assertEquals("Compared with your copy: Same as yours", vsYoursSpoken(same))
        assertNull(vsYoursTag(FoundCandidate(source = "Soulseek")))
        assertEquals("Yours looks like a live recording. Pick the plain song to replace it.", takeLine(" Yours looks like a live recording. Pick the plain song to replace it. "))
        assertNull(takeLine(" "))
    }

    private fun replacing(plain: Boolean = true) = Replacing("nd-1", "Holocene", "f1", "id:c1", plainSong = plain)

    private fun job(state: String, at: String = "2026-10-11T12:05:00Z", detail: String? = null, id: String = "nd-1") =
        Upgrade(id = id, title = "Holocene", state = state, detail = detail, updatedAt = at)

    @Test
    fun aReplacementIsFollowedUntilItIsReplaced() {
        val all = Replacements()
        all.start(replacing(), emptyList())
        assertTrue(all.replacing("nd-1"))
        assertEquals("id:c1", all.of("f1", "id:c1")?.copyKey)

        // Not listed yet, then under way: still replacing.
        assertTrue(all.update(emptyList()).isEmpty())
        assertTrue(all.update(listOf(job("working"))).isEmpty())
        assertTrue(all.now.value["nd-1"]!!.seen)

        val ends = all.update(listOf(job("upgraded", detail = "Now FLAC.")))
        val end = ends.single() as ReplaceEnd.Replaced
        assertEquals("Replaced with the plain song", end.words)
        assertFalse(all.replacing("nd-1"))
        // Undo puts the old copy back by the song's id.
        assertEquals(LIBRARY_ACTION_RESTORE, end.putBack.action)
        assertEquals("nd-1", end.putBack.id)
        assertEquals(end.putBack, end.putBack.undo)
    }

    @Test
    fun aReplacementThatFailsSaysYourCopyWasKept_AndWhereToLook() {
        val all = Replacements()
        all.start(replacing(plain = false), emptyList())
        val end = all.update(listOf(job("failed", detail = "The copy you picked was not the right recording, so it was thrown away. Your copy was kept."))).single()
        assertTrue(end is ReplaceEnd.Kept)
        assertEquals(
            "The copy you picked was not the right recording, so it was thrown away. Your copy was kept. Details are in Downloads.",
            end.words,
        )
        assertEquals("Replaced with the copy you picked", replacedLine(plainSong = false))
        assertEquals("The copy you picked did not replace yours. Your copy was kept. Details are in Downloads.", keptLine(null))
    }

    @Test
    fun anOlderJobsEndIsNotThisReplacements() {
        val all = Replacements()
        val older = job("upgraded", at = "2026-10-10T12:00:00Z")
        all.start(replacing(), listOf(older))
        // Still the older job's end, and this one never seen under way.
        assertTrue(all.update(listOf(older)).isEmpty())
        assertTrue(all.replacing("nd-1"))
        // Another song's job says nothing about this one.
        assertTrue(all.update(listOf(older, job("failed", id = "nd-2"))).isEmpty())
        // A later end is this one's.
        assertTrue(all.update(listOf(job("failed", at = "2026-10-11T12:05:00Z", detail = "No copy came. Your copy was kept."))).single() is ReplaceEnd.Kept)
    }

    @Test
    fun theReplacingRowSaysTheStepThePillWould() {
        val r = replacing()
        val row = rowOf(Upgrade(id = "nd-1", title = "Holocene", state = "working", progress = 0.4))
        assertEquals(row, replacingRow(r, listOf(row)))
        assertEquals("Replacing... Searching", replacingWords(row))
        assertEquals("Replacing...", replacingWords(null))
        val downloading = rowOf(download("s:a", "downloading", progress = 0.42f), Upgrade(id = "nd-1", state = "working"))
        assertEquals("Replacing... Downloading 42%", replacingWords(replacingRow(r, listOf(downloading))))
    }

    // ---------------------------------------------------------------------------
    // The pill's count

    private fun asked(id: String, agoMs: Long) = AskedSong(id, now - agoMs)

    private fun rows(vararg list: Acquisition) = drawerRows(list.toList(), emptyList(), now)

    @Test
    fun aSongAskedForLongAgoThatNoListShows_IsNotCounted() {
        // The phone still holds an ask from an hour ago; the server's list,
        // asked for seconds ago, has nothing: the drawer is empty, so is the pill.
        assertEquals(0, pillCount(emptyList(), listOf(asked("a", 60 * 60_000L)), now - 5_000, now))
    }

    @Test
    fun aSongJustAskedFor_CountsUntilTheFirstListAfterIt() {
        val ask = asked("a", 1_000)
        assertEquals("No list asked for since the ask", 1, pillCount(emptyList(), listOf(ask), now - 10_000, now))
        assertEquals("No list at all yet", 1, pillCount(emptyList(), listOf(ask), null, now))
        assertEquals("A list asked for after it, without it: refused or never taken", 0, pillCount(emptyList(), listOf(ask), now - 500, now))
    }

    @Test
    fun aListedSongCountsOnce_WhicheverListIsNewer() {
        val running = rows(download("s:a", "downloading", progress = 0.4f))
        val ask = asked("a", 1_000)
        assertEquals(1, pillCount(running, listOf(ask), now - 500, now))
        assertEquals("Listed before the newest list came back", 1, pillCount(running, listOf(ask), now - 10_000, now))
    }

    @Test
    fun aSongListedUnderItsLibraryIdCountsOnce() {
        val importing = rows(download("s:a", "importing").copy(libraryId = "nd-9"))
        assertEquals("nd-9", importing.single().findId)
        assertEquals(1, pillCount(importing, listOf(asked("a", 1_000)), now - 10_000, now))
    }

    @Test
    fun aDoneOrFailedSongIsNotCounted() {
        val ask = asked("a", 60_000)
        assertEquals(0, pillCount(rows(download("s:a", "done")), listOf(ask), now - 500, now))
        assertEquals(0, pillCount(rows(download("s:a", "failed", error = "no copy")), listOf(ask), now - 500, now))
        assertEquals("Ended, and its row since cleared", 0, pillCount(emptyList(), listOf(ask), now - 500, now))
    }

    @Test
    fun withNoListComingBack_AnAskCountsTwoMinutesAtMost() {
        assertEquals(1, pillCount(emptyList(), listOf(asked("a", ASKED_UNLISTED_MS - 1)), null, now))
        assertEquals(0, pillCount(emptyList(), listOf(asked("a", ASKED_UNLISTED_MS)), null, now))
        assertEquals("After a restart nothing has come back yet, and an old ask is not counted", 0, pillCount(emptyList(), listOf(asked("a", 3 * 60 * 60_000L)), null, now))
        assertEquals("An ask from a clock set later is not counted", 0, pillCount(emptyList(), listOf(asked("a", -60_000)), null, now))
    }

    @Test
    fun runningRowsCountWithoutAnyAsk() {
        val list = rows(download("s:a", "downloading"), download("s:b", "queued"), download("s:c", "done"))
        assertEquals(2, pillCount(list, emptyList(), now - 500, now))
    }

    @Test
    fun thePillLooksAgainWhenTheFirstCountedAskRunsOut() {
        val asks = listOf(asked("a", 30_000), asked("b", 90_000))
        assertEquals(30_000L, pillRecheckMs(emptyList(), asks, null, now))
        assertNull("Nothing counted, nothing to look at", pillRecheckMs(emptyList(), asks, now - 500, now))
        assertNull(pillRecheckMs(rows(download("s:a", "downloading")), listOf(asked("a", 30_000)), null, now))
    }

    @Test
    fun theWatchSaysWhenItsNewestListWasAskedFor() = runTest {
        var clock = now
        val source = FakeDownloads().apply { list = listOf(download("s:a", "downloading")) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watch = DownloadsWatch(source, scope, clock = { clock })
        assertNull(watch.listedSince.value)
        watch.poll()
        assertEquals(now, watch.listedSince.value)

        clock = now + 5_000
        source.fail = true
        watch.poll()
        assertEquals("A list that did not come back changes nothing", now, watch.listedSince.value)

        watch.reset()
        assertNull(watch.listedSince.value)
    }

    @Test
    fun aSongAskedWithItsAlbumCountsUntilTheAlbumIsListed_ThirtyMinutesAtMost() {
        val ask = AskedSong("a", now - 10 * 60_000L, album = true)
        assertTrue("Lists since have not shown the album yet", stillAsked(ask, now - 1_000, now))
        assertEquals(1, pillCount(emptyList(), listOf(ask), now - 1_000, now))
        assertFalse(stillAsked(ask.copy(askedAt = now - ASKED_ALBUM_UNLISTED_MS), now - 1_000, now))
        assertEquals(20 * 60_000L, stillAskedForMs(ask, now - 1_000, now))
        assertNull(stillAskedForMs(AskedSong("a", now - 1_000), now - 500, now))
    }
}
