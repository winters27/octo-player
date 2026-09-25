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
//   phone files often lack them.
// - The first source to have something gives it its id, so give the phone
//   first: its songs keep their ids, and likes and playlists keep working.
// - A merged song or album takes the best of its copies: a track number,
//   year or genre that one copy lacks comes from another.
// - Anything only one source has is passed through exactly as it was.
fun mergeCatalogs(sources: List<SourceCatalog>): MergedCatalog {
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
    for (source in sources) {
        // A song matches at most one copy from each source.
        val taken = HashSet<String>()
        for (track in source.tracks.sortedBy { it.albumOrder }) {
            val albumId = albumIdFor[track.albumId] ?: track.albumId
            fun fits(song: MergingSong) = song.id !in taken && song.sources.none { it == source.sourceId } &&
                matchKey(song.base.title) == matchKey(track.title) &&
                abs(song.base.durationMs - track.durationMs) <= SAME_LENGTH_MS
            // First within the matched album. Failing that, the same song by
            // the same artist anywhere: sources often file an album under a
            // different album artist or edition name.
            val match = inAlbum[albumId]?.firstOrNull(::fits)
                ?: everywhere[songKey(track)]?.firstOrNull(::fits)
            if (match != null) {
                match.add(track, source)
                taken += match.id
                mergedIds[track.id] = match.id
            } else {
                val song = MergingSong(track, source, albumId, artistIdFor[track.artistId] ?: track.artistId)
                inAlbum.getOrPut(albumId) { mutableListOf() } += song
                everywhere.getOrPut(songKey(track)) { mutableListOf() } += song
                mergedIds[track.id] = song.id
            }
        }
    }

    // Album order: an album filled from more than one source is put in
    // track number order when every song has one; otherwise its songs keep
    // the order they came in. Single-source albums keep their own order.
    val tracks = ArrayList<TrackEntity>(mergedIds.size)
    for ((_, songs) in inAlbum) {
        val mixed = songs.flatMap { it.sources }.toSet().size > 1
        val ordered = when {
            !mixed -> songs.map { it.toTrack(it.base.albumOrder) }
            songs.all { it.trackNo != null } ->
                songs.sortedWith(compareBy({ it.discNo ?: 1 }, { it.trackNo }))
                    .mapIndexed { index, song -> song.toTrack(index) }
            else -> songs.mapIndexed { index, song -> song.toTrack(index) }
        }
        tracks += ordered
    }
    val tracksByAlbum = tracks.groupBy { it.albumId }

    // An album whose songs all turned out to be copies of songs already in
    // another album has nothing left, and is left out.
    val albums = albumBases.bases.filter { it.id in tracksByAlbum }.map { base ->
        val extras = albumBases.extras[base.id].orEmpty()
        val albumTracks = tracksByAlbum.getValue(base.id)
        // Counts are only worked out again for albums whose songs changed:
        // made from several sources, or with songs gone to another album.
        val recount = extras.isNotEmpty() || albumTracks.size != base.songCount
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
            songCount = if (recount) albumTracks.size else base.songCount,
            durationMs = if (recount) albumTracks.sumOf { it.durationMs } else base.durationMs,
            addedAt = base.addedAt,
            artwork = base.artwork ?: extras.firstNotNullOfOrNull { it.artwork },
        )
    }

    // Artists from a later source with nothing left of their own are left
    // out too; the phone's artists always stay as they were.
    val firstSource = sources.firstOrNull()?.sourceId
    val artists = artistBases.bases.mapNotNull { base ->
        val several = artistBases.extras[base.id].orEmpty().isNotEmpty()
        val later = base.sourceId != firstSource
        val albumCount = albums.count { it.artistId == base.id }
        val songCount = tracks.count { it.artistId == base.id }
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

    return MergedCatalog(tracks, albums, artists, mergedIds)
}

// Things matched across sources: the first copy of each (the base, whose
// id the library uses), the other copies of it, and which base every copy
// belongs to.
private class Matched<T>(val bases: List<T>, val extras: Map<String, List<T>>, val idFor: Map<String, String>)

private fun <T> acrossSources(
    sources: List<SourceCatalog>,
    items: (SourceCatalog) -> List<T>,
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
private class MergingSong(
    val base: SourceTrackEntity,
    source: SourceCatalog,
    val albumId: String,
    val artistId: String,
) {
    val id = base.id
    val sources = mutableListOf(source.sourceId)
    var onPhone = source.onPhone
    var trackNo = base.trackNo
    var discNo = base.discNo
    var year = base.year
    var genre = base.genre

    fun add(copy: SourceTrackEntity, source: SourceCatalog) {
        sources += source.sourceId
        onPhone = onPhone || source.onPhone
        trackNo = trackNo ?: copy.trackNo
        discNo = discNo ?: copy.discNo
        year = year ?: copy.year
        if (genre.isEmpty()) genre = copy.genre
    }

    fun toTrack(order: Int) = TrackEntity(
        id = base.id,
        sourceId = base.sourceId,
        nativeId = base.nativeId,
        title = base.title,
        searchKey = base.searchKey,
        sortKey = base.sortKey,
        artist = base.artist,
        artistId = artistId,
        album = base.album,
        albumId = albumId,
        trackNo = trackNo,
        discNo = discNo,
        year = year,
        durationMs = base.durationMs,
        addedAt = base.addedAt,
        mimeType = base.mimeType,
        sizeBytes = base.sizeBytes,
        artwork = base.artwork,
        uri = base.uri,
        albumOrder = order,
        relinkKey = base.relinkKey,
        genre = genre,
        onPhone = onPhone,
    )
}

// A song's title and artist, for matching it outside its album.
private fun songKey(track: SourceTrackEntity) = matchKey(track.title) + "|" + matchKey(track.artist)

// A name for matching across sources: case, punctuation, spacing and
// bracketed extras such as "(feat. someone)" or "[Remastered]" are
// ignored.
internal fun matchKey(name: String): String =
    name.lowercase()
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .filter(Char::isLetterOrDigit)
