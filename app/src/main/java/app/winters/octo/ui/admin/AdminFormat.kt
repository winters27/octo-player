package app.winters.octo.ui.admin

import app.winters.octo.admin.DownloadRecord
import app.winters.octo.admin.Health
import app.winters.octo.admin.LibraryStatus
import app.winters.octo.admin.RadioLearning
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// How a service's health is drawn.
enum class HealthDot { Ok, Warning, Down, NotSetUp }

fun healthDot(health: Health): HealthDot = when {
    !health.configured -> HealthDot.NotSetUp
    !health.ok -> HealthDot.Down
    health.warning -> HealthDot.Warning
    else -> HealthDot.Ok
}

// What each of the server's services is called on screen. Anything new
// shows by its key.
fun serviceName(key: String): String = when (key) {
    "navidrome" -> "Navidrome"
    "slskd" -> "Soulseek"
    "lidarr" -> "Lidarr"
    "ytDlpShim" -> "Streaming helper"
    "lastfm" -> "Last.fm"
    else -> key
}

// A time from Octo. One written without a zone is taken as UTC.
fun parseTime(text: String?): Instant? {
    if (text.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(text).toInstant() }
        .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
        .getOrNull()
}

// How long ago, the way a person says it: "Just now", "5 min ago",
// "Yesterday", then the date.
fun ago(then: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val minutes = Duration.between(then, now).toMinutes()
    val day = then.atZone(zone)
    val days = ChronoUnit.DAYS.between(day.toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        days == 0L || minutes < 6 * 60 -> "${minutes / 60} hr ago"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        day.year == now.atZone(zone).year -> DateTimeFormatter.ofPattern("d MMM", locale).format(day)
        else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(day)
    }
}

fun ago(text: String?, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? =
    parseTime(text)?.let { ago(it, now, zone, locale) }

// How far the stations have learned the listener's taste.
fun learningLine(learning: RadioLearning): String =
    if (learning.needed <= 0 || learning.plays >= learning.needed) {
        "Learned from ${plays(learning.plays)}"
    } else {
        "Learning your taste: ${"%,d".format(learning.plays)} of ${plays(learning.needed)}"
    }

private fun plays(count: Int) = if (count == 1) "1 play" else "%,d plays".format(count)

// The last refresh's error in words fit for the screen. Anything that
// reads like a program's output gets a plain sentence instead.
fun refreshProblem(error: String?): String? {
    val text = error?.trim().orEmpty()
    if (text.isEmpty()) return null
    val technical = text.first() in "{[<" || '\n' in text || "Exception" in text || text.length > 160
    return if (technical) "The last refresh did not finish." else "The last refresh did not finish: $text"
}

// What stops downloads reaching the library, if anything.
fun libraryProblems(status: LibraryStatus): List<String> = buildList {
    if (!status.writable) add("Octo cannot write to this folder")
    if (!status.rescanAuthenticated) add("Octo cannot ask Navidrome to rescan")
}

// The line under a download's title: who it is by and what kind of file.
fun downloadLine(record: DownloadRecord): String =
    listOf(record.artist, record.format.uppercase()).filter { it.isNotBlank() }.joinToString(" · ")
