package app.winters.octo.ui.admin

import app.winters.octo.admin.DownloadRecord
import app.winters.octo.admin.Health
import app.winters.octo.admin.LibraryStatus
import app.winters.octo.admin.RadioLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class AdminFormatTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-09-26T15:00:00Z")

    private fun ago(text: String) = ago(text, now, utc, Locale.ENGLISH)

    @Test
    fun readsTimesWithAndWithoutAZone() {
        assertEquals(Instant.parse("2026-09-26T14:00:00Z"), parseTime("2026-09-26T14:00:00Z"))
        assertEquals(Instant.parse("2026-09-26T14:00:00Z"), parseTime("2026-09-26T16:00:00+02:00"))
        assertEquals(Instant.parse("2026-09-26T14:00:00.1234567Z"), parseTime("2026-09-26T14:00:00.1234567Z"))
        assertEquals(Instant.parse("2026-09-26T14:00:00Z"), parseTime("2026-09-26T14:00:00"))
        assertNull(parseTime(null))
        assertNull(parseTime(""))
        assertNull(parseTime("yesterday"))
    }

    @Test
    fun saysRecentTimesInMinutes() {
        assertEquals("Just now", ago("2026-09-26T14:59:40Z"))
        assertEquals("5 min ago", ago("2026-09-26T14:55:00Z"))
        assertEquals("59 min ago", ago("2026-09-26T14:01:00Z"))
        // A clock a little ahead is still "just now".
        assertEquals("Just now", ago("2026-09-26T15:02:00Z"))
    }

    @Test
    fun saysEarlierTodayInHours() {
        assertEquals("1 hr ago", ago("2026-09-26T14:00:00Z"))
        assertEquals("14 hr ago", ago("2026-09-26T01:00:00Z"))
    }

    @Test
    fun lateLastNightIsHoursAgo() {
        val morning = Instant.parse("2026-09-26T02:00:00Z")
        assertEquals("3 hr ago", ago(Instant.parse("2026-09-25T23:00:00Z"), morning, utc, Locale.ENGLISH))
    }

    @Test
    fun thenYesterdayDaysAndDates() {
        assertEquals("Yesterday", ago("2026-09-25T08:00:00Z"))
        assertEquals("3 days ago", ago("2026-09-23T15:00:00Z"))
        assertEquals("12 Sep", ago("2026-09-12T10:00:00Z"))
        assertEquals("30 Dec 2025", ago("2025-12-30T10:00:00Z"))
    }

    @Test
    fun anUnreadableTimeSaysNothing() {
        assertNull(ago(null, now))
        assertNull(ago("soon", now))
    }

    @Test
    fun learningCountsUpToWhatItNeeds() {
        assertEquals("Learning your taste: 40 of 50 plays", learningLine(RadioLearning(plays = 40, needed = 50)))
        assertEquals("Learning your taste: 0 of 1 play", learningLine(RadioLearning(plays = 0, needed = 1)))
    }

    @Test
    fun learnedOnceItHasEnough() {
        assertEquals("Learned from 212 plays", learningLine(RadioLearning(plays = 212, needed = 50)))
        assertEquals("Learned from 50 plays", learningLine(RadioLearning(plays = 50, needed = 50)))
        assertEquals("Learned from 1 play", learningLine(RadioLearning(plays = 1, needed = 0)))
    }

    @Test
    fun healthPicksItsDot() {
        assertEquals(HealthDot.Ok, healthDot(Health(ok = true)))
        assertEquals(HealthDot.Warning, healthDot(Health(ok = true, warning = true)))
        assertEquals(HealthDot.Down, healthDot(Health(ok = false)))
        assertEquals(HealthDot.Down, healthDot(Health(ok = false, warning = true)))
        // Something never set up is not a failure.
        assertEquals(HealthDot.NotSetUp, healthDot(Health(ok = false, configured = false)))
    }

    @Test
    fun servicesHaveFriendlyNames() {
        assertEquals("Soulseek", serviceName("slskd"))
        assertEquals("Streaming helper", serviceName("ytDlpShim"))
        assertEquals("Last.fm", serviceName("lastfm"))
        assertEquals("somethingNew", serviceName("somethingNew"))
    }

    @Test
    fun refreshErrorsStayReadable() {
        assertNull(refreshProblem(null))
        assertNull(refreshProblem("  "))
        assertEquals("The last refresh did not finish: No plays yet", refreshProblem("No plays yet"))
        assertEquals("The last refresh did not finish.", refreshProblem("{\"error\":\"boom\"}"))
        assertEquals("The last refresh did not finish.", refreshProblem("System.Net.HttpRequestException: timed out"))
        assertEquals("The last refresh did not finish.", refreshProblem("failed\n   at Octo.Radio.Refresh()"))
    }

    @Test
    fun libraryNamesEachProblem() {
        assertTrue(libraryProblems(LibraryStatus(writable = true, rescanAuthenticated = true)).isEmpty())
        assertEquals(
            listOf("Octo cannot write to this folder", "Octo cannot ask Navidrome to rescan"),
            libraryProblems(LibraryStatus(writable = false, rescanAuthenticated = false)),
        )
    }

    @Test
    fun downloadLineSkipsWhatIsMissing() {
        assertEquals("Radiohead · FLAC", downloadLine(DownloadRecord(artist = "Radiohead", format = "flac")))
        assertEquals("Radiohead", downloadLine(DownloadRecord(artist = "Radiohead")))
        assertEquals("MP3", downloadLine(DownloadRecord(format = "mp3")))
    }
}
