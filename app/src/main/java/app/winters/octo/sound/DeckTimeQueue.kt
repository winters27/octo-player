package app.winters.octo.sound

// One stretch of a deck's sound as its last processor handed it on: how
// many frames (at the song's rate), the song time of the first, how far the
// song moves per frame, and the stream they belong to. Frames inside one
// label are contiguous in song time.
class SongLabel {
    var frames = 0
    var songMs = 0.0
    var msPerFrame = 0.0

    // The queue entry the stream plays, when the player says.
    var entryId: String? = null

    // The first label after the stream started (a seek, a new song, a
    // reconfiguration): with the entry and the song time it decides whether
    // the deck moved on to another play of a song.
    var streamStart = false

    // The song's length in ms, or NaN when it is not known.
    var songEndMs = Double.NaN

    // A live or unseekable window, which never continues by song time.
    var live = false

    // Frames of this label already taken by the deck's output.
    var taken = 0

    fun set(from: SongLabel) {
        frames = from.frames
        songMs = from.songMs
        msPerFrame = from.msPerFrame
        entryId = from.entryId
        streamStart = from.streamStart
        songEndMs = from.songEndMs
        live = from.live
        taken = from.taken
    }
}

// A deck's labels in order, from its last processor to its output's write.
// Both run on the deck's playback thread, so nothing here is shared across
// threads. Labels are kept in a small pool so steady playback allocates
// nothing.
class DeckTimeQueue {
    private val queue = ArrayDeque<SongLabel>()
    private val pool = ArrayDeque<SongLabel>()

    val isEmpty: Boolean get() = queue.isEmpty()

    // Frames labeled and not yet taken.
    val frames: Long get() = queue.sumOf { (it.frames - it.taken).toLong() }

    fun push(
        frames: Int,
        songMs: Double,
        msPerFrame: Double,
        entryId: String?,
        streamStart: Boolean,
        songEndMs: Double,
        live: Boolean,
    ) {
        if (frames <= 0) return
        val label = pool.removeLastOrNull() ?: SongLabel()
        label.frames = frames
        label.songMs = songMs
        label.msPerFrame = msPerFrame
        label.entryId = entryId
        label.streamStart = streamStart
        label.songEndMs = songEndMs
        label.live = live
        label.taken = 0
        queue.addLast(label)
    }

    fun peek(): SongLabel? = queue.firstOrNull()

    // Marks `count` more frames of the first label taken, dropping it once
    // all are.
    fun take(count: Int) {
        val label = queue.firstOrNull() ?: return
        label.taken += count
        if (label.taken >= label.frames) pool.addLast(queue.removeFirst())
    }

    fun clear() {
        while (queue.isNotEmpty()) pool.addLast(queue.removeFirst())
    }
}

// What the processor chain knows of a stream's song time that the steps
// after the silence skipper cannot see: for each chunk the skipper handed
// on, its first frame's index and how many frames it had skipped before
// it, since the stream started. All on the deck's playback thread.
class ChainTimes {
    // Points (skipper output frame, frames skipped before it), oldest first.
    private var pointAt = LongArray(64)
    private var pointSkips = LongArray(64)
    private var head = 0
    private var count = 0

    fun flushed() {
        head = 0
        count = 0
    }

    fun chunk(outputFrame: Long, skippedBefore: Long) {
        if (count > 0) {
            val last = (head + count - 1) % pointAt.size
            if (pointSkips[last] == skippedBefore) return
        }
        if (count == pointAt.size) grow()
        val at = (head + count) % pointAt.size
        pointAt[at] = outputFrame
        pointSkips[at] = skippedBefore
        count++
    }

    // Frames skipped before skipper output frame `frame`. Points before the
    // last one at or before `frame` are dropped: frames are asked in order.
    fun skipsBefore(frame: Long): Long {
        var skips = 0L
        while (count > 0) {
            val first = pointAt[head]
            if (first > frame) break
            skips = pointSkips[head]
            if (count > 1 && pointAt[(head + 1) % pointAt.size] <= frame) {
                head = (head + 1) % pointAt.size
                count--
                continue
            }
            break
        }
        return skips
    }

    // The next point after `frame`, or Long.MAX_VALUE: a label is split there.
    fun nextPointAfter(frame: Long): Long {
        for (i in 0 until count) {
            val at = pointAt[(head + i) % pointAt.size]
            if (at > frame) return at
        }
        return Long.MAX_VALUE
    }

    private fun grow() {
        val at = LongArray(pointAt.size * 2)
        val skips = LongArray(pointAt.size * 2)
        for (i in 0 until count) {
            at[i] = pointAt[(head + i) % pointAt.size]
            skips[i] = pointSkips[(head + i) % pointAt.size]
        }
        pointAt = at
        pointSkips = skips
        head = 0
    }
}
