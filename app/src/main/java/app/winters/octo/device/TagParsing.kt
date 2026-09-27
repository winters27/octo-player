package app.winters.octo.device

import app.winters.octo.sound.ReplayGain

// The tags Octo uses from a file, already cleaned up.
data class FileTags(
    val title: String? = null,
    // Who the song is by as the file writes it, and each credited artist.
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    // This edition's year, and the year it first came out, when the file says.
    val year: Int? = null,
    val compilation: Boolean = false,
    val mbAlbumId: String? = null,
    // The first is the main one.
    val genres: List<String> = emptyList(),
    val originalYear: Int? = null,
    val artists: List<String> = emptyList(),
    val composer: String? = null,
    val bpm: Int? = null,
    val comment: String? = null,
    // True when marked explicit, false when marked clean.
    val explicit: Boolean? = null,
    val discTitle: String? = null,
    val mbRecordingId: String? = null,
    val mbReleaseGroupId: String? = null,
    val mbArtistIds: List<String> = emptyList(),
    // How the title, album and album artist are filed, like "Beatles, The".
    val sortTitle: String? = null,
    val sortAlbum: String? = null,
    val sortAlbumArtist: String? = null,
    // Loudness, in decibels and where 1 is full scale.
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
)

// Every name a tag goes by in the files Octo reads. The tag library turns
// most of them into one name (DATE for an MP3's TDRC or an MP4's ©day), but
// older files, other tag writers and free-form tags can still arrive under
// their own names, so each list takes all of them. Names are compared
// without case, spaces, underscores or other punctuation, so "ALBUM ARTIST",
// "ALBUMARTIST" and "album_artist" are one name.
private object Names {
    val title = names("TITLE", "TIT2", "©NAM")
    val artist = names("ARTIST", "TPE1", "©ART")
    val artists = names("ARTISTS")
    val albumArtist = names("ALBUMARTIST", "TPE2", "AART")
    val albumArtists = names("ALBUMARTISTS")
    val album = names("ALBUM", "TALB", "©ALB")
    val track = names("TRACKNUMBER", "TRACK", "TRCK", "TRKN")
    val trackTotal = names("TRACKTOTAL", "TOTALTRACKS")
    val disc = names("DISCNUMBER", "DISC", "TPOS", "DISK")
    val discTotal = names("DISCTOTAL", "TOTALDISCS")
    val date = names("DATE", "TDRC", "YEAR", "TYER", "©DAY", "RELEASEDATE", "TDRL", "RELEASEYEAR")
    val originalDate = names("ORIGINALDATE", "TDOR", "ORIGINALYEAR", "TORY", "ORIGYEAR", "ORIGINALRELEASEDATE")
    val genre = names("GENRE", "TCON", "©GEN", "GNRE")
    val compilation = names("COMPILATION", "TCMP", "CPIL")
    val titleSort = names("TITLESORT", "TSOT", "SONM")
    val artistSort = names("ARTISTSORT", "TSOP", "SOAR")
    val albumSort = names("ALBUMSORT", "TSOA", "SOAL")
    val albumArtistSort = names("ALBUMARTISTSORT", "TSO2", "SOAA")
    val recordingId = names("MUSICBRAINZ_TRACKID", "MUSICBRAINZ TRACK ID", "MUSICBRAINZ_RECORDINGID", "UFID:HTTP://MUSICBRAINZ.ORG")
    val albumId = names("MUSICBRAINZ_ALBUMID", "MUSICBRAINZ ALBUM ID")
    val releaseGroupId = names("MUSICBRAINZ_RELEASEGROUPID", "MUSICBRAINZ RELEASE GROUP ID")
    val artistId = names("MUSICBRAINZ_ARTISTID", "MUSICBRAINZ ARTIST ID")
    val bpm = names("BPM", "TBPM", "TMPO")
    val comment = names("COMMENT", "COMM", "©CMT", "DESCRIPTION")
    val composer = names("COMPOSER", "TCOM", "©WRT")
    val discTitle = names("DISCSUBTITLE", "TSST", "SETSUBTITLE")
    val explicit = names("ITUNESADVISORY", "RTNG", "EXPLICIT")
    val loudness = listOf(
        "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_PEAK",
        "R128_TRACK_GAIN", "R128_ALBUM_GAIN",
    )

    private fun names(vararg all: String) = all.map(::tagName)
}

// A tag's name for comparing: upper case, with no byte-order mark, no
// "----:com.apple.iTunes:" or "TXXX:" in front, and only letters, digits and
// the © that MP4 names start with.
internal fun tagName(raw: String): String {
    var name = raw.replace("\uFEFF", "").trim().uppercase()
    if (name.startsWith("----:")) name = name.substringAfterLast(':')
    name = name.removePrefix("TXXX:")
    return name.filter { it.isLetterOrDigit() || it == '©' }
}

// Reads a file's tag map (keys as the tag library names them, so the same
// names cover FLAC, MP3 and MP4). Where several tags can hold a value, the
// more specific one wins: ARTISTS over ARTIST for the list of artists.
fun parseTags(raw: Map<String, Array<String>>): FileTags {
    // Values by comparable name, cleaned, in the order the file had them.
    val map = LinkedHashMap<String, MutableList<String>>()
    for ((key, values) in raw) {
        map.getOrPut(tagName(key)) { mutableListOf() } += values.map(::cleanValue).filter(String::isNotEmpty)
    }

    fun values(names: List<String>): List<String>? =
        names.firstNotNullOfOrNull { name -> map[name]?.takeIf { it.isNotEmpty() } }
    fun first(names: List<String>): String? = values(names)?.first()?.substringBefore('\u0000')?.trim()?.takeIf(String::isNotEmpty)
    // Several values can also arrive joined by a null in one value.
    fun list(names: List<String>): List<String>? =
        values(names)?.flatMap { it.split('\u0000') }?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.takeIf { it.isNotEmpty() }

    val artistValues = list(Names.artist)
    val albumArtistValues = list(Names.albumArtist)
    val track = position(first(Names.track))
    val disc = position(first(Names.disc))
    val year = yearOf(first(Names.date))
    val originalYear = yearOf(first(Names.originalDate))
    val gains = ReplayGain.fromTags(Names.loudness.flatMap { name -> map[tagName(name)].orEmpty().map { name to it } })

    return FileTags(
        title = first(Names.title),
        artist = artistValues?.joinToString(", ") ?: list(Names.artists)?.joinToString(", "),
        albumArtist = albumArtistValues?.joinToString(", ") ?: list(Names.albumArtists)?.joinToString(", "),
        album = first(Names.album),
        trackNo = track.number,
        discNo = disc.number,
        year = year,
        compilation = first(Names.compilation)?.let(::isYes) ?: false,
        mbAlbumId = musicIds(values(Names.albumId)).firstOrNull(),
        genres = parseGenres(values(Names.genre).orEmpty()),
        // An original release cannot come after this edition; one that says
        // so is a mistake.
        originalYear = originalYear?.takeIf { year == null || it <= year },
        artists = list(Names.artists) ?: artistValues.orEmpty(),
        composer = list(Names.composer)?.joinToString(", "),
        bpm = first(Names.bpm)?.let(::bpmOf),
        comment = first(Names.comment),
        explicit = first(Names.explicit)?.let(::explicitOf),
        discTitle = first(Names.discTitle),
        mbRecordingId = musicIds(values(Names.recordingId)).firstOrNull(),
        mbReleaseGroupId = musicIds(values(Names.releaseGroupId)).firstOrNull(),
        mbArtistIds = musicIds(values(Names.artistId)),
        sortTitle = first(Names.titleSort),
        sortAlbum = first(Names.albumSort),
        // With no album-artist tag, the album is filed under its artist.
        sortAlbumArtist = first(Names.albumArtistSort) ?: first(Names.artistSort).takeIf { albumArtistValues == null },
        trackGain = gains?.trackGain,
        albumGain = gains?.albumGain,
        trackPeak = gains?.trackPeak,
        albumPeak = gains?.albumPeak,
    )
}

// A value without a byte-order mark, surrounding spaces or trailing nulls.
private fun cleanValue(value: String): String = value.replace("\uFEFF", "").trim { it.isWhitespace() || it == '\u0000' }

// A place in a set and the set's size, either of which may be unknown.
data class Position(val number: Int?, val total: Int?)

// "3", "03", "3/12", "3 of 12" and "(3)" all mean 3. Zero means not set.
// A vinyl side like "A1" is not a number in the whole album, so it is not one.
private val positionText = Regex("""^[^\p{L}\d]*(\d+)\s*(?:(?:/|of)\s*(\d+))?""", RegexOption.IGNORE_CASE)

fun position(text: String?): Position {
    val match = text?.let { positionText.find(cleanValue(it)) } ?: return Position(null, null)
    val (number, total) = match.destructured
    return Position(number.toIntOrNull()?.takeIf { it > 0 }, total.toIntOrNull()?.takeIf { it > 0 })
}

fun positionNumber(text: String?): Int? = position(text).number

private val leadingYear = Regex("""^\D*(\d{4})""")
private val anyYear = Regex("""(?<!\d)(\d{4})(?!\d)""")

// The year in a date, however it is written: "2017", "2017-06-16",
// "2017-06", "20170616", "2017/06/16", "(2017)", "2017-06-16T10:00:00Z",
// and a year at the end, as in "16/06/2017".
fun yearOf(text: String?): Int? {
    val clean = text?.let(::cleanValue) ?: return null
    val found = leadingYear.find(clean) ?: anyYear.find(clean) ?: return null
    return found.groupValues[1].toInt().takeIf { it in 1000..2999 }
}

// "1", "true" and "yes" all mean yes.
private fun isYes(text: String) = text == "1" || text.equals("true", ignoreCase = true) || text.equals("yes", ignoreCase = true)

// Beats per minute, rounded: "120", "120.5", "128 BPM". Zero means not set.
fun bpmOf(text: String): Int? =
    Regex("""\d+(?:[.,]\d+)?""").find(text)?.value?.replace(',', '.')?.toDoubleOrNull()
        ?.let { Math.round(it).toInt() }?.takeIf { it in 1..999 }

// The explicit mark: iTunes writes 1 or 4 for explicit and 2 for clean;
// others write the word.
fun explicitOf(text: String): Boolean? = when (text.trim().lowercase()) {
    "1", "4", "explicit", "e", "true", "yes" -> true
    "2", "clean", "c" -> false
    else -> null
}

private val musicId = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

// Every MusicBrainz id in some values, however they are joined, in lower case.
fun musicIds(values: List<String>?): List<String> =
    values.orEmpty().flatMap { value -> musicId.findAll(value).map { it.value.lowercase() }.toList() }.distinct()

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
// "/" or ",", become a list in the order written. A number is an old-style
// genre number ("17" is Rock). Repeats that differ only in case are dropped.
fun parseGenres(values: List<String>): List<String> =
    values.map(::id3Genre).flatMap(::splitGenres)
        .map { id3Genre(it).replace(spaces, " ").trim() }
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)

// Splits one value, first setting aside the names that must stay whole.
private fun splitGenres(value: String): List<String> {
    val kept = mutableListOf<String>()
    // A null inside a value joins several values, so it splits like ";".
    val marked = wholeGenres.fold(cleanValue(value).replace('\u0000', ';')) { text, name ->
        name.replace(text) { match ->
            kept += match.value
            "\u0000${kept.lastIndex}\u0000"
        }
    }
    return marked.split(genreSeparators).map { piece ->
        keptWhole.replace(piece) { kept[it.groupValues[1].toInt()] }
    }
}

private val numberedGenres = Regex("""^\s*((?:\((?:\d{1,3}|RX|CR)\))+)(.*)$""")
private val genreNumber = Regex("""\((\d{1,3}|RX|CR)\)""")

// An old-style genre: "17" and "(17)" are Rock, "(RX)" is Remix, and in
// "(17)Rock and Roll" the words after the number win. Anything else is kept.
fun id3Genre(value: String): String {
    val text = cleanValue(value)
    text.toIntOrNull()?.let { number -> return id3Genres.getOrNull(number) ?: text }
    val match = numberedGenres.find(text) ?: return text
    val (numbers, rest) = match.destructured
    if (rest.isNotBlank()) return rest.trim()
    return genreNumber.findAll(numbers).map { it.groupValues[1] }.mapNotNull { code ->
        when (code) {
            "RX" -> "Remix"
            "CR" -> "Cover"
            else -> id3Genres.getOrNull(code.toInt())
        }
    }.joinToString(";")
}

// The genre numbers older MP3 tags use, 0 to 191.
private val id3Genres = listOf(
    "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
    "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
    "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz-Funk",
    "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
    "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
    "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
    "Top 40", "Christian Rap", "Pop-Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
    "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    "Folk", "Folk Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic", "Bluegrass",
    "Avant-garde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
    "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove",
    "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
    "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House", "Dancehall", "Goa", "Drum & Bass", "Club-House", "Hardcore Techno",
    "Terror", "Indie", "Britpop", "Worldbeat", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
    "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "J-Pop", "Synth-pop", "Abstract", "Art Rock",
    "Baroque", "Bhangra", "Big Beat", "Breakbeat", "Chillout", "Downtempo", "Dub", "EBM", "Eclectic", "Electro",
    "Electroclash", "Emo", "Experimental", "Garage", "Global", "IDM", "Illbient", "Industro-Goth", "Jam Band", "Krautrock",
    "Leftfield", "Lounge", "Math Rock", "New Romantic", "Nu-Breakz", "Post-Punk", "Post-Rock", "Psytrance", "Shoegaze", "Space Rock",
    "Trop Rock", "World Music", "Neoclassical", "Audiobook", "Audio Theatre", "Neue Deutsche Welle", "Podcast", "Indie Rock", "G-Funk", "Dubstep",
    "Garage Rock", "Psybient",
)

// Which genre a name belongs to, so the ways of writing one genre fold
// together. Case, spaces and punctuation are ignored ("Hip Hop", "Hip-Hop",
// "hiphop"), "&" counts as "and", and a few genres commonly written several
// ways share one key.
fun genreKey(name: String): String {
    val bare = name.lowercase().replace("&", "and").filter(Char::isLetterOrDigit)
    return genreFamilies[bare] ?: bare
}

private val genreFamilies: Map<String, String> = listOf(
    "hiphop" to listOf("hiphoprap", "raphiphop", "rap", "hiphopandrap", "rapandhiphop"),
    "randb" to listOf("rnb", "rhythmandblues", "randbsoul", "rnbsoul"),
    "electronic" to listOf("electronica", "électronique"),
    "alternative" to listOf("alternativeandindie", "indieandalternative", "alternativeindie", "indiealternative", "alternatifetindé"),
).flatMap { (key, names) -> names.map { it to key } }.toMap()
