package app.winters.octo.listening

import app.winters.octo.catalog.PlayedAt
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class HistoryTest {
    private val zone = ZoneId.of("America/Chicago")

    private fun track(id: String, durationMs: Long = 200_000) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = durationMs, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun play(id: String, time: Long) = HistoryPlay(track(id), time)

    // Days

    @Test
    fun playsFallUnderTodayYesterdayAndTheWeekday() {
        val now = at(2026, 9, 26, 20)
        val plays = listOf(
            play("a", at(2026, 9, 26, 19)),
            play("b", at(2026, 9, 26, 0, 5)),
            play("c", at(2026, 9, 25, 23, 59)),
            play("d", at(2026, 9, 22)),
        )
        val days = historyDays(plays, now, zone, Locale.US)
        assertEquals(listOf("Today", "Yesterday", "Tuesday"), days.map { it.label })
        assertEquals(listOf("a", "b"), days[0].plays.map { it.track.id })
        assertEquals(listOf("c"), days[1].plays.map { it.track.id })
    }

    @Test
    fun olderDaysShowTheDateAndTheYearOnceItIsNotThisOne() {
        val today = LocalDate.of(2026, 9, 26)
        assertEquals("September 19", dayLabel(LocalDate.of(2026, 9, 19), today, Locale.US))
        assertEquals("Sunday", dayLabel(LocalDate.of(2026, 9, 20), today, Locale.US))
        assertEquals("December 31, 2025", dayLabel(LocalDate.of(2025, 12, 31), today, Locale.US))
    }

    @Test
    fun midnightIsCountedInTheListenersZone() {
        // 23:30 in Chicago is already the next day in UTC.
        val now = at(2026, 9, 26, 23, 45)
        val days = historyDays(listOf(play("a", at(2026, 9, 26, 23, 30))), now, zone, Locale.US)
        assertEquals("Today", days.single().label)
    }

    @Test
    fun theSameSongPlayedTwiceShowsTwice() {
        val now = at(2026, 9, 26, 20)
        val days = historyDays(listOf(play("a", at(2026, 9, 26, 19)), play("a", at(2026, 9, 26, 18))), now, zone, Locale.US)
        assertEquals(2, days.single().plays.size)
    }

    @Test
    fun noPlaysNoDays() {
        assertEquals(emptyList<HistoryDay>(), historyDays(emptyList(), at(2026, 9, 26), zone, Locale.US))
    }

    // The recent list

    private fun local(id: String, time: Long) = PlayedAt(track(id), time)

    private fun server(id: String, plays: Int, last: Long?, serverId: String = "s$id") =
        ServerPlayedTrack(track(id), serverId, plays, last)

    @Test
    fun theServersLastPlayJoinsWhenItIsNotThePhonesOwn() {
        val mine = listOf(local("a", 5_000_000))
        val elsewhere = listOf(server("b", 3, 9_000_000))
        val list = recentHistory(mine, elsewhere, 100)
        assertEquals(listOf("b", "a"), list.map { it.track.id })
    }

    @Test
    fun aPlayTheServerHeardFromThePhoneIsNotShownTwice() {
        // Sent with its start time; the server keeps whole seconds.
        val mine = listOf(local("a", 5_000_400))
        val list = recentHistory(mine, listOf(server("a", 1, 5_000_000)), 100)
        assertEquals(1, list.size)
        assertEquals(5_000_400, list.single().at)
    }

    @Test
    fun serverPlaysOlderThanTheLoadedPhonePlaysAreLeftOut() {
        // The list is full, so an old server play might be the phone's own.
        val mine = listOf(local("a", 9_000_000), local("b", 8_000_000))
        val list = recentHistory(mine, listOf(server("c", 1, 1_000_000)), 2)
        assertEquals(listOf("a", "b"), list.map { it.track.id })
    }

    @Test
    fun aSongNeverPlayedOnTheServerAddsNothing() {
        val list = recentHistory(emptyList(), listOf(server("a", 0, null)), 100)
        assertEquals(emptyList<HistoryPlay>(), list)
    }

    // Most played over a range

    @Test
    fun rangesStartFourWeeksOrSixCalendarMonthsBack() {
        val now = at(2026, 9, 26, 12)
        assertEquals(now - 28L * 24 * 60 * 60 * 1000, rangeStart(HistoryRange.FourWeeks, now, zone))
        assertEquals(at(2026, 3, 26, 12), rangeStart(HistoryRange.SixMonths, now, zone))
        assertNull(rangeStart(HistoryRange.AllTime, now, zone))
    }

    private fun counted(id: String, plays: Int, last: Long) = PlayedTrack(track(id), plays, last)

    @Test
    fun allTimeAddsTheServersCounts() {
        val top = mostPlayed(listOf(counted("a", 2, 100)), listOf(server("a", 5, 50), server("b", 4, 70)), emptyMap(), null, 10)
        assertEquals(listOf("a" to 7, "b" to 4), top.map { it.track.id to it.plays })
    }

    @Test
    fun aRangeCountsOnlyThePhonesPlaysInIt() {
        // The phone's plays arrive already counted from the range's start.
        val since = 1_000L
        val top = mostPlayed(listOf(counted("a", 2, 5_000), counted("b", 3, 4_000)), emptyList(), emptyMap(), since, 10)
        assertEquals(listOf("b" to 3, "a" to 2), top.map { it.track.id to it.plays })
    }

    @Test
    fun aRangeCountsAServerPlayElsewhereOnceWhenItFallsInside() {
        val since = 1_000_000L
        val server = listOf(server("a", 40, 2_000_000), server("b", 9, 500_000))
        val top = mostPlayed(listOf(counted("a", 1, 1_500_000)), server, emptyMap(), since, 10)
        // "a": one on the phone plus one elsewhere; "b" was last played before the range.
        assertEquals(listOf("a" to 2), top.map { it.track.id to it.plays })
    }

    @Test
    fun aRangeDoesNotCountThePhonesOwnPlayTwice() {
        val since = 1_000L
        // The server's last play is the phone's, sent to it.
        val top = mostPlayed(listOf(counted("a", 1, 2_000)), listOf(server("a", 1, 2_000)), emptyMap(), since, 10)
        assertEquals(listOf("a" to 1), top.map { it.track.id to it.plays })
    }

    @Test
    fun aRangeLeavesOutServerPlaysThatAreAllThePhonesOwn() {
        val since = 1_000L
        val sent = mapOf("sa" to SentPlays(folded = 3))
        val top = mostPlayed(emptyList(), listOf(server("a", 3, 2_000)), sent, since, 10)
        assertEquals(emptyList<PlayedTrack>(), top)
    }

    @Test
    fun theListStopsAtItsLimitMostFirst() {
        val songs = (1..30).map { counted("s$it", it, it.toLong()) }
        val top = mostPlayed(songs, emptyList(), emptyMap(), 0L, 10)
        assertEquals(10, top.size)
        assertEquals("s30", top.first().track.id)
    }
}
