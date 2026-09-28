package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState

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

// The words shown when the pointer rests on the tray icon.
fun trayTooltip(now: NowPlaying?): String = now?.let { "Octo: " + shortLabel(songLine(it), 100) } ?: "Octo"

// The tray icon, drawn from the menu above. A click on the icon itself
// brings the window forward.
@Composable
fun ApplicationScope.OctoTray(
    icon: Painter,
    state: TrayState,
    now: NowPlaying?,
    miniPlayerOpen: Boolean,
    onAction: (TrayAction) -> Unit,
) {
    Tray(
        icon = icon,
        state = state,
        tooltip = trayTooltip(now),
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
