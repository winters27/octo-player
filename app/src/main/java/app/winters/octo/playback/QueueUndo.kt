package app.winters.octo.playback

// Where songs put in the queue together are now, to take them back out.
// They went in at `insertedAt`, but the queue may have changed since: the
// run of those songs nearest that place is taken to be them. Null when the
// run is not in the queue any more.
fun findInserted(queue: List<String>, inserted: List<String>, insertedAt: Int): Int? {
    if (inserted.isEmpty() || inserted.size > queue.size) return null
    return (0..queue.size - inserted.size)
        .filter { start -> inserted.indices.all { queue[start + it] == inserted[it] } }
        .minByOrNull { kotlin.math.abs(it - insertedAt) }
}
