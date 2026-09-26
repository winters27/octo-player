package app.winters.octo.ui.server

import app.winters.octo.server.ScanState
import app.winters.octo.server.serverTime
import app.winters.octo.subsonic.Share

private const val HOUR_MS = 60 * 60 * 1000L
private const val DAY_MS = 24 * HOUR_MS

// The words for a library scan in the Server card.
fun scanLabel(state: ScanState): String = when (state) {
    ScanState.Idle -> "Scan library now"
    is ScanState.Scanning -> if (state.count > 0) "Scanning, %,d songs".format(state.count) else "Scanning"
    ScanState.Finished -> "Scan finished"
    is ScanState.Failed -> state.message
}

// What a link shares: its description, or its first song.
fun shareTitle(share: Share): String =
    share.description?.takeIf(String::isNotBlank) ?: share.entry.firstOrNull()?.title?.takeIf(String::isNotBlank) ?: "Shared link"

// How much it holds, when it expires and how often it was opened, on one line.
fun shareDetails(share: Share, now: Long): String = listOf(
    when (val count = share.entry.size) {
        0 -> null
        1 -> "1 song"
        else -> "$count songs"
    },
    expiryLabel(serverTime(share.expires), now),
    when (share.visitCount) {
        0 -> "No visits"
        1 -> "1 visit"
        else -> "%,d visits".format(share.visitCount)
    },
).filterNotNull().joinToString(" • ")

// When a link stops working, in words.
fun expiryLabel(expiresAt: Long?, now: Long): String {
    if (expiresAt == null || expiresAt <= 0) return "Never expires"
    val left = expiresAt - now
    return when {
        left <= 0 -> "Expired"
        left < HOUR_MS -> "Expires within the hour"
        left < DAY_MS -> (left / HOUR_MS).let { if (it == 1L) "Expires in 1 hour" else "Expires in $it hours" }
        else -> ((left + DAY_MS / 2) / DAY_MS).let { if (it == 1L) "Expires in 1 day" else "Expires in $it days" }
    }
}

// How long ago someone started a song, from the server's minutes.
fun minutesAgo(minutes: Int): String = when {
    minutes <= 0 -> "Just now"
    minutes == 1 -> "1 minute ago"
    minutes < 60 -> "$minutes minutes ago"
    minutes < 120 -> "1 hour ago"
    else -> "${minutes / 60} hours ago"
}
