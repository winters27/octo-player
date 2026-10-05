package app.winters.octo.playback

import androidx.media3.common.C

// A song in the queue as the "Up next" list shows it. `index` is where it
// sits in the player's queue; `key` stays unique when a song is queued
// twice, and stays with the song while others come and go. `autoplay` is a
// song Autoplay added, not one the listener chose; `source` is where it
// came from, for the queue's headings.
data class QueueEntry(
    val key: String,
    val index: Int,
    val trackId: String,
    val title: String,
    val artist: String,
    val artwork: String?,
    val durationMs: Long,
    val explicit: Boolean = false,
    val autoplay: Boolean = false,
    val source: QueueSource = NoSource,
)

// Where a song sits in the queue, and the key a list draws it under.
data class QueueSlot(val index: Int, val key: String)

// The key each song in the queue is drawn under: its queue entry id, or,
// for a song without one, its id and how many times it appears earlier in
// the queue, so the same song twice gets two keys.
fun queueKeys(trackIds: List<String>, entryIds: List<String?> = emptyList()): List<String> {
    val seen = HashMap<String, Int>()
    return trackIds.mapIndexed { i, id ->
        val times = seen[id] ?: 0
        seen[id] = times + 1
        entryIds.getOrNull(i) ?: "$id#$times"
    }
}

// The queue in the order it will play, starting with the current song.
// `next` gives the index played after a given one, or C.INDEX_UNSET when
// the queue runs out.
fun upNextOrder(trackIds: List<String>, current: Int, entryIds: List<String?> = emptyList(), next: (Int) -> Int): List<QueueSlot> =
    walk(trackIds, current, entryIds, next)

// The songs already played, in the order they played, ending just before
// the current song. `previous` gives the index played before a given one.
fun playedOrder(trackIds: List<String>, current: Int, entryIds: List<String?> = emptyList(), previous: (Int) -> Int): List<QueueSlot> {
    if (current !in trackIds.indices) return emptyList()
    return walk(trackIds, previous(current), entryIds, previous).reversed()
}

private fun walk(trackIds: List<String>, from: Int, entryIds: List<String?>, step: (Int) -> Int): List<QueueSlot> {
    if (from !in trackIds.indices) return emptyList()
    val keys = queueKeys(trackIds, entryIds)
    return buildList {
        var i = from
        // Never longer than the queue, even if the order loops back.
        while (i != C.INDEX_UNSET && size < trackIds.size) {
            add(QueueSlot(i, keys[i]))
            i = step(i)
        }
    }
}
