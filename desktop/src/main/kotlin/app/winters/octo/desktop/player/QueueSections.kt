package app.winters.octo.desktop.player

import app.winters.octo.desktop.library.totalLengthText
import app.winters.octo.playback.QueueSource
import app.winters.octo.playback.queueSourceTitle
import app.winters.octo.playback.sourceRuns
import app.winters.octo.subsonic.Song
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// How many played songs the queue shows while Played is folded.
const val PLAYED_FOLDED = 3

enum class SectionKind { Played, NowPlaying, Coming }

// One part of the queue as the panel lists it: its heading and its songs.
// `hidden` counts played songs folded away; `source` is where a part of
// what is to come came from.
data class QueueSection(
    val kind: SectionKind,
    val title: String,
    val entries: List<QueueEntry>,
    val hidden: Int = 0,
    val source: QueueSource? = null,
)

// The queue in the panel's parts: Played (the last few unless opened), Now
// playing, then what is to come in runs by where it came from ("Next from
// you", "Next from OK Computer", or a plain "Up next"), in play order.
fun queueSections(state: PlayerState, playedOpen: Boolean = false): List<QueueSection> {
    val current = state.current ?: return emptyList()
    return buildList {
        if (state.played.isNotEmpty()) {
            val shown = if (playedOpen) state.played else state.played.takeLast(PLAYED_FOLDED)
            add(QueueSection(SectionKind.Played, "Played", shown, hidden = state.played.size - shown.size))
        }
        add(QueueSection(SectionKind.NowPlaying, "Now playing", listOf(current)))
        sourceRuns(state.upcoming) { it.source }.forEach { (source, entries) ->
            add(QueueSection(SectionKind.Coming, queueSourceTitle(source), entries, source = source))
        }
    }
}

// The queue's quiet line: how many songs are still to come, how long they
// last, and when the music will end at the speed it plays, like "12 songs
// to come · 48 min · Ends at 23:14". The end time is left out while a
// song's length is unknown (a stream). `leftMs` is what remains of the
// song playing.
fun queueSummary(
    state: PlayerState,
    leftMs: Long,
    now: LocalDateTime,
    clock: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT),
): String? {
    val current = state.current ?: return null
    val coming = state.upcoming
    val seconds = coming.sumOf { it.song.duration.coerceAtLeast(0) }
    val count = when (coming.size) {
        0 -> "Nothing after this song"
        1 -> "1 song to come · ${totalLengthText(seconds)}"
        else -> "${coming.size} songs to come · ${totalLengthText(seconds)}"
    }
    val known = current.song.duration > 0 && coming.all { it.song.duration > 0 }
    if (!known) return count
    val speed = state.speed.takeIf { it > 0f } ?: 1f
    val endsMs = ((leftMs.coerceAtLeast(0) + seconds * 1000L) / speed).toLong()
    return "$count · Ends at ${now.plusNanos(endsMs * 1_000_000).format(clock)}"
}

// The songs of the queue to save as a playlist, in the order they play:
// what played, the song on now and what is to come, without the songs
// Autoplay added, as the phone saves it.
fun songsToSave(state: PlayerState): List<Song> =
    (state.played + listOfNotNull(state.current) + state.upcoming).filter { it.source != QueueSource.Autoplay }.map { it.song }
