package app.winters.octo.playback

// A shuffled queue is two lists: the songs in queue order, and `order`, the
// queue positions in the order they play. These keep the second in step
// with the first as songs come and go, so a song put in never lands at a
// random spot. The Media3 ShuffleOrder that uses them is the app's
// QueueShuffleOrder.

// Songs put in at queue positions `at` until `at + count` play, in queue
// order, after the first `rank` entries of the shuffled order. Every song
// already at `at` or later moves along by `count`.
fun insertIntoShuffle(order: IntArray, at: Int, count: Int, rank: Int): IntArray {
    val shifted = order.map { if (it >= at) it + count else it }
    val place = rank.coerceIn(0, shifted.size)
    return (shifted.subList(0, place) + (at until at + count) + shifted.subList(place, shifted.size)).toIntArray()
}

// How many songs of the shuffled order play before songs put in at queue
// position `at` of a queue `size` long, while `current` is on:
// - "Play next" (`playNext`): right after the song that is on;
// - at the end of the queue ("Add to queue"): after all of them;
// - right after the song that is on in the queue: right after it;
// - just before a song Autoplay added: just before it, so the listener's
//   own songs play first;
// - anywhere else: right after the song before them in the queue.
fun shuffleRankFor(order: IntArray, at: Int, size: Int, current: Int, playNext: Boolean = false, beforeAutoplay: Boolean = false): Int {
    fun after(position: Int) = order.indexOf(position).let { if (it < 0) order.size else it + 1 }
    return when {
        playNext && current >= 0 -> after(current)
        at >= size -> order.size
        current >= 0 && at == current + 1 -> after(current)
        beforeAutoplay -> order.indexOf(at).let { if (it < 0) order.size else it }
        at == 0 -> 0
        else -> after(at - 1)
    }
}

// Where songs the listener adds at queue position `at` go. Added at the end,
// they go before the first Autoplay song still to come (`upcoming` is the
// queue positions after the current song, in play order), so the
// listener's own songs play before Autoplay's. Anywhere else, at `at`.
fun ownSongsAt(at: Int, upcoming: List<Int>, autoplay: List<Boolean>): Int {
    if (at < autoplay.size) return at
    return upcoming.firstOrNull { autoplay.getOrElse(it) { false } } ?: at
}

// The shuffled order once queue positions `from` until `to` are taken out:
// the rest keep playing in the same order.
fun removeFromShuffle(order: IntArray, from: Int, to: Int): IntArray {
    val count = to - from
    return order.filter { it < from || it >= to }.map { if (it >= to) it - count else it }.toIntArray()
}

// The shuffled order once queue positions `from` until `to` move to start
// at `newFrom`: each song keeps its place in the play order.
fun moveInShuffle(order: IntArray, from: Int, to: Int, newFrom: Int): IntArray {
    val size = order.size
    val moved = (from until to).toList()
    val rest = (0 until size).filter { it < from || it >= to }
    val queue = rest.subList(0, newFrom) + moved + rest.subList(newFrom, rest.size)
    // Where each old queue position ends up.
    val newPosition = IntArray(size)
    queue.forEachIndexed { position, old -> newPosition[old] = position }
    return order.map { newPosition[it] }.toIntArray()
}
