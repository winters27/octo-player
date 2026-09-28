package app.winters.octo.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.winters.octo.desktop.audio.CHECK_PLAY
import app.winters.octo.desktop.audio.checkSound
import app.winters.octo.desktop.audio.openPlayer
import app.winters.octo.desktop.library.coverLoader
import app.winters.octo.desktop.nav.KeyPress
import app.winters.octo.desktop.nav.shortcutFor
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.WindowSpot
import app.winters.octo.desktop.settings.currentOs
import app.winters.octo.desktop.settings.systemReducesMotion
import app.winters.octo.desktop.system.AudioDropZone
import app.winters.octo.desktop.system.LocalSystem
import app.winters.octo.desktop.system.SingleInstance
import app.winters.octo.desktop.system.SystemIntegration
import app.winters.octo.desktop.system.letRunningOctoComeForward
import app.winters.octo.desktop.ui.Shell
import app.winters.octo.desktop.window.Frame
import app.winters.octo.desktop.window.MIN_HEIGHT
import app.winters.octo.desktop.window.MIN_WIDTH
import app.winters.octo.desktop.window.ScreenArea
import app.winters.octo.desktop.window.placeWindow
import app.winters.octo.desktop.window.roundWindowsCorners
import app.winters.octo.desktop.window.seeThroughMacTitleBar
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.TypingState
import coil3.compose.setSingletonImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import okhttp3.OkHttpClient
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.util.concurrent.TimeUnit

// The usable part of every screen, without taskbars and menu bars.
private fun screenAreas(): List<ScreenArea> = runCatching {
    val toolkit = Toolkit.getDefaultToolkit()
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val config = device.defaultConfiguration
        val b = config.bounds
        val i = toolkit.getScreenInsets(config)
        ScreenArea((b.x + i.left).toFloat(), (b.y + i.top).toFloat(), (b.width - i.left - i.right).toFloat(), (b.height - i.top - i.bottom).toFloat())
    }
}.getOrDefault(emptyList())

private fun appIcon(): Painter? = runCatching {
    val bytes = AppState::class.java.getResourceAsStream("/octo-icon.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    val places = AppPlaces.forSystem()
    // One Octo at a time: launching it again hands the files and links to
    // the running one, which comes forward, and ends here.
    val claim = SingleInstance.claim(places.config, args.toList(), beforeHandover = ::letRunningOctoComeForward)
    if (claim is SingleInstance.Claim.HandedOver) return
    val instance = (claim as? SingleInstance.Claim.First)?.instance
    val settings = SettingsStore(File(places.config, SettingsStore.FILE_NAME), SettingsStore.APP_WRITE_DELAY_MS)
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val accounts = Accounts(settings, SecretStore.forSystem(), http)
    val os = currentOs()
    val systemCalm = systemReducesMotion(os)
    val icon = appIcon()

    application {
        val app = remember {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var made: AppState? = null
            // Server songs are signed with whoever is signed in when they queue.
            val opened = openPlayer(settings, scope) { made?.connection?.client }
            AppState(settings, accounts, http, scope, os, opened.player).also {
                made = it
                opened.problem?.let { problem -> it.notice = problem }
            }
        }
        val system = remember { SystemIntegration(app, places, os, instance) }
        System.getProperty(CHECK_PLAY)?.let { path -> LaunchedEffect(Unit) {
                checkSound(app, File(path)) {
                    system.close()
                    app.player.close()
                    exitApplication()
                }
            }
        }
        setSingletonImageLoaderFactory { context -> coverLoader(context, http, places.cache) }
        val spot = remember { placeWindow(settings.current.window, screenAreas()) }
        // Windows and Linux get the app's own glass frame unless the
        // listener asked for the system's; macOS keeps its own lights.
        val custom = remember { os != DesktopOs.Mac && !settings.current.systemTitleBar }
        val windowState = rememberWindowState(
            placement = if (spot.maximized && !custom) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition(spot.x.dp, spot.y.dp),
            size = DpSize(spot.width.dp, spot.height.dp),
        )
        val typing = remember { TypingState() }
        var frame by remember { mutableStateOf<Frame?>(null) }
        // Where the window last was at its own size, for the next run.
        var floating by remember { mutableStateOf(spot.copy(maximized = false)) }
        fun maximizedNow() = frame?.maximized ?: (windowState.placement == WindowPlacement.Maximized)
        fun keepPlace() = settings.update { it.copy(window = floating.copy(maximized = maximizedNow())) }
        fun close() {
            keepPlace()
            settings.flush()
            system.close()
            app.player.close()
            exitApplication()
        }
        // Closing the window quits, or with the setting on, leaves Octo
        // playing in the tray.
        fun closeWindow() {
            if (!system.closesToTray) return close()
            keepPlace()
            system.hideWindow()
        }
        LaunchedEffect(Unit) {
            system.quit = ::close
            system.start(args.toList())
        }

        Window(
            onCloseRequest = ::closeWindow,
            state = windowState,
            visible = system.windowVisible,
            title = "Octo",
            icon = icon,
            undecorated = custom,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                val press = KeyPress(event.key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
                val shortcut = shortcutFor(press, app.mac, typing.active) ?: return@Window false
                app.perform(shortcut)
            },
        ) {
            val own = remember { if (custom) Frame(window, windowState) else null }
            LaunchedEffect(Unit) {
                frame = own
                window.minimumSize = Dimension(MIN_WIDTH.toInt(), MIN_HEIGHT.toInt())
                if (os == DesktopOs.Mac) seeThroughMacTitleBar(window)
                if (own != null && os == DesktopOs.Windows) roundWindowsCorners(window)
                if (own != null && spot.maximized) own.maximize()
                system.watch(window)
                system.focusWindow = {
                    windowState.isMinimized = false
                    window.isVisible = true
                    window.toFront()
                    window.requestFocus()
                }
            }
            // Remembers the window's own size and place as it changes, and
            // saves it once it settles, so a crash does not lose it.
            LaunchedEffect(Unit) {
                snapshotFlow { Triple(windowState.position, windowState.size, maximizedNow() || windowState.placement != WindowPlacement.Floating) }
                    .debounce(700)
                    .collect { (position, size, filled) ->
                        if (!filled && position is WindowPosition.Absolute) {
                            floating = WindowSpot(position.x.value, position.y.value, size.width.value, size.height.value)
                        }
                        keepPlace()
                    }
            }
            val look by app.settings.state.collectAsState()
            ProvideWindowLook(reduceMotion = look.appearance.calmMotion || systemCalm) {
                CompositionLocalProvider(LocalTyping provides typing, LocalSystem provides system) {
                    AudioDropZone(system::openFiles) { Shell(app, own, ::closeWindow) }
                }
            }
        }
        with(system) { Surfaces(icon) }
    }
}
