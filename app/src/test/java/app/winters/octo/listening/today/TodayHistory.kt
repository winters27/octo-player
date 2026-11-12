package app.winters.octo.listening.today

import app.winters.octo.catalog.PlayedAt
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.listening.HistoryPlay
import app.winters.octo.listening.SentPlays
import kotlin.math.abs

// A labeled pin: today's History lists, recentHistory and mostPlayed as
// listening/History.kt had them at c2208eaa, frozen as the measure the
// bounded readers must match. Never edited to follow the app.

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
