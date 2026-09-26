package app.winters.octo.listening

import app.winters.octo.catalog.PlayedAt
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.catalog.TrackEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// The History page's lists, from the plays on the phone and the server's
// record together, as the Home shelves use them.

// One play in the history: the song, and when it started.
data class HistoryPlay(val track: TrackEntity, val at: Long)

// A day's plays under its heading, newest first.
data class HistoryDay(val label: String, val plays: List<HistoryPlay>)

// Octo sends its own plays to the server with their start time, which the
// server may keep to the second only.
private const val SAME_PLAY_SLACK_MS = 1_000L

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
        val window = copy.track.durationMs.coerceAtLeast(0) + SAME_PLAY_SLACK_MS
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
// The plays come newest first and keep that order.
fun historyDays(plays: List<HistoryPlay>, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): List<HistoryDay> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = mutableListOf<HistoryDay>()
    var day: LocalDate? = null
    var bunch = mutableListOf<HistoryPlay>()
    for (play in plays) {
        val date = Instant.ofEpochMilli(play.at).atZone(zone).toLocalDate()
        if (date != day) {
            day?.let { days += HistoryDay(dayLabel(it, today, locale), bunch) }
            day = date
            bunch = mutableListOf()
        }
        bunch += play
    }
    day?.let { days += HistoryDay(dayLabel(it, today, locale), bunch) }
    return days
}

fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
    day == today -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> day.format(DateTimeFormatter.ofPattern("EEEE", locale))
    day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMMM d", locale))
    else -> day.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", locale))
}

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

// The most played songs, most first, then the one played last, from the
// phone's plays counted since `since` (all of them when it is null).
//
// For all time, the server's counts join as the Home shelf has them. The
// server keeps no dates for its plays but the last one, so within a range a
// song played elsewhere counts one more play when that last play falls in
// the range and was not the phone's own.
fun mostPlayed(
    local: List<PlayedTrack>,
    server: List<ServerPlayedTrack>,
    sent: Map<String, SentPlays>,
    since: Long?,
    limit: Int,
): List<PlayedTrack> {
    val merged = if (since == null) withServerPlays(local, server, sent) else withServerPlaysSince(local, server, sent, since)
    return merged
        .sortedWith(
            compareByDescending<PlayedTrack> { it.plays }
                .thenByDescending { it.lastPlayedAt }
                .thenBy { it.track.id },
        )
        .take(limit)
}

private fun withServerPlaysSince(
    local: List<PlayedTrack>,
    server: List<ServerPlayedTrack>,
    sent: Map<String, SentPlays>,
    since: Long,
): List<PlayedTrack> {
    val byId = local.associateByTo(LinkedHashMap()) { it.track.id }
    server.forEach { copy ->
        val last = copy.serverLastPlayedAt ?: return@forEach
        if (last < since) return@forEach
        // Plays the server holds that are not the phone's own.
        val others = copy.serverPlays - sent[copy.serverId].heldBy(last)
        if (others <= 0) return@forEach
        val mine = byId[copy.track.id]
        // The phone's latest play in the range is the server's last: its own.
        if (mine != null && abs(mine.lastPlayedAt - last) <= SAME_PLAY_SLACK_MS) return@forEach
        byId[copy.track.id] = if (mine == null) {
            PlayedTrack(copy.track, 1, last)
        } else {
            mine.copy(plays = mine.plays + 1, lastPlayedAt = maxOf(mine.lastPlayedAt, last))
        }
    }
    return byId.values.toList()
}
