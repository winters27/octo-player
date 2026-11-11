package app.winters.octo.listening

import app.winters.octo.catalog.PlayedAt
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.catalog.TrackEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

// The History page's lists, from the plays on the phone and the server's
// record together, as the Home shelves use them. PlayHistory reads them a
// page at a time (PlayShelves.kt); the most played within a range count
// each song by playsSince (PlayWalk.kt).

// One play in the history: the song, and when it started.
data class HistoryPlay(val track: TrackEntity, val at: Long)

// A day's plays under its heading, newest first.
data class HistoryDay(val label: String, val plays: List<HistoryPlay>)

// The latest plays, newest first. The phone knows every play it made; the
// server adds only the last time each song was played anywhere, which joins
// the list unless it is one of the phone's own plays sent there. Past the
// oldest phone play loaded, that cannot be told, so older server plays are
// left out.
fun recentHistory(local: List<PlayedAt>, server: List<ServerPlayedTrack>, limit: Int): List<HistoryPlay> {
    val mine = local.groupBy({ it.track.id }, { it.startedAt })
    val horizon = if (local.size >= limit) local.minOfOrNull { it.startedAt } ?: Long.MIN_VALUE else Long.MIN_VALUE
    val elsewhere = server.mapNotNull { copy ->
        val at = copy.serverLastPlayedAt ?: return@mapNotNull null
        val window = copy.track.durationMs.coerceAtLeast(0) + OWN_PLAY_SLACK_MS
        val ours = mine[copy.track.id].orEmpty().any { abs(it - at) <= window }
        if (ours || at < horizon) null else HistoryPlay(copy.track, at)
    }
    return (local.map { HistoryPlay(it.track, it.startedAt) } + elsewhere)
        .distinctBy { it.track.id to it.at }
        .sortedWith(compareByDescending<HistoryPlay> { it.at }.thenBy { it.track.id })
        .take(limit)
}

// Plays under a heading per day: Today, Yesterday, the weekday for the
// rest of the week, then the date, with the year once it is not this one.
// The plays come newest first and keep that order. The headings are shared
// with the desktop.
fun historyDays(plays: List<HistoryPlay>, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): List<HistoryDay> =
    byPlayDay(plays, { it.at }, now, zone, locale).map { HistoryDay(it.heading, it.items) }

fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = playDayHeading(day, today, locale)

// How far back "most played" looks.
enum class HistoryRange(val label: String) {
    FourWeeks("Last 4 weeks"),
    SixMonths("Last 6 months"),
    AllTime("All time"),
}

// When a range starts, or null for all time. Six months is by the calendar.
fun rangeStart(range: HistoryRange, now: Long, zone: ZoneId): Long? = when (range) {
    HistoryRange.FourWeeks -> now - 28L * 24 * 60 * 60 * 1000
    HistoryRange.SixMonths -> Instant.ofEpochMilli(now).atZone(zone).minusMonths(6).toInstant().toEpochMilli()
    HistoryRange.AllTime -> null
}
