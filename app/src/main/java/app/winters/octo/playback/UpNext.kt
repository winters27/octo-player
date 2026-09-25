package app.winters.octo.playback

import androidx.media3.common.C

// A song in the queue as the "Up next" list shows it. `index` is where it
// sits in the player's queue; `key` stays unique when a song is queued twice.
data class QueueEntry(
    val key: String,
    val index: Int,
    val trackId: String,
    val title: String,
    val artist: String,
    val artwork: String?,
    val durationMs: Long,
)

// Where a song sits in the queue, and the key a list draws it under.
data class QueueSlot(val index: Int, val key: String)

// The queue in the order it will play, starting with the current song.
// `next` gives the index played after a given one, or C.INDEX_UNSET when
// the queue runs out. A key is the song's id and how many times it appears
// earlier in the queue, so the same song twice gets two keys.
fun upNextOrder(trackIds: List<String>, current: Int, next: (Int) -> Int): List<QueueSlot> {
    if (current !in trackIds.indices) return emptyList()
    val seen = HashMap<String, Int>()
    val keys = trackIds.map { id ->
        val times = seen[id] ?: 0
        seen[id] = times + 1
        "$id#$times"
    }
    return buildList {
        var i = current
        // Never longer than the queue, even if the order loops back.
        while (i != C.INDEX_UNSET && size < trackIds.size) {
            add(QueueSlot(i, keys[i]))
            i = next(i)
        }
    }
}
