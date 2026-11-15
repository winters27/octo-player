package app.winters.octo.offline

import android.net.TestUri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

// A saved copy's writer opened before its song's copies were let go writes
// nothing more, and lets them go again when it closes, so a late write
// never survives; one of the song's new generation, or of another song,
// writes as before.
@OptIn(UnstableApi::class)
class FencedSinkTest {
    private class Sink : DataSink {
        val written = ByteArrayOutputStream()
        var closed = 0

        override fun open(dataSpec: DataSpec) = Unit

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            written.write(buffer, offset, length)
        }

        override fun close() {
            closed++
        }
    }

    private class Fence : SongFence {
        val generations = HashMap<String, Long>()
        val dropped = mutableListOf<String>()

        override fun generation(song: String): Long = generations[song] ?: 0L

        override fun dropAgain(song: String) {
            dropped += song
        }
    }

    private fun spec(key: String?): DataSpec = DataSpec.Builder().setUri(TestUri("octo-stream://song/ext-1")).setKey(key).build()

    private val key = "server:a|ext-1|audio/webm|raw"

    @Test
    fun aWriterFromBeforeTheDropWritesNothingMoreAndDropsAgainWhenItCloses() {
        val inner = Sink()
        val fence = Fence()
        val sink = FencedSink(inner, fence)
        sink.open(spec(key))
        sink.write(byteArrayOf(1, 2), 0, 2)
        fence.generations["server:a|ext-1"] = 1
        sink.write(byteArrayOf(3, 4), 0, 2)
        sink.close()
        assertArrayEquals(byteArrayOf(1, 2), inner.written.toByteArray())
        assertEquals(1, inner.closed)
        assertEquals(listOf("server:a|ext-1"), fence.dropped)
    }

    @Test
    fun aWriterOfTheNewGenerationWritesAndKeeps() {
        val inner = Sink()
        val fence = Fence().apply { generations["server:a|ext-1"] = 3 }
        val sink = FencedSink(inner, fence)
        sink.open(spec("$key|4000"))
        sink.write(byteArrayOf(5, 6, 7), 0, 3)
        sink.close()
        assertArrayEquals(byteArrayOf(5, 6, 7), inner.written.toByteArray())
        assertTrue(fence.dropped.isEmpty())
    }

    @Test
    fun anotherSongsDropAndANameOfNoSongLeaveTheWriterAlone() {
        val inner = Sink()
        val fence = Fence()
        val sink = FencedSink(inner, fence)
        sink.open(spec(key))
        fence.generations["server:a|ext-2"] = 1
        sink.write(byteArrayOf(1), 0, 1)
        sink.close()
        val plain = Sink()
        FencedSink(plain, fence).apply {
            open(spec(null))
            write(byteArrayOf(9), 0, 1)
            close()
        }
        assertArrayEquals(byteArrayOf(1), inner.written.toByteArray())
        assertArrayEquals(byteArrayOf(9), plain.written.toByteArray())
        assertTrue(fence.dropped.isEmpty())
    }
}
