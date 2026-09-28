package app.winters.octo.listening

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class PlayDaysTest {
    private val zone = ZoneId.of("America/Chicago")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun playsFallUnderTodayYesterdayTheWeekdayAndTheDate() {
        val now = at(2026, 9, 28, 20)
        val plays = listOf(
            "a" to at(2026, 9, 28, 19),
            "b" to at(2026, 9, 28, 0, 5),
            "c" to at(2026, 9, 27, 23, 59),
            "d" to at(2026, 9, 24),
            "e" to at(2026, 9, 2),
            "f" to at(2025, 12, 31),
        )
        val days = byPlayDay(plays, { it.second }, now, zone, Locale.US)
        assertEquals(listOf("Today", "Yesterday", "Thursday", "September 2", "December 31, 2025"), days.map { it.heading })
        assertEquals(listOf("a", "b"), days[0].items.map { it.first })
    }

    @Test
    fun aWeekAgoIsADateNotAWeekday() {
        val today = LocalDate.of(2026, 9, 28)
        assertEquals("Tuesday", playDayHeading(LocalDate.of(2026, 9, 22), today, Locale.US))
        assertEquals("September 21", playDayHeading(LocalDate.of(2026, 9, 21), today, Locale.US))
    }

    @Test
    fun midnightIsTheListenersOwn() {
        val now = at(2026, 9, 28, 23, 45)
        assertEquals("Today", byPlayDay(listOf(at(2026, 9, 28, 23, 30)), { it }, now, zone, Locale.US).single().heading)
    }
}
