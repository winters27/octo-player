package app.winters.octo.desktop

import app.winters.octo.desktop.settings.systemTextScale
import app.winters.octo.desktop.settings.textScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import javax.swing.SwingUtilities
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
import androidx.compose.ui.input.key.Key
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
import app.winters.octo.design.ArrowKeys
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.audio.CHECK_PLAY
import app.winters.octo.desktop.audio.checkSound
import app.winters.octo.desktop.audio.openPlayer
import app.winters.octo.desktop.library.coverLoader
import app.winters.octo.desktop.nav.KeyPress
import app.winters.octo.desktop.nav.shortcutFor
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.ServerSecurity
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
import app.winters.octo.desktop.system.startsInTray
import app.winters.octo.desktop.ui.ListFocus
import app.winters.octo.desktop.ui.LocalListFocus
import app.winters.octo.desktop.ui.LocalSoftwareDrawing
import app.winters.octo.desktop.ui.LocalWindowShown
import app.winters.octo.desktop.ui.Shell
import app.winters.octo.desktop.window.Frame
import app.winters.octo.desktop.window.MIN_HEIGHT
import app.winters.octo.desktop.window.MIN_WIDTH
import app.winters.octo.desktop.window.keepsSpot
import app.winters.octo.desktop.window.placeWindow
import app.winters.octo.desktop.window.roundWindowsCorners
import app.winters.octo.desktop.window.screenAreas
import app.winters.octo.desktop.window.seeThroughMacTitleBar
import coil3.compose.setSingletonImageLoaderFactory
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import okhttp3.OkHttpClient
import org.jetbrains.skiko.GraphicsApi

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
    // One client for everything, set up for the signed-in server's headers
    // and the certificates the listener trusted.
    val security = ServerSecurity(settings)
    val http = security.install(OkHttpClient.Builder())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val accounts = Accounts(settings, SecretStore.forSystem(), http, security)
    // Read here, before the window, since the password store can wait on
    // the listener (a locked keyring asks to be unlocked).
    val restored = accounts.restore()
    val os = currentOs()
    // The system's own "Animation effects" (or Reduce motion), read again
    // whenever the window comes forward, so a change applies without a restart.
    var systemCalm by mutableStateOf(systemReducesMotion(os))
    // The system's text size, read the same way.
    var systemText by mutableStateOf(systemTextScale(os))
    val icon = appIcon()

    application {
        val app = remember {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var made: AppState? = null
            // Server songs are signed with whoever is signed in when they queue.
            val opened = openPlayer(settings, scope, { made?.connection?.client }) { made?.connection?.headers.orEmpty() }
            AppState(settings, accounts, http, scope, os, opened.player, restored = restored, listeningRoot = places.config).also {
                made = it
                opened.problem?.let { problem -> it.notice = problem }
            }
        }
        val system = remember { SystemIntegration(app, places, os, instance, startsInTray(args.toList())).also { app.toggleMiniPlayer = it::toggleMiniPlayer } }
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
        val lists = remember { ListFocus() }
        // Whether the keyboard is moving about (rings show), and whether a
        // focused control holds the arrow keys (a slider).
        val keyboard = remember { FocusVisibility() }
        val arrows = remember { ArrowKeys() }
        var frame by remember { mutableStateOf<Frame?>(null) }
        // Where the window last was at its own size, for the next run.
        var floating by remember { mutableStateOf(spot.copy(maximized = false)) }
        fun maximizedNow() = frame?.maximized ?: (windowState.placement == WindowPlacement.Maximized)
        fun keepPlace() = settings.update { it.copy(window = floating.copy(maximized = maximizedNow())) }
        fun close() {
            keepPlace()
            app.beforeQuit()
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
                // Tab, the Menu key, and the arrows in a list, a menu or a
                // slider are the keyboard finding its way: rings show.
                val finding = event.key == Key.Tab || event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed) ||
                    (event.key in WalkKeys && (lists.active || arrows.claimed || app.popups.open))
                if (finding) keyboard.keyboard = true
                // New keys for a global shortcut, being pressed in Settings.
                if (system.shortcuts.recording != null) {
                    return@Window system.shortcuts.pressed((event.nativeKeyEvent as? java.awt.event.KeyEvent)?.keyCode ?: 0, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
                }
                val press = KeyPress(event.key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
                val shortcut = shortcutFor(press, app.mac, typing.active, lists.active, arrows = arrows.claimed || app.popups.open) ?: return@Window false
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
                // Coming back to Octo is when a queue from the phone is worth a look.
                window.addWindowFocusListener(object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        app.queueSync.check()
                        Thread {
                            val calm = systemReducesMotion(os)
                            val text = systemTextScale(os)
                            SwingUtilities.invokeLater {
                                systemCalm = calm
                                systemText = text
                            }
                        }.apply { isDaemon = true }.start()
                    }
                })
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
                snapshotFlow { Triple(windowState.position, windowState.size, maximizedNow() || windowState.placement != WindowPlacement.Floating) to windowState.isMinimized }
                    .debounce(700)
                    .collect { (where, minimized) ->
                        val (position, size, filled) = where
                        if (position is WindowPosition.Absolute && keepsSpot(minimized, filled, position.x.value, position.y.value)) {
                            floating = WindowSpot(position.x.value, position.y.value, size.width.value, size.height.value)
                        }
                        keepPlace()
                    }
            }
            // Whether the window fell back to drawing without the graphics card.
            var software by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                fun check() {
                    software = window.renderApi == GraphicsApi.SOFTWARE_FAST || window.renderApi == GraphicsApi.SOFTWARE_COMPAT
                }
                check()
                window.onRenderApiChanged(::check)
            }
            val look by app.settings.state.collectAsState()
            // Every word grows with the text size; nothing else does.
            val density = LocalDensity.current
            val words = textScale(look.appearance.textSize, systemText)
            ProvideWindowLook(reduceMotion = look.appearance.calmMotion || systemCalm, focus = keyboard, arrows = arrows) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, density.fontScale * words),
                    LocalTyping provides typing,
                    LocalSystem provides system,
                    LocalListFocus provides lists,
                    LocalWindowShown provides (system.windowVisible && !windowState.isMinimized),
                    LocalSoftwareDrawing provides software,
                ) {
                    AudioDropZone(system::openFiles) { Shell(app, own, ::closeWindow) }
                }
            }
        }
        with(system) { Surfaces(icon) }
    }
}

// The keys that walk a list or a menu.
private val WalkKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd)
