package app.winters.octo.playback

// The queue arithmetic behind undo, on plain keys and positions. The app's
// QueueEditor applies it to the player.

// Where songs go back for an undo. `before` is the queue's keys before the
// edit and `now` its keys since. Each run is a queue position and the old
// positions of the songs that go there, lowest first; putting them back in
// that order puts every song at its old position. Nothing when the queue
// has changed some other way since, so an undo never scrambles it.
fun putBackRuns(before: List<String>, now: List<String>): List<Pair<Int, List<Int>>>? {
    val runs = mutableListOf<Pair<Int, MutableList<Int>>>()
    var kept = 0
    before.forEachIndexed { i, key ->
        if (kept < now.size && now[kept] == key) {
            kept++
        } else {
            val last = runs.lastOrNull()
            if (last != null && last.second.last() == i - 1) last.second += i else runs += i to mutableListOf(i)
        }
    }
    return if (kept == now.size && runs.isNotEmpty()) runs else null
}

// Queue positions to take out, as runs from the last to the first, so
// taking out one never moves the next.
fun removalRuns(positions: Collection<Int>): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    positions.toSortedSet().forEach { p ->
        val last = runs.lastOrNull()
        if (last != null && last.last == p - 1) runs[runs.lastIndex] = last.first..p else runs += p..p
    }
    return runs.reversed()
}

// Where these songs sit in the queue, by the queue's song ids, every time
// each one is there.
fun queuePositionsOf(queueTrackIds: List<String>, songs: Set<String>): List<Int> =
    queueTrackIds.indices.filter { queueTrackIds[it] in songs }
