package app.winters.octo.offline

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import app.winters.octo.playback.songOfCacheKey

// Each saved song's write generation, which goes up each time the song's
// saved copies are let go because the file it streams as changed.
interface SongFence {
    fun generation(song: String): Long

    // Lets the song's saved copies go again, the generation as it is.
    fun dropAgain(song: String)
}

// What letting outside songs' saved copies go needs of the store, by song
// (see songKey).
interface SongCopies {
    // Raises the song's generation: writers opened before write no more.
    fun fence(song: String)

    // Fences the song, then lets every saved copy of it go.
    fun dropSong(song: String)

    // The same for many songs, the store read once.
    fun dropSongs(songs: Collection<String>)

    // The songs of a server with a saved copy made from a named video
    // (X-Octo-Video): songs found online, by song.
    fun videoSongs(sourceId: String): List<String>
}

// A saved copy's writer that writes for one generation of its song: the
// one it opened in. Once the song's copies were let go after that, it
// writes nothing more, and when it closes it lets them go again, so what it
// had written but not yet kept never comes back. A name that is no song's
// is written as it is.
@OptIn(UnstableApi::class)
class FencedSink(private val inner: DataSink, private val fence: SongFence) : DataSink {
    private var song: String? = null
    private var opened = 0L

    override fun open(dataSpec: DataSpec) {
        val key = dataSpec.key
        song = key?.let(::songOfCacheKey)?.takeIf(String::isNotEmpty)
        opened = song?.let(fence::generation) ?: 0L
        inner.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (stale()) return
        inner.write(buffer, offset, length)
    }

    override fun close() {
        val wasStale = stale()
        try {
            inner.close()
        } finally {
            val closed = song
            song = null
            if (closed != null && (wasStale || fence.generation(closed) != opened)) fence.dropAgain(closed)
        }
    }

    private fun stale(): Boolean {
        val now = song ?: return false
        return fence.generation(now) != opened
    }
}
