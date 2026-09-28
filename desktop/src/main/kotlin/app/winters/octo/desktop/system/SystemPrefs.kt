package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.WindowSpot
import kotlinx.serialization.Serializable

// How Octo fits into the operating system, kept with the other settings.
@Serializable
data class SystemPrefs(
    // Closing the window keeps Octo playing in the tray (the menu bar on
    // macOS) instead of quitting.
    val closeToTray: Boolean = false,
    // A system notification with each new song.
    val nowPlayingNotices: Boolean = false,
    // Whether the mini player was open, and where it was.
    val miniPlayerOpen: Boolean = false,
    val miniPlayer: WindowSpot? = null,
    // The mini player stays above other windows (the pin in it), and what
    // it shows under its header when tall enough: "lyrics", "queue" or none.
    val miniPlayerOnTop: Boolean = true,
    val miniPlayerPanel: String? = null,
)
