package app.winters.octo.desktop.history

import app.winters.octo.desktop.listening.LoggedPlay
import app.winters.octo.listening.byPlayDay
import app.winters.octo.server.serverTime
import app.winters.octo.subsonic.Song
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// The Recently played page's list: every play this computer logged, and
// for songs played elsewhere the last play the server knows of.

// One play: the song and when it began.
data class RecentPlay(val song: Song, val at: Long)

// How many plays the page shows at most.
const val RECENT_PLAYS = 2_000

// A server keeps the start time Octo sends to the second only, and a server
// that ignores it keeps the time the play reached it.
private const val SAME_PLAY_SLACK_MS = 1_000L

// The latest plays, newest first. The log holds every play made here, with
// the song as the library has it now when it is still there. The server adds
// the last time each song was played anywhere, which joins the list unless it
// is one of this computer's own plays sent there. When the log has been cut
// back, a server play older than its oldest play cannot be told from one of
// its own, so those are left out.
fun recentPlays(log: List<LoggedPlay>, library: List<Song>, limit: Int = RECENT_PLAYS, logCut: Boolean = false): List<RecentPlay> {
    val byId = library.associateBy { it.id }
    val mine = log.groupBy({ it.song.id }, { it.at })
    val horizon = if (logCut) log.minOfOrNull { it.at } ?: Long.MIN_VALUE else Long.MIN_VALUE
    val here = log.map { RecentPlay(byId[it.song.id] ?: it.song.toSong(), it.at) }
    val elsewhere = library.mapNotNull { song ->
        val at = serverTime(song.played) ?: return@mapNotNull null
        val window = song.duration.coerceAtLeast(0) * 1_000L + SAME_PLAY_SLACK_MS
        val ours = mine[song.id].orEmpty().any { abs(it - at) <= window }
        if (ours || at < horizon) null else RecentPlay(song, at)
    }
    return (here + elsewhere)
        .distinctBy { it.song.id to it.at }
        .sortedWith(compareByDescending<RecentPlay> { it.at }.thenBy { it.song.id })
        .take(limit)
}

// The plays as the song table takes them: the songs in order, each play's
// time for the table's first column, and a day's heading above its first play.
class HistoryRows(val songs: List<Song>, val times: List<String>, val headings: Map<Int, String>) {
    // The day headings for `count` rows shown out of these, where `placeOf`
    // gives a shown row's place among all of them: each day's heading goes
    // over the first of its rows still shown, so a filter never hides it.
    fun headingsShown(count: Int, placeOf: (Int) -> Int): Map<Int, String> {
        var day: String? = null
        val dayOf = songs.indices.map { i -> headings[i]?.let { day = it }; day }
        return buildMap {
            var last: String? = null
            for (row in 0 until count) {
                val here = dayOf.getOrNull(placeOf(row))
                if (here != null && here != last) put(row, here)
                last = here
            }
        }
    }
}

// A play's time on the 24-hour clock ("21:42"), which fits the table's
// narrow first column where "9:42 PM" would be cut short.
private val clock = DateTimeFormatter.ofPattern("H:mm")

fun historyRows(plays: List<RecentPlay>, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): HistoryRows {
    val headings = HashMap<Int, String>()
    var at = 0
    byPlayDay(plays, { it.at }, now, zone, locale).forEach { day ->
        headings[at] = day.heading
        at += day.items.size
    }
    return HistoryRows(
        songs = plays.map { it.song },
        times = plays.map { Instant.ofEpochMilli(it.at).atZone(zone).format(clock) },
        headings = headings,
    )
}
