package app.winters.octo.desktop.player

import app.winters.octo.playback.NoSource
import app.winters.octo.playback.QueueSource
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// The queue panel's parts, worked out from the player's state alone.
class QueueSectionsTest {
    private val album = QueueSource.Played("OK Computer")
    private val you = QueueSource.You
    private var nextKey = 1L

    private fun entry(id: String, source: QueueSource = album, seconds: Int = 180) =
        QueueEntry(nextKey++, Song(id, "Song $id", duration = seconds), source)

    private fun titles(sections: List<QueueSection>) = sections.map { it.title to it.entries.map { e -> e.song.id } }

    @Test
    fun theQueueReadsPlayedNowPlayingThenEachSource() {
        val played = (1..5).map { entry("p$it") }
        val state = PlayerState(
            played = played,
            current = entry("now"),
            upcoming = listOf(entry("x", you), entry("y", you), entry("a"), entry("b")),
        )
        assertEquals(
            listOf(
                "Played" to listOf("p3", "p4", "p5"),
                "Now playing" to listOf("now"),
                "Next from you" to listOf("x", "y"),
                "Next from OK Computer" to listOf("a", "b"),
            ),
            titles(queueSections(state)),
        )
        assertEquals(2, queueSections(state).first().hidden)
    }

    @Test
    fun openedPlayedShowsEverySongPlayed() {
        val state = PlayerState(played = (1..5).map { entry("p$it") }, current = entry("now"))
        val played = queueSections(state, playedOpen = true).first()
        assertEquals(SectionKind.Played, played.kind)
        assertEquals(5, played.entries.size)
        assertEquals(0, played.hidden)
    }

    @Test
    fun aListWithNoNameIsJustUpNextAndNothingPlayedHasNoPart() {
        val state = PlayerState(current = entry("now", NoSource), upcoming = listOf(entry("a", NoSource)))
        assertEquals(listOf("Now playing" to listOf("now"), "Up next" to listOf("a")), titles(queueSections(state)))
    }

    @Test
    fun songsAddedAfterTheListComeAfterIt() {
        val state = PlayerState(current = entry("now"), upcoming = listOf(entry("a"), entry("z", you), entry("auto", QueueSource.Autoplay)))
        assertEquals(
            listOf("Now playing", "Next from OK Computer", "Next from you", "Next from Autoplay"),
            queueSections(state).map { it.title },
        )
    }

    @Test
    fun anEmptyQueueHasNoParts() {
        assertEquals(emptyList<QueueSection>(), queueSections(PlayerState()))
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val evening = LocalDateTime.of(2026, 9, 28, 22, 0)

    @Test
    fun theQuietLineSaysHowLongIsLeftAndWhenItEnds() {
        val state = PlayerState(current = entry("now", seconds = 240), upcoming = listOf(entry("a", seconds = 600), entry("b", seconds = 600)))
        assertEquals("2 songs to come · 20 min · Ends at 22:22", queueSummary(state, leftMs = 120_000, now = evening, clock = clock))
        // Faster, it ends sooner.
        assertEquals("2 songs to come · 20 min · Ends at 22:11", queueSummary(state.copy(speed = 2f), leftMs = 120_000, now = evening, clock = clock))
    }

    @Test
    fun theLastSongSaysNothingComesAfter() {
        val state = PlayerState(current = entry("now", seconds = 240))
        assertEquals("Nothing after this song · Ends at 22:01", queueSummary(state, leftMs = 60_000, now = evening, clock = clock))
        assertNull(queueSummary(PlayerState(), 0, evening, clock))
    }

    @Test
    fun aSongOfUnknownLengthLeavesTheEndTimeOut() {
        val state = PlayerState(current = entry("stream", seconds = 0), upcoming = listOf(entry("a", seconds = 120)))
        assertEquals("1 song to come · 2 min", queueSummary(state, 0, evening, clock))
    }

    @Test
    fun savingTheQueueKeepsPlayOrderWithoutAutoplaysSongs() {
        val state = PlayerState(
            played = listOf(entry("p")),
            current = entry("now"),
            upcoming = listOf(entry("x", you), entry("auto", QueueSource.Autoplay), entry("a")),
        )
        assertEquals(listOf("p", "now", "x", "a"), songsToSave(state).map { it.id })
    }
}
