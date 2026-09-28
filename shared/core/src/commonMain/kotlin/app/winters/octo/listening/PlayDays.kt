package app.winters.octo.listening

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Plays under a heading per day, for the Recently played lists of both apps.

// One day's plays under its heading, in the order they came.
data class PlayDay<T>(val heading: String, val items: List<T>)

// Groups plays that come newest first by the day they began: Today,
// Yesterday, the weekday for the rest of the week, then the date, with the
// year once it is not this one. The plays keep their order.
fun <T> byPlayDay(items: List<T>, at: (T) -> Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): List<PlayDay<T>> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = mutableListOf<PlayDay<T>>()
    var day: LocalDate? = null
    var bunch = mutableListOf<T>()
    for (item in items) {
        val date = Instant.ofEpochMilli(at(item)).atZone(zone).toLocalDate()
        if (date != day) {
            day?.let { days += PlayDay(playDayHeading(it, today, locale), bunch) }
            day = date
            bunch = mutableListOf()
        }
        bunch += item
    }
    day?.let { days += PlayDay(playDayHeading(it, today, locale), bunch) }
    return days
}

fun playDayHeading(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
    day == today -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> day.format(DateTimeFormatter.ofPattern("EEEE", locale))
    day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMMM d", locale))
    else -> day.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", locale))
}
