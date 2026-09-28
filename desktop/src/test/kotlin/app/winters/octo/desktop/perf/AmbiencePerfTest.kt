package app.winters.octo.desktop.perf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.player.wash.WashCover
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.ui.CoverGlow
import app.winters.octo.desktop.ui.ImmersiveBackdrop
import app.winters.octo.design.FrameSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.chromeFilm
import app.winters.octo.player.immersive.BaseBpm
import app.winters.octo.player.immersive.WashTuning
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.graphicapi.DirectXOffscreenContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime

// Times whole window frames with each ambience behind the frame's glass,
// off screen, at 1440 x 900 and 2560 x 1440, and writes what it found to
// build/perf/ambience.txt. On Windows the frames are drawn with Direct3D,
// the way the window draws them, and each is timed until the graphics card
// has finished it; everywhere they are also drawn on the processor, which
// is what the window falls back to without a usable graphics card. It only
// runs when asked: OCTO_PERF=1 ./gradlew :desktop:test --tests '*AmbiencePerfTest*'.
class AmbiencePerfTest {
    private val lines = mutableListOf<String>()

    private fun say(line: String) {
        println(line)
        lines += line
    }

    // What lies behind the frame.
    private enum class Look(val label: String) {
        Glow("Glow (before)"),
        Moving("Immersive, moving"),
        Still("Immersive, still"),
    }

    @Test
    fun measureTheAmbience() {
        assumeTrue(System.getenv("OCTO_PERF") == "1")
        val runtime = ManagementFactory.getRuntimeMXBean()
        say("Octo ambience performance, ${LocalDateTime.now().withNano(0)}")
        say("JVM ${runtime.vmVendor} ${runtime.vmName} ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores, max heap ${Runtime.getRuntime().maxMemory() / (1 shl 20)} MB")
        say("JVM options: ${runtime.inputArguments.filter { it.startsWith("-X") }.joinToString(" ")}")
        say("A frame is the whole window: the ambience, the frame's glass blurring it (title bar, sidebar, side panel, player), and a page of words.")
        say("The screen offers 60 frames a second in real time; the immersive wash is held to 30. Times are ms to draw one whole frame, after $WARMUP warm-up frames ($CPU_WARMUP on the processor).")
        say("\"Drawn a second\" counts the frames the window would really draw (it draws only when something changed).")
        val source = madeUpCover()
        val started = System.nanoTime()
        val cover = WashCovers.prepared("perf", source, WashTuning())
        say("Preparing a cover for the wash (once a song): %.1f ms".format((System.nanoTime() - started) / 1e6))
        val picture = source.toComposeImageBitmap()
        val gpu = runCatching { DirectXOffscreenContext() }.getOrNull()
        for ((width, height) in SIZES) {
            say("")
            say("$width x $height")
            if (gpu != null) {
                for (look in Look.entries) measure(look, width, height, cover, picture, gpu, GPU_FRAMES)
            } else {
                say("  Direct3D off screen is not available here.")
            }
            for (look in Look.entries) measure(look, width, height, cover, picture, null, CPU_FRAMES)
        }
        gpu?.close()
        val out = File("build/perf").apply { mkdirs() }
        File(out, "ambience.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun measure(look: Look, width: Int, height: Int, cover: WashCover, picture: ImageBitmap, gpu: DirectXOffscreenContext?, frames: Int) {
        val scene = ImageComposeScene(width, height, Density(1f)) { Probe(look, cover, picture) }
        val target = gpu?.let { GpuTarget(it, width, height) }
        target?.let { swapSurface(scene, it.surface) }
        val times = DoubleArray(frames)
        var drawn = 0
        // Frames are offered on a 60 Hz beat in real time, as a screen
        // offers them, since the wash rests on a real timer between frames.
        val warmup = if (gpu != null) WARMUP else CPU_WARMUP
        val start = System.nanoTime()
        var measuredFrom = start
        var t = 0L
        for (i in -warmup until frames) {
            val slot = start + (i + warmup) * FRAME_NANOS
            waitUntil(slot)
            if (i == 0) measuredFrom = System.nanoTime()
            t = System.nanoTime() - start
            val due = scene.hasInvalidations()
            val begin = System.nanoTime()
            scene.render(t).close()
            target?.finish()
            val ms = (System.nanoTime() - begin) / 1e6
            if (i >= 0) {
                times[i] = ms
                if (due) drawn++
            }
        }
        val seconds = (System.nanoTime() - measuredFrom) / 1e9
        // A look at the last frame, to be sure the ambience was there.
        if (target == null && look != Look.Still) {
            File("build/perf").mkdirs()
            File("build/perf/ambience-${look.name.lowercase()}-$width.png").writeBytes(scene.render(t + FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        scene.close()
        target?.close()
        times.sort()
        say(
            "  %-5s %-20s mean %6.2f  median %6.2f  p95 %6.2f  drawn a second %4.1f".format(
                if (gpu != null) "GPU" else "CPU",
                look.label,
                times.average(),
                times[times.size / 2],
                times[(times.size * 95) / 100],
                drawn / seconds,
            ),
        )
    }

    // The window's layout in miniature: the ambience as the haze source,
    // and over it the frame's glass and a page of words.
    @Composable
    private fun Probe(look: Look, cover: WashCover, picture: ImageBitmap) {
        val backdrop = rememberHazeState()
        Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
            Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                when (look) {
                    Look.Glow -> CoverGlow(0.5f) { blurred ->
                        Box(blurred.background(OctoColors.BackgroundTertiary)) {
                            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Look.Moving -> ImmersiveBackdrop(cover, 0.5f, BaseBpm, 30, 0.125f, moving = true)
                    Look.Still -> ImmersiveBackdrop(cover, 0.5f, BaseBpm, 30, 0.125f, moving = false)
                }
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(FrameSize.TitleBar).chromeFilm(backdrop))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.width(FrameSize.Sidebar).fillMaxHeight().chromeFilm(backdrop))
                    Column(Modifier.weight(1f).padding(Space.Page)) {
                        repeat(40) { Txt("A song on the page, number $it, by an artist", OctoType.bodySmall, if (it % 2 == 0) OctoColors.TextPrimary else OctoColors.TextSecondary) }
                    }
                    Box(Modifier.width(FrameSize.Panel).fillMaxHeight().chromeFilm(backdrop))
                }
            }
        }
    }

    // Waits for a moment to the tenth of a millisecond: short sleeps (the
    // system keeps those to the millisecond), then a spin.
    private fun waitUntil(moment: Long) {
        while (moment - System.nanoTime() > 2_000_000) Thread.sleep(minOf((moment - System.nanoTime()) / 1_000_000 - 1, 9))
        while (System.nanoTime() < moment) Thread.onSpinWait()
    }

    // A Direct3D texture off screen and a Skia surface over it.
    private class GpuTarget(private val context: DirectXOffscreenContext, width: Int, height: Int) : AutoCloseable {
        private val texture = context.Texture(width, height)
        val surface: Surface = Surface.makeFromBackendRenderTarget(
            context.directContext,
            texture.backendRenderTarget,
            SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.BGRA_8888,
            ColorSpace.sRGB,
        )!!

        // Waits until the graphics card has drawn everything sent so far.
        fun finish() {
            context.directContext.flushAndSubmit(surface, true)
            texture.waitForCompletion()
        }

        override fun close() {
            surface.close()
            texture.close()
        }
    }

    // An ImageComposeScene draws into a surface of its own on the
    // processor; for timing it is handed the graphics card's instead.
    private fun swapSurface(scene: ImageComposeScene, surface: Surface) {
        val field = ImageComposeScene::class.java.getDeclaredField("surface").apply { isAccessible = true }
        (field.get(scene) as Surface).close()
        field.set(scene, surface)
    }

    // A cover of soft coloured shapes.
    private fun madeUpCover(): Image {
        val surface = Surface.makeRasterN32Premul(600, 600)
        val canvas = surface.canvas
        canvas.clear(0xFF1B2A4A.toInt())
        val paint = Paint()
        paint.color = 0xFFE0703A.toInt()
        canvas.drawCircle(160f, 200f, 180f, paint)
        paint.color = 0xFF3AA6A0.toInt()
        canvas.drawCircle(440f, 300f, 200f, paint)
        paint.color = 0xFFF2D06B.toInt()
        canvas.drawRect(Rect.makeXYWH(80f, 420f, 300f, 120f), paint)
        return surface.makeImageSnapshot()
    }

    private companion object {
        val SIZES = listOf(1440 to 900, 2560 to 1440)
        const val WARMUP = 120
        const val GPU_FRAMES = 600
        const val CPU_FRAMES = 60
        const val CPU_WARMUP = 20
        const val FRAME_NANOS = 16_666_667L
    }
}
