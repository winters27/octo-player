package app.winters.octo.health

import java.util.Locale

// The words both apps use for library health, so a finding reads the same
// on the phone and the desktop: what each check is called, what it means,
// and what to do about it.

// A count with its noun, "1 song" or "2,256 songs".
fun countText(count: Int, one: String, many: String): String =
    if (count == 1) "1 $one" else String.format(Locale.ROOT, "%,d %s", count, many)

fun HealthCheck.title(): String = when (this) {
    HealthCheck.Duplicates -> "Songs you have twice"
    HealthCheck.SplitAlbums -> "Albums split apart"
    HealthCheck.NoLength -> "Songs with no length"
    HealthCheck.NoTrackNumber -> "No track number"
    HealthCheck.NoAlbumArtist -> "No album artist"
    HealthCheck.NoCover -> "No cover art"
    HealthCheck.NoYear -> "No year"
    HealthCheck.NoGenre -> "No genre"
}

// How much a check found, in its own terms.
fun HealthCheck.countLabel(count: Int): String = when (this) {
    HealthCheck.Duplicates -> countText(count, "song with more than one copy", "songs with more than one copy")
    HealthCheck.SplitAlbums -> countText(count, "album", "albums")
    else -> countText(count, "song", "songs")
}

// What a finding means for the listener.
fun HealthCheck.meaning(): String = when (this) {
    HealthCheck.Duplicates ->
        "The same recording is in your library more than once, so it shows up twice in your lists and takes up space."
    HealthCheck.SplitAlbums ->
        "Each of these albums shows up as more than one album, because its songs' tags do not agree."
    HealthCheck.NoLength ->
        "The server could not tell how long these songs are, which usually means the file is damaged or cut short."
    HealthCheck.NoTrackNumber ->
        "These songs are on albums but have no track number, so their albums play out of order."
    HealthCheck.NoAlbumArtist ->
        "Without an album artist, an album can be filed under each song's own artist and fall apart."
    HealthCheck.NoCover ->
        "These songs have no picture, so they show a blank square."
    HealthCheck.NoYear ->
        "These songs have no year, so year filters leave them out and they sort last by year."
    HealthCheck.NoGenre ->
        "These songs have no genre, so genre pages and genre filters leave them out."
}

// What to do about it.
fun HealthCheck.advice(): String = when (this) {
    HealthCheck.Duplicates ->
        "The first copy of each is the one to keep: it sounds best, or has the fullest tags. " +
            "Remove the others, unless a copy belongs to an album you want to keep whole."
    HealthCheck.SplitAlbums ->
        "Give every song of the album the same album title, album artist and year with a tag editor, then let the server rescan."
    HealthCheck.NoLength ->
        "Play one to check. If it will not play, replace the file."
    HealthCheck.NoCover ->
        "Add a picture to the files, or put a cover image in the album's folder, then let the server rescan."
    HealthCheck.NoTrackNumber, HealthCheck.NoAlbumArtist, HealthCheck.NoYear, HealthCheck.NoGenre ->
        "Add the missing tag with a tag editor, then let the server rescan."
}

// Why a set's copies were taken for one recording.
fun DuplicateBasis.words(): String = when (this) {
    DuplicateBasis.Tags -> "Tagged as the same recording"
    DuplicateBasis.TitleAndLength -> "Same title, artist and length"
}

// Which copy to keep and why, for a set whose best copy sounds like `best`.
fun BestReason.words(best: String): String = when (this) {
    BestReason.Sound -> "Keep the first, $best"
    BestReason.Tags -> "They sound alike; the first has fuller tags"
    BestReason.None -> "They sound alike"
}

// One line for a set of copies: "3 copies. Same title, artist and length.
// Keep the first, FLAC, 24-bit, 96 kHz."
fun <T> DuplicateGroup<T>.summary(fields: HealthFields<T>): String =
    "${copies.size} copies. ${basis.words()}. ${bestReason.words(qualityText(best, fields))}."

// A heading for a set of copies: the song and its artist.
fun <T> DuplicateGroup<T>.heading(fields: HealthFields<T>): String {
    val artist = fields.artist(best)?.takeIf(String::isNotBlank)
    return if (artist == null) fields.title(best) else "${fields.title(best)} by $artist"
}

// A heading for a split album and what its parts disagree on.
fun <T> SplitAlbum<T>.heading(): String {
    val by = if (artist.isBlank()) title.trim() else "${title.trim()} by ${artist.trim()}"
    return "$by, shown as ${parts.size} albums"
}

fun <T> SplitAlbum<T>.summary(): String = differences.joinToString(" ") { it.words() }

fun AlbumDifferenceValues.words(): String {
    val listed = values.joinToString(", ") { it.trim().ifBlank { "none" } }
    // A space before or after the words is there but cannot be seen.
    val spaceOnly = values.map { it.trim() }.distinct().size == 1
    return when {
        spaceOnly && kind == AlbumDifference.Title -> "The album title has a stray space on some songs."
        spaceOnly && kind == AlbumDifference.AlbumArtist -> "The album artist has a stray space on some songs."
        else -> byKind(listed)
    }
}

private fun AlbumDifferenceValues.byKind(listed: String): String {
    return when (kind) {
        AlbumDifference.Title -> "The album title is written differently: $listed."
        AlbumDifference.AlbumArtist -> "The album artist differs: $listed."
        AlbumDifference.Year -> "The year differs: $listed."
        AlbumDifference.Other -> "Something else in the tags differs, often a release date or an album id on only some songs."
    }
}

// How a copy sounds, in a few words: "FLAC, 24-bit, 96 kHz" or "MP3,
// 320 kbps". Only what the server says.
fun <T> qualityText(song: T, fields: HealthFields<T>): String =
    qualityText(fields.format(song), fields.lossless(song), fields.bitDepth(song), fields.sampleRate(song), fields.bitRate(song))

fun qualityText(format: String?, lossless: Boolean, bitDepth: Int?, sampleRate: Int?, bitRate: Int?): String {
    val parts = ArrayList<String>()
    format?.takeIf(String::isNotBlank)?.let { parts += it.uppercase(Locale.ROOT) }
    if (lossless) {
        bitDepth?.takeIf { it > 0 }?.let { parts += "$it-bit" }
        sampleRate?.takeIf { it > 0 }?.let { parts += rateText(it) }
    } else {
        bitRate?.takeIf { it > 0 }?.let { parts += "$it kbps" }
    }
    return parts.joinToString(", ").ifEmpty { "unknown quality" }
}

// 44100 as "44.1 kHz", 96000 as "96 kHz".
private fun rateText(hz: Int): String {
    val tenths = (hz + 50) / 100
    return if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10}.${tenths % 10} kHz"
}

// The line under the page's title.
fun <T> HealthReport<T>.overview(): String {
    val checkedText = "Checked ${countText(checked, "song", "songs")}."
    // The page says the rest when there is nothing to fix.
    if (clean) return checkedText
    val kinds = findings.size
    return "$checkedText ${if (kinds == 1) "One thing" else "$kinds things"} could be better."
}

// What the page says when there is nothing to fix.
const val HEALTH_ALL_CLEAR = "No second copies, no split albums and no missing tags. There is nothing to fix."
