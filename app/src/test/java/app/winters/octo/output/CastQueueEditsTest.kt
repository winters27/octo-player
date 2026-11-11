package app.winters.octo.output

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.playback.OctoPlayer
import app.winters.octo.playback.QueueEditor
import app.winters.octo.playback.QueueSource
import app.winters.octo.playback.endOfOwnSongs
import app.winters.octo.playback.entryId
import app.winters.octo.playback.markedAutoplay
import app.winters.octo.playback.withSource
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Edits to the queue while music plays on another device, through the
// media session as the app makes them. The app's own controller holds
// the session's last timeline and compares it with each new one, so a
// timeline that changed after it was handed out would throw here.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class CastQueueEditsTest {
    private class QuietDevice : RemoteOutput {
        override val device = OutputDevice("tv", "TV", OutputFamily.Cast, DeviceShape.Tv)
        override fun accepts(mimeType: String) = true
        override val takesNext = true
        override fun load(media: RemoteMedia, startMs: Long, play: Boolean, next: RemoteMedia?) = Unit
        override fun setNext(next: RemoteMedia?) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seek(positionMs: Long) = Unit
        override fun stop() = Unit
        override fun setVolume(level: Float) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun listen(listener: RemoteOutput.Listener?) = Unit
        override fun close(stopPlaying: Boolean) = Unit
    }

    private class AnyMedia : MediaForDevice {
        override fun canPlay(item: MediaItem, output: RemoteOutput) = true
        override suspend fun make(item: MediaItem, output: RemoteOutput) = RemoteMedia("http://device/${item.mediaId}", "audio/mpeg", item.mediaId)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = MainScope()
    private lateinit var switch: OutputSwitch
    private lateinit var remote: RemotePlayer
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController

    private fun song(id: String) = MediaItem.Builder()
        .setMediaId(id)
        .setUri("http://server/$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setDurationMs(200_000).build())
        .build()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ids(player: Player) = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    // Music on a device with these songs queued, `index` on, and the app's
    // controller connected to the session.
    private fun cast(items: List<MediaItem>, index: Int = 0, shuffle: Boolean = false) {
        val local = OctoPlayer(context, ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build())
        switch = OutputSwitch(local) { null }
        remote = RemotePlayer(QuietDevice(), AnyMedia(), scope) {}
        val order = if (shuffle) listOf(2, 0, 1) + items.indices.drop(3) else items.indices.toList()
        remote.start(QueueState(items, index, 0, playing = true, shuffle = shuffle, order = order))
        switch.useRemote(remote)
        session = MediaSession.Builder(context, switch)
            .setId("cast-queue-edits")
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems.map { song(it.mediaId) }.toMutableList())
            })
            .build()
        val connecting = MediaController.Builder(context, session.token).buildAsync()
        idle()
        controller = connecting.get()
        idle()
    }

    private fun abc() = listOf(song("a"), song("b"), song("c"))

    private fun assertQueue(expected: List<String>) {
        idle()
        idle()
        assertEquals(expected, ids(remote))
        assertEquals(expected, ids(controller))
    }

    @After
    fun release() {
        if (::controller.isInitialized) controller.release()
        if (::session.isInitialized) session.release()
        if (::switch.isInitialized) switch.release()
        scope.cancel()
        idle()
    }

    @Test
    fun addToQueueAtTheEnd() {
        cast(abc())
        controller.addMediaItems(listOf(MediaItem.Builder().setMediaId("d").build()))
        assertQueue(listOf("a", "b", "c", "d"))
    }

    @Test
    fun addToQueueAtTheEndShuffled() {
        cast(abc(), shuffle = true)
        controller.addMediaItems(listOf(MediaItem.Builder().setMediaId("d").build()))
        assertQueue(listOf("a", "b", "c", "d"))
    }

    @Test
    fun addToQueueBeforeAutoplay() {
        cast(abc() + song("p").markedAutoplay())
        controller.addMediaItems(listOf(MediaItem.Builder().setMediaId("d").build()))
        assertQueue(listOf("a", "b", "c", "d", "p"))
    }

    @Test
    fun addToQueueBeforeAutoplayShuffled() {
        cast(abc() + song("p").markedAutoplay(), shuffle = true)
        controller.addMediaItems(listOf(MediaItem.Builder().setMediaId("d").build()))
        assertQueue(listOf("a", "b", "c", "d", "p"))
    }

    @Test
    fun addToQueueLooksBeforeAutoplaySongsStillToCome() {
        cast(abc() + song("x").markedAutoplay() + song("y").markedAutoplay())
        assertEquals(3, endOfOwnSongs(controller))
    }

    @Test
    fun addToQueueLooksBeforeAutoplaySongsStillToComeShuffled() {
        cast(abc() + song("x").markedAutoplay() + song("y").markedAutoplay(), shuffle = true)
        assertEquals(3, endOfOwnSongs(controller))
    }

    @Test
    fun addToQueueLooksAtTheEndWithNoAutoplaySongs() {
        cast(abc())
        assertEquals(3, endOfOwnSongs(controller))
    }

    @Test
    fun playNext() {
        cast(abc())
        switch.addNext(listOf(song("d")))
        assertQueue(listOf("a", "d", "b", "c"))
    }

    @Test
    fun playNextShuffled() {
        cast(abc(), shuffle = true)
        switch.addNext(listOf(song("d")))
        assertQueue(listOf("a", "d", "b", "c"))
    }

    @Test
    fun playNextOnTheLastSong() {
        cast(abc(), index = 2)
        switch.addNext(listOf(song("d")))
        assertQueue(listOf("a", "b", "c", "d"))
    }

    @Test
    fun autoplayRefillAtTheEnd() {
        cast(abc(), index = 2)
        switch.addMediaItems(switch.mediaItemCount, listOf(song("p").markedAutoplay()))
        assertQueue(listOf("a", "b", "c", "p"))
    }

    @Test
    fun stationRefillAtTheEnd() {
        cast(abc(), index = 2)
        // As the station's refill does: its songs at the end, then on from the first of them.
        val first = switch.mediaItemCount
        switch.addMediaItems(first, listOf(song("s").withSource(QueueSource.Station("st", "St"))))
        switch.seekTo(first, 0)
        switch.play()
        idle()
        assertQueue(listOf("a", "b", "c", "s"))
    }

    @Test
    fun queueEditorPlayNextOnTheLastSong() {
        cast(abc(), index = 2)
        val key = switch.getMediaItemAt(0).entryId!!
        val editor = QueueEditor()
        editor.attach(switch)
        // "a" comes out and goes right after "c", the song that is on and the last.
        assertTrue(editor.playNext(key))
        idle()
        assertQueue(listOf("b", "c", "a"))
    }

    @Test
    fun removeTheLastSong() {
        cast(abc() + song("d"))
        controller.removeMediaItem(3)
        assertQueue(listOf("a", "b", "c"))
    }

    @Test
    fun removeAMiddleSong() {
        cast(abc() + song("d"))
        controller.removeMediaItem(1)
        assertQueue(listOf("a", "c", "d"))
    }

    @Test
    fun undoRemovingTheLastSong() {
        cast(abc() + song("d"))
        val last = remote.getMediaItemAt(3)
        switch.removeRuns(listOf(3..3))
        idle()
        switch.putBack(listOf(3 to listOf(last)), intArrayOf(0, 1, 2, 3))
        assertQueue(listOf("a", "b", "c", "d"))
    }

    @Test
    fun moveTheLastSongUp() {
        cast(abc() + song("d"))
        controller.moveMediaItem(3, 1)
        assertQueue(listOf("a", "d", "b", "c"))
    }

    @Test
    fun moveASongToTheEnd() {
        cast(abc() + song("d"))
        controller.moveMediaItem(1, 3)
        assertQueue(listOf("a", "c", "d", "b"))
    }
}
