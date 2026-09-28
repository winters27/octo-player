package app.winters.octo.desktop.system

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState

// Shows a system notification.
interface Notifier : AutoCloseable {
    fun show(title: String, text: String)

    override fun close() {}
}

// Notifications through the tray icon, on Windows (a toast) and macOS.
class TrayNotifier(private val tray: TrayState) : Notifier {
    override fun show(title: String, text: String) = tray.sendNotification(Notification(title, text, Notification.Type.None))
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
