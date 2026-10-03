package app.winters.octo.ui.upgrade

import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.Upgrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlacUpgradesTest {
    private var now = 1_000_000L
    private val follower = UpgradeFollower(clock = { now })

    private fun queued(id: String) = LibraryActionResult(id, "upgrade", "queued")

    private fun up(id: String, state: String, title: String = "Song $id", detail: String? = null, updatedAt: String = "2026-10-03T12:00:00Z") =
        Upgrade(id = id, title = title, artist = "Bon Iver", state = state, detail = detail, updatedAt = updatedAt)

    // Asks for the songs and has the server queue each one.
    private fun follow(vararg ids: String) {
        follower.ask(ids.map { UpgradeAsk(it, "Song $it") }).forEach { follower.answered(it.id, queued(it.id)) }
        follower.askedNotice()
    }

    @Test
    fun theMenuWordsCountSongs() {
        assertEquals("Find FLAC for 1 song", findFlacLabel(1))
        assertEquals("Find FLAC for 9 songs", findFlacLabel(9))
        assertEquals("Look for a FLAC of 9 songs on Soulseek? Each original is kept until its FLAC passes.", findFlacQuestion(9))
        assertEquals("Find in FLAC", FIND_IN_FLAC)
    }

    @Test
    fun theWordsHaveNoDashes() {
        val words = listOf(findFlacLabel(2), findFlacQuestion(2), FIND_IN_FLAC, WAITING_FOR_SOULSEEK)
        words.forEach { assertFalse(it, it.contains('-') || it.contains('—') || it.contains('–')) }
    }

    @Test
    fun askingTakesAtMostABatchAndNeverASongTwice() {
        val songs = (1..60).map { UpgradeAsk("s$it", "Song $it") }
        val taken = follower.ask(songs + songs.first())
        assertEquals(UPGRADE_BATCH, taken.size)
        assertEquals("s1", taken.first().id)
        // Being looked for already: not sent again.
        assertTrue(follower.ask(songs.take(5)).isEmpty())
        taken.forEach { follower.answered(it.id, queued(it.id)) }
        assertEquals("Looking for FLAC for 50 songs. At most 50 songs at a time, so 10 songs were left out", follower.askedNotice())
        assertNull(follower.askedNotice())
    }

    @Test
    fun aSongNotAnsweredYetShowsAsQueued() {
        follower.ask(listOf(UpgradeAsk("a", "Holocene")))
        assertTrue(follower.isPending())
        assertEquals("queued", follower.pending().getValue("a").state)
        assertEquals("Holocene", follower.pending().getValue("a").title)
    }

    @Test
    fun aRefusalSaysWhyAndIsNotFollowed() {
        follower.ask(listOf(UpgradeAsk("a", "Holocene"), UpgradeAsk("b", "Towers")))
        follower.answered("a", LibraryActionResult("a", "upgrade", "skipped", "It is lossless already."))
        follower.answered("b", queued("b"))
        assertEquals("Looking for FLAC for 1 song. Could not look for a FLAC of Holocene: It is lossless already", follower.askedNotice())
        assertEquals(setOf("b"), follower.pending().keys)
    }

    @Test
    fun aSongThatCouldNotBeAskedSaysSo() {
        follower.ask(listOf(UpgradeAsk("a", "Holocene")))
        follower.answered("a", null, "Couldn't reach the server.")
        assertEquals("Could not look for a FLAC of Holocene: Couldn't reach the server", follower.askedNotice())
        assertFalse(follower.isPending())
    }

    @Test
    fun aLookWhileStillAskingLeavesTheSongAlone() {
        follower.ask(listOf(UpgradeAsk("a", "Holocene")))
        // An older try of the same song, finished days ago.
        val news = follower.seen(listOf(up("a", "notFound")))
        assertNull(news.notice)
        assertTrue(follower.isPending())
    }

    @Test
    fun progressIsKeptWhileTheServerIsOnIt() {
        follow("a")
        val news = follower.seen(listOf(up("a", "working").copy(progress = 0.5)))
        assertEquals(UpgradeNews(null, false), news)
        assertEquals(0.5, follower.pending().getValue("a").progress!!, 0.0)
    }

    @Test
    fun foundAndNotFoundAreSaidInOneLine() {
        follow("a", "b", "c")
        val news = follower.seen(listOf(up("a", "upgraded"), up("b", "upgraded"), up("c", "notFound", title = "Towers")))
        assertEquals("Found FLAC for 2 songs. No FLAC found for: Towers", news.notice)
        assertTrue(news.reload)
        assertFalse(follower.isPending())
    }

    @Test
    fun aFailureGivesTheServersWords() {
        follow("a")
        val news = follower.seen(listOf(up("a", "failed", title = "Holocene", detail = "The new file was not really lossless.")))
        assertEquals("Could not upgrade Holocene: The new file was not really lossless", news.notice)
        // Nothing landed, so nothing to read again.
        assertFalse(news.reload)
    }

    @Test
    fun manyNotFoundAreShortened() {
        follow("a", "b", "c", "d", "e")
        val news = follower.seen(listOf("a", "b", "c", "d", "e").map { up(it, "notFound") })
        assertEquals("No FLAC found for: Song a, Song b, Song c and 2 more", news.notice)
    }

    @Test
    fun waitingForSoulseekIsSaidOnceAWait() {
        follow("a", "b")
        assertEquals(WAITING_FOR_SOULSEEK, follower.seen(listOf(up("a", "waiting"), up("b", "queued"))).notice)
        assertNull(follower.seen(listOf(up("a", "waiting"), up("b", "waiting"))).notice)
        assertNull(follower.seen(listOf(up("a", "working"), up("b", "working"))).notice)
        assertEquals(WAITING_FOR_SOULSEEK, follower.seen(listOf(up("a", "waiting"), up("b", "working"))).notice)
    }

    @Test
    fun theLibraryIsReadAgainAtMostOnceAGapAndOnceAtTheEnd() {
        follow("a", "b", "c")
        // The first landing reads it again at once.
        assertTrue(follower.seen(listOf(up("a", "upgraded"), up("b", "working"), up("c", "working"))).reload)
        // Another lands 3 seconds later: held back.
        now += 3_000
        assertFalse(follower.seen(listOf(up("a", "upgraded"), up("b", "upgraded"), up("c", "working"))).reload)
        // Still held while nothing else lands, until the gap has passed.
        now += 3_000
        assertFalse(follower.seen(listOf(up("a", "upgraded"), up("b", "upgraded"), up("c", "working"))).reload)
        now += UPGRADE_RELOAD_GAP_MS
        assertTrue(follower.seen(listOf(up("a", "upgraded"), up("b", "upgraded"), up("c", "working"))).reload)
        // The last one ends soon after: read again regardless of the gap.
        now += 3_000
        assertTrue(follower.seen(listOf(up("a", "upgraded"), up("b", "upgraded"), up("c", "upgraded"))).reload)
    }

    @Test
    fun theLastOneEndingWithoutALandingReadsNothing() {
        follow("a")
        assertFalse(follower.seen(listOf(up("a", "notFound"))).reload)
    }

    @Test
    fun aStateFromANewerServerIsAskedAboutAgain() {
        follow("a")
        assertNull(follower.seen(listOf(up("a", "verifying"))).notice)
        assertTrue(follower.isPending())
    }

    @Test
    fun aSongTheServerStopsListingIsLetGo() {
        follow("a")
        repeat(UPGRADE_MISSES - 1) { follower.seen(emptyList()) }
        assertTrue(follower.isPending())
        follower.seen(emptyList())
        assertFalse(follower.isPending())
    }

    @Test
    fun theNewestTryOfASongCounts() {
        follow("a")
        val old = up("a", "notFound", updatedAt = "2026-10-01T12:00:00Z")
        assertNull(follower.seen(listOf(old, up("a", "working"))).notice)
        assertTrue(follower.isPending())
    }

    @Test
    fun songsStillOnFromBeforeAreTakenOnQuietly() {
        follower.adopt(listOf(up("a", "working"), up("b", "upgraded"), up("c", "waiting")))
        assertEquals(setOf("a", "c"), follower.pending().keys)
        assertEquals("Found FLAC for 1 song", follower.seen(listOf(up("a", "upgraded"), up("c", "waiting"))).notice)
    }

    @Test
    fun forgettingDropsEverything() {
        follow("a")
        follower.forget()
        assertFalse(follower.isPending())
    }
}
