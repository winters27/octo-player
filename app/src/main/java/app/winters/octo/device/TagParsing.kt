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
    )
}

// "3", "03" and "3/12" all mean 3. Zero means not set.
fun positionNumber(text: String?): Int? =
    text?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

// "2015", "2015-09-04" and "20150904" all mean 2015.
fun yearOf(text: String?): Int? =
    text?.let { leadingYear.find(it)?.groupValues?.get(1)?.toInt() }?.takeIf { it in 1000..2999 }
