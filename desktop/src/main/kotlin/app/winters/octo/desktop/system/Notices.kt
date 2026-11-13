package app.winters.octo.desktop.system

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsHold
import app.winters.octo.desktop.ui.settingsHoldWords

// Shows a system notification.
interface Notifier : AutoCloseable {
    fun show(title: String, text: String)

    override fun close() {}
}

// Notifications through the tray icon, on Windows (a toast) and macOS.
class TrayNotifier(private val tray: TrayState) : Notifier {
    override fun show(title: String, text: String) = tray.sendNotification(Notification(title, text, Notification.Type.None))
}

// Where notices go. Linux's go to the desktop's notification service and
// never through the tray: its tray is kept by hand, which takes no
// TrayState, and the notices still arrive while that tray is away. Windows
// and macOS show theirs from Compose's tray icon.
enum class NoticeRoute { Desktop, Tray }

fun noticeRoute(os: DesktopOs, trayAvailable: Boolean): NoticeRoute? = when {
    os == DesktopOs.Linux -> NoticeRoute.Desktop
    trayAvailable -> NoticeRoute.Tray
    else -> null
}

// Decides when a hold on the settings file earns a system notice: once per
// hold, as it begins, in the banner's words (the banner has Try again; a
// notice has no actions). A hold that ends lets the next one be told.
class SettingsHoldNotices {
    private var told = false

    fun noticeFor(hold: SettingsHold): String? {
        if (hold == SettingsHold.Free) {
            told = false
            return null
        }
        if (told) return null
        told = true
        return settingsHoldWords(hold)
    }
}

// Decides when a new song earns a "Now playing" notice: once per queue
// entry, only while it plays, only when the listener turned the notices on,
// and never while Octo's window is in front, where the song is in view.
class NowPlayingNotices {
    private var lastShown: Long? = null

    // The notice to show for this moment, or null for none.
    fun noticeFor(now: NowPlaying?, enabled: Boolean, windowInFront: Boolean): Pair<String, String>? {
        if (now == null || !now.playing || now.entryKey == lastShown) return null
        // A song that starts while the notices are off or the window is in
        // front counts as seen, so it is not announced later.
        lastShown = now.entryKey
        if (!enabled || windowInFront) return null
        return now.title to listOf(now.artist, now.album).filter(String::isNotBlank).joinToString(" · ")
    }
}
