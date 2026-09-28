package app.winters.octo.query

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// What a field is called in a rule's words.
fun QueryField.label(): String = when (this) {
    QueryField.Title -> "Title"
    QueryField.Artist -> "Artist"
    QueryField.Album -> "Album"
    QueryField.AlbumArtist -> "Album artist"
    QueryField.Genre -> "Genre"
    QueryField.Composer -> "Composer"
    QueryField.Format -> "Format"
    QueryField.Year -> "Year"
    QueryField.Plays -> "Plays"
    QueryField.Rating -> "Rating"
    QueryField.Duration -> "Length"
    QueryField.BitRate -> "Bit rate"
    QueryField.Bpm -> "BPM"
    QueryField.Added -> "Added"
    QueryField.LastPlayed -> "Played"
    QueryField.Favourite -> "Favourite"
    QueryField.Lossless -> "Lossless"
}

// A rule in plain words, for a pill or a live list's summary: "Added in the
// last month", "Genre is Electronic", "Never played", "Rating at least 4",
// "Favourites". Dates are told in `zone`.
fun QueryRule.label(zone: ZoneId = ZoneId.systemDefault()): String {
    val name = field.label()
    if (!complete) return name
    return when (field.kind) {
        FieldKind.Text -> {
            val value = if (field == QueryField.Format) text.orEmpty().uppercase() else text.orEmpty()
            "$name ${textWords(op)} $value"
        }
        FieldKind.Number -> numberLabel(name)
        FieldKind.Date -> dateLabel(zone)
        FieldKind.Flag -> when (field) {
            QueryField.Lossless -> if (flag == true) "Lossless" else "Lossy"
            else -> if (flag == true) "Favourites" else "Not favourites"
        }
    }
}

private fun textWords(op: QueryOp): String = when (op) {
    QueryOp.Contains -> "contains"
    QueryOp.DoesNotContain -> "does not contain"
    QueryOp.StartsWith -> "starts with"
    QueryOp.IsNot -> "is not"
    else -> "is"
}

private fun QueryRule.numberLabel(name: String): String {
    val low = number ?: 0
    val high = to ?: low
    // A year range of one whole decade reads as the decade.
    if (field == QueryField.Year && op == QueryOp.Between && low % 10 == 0L && high == low + 9) return "From the ${low}s"
    val show: (Long) -> String = when (field) {
        QueryField.Duration -> ::lengthWords
        QueryField.BitRate -> { n -> "$n kbps" }
        else -> Long::toString
    }
    return when (op) {
        QueryOp.Is -> "$name is ${show(low)}"
        QueryOp.IsNot -> "$name is not ${show(low)}"
        QueryOp.AtLeast -> "$name at least ${show(low)}"
        QueryOp.AtMost -> "$name at most ${show(low)}"
        else -> "$name between ${show(minOf(low, high))} and ${show(maxOf(low, high))}"
    }
}

private fun QueryRule.dateLabel(zone: ZoneId): String {
    val added = field == QueryField.Added
    val span = spanWords(days ?: 0)
    val day = at?.let { DayWords.withZone(zone).format(Instant.ofEpochMilli(it)) }.orEmpty()
    return when (op) {
        QueryOp.InTheLast -> if (added) "Added in the last $span" else "Played in the last $span"
        QueryOp.NotInTheLast -> if (added) "Not added in the last $span" else "Not played in the last $span"
        QueryOp.Before -> if (added) "Added before $day" else "Last played before $day"
        QueryOp.After -> if (added) "Added since $day" else "Last played since $day"
        else -> if (added) "No date added" else "Never played"
    }
}

private val DayWords = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

// "week", "month", "6 months", "2 years", "10 days": a span in the words a
// person would use, for the spans the filters offer.
fun spanWords(days: Int): String = when {
    days == 1 -> "day"
    days == 7 -> "week"
    days == 30 -> "month"
    days == 365 -> "year"
    days > 0 && days % 365 == 0 -> "${days / 365} years"
    days > 0 && days % 30 == 0 -> "${days / 30} months"
    days > 0 && days % 7 == 0 -> "${days / 7} weeks"
    else -> "$days days"
}

// "3:07", or "1:02:03" for an hour or more.
private fun lengthWords(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
