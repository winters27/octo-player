package app.winters.octo.catalog

// Covers Octo paints itself: a station's or a mix's. The server paints each
// afresh as the list's songs change, under the list's own id, so both apps
// keep them apart from library covers and only for a day.

// Raise this when the server changes how it draws them, so every copy kept
// is dropped at once. 1: painted backgrounds with no badge (Octo 2026.09.29).
const val DRAWN_COVER_VERSION = 1

// How long a drawn cover is kept: a UTC day, the period Octo makes its mixes
// afresh by default. Its stations are made afresh every 12 hours, but their
// covers change only when their songs' colours or count do.
const val DRAWN_COVER_MS = 24L * 60 * 60 * 1000

// Whether a cover id is one of Octo's own lists: "or" for a station, "og"
// for a mix, then 20 letters and digits. Navidrome's ids start with a digit
// and its cover ids with a kind ("al-", "pl-"), so none of them looks like it.
fun isDrawnCoverId(coverId: String): Boolean =
    coverId.length == 22 && (coverId.startsWith("or") || coverId.startsWith("og")) && coverId.all(::isBase62)

private fun isBase62(c: Char) = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

// Which day a drawn cover asked for at `nowMs` belongs to.
fun drawnCoverDay(nowMs: Long): Long = nowMs.floorDiv(DRAWN_COVER_MS)

// What a drawn cover is kept under on the day `nowMs` falls in: the
// version and the day, placed in each app's key.
fun drawnCoverStamp(nowMs: Long): String = "v$DRAWN_COVER_VERSION:${drawnCoverDay(nowMs)}"
