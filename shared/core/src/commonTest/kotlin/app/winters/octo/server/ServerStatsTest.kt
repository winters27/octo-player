package app.winters.octo.server

import app.winters.octo.subsonic.ScanStatus
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The words about a server both apps show.
class ServerStatsTest {
    private val utc = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 9, 29, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
    private val minute = 60_000L

    @Test
    fun theKindAndVersionReadAsOneName() {
        assertEquals("Octo 0.9.3", serverKind("octo", "0.9.3"))
        assertEquals("Navidrome", serverKind("navidrome", null))
        assertEquals("Subsonic server", serverKind(null, " "))
    }

    @Test
    fun whatItOffersIsSaidWithoutItsMachinery() {
        assertEquals("Offers synced lyrics and adding songs you find online to your library.", serverOffers(lyrics = true, adds = true))
        assertEquals("Offers synced lyrics.", serverOffers(lyrics = true, adds = false))
        assertNull(serverOffers(lyrics = false, adds = false))
    }

    @Test
    fun countsReadAsAListLeavingOutWhatIsNotKnown() {
        assertEquals("12,040 songs, 960 albums, 1 artist and 12 playlists", libraryCounts(12_040, 960, 1, 12))
        assertEquals("3 songs and 1 playlist", libraryCounts(3, null, null, 1))
        assertEquals("1 song", libraryCounts(1, null, null, null))
        assertNull(libraryCounts(null, null, null, null))
    }

    @Test
    fun answerTimesAreInMillisecondsThenSeconds() {
        assertEquals("Answered in 42 ms", answerWords(42))
        assertEquals("Answered in 1.3 s", answerWords(1_260))
    }

    @Test
    fun aScanInProgressSaysHowFarItIs() {
        assertEquals("Reading its folders now, 1,204 files so far.", scanWords(ScanStatus(scanning = true, count = 1_204), now, utc))
        assertEquals("Reading its folders now.", scanWords(ScanStatus(scanning = true), now, utc))
    }

    @Test
    fun theLastScanSaysWhen() {
        assertEquals("Last read its folders 3 hours ago.", scanWords(ScanStatus(lastScan = "2026-09-29T09:00:00Z"), now, utc))
        assertNull("a server that does not say", scanWords(ScanStatus(), now, utc))
        assertNull(scanWords(null, now, utc))
    }

    @Test
    fun timesAgoReadLikeSpeech() {
        assertEquals("just now", agoWords(now - 20_000, now, utc))
        assertEquals("a minute ago", agoWords(now - minute, now, utc))
        assertEquals("45 minutes ago", agoWords(now - 45 * minute, now, utc))
        assertEquals("an hour ago", agoWords(now - 61 * minute, now, utc))
        assertEquals("yesterday", agoWords(now - 30 * 60 * minute, now, utc))
        assertEquals("4 days ago", agoWords(now - 4 * 24 * 60 * minute, now, utc))
        assertEquals("on 12 September", agoWords(ZonedDateTime.of(2026, 9, 12, 8, 0, 0, 0, utc).toInstant().toEpochMilli(), now, utc))
        assertEquals("on 3 March 2025", agoWords(ZonedDateTime.of(2025, 3, 3, 8, 0, 0, 0, utc).toInstant().toEpochMilli(), now, utc))
        assertEquals("a clock running behind is not the future", "just now", agoWords(now + 5 * minute, now, utc))
    }
}
