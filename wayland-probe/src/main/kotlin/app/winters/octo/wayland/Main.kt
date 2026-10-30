@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.winters.octo.wayland

import androidx.compose.ui.input.pointer.PointerEventType
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.desktop.linux.*
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

private const val TEXT = "text/plain;charset=utf-8"
private val mimeTypes = listOf(TEXT, "text/plain", "text/uri-list")

fun main(args: Array<String>) {
    val status = try { runProbe(args); 0 } catch (error: Throwable) { error.printStackTrace(); 1 }
    exitProcess(status)
}

private fun runProbe(args: Array<String>) {
    check(Runtime.version().feature() == 27) { "Run the probe on Java 27" }
    check(System.getenv("DISPLAY").isNullOrEmpty()) { "Unset DISPLAY to rule out XWayland" }
    check(!System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()) { "A Wayland compositor is required" }
    System.setProperty("java.awt.headless", "true")
    val seconds = args.firstOrNull { it.startsWith("--seconds=") }?.substringAfter('=')?.toLong() ?: 0
    require(seconds >= 0)
    val exercise = "--exercise" in args
    require(!exercise || seconds >= 20) { "--exercise needs --seconds=20 or longer" }
    val snapshot = args.firstOrNull { it.startsWith("--snapshot=") }?.substringAfter('=')?.let(Path::of)
    KotlinDesktopToolkit.init()
    println("probe: java=${Runtime.version()} display=unset wayland=${System.getenv("WAYLAND_DISPLAY")}")
    // Like the player, this one-shot process retains KDT 0.0.90's Application:
    // close() drops Wayland before EGL and can crash Mesa 25 in eglTerminate.
    // The loop and finally below still dispose native windows and Compose/Skia.
    val app = Application()
    run {
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = app.runOnEventLoopAsync { block.run() }
        }
        val windows = linkedMapOf<Long, ProbeWindow>()
        var nextId = 0L
        var focused: Long? = null
        var modifiers = emptySet<KeyModifiers>()
        var clipboard = ""
        var pasteSerial = 0
        val pastes = mutableMapOf<Int, Long>()
        var failure: Throwable? = null
        var began = 0L
        var step = 0
        var completed = false

        fun close(id: Long) { windows.remove(id)?.close() }
        fun toggleMini() {
            val mini = windows.keys.firstOrNull { it != 1L }
            if (mini != null) close(mini) else {
                val id = ++nextId
                windows[id] = ProbeWindow(app, id, dispatcher, mini = true, toggleMini = {})
                println("probe: mini-open id=$id")
            }
        }
        fun inputContext(w: ProbeWindow, fromIme: Boolean = false) = TextInputContext(
            surroundingText = w.text,
            cursorCodepointOffset = w.text.codePointCount(0, w.text.length).toUShort(),
            selectionStartCodepointOffset = w.text.codePointCount(0, w.text.length).toUShort(),
            hints = emptySet(), contentPurpose = TextInputContentPurpose.Normal,
            cursorRectangle = LogicalRect.makeXYWH(56, 150, 2, 24), changeCausedByInputMethod = fromIme,
        )
        fun updateText(w: ProbeWindow, text: String, fromIme: Boolean = false) {
            // Wayland surrounding text has a 4 KiB protocol limit. This is
            // deliberately a small append-only protocol inspector.
            val codepoints = text.codePoints().limit(800).toArray()
            w.text = String(codepoints, 0, codepoints.size)
            if (w.ime) app.textInputUpdate(inputContext(w, fromIme))
        }
        fun exercise(now: Long) {
            if (began == 0L) began = now
            val elapsed = (now - began) / 1e9
            if (exercise) {
                when {
                    step == 0 && elapsed >= 3 -> { windows[1]?.window?.setFullScreen(); step++ }
                    step == 1 && elapsed >= 8 -> { windows[1]?.window?.unsetFullScreen(); step++ }
                    step == 2 && elapsed >= 10 -> { toggleMini(); step++ }
                    step == 3 && elapsed >= 13 -> { toggleMini(); step++ }
                    step == 4 && elapsed >= 15 -> { toggleMini(); step++ }
                }
            }
            if (seconds > 0 && elapsed >= seconds) {
                check(windows.values.all { it.frames >= 60 }) { "Not enough GPU frames from every live window" }
                check(!exercise || step == 5) { "Window exercise incomplete" }
                completed = true
                app.stopEventLoop()
            }
        }

        try {
            app.runEventLoop(ApplicationConfig(
                eventHandler = { event ->
                    try {
                        val current = windows[focused]
                        when (event) {
                            Event.ApplicationStarted -> {
                                nextId = 1
                                windows[1] = ProbeWindow(app, 1, dispatcher, mini = false, toggleMini = ::toggleMini)
                                if ("--fullscreen" in args) windows[1]!!.window.setFullScreen()
                                println("probe: screens=${app.allScreens()}")
                            }
                            is Event.WindowDraw -> {
                                val now = System.nanoTime()
                                windows[event.windowId]?.draw(event, now, snapshot.takeIf { event.windowId == 1L })
                                if (event.windowId == 1L) exercise(now)
                            }
                            is Event.WindowConfigure -> {
                                windows[event.windowId]?.fullscreen = event.fullscreen
                                println("probe: configure $event")
                            }
                            is Event.WindowScaleChanged -> {
                                windows[event.windowId]?.scale = event.newScale.rawScale.toFloat()
                                println("probe: scale $event")
                            }
                            is Event.WindowScreenChange, is Event.DisplayConfigurationChange -> println("probe: screen $event")
                            is Event.WindowCloseRequest -> { close(event.windowId); if (windows.isEmpty()) app.stopEventLoop() }
                            is Event.WindowClosed -> windows.remove(event.windowId)?.nativeClosed()
                            is Event.WindowKeyboardEnter -> focused = event.windowId
                            is Event.WindowKeyboardLeave -> { windows[event.windowId]?.preedit = ""; focused = null }
                            is Event.ModifiersChanged -> modifiers = event.modifiers
                            is Event.MouseMoved -> windows[event.windowId]?.pointer(PointerEventType.Move, event.locationInWindow)
                            is Event.MouseDown -> if (event.button == MouseButton.LEFT) {
                                windows[event.windowId]?.pointer(PointerEventType.Press, event.locationInWindow, true)
                            }
                            is Event.MouseUp -> if (event.button == MouseButton.LEFT) {
                                windows[event.windowId]?.pointer(PointerEventType.Release, event.locationInWindow, false)
                            }
                            is Event.KeyDown -> if (current != null) {
                                println("probe: key code=${event.keyCode.value} repeat=${event.isRepeat}")
                                val ctrl = KeyModifiers.Control in modifiers
                                when {
                                    event.keyCode.value == KeyCode.Escape -> { close(current.id); if (windows.isEmpty()) app.stopEventLoop() }
                                    event.keyCode.value == KeyCode.F11 -> if (current.fullscreen) current.window.unsetFullScreen() else current.window.setFullScreen()
                                    ctrl && event.keyCode.value == KeyCode.N -> toggleMini()
                                    ctrl && event.keyCode.value == KeyCode.C -> { clipboard = current.text; app.clipboardPut(listOf(TEXT)) }
                                    ctrl && event.keyCode.value == KeyCode.V -> { pastes[++pasteSerial] = current.id; app.clipboardPaste(pasteSerial, mimeTypes) }
                                    event.keyCode.value == KeyCode.BackSpace -> if (current.text.isNotEmpty()) {
                                        updateText(current, current.text.substring(0, current.text.offsetByCodePoints(current.text.length, -1)))
                                    }
                                    !ctrl && KeyModifiers.Alt !in modifiers && KeyModifiers.Logo !in modifiers ->
                                        event.characters?.filterNot(Char::isISOControl)?.let { updateText(current, current.text + it) }
                                }
                            }
                            is Event.TextInputAvailability -> windows[event.windowId]?.let { w ->
                                w.ime = event.available
                                if (event.available) app.textInputEnable(inputContext(w))
                                println("probe: ime-available=$event")
                            }
                            is Event.TextInput -> if (current != null) {
                                event.deleteSurroundingTextData?.let { deletion ->
                                    val bytes = current.text.toByteArray()
                                    updateText(current, bytes.copyOf((bytes.size - deletion.beforeLengthInBytes.toInt()).coerceAtLeast(0)).decodeToString(), true)
                                }
                                event.commitStringData?.text?.let { updateText(current, current.text + it, true) }
                                current.preedit = event.preeditStringData?.text.orEmpty()
                                println("probe: ime commit=${event.commitStringData != null} preedit=${event.preeditStringData != null}")
                            }
                            is Event.DataTransfer -> pastes.remove(event.serial)?.let { id ->
                                windows[id]?.let { w -> event.content?.let {
                                    updateText(w, w.text + it.data.decodeToString()); println("probe: clipboard-received mime=${it.mimeType} bytes=${it.data.size}")
                                } }
                            }
                            is Event.DropPerformed -> windows[event.windowId]?.let { w -> event.content?.let {
                                w.status = "Dropped ${it.mimeType}: ${it.data.decodeToString().take(200)}"
                                println("probe: drop-received mime=${it.mimeType} bytes=${it.data.size}")
                            } }
                            else -> Unit
                        }
                        // For ApplicationWantsToTerminate, Stop means veto.
                        if (event == Event.ApplicationWantsToTerminate) EventHandlerResult.Continue else EventHandlerResult.Stop
                    } catch (t: Throwable) {
                        failure = t
                        t.printStackTrace()
                        app.stopEventLoop()
                        EventHandlerResult.Stop
                    }
                },
                queryDragAndDropTarget = { query -> DragAndDropQueryResponse(
                    query.mimeTypes.filter { it in mimeTypes }.map {
                        SupportedActionsForMime(it, setOf(DragAndDropAction.Copy), DragAndDropAction.Copy)
                    },
                ) },
                getDataTransferData = { source, mime -> if (source == DataSource.Clipboard && mime == TEXT) clipboard.toByteArray() else null },
            ))
        } finally {
            // The native event loop destroys its windows when it terminates.
            windows.values.toList().forEach { it.nativeClosed() }
        }
        failure?.let { throw it }
        check(seconds == 0L || completed) { "Probe closed before the timed run completed" }
        println("probe: PASS java=27 native-wayland hardware-gpu")
    }
}
