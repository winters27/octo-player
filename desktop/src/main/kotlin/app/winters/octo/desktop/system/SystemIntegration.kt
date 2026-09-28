package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.isTraySupported
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.window.screenAreas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

// The system integration, for the settings page and the now-playing bar.
val LocalSystem = staticCompositionLocalOf<SystemIntegration?> { null }

// Everything that ties Octo into the operating system: the media controls
// and media keys, the tray icon and its menu, notifications, the mini
// player, files and links handed over by later launches or dropped on the
// window, and pausing when the machine goes to sleep.
@Stable
class SystemIntegration(
    private val app: AppState,
    places: AppPlaces,
    val os: DesktopOs,
    private val instance: SingleInstance?,
) : AutoCloseable {
    val controls: SystemMediaControls = when (os) {
        DesktopOs.Windows -> NativeMediaControls.load("System media controls") ?: NoMediaControls()
        DesktopOs.Mac -> NativeMediaControls.load("Now Playing") ?: NoMediaControls()
        DesktopOs.Linux -> LinuxMediaControls(app.player::positionMs)
    }

    private val session = MediaSession(
        player = app.player,
        controls = controls,
        covers = ServerCovers(app.http, { app.connection?.client }, File(places.cache, "now-playing")),
        scope = app.scope,
        others = ::otherEvent,
    )

    private val sleepWatch = if (os == DesktopOs.Linux) LinuxSleepWatch() else null

    val trayAvailable: Boolean = runCatching { isTraySupported }.getOrDefault(false)
    val trayState = TrayState()
    val trayIcon: Painter? = picture("/octo-tray.png")

    private val notifier: Notifier? = when {
        os == DesktopOs.Linux -> LinuxNotifier(null)
        trayAvailable -> TrayNotifier(trayState)
        else -> null
    }
    private val notices = NowPlayingNotices()

    val notificationsAvailable: Boolean get() = notifier != null

    // Whether the media keys reach Octo, for the settings page.
    var mediaKeysWork by mutableStateOf(false)
        private set

    // The main window: shown or hidden in the tray, and in front or not.
    var windowVisible by mutableStateOf(true)
        private set
    private var windowInFront = true

    var miniPlayerOpen by mutableStateOf(app.settings.current.system.miniPlayerOpen)
        private set

    // Set by the window: bring it forward, and quit the app.
    var focusWindow: () -> Unit = {}
    var quit: () -> Unit = {}

    // Closing the window keeps Octo in the tray, when there is a tray.
    val closesToTray: Boolean get() = trayAvailable && app.settings.current.system.closeToTray

    fun start(launchArgs: List<String>) {
        session.start { works -> mediaKeysWork = works }
        // The system bus can be slow to answer, so it is reached off the window's thread.
        sleepWatch?.let { watch -> app.scope.launch(Dispatchers.IO) { watch.start { event -> app.scope.launch { session.handle(event) } } } }
        app.scope.launch {
            app.player.state.collect { state ->
                val notice = notices.noticeFor(nowPlayingOf(state), app.settings.current.system.nowPlayingNotices, windowVisible && windowInFront)
                notice?.let { (title, text) -> notifier?.show(title, text) }
            }
        }
        instance?.onLaunch { args -> app.scope.launch { arrived(args) } }
        listenToMac()
        open(parseLaunchArgs(launchArgs))
    }

    // A later launch handed its command line over.
    private fun arrived(args: List<String>) {
        raise()
        open(parseLaunchArgs(args))
    }

    // On macOS files and links come as events, not on the command line, and
    // a click on the Dock icon should bring a hidden window back.
    private fun listenToMac() {
        if (os != DesktopOs.Mac || !Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        runCatching { desktop.setOpenFileHandler { event -> app.scope.launch { open(parseLaunchArgs(event.files.map { it.path })) } } }
        runCatching { desktop.setOpenURIHandler { event -> app.scope.launch { raise(); open(parseLaunchArgs(listOf(event.uri.toString()))) } } }
        runCatching { desktop.addAppEventListener(AppReopenedListener { app.scope.launch { raise() } }) }
    }

    // Plays opened files as one-off songs, or follows a link.
    fun open(requests: List<LaunchRequest>) {
        for (request in requests) {
            when (request) {
                is LaunchRequest.OpenFiles -> app.play(request.files.map(::openedFileSong))
                is LaunchRequest.OpenLink -> {
                    val page = pageForLink(request.link)
                    when {
                        page == null -> app.notice = "Octo doesn't know that link."
                        app.connection == null -> app.notice = "Sign in to open that link."
                        else -> {
                            app.fullPlayer = false
                            app.navigator.go(page)
                        }
                    }
                }
            }
        }
    }

    fun openFiles(files: List<File>) = open(listOf(LaunchRequest.OpenFiles(files)))

    private fun otherEvent(event: SystemEvent) {
        when (event) {
            SystemEvent.Raise -> raise()
            SystemEvent.Quit -> quit()
            is SystemEvent.SetVolume -> app.setVolume(event.volume)
            is SystemEvent.OpenUri -> open(parseLaunchArgs(listOf(event.uri)))
            else -> {}
        }
    }

    fun raise() {
        windowVisible = true
        focusWindow()
    }

    fun hideWindow() {
        windowVisible = false
    }

    fun toggleMiniPlayer() = setMiniPlayer(!miniPlayerOpen)

    fun setMiniPlayer(open: Boolean) {
        miniPlayerOpen = open
        app.settings.update { it.copy(system = it.system.copy(miniPlayerOpen = open)) }
    }

    fun onTray(action: TrayAction) {
        when (action) {
            TrayAction.PlayPause -> app.player.togglePlay()
            TrayAction.Next -> app.player.next()
            TrayAction.Previous -> app.player.previous()
            TrayAction.ShowWindow -> raise()
            TrayAction.MiniPlayer -> toggleMiniPlayer()
            TrayAction.Quit -> quit()
        }
    }

    // Follows the window's focus, for the notices.
    fun watch(window: java.awt.Window) {
        window.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) {
                windowInFront = true
            }

            override fun windowLostFocus(e: WindowEvent?) {
                windowInFront = false
            }
        })
    }

    // The tray icon and the mini player, which live beside the main window.
    @Composable
    fun ApplicationScope.Surfaces(windowIcon: Painter?) {
        val state by app.player.state.collectAsState()
        val now = nowPlayingOf(state)
        val icon = trayIcon ?: windowIcon
        if (trayAvailable && icon != null) {
            OctoTray(icon, trayState, now, miniPlayerOpen, ::onTray)
        }
        if (miniPlayerOpen) {
            val spot = placeMiniPlayer(app.settings.current.system.miniPlayer, screenAreas())
            MiniPlayerWindow(
                player = app.player,
                covers = app.connection?.client,
                spot = spot,
                os = os,
                icon = windowIcon,
                onMoved = { moved -> app.settings.update { it.copy(system = it.system.copy(miniPlayer = moved)) } },
                onOpenOcto = ::raise,
                onClose = { setMiniPlayer(false) },
            )
        }
    }

    // The listener for later launches goes first, so a launch made while
    // quitting starts its own Octo instead of being taken and lost.
    override fun close() {
        runCatching { instance?.close() }
        runCatching { session.close() }
        runCatching { sleepWatch?.close() }
        runCatching { notifier?.close() }
    }
}

// A picture from the app's resources.
fun picture(resource: String): Painter? = runCatching {
    val bytes = SystemIntegration::class.java.getResourceAsStream(resource)!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()
