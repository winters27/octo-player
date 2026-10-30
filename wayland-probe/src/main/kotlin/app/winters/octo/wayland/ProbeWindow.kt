@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.winters.octo.wayland

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.desktop.player.wash.WashRenderer
import app.winters.octo.player.immersive.WashMotion
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.desktop.linux.Application
import org.jetbrains.desktop.linux.Event
import org.jetbrains.desktop.linux.LogicalPixelsInt
import org.jetbrains.desktop.linux.LogicalPoint
import org.jetbrains.desktop.linux.LogicalSize
import org.jetbrains.desktop.linux.RenderingMode
import org.jetbrains.desktop.linux.WindowFrame
import org.jetbrains.desktop.linux.WindowParams
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal class ProbeWindow(
    private val app: Application,
    val id: Long,
    dispatcher: CoroutineDispatcher,
    private val mini: Boolean,
    toggleMini: () -> Unit,
) : AutoCloseable {
    val window = app.createWindow(WindowParams(
        windowId = id,
        appId = "app.winters.octo.wayland-probe",
        title = if (mini) "Octo Wayland · mini probe" else "Octo Wayland · GPU probe",
        size = LogicalSize.makeWH(if (mini) 420 else 1000, if (mini) 240 else 680),
        minSize = LogicalSize.makeWH(320, 220),
        renderingMode = RenderingMode.EGL,
        clientSideDecorationFrame = WindowFrame(
            WindowFrame.Padding.withAll(LogicalPixelsInt(0)),
            WindowFrame.ResizerThickness.withAll(LogicalPixelsInt(0)),
        ),
    ))
    var text by mutableStateOf("")
    var preedit by mutableStateOf("")
    var status by mutableStateOf("Type here · Ctrl+C / Ctrl+V · drop text or files")
    var scale = 1f
        set(value) { field = value; scene.density = Density(value) }
    var fullscreen = false
    var ime = false
    var frames = 0
        private set
    private var clicks by mutableStateOf(0)
    private var gpu: Gpu? = null
    private val recomposer = FrameRecomposer(dispatcher)
    private val scene = CanvasLayersComposeScene(frameRecomposer = recomposer)
    private val wash = WashRenderer()
    private val motion = WashMotion()
    // A deterministic, colourful cover exercises the real runtime shaders.
    private val cover = Surface.makeRasterN32Premul(512, 512).use { surface ->
        surface.canvas.clear(0xff203b74.toInt())
        Paint().use { paint ->
            paint.color = 0xfff58c55.toInt()
            surface.canvas.drawCircle(165f, 215f, 180f, paint)
            paint.color = 0xff63ddb0.toInt()
            surface.canvas.drawRect(Rect.makeXYWH(280f, 280f, 230f, 230f), paint)
        }
        surface.makeImageSnapshot()
    }
    private var previous = 0L
    private val intervals = mutableMapOf<String, MutableList<Double>>()
    private val submissions = mutableMapOf<String, MutableList<Double>>()
    private var closed = false

    init {
        wash.setCovers(cover, cover)
        scene.setContent {
            Column(
                Modifier.padding(24.dp).background(Color(0xcc10151f)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(if (mini) "OCTO / MINI PROBE" else "OCTO / WAYLAND + GPU", style = TextStyle(Color.White, 24.sp))
                BasicText("Java 27 · EGL · Compose · production WashRenderer", style = TextStyle(Color(0xffa8dac8), 14.sp))
                if (!mini) {
                    BasicText(status, style = TextStyle(Color.White, 14.sp))
                    BasicText(text.ifEmpty { "Native text input inspector" } + preedit,
                        Modifier.fillMaxWidth().background(Color(0x55111111)).padding(12.dp),
                        style = TextStyle(Color.White, 20.sp))
                    BasicText("Open / close mini window", Modifier.clickable { toggleMini() }.padding(8.dp),
                        style = TextStyle(Color(0xff9de8cb), 16.sp))
                    BasicText("Compose pointer clicks: $clicks", Modifier.clickable {
                        clicks++
                        println("probe: compose-click window=$id count=$clicks")
                    }.padding(8.dp), style = TextStyle(Color.White, 16.sp))
                    BasicText("F11 fullscreen · Ctrl+N mini · Escape close\nInput inspector is not a production Compose text-field adapter.",
                        style = TextStyle(Color(0xffb9c0cf), 13.sp))
                }
            }
        }
    }

    fun pointer(type: PointerEventType, point: LogicalPoint, down: Boolean? = null) {
        scene.sendPointerEvent(type, Offset(
            point.x.rawLogical.toFloat() * scale, point.y.rawLogical.toFloat() * scale,
        ), buttons = down?.let { PointerButtons(isPrimaryPressed = it) },
            button = if (down != null) PointerButton.Primary else null)
    }

    fun draw(event: Event.WindowDraw, now: Long, snapshot: Path?) {
        check(event.softwareDrawData == null) { "Software fallback is not a Wayland GPU pass" }
        val width = event.size.width.rawPhysical
        val height = event.size.height.rawPhysical
        if (width <= 0 || height <= 0) return
        val gpu = gpu ?: Gpu(app).also { gpu = it }
        val surface = gpu.surface(width, height)
        val dt = if (previous == 0L) 1f / 60 else ((now - previous) / 1e9).toFloat()
        val phase = "${if (fullscreen) "fullscreen" else if (mini) "mini" else "window"} ${width}x$height"
        if (frames >= 60 && previous != 0L) intervals.getOrPut(phase) { mutableListOf() }.add(dt * 1000.0)
        previous = now
        motion.step(dt.coerceAtMost(.1f) * 60, 120f, dt)
        surface.canvas.clear(0xff10151f.toInt())
        check(wash.draw(surface.canvas, width.toFloat(), height.toFloat(), motion, 1f, 1f, 1f))
        scene.size = IntSize(width, height)
        recomposer.performFrame(now)
        scene.measureAndLayout()
        scene.draw(surface.canvas.asComposeCanvas())
        surface.flushAndSubmit()
        if (frames >= 60) submissions.getOrPut(phase) { mutableListOf() }.add((System.nanoTime() - now) / 1e6)
        frames++
        if (frames == 90 && snapshot != null) {
            Bitmap().use { bitmap ->
                check(bitmap.allocN32Pixels(width, height))
                check(surface.readPixels(bitmap, 0, 0)) { "GPU readback failed" }
                Image.makeFromBitmap(bitmap).use { image ->
                    checkNotNull(image.encodeToData()).use { Files.write(snapshot, it.bytes) }
                }
            }
            println("probe: snapshot=$snapshot")
        }
    }

    override fun close() = dispose(closeNative = true)

    fun nativeClosed() = dispose(closeNative = false)

    private fun dispose(closeNative: Boolean) {
        if (closed) return
        closed = true
        println("probe: window=$id frames=$frames")
        for ((phase, times) in intervals) {
            val sorted = times.sorted()
            fun p(percent: Double) = sorted[((sorted.lastIndex) * percent).toInt()]
            println(String.format(Locale.ROOT,
                "probe: timing window=%d phase=%s samples=%d interval-p50=%.2fms interval-p95=%.2fms submit-mean=%.2fms",
                id, phase, times.size, p(.5), p(.95), submissions[phase]!!.average()))
        }
        scene.close()
        recomposer.close()
        wash.close()
        cover.close()
        gpu?.close()
        if (closeNative) window.close()
    }
}
