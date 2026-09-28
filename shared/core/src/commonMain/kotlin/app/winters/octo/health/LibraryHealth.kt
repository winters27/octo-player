package app.winters.octo.health

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SongMatchOptions
import app.winters.octo.catalog.SongRef
import app.winters.octo.query.isLosslessFormat
import app.winters.octo.subsonic.Song
import java.util.stream.IntStream
import kotlin.math.abs

// Library health: plain checks over the library's songs that find what is
// worth fixing in the files. Nothing is guessed from outside the library;
// the same songs always give the same report. Both apps run it away from
// the window's thread.

// A tag the checks look for.
enum class HealthTag { Genre, Year, AlbumArtist, TrackNumber, Cover }

// What the checks read from a song. Each app reads its own kind of song.
interface HealthFields<T> {
    fun id(song: T): String
    fun title(song: T): String
    fun artist(song: T): String?
    fun album(song: T): String?
    fun albumId(song: T): String?
    fun albumArtist(song: T): String?
    fun genres(song: T): List<String>
    fun year(song: T): Int?
    fun disc(song: T): Int?
    fun track(song: T): Int?
    fun seconds(song: T): Int
    fun format(song: T): String?
    fun lossless(song: T): Boolean

    // Kilobits per second, bits per sample, samples per second.
    fun bitRate(song: T): Int?
    fun bitDepth(song: T): Int?
    fun sampleRate(song: T): Int?

    // Codes that name the recording: its ISRCs and its MusicBrainz
    // recording id.
    fun isrcs(song: T): List<String>
    fun recordingId(song: T): String?
    fun cover(song: T): String?

    // Where the song is kept (a server, the phone). Copies in two places
    // are not duplicates, and an album is never split across places.
    fun place(song: T): String = ""

    // The tags this app can see at all. One it never keeps is never
    // reported missing.
    val seen: Set<HealthTag> get() = HealthTag.entries.toSet()
}

// A server's songs, as the desktop keeps them.
object SubsonicHealth : HealthFields<Song> {
    override fun id(song: Song) = song.id
    override fun title(song: Song) = song.title
    override fun artist(song: Song) = song.artist ?: song.displayArtist
    override fun album(song: Song) = song.album
    override fun albumId(song: Song) = song.albumId
    override fun albumArtist(song: Song) = song.displayAlbumArtist?.takeIf(String::isNotBlank) ?: song.albumArtists.firstOrNull()?.name
    override fun genres(song: Song) = song.genres.ifEmpty { listOfNotNull(song.genre?.takeIf(String::isNotBlank)) }
    override fun year(song: Song) = song.year
    override fun disc(song: Song) = song.discNumber
    override fun track(song: Song) = song.track
    override fun seconds(song: Song) = song.duration
    override fun format(song: Song) = song.suffix?.lowercase()?.takeIf(String::isNotBlank)
    override fun lossless(song: Song) = isLosslessFormat(song.suffix, song.bitDepth)
    override fun bitRate(song: Song) = song.bitRate
    override fun bitDepth(song: Song) = song.bitDepth
    override fun sampleRate(song: Song) = song.samplingRate
    override fun isrcs(song: Song) = song.isrc
    override fun recordingId(song: Song) = song.musicBrainzId
    override fun cover(song: Song) = song.coverArt
}

// Why copies were taken for one recording.
enum class DuplicateBasis {
    // Their tags name the same recording: an ISRC or a MusicBrainz id.
    Tags,

    // The same title and artist, in the same version, and lengths within
    // a few seconds.
    TitleAndLength,
}

// Why the first copy of a set is the one to keep.
enum class BestReason {
    // It sounds better: lossless, more bits, a higher rate.
    Sound,

    // The copies sound alike, and it has more of its tags.
    Tags,

    // Nothing sets them apart.
    None,
}

// Copies of one recording, the best first.
data class DuplicateGroup<T>(val copies: List<T>, val basis: DuplicateBasis, val bestReason: BestReason) {
    val best: T get() = copies.first()
}

// One way the parts of a split album differ, with each part's value.
enum class AlbumDifference { Title, AlbumArtist, Year, Other }

data class AlbumDifferenceValues(val kind: AlbumDifference, val values: List<String>)

// One album entry the server shows, and its songs in album order.
data class AlbumPart<T>(val albumId: String, val songs: List<T>)

// An album the server shows as two or more, because its songs' tags do
// not agree.
data class SplitAlbum<T>(
    val title: String,
    val artist: String,
    val parts: List<AlbumPart<T>>,
    val differences: List<AlbumDifferenceValues>,
)

// What one check is about. The order is the order they are shown in.
enum class HealthCheck {
    Duplicates,
    SplitAlbums,
    NoLength,
    NoTrackNumber,
    NoAlbumArtist,
    NoCover,
    NoYear,
    NoGenre,
    ;

    val tag: HealthTag?
        get() = when (this) {
            NoTrackNumber -> HealthTag.TrackNumber
            NoAlbumArtist -> HealthTag.AlbumArtist
            NoCover -> HealthTag.Cover
            NoYear -> HealthTag.Year
            NoGenre -> HealthTag.Genre
            else -> null
        }
}

// Everything the checks found.
data class HealthReport<T>(
    // How many songs were looked at.
    val checked: Int,
    val duplicates: List<DuplicateGroup<T>> = emptyList(),
    val splitAlbums: List<SplitAlbum<T>> = emptyList(),
    val noLength: List<T> = emptyList(),
    // Songs missing each tag, only the tags some song is missing.
    val missing: Map<HealthTag, List<T>> = emptyMap(),
) {
    // How many things a check found: sets of copies, albums, or songs.
    fun count(check: HealthCheck): Int = when (check) {
        HealthCheck.Duplicates -> duplicates.size
        HealthCheck.SplitAlbums -> splitAlbums.size
        HealthCheck.NoLength -> noLength.size
        else -> missing[check.tag]?.size ?: 0
    }

    // The checks that found something, in order.
    val findings: List<HealthCheck> get() = HealthCheck.entries.filter { count(it) > 0 }

    val clean: Boolean get() = findings.isEmpty()

    // The songs a check is about, for a list: each set of copies together
    // with the best first, each split album's parts one after the other,
    // and missing tags by artist and album.
    fun songs(check: HealthCheck): List<T> = when (check) {
        HealthCheck.Duplicates -> duplicates.flatMap { it.copies }
        HealthCheck.SplitAlbums -> splitAlbums.flatMap { album -> album.parts.flatMap { it.songs } }
        HealthCheck.NoLength -> noLength
        else -> missing[check.tag].orEmpty()
    }

    // The report without some songs, as after they were removed from the
    // library: a set left with one copy is no longer a duplicate.
    fun without(ids: Set<String>, fields: HealthFields<T>): HealthReport<T> {
        if (ids.isEmpty()) return this
        fun keep(song: T) = fields.id(song) !in ids
        return copy(
            duplicates = duplicates.mapNotNull { group ->
                val left = group.copies.filter(::keep)
                if (left.size < 2) null else group.copy(copies = left)
            },
            splitAlbums = splitAlbums.mapNotNull { album ->
                val parts = album.parts.map { it.copy(songs = it.songs.filter(::keep)) }.filter { it.songs.isNotEmpty() }
                if (parts.size < 2) null else album.copy(parts = parts)
            },
            noLength = noLength.filter(::keep),
            missing = missing.mapValues { (_, songs) -> songs.filter(::keep) }.filterValues { it.isNotEmpty() },
        )
    }
}

// Lengths of copies of one recording may be this far apart, in seconds.
const val SAME_LENGTH_SECONDS = SongIdentity.LENGTH_TOLERANCE_SECONDS

// A shared ISRC or MusicBrainz id counts only when the lengths are this
// close, so an album whose every song was tagged with one code by mistake
// is not taken for one song many times.
const val TAGGED_LENGTH_SECONDS = 10

private val DuplicateTitles = SongMatchOptions(lengthToleranceSeconds = SAME_LENGTH_SECONDS, extrasMustAgree = true)

// Runs every check over the songs.
fun <T> checkLibrary(songs: List<T>, fields: HealthFields<T>): HealthReport<T> = HealthReport(
    checked = songs.size,
    duplicates = findDuplicates(songs, fields),
    splitAlbums = findSplitAlbums(songs, fields),
    noLength = songs.filter { fields.seconds(it) <= 0 },
    missing = findMissingTags(songs, fields),
)

// Songs that are the same recording, in sets. A set's copies are all in
// one place. Songs whose tags name one recording (an ISRC or a MusicBrainz
// recording id, with lengths close) go together first; then songs by the
// same artist whose titles read the same, version and all, and whose
// lengths are within a few seconds.
fun <T> findDuplicates(songs: List<T>, fields: HealthFields<T>): List<DuplicateGroup<T>> {
    val sets = Sets(songs.size)
    val byTag = HashMap<String, Int>(songs.size / 4)
    val byTitle = HashMap<String, MutableList<Int>>(songs.size)
    val keys = matchKeys(songs, fields)
    val tagged = BooleanArray(songs.size)

    songs.forEachIndexed { index, song ->
        val place = fields.place(song)
        val codes = SongIdentity.isrcs(fields.isrcs(song)).map { "i:$it" } +
            listOfNotNull(fields.recordingId(song)?.trim()?.lowercase()?.takeIf(String::isNotEmpty)?.let { "m:$it" })
        for (code in codes) {
            val key = "$place\u0000$code"
            val first = byTag[key]
            if (first == null) {
                byTag[key] = index
            } else if (lengthsClose(fields.seconds(songs[first]), fields.seconds(song), TAGGED_LENGTH_SECONDS)) {
                if (sets.join(first, index)) {
                    tagged[first] = true
                    tagged[index] = true
                }
            }
        }
        val match = keys[index]
        // A title with nothing to read in it is never matched on its words.
        if (match.substringAfter('|').isNotEmpty()) byTitle.getOrPut("$place\u0000$match") { ArrayList(2) } += index
    }

    for (bucket in byTitle.values) {
        if (bucket.size < 2) continue
        for (i in bucket.indices) {
            val a = songs[bucket[i]]
            val refA = refOf(a, fields)
            for (j in i + 1 until bucket.size) {
                if (sets.same(bucket[i], bucket[j])) continue
                val b = songs[bucket[j]]
                if (!lengthsClose(fields.seconds(a), fields.seconds(b), SAME_LENGTH_SECONDS)) continue
                if (SongIdentity.same(refA, refOf(b, fields), DuplicateTitles).isSame) sets.join(bucket[i], bucket[j])
            }
        }
    }

    val groups = HashMap<Int, MutableList<Int>>()
    for (index in songs.indices) {
        if (sets.size(index) > 1) groups.getOrPut(sets.root(index)) { ArrayList(2) } += index
    }
    val order = compareByDescending<T> { soundOf(it, fields) }.thenByDescending { tagsOf(it, fields) }.thenBy { fields.id(it) }
    return groups.values.map { members ->
        val copies = members.map(songs::get).sortedWith(order)
        val basis = if (members.any { tagged[it] }) DuplicateBasis.Tags else DuplicateBasis.TitleAndLength
        DuplicateGroup(copies, basis, bestReason(copies, fields))
    }.sortedWith(compareBy<DuplicateGroup<T>> { sortKey(fields.artist(it.best)) }.thenBy { sortKey(fields.title(it.best)) }.thenBy { fields.id(it.best) })
}

// Each song's key for "this song in this version". Reading a title is the
// slow part of the checks, so each artist and title pair is read once, on
// every core.
private fun <T> matchKeys(songs: List<T>, fields: HealthFields<T>): Array<String> {
    val pairs = HashMap<Pair<String?, String>, Int>(songs.size)
    val at = IntArray(songs.size) { pairs.getOrPut(fields.artist(songs[it]) to fields.title(songs[it])) { pairs.size } }
    val distinct = arrayOfNulls<Pair<String?, String>>(pairs.size)
    pairs.forEach { (pair, index) -> distinct[index] = pair }
    val read = arrayOfNulls<String>(distinct.size)
    IntStream.range(0, distinct.size).parallel().forEach { i ->
        val pair = distinct[i]!!
        read[i] = SongIdentity.matchKey(pair.first, pair.second)
    }
    return Array(songs.size) { read[at[it]]!! }
}

private fun <T> refOf(song: T, fields: HealthFields<T>) =
    SongRef(fields.title(song), fields.artist(song), fields.seconds(song).takeIf { it > 0 }?.toDouble(), fields.isrcs(song))

// Within `tolerance` seconds, or a length unknown on either side.
private fun lengthsClose(a: Int, b: Int, tolerance: Int) = a <= 0 || b <= 0 || abs(a - b) <= tolerance

// How good a copy sounds, as one number that sorts: lossless first, then
// more bits per sample, a higher sample rate, and a higher bit rate. The
// bit rate of two lossless copies of one depth says only how hard they
// were packed, so it counts there only when the depth is unknown.
fun <T> soundOf(song: T, fields: HealthFields<T>): Long {
    val lossless = fields.lossless(song)
    val depth = (fields.bitDepth(song) ?: 0).coerceIn(0, 63)
    val rate = ((fields.sampleRate(song) ?: 0) / 100).coerceIn(0, 65_535)
    val bits = if (lossless && depth > 0) 0 else (fields.bitRate(song) ?: 0).coerceIn(0, 65_535)
    return ((if (lossless) 1L else 0L) shl 48) or (depth.toLong() shl 40) or (rate.toLong() shl 20) or bits.toLong()
}

// How many of the tags the checks look for a song has.
fun <T> tagsOf(song: T, fields: HealthFields<T>): Int =
    listOf(
        fields.genres(song).any(String::isNotBlank),
        (fields.year(song) ?: 0) > 0,
        !fields.albumArtist(song).isNullOrBlank(),
        (fields.track(song) ?: 0) > 0,
        !fields.cover(song).isNullOrBlank(),
    ).count { it }

private fun <T> bestReason(copies: List<T>, fields: HealthFields<T>): BestReason {
    val best = copies[0]
    val next = copies[1]
    return when {
        soundOf(best, fields) > soundOf(next, fields) -> BestReason.Sound
        tagsOf(best, fields) > tagsOf(next, fields) -> BestReason.Tags
        else -> BestReason.None
    }
}

// Albums the server shows as more than one: the same title by the same
// album artist, in one place, under two or more album ids, with years that
// do not tell them apart (the same year, or none on one side). Two albums
// of one name from different years stay apart.
fun <T> findSplitAlbums(songs: List<T>, fields: HealthFields<T>): List<SplitAlbum<T>> {
    val byAlbum = LinkedHashMap<String, MutableList<T>>()
    for (song in songs) {
        val id = fields.albumId(song)?.takeIf(String::isNotEmpty) ?: continue
        byAlbum.getOrPut(id) { ArrayList() } += song
    }
    val buckets = HashMap<String, MutableList<String>>()
    val years = HashMap<String, Set<Int>>()
    for ((id, members) in byAlbum) {
        val first = members[0]
        val title = SongIdentity.key(fields.album(first))
        if (title.isEmpty()) continue
        val artist = SongIdentity.key(SongIdentity.primaryArtist(albumArtistOf(members, fields)))
        if (artist.isEmpty()) continue
        buckets.getOrPut("${fields.place(first)}\u0000$title\u0000$artist") { ArrayList(1) } += id
        years[id] = members.mapNotNullTo(HashSet()) { fields.year(it)?.takeIf { y -> y > 0 } }
    }
    val found = ArrayList<SplitAlbum<T>>()
    for (ids in buckets.values) {
        if (ids.size < 2) continue
        // Parts whose years agree, or that lack one, belong together.
        val sets = Sets(ids.size)
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = years.getValue(ids[i])
            val b = years.getValue(ids[j])
            if (a.isEmpty() || b.isEmpty() || a.any(b::contains)) sets.join(i, j)
        }
        ids.indices.groupBy(sets::root).values.filter { it.size > 1 }.forEach { together ->
            val parts = together.map { i ->
                val id = ids[i]
                AlbumPart(id, byAlbum.getValue(id).sortedWith(albumOrder(fields)))
            }.sortedWith(compareByDescending<AlbumPart<T>> { it.songs.size }.thenBy { it.albumId })
            val lead = parts[0].songs
            found += SplitAlbum(
                title = fields.album(lead[0]).orEmpty(),
                artist = albumArtistOf(lead, fields),
                parts = parts,
                differences = differencesOf(parts, fields),
            )
        }
    }
    return found.sortedWith(compareBy<SplitAlbum<T>> { sortKey(it.artist) }.thenBy { sortKey(it.title) }.thenBy { it.parts[0].albumId })
}

// The album artist most of an album's songs name, or their artist.
private fun <T> albumArtistOf(songs: List<T>, fields: HealthFields<T>): String {
    val named = songs.mapNotNull { fields.albumArtist(it)?.takeIf(String::isNotBlank) }
    val pool = named.ifEmpty { songs.mapNotNull { fields.artist(it)?.takeIf(String::isNotBlank) } }
    return pool.groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key.orEmpty()
}

// What the parts disagree on, as each part says it.
private fun <T> differencesOf(parts: List<AlbumPart<T>>, fields: HealthFields<T>): List<AlbumDifferenceValues> {
    val found = ArrayList<AlbumDifferenceValues>()
    fun check(kind: AlbumDifference, of: (List<T>) -> String) {
        val values = parts.map { of(it.songs) }
        if (values.distinct().size > 1) found += AlbumDifferenceValues(kind, values)
    }
    check(AlbumDifference.Title) { songs -> fields.album(songs[0]).orEmpty() }
    check(AlbumDifference.AlbumArtist) { songs -> songs.mapNotNull { fields.albumArtist(it)?.takeIf(String::isNotBlank) }.distinct().sorted().joinToString(", ") }
    check(AlbumDifference.Year) { songs ->
        songs.mapNotNull { fields.year(it)?.takeIf { y -> y > 0 } }.distinct().sorted().joinToString(", ")
    }
    if (found.isEmpty()) found += AlbumDifferenceValues(AlbumDifference.Other, emptyList())
    return found
}

// Songs missing each tag the app can see. A track number and an album
// artist matter only on an album of two or more songs; a single needs
// neither.
fun <T> findMissingTags(songs: List<T>, fields: HealthFields<T>): Map<HealthTag, List<T>> {
    val albumSizes = HashMap<String, Int>()
    for (song in songs) fields.albumId(song)?.let { albumSizes[it] = (albumSizes[it] ?: 0) + 1 }
    fun onAlbum(song: T) = (fields.albumId(song)?.let(albumSizes::get) ?: 0) > 1
    val found = LinkedHashMap<HealthTag, List<T>>()
    val order = albumOrderAcross(fields)
    for (tag in HealthTag.entries) {
        if (tag !in fields.seen) continue
        val missing = songs.filter { song ->
            when (tag) {
                HealthTag.Genre -> fields.genres(song).none(String::isNotBlank)
                HealthTag.Year -> (fields.year(song) ?: 0) <= 0
                HealthTag.AlbumArtist -> onAlbum(song) && fields.albumArtist(song).isNullOrBlank()
                HealthTag.TrackNumber -> onAlbum(song) && (fields.track(song) ?: 0) <= 0
                HealthTag.Cover -> fields.cover(song).isNullOrBlank()
            }
        }
        if (missing.isNotEmpty()) found[tag] = missing.sortedWith(order)
    }
    return found
}

// Songs of one album in its order: disc, track, then title.
private fun <T> albumOrder(fields: HealthFields<T>): Comparator<T> =
    compareBy<T> { fields.disc(it) ?: 0 }.thenBy { fields.track(it)?.takeIf { t -> t > 0 } ?: Int.MAX_VALUE }.thenBy { sortKey(fields.title(it)) }.thenBy { fields.id(it) }

// Songs from many albums: by artist, then album, then album order.
private fun <T> albumOrderAcross(fields: HealthFields<T>): Comparator<T> =
    compareBy<T> { sortKey(fields.albumArtist(it) ?: fields.artist(it)) }.thenBy { sortKey(fields.album(it)) }.thenBy { fields.albumId(it).orEmpty() }.then(albumOrder(fields))

private fun sortKey(text: String?): String = text.orEmpty().trim().lowercase()

// Sets of songs joined as they are found to belong together, by index.
private class Sets(count: Int) {
    private val parent = IntArray(count) { it }
    private val sizes = IntArray(count) { 1 }

    fun root(i: Int): Int {
        var at = i
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }

    fun same(a: Int, b: Int) = root(a) == root(b)

    fun size(i: Int) = sizes[root(i)]

    // Joins the two sets; false when they were one already.
    fun join(a: Int, b: Int): Boolean {
        val x = root(a)
        val y = root(b)
        if (x == y) return false
        if (sizes[x] < sizes[y]) {
            parent[x] = y
            sizes[y] += sizes[x]
        } else {
            parent[y] = x
            sizes[x] += sizes[y]
        }
        return true
    }
}
