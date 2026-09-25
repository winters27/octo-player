package app.winters.octo.device

// The tags Octo uses from a file, already cleaned up.
data class FileTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
    val compilation: Boolean = false,
    val mbAlbumId: String? = null,
    // The first is the main one.
    val genres: List<String> = emptyList(),
)

private val leadingYear = Regex("""^\s*(\d{4})""")

// Reads a file's tag map (keys as the tag library names them, so the same
// names cover FLAC, MP3 and MP4). Where several tags can hold a value, the
// more specific one wins: ARTISTS over ARTIST, ORIGINALDATE over DATE.
fun parseTags(raw: Map<String, Array<String>>): FileTags {
    val map = raw.mapKeys { it.key.uppercase() }

    fun values(vararg keys: String): List<String>? = keys.firstNotNullOfOrNull { key ->
        map[key]?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }
    }
    fun first(vararg keys: String): String? = values(*keys)?.first()

    return FileTags(
        title = first("TITLE"),
        artist = values("ARTISTS", "ARTIST")?.joinToString(", "),
        albumArtist = values("ALBUMARTISTS", "ALBUMARTIST", "ALBUM ARTIST")?.joinToString(", "),
        album = first("ALBUM"),
        trackNo = positionNumber(first("TRACKNUMBER", "TRACK")),
        discNo = positionNumber(first("DISCNUMBER", "DISC")),
        year = yearOf(first("ORIGINALDATE", "DATE", "YEAR", "ORIGINALYEAR")),
        compilation = first("COMPILATION")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false,
        mbAlbumId = first("MUSICBRAINZ_ALBUMID"),
        genres = parseGenres(values("GENRE").orEmpty()),
    )
}

// "3", "03" and "3/12" all mean 3. Zero means not set.
fun positionNumber(text: String?): Int? =
    text?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

// "2015", "2015-09-04" and "20150904" all mean 2015.
fun yearOf(text: String?): Int? =
    text?.let { leadingYear.find(it)?.groupValues?.get(1)?.toInt() }?.takeIf { it in 1000..2999 }

// What joins several genres into one tag value. "&" is not one, so "R&B"
// and "Drum & Bass" stay whole.
private val genreSeparators = Regex("[;/,]")
private val spaces = Regex("""\s+""")
// Stands in for a name set aside while a value is split.
private val keptWhole = Regex("\u0000(\\d+)\u0000")

// Genre names that are commonly written with a separator inside them.
private val wholeGenres = listOf("Folk, World, & Country", "Hip-Hop/Rap", "R&B/Soul", "Singer/Songwriter")
    .map { Regex("""(?<![^;/,\s])${Regex.escape(it)}(?![^;/,\s])""", RegexOption.IGNORE_CASE) }

// A genre tag as clean names: several values, or one value joined by ";",
// "/" or ",", become a list in the order written. Repeats that differ only
// in case are dropped.
fun parseGenres(values: List<String>): List<String> =
    values.flatMap(::splitGenres)
        .map { it.replace(spaces, " ").trim() }
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)

// Splits one value, first setting aside the names that must stay whole.
private fun splitGenres(value: String): List<String> {
    val kept = mutableListOf<String>()
    // A null inside a value joins several values, so it splits like ";".
    val marked = wholeGenres.fold(value.replace('\u0000', ';')) { text, name ->
        name.replace(text) { match ->
            kept += match.value
            "\u0000${kept.lastIndex}\u0000"
        }
    }
    return marked.split(genreSeparators).map { piece ->
        keptWhole.replace(piece) { kept[it.groupValues[1].toInt()] }
    }
}
