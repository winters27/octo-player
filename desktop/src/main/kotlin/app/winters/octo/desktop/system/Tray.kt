package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import app.winters.octo.desktop.ui.readLine
import app.winters.octo.server.CopyRunState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

// What the tray icon's menu (the menu bar's, on macOS) can do.
enum class TrayAction { PlayPause, Next, Previous, ShowWindow, MiniPlayer, Quit }

// One line of the tray menu.
sealed interface TrayEntry {
    // The song playing, shown and not clickable.
    data class Heading(val text: String) : TrayEntry

    data class Action(val action: TrayAction, val label: String, val enabled: Boolean = true) : TrayEntry

    data object Divider : TrayEntry
}

// Menu labels are cut here so a long title keeps the menu a sensible width.
private const val LABEL_LIMIT = 48

fun shortLabel(text: String, limit: Int = LABEL_LIMIT): String =
    if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"

// The line naming the song: its title, and its artist when known.
fun songLine(now: NowPlaying): String = if (now.artist.isBlank()) now.title else "${now.title} · ${now.artist}"

// The tray menu for what is playing: the song, the transport, then the
// window, the mini player and Quit.
fun trayMenu(now: NowPlaying?, miniPlayerOpen: Boolean): List<TrayEntry> = listOf(
    TrayEntry.Heading(now?.let { shortLabel(songLine(it)) } ?: "Nothing playing"),
    TrayEntry.Divider,
    TrayEntry.Action(TrayAction.PlayPause, if (now?.playing == true) "Pause" else "Play", enabled = now != null),
    TrayEntry.Action(TrayAction.Next, "Next", enabled = now?.canNext == true),
    TrayEntry.Action(TrayAction.Previous, "Previous", enabled = now != null),
    TrayEntry.Divider,
    TrayEntry.Action(TrayAction.ShowWindow, "Show Octo"),
    TrayEntry.Action(TrayAction.MiniPlayer, if (miniPlayerOpen) "Close the mini player" else "Mini player"),
    TrayEntry.Divider,
    TrayEntry.Action(TrayAction.Quit, "Quit Octo"),
)

// The words shown when the pointer rests on the tray icon: the read of the
// library while one runs and the window is hidden (`read`), its step and
// count, and otherwise what plays.
fun trayTooltip(now: NowPlaying?, read: CopyRunState? = null): String {
    if (read != null) return "Octo: " + readLine(read)
    return now?.let { "Octo: " + shortLabel(songLine(it), 100) } ?: "Octo"
}

// Whether closing the window leaves Octo in the tray: only with the setting
// on and Octo's icon in the tray to bring the window back from.
fun closeKeepsInTray(setting: Boolean, trayShowing: Boolean): Boolean = setting && trayShowing

// The system tray as the keeper sees it, so its choices can be tried
// without one.
interface TrayHost {
    // Puts a new icon in the tray: false, or a throw, when it can't.
    fun add(): Boolean

    // Takes Octo's icon out, if it is in.
    fun remove()

    // Whether Octo's icon is in the tray now.
    fun present(): Boolean
}

// Seconds between tries to put a lost icon back, and the tries in a row
// before the keeper gives up.
const val TRAY_RETRY_SECONDS = 3L
const val TRAY_TRIES = 5

// Keeps Octo's icon in the system tray. A lost icon is put back fresh,
// `retrySeconds` apart, and a new one is looked at again after that wait:
// only one still there counts as kept. After `tries` tries in a row that did
// not keep it, the keeper gives up and calls `onGaveUp`, then waits for the
// system to say a tray is there again.
class TrayKeeper(
    private val host: TrayHost,
    private val retrySeconds: Long = TRAY_RETRY_SECONDS,
    private val tries: Int = TRAY_TRIES,
    private val onGaveUp: () -> Unit = {},
) {
    private val news = Channel<Boolean>(Channel.UNLIMITED)
    private var tried = 0

    // Whether Octo's icon is in the tray, as far as the keeper knows.
    var showing = false
        private set
    var gaveUp = false
        private set

    // From any thread: the system said its tray is there (true) or gone.
    fun hear(trayThere: Boolean) {
        news.trySend(trayThere)
    }

    // Puts the icon in and keeps it there, until cancelled.
    suspend fun keep() {
        while (true) {
            check()
            val wait = nextCheckSeconds()
            val heard = if (wait == null) news.receive() else withTimeoutOrNull(wait * 1000) { news.receive() }
            heard?.let(::told)
        }
    }

    // What the system said about its tray. Either way the icon is made
    // again, and a tray that is back earns a fresh count of tries.
    fun told(trayThere: Boolean) {
        showing = false
        if (trayThere) {
            tried = 0
            gaveUp = false
        }
    }

    // Looks once: an icon still in place is kept, and a lost one is made again.
    fun check() {
        if (gaveUp) return
        if (showing && runCatching(host::present).getOrDefault(false)) {
            tried = 0
            return
        }
        showing = false
        if (tried >= tries) {
            gaveUp = true
            onGaveUp()
            return
        }
        tried++
        runCatching(host::remove)
        showing = runCatching(host::add).getOrDefault(false)
    }

    // Seconds to the next look, or null when only the system's word wakes the keeper.
    fun nextCheckSeconds(): Long? = if (gaveUp || (showing && tried == 0)) null else retrySeconds

    fun drop() {
        runCatching(host::remove)
        showing = false
    }
}

// Octo's icon in the system tray, made on AWT by hand. A new icon is made
// for every try, and every tray call is guarded, since AWT throws on tray
// calls while the tray is away.
class AwtTray(private val onAction: (TrayAction) -> Unit) : TrayHost {
    private var icon: TrayIcon? = null
    private var image: Image? = null
    private var tooltip = "Octo"
    private var entries: List<TrayEntry> = emptyList()

    // Passes on the system's word that its tray is there (true) or gone.
    fun listen(heard: (Boolean) -> Unit) {
        runCatching { SystemTray.getSystemTray().addPropertyChangeListener("systemTray") { heard(it.newValue != null) } }
    }

    // What the icon shows, kept for the next icon made too.
    fun show(image: Image, tooltip: String, entries: List<TrayEntry>) {
        val menuChanged = entries != this.entries
        this.image = image
        this.tooltip = tooltip
        this.entries = entries
        val made = icon ?: return
        runCatching {
            if (made.image !== image) made.image = image
            if (made.toolTip != tooltip) made.toolTip = tooltip
            if (menuChanged) fill(made.popupMenu)
        }
    }

    override fun add(): Boolean {
        val made = TrayIcon(image ?: return false, tooltip, PopupMenu().also(::fill)).apply {
            isImageAutoSize = true
            addActionListener { onAction(TrayAction.ShowWindow) }
        }
        SystemTray.getSystemTray().add(made)
        icon = made
        return true
    }

    override fun remove() {
        val made = icon ?: return
        icon = null
        SystemTray.getSystemTray().remove(made)
    }

    override fun present(): Boolean {
        val made = icon ?: return false
        return SystemTray.isSupported() && made in SystemTray.getSystemTray().trayIcons
    }

    private fun fill(menu: PopupMenu) {
        menu.removeAll()
        entries.forEach { entry ->
            when (entry) {
                is TrayEntry.Heading -> menu.add(MenuItem(entry.text).apply { isEnabled = false })
                is TrayEntry.Action -> menu.add(
                    MenuItem(entry.label).apply {
                        isEnabled = entry.enabled
                        addActionListener { onAction(entry.action) }
                    },
                )
                TrayEntry.Divider -> menu.addSeparator()
            }
        }
    }
}

// The screen's scale, which Compose's own tray draws its icon at.
private fun screenDensity(): Density = Density(
    runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat() }.getOrDefault(1f),
)

// The tray icon kept by `keeper`, with the same picture, menu and tooltip as
// OctoTray below, drawn at the size Compose's tray uses on Linux.
@Composable
fun KeptTray(tray: AwtTray, keeper: TrayKeeper, icon: Painter, now: NowPlaying?, miniPlayerOpen: Boolean, read: CopyRunState? = null) {
    val image = remember(icon) { icon.toAwtImage(screenDensity(), LayoutDirection.Ltr, Size(22f, 22f)) }
    val entries = trayMenu(now, miniPlayerOpen)
    val tooltip = trayTooltip(now, read)
    SideEffect { tray.show(image, tooltip, entries) }
    LaunchedEffect(keeper) { keeper.keep() }
    DisposableEffect(keeper) { onDispose { keeper.drop() } }
}

// The tray icon, drawn from the menu above. A click on the icon itself
// brings the window forward.
@Composable
fun ApplicationScope.OctoTray(
    icon: Painter,
    state: TrayState,
    now: NowPlaying?,
    miniPlayerOpen: Boolean,
    onAction: (TrayAction) -> Unit,
    read: CopyRunState? = null,
) {
    Tray(
        icon = icon,
        state = state,
        tooltip = trayTooltip(now, read),
        onAction = { onAction(TrayAction.ShowWindow) },
        menu = {
            trayMenu(now, miniPlayerOpen).forEach { entry ->
                when (entry) {
                    is TrayEntry.Heading -> Item(entry.text, enabled = false, onClick = {})
                    is TrayEntry.Action -> Item(entry.label, enabled = entry.enabled, onClick = { onAction(entry.action) })
                    TrayEntry.Divider -> Separator()
                }
            }
        },
    )
}
