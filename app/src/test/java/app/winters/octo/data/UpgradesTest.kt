package app.winters.octo.data

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.Upgrade
import app.winters.octo.ui.upgrade.UPGRADE_POLL_MS
import app.winters.octo.ui.upgrade.UpgradeAsk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpgradesTest {
    private class FakeHost : UpgradeHost {
        val asked = mutableListOf<String>()
        var answer: (String) -> LibraryActionResult = { LibraryActionResult(it, "upgrade", "queued") }
        var list: List<Upgrade> = emptyList()
        var looks = 0
        var reloads = 0
        val said = mutableListOf<String>()

        override suspend fun ask(serverId: String): LibraryActionResult {
            asked += serverId
            return answer(serverId)
        }

        override suspend fun upgrades(): List<Upgrade> {
            looks++
            return list
        }

        override suspend fun reload() {
            reloads++
        }

        override fun say(text: String) {
            said += text
        }
    }

    private fun TestScope.watchOf(host: FakeHost) = UpgradeWatch(host, backgroundScope, clock = { testScheduler.currentTime }, reloadGapMs = 30_000)

    private fun row(id: String, state: String, title: String = "Song $id") = Upgrade(id = id, title = title, state = state, updatedAt = "2026-10-03T12:00:00Z")

    private fun copy(mime: String?, bitDepth: Int? = null) = SourceTrackEntity(
        id = "s1", sourceId = "server", nativeId = "n1", title = "Holocene", searchKey = "holocene", sortKey = "holocene",
        artist = "Bon Iver", artistId = "ar", album = "Bon Iver", albumId = "al", trackNo = 1, discNo = 1, year = 2011,
        durationMs = 336_000, addedAt = 0, mimeType = mime, sizeBytes = null, artwork = null, uri = null,
        albumOrder = 0, relinkKey = "", genre = "", bitDepth = bitDepth,
    )

    @Test
    fun aLossyCopyCanBeUpgradedAndALosslessOneCannot() {
        assertTrue(isUpgradableCopy(copy("audio/mpeg")))
        assertTrue(isUpgradableCopy(copy("audio/ogg")))
        assertFalse(isUpgradableCopy(copy("audio/flac")))
        // An m4a is Apple Lossless with a bit depth, AAC without.
        assertFalse(isUpgradableCopy(copy("audio/mp4", bitDepth = 16)))
        assertTrue(isUpgradableCopy(copy("audio/mp4")))
        // A copy of a kind nobody said is left alone.
        assertFalse(isUpgradableCopy(copy(null)))
        assertFalse(isUpgradableCopy(copy("application/octet-stream")))
    }

    @Test
    fun askingSendsEachSongThenFollowsItOnlyWhileItIsOn() = runTest {
        val host = FakeHost()
        host.list = listOf(row("a", "working"))
        val watch = watchOf(host)
        watch.request(listOf(UpgradeAsk("a", "Holocene")))
        assertTrue("a" in watch.pending.value)
        runCurrent()
        assertEquals(listOf("a"), host.asked)
        assertEquals("Looking for FLAC for 1 song", host.said.first())
        assertEquals("working", watch.pending.value.getValue("a").state)

        host.list = listOf(row("a", "upgraded", "Holocene"))
        advanceTimeBy(UPGRADE_POLL_MS + 1)
        assertTrue(watch.pending.value.isEmpty())
        assertEquals("Found FLAC for 1 song", host.said.last())
        runCurrent()
        assertEquals(1, host.reloads)
        // Nothing still on: no more questions.
        val looks = host.looks
        advanceTimeBy(UPGRADE_POLL_MS * 5)
        assertEquals(looks, host.looks)
    }

    @Test
    fun behindTheAppItAsksSeldomAndAtOnceOnComingBack() = runTest {
        val host = FakeHost()
        host.list = listOf(row("a", "working"))
        val watch = watchOf(host)
        watch.foreground = false
        watch.request(listOf(UpgradeAsk("a", "Holocene")))
        runCurrent()
        val first = host.looks
        advanceTimeBy(UPGRADE_POLL_MS * 3)
        assertEquals(first, host.looks)
        advanceTimeBy(UPGRADE_POLL_AWAY_MS)
        assertEquals(first + 1, host.looks)
        watch.foreground = true
        runCurrent()
        assertEquals(first + 2, host.looks)
    }

    @Test
    fun noFlacFoundIsSaidWithTheTitle() = runTest {
        val host = FakeHost()
        host.list = listOf(row("a", "notFound", "Towers"))
        val watch = watchOf(host)
        watch.request(listOf(UpgradeAsk("a", "Towers")))
        runCurrent()
        assertEquals("No FLAC found for: Towers", host.said.last())
        assertEquals(0, host.reloads)
    }

    @Test
    fun theLibraryIsCopiedAgainAtMostOnceAGapAndOnceAtTheEnd() = runTest {
        val host = FakeHost()
        host.list = listOf(row("a", "working"), row("b", "working"), row("c", "working"))
        val watch = watchOf(host)
        watch.request(listOf("a", "b", "c").map { UpgradeAsk(it, "Song $it") })
        runCurrent()
        host.list = listOf(row("a", "upgraded"), row("b", "working"), row("c", "working"))
        advanceTimeBy(UPGRADE_POLL_MS + 1)
        runCurrent()
        assertEquals(1, host.reloads)
        host.list = listOf(row("a", "upgraded"), row("b", "upgraded"), row("c", "working"))
        advanceTimeBy(UPGRADE_POLL_MS + 1)
        runCurrent()
        assertEquals(1, host.reloads)
        host.list = listOf(row("a", "upgraded"), row("b", "upgraded"), row("c", "upgraded"))
        advanceTimeBy(UPGRADE_POLL_MS + 1)
        runCurrent()
        assertEquals(2, host.reloads)
    }

    @Test
    fun aSongTheServerCouldNotBeAskedAboutSaysWhy() = runTest {
        val host = FakeHost()
        host.answer = { throw SubsonicException.Unreachable(IOException("down")) }
        val watch = watchOf(host)
        watch.request(listOf(UpgradeAsk("a", "Holocene")))
        runCurrent()
        assertTrue(host.said.single().startsWith("Could not look for a FLAC of Holocene: "))
        assertTrue(watch.pending.value.isEmpty())
        assertEquals(0, host.looks)
    }

    @Test
    fun songsStillOnFromBeforeAreTakenOn() = runTest {
        val host = FakeHost()
        host.list = listOf(row("a", "waiting"), row("b", "upgraded"))
        val watch = watchOf(host)
        watch.adopt()
        assertEquals(setOf("a"), watch.pending.value.keys)
        // Nothing said about them until they are done.
        assertTrue(host.said.isEmpty())
    }

    @Test
    fun forgettingStopsTheQuestions() = runTest {
        val host = FakeHost()
        host.list = listOf(row("a", "working"))
        val watch = watchOf(host)
        watch.request(listOf(UpgradeAsk("a", "Holocene")))
        runCurrent()
        watch.forget()
        val looks = host.looks
        advanceTimeBy(UPGRADE_POLL_MS * 5)
        assertEquals(looks, host.looks)
        assertTrue(watch.pending.value.isEmpty())
    }
}
