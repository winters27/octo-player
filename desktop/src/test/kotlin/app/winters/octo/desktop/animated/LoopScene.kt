package app.winters.octo.desktop.animated

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import app.winters.octo.animated.LOOP_FADE_MS
import app.winters.octo.animated.LoopPlay
import app.winters.octo.audio.LoopVideo
import app.winters.octo.audio.NoHandle
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertTrue

// All scene work, including creation and disposal, runs on the same UI
// dispatcher as the frame reader's delivery. Native work still runs on IO.
internal class LoopScene(file: File, val probe: LoopProbe = LoopProbe()) : AutoCloseable {
    private lateinit var scene: ImageComposeScene
    private var effects: Job? = null
    private var nanos = 0L
    private val play = mutableStateOf(LoopPlay.Play)
    private val errors = CopyOnWriteArrayList<Throwable>()
    var pixels = 0 to 0
        private set

    init {
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(90, 90, Density(1f), coroutineContext = Dispatchers.Main +
                CoroutineExceptionHandler { _, error -> errors += error }) {
                // Compose owns this job, not the Job passed to its constructor.
                // Join the effects' actual parent after close cancels it.
                LaunchedEffect(Unit) { effects = checkNotNull(currentCoroutineContext().job.parent) }
                CompositionLocalProvider(LocalLoopVideoOpener provides probe::open) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        LoopFrames(file, play.value, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    fun frame(): Pair<Int, Int> {
        SwingUtilities.invokeAndWait {
            nanos += 16_000_000L
            scene.render(nanos).use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    pixels = bitmap.getColor(45, 10) to bitmap.getColor(45, 80)
                }
            }
        }
        errors.firstOrNull()?.let { throw AssertionError(diagnostics(), it) }
        return pixels
    }

    fun play(value: LoopPlay) = SwingUtilities.invokeAndWait { play.value = value }

    fun heldFrames(count: Int, check: (Pair<Int, Int>) -> Unit) = runBlocking {
        // Observe across real decoder intervals too: the fixture is 24 fps.
        repeat(count) { check(frame()); delay(16) }
    }

    // A bounded readiness wait, not a sleep chosen to make the decoder ready.
    // Keep the old five-second wall-clock budget. Rendering has its own clock.
    fun await(label: String, ready: () -> Boolean) = runBlocking {
        val started = System.nanoTime()
        do {
            frame()
            if (ready()) return@runBlocking
            delay(16) // one render tick; IO and the EDT can deliver the next frame
        } while (System.nanoTime() - started < 5_000_000_000L)
        dumpThreads()
        throw AssertionError("Timed out waiting for $label; ${diagnostics()}")
    }

    fun awaitPicture() {
        // An actual visible frame establishes that delivery and the fade have
        // started. Advancing an animation before native readiness proves neither.
        await("first visible video frame") { probe.copies.get() > 0 && pixels.first != 0xFF000000.toInt() }
        repeat(LOOP_FADE_MS / 16 + 2) { frame() }
        assertColors(pixels, diagnostics())
    }

    fun diagnostics(): String = "pixels=${pixels.first.toUInt().toString(16)}/${pixels.second.toUInt().toString(16)} " +
        "sceneMs=${nanos / 1_000_000} effects=$effects errors=$errors ${probe.diagnostics()}"

    override fun close() {
        try {
            SwingUtilities.invokeAndWait { scene.close() }
            // No EDT blocking: the cancelled reader must finish its IO finally
            // and return to Main before the scene's effects job can complete.
            runBlocking { withTimeout(10_000) { effects?.join() } }
            errors.firstOrNull()?.let { throw AssertionError("Scene effect failed", it) }
            check(probe.opens.get() == probe.closes.get()) { "Unclosed decoder: ${diagnostics()}" }
        } catch (error: Throwable) {
            dumpThreads()
            throw AssertionError("Scene cleanup failed: ${diagnostics()}", error)
        } finally {
            println("LoopScene ${diagnostics()}")
        }
    }

    private fun dumpThreads() {
        println("LoopScene runtime java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} " +
            "nativeIDs=${runCatching { if (VideoThreads.supported) VideoThreads.ids() else "unsupported" }}")
        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
            println("${thread.name} ${thread.state}\n${stack.joinToString("\n")}")
        }
    }

    companion object {
        fun assertColors(seen: Pair<Int, Int>, detail: String) {
            assertTrue((seen.first shr 16 and 0xFF) > 200 && (seen.first and 0xFF) < 60, "top $detail")
            assertTrue((seen.second and 0xFF) > 200 && (seen.second shr 16 and 0xFF) < 60, "bottom $detail")
        }
    }
}

// Delegates every decoder operation to the real native object. Only the
// first copy is sampled; events are transitions, not an unbounded frame log.
internal class LoopProbe(
    private val beforeOpen: () -> Unit = {},
    private val beforeStop: () -> Unit = {},
) {
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    val copies = AtomicInteger()
    val holds = AtomicInteger()
    val plays = AtomicInteger()
    private val start = System.nanoTime()
    private val events = CopyOnWriteArrayList<String>()
    @Volatile private var newest = 0uL
    @Volatile private var failure: String? = null

    private fun note(text: String) {
        events += "${(System.nanoTime() - start) / 1_000_000}ms ${Thread.currentThread().name}: $text"
    }

    fun open(file: File, size: IntSize): LoopVideo {
        note("opening ${file.name} target=$size")
        val video = try {
            beforeOpen()
            LoopVideo.open(file.path, size.width.toUInt(), size.height.toUInt())
        } catch (error: Throwable) {
            note("open failed: ${error.stackTraceToString()}")
            throw error
        }
        opens.incrementAndGet()
        val width = video.width()
        val height = video.height()
        note("opened ${width}x$height")
        return object : LoopVideo(NoHandle) {
            override fun width() = width
            override fun height() = height
            override fun frames() = video.frames()
            override fun failure(): String? = video.failure().also { failure = it }
            override fun play() { video.play(); plays.incrementAndGet(); note("play") }
            override fun hold() { video.hold(); holds.incrementAndGet(); note("hold frames=${video.frames()}") }
            override fun waitFrame(have: ULong, timeoutMs: UInt): ULong = video.waitFrame(have, timeoutMs).also { newest = it }
            override fun copyFrame(address: ULong, rowBytes: UInt, length: ULong, have: ULong): ULong {
                val copied = video.copyFrame(address, rowBytes, length, have)
                if (copied != 0uL && copied != have && copies.incrementAndGet() == 1) {
                    val memory = Pointer(address.toLong())
                    val x = width.toLong() / 2 * 4
                    val top = memory.getInt(height.toLong() / 9 * rowBytes.toLong() + x)
                    val bottom = memory.getInt(height.toLong() * 8 / 9 * rowBytes.toLong() + x)
                    note("first copy=$copied raw=${top.toUInt().toString(16)}/${bottom.toUInt().toString(16)}")
                }
                return copied
            }
            override fun stop() { note("stopping"); beforeStop(); video.stop(); note("stopped/joined") }
            override fun destroy() { video.destroy(); closes.incrementAndGet(); note("destroyed") }
        }
    }

    fun diagnostics(): String = "opens=${opens.get()} closes=${closes.get()} copies=${copies.get()} " +
        "plays=${plays.get()} holds=${holds.get()} newest=$newest decoderFailure=$failure events=$events"
}
