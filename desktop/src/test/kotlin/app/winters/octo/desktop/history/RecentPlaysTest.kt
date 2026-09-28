package app.winters.octo.desktop.history

import app.winters.octo.desktop.listening.LoggedPlay
import app.winters.octo.desktop.listening.logged
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class RecentPlaysTest {
    private val zone = ZoneId.of("America/Chicago")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun iso(time: Long) = Instant.ofEpochMilli(time).toString()

    private fun song(id: String, played: Long? = null, plays: Long? = null, duration: Int = 200) =
        Song(id, "Song $id", duration = duration, played = played?.let(::iso), playCount = plays)

    private fun logged(song: Song, time: Long) = LoggedPlay(time, 180_000, song.logged())

    @Test
    fun everyPlayHereShowsAndTheServerAddsSongsPlayedElsewhere() {
        val a = song("a", played = at(28, 9), plays = 2)
        val b = song("b", played = at(27, 22), plays = 5)
        val log = listOf(logged(a, at(28, 9)), logged(a, at(26, 8)))
        val plays = recentPlays(log, listOf(a, b, song("never")))
        assertEquals(
            "a's last play is this computer's own; b was played elsewhere",
            listOf("a" to at(28, 9), "b" to at(27, 22), "a" to at(26, 8)),
            plays.map { it.song.id to it.at },
        )
    }

    @Test
    fun theServersTimeForOurOwnPlayMayBeLateByTheSongsLength() {
        // A server that ignores the start time Octo sends keeps the time the
        // play reached it, up to a song's length later.
        val a = song("a", played = at(28, 9, 3), duration = 240)
        val plays = recentPlays(listOf(logged(a, at(28, 9))), listOf(a))
        assertEquals(1, plays.size)
    }

    @Test
    fun aLaterPlayElsewhereOfASongPlayedHereShowsToo() {
        val a = song("a", played = at(28, 20))
        val plays = recentPlays(listOf(logged(a, at(27, 9))), listOf(a))
        assertEquals(listOf(at(28, 20), at(27, 9)), plays.map { it.at })
    }

    @Test
    fun aSongThatLeftTheLibraryStillShowsAsTheLogKeptIt() {
        val gone = Song("gone", "Gone song", artist = "Someone", album = "Somewhere", albumId = "al1", duration = 99)
        val plays = recentPlays(listOf(logged(gone, at(28, 9))), emptyList())
        assertEquals("Gone song", plays.single().song.title)
        assertEquals("al1", plays.single().song.albumId)
    }

    @Test
    fun theLibrarysCopyOfALoggedSongIsShownWithItsPlayCount() {
        val a = song("a", played = at(28, 9), plays = 12)
        val plays = recentPlays(listOf(logged(song("a"), at(28, 9))), listOf(a))
        assertEquals(12L, plays.single().song.playCount)
    }

    @Test
    fun onceTheLogIsCutOlderServerPlaysAreLeftOut() {
        val a = song("a", played = at(28, 9))
        val old = song("old", played = at(1, 9))
        val log = listOf(logged(a, at(28, 9)), logged(a, at(20, 9)))
        assertEquals(listOf("a", "a"), recentPlays(log, listOf(a, old), logCut = true).map { it.song.id })
        assertEquals(listOf("a", "a", "old"), recentPlays(log, listOf(a, old), logCut = false).map { it.song.id })
    }

    @Test
    fun theListKeepsToItsLimitNewestFirst() {
        val songs = (1..10).map { song("s$it", played = at(it, 12)) }
        assertEquals(listOf("s10", "s9", "s8"), recentPlays(emptyList(), songs, limit = 3).map { it.song.id })
    }

    @Test
    fun rowsCarryEachPlaysTimeAndADayHeadingAboveItsFirstPlay() {
        val now = at(28, 21)
        val plays = listOf(
            RecentPlay(song("a"), at(28, 20, 5)),
            RecentPlay(song("b"), at(28, 9, 30)),
            RecentPlay(song("a"), at(27, 23, 59)),
            RecentPlay(song("c"), at(22, 7)),
        )
        val rows = historyRows(plays, now, zone, Locale.US)
        assertEquals(listOf("a", "b", "a", "c"), rows.songs.map { it.id })
        assertEquals(mapOf(0 to "Today", 2 to "Yesterday", 3 to "Tuesday"), rows.headings)
        assertEquals(listOf("20:05", "9:30", "23:59", "7:00"), rows.times)
    }

    @Test
    fun aFilterKeepsEachDaysHeadingOverItsFirstRowShown() {
        val songs = (1..5).map { Song("s$it", "Song $it") }
        val rows = HistoryRows(songs, List(5) { "" }, mapOf(0 to "Today", 3 to "Yesterday"))
        // Everything shown: the headings stay where they were.
        assertEquals(mapOf(0 to "Today", 3 to "Yesterday"), rows.headingsShown(5) { it })
        // Rows 0 and 3 filtered out: rows 1 and 4 now lead their days.
        val kept = listOf(1, 2, 4)
        assertEquals(mapOf(0 to "Today", 2 to "Yesterday"), rows.headingsShown(kept.size) { kept[it] })
        // Nothing left from today: yesterday's heading still shows.
        assertEquals(mapOf(0 to "Yesterday"), rows.headingsShown(1) { 4 })
    }
}
