package app.winters.octo.server

import app.winters.octo.subsonic.ScanStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The words both apps show about a server: what it is, what it holds and
// offers, how quickly it answers, and when it last read its folders.

// Its kind and version: "Octo 0.9.3", "Navidrome 0.58.0", or a plain
// "Subsonic server" when it does not say.
fun serverKind(type: String?, version: String?): String =
    listOfNotNull(type?.takeIf(String::isNotBlank)?.replaceFirstChar { it.uppercase() }, version?.takeIf(String::isNotBlank))
        .joinToString(" ")
        .ifEmpty { "Subsonic server" }

// What it brings beyond the music, or null when nothing.
fun serverOffers(lyrics: Boolean, adds: Boolean): String? {
    val offers = listOfNotNull(if (lyrics) "synced lyrics" else null, if (adds) "adding songs you find online to your library" else null)
    return if (offers.isEmpty()) null else "Offers ${offers.joinToString(" and ")}."
}

// How much it holds: "1,204 songs, 96 albums, 41 artists and 12
// playlists". A count not known yet is left out.
fun libraryCounts(songs: Int?, albums: Int?, artists: Int?, playlists: Int?): String? {
    val parts = listOfNotNull(
        songs?.let { counted(it, "song") },
        albums?.let { counted(it, "album") },
        artists?.let { counted(it, "artist") },
        playlists?.let { counted(it, "playlist") },
    )
    return when (parts.size) {
        0 -> null
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}

private fun counted(n: Int, one: String) = "%,d %s".format(Locale.ENGLISH, n, if (n == 1) one else one + "s")

// How quickly it answered: "Answered in 42 ms", in seconds past one.
fun answerWords(ms: Long): String =
    if (ms < 1_000) "Answered in $ms ms" else "Answered in %.1f s".format(Locale.ENGLISH, ms / 1_000.0)

// Whether the server is reading its folders now and how far it is, or when
// it last did. Null when it says neither.
fun scanWords(status: ScanStatus?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (status == null) return null
    if (status.scanning) {
        return if (status.count > 0) "Reading its folders now, %,d files so far.".format(Locale.ENGLISH, status.count) else "Reading its folders now."
    }
    val last = serverTime(status.lastScan) ?: return null
    return "Last read its folders ${agoWords(last, nowMs, zone)}."
}

// A time before now in words: "just now", "5 minutes ago", "yesterday",
// "on 12 September", with the year when it was another year.
fun agoWords(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val minutes = (nowMs - thenMs).coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> if (minutes == 1L) "a minute ago" else "$minutes minutes ago"
        hours < 24 -> if (hours == 1L) "an hour ago" else "$hours hours ago"
        days < 2 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> {
            val then = Instant.ofEpochMilli(thenMs).atZone(zone)
            val sameYear = then.year == Instant.ofEpochMilli(nowMs).atZone(zone).year
            "on " + then.format(DateTimeFormatter.ofPattern(if (sameYear) "d MMMM" else "d MMMM yyyy", Locale.ENGLISH))
        }
    }
}
