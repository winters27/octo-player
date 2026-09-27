package app.winters.octo.output

import androidx.annotation.OptIn
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

// The queue as Media3 reads it while music plays on another device, one
// window per song. Unlike Media3's own list it follows a shuffled play
// order, so the lock screen, the app and "next" agree with the phone.
@OptIn(UnstableApi::class)
internal class QueueTimeline(
    private val items: List<MediaItem>,
    private val uids: List<Any>,
    private val durationsMs: List<Long?>,
    private val order: List<Int>,
) : Timeline() {
    // Where each queue position sits in the play order.
    private val rank = IntArray(order.size).also { ranks -> order.forEachIndexed { r, position -> ranks[position] = r } }

    override fun getWindowCount(): Int = items.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
        window.set(
            uids[windowIndex],
            items[windowIndex],
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            true,
            false,
            null,
            0,
            durationUs(windowIndex),
            windowIndex,
            windowIndex,
            0,
        )

    override fun getPeriodCount(): Int = items.size

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        period.set(
            if (setIds) uids[periodIndex] else null,
            if (setIds) uids[periodIndex] else null,
            periodIndex,
            durationUs(periodIndex),
            0,
            AdPlaybackState.NONE,
            false,
        )

    override fun getIndexOfPeriod(uid: Any): Int = uids.indexOf(uid)

    override fun getUidOfPeriod(periodIndex: Int): Any = uids[periodIndex]

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int = when {
        items.isEmpty() -> C.INDEX_UNSET
        shuffleModeEnabled -> order.first()
        else -> 0
    }

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int = when {
        items.isEmpty() -> C.INDEX_UNSET
        shuffleModeEnabled -> order.last()
        else -> items.lastIndex
    }

    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
        val next = if (shuffleModeEnabled) order.getOrNull(rank[windowIndex] + 1) else (windowIndex + 1).takeIf { it < items.size }
        return next ?: if (repeatMode == Player.REPEAT_MODE_ALL) getFirstWindowIndex(shuffleModeEnabled) else C.INDEX_UNSET
    }

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
        val previous = if (shuffleModeEnabled) order.getOrNull(rank[windowIndex] - 1) else (windowIndex - 1).takeIf { it >= 0 }
        return previous ?: if (repeatMode == Player.REPEAT_MODE_ALL) getLastWindowIndex(shuffleModeEnabled) else C.INDEX_UNSET
    }

    private fun durationUs(index: Int): Long = durationsMs[index]?.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET
}
