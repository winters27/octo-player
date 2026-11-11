package app.winters.octo.server.today

import app.winters.octo.playback.QueueSnapshot
import app.winters.octo.server.ServerQueue

// A labeled pin: today's queue as the server keeps it, serverQueueOf and
// trimmed as server/QueueSync.kt and shared server/ServerQueues.kt had them
// at c2208eaa, frozen as the measure the paged server view must match on
// queues that fit. Never edited to follow the app.

// The most songs saved on the server.
private const val SERVER_QUEUE_MAX = 400

// How many songs before the current one a trimmed queue keeps.
private const val KEEP_BEFORE = 50

// The phone's queue as a queue for the server. Songs the server does not
// have are left out. With shuffle on, the songs go in the order they will
// play, so the other device carries on with the same songs next. When the
// current song is not on the server, the next one that is takes its place,
// from its start. Null when no song is on the server.
fun serverQueueOf(snapshot: QueueSnapshot, serverIdOf: (String) -> String?): ServerQueue? {
    val ids = snapshot.trackIds
    val shuffled = snapshot.shuffle && snapshot.shuffleOrder.size == ids.size && snapshot.shuffleOrder.toSet() == ids.indices.toSet()
    val order = if (shuffled) snapshot.shuffleOrder else ids.indices.toList()
    // Each song on the server: its place in play order, and its server id.
    val kept = order.mapIndexedNotNull { rank, position -> serverIdOf(ids[position])?.let { Triple(rank, position, it) } }
    if (kept.isEmpty()) return null
    val serverIds = kept.map { it.third }
    val exact = kept.indexOfFirst { it.second == snapshot.index }
    if (exact >= 0) return ServerQueue(serverIds, exact, snapshot.positionMs.coerceAtLeast(0))
    val currentRank = order.indexOf(snapshot.index)
    val next = kept.indexOfFirst { it.first > currentRank }
    return ServerQueue(serverIds, if (next >= 0) next else kept.lastIndex, 0)
}

// A long queue cut down around the current song: a few before it, the rest after.
fun ServerQueue.trimmed(max: Int = SERVER_QUEUE_MAX): ServerQueue {
    if (ids.size <= max) return this
    val start = (index - KEEP_BEFORE).coerceIn(0, ids.size - max)
    return copy(ids = ids.subList(start, start + max).toList(), index = index - start)
}
