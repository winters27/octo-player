@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package app.winters.octo.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.*
import org.jetbrains.desktop.linux.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Cursor
import java.nio.file.Files
import java.nio.file.Path

internal class NativeWindowInfo : WindowInfo {
    override var isWindowFocused by mutableStateOf(false)
    override var containerSize by mutableStateOf(IntSize.Zero)
    override var containerDpSize by mutableStateOf(DpSize.Zero)
    override var keyboardModifiers by mutableStateOf(PointerKeyboardModifiers())
}

internal class WaylandWindow(
    private val application: Application,
    val id: Long,
    val mini: Boolean,
    width: Int,
    height: Int,
    private val clipboard: WaylandClipboard,
    private val previewKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
    content: @Composable (WaylandWindow) -> Unit,
) : AutoCloseable {
    val window = application.createWindow(WindowParams(
        windowId = id, appId = "app.winters.octo.wayland",
        title = if (mini) "Octo mini player · Wayland" else "Octo · Wayland",
        size = LogicalSize.makeWH(width, height),
        minSize = LogicalSize.makeWH(if (mini) 320 else 720, if (mini) 120 else 480),
        renderingMode = RenderingMode.EGL,
        // Prefer the compositor's decorations; a small client header is drawn
        // on compositors (GNOME) that do not implement xdg-decoration.
        clientSideDecorationFrame = WindowFrame(
            WindowFrame.Padding.withAll(LogicalPixelsInt(0)),
            WindowFrame.ResizerThickness.withAll(LogicalPixelsInt(5)),
        ),
    ))
    val info = NativeWindowInfo()
    var clientDecorated by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var maximized by mutableStateOf(false)
        private set
    var logicalSize = width to height
        private set
    var scale = 1f
        set(value) { field = value; scene.density = Density(value); needsDraw = true; input.sync() }
    private var needsDraw = true
    private val recomposer = FrameRecomposer(WaylandDispatcher) { needsDraw = true }
    val input = WaylandTextInput(application, { scale }, recomposer::flushPendingTextInput)
    private val platform = object : PlatformContext.Empty() {
        override val windowInfo get() = info
        override val textInputService get() = input
        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing = input.start(request)
        override fun setPointerIcon(pointerIcon: PointerIcon) {
            val shape = when (pointerIcon) {
                PointerIcon.Hand -> PointerShape.Pointer
                PointerIcon.Text -> PointerShape.Text
                PointerIcon.Crosshair -> PointerShape.Crosshair
                PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)), PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)) -> PointerShape.EwResize
                PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)), PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR)) -> PointerShape.NsResize
                else -> PointerShape.Default
            }
            if (!closed && pointerInside) window.setPointerShape(shape)
        }
    }
    private val scene = CanvasLayersComposeScene(
        frameRecomposer = recomposer, platformContext = platform,
        size = IntSize(width, height),
        invalidateLayout = { needsDraw = true }, invalidateDraw = { needsDraw = true },
    )
    private var gpu: Gpu? = null
    private var closed = false
    private var pointerInside = false
    private val pressed = mutableSetOf<MouseButton>()
    private var mouseSerial: EventSerial? = null
    var frames = 0
        private set
    private var capture: Path? = null

    init {
        scene.setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides clipboard.legacy) {
                Box(Modifier.fillMaxSize()) { content(this@WaylandWindow) }
            }
        }
    }

    fun configure(event: Event.WindowConfigure) {
        fullscreen = event.fullscreen
        maximized = event.maximized
        clientDecorated = event.decorationMode is WindowDecorationMode.Client
        logicalSize = event.size.width.rawLogical to event.size.height.rawLogical
        needsDraw = true
        println("wayland: configure id=$id size=${logicalSize.first}x${logicalSize.second} fullscreen=$fullscreen maximized=$maximized")
    }

    fun focus(value: Boolean) {
        println("wayland: focus id=$id focused=$value")
        // Publish/cancel preedit before Compose reacts to window focus loss
        // and can dispose the field's input request.
        input.focused = value
        info.isWindowFocused = value
        if (!value) cancelPointer()
    }

    private fun cancelPointer() {
        if (pressed.isNotEmpty()) println("wayland: pointer-cancel id=$id buttons=${pressed.size}")
        pressed.clear()
        mouseSerial = null
        scene.cancelPointerInput()
    }

    fun beginMove() { mouseSerial?.let(window::startMove) }
    fun toggleFullscreen() { if (fullscreen) window.unsetFullScreen() else window.setFullScreen() }
    fun toggleMaximized() { if (maximized) window.unmaximize() else window.maximize() }

    fun pointer(type: PointerEventType, point: LogicalPoint, button: MouseButton? = null, serial: EventSerial? = null, scroll: Offset = Offset.Zero) {
        if (type == PointerEventType.Enter || type == PointerEventType.Exit) {
            // The compositor consumes the release during an interactive move.
            // KDT resets its grab on leave/enter; cancel Compose's gesture too.
            cancelPointer()
            pointerInside = type == PointerEventType.Enter
        }
        if (serial != null) mouseSerial = serial
        if (button != null) {
            if (type == PointerEventType.Press) pressed.add(button)
            if (type == PointerEventType.Release) pressed.remove(button)
        }
        val composeButton = when (button) {
            MouseButton.LEFT -> PointerButton.Primary
            MouseButton.RIGHT -> PointerButton.Secondary
            MouseButton.MIDDLE -> PointerButton.Tertiary
            MouseButton.SIDE, MouseButton.BACK -> PointerButton.Back
            MouseButton.EXTRA, MouseButton.FORWARD -> PointerButton.Forward
            else -> null
        }
        scene.sendPointerEvent(
            type, Offset(point.x.rawLogical.toFloat() * scale, point.y.rawLogical.toFloat() * scale),
            scrollDelta = scroll,
            buttons = PointerButtons(
                isPrimaryPressed = MouseButton.LEFT in pressed,
                isSecondaryPressed = MouseButton.RIGHT in pressed,
                isTertiaryPressed = MouseButton.MIDDLE in pressed,
                isBackPressed = MouseButton.SIDE in pressed || MouseButton.BACK in pressed,
                isForwardPressed = MouseButton.EXTRA in pressed || MouseButton.FORWARD in pressed,
            ),
            keyboardModifiers = info.keyboardModifiers, button = composeButton,
        )
    }

    fun key(code: UInt, symbol: UInt, down: Boolean, text: String?, modifiers: Set<KeyModifiers>) {
        if (closed || !info.isWindowFocused) return
        if (down) {
            // Selection commands read the last composed value, unlike edits
            // applied directly to EditProcessor. Publish the preceding key or
            // clipboard result before this key can consume that value.
            recomposer.flushPendingTextInput()
            // Publication also runs queued effects, which can retire this window.
            if (closed || !info.isWindowFocused) return
        }
        val key = composeKey(code, symbol)
        val ctrl = KeyModifiers.Control in modifiers
        val alt = KeyModifiers.Alt in modifiers
        val meta = KeyModifiers.Logo in modifiers
        val shift = KeyModifiers.Shift in modifiers
        val event = KeyEvent(key, if (down) KeyEventType.KeyDown else KeyEventType.KeyUp,
            isCtrlPressed = ctrl, isAltPressed = alt, isMetaPressed = meta, isShiftPressed = shift)
        if (down && key == Key.F11) { toggleFullscreen(); return }
        if (down && key == Key.F12) System.getProperty("octo.wayland.capture")?.let { capture(Path.of(it)); return }
        // Compose's selection manager reads LocalClipboard asynchronously on
        // the original field. Delaying the key itself would retarget a paste
        // after Tab/click changes focus while the clipboard owner is replying.
        val consumed = previewKey(event) || scene.sendKeyEvent(event)
        if (down && !consumed && !ctrl && !alt && !meta) input.type(text.orEmpty().filterNot(Char::isISOControl))
    }

    fun capture(path: Path) { capture = path; needsDraw = true }

    fun draw(event: Event.WindowDraw): Boolean {
        if (closed) return false
        check(event.softwareDrawData == null) { "Native Wayland requires an EGL GPU surface" }
        val size = IntSize(event.size.width.rawPhysical, event.size.height.rawPhysical)
        if (size.width <= 0 || size.height <= 0) return false
        if (scene.size != size) { scene.size = size; needsDraw = true }
        info.containerSize = size
        info.containerDpSize = DpSize((size.width / scale).dp, (size.height / scale).dp)
        if (!needsDraw && !recomposer.hasPendingWork() && !scene.hasPendingDraw && !scene.hasPendingMeasureOrLayout) return false
        recomposer.performFrame(System.nanoTime())
        scene.measureAndLayout()
        val gpu = gpu ?: Gpu(application, "wayland", System.getProperty("octo.wayland.testSoftware") == "true").also { gpu = it }
        val surface = gpu.surface(size.width, size.height)
        needsDraw = false
        surface.canvas.clear(0xff10151f.toInt())
        scene.draw(surface.canvas.asComposeCanvas())
        surface.flushAndSubmit()
        frames++
        capture?.let { path ->
            Bitmap().use { bitmap ->
                check(bitmap.allocN32Pixels(size.width, size.height))
                check(surface.readPixels(bitmap, 0, 0))
                Image.makeFromBitmap(bitmap).use { image ->
                    checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { Files.write(path, it.bytes) }
                }
            }
            capture = null
            println("wayland: captured=$path")
        }
        return true
    }

    override fun close() = dispose(true)
    fun nativeClosed() = dispose(false)
    private fun dispose(closeNative: Boolean) {
        if (closed) return
        closed = true
        input.close()
        // A close event can occur with another window's GL context current.
        // Abandon before disposing Compose's retained GPU resources.
        gpu?.close()
        scene.close()
        recomposer.close()
        if (closeNative) window.close()
        println("wayland: closed id=$id frames=$frames")
    }
}
