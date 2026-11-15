package app.winters.octo.versions

import app.winters.octo.subsonic.StreamVersionChanges
import kotlinx.serialization.Serializable

// Where a device's reading of a server's version changes stands: the
// server's `epoch` and the last `seq` it handed out. Kept per server.
@Serializable
data class StreamVersionCursor(val epoch: String, val seq: Long)

// What one getStreamVersionChanges answer asks of a device: drop every
// saved outside song (`dropAll`), and in any case the songs of `dropIds`,
// then keep `next`.
data class FeedPlan(val dropAll: Boolean, val dropIds: Set<String>, val next: StreamVersionCursor)

// The plan for an answer to the cursor `stored` (null: none kept for this
// server yet). Every saved outside song goes when no cursor was kept (the
// device cannot know what changed before), when the server's epoch is
// another (its record of changes started again), or when the answer is not
// complete.
fun planFeed(stored: StreamVersionCursor?, answer: StreamVersionChanges): FeedPlan = FeedPlan(
    dropAll = stored == null || stored.epoch != answer.epoch || !answer.complete,
    dropIds = answer.change.flatMapTo(LinkedHashSet()) { it.ids },
    next = StreamVersionCursor(answer.epoch, answer.seq),
)
