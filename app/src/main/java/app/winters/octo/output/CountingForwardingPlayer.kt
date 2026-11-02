package app.winters.octo.output

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicLong

// A player that counts each time its whole queue is replaced: set anew,
// emptied, or swapped whole. Adding, moving or taking out some songs does
// not count. Whatever replaces the queue through this player is counted:
// an app's or a car's request, a widget, a change of server.
@OptIn(UnstableApi::class)
open class CountingForwardingPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    private val count = AtomicLong()

    val replacements: Long get() = count.get()

    // For a change that ends what plays without replacing the queue, like Close.
    fun markReplaced() {
        count.incrementAndGet()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        markReplaced()
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (fromIndex == 0 && toIndex >= mediaItemCount) markReplaced()
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (fromIndex == 0 && toIndex >= mediaItemCount) markReplaced()
        return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    }
}
