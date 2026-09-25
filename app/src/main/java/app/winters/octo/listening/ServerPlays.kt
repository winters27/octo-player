package app.winters.octo.listening

import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack

// Plays on the phone and plays on the server, counted together.
//
// Octo records every play on the phone and also sends plays of songs with a
// server copy to the server, so the server's count holds Octo's own plays
// too. Adding the two would count those twice. So Octo remembers the plays
// it sent each server song, and takes off the ones the server's count
// already holds: those that started no later than the server's last play.
// A play sent after the server's record was read is not in it yet, and is
// not taken off.

// The plays Octo sent the server for one song: how many the server's record
// is known to hold, and the start times of newer ones.
data class SentPlays(val folded: Int = 0, val recent: List<Long> = emptyList())

// The server may keep times to the second only.
private const val SERVER_TIME_SLACK_MS = 999L

private fun isHeld(startedAt: Long, serverLastPlayedAt: Long?) =
    serverLastPlayedAt != null && startedAt <= serverLastPlayedAt + SERVER_TIME_SLACK_MS

// How many of the plays Octo sent the server's record holds.
fun SentPlays?.heldBy(serverLastPlayedAt: Long?): Int =
    if (this == null) 0 else folded + recent.count { isHeld(it, serverLastPlayedAt) }

fun Map<String, SentPlays>.plusSent(serverId: String, startedAt: Long): Map<String, SentPlays> {
    val before = this[serverId] ?: SentPlays()
    return this + (serverId to before.copy(recent = (before.recent + startedAt).sorted()))
}

// After a sync: plays the server's record now holds become a plain number,
// and songs the server no longer has are forgotten.
fun Map<String, SentPlays>.foldedInto(lastPlayed: Map<String, Long?>): Map<String, SentPlays> =
    filterKeys { it in lastPlayed }.mapValues { (id, sent) ->
        val (held, newer) = sent.recent.partition { isHeld(it, lastPlayed[id]) }
        SentPlays(sent.folded + held.size, newer)
    }

// Each song's plays on the phone plus the server's plays that are not Octo's
// own, and the later of the two last plays. A song played only on the server
// joins the list.
fun withServerPlays(
    local: List<PlayedTrack>,
    server: List<ServerPlayedTrack>,
    sent: Map<String, SentPlays>,
): List<PlayedTrack> {
    val byId = local.associateByTo(LinkedHashMap()) { it.track.id }
    server.forEach { copy ->
        val others = (copy.serverPlays - sent[copy.serverId].heldBy(copy.serverLastPlayedAt)).coerceAtLeast(0)
        val last = copy.serverLastPlayedAt ?: 0
        val mine = byId[copy.track.id]
        byId[copy.track.id] = if (mine == null) {
            PlayedTrack(copy.track, others, last)
        } else {
            mine.copy(plays = mine.plays + others, lastPlayedAt = maxOf(mine.lastPlayedAt, last))
        }
    }
    return byId.values.filter { it.plays > 0 }
}

// Each album once, at the later of its last play on the phone and on the server.
fun withServerAlbumPlays(local: List<PlayedAlbum>, server: List<PlayedAlbum>): List<PlayedAlbum> =
    (local + server).groupBy { it.album.id }.map { (_, rows) -> rows.maxBy { it.lastPlayedAt } }

// Stored one song per line: "<folded> <start>,<start> <server id>".
fun encodeSent(sent: Map<String, SentPlays>): Set<String> =
    sent.mapTo(HashSet()) { (id, plays) -> "${plays.folded} ${plays.recent.joinToString(",")} $id" }

fun decodeSent(lines: Set<String>): Map<String, SentPlays> =
    lines.mapNotNull { line ->
        val parts = line.split(" ", limit = 3)
        val folded = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val id = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val recent = parts[1].split(",").mapNotNull { it.toLongOrNull() }
        id to SentPlays(folded, recent)
    }.toMap()
