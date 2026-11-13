package app.winters.octo.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.ui.common.isOutsideLibrary
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ArtworkProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val loader = ArtworkProvider.loadCover
    private val glyphs = ArtworkProvider.drawGlyph
    private val threads = ArtworkProvider.executor
    private val realPipes = ArtworkProvider.pipes
    private val deadline = ArtworkProvider.pipeDeadlineMs
    private val drawn = mutableListOf<Int>()
    private val pipes = MemoryPipes()
    private lateinit var provider: ArtworkProvider

    @Before
    fun start() {
        // The app's drawables are not here; the glyph drawn is noted instead.
        ArtworkProvider.drawGlyph = { _, id, _, _ -> synchronized(drawn) { drawn += id } }
        ArtworkProvider.executor = Executor { it.run() }
        ArtworkProvider.pipes = pipes
        provider = Robolectric.setupContentProvider(ArtworkProvider::class.java, ArtworkProvider.AUTHORITY)
        // The kinds' tiles were made as the provider came up.
        assertEquals(CarGlyph.entries.map { it.drawable }, drawn)
        drawn.clear()
    }

    @After
    fun putBack() {
        ArtworkProvider.loadCover = loader
        ArtworkProvider.drawGlyph = glyphs
        ArtworkProvider.executor = threads
        ArtworkProvider.pipes = realPipes
        ArtworkProvider.pipeDeadlineMs = deadline
    }

    private fun kept(): List<File> = File(context.cacheDir, "car-art-512").listFiles().orEmpty().toList()

    private fun glyphBytes(glyph: CarGlyph): ByteArray = File(context.cacheDir, "car-glyph-512").resolve("${glyph.address}.jpg").readBytes()

    // What the asker read: a kept file's bytes, or the pipe's picture.
    private fun read(fd: ParcelFileDescriptor): ByteArray {
        val piped = pipes.of(fd)
        if (piped == null) return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        fd.close()
        return piped.readAll()
    }

    @Test
    fun aCoverThatFailedToLoadShowsItsKindsTileIsNotKeptAndTheNextAskLoadsIt() {
        var loads = 0
        var fails = true
        ArtworkProvider.loadCover = { _, _, _ ->
            loads++
            if (fails) null else Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        }
        val uri = ArtworkProvider.carUriFor(context, "online:cover-1", outside = true)
        assertArrayEquals(glyphBytes(CarGlyph.Album), read(provider.openFile(uri, "r")))
        assertTrue(kept().isEmpty())
        fails = false
        assertTrue(read(provider.openFile(uri, "r")).isNotEmpty())
        assertEquals(2, loads)
        assertEquals(1, kept().size)
    }

    @Test
    fun anArtistWithNoPictureGetsTheArtistTileAndTheMark() {
        ArtworkProvider.loadCover = { _, _, _ -> error("nothing is loaded for no cover") }
        val uri = ArtworkProvider.carMarkOnlyUri(round = true)
        assertEquals(listOf("512", "x", "mr", ArtworkProvider.NO_COVER), uri.pathSegments)
        assertTrue(read(provider.openFile(uri, "r")).isNotEmpty())
        assertEquals(listOf(CarGlyph.Artist.drawable), drawn)
    }

    @Test
    fun aGlyphTileIsServedOnlyForAGlyphOfItsOwn() {
        // The size the glyph is drawn at is part of the address, so a car that
        // kept an older drawing asks for this one.
        assertEquals(listOf("512", "x", "g", "more-56"), ArtworkProvider.carGlyphUri(CarGlyph.More).pathSegments)
        assertArrayEquals(glyphBytes(CarGlyph.More), read(provider.openFile(ArtworkProvider.carGlyphUri(CarGlyph.More), "r")))
        // Made once at the start: nothing drawn for the ask.
        assertTrue(drawn.isEmpty())
        listOf("/512/x/g/sym_lock", "/512/x/g/more").forEach { path ->
            val unknown = ArtworkProvider.carGlyphUri(CarGlyph.More).buildUpon().path(path).build()
            assertThrows(FileNotFoundException::class.java) { provider.openFile(unknown, "r") }
        }
    }

    @Test
    fun aDrawnPlaylistCoverIsDrawnForEachAskAndNeverKept() {
        var draws = 0
        ArtworkProvider.loadCover = { _, _, _ -> draws++; Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
        val uri = ArtworkProvider.carUriFor(context, PlaylistArtRef(PlaylistCoverStyle.Mosaic, "pl1", "Road Trip", null, null, null, listOf("a", "b")).encode())
        assertTrue(read(provider.openFile(uri, "r")).isNotEmpty())
        assertTrue(read(provider.openFile(uri, "r")).isNotEmpty())
        assertEquals(2, draws)
        assertTrue(kept().isEmpty())
        // Always through the pipe, never a file.
        assertEquals(2, pipes.opened)
    }

    // A sudden stop can leave a kept picture empty; it is made again rather
    // than served empty forever.
    @Test
    fun aPictureLeftEmptyIsMadeAgain() {
        var loads = 0
        ArtworkProvider.loadCover = { _, _, _ -> loads++; Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
        val cover = File(context.cacheDir, "car-art-512").apply { mkdirs() }.resolve("${carSha1("s:server:c1")}.jpg").apply { writeBytes(ByteArray(0)) }
        assertTrue(read(provider.openFile(ArtworkProvider.uriFor("s:server:c1"), "r")).isNotEmpty())
        assertEquals(1, loads)
        assertTrue(cover.length() > 0)
        File(context.cacheDir, "car-glyph-512").apply { mkdirs() }.resolve("${CarGlyph.More.address}.jpg").writeBytes(ByteArray(0))
        assertTrue(read(provider.openFile(ArtworkProvider.carGlyphUri(CarGlyph.More), "r")).isNotEmpty())
        assertEquals(listOf(CarGlyph.More.drawable), drawn)
    }

    @Test
    fun aKeptCoverIsOpenedAtOnceWithNoPipe() {
        ArtworkProvider.loadCover = { _, _, _ -> Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
        val uri = ArtworkProvider.uriFor("s:server:c2")
        read(provider.openFile(uri, "r"))
        assertEquals(1, pipes.opened)
        assertTrue(read(provider.openFile(uri, "r")).isNotEmpty())
        assertEquals(1, pipes.opened)
    }

    @Test
    fun aMosaicMissingACoverIsNeverDrawnWithAHole() {
        val one = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertNull(mosaicBitmap(listOf("a", "b", "c", "d"), 64) { ref, _ -> if (ref == "c") null else one })
        assertNull(mosaicBitmap(listOf("a"), 64) { _, _ -> null })
        assertEquals(64, mosaicBitmap(listOf("a", "b", "c", "d"), 64) { _, _ -> one }?.width)
    }

    @Test
    fun anAddressMarkedTheOldWayNoLongerCarriesTheMark() {
        ArtworkProvider.loadCover = { _, _, _ -> Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
        val old = ArtworkProvider.uriFor("x").buildUpon().path("/512/x/o/online:cover-2").build()
        read(provider.openFile(old, "r"))
        assertEquals(listOf("${carSha1("online:cover-2")}.jpg"), kept().map { it.name })
    }

    @Test
    fun theMarkOnSomethingWithNoCoverSitsOnThePlainTile() {
        ArtworkProvider.loadCover = { _, _, _ -> error("nothing is loaded for no cover") }
        assertTrue(read(provider.openFile(ArtworkProvider.carMarkOnlyUri(), "r")).isNotEmpty())
    }

    @Test
    fun aReferenceThatGrowsWhenWrittenOutGoesByItsHandle() {
        val ref = "s:server:" + "東".repeat(60)
        assertTrue(ref.length < INLINE_REF_MAX)
        val uri = ArtworkProvider.carUriFor(context, ref)
        assertEquals(listOf("512", "x", "h"), uri.pathSegments.take(3))
        assertEquals(ref, ArtworkProvider.refs(context).refFor(uri.pathSegments[3]))
    }

    @Test
    fun aFindDownloadedIntoTheLibraryHasNoMark() {
        val look = CarLook(context)
        val find = track("find:9")
        val adopted = look.song(find, CarNode.Liked, isOutsideLibrary(find.id, setOf(find.id)), downloaded = true)
        assertFalse(adopted.mediaMetadata.artworkUri.toString().contains("/x/m"))
        val outside = look.song(find, CarNode.Liked, isOutsideLibrary(find.id, emptySet()), downloaded = false)
        assertTrue(outside.mediaMetadata.artworkUri.toString().contains("/x/m"))
    }

    // No ask waits on a fetch: the binder thread gets its answer while the
    // fetch is still running, and the handle's reference is looked up on
    // the provider's own threads.
    @Test
    fun anAskReturnsAtOnceWhileItsCoverIsFetchedElsewhere() {
        val pool = Executors.newFixedThreadPool(8)
        ArtworkProvider.executor = pool
        val release = CountDownLatch(1)
        var fetchedOn: Thread? = null
        ArtworkProvider.loadCover = { _, _, _ ->
            fetchedOn = Thread.currentThread()
            release.await(5, TimeUnit.SECONDS)
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        }
        val ref = "s:server:" + "東".repeat(60)
        val asked = System.nanoTime()
        val fd = provider.openFile(ArtworkProvider.carUriFor(context, ref), "r")
        val tookMs = (System.nanoTime() - asked) / 1e6
        // A fetch on this thread would have held the ask for the fetch's
        // whole 5 s; the answer came while the fetch still waits.
        assertEquals(1L, release.count)
        assertTrue("the ask took $tookMs ms", tookMs < 2_000)
        release.countDown()
        assertTrue(read(fd).isNotEmpty())
        assertTrue(fetchedOn != null && fetchedOn !== Thread.currentThread())
        pool.shutdown()
    }

    @Test
    fun aPictureGoesInOneWriteOnceItsPipeHoldsIt() {
        ArtworkProvider.loadCover = { _, _, _ -> Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888) }
        val fd = provider.openFile(ArtworkProvider.uriFor("s:server:big"), "r")
        val pipe = pipes.of(fd)!!
        assertEquals(1, pipe.writes)
        assertTrue(pipe.capacity >= pipe.size)
        assertEquals(0, ArtworkProvider.feeder().slowWrites)
        read(fd)
    }

    // A car that reads 40 tiles in reverse order and one that never reads
    // its tile: with the pipes kept small (raising them fails), the one
    // writer thread fills every pipe as it has room, so the others all load
    // and only the unread one fails, at its own deadline.
    @Test
    fun aReaderOutOfOrderAndOneThatNeverReadsHoldUpNoOtherTile() {
        val pool = Executors.newFixedThreadPool(8)
        ArtworkProvider.executor = pool
        ArtworkProvider.pipeDeadlineMs = 2_500
        pipes.growFails = true
        pipes.capacity = 512
        ArtworkProvider.loadCover = { _, ref, _ -> Bitmap.createBitmap(64 + ref.hashCode().and(7), 64, Bitmap.Config.ARGB_8888) }
        val never = provider.openFile(ArtworkProvider.uriFor("s:server:never"), "r")
        val fds = (0 until 40).map { provider.openFile(ArtworkProvider.uriFor("s:server:tile-$it"), "r") }
        val pictures = fds.reversed().map { read(it) }
        assertTrue(pictures.all { it.isNotEmpty() })
        assertTrue(ArtworkProvider.feeder().slowWrites >= 41)
        val unread = pipes.of(never)!!
        assertTrue("the unread tile fails at its deadline", unread.awaitFailed(6_000))
        never.close()
        pool.shutdown()
    }

    // In-memory pipes with a fixed capacity: a write takes what fits.
    class MemoryPipes : CoverPipes {
        var capacity = 65_536
        var growFails = false
        @Volatile var opened = 0
        private val byReader = HashMap<ParcelFileDescriptor, MemoryPipe>()

        inner class MemoryPipe : CoverPipe {
            private val dummy = File.createTempFile("pipe", ".jpg")
            override val reader: ParcelFileDescriptor = ParcelFileDescriptor.open(dummy, ParcelFileDescriptor.MODE_READ_ONLY)
            private val lock = Object()
            private val held = java.io.ByteArrayOutputStream()
            private val got = java.io.ByteArrayOutputStream()
            @Volatile var capacity = this@MemoryPipes.capacity
            @Volatile var writes = 0
            @Volatile var size = 0
            @Volatile var done = false
            @Volatile var failed = false

            override fun grow(bytes: Int): Int {
                size = bytes
                if (growFails) return -1
                capacity = maxOf(capacity, bytes)
                return capacity
            }

            override fun nonBlocking() {}

            override fun write(bytes: ByteArray, from: Int): Int = synchronized(lock) {
                if (failed) throw IOException("closed")
                size = bytes.size
                val n = minOf(bytes.size - from, capacity - held.size())
                if (n > 0) {
                    held.write(bytes, from, n)
                    writes++
                    lock.notifyAll()
                }
                n
            }

            override fun finish() = synchronized(lock) {
                done = true
                lock.notifyAll()
            }

            override fun fail(message: String) = synchronized(lock) {
                failed = true
                lock.notifyAll()
            }

            fun room(): Boolean = synchronized(lock) { held.size() < capacity }

            // Reads until the picture ends, as the asking app would.
            fun readAll(): ByteArray {
                val end = System.nanoTime() + 5_000_000_000L
                synchronized(lock) {
                    while (true) {
                        val chunk = held.toByteArray()
                        held.reset()
                        got.write(chunk)
                        lock.notifyAll()
                        if (done || failed) break
                        if (System.nanoTime() > end) throw AssertionError("the picture never ended")
                        lock.wait(10)
                    }
                    if (failed) throw IOException("the picture failed")
                    return got.toByteArray()
                }
            }

            fun awaitFailed(ms: Long): Boolean {
                val end = System.nanoTime() + ms * 1_000_000
                synchronized(lock) {
                    while (!failed && System.nanoTime() < end) lock.wait(10)
                    return failed
                }
            }
        }

        override fun open(): CoverPipe = MemoryPipe().also {
            synchronized(byReader) { byReader[it.reader] = it }
            opened++
        }

        override fun room(pipes: List<CoverPipe>, timeoutMs: Int): BooleanArray {
            val end = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                val ready = BooleanArray(pipes.size) { (pipes[it] as MemoryPipe).room() }
                if (ready.any { it } || System.nanoTime() >= end) return ready
                Thread.sleep(1)
            }
        }

        fun of(fd: ParcelFileDescriptor): MemoryPipe? = synchronized(byReader) { byReader[fd] }
    }
}
