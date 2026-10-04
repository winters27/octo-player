package app.winters.octo.desktop.system

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.listening.listeningFolder
import app.winters.octo.desktop.liveListSongs
import app.winters.octo.desktop.library.LibraryState
import app.winters.octo.desktop.playlistGone
import app.winters.octo.desktop.playlistSongs
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.key
import app.winters.octo.subsonic.SubsonicException
import com.sun.jna.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

// What the rest of the app tells the jump list, and asks of it: what was
// just played, and pinning albums from their menu.
interface JumpListHooks {
    fun played(target: JumpTarget)

    fun isPinned(target: JumpTarget): Boolean

    fun setPinned(target: JumpTarget, pinned: Boolean)

    // Takes something the server no longer has out of the jump list.
    fun forget(target: JumpTarget) {}
}

// Octo on the Windows shell: the taskbar button, the jump list and starting
// at sign-in. Elsewhere, or without the system library, it does nothing.
@Stable
class ShellIntegration internal constructor(
    private val app: AppState,
    private val os: DesktopOs,
    // Where each account's listening is kept, or null to keep none.
    private val listeningRoot: File?,
    // The installed program, or null when Octo runs from a build.
    val program: String? = installedProgram(),
    library: ShellLibrary? = if (os == DesktopOs.Windows) loadShellLibrary() else null,
    private val startup: StartupEntries? = if (os == DesktopOs.Windows) WindowsStartupEntries() else null,
) : JumpListHooks, AutoCloseable {
    private val taskbar = library?.let { WindowsTaskbar(it, app.player, app.scope) }

    // The jump list's entries start the installed program, so a build has none.
    private val jumpList = if (library != null && program != null) WindowsJumpList(library, program) else null
    private val jumpStore = JumpListStore(app.scope)
    private val jobs = ArrayList<Job>()

    val jumpListWorks: Boolean get() = jumpList != null

    // Starting at sign-in needs the installed program to point Windows at.
    val startAtLoginWorks: Boolean get() = startup != null && program != null

    // Whether the listener turned Octo off in Task Manager's Startup apps.
    var startupTurnedOff by mutableStateOf(false)
        private set

    @OptIn(FlowPreview::class)
    fun start() {
        if (jumpList != null) {
            app.jumpList = this
            // Each account keeps its own record, since album ids belong to one server.
            jobs += app.scope.launch {
                snapshotFlow { app.connection?.server }
                    .distinctUntilChanged { a, b -> a?.key == b?.key }
                    .collect { account ->
                        jumpStore.saveNow()
                        val folder = account?.let { server -> listeningRoot?.let { listeningFolder(it, server) } }
                        jumpStore.open(folder?.let { File(it, JumpListStore.FILE_NAME) })
                        if (account == null) jumpList.clear()
                    }
            }
            jobs += app.scope.launch {
                jumpStore.entries.debounce(PUBLISH_DELAY_MS).collect { entries ->
                    if (app.connection == null) return@collect
                    jumpList.publish(entries.items()) { gone -> app.scope.launch { jumpStore.forget(gone) } }
                }
            }
        }
        val entries = startup
        val installed = program
        if (entries != null && installed != null) {
            jobs += app.scope.launch {
                app.settings.state.map { it.system.startWithWindows to it.system.startInTray }.distinctUntilChanged().collect { (on, inTray) ->
                    startupTurnedOff = withContext(Dispatchers.IO) {
                        runCatching {
                            if (!syncStartAtLogin(on, inTray, installed, entries)) shellLog.log(System.Logger.Level.INFO, "Starting with Windows could not be set")
                            on && entries.turnedOff(RUN_VALUE)
                        }.getOrDefault(false)
                    }
                }
            }
        }
    }

    // Takes on the main window's taskbar button, once the window is on
    // screen (it may start hidden in the tray).
    fun attach(window: Window) {
        val bar = taskbar ?: return
        fun now() {
            val handle = windowHandle(window)
            if (handle == 0L) return
            bar.attach(handle) { button ->
                when (button) {
                    TaskbarButton.Previous -> app.player.previous()
                    TaskbarButton.Toggle -> app.player.togglePlay()
                    TaskbarButton.Next -> app.player.next()
                }
            }
        }
        if (window.isDisplayable) {
            now()
        } else {
            window.addWindowListener(object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent?) {
                    window.removeWindowListener(this)
                    now()
                }
            })
        }
    }

    // Plays what a jump list entry names, and puts it first among the
    // recent ones.
    fun play(kind: JumpKind, id: String) {
        if (app.connection == null) {
            app.notice = "Sign in to play that."
            return
        }
        app.scope.launch {
            val known = jumpStore.entries.value.let { e -> (e.pinned + e.recent).firstOrNull { it.kind == kind && it.id == id } }
            val songs = try {
                when (kind) {
                    JumpKind.Album -> app.albumSongs(id)
                    JumpKind.Playlist -> app.playlistSongs(id)
                    JumpKind.LiveList -> {
                        val list = app.liveLists.byId(id)
                        // A live list picks from the library, which may still be on its way.
                        withTimeoutOrNull(LIBRARY_WAIT_MS) { app.library?.state?.first { it is LibraryState.Ready || it is LibraryState.Failed } }
                        list?.let { app.liveListSongs(it) }.orEmpty()
                    }
                }
            } catch (e: SubsonicException.NotFound) {
                // Gone from the server: gone from the jump list too.
                if (kind == JumpKind.Playlist) app.playlistGone(id) else forget(JumpTarget(kind, id, ""))
                emptyList()
            } catch (e: SubsonicException) {
                emptyList()
            }
            if (songs.isEmpty()) {
                app.notice = "Couldn't find that ${kind.noun} on the server."
                return@launch
            }
            val name = when (kind) {
                JumpKind.Album -> songs.first().album?.takeIf(String::isNotBlank)
                JumpKind.Playlist -> app.playlists.firstOrNull { it.id == id }?.name
                JumpKind.LiveList -> app.liveLists.byId(id)?.name
            } ?: known?.name
            app.play(songs, source = name)
            (known ?: name?.let { JumpTarget(kind, id, it) })?.let(::played)
        }
    }

    override fun played(target: JumpTarget) = jumpStore.played(target)

    override fun isPinned(target: JumpTarget): Boolean = jumpStore.entries.value.isPinned(target)

    override fun setPinned(target: JumpTarget, pinned: Boolean) = jumpStore.setPinned(target, pinned)

    override fun forget(target: JumpTarget) = jumpStore.forget(listOf(target.link))

    override fun close() {
        jobs.forEach { it.cancel() }
        jumpStore.saveNow()
        runCatching { taskbar?.close() }
        runCatching { jumpList?.close() }
    }

    private companion object {
        const val PUBLISH_DELAY_MS = 800L
        const val LIBRARY_WAIT_MS = 60_000L
    }
}

private val JumpKind.noun: String get() = when (this) {
    JumpKind.Album -> "album"
    JumpKind.Playlist -> "playlist"
    JumpKind.LiveList -> "live list"
}

// The window's own handle on Windows, or 0 when it has none yet.
private fun windowHandle(window: Window): Long = runCatching { Native.getWindowID(window) }.getOrNull()
    ?: runCatching { (window as? androidx.compose.ui.awt.ComposeWindow)?.windowHandle }.getOrNull()
    ?: 0L

// Whether a later launch needs the window: not when all it asks is to play
// something from the jump list, or to start in the tray.
fun launchWantsWindow(args: List<String>): Boolean {
    val asked = args.map(String::trim).filter(String::isNotEmpty)
    return asked.isEmpty() || asked.any { arg -> playLinkOf(arg) == null && !arg.equals(TRAY_FLAG, ignoreCase = true) }
}
