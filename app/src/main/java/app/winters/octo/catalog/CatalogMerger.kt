package app.winters.octo.catalog

import kotlin.math.abs

// Everything one source has.
data class SourceCatalog(
    val sourceId: String,
    // Whether the source is the phone's own files.
    val onPhone: Boolean,
    val tracks: List<SourceTrackEntity>,
    val albums: List<SourceAlbumEntity>,
    val artists: List<SourceArtistEntity>,
)

// The library as the screens read it, and which library song each source
// copy became.
data class MergedCatalog(
    val tracks: List<TrackEntity>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
    val mergedIds: Map<String, String>,
)

// Two copies of a song count as the same one if their lengths differ by
// no more than this.
private const val SAME_LENGTH_MS = 3_000L

// Merges the sources into one library, so something on the phone and on a
// server shows once.
//
// - Artists match by name, albums by album artist and title, and songs by
//   title and length within an album. Track numbers are not needed, since
//   phone files often lack them. A song's title is read by SongIdentity, so
//   "Song - Remastered 2011" is "Song", but "Song (Live)" stays apart.
// - The first source to have something gives it its id, so give the phone
//   first: its songs keep their ids, and likes and playlists keep working.
// - A merged song or album takes the best of its copies. Copies are asked
//   in source order, the phone's first, and for each field:
//   - added: the earliest real time any copy was added. A server stamps
//     every song with the day it first scanned it, while the phone's time
//     comes from the file, so the earliest is the nearest to when the song
//     was really got. Unknown times (0) are passed over. Albums alike.
//   - year: the first copy's original release year; failing that, the
//     first copy's own year. So a remaster shows the year the song came out.
//   - genre: the first copy's main genre; the song info sheet shows every
//     copy's genres together (songDetails).
//   - artist: the fullest credit, the copy naming the most artists (say,
//     "A, B" over "A"); on equal credits the first copy's stays.
//   - track and disc numbers and the server rating: the first copy's.
//   - explicit or clean: the first copy that says, so a server's mark
//     reaches a phone file whose tags say nothing.
//   - composer, tempo, MusicBrainz ids and the other details the library
//     does not keep: read from the copies when needed, by the same order
//     (songDetails).
// - Anything only one source has is passed through exactly as it was,
//   apart from its year, which is its original year when it has one.
fun mergeCatalogs(sources: List<SourceCatalog>): MergedCatalog {
    val plan = planMerge(sources.map { SourceRows(it.sourceId, it.onPhone, it.tracks.map(SourceTrackEntity::toMergeRow), it.albums, it.artists) })
    // The first source's row for an id is the base the plan was made from.
    val bases = HashMap<String, SourceTrackEntity>()
    for (source in sources) for (track in source.tracks) bases.putIfAbsent(track.id, track)
    return MergedCatalog(plan.songs.map { it.toTrack(bases.getValue(it.id)) }, plan.albums, plan.artists, plan.mergedIds)
}

// What matching needs from a source song, and nothing more: a library of
// 100,000 songs held as whole rows ran a phone out of memory. The rest of
// a song's row is read again, a few hundred at a time, when its library row
// is written (`PlannedSong.toTrack`).
data class MergeRow(
    val id: String,
    val albumId: String,
    val artistId: String,
    val title: String,
    val artist: String,
    val artists: String,
    val albumOrder: Int,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val originalYear: Int?,
    val genre: String,
    val rating: Int?,
    val explicit: Boolean?,
    val addedAt: Long,
    val durationMs: Long,
)

fun SourceTrackEntity.toMergeRow() = MergeRow(
    id, albumId, artistId, title, artist, artists, albumOrder, trackNo, discNo, year, originalYear, genre, rating, explicit, addedAt, durationMs,
)

// One source as matching sees it.
class SourceRows(
    val sourceId: String,
    // Whether the source is the phone's own files.
    val onPhone: Boolean,
    val tracks: List<MergeRow>,
    val albums: List<SourceAlbumEntity>,
    val artists: List<SourceArtistEntity>,
)

// The merged library before its songs are built: which songs it has, in
// what order, which library song every copy became, and the albums and
// artists, which need nothing more from the songs than their counts.
class MergePlan(
    val songs: List<PlannedSong>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
    val mergedIds: Map<String, String>,
)

// A library song that is decided but not built: its row needs the base
// copy's row, which `toTrack` is given.
class PlannedSong internal constructor(private val song: MergingSong, private val order: Int) {
    val id: String get() = song.id

    fun toTrack(base: SourceTrackEntity): TrackEntity = song.toTrack(base, order)
}

// Everything `mergeCatalogs` decides, with the same rules (see there), but
// for songs held as `MergeRow`s and with each library song left unbuilt.
fun planMerge(sources: List<SourceRows>): MergePlan {
    // Artists and albums match only across sources: two a source keeps
    // apart (say, two albums of the same name) stay apart.
    val artistBases = acrossSources(sources, { it.artists }, { it.id }, { matchKey(it.name) })
    val artistIdFor = artistBases.idFor
    val albumBases = acrossSources(sources, { it.albums }, { it.id }, { matchKey(it.artist) + "|" + matchKey(it.title) })
    val albumIdFor = albumBases.idFor

    // Songs, matched within each merged album
    val inAlbum = HashMap<String, MutableList<MergingSong>>()
    // Every song so far by title and artist, for the second chance below.
    val everywhere = HashMap<String, MutableList<MergingSong>>()
    val mergedIds = HashMap<String, String>()
    for ((position, source) in sources.withIndex()) {
        // Only a later source looks songs up by title and artist, so the last
        // source's songs, the biggest set when a server is the only one, are
        // not kept there.
        val laterSourceFollows = position < sources.lastIndex
        // A song matches at most one copy from each source.
        val taken = HashSet<String>()
        for (track in source.tracks.sortedBy { it.albumOrder }) {
            val albumId = albumIdFor[track.albumId] ?: track.albumId
            val trackKeys = SongIdentity.songKeys(track.artist, track.title)
            val matchKey = trackKeys.matchKey
            fun fits(song: MergingSong) = song.id !in taken && song.sources.none { it == source.sourceId } &&
                song.titleKey == trackKeys.title &&
                abs(song.durationMs - track.durationMs) <= SAME_LENGTH_MS
            // First within the matched album. Failing that, the same song by
            // the same artist anywhere: sources often file an album under a
            // different album artist or edition name.
            val match = inAlbum[albumId]?.firstOrNull(::fits)
                ?: everywhere[matchKey]?.firstOrNull(::fits)
            if (match != null) {
                match.add(track, source)
                taken += match.id
                mergedIds[track.id] = match.id
            } else {
                val song = MergingSong(track, source, albumId, artistIdFor[track.artistId] ?: track.artistId, trackKeys.title)
                inAlbum.getOrPut(albumId) { mutableListOf() } += song
                if (laterSourceFollows) everywhere.getOrPut(matchKey) { mutableListOf() } += song
                mergedIds[track.id] = song.id
            }
        }
    }

    // Album order: an album filled from more than one source is put in
    // track number order when every song has one; otherwise its songs keep
    // the order they came in. Single-source albums keep their own order.
    val songs = ArrayList<PlannedSong>(mergedIds.size)
    for ((_, group) in inAlbum) {
        val mixed = group.flatMap { it.sources }.toSet().size > 1
        val ordered = when {
            !mixed -> group.map { PlannedSong(it, it.albumOrder) }
            group.all { it.trackNo != null } ->
                group.sortedWith(compareBy({ it.discNo ?: 1 }, { it.trackNo }))
                    .mapIndexed { index, song -> PlannedSong(song, index) }
            else -> group.mapIndexed { index, song -> PlannedSong(song, index) }
        }
        songs += ordered
    }
    // How many songs and how long each album and artist has now, counted
    // once rather than once for each of them.
    val songsInAlbum = HashMap<String, Int>()
    val lengthOfAlbum = HashMap<String, Long>()
    val songsOfArtist = HashMap<String, Int>()
    for ((_, group) in inAlbum) {
        for (song in group) {
            songsInAlbum.merge(song.albumId, 1, Int::plus)
            lengthOfAlbum.merge(song.albumId, song.durationMs, Long::plus)
            songsOfArtist.merge(song.artistId, 1, Int::plus)
        }
    }

    // An album whose songs all turned out to be copies of songs already in
    // another album has nothing left, and is left out.
    val albums = albumBases.bases.filter { it.id in songsInAlbum }.map { base ->
        val extras = albumBases.extras[base.id].orEmpty()
        val songCount = songsInAlbum.getValue(base.id)
        // Counts are only worked out again for albums whose songs changed:
        // made from several sources, or with songs gone to another album.
        val recount = extras.isNotEmpty() || songCount != base.songCount
        AlbumEntity(
            id = base.id,
            sourceId = base.sourceId,
            nativeId = base.nativeId,
            title = base.title,
            searchKey = base.searchKey,
            sortKey = base.sortKey,
            artist = base.artist,
            artistId = artistIdFor[base.artistId] ?: base.artistId,
            year = base.year ?: extras.firstNotNullOfOrNull { it.year },
            songCount = if (recount) songCount else base.songCount,
            durationMs = if (recount) lengthOfAlbum.getValue(base.id) else base.durationMs,
            addedAt = earliestAdded(listOf(base.addedAt) + extras.map { it.addedAt }),
            artwork = base.artwork ?: extras.firstNotNullOfOrNull { it.artwork },
            releaseTypes = base.releaseTypes.ifEmpty { extras.firstOrNull { it.releaseTypes.isNotEmpty() }?.releaseTypes.orEmpty() },
        )
    }
    val albumsOfArtist = HashMap<String, Int>()
    for (album in albums) albumsOfArtist.merge(album.artistId, 1, Int::plus)

    // Artists from a later source with nothing left of their own are left
    // out too; the phone's artists always stay as they were.
    val firstSource = sources.firstOrNull()?.sourceId
    val artists = artistBases.bases.mapNotNull { base ->
        val several = artistBases.extras[base.id].orEmpty().isNotEmpty()
        val later = base.sourceId != firstSource
        val albumCount = albumsOfArtist[base.id] ?: 0
        val songCount = songsOfArtist[base.id] ?: 0
        if (later && albumCount == 0 && songCount == 0) return@mapNotNull null
        ArtistEntity(
            id = base.id,
            sourceId = base.sourceId,
            name = base.name,
            searchKey = base.searchKey,
            sortKey = base.sortKey,
            albumCount = if (several || later) albumCount else base.albumCount,
            songCount = if (several || later) songCount else base.songCount,
            artwork = base.artwork,
        )
    }

    return MergePlan(songs, albums, artists, mergedIds)
}

// Things matched across sources: the first copy of each (the base, whose
// id the library uses), the other copies of it, and which base every copy
// belongs to.
private class Matched<T>(val bases: List<T>, val extras: Map<String, List<T>>, val idFor: Map<String, String>)

private fun <T> acrossSources(
    sources: List<SourceRows>,
    items: (SourceRows) -> List<T>,
    id: (T) -> String,
    key: (T) -> String,
): Matched<T> {
    val bases = ArrayList<T>()
    val extras = HashMap<String, MutableList<T>>()
    val idFor = HashMap<String, String>()
    // Bases from earlier sources, by key, that no copy from the current
    // source has claimed yet.
    val earlier = HashMap<String, ArrayDeque<T>>()
    for (source in sources) {
        val added = HashMap<String, ArrayDeque<T>>()
        for (item in items(source)) {
            val k = key(item)
            val base = earlier[k]?.removeFirstOrNull()
            if (base != null) {
                idFor[id(item)] = id(base)
                extras.getOrPut(id(base)) { mutableListOf() } += item
            } else {
                bases += item
                idFor[id(item)] = id(item)
                added.getOrPut(k) { ArrayDeque() } += item
            }
        }
        for ((k, list) in added) earlier.getOrPut(k) { ArrayDeque() } += list
    }
    return Matched(bases, extras, idFor)
}

// One library song while its copies are gathered.
internal class MergingSong(
    base: MergeRow,
    source: SourceRows,
    val albumId: String,
    val artistId: String,
    // The base copy's title as songs are matched by it.
    val titleKey: String,
) {
    val id = base.id
    val durationMs = base.durationMs
    val albumOrder = base.albumOrder
    val sources = mutableListOf(source.sourceId)
    var onPhone = source.onPhone
    var trackNo = base.trackNo
    var discNo = base.discNo
    var year = base.year
    var originalYear = base.originalYear
    var genre = base.genre
    var rating = base.rating?.takeIf { it in 1..5 }
    var explicit = base.explicit
    var addedAt = base.addedAt
    var artist = base.artist
    var credits = splitLines(base.artists).size

    fun add(copy: MergeRow, source: SourceRows) {
        sources += source.sourceId
        onPhone = onPhone || source.onPhone
        trackNo = trackNo ?: copy.trackNo
        discNo = discNo ?: copy.discNo
        year = year ?: copy.year
        originalYear = originalYear ?: copy.originalYear
        if (genre.isEmpty()) genre = copy.genre
        rating = rating ?: copy.rating?.takeIf { it in 1..5 }
        explicit = explicit ?: copy.explicit
        addedAt = earliestAdded(listOf(addedAt, copy.addedAt))
        val copyCredits = splitLines(copy.artists).size
        if (copyCredits > credits && copy.artist.isNotBlank()) {
            artist = copy.artist
            credits = copyCredits
        }
    }

    fun toTrack(base: SourceTrackEntity, order: Int) = TrackEntity(
        id = base.id,
        sourceId = base.sourceId,
        nativeId = base.nativeId,
        title = base.title,
        searchKey = base.searchKey,
        sortKey = base.sortKey,
        artist = artist,
        artistId = artistId,
        album = base.album,
        albumId = albumId,
        trackNo = trackNo,
        discNo = discNo,
        year = originalYear ?: year,
        durationMs = base.durationMs,
        addedAt = addedAt,
        mimeType = base.mimeType,
        sizeBytes = base.sizeBytes,
        artwork = base.artwork,
        uri = base.uri,
        albumOrder = order,
        relinkKey = base.relinkKey,
        genre = genre,
        onPhone = onPhone,
        rating = rating ?: 0,
        explicit = explicit,
    )
}

// The earliest of some times added, leaving out unknown ones (0 or less).
internal fun earliestAdded(times: List<Long>): Long = times.filter { it > 0 }.minOrNull() ?: 0

private val Bracketed = Regex("""\s*[(\[][^)\]]*[)\]]""")

// A name for matching across sources as a whole, such as an album or an
// artist: case, accents, punctuation, spacing, lookalike letters and
// bracketed extras such as "(Deluxe)" or "[Remastered]" are ignored, and
// "&" reads as "and". Songs are matched by SongIdentity instead, which also
// knows versions and guests.
internal fun matchKey(name: String): String = SongIdentity.key(name.replace(Bracketed, " "))
