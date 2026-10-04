package app.winters.octo.health

// The words both apps use around Library health's fixes and deleting a
// song from disk, so the desktop and the phone ask and answer alike.

const val DELETE_FROM_DISK = "Delete from disk"
const val RECENTLY_REMOVED = "Recently removed"
const val PUT_BACK = "Put back"
const val LOOK_UP_TAGS = "Look up tags"
const val UNDO_LAST_CHANGE = "Undo the last change"

// The question before songs leave the server's disk.
fun deleteTitle(count: Int): String = if (count == 1) "Delete this song from disk?" else "Delete ${countText(count, "song", "songs")} from disk?"

// What deleting does, for `names` ("Teardrop" or "3 songs"). The server
// keeps the file in its trash for `keepDays` (0 is until someone clears it).
fun deleteBody(names: String, keepDays: Int): String {
    val kept = if (keepDays > 0) "for ${countText(keepDays, "day", "days")}" else "until someone clears it"
    return "This takes $names off the server's disk and out of your library. The server keeps the file in its trash $kept, " +
        "so you can put it back from Library health. Octo will not download it again by itself."
}

// The line after a delete.
fun deletedLine(count: Int, title: String?): String =
    if (count == 1 && title != null) "Deleted $title from disk." else "Deleted ${countText(count, "song", "songs")} from disk."

// What the Recently removed list says about one song's time left, from
// the server's "goneAt", counted from `nowMillis`.
fun goneText(goneAtMillis: Long?, nowMillis: Long): String {
    if (goneAtMillis == null) return "Kept until someone clears the trash"
    val days = ((goneAtMillis - nowMillis) / 86_400_000L).coerceAtLeast(0)
    return when (days) {
        0L -> "Deleted for good today"
        1L -> "Deleted for good tomorrow"
        else -> "Deleted for good in $days days"
    }
}

// What a fix button says for a check, and what it does, for `count` items.
fun HealthCheck.fixAllLabel(count: Int): String = when (this) {
    HealthCheck.Duplicates -> "Fix ${countText(count, "song", "songs")}"
    HealthCheck.SplitAlbums -> "Join ${countText(count, "album", "albums")}"
    HealthCheck.NoCover -> "Find ${countText(count, "cover", "covers")}"
    HealthCheck.NoLength -> "Find higher quality for ${countText(count, "song", "songs")}"
    HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist -> "Fill in ${countText(count, "song", "songs")} from their albums"
    HealthCheck.NoTrackNumber -> "Look up ${countText(count, "song", "songs")}"
}

// How a fix for a check works, in a sentence, for the preview.
fun HealthCheck.fixMeaning(): String = when (this) {
    HealthCheck.Duplicates ->
        "Keeps the best copy of each song, fills in the tags it lacks from the others, and moves the others to the server's trash, where they can be put back."
    HealthCheck.SplitAlbums ->
        "Gives the songs of the smaller parts the album tags of the largest part, so each album shows as one."
    HealthCheck.NoCover ->
        "Looks for each album's cover and puts it inside songs that have no picture. A picture already there is never replaced."
    HealthCheck.NoLength ->
        "Asks the server for a higher quality copy of each song, which takes the damaged file's place once it passes the server's checks."
    HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist ->
        "Fills in the tag where the rest of the album agrees on it. Look up the others one at a time."
    HealthCheck.NoTrackNumber ->
        "Looks each song up the way a download is tagged, and shows what it found before anything is written."
}
