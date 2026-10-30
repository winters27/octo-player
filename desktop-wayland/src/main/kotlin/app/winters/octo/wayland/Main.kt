@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.winters.octo.wayland

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.animated.LoopShelf
import app.winters.octo.design.*
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.Startup
import app.winters.octo.desktop.animated.LOOP_SHELF_BYTES
import app.winters.octo.desktop.audio.CHECK_PLAY
import app.winters.octo.desktop.audio.checkSound
import app.winters.octo.desktop.audio.openPlayer
import app.winters.octo.desktop.library.LocalCovers
import app.winters.octo.desktop.library.coverLoader
import app.winters.octo.desktop.library.followCoversAhead
import app.winters.octo.desktop.nav.KeyPress
import app.winters.octo.desktop.nav.shortcutFor
import app.winters.octo.desktop.settings.*
import app.winters.octo.desktop.system.*
import app.winters.octo.desktop.ui.*
import app.winters.octo.desktop.update.DesktopUpdates
import app.winters.octo.desktop.update.UpdaterAvailability
import app.winters.octo.server.NEW_MUSIC_LOOK_MS
import app.winters.octo.subsonic.OCTO_TRANSITIONS
import coil3.PlatformContext as CoilContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.*
import org.jetbrains.desktop.linux.*
import java.io.File
import java.nio.file.Path
import java.util.Properties
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val status = try {
        runPlayer(args)
        0
    } catch (error: Throwable) {
        error.printStackTrace()
        1
    }
    // Network workers may outlive the native loop's orderly cleanup.
    exitProcess(status)
}

private fun runPlayer(args: Array<String>) {
    check(Runtime.version().feature() >= 27) { "Octo Wayland needs Java 27 (included in the release)." }
    check(!System.getenv("WAYLAND_DISPLAY").isNullOrBlank()) { "Start Octo Wayland in a Wayland desktop session." }
    System.setProperty("java.awt.headless", "true")
    val build = Properties().apply {
        checkNotNull(WaylandWindow::class.java.getResourceAsStream("/wayland-build.properties")).use { load(it) }
    }
    System.setProperty("octo.version", build.getProperty("version"))
    check(androidx.compose.ui.Actuals_desktopKt.getPostDelayedDispatcher() === Dispatchers.Main) {
        "The Octo host jar must precede Compose on the classpath"
    }
    useAppNatives()
    val places = AppPlaces.forSystem()
    val claim = SingleInstance.claim(places.config, args.toList())
    if (claim is SingleInstance.Claim.HandedOver) return
    val instance = (claim as? SingleInstance.Claim.First)?.instance
    val settings = SettingsStore(File(places.config, SettingsStore.FILE_NAME), SettingsStore.APP_WRITE_DELAY_MS)
    val startup = Startup(settings, places, DesktopOs.Linux)
    KotlinDesktopToolkit.init()
    println("wayland: java=${Runtime.version()} pid=${ProcessHandle.current().pid()} awt=headless display=${System.getenv("DISPLAY") ?: "unset"} socket=${System.getenv("WAYLAND_DISPLAY")}")
    // KDT 0.0.90 drops its event-loop/Wayland connection before EGLData during
    // Application.close(). Mesa 25 then crashes in libwayland-client during
    // eglTerminate (caught by the Ubuntu packaged smoke test). This executable
    // creates exactly one Application: retain it until exitProcess instead.
    // run() still stops the loop, closes native windows, flushes settings and
    // disposes the audio/player/Compose resources before process teardown.
    val native = Application()
    WaylandDispatcher.application = native
    NativePlayer(native, startup, places, settings, instance, args.toList()).run()
}

private class NativePlayer(
    private val native: Application,
    private val startup: Startup,
    private val places: AppPlaces,
    private val settings: SettingsStore,
    private val instance: SingleInstance?,
    private val args: List<String>,
) {
    private var failure: Throwable? = null
    private var stopping = false
    private val scope = CoroutineScope(SupervisorJob() + WaylandDispatcher + CoroutineExceptionHandler { _, error -> fail(error) })
    private val clipboard = WaylandClipboard(native)
    private val fileDrops = NativeFileDrops()
    private val windows = linkedMapOf<Long, WaylandWindow>()
    private val restoringMaximized = mutableSetOf<Long>()
    private val files = WaylandFiles({ app?.notice = it }) { windows[focused]?.window ?: windows.values.firstOrNull()?.window }
    private var nextId = 0L
    private var focused: Long? = null
    private var modifiers = emptySet<KeyModifiers>()
    private val activations = mutableMapOf<RequestId, Long>()
    private var app by mutableStateOf<AppState?>(null)
    private var system: SystemIntegration? = null
    private var systemCalm = false
    private var systemText = 1f
    private val typing = TypingState()
    private val lists = ListFocus()
    private val keyboard = FocusVisibility()
    private val arrows = ArrowKeys()

    private fun fail(error: Throwable) {
        if (error is CancellationException) return
        if (failure == null) failure = error
        error.printStackTrace()
        native.stopEventLoop()
    }

    private fun quit() {
        if (stopping) return
        stopping = true
        native.stopEventLoop()
    }

    private fun openWindow(mini: Boolean, size: Pair<Int, Int>? = null): WaylandWindow {
        val saved = if (mini) settings.current.system.miniPlayer else settings.current.window
        val dimensions = size ?: if (mini) (saved?.width?.toInt() ?: 520) to (saved?.height?.toInt() ?: 160)
            else (saved?.width?.toInt() ?: 1100) to (saved?.height?.toInt() ?: 760)
        val id = ++nextId
        val window = WaylandWindow(native, id, mini, dimensions.first.coerceAtLeast(if (mini) 320 else 720),
            dimensions.second.coerceAtLeast(if (mini) 120 else 480), clipboard,
            previewKey = { preview(it, mini) }, content = { Content(it) })
        windows[id] = window
        if (!mini && saved?.maximized == true) restoringMaximized.add(id)
        return window
    }

    private fun closeWindow(window: WaylandWindow) {
        windows.remove(window.id)
        restoringMaximized.remove(window.id)
        window.close()
    }

    private fun activate(window: WaylandWindow) {
        window.window.requestActivationToken()?.let { activations[it] = window.id }
    }

    private fun resizeMini(width: Float, height: Float) {
        // xdg-shell deliberately has no set-position/set-size request. A new
        // toplevel gives the compositor the requested normal size.
        scope.launch {
            windows.values.firstOrNull { it.mini }?.let(::closeWindow)
            openWindow(true, width.toInt() to height.toInt())
        }
    }

    private suspend fun openApp() {
        val parts = startup.parts()
        systemCalm = parts.systemCalm
        systemText = parts.systemText
        var made: AppState? = null
        val opened = openPlayer(
            settings, scope, { made?.connection?.client },
            { made?.connection?.headers.orEmpty() + parts.accounts.security.deviceHeaders() },
            { id -> made?.fetches?.landedId(id) },
            transitions = { made?.connection?.supports(OCTO_TRANSITIONS) == true },
            params = { made?.streamParams() ?: mapOf("format" to "raw") },
            kept = { id -> made?.offline?.localFile(id) }, opened = parts.engine,
        )
        // A fork's experimental backend must not install an upstream AWT build
        // over itself. Releases are updated by replacing the portable bundle.
        val updates = DesktopUpdates(settings,
            UpdaterAvailability.Off("Wayland preview: download updates from VibeProgramm/octo-player releases."),
            DesktopOs.Linux, File(places.cache, "updates"), null)
        val state = AppState(settings, parts.accounts, parts.http, scope, DesktopOs.Linux, opened.player,
            restored = parts.restored, listeningRoot = places.config, updates = updates, homeShelves = File(places.cache, "home"))
        made = state
        opened.problem?.let { state.notice = it }
        state.playlistArt.folder = File(places.cache, "playlist-art")
        state.animated.shelf = LoopShelf(File(places.cache, "loops"), LOOP_SHELF_BYTES)
        SingletonImageLoader.setSafe { context -> coverLoader(context, parts.http, places.cache) }
        state.coverImages = { SingletonImageLoader.get(CoilContext.INSTANCE) }
        state.offline.start()
        val integration = SystemIntegration(state, places, DesktopOs.Linux, instance, nativeHost = true)
        system = integration
        state.toggleMiniPlayer = integration::toggleMiniPlayer
        integration.quit = ::quit
        integration.minimizeWindow = { windows.values.firstOrNull()?.window?.minimize() }
        integration.focusWindow = {
            // MPRIS Raise and a second launch return to the full window.
            if (integration.miniPlayerOpen) integration.setMiniPlayer(false)
            windows.values.firstOrNull { !it.mini }?.let(::activate)
        }
        app = state
        integration.start(args)
        scope.launch { followCoversAhead(state.player, { state.connection?.client }, { SingletonImageLoader.get(CoilContext.INSTANCE) }) }
        scope.launch { while (isActive) { delay(NEW_MUSIC_LOOK_MS); state.library?.look() } }
        scope.launch {
            snapshotFlow { integration.miniPlayerOpen }.collect { mini ->
                val existing = windows.values.firstOrNull { it.mini == mini }
                if (existing == null) openWindow(mini)
                windows.values.filter { it.mini != mini }.toList().forEach(::closeWindow)
            }
        }
        System.getProperty(CHECK_PLAY)?.let { path -> scope.launch { checkSound(state, File(path), ::quit) } }
        System.getProperty("octo.wayland.capture")?.let { path -> scope.launch {
            delay(if (System.getProperty(CHECK_PLAY) != null) 1_000 else 6_000)
            windows.values.firstOrNull()?.capture(Path.of(path))
        } }
        System.getProperty("octo.wayland.seconds")?.toLongOrNull()?.let { seconds -> scope.launch { delay(seconds * 1_000); quit() } }
        println("wayland: player-ready audio=${opened.problem ?: "native"}")
        if (System.getProperty("octo.wayland.checkFiles") == "true") scope.launch {
            delay(1_000)
            checkFileDialogs(files)
            quit()
        }
    }

    private fun preview(event: androidx.compose.ui.input.key.KeyEvent, mini: Boolean): Boolean {
        val app = app ?: return false
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key == Key.Tab || event.key == Key.Menu || event.key in WalkKeys) keyboard.keyboard = true
        if (mini && event.key == Key.Escape) { system?.setMiniPlayer(false); return true }
        val press = KeyPress(event.key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
        val shortcut = shortcutFor(press, false, typing.active, lists.active, arrows.claimed || app.popups.open) ?: return false
        return app.perform(shortcut)
    }

    @Composable
    private fun Content(window: WaylandWindow) {
        FollowLateCloses()
        val state = app
        if (state == null) {
            Opening(remember { OctopusPainter() }, calm = settings.current.appearance.calmMotion,
                leaving = false, onEntered = {}, onGone = {})
            return
        }
        val settings by state.settings.state.collectAsState()
        val density = LocalDensity.current
        ProvideWindowLook(reduceMotion = settings.appearance.calmMotion || systemCalm, focus = keyboard, arrows = arrows) {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, textScale(settings.appearance.textSize, systemText)),
                LocalTyping provides typing, LocalSystem provides system, LocalListFocus provides lists,
                LocalWindowShown provides true, LocalSoftwareDrawing provides false,
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (window.clientDecorated && !window.fullscreen) Header(window)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (!window.mini) Shell(state, null, ::quit)
                        else {
                            val prefs = settings.system
                            CompositionLocalProvider(LocalCovers provides state.connection?.client) {
                                MiniPlayerView(state, prefs.miniPlayerPanel.toMiniPanel(), false,
                                    actions = MiniActions(
                                        pin = {}, // Compositor-managed; hidden in the native host.
                                        resize = ::resizeMini,
                                        showPanel = { panel ->
                                            state.settings.update { it.copy(system = it.system.copy(miniPlayerPanel = panel?.name?.lowercase())) }
                                            if (panel != null && window.logicalSize.second < 440) resizeMini(400f, 600f)
                                        },
                                        close = { system?.setMiniPlayer(false) },
                                        more = { system?.setMiniPlayer(false); state.fullPlayer = true },
                                    ), shape = RectangleShape,
                                    dragArea = { modifier -> Box(modifier.pointerInput(window.id) {
                                        detectTapGestures(onPress = { window.beginMove() })
                                    }) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(window: WaylandWindow) {
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().pointerInput(window.id) {
                detectTapGestures(onPress = { window.beginMove() }, onDoubleTap = { window.toggleMaximized() })
            }) { Txt("Octo", OctoType.bodySmall, OctoColors.TextPrimary, Modifier.padding(start = 12.dp, top = 6.dp)) }
            TextAction("—", { window.window.minimize() })
            TextAction("□", { window.toggleMaximized() })
            TextAction("×", { if (window.mini) system?.setMiniPlayer(false) else quit() })
        }
    }

    fun run() {
        DesktopServices.clipboard = clipboard
        DesktopServices.files = files
        DesktopServices.nativeFileDrops = fileDrops
        try {
            native.runEventLoop(ApplicationConfig(
                eventHandler = { event ->
                    try {
                        when (event) {
                            Event.ApplicationStarted -> { openWindow(false); scope.launch { openApp() } }
                            is Event.WindowDraw -> {
                                val window = windows[event.windowId]
                                val drawn = window?.draw(event) == true
                                if (drawn && window.frames == 1 && event.windowId in restoringMaximized) {
                                    // First map the saved normal bounds. Maximizing before the
                                    // first buffer leaves KWin with no restore geometry, and KDT
                                    // uses the maximized size for a later 0x0 normal configure.
                                    scope.launch { windows[event.windowId]?.window?.maximize() }
                                }
                                return@ApplicationConfig if (drawn) EventHandlerResult.Stop else EventHandlerResult.Continue
                            }
                            is Event.WindowConfigure -> windows[event.windowId]?.let { w ->
                                w.configure(event)
                                val (width, height) = w.logicalSize
                                if (event.maximized) restoringMaximized.remove(event.windowId)
                                if (!event.fullscreen && event.windowId !in restoringMaximized && width > 0 && height > 0) settings.update { old ->
                                    if (w.mini) old.copy(system = old.system.copy(miniPlayer = windowSpot(old.system.miniPlayer, width, height, event.maximized, true)))
                                    else old.copy(window = windowSpot(old.window, width, height, event.maximized, false))
                                }
                            }
                            is Event.WindowScaleChanged -> { windows[event.windowId]?.scale = event.newScale.rawScale.toFloat(); println("wayland: scale=$event") }
                            is Event.WindowCloseRequest -> if (windows[event.windowId]?.mini == true) system?.setMiniPlayer(false) else quit()
                            is Event.WindowClosed -> { restoringMaximized.remove(event.windowId); windows.remove(event.windowId)?.nativeClosed() }
                            is Event.WindowKeyboardEnter -> {
                                focused = event.windowId
                                windows[focused]?.focus(true)
                                system?.focusChanged(true)
                                scope.launch { clipboard.read(); system?.family?.offerClipboard() }
                            }
                            is Event.WindowKeyboardLeave -> { windows[event.windowId]?.focus(false); if (focused == event.windowId) focused = null; system?.focusChanged(false) }
                            is Event.ModifiersChanged -> { modifiers = event.modifiers; windows.values.forEach { it.info.keyboardModifiers = modifiers.composeModifiers() } }
                            is Event.MouseMoved -> windows[event.windowId]?.pointer(PointerEventType.Move, event.locationInWindow)
                            is Event.MouseEntered -> windows[event.windowId]?.pointer(PointerEventType.Enter, event.locationInWindow)
                            is Event.MouseExited -> windows[event.windowId]?.pointer(PointerEventType.Exit, event.locationInWindow)
                            is Event.MouseDown -> windows[event.windowId]?.pointer(PointerEventType.Press, event.locationInWindow, event.button, event.serial)
                            is Event.MouseUp -> windows[event.windowId]?.pointer(PointerEventType.Release, event.locationInWindow, event.button, event.serial)
                            is Event.ScrollWheel -> windows[event.windowId]?.pointer(PointerEventType.Scroll, event.locationInWindow, scroll = Offset(scroll(event.horizontalScroll), scroll(event.verticalScroll)))
                            is Event.KeyDown -> windows[focused]?.key(event.keyCode.value, event.key.value, true, event.characters, modifiers)
                            is Event.KeyUp -> windows[focused]?.key(event.keyCode.value, event.key.value, false, null, modifiers)
                            is Event.TextInputAvailability -> windows[event.windowId]?.input?.available = event.available
                            is Event.TextInput -> windows[focused]?.input?.receive(event)
                            is Event.DataTransfer -> clipboard.receive(event)
                            is Event.DataTransferAvailable -> if (event.dataSource == DataSource.Clipboard) {
                                clipboard.offered()
                                scope.launch { clipboard.read() }
                            }
                            is Event.DataTransferCancelled -> if (event.dataSource == DataSource.Clipboard) {
                                clipboard.cancelled()
                                scope.launch { clipboard.read() }
                            }
                            is Event.FileChooserResponse -> files.receive(event)
                            is Event.ActivationTokenResponse -> activations.remove(event.requestId)?.let { windows[it]?.window?.activate(event.token) }
                            is Event.DropPerformed -> {
                                println("wayland: drop id=${event.windowId} mime=${event.content?.mimeType} bytes=${event.content?.data?.size ?: 0}")
                                event.content?.data?.decodeToString()?.lineSequence()?.filter { it.isNotBlank() && !it.startsWith('#') }?.toList()?.let { addresses ->
                                    scope.launch {
                                        if (!fileDrops.receive(addresses)) system?.open(parseLaunchArgs(addresses))
                                    }
                                }
                            }
                            else -> Unit
                        }
                        if (event == Event.ApplicationWantsToTerminate) EventHandlerResult.Continue else EventHandlerResult.Stop
                    } catch (error: Throwable) { fail(error); EventHandlerResult.Continue }
                },
                queryDragAndDropTarget = { query -> DragAndDropQueryResponse(query.mimeTypes.filter { it == "text/uri-list" }.map {
                    SupportedActionsForMime(it, setOf(DragAndDropAction.Copy), DragAndDropAction.Copy)
                }) },
                getDataTransferData = clipboard::data,
            ))
        } finally {
            app?.beforeQuit()
            settings.flush()
            system?.close()
            app?.player?.close()
            instance?.close()
            scope.cancel()
            windows.values.toList().forEach { it.nativeClosed() }
            WaylandDispatcher.stopped = true
        }
        failure?.let { throw it }
        println("wayland: clean-exit")
    }
}

private fun scroll(data: ScrollData): Float = if (data.wheelValue120 != 0) data.wheelValue120 / 120f else data.delta.rawLogical.toFloat() / 15f
private val WalkKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd)
