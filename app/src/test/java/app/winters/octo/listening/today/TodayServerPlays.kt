package app.winters.octo.listening.today

import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.listening.SentPlays

// A labeled pin: today's sum of the phone's plays and the server's, as
// listening/ServerPlays.kt had it at c2208eaa, frozen as the measure the
// stored play totals must match. Never edited to follow the app.
//
// Octo records every play on the phone and also sends plays of songs with a
// server copy to the server, so the server's count holds Octo's own plays
// too. Adding the two would count those twice. So Octo remembers the plays
// it sent each server song, and takes off the ones the server's count
// already holds: those that started no later than the server's last play.
// A play sent after the server's record was read is not in it yet, and is
// not taken off.

// The server may keep times to the second only.
private const val SERVER_TIME_SLACK_MS = 999L

private fun isHeld(startedAt: Long, serverLastPlayedAt: Long?) =
    serverLastPlayedAt != null && startedAt <= serverLastPlayedAt + SERVER_TIME_SLACK_MS

// How many of the plays Octo sent the server's record holds.
fun SentPlays?.heldBy(serverLastPlayedAt: Long?): Int =
    if (this == null) 0 else folded + recent.count { isHeld(it, serverLastPlayedAt) }

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
