package app.winters.octo.server

import app.winters.octo.subsonic.PlayQueue
import app.winters.octo.subsonic.PlayQueueByIndex
import app.winters.octo.subsonic.Song
import java.time.Instant
import java.time.OffsetDateTime

// The play queue as the server keeps it. The phone and the desktop share
// this, so both save and pick up queues the same way.

// The most songs saved on the server. A save is one address with every id
// in it, and proxies in front of a server refuse addresses much longer than
// this makes (about 11 KB).
const val SERVER_QUEUE_MAX = 400

// How many songs before the current one a trimmed queue keeps.
private const val KEEP_BEFORE = 50

// A queue as the server keeps it: server song ids in the order they will
// play, the current one's place, and how far into it playback is.
data class ServerQueue(val ids: List<String>, val index: Int, val positionMs: Long)

// A long queue cut down around the current song: a few before it, the rest after.
fun ServerQueue.trimmed(max: Int = SERVER_QUEUE_MAX): ServerQueue {
    if (ids.size <= max) return this
    val start = (index - KEEP_BEFORE).coerceIn(0, ids.size - max)
    return copy(ids = ids.subList(start, start + max).toList(), index = index - start)
}

// A queue another device saved: its songs, the current one's place, the
// position in it, when it was saved and by which app.
data class RemoteQueue(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val changedAt: Long?,
    val changedBy: String?,
) {
    val current: Song? get() = songs.getOrNull(index)
}

fun remoteQueueOf(queue: PlayQueueByIndex): RemoteQueue = RemoteQueue(
    songs = queue.entry,
    index = (queue.currentIndex ?: 0).coerceIn(0, (queue.entry.size - 1).coerceAtLeast(0)),
    positionMs = queue.position.coerceAtLeast(0),
    changedAt = serverTime(queue.changed),
    changedBy = queue.changedBy,
)

fun remoteQueueOf(queue: PlayQueue): RemoteQueue {
    val index = queue.entry.indexOfFirst { it.id == queue.current }
    return RemoteQueue(
        songs = queue.entry,
        index = index.coerceAtLeast(0),
        // A position only means something in the song it was saved for.
        positionMs = if (index >= 0) queue.position.coerceAtLeast(0) else 0,
        changedAt = serverTime(queue.changed),
        changedBy = queue.changedBy,
    )
}

// A server's time as milliseconds since 1970, or null when it cannot be read.
fun serverTime(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

// Whether to offer picking up a queue from the server: it has songs, it is
// not the one this device saved last (known by the server's own time stamp
// for that save, so another device running Octo still counts as another
// device), and it was saved after this device last saved or answered an offer.
fun shouldOfferResume(remote: RemoteQueue?, lastSavedAt: Long, answeredUpTo: Long, ownStamp: Long?): Boolean {
    if (remote == null || remote.current == null) return false
    val changedAt = remote.changedAt ?: return false
    if (changedAt == ownStamp) return false
    return changedAt > maxOf(lastSavedAt, answeredUpTo)
}
