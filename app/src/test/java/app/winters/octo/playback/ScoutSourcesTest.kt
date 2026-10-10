package app.winters.octo.playback

import android.net.TestUri
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.inspector.MediaExtractorCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer

// The scout reads songs with MediaExtractorCompat, which opens a song again
// at another place by the address its source names. These run the real
// reader over the same chain Streams builds, and check every read goes
// through the pinned request, the saved-copy layer and a fresh signature.
@OptIn(UnstableApi::class)
class ScoutSourcesTest {
    companion object {
        // The readers check Build.FINGERPRINT to see whether they run in a
        // test, and the computer's stand-in leaves it null. It is a
        // constant, so it is written the low-level way.
        @JvmStatic
        @BeforeClass
        fun nameTheDevice() {
            val field = android.os.Build::class.java.getField("FINGERPRINT")
            if (field.get(null) != null) return
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = unsafeField.get(null)
            val type = unsafe.javaClass
            val base = type.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
            val offset = type.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
            type.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java).invoke(unsafe, base, offset, "robolectric")
        }
    }

    private val song = "octo-stream://song/abc"

    // One minute of silent 128 kbps MP3 at 44.1 kHz: 417-byte frames of
    // 1152 samples each.
    private val mp3: ByteArray = run {
        val frames = (60L * 44_100 / 1_152).toInt()
        val frame = ByteArray(417).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xFB.toByte()
            it[2] = 0x90.toByte()
            it[3] = 0x00
        }
        ByteArray(frames * frame.size) { frame[it % frame.size] }
    }

    // The server: answers ranged requests unless told not to, and counts
    // every byte it sends, including those a reader would have to skip.
    private class Server(val bytes: ByteArray, val ranges: Boolean) : DataSource.Factory {
        val opens = mutableListOf<DataSpec>()
        var sent = 0L

        override fun createDataSource(): DataSource = object : DataSource {
            private var spec: DataSpec? = null
            private var at = 0L
            private var left = 0L

            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                opens += dataSpec
                spec = dataSpec
                // A server that ignores the range sends the song from its start.
                if (!ranges) sent += dataSpec.position
                at = dataSpec.position
                val rest = bytes.size - at
                left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) rest else minOf(rest, dataSpec.length)
                return left
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (left == 0L) return C.RESULT_END_OF_INPUT
                val n = minOf(length.toLong(), left).toInt()
                System.arraycopy(bytes, at.toInt(), buffer, offset, n)
                at += n
                left -= n
                sent += n
                return n
            }

            override fun getUri(): Uri? = spec?.uri

            override fun getResponseHeaders(): Map<String, List<String>> {
                val s = spec ?: return emptyMap()
                return if (ranges) {
                    val end = if (s.length == C.LENGTH_UNSET.toLong()) bytes.size - 1L else s.position + s.length - 1
                    mapOf("Content-Range" to listOf("bytes ${s.position}-$end/${bytes.size}"))
                } else {
                    mapOf("Accept-Ranges" to listOf("none"))
                }
            }

            override fun close() {
                spec = null
            }
        }
    }

    // Stands in for the saved copies: notes every song it is asked for and
    // reads it from the server.
    private class SavedCopies(val upstream: DataSource.Factory) : DataSource.Factory {
        val opens = mutableListOf<DataSpec>()

        override fun createDataSource(): DataSource {
            val inner = upstream.createDataSource()
            return object : DataSource by inner {
                override fun open(dataSpec: DataSpec): Long {
                    opens += dataSpec
                    return inner.open(dataSpec)
                }
            }
        }
    }

    // The MP3 reader alone, seeking by the steady bitrate as the decks do;
    // the other readers need parts of the phone the computer lacks.
    private val mp3Only = ExtractorsFactory { arrayOf(Mp3Extractor(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)) }

    private var signatures = 0
    private var saved: SavedCopies? = null

    // The chain the decks and the scout use, with the server under it.
    private fun chain(network: DataSource.Factory): DataSource.Factory = streamSources(
        network = network,
        isStream = { it.scheme == STREAM_SCHEME },
        pin = { TestUri("$it?format=raw") },
        sign = { TestUri("https://music.example/rest/stream?id=abc&t=signature${++signatures}") },
        cached = { upstream -> SavedCopies(upstream).also { saved = it } },
        keeping = { true },
    )

    // What the scout does: open the song and jump to its last 20 seconds.
    private fun readTail(sources: DataSource.Factory): Long {
        val extractor = MediaExtractorCompat(mp3Only, sources)
        try {
            extractor.setDataSource(TestUri(song), 0)
            extractor.selectTrack(0)
            extractor.seekTo(40_000_000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(4_096)
            assertTrue(extractor.readSampleData(buffer, 0) > 0)
            return extractor.sampleTime
        } finally {
            extractor.release()
        }
    }

    @Test
    fun everyReadOfTheScoutGoesThroughSavedCopiesAndASignature() {
        val server = Server(mp3, ranges = true)
        val time = readTail(StableUriDataSource.Factory(chain(RangeGuardDataSource.Factory(server))))
        assertEquals(40_000_000.0, time.toDouble(), 30_000.0)

        val copies = saved!!.opens
        // The song was opened at its start and again near its end, both
        // times by its own address with the pinned request, never a signed one.
        assertTrue("${copies.size} opens", copies.size >= 2)
        assertTrue(copies.all { it.uri.toString() == "$song?format=raw" })
        val tail = copies.last()
        assertTrue("${tail.position}", tail.position > mp3.size / 2)
        // The server only ever saw signed addresses: a first-byte check, then the read.
        assertTrue(server.opens.all { it.uri.scheme == "https" && it.uri.query!!.contains("t=signature") })
        val reads = server.opens.filter { it.length != 1L }
        assertEquals(copies.size, reads.size)
        assertEquals(tail.position, reads.last().position)
        // Each open was signed afresh, its first-byte check with it.
        assertEquals(reads.size, signatures)
        assertEquals(reads.size, reads.map { it.uri.query }.distinct().size)
        assertEquals(server.opens.size, 2 * reads.size)
        // Far less than the whole song came over.
        assertTrue("${server.sent} of ${mp3.size}", server.sent < mp3.size / 2)
    }

    @Test
    fun withoutTheStableAddressTheReaderWouldGoAroundTheSavedCopies() {
        val server = Server(mp3, ranges = true)
        readTail(chain(server))
        // The jump to the end was opened by the signed address, straight
        // from the server: the saved copies saw only the first open.
        assertEquals(1, saved!!.opens.size)
        assertTrue(server.opens.last().position > 0)
    }

    @Test
    fun aServerThatOnlySendsWholeSongsIsNeverRead() {
        val server = Server(mp3, ranges = false)
        try {
            readTail(StableUriDataSource.Factory(chain(RangeGuardDataSource.Factory(server))))
            fail("read a song that can only be sent whole")
        } catch (e: NotSeekable) {
            // expected
        }
        // Only the first-byte check went out, and nothing was read.
        assertEquals(1, server.opens.size)
        assertEquals(1L, server.opens.single().length)
        assertEquals(0L, server.sent)
    }

    @Test
    fun aPhoneFileIsReadWithoutAsking() {
        val files = Server(mp3, ranges = false)
        val extractor = MediaExtractorCompat(mp3Only, StableUriDataSource.Factory(chain(RangeGuardDataSource.Factory(files))))
        try {
            extractor.setDataSource(TestUri("content://media/audio/1"), 0)
            assertEquals(1, extractor.trackCount)
        } finally {
            extractor.release()
        }
        assertTrue(files.opens.none { it.length == 1L })
    }

    @Test
    fun aRangedAnswerIsToldApartFromAWholeOne() {
        assertTrue(answersRanges(mapOf("content-range" to listOf("bytes 0-0/1000"))))
        assertTrue(!answersRanges(mapOf("Accept-Ranges" to listOf("none"))))
        assertTrue(!answersRanges(emptyMap()))
    }
}
