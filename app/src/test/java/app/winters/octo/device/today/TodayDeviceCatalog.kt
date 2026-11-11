package app.winters.octo.device.today

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.FileTagsEntity
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.joinLines
import app.winters.octo.catalog.relinkKey
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.catalog.toSource
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceFile
import app.winters.octo.device.DeviceRow
import app.winters.octo.device.FileTags
import app.winters.octo.device.MusicFolder
import app.winters.octo.device.genreKey
import app.winters.octo.device.savedTags
import app.winters.octo.device.toTags
import app.winters.octo.folders.pathNames
import java.security.MessageDigest

// A labeled pin: today's phone scan, frozen as the measure the paged scan
// must match row for row: which files are read again for their tags
// (DeviceLibrary.refreshTags), the rows each file becomes (DeviceFile.toRow),
// the builder (device/DeviceCatalogBuilder.kt), the folder rules and paths
// (device/Folders.kt) and the rows written for the phone's source, as they
// were at c2208eaa. The conversions between stored and read tags and to
// source rows are the app's own. Never edited to follow the app.

// What one scan makes of the phone's files: the files whose tags it reads
// again, the saved tags it forgets, the source rows it writes, and the
// folders, folder paths and file paths it keeps.
class TodayPhoneScan(
    val reread: List<Long>,
    val forgotten: Set<Long>,
    val tracks: List<SourceTrackEntity>,
    val albums: List<SourceAlbumEntity>,
    val artists: List<SourceArtistEntity>,
    val folders: List<MusicFolder>,
    val paths: Map<Long, List<String>>,
    val files: Map<Long, String>,
)

// Raised whenever reading tags learns something new, so every file is read
// again once.
private const val TAGS_VERSION = 2

// One scan of `files` (the phone's media library as listed) against the
// tags saved by the last (`saved`), reading a file's own tags with `read`,
// with the folders in `excluded` switched off.
fun todayPhoneScan(files: List<DeviceFile>, saved: List<FileTagsEntity>, read: (DeviceFile) -> FileTags?, excluded: Set<String>): TodayPhoneScan {
    val known = saved.associateBy { it.mediaId }
    val stale = files.filter { file ->
        val k = known[file.id]
        k == null || k.modifiedAt != file.modifiedAt || k.size != file.sizeBytes || k.tagsVersion != TAGS_VERSION
    }
    val fresh = stale.map { savedTags(it.id, it.modifiedAt, it.sizeBytes, read(it), TAGS_VERSION) }
    val gone = known.keys - files.map { it.id }.toSet()
    val tags = (known - gone + fresh.associateBy { it.mediaId }).filterValues { it.readOk }.mapValues { (_, t) -> t.toTags() }
    val included = files.withoutFolders(excluded)
    val catalog = buildDeviceCatalog(included.map { it.toRow(tags[it.id]) })
    return TodayPhoneScan(
        reread = stale.map { it.id },
        forgotten = gone,
        tracks = catalog.tracks,
        albums = catalog.albums.map { it.toSource() },
        artists = catalog.artists.map { it.toSource() },
        folders = files.folders(excluded),
        paths = included.folderPaths(),
        files = included.associate { it.id to relativeFile(it.folder, it.fileName) },
    )
}

// Prefers the file's own tags, falling back to the phone's reading for
// anything the file did not say.
fun DeviceFile.toRow(tags: FileTags?): DeviceRow = DeviceRow(
    id = id,
    uri = uri,
    fileName = fileName,
    folder = folder,
    title = tags?.title ?: fallback.title,
    artist = tags?.artist ?: fallback.artist,
    albumArtist = tags?.albumArtist ?: fallback.albumArtist,
    album = tags?.album ?: fallback.album,
    track = tags?.trackNo ?: fallback.trackNo,
    disc = tags?.discNo ?: fallback.discNo,
    year = tags?.year ?: fallback.year,
    compilation = tags?.compilation ?: false,
    mbAlbumId = tags?.mbAlbumId,
    genres = tags?.genres?.takeIf { it.isNotEmpty() } ?: fallback.genres,
    durationMs = durationMs,
    addedAtSeconds = addedAtSeconds,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    tags = tags ?: FileTags(),
)

// Folders are grouped by their top level, like "Music" or "Download", so a
// big library of artist and album folders still shows as a short list.
fun topFolder(relativePath: String?): String =
    relativePath?.trim('/')?.substringBefore('/').orEmpty()

// Leaves out files in folders someone switched off.
fun List<DeviceFile>.withoutFolders(excluded: Set<String>): List<DeviceFile> =
    if (excluded.isEmpty()) this else filterNot { topFolder(it.folder) in excluded }

// Lists every folder music was found in, including switched-off ones, so
// they can be switched back on.
fun List<DeviceFile>.folders(excluded: Set<String>): List<MusicFolder> =
    groupingBy { topFolder(it.folder) }.eachCount()
        .map { (name, count) -> MusicFolder(name, count, name !in excluded) }
        .sortedByDescending { it.songs }

// A file's path from the top of its storage, like "Music/Kavinsky/01.mp3".
fun relativeFile(folder: String?, fileName: String): String =
    listOfNotNull(folder?.trim('/')?.takeIf(String::isNotEmpty), fileName).joinToString("/")

// The phone's main storage, as the media library names it.
private const val MAIN_STORAGE = "external_primary"

// Where each file sits, as folder names, by media id. The storage it is on
// leads the path only when music is on more than one, so a phone with no
// SD card starts at "Music" rather than "Internal storage".
fun List<DeviceFile>.folderPaths(): Map<Long, List<String>> {
    val volumes = mapTo(HashSet()) { it.volume ?: MAIN_STORAGE }
    val cards = volumes - MAIN_STORAGE
    return associate { file ->
        val names = pathNames(file.folder)
        file.id to if (volumes.size > 1) listOf(storageName(file.volume, cards.size)) + names else names
    }
}

// What to call a storage: the phone's own, or an SD card, told apart by its
// id when there is more than one card.
fun storageName(volume: String?, cardCount: Int): String = when {
    volume == null || volume == MAIN_STORAGE -> "Internal storage"
    cardCount <= 1 -> "SD card"
    else -> "SD card ${volume.uppercase()}"
}

private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"
private const val VARIOUS_ARTISTS = "Various Artists"

class DeviceCatalog(
    val tracks: List<SourceTrackEntity>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
)

// A leading track number in a file name: "04 Title", "04. Title", "04 - Title".
private val fileNumber = Regex("""^\s*(\d{1,3})(?=\s*[-._)]|\s)""")

private fun String?.orNull() = this?.takeIf(String::isNotBlank)

// Turns the phone's files into albums and artists.
//
// Albums are grouped by name, not by the phone's album id, which follows
// the folder when a file has no album-artist tag and so splits one album
// kept in two folders. A same-named group is only split apart when every
// file in it says which release it belongs to (a MusicBrainz id) or who the
// album is by (an album artist); a partly tagged group stays together.
fun buildDeviceCatalog(rows: List<DeviceRow>): DeviceCatalog {
    val groups = rows
        .groupBy { searchKey(albumName(it)) }
        .flatMap { (nameKey, cluster) -> splitCluster(cluster).map { (splitKey, group) -> Triple(nameKey, splitKey, group) } }

    val tracks = mutableListOf<SourceTrackEntity>()
    val albums = mutableListOf<AlbumEntity>()
    val genreNames = genreSpellings(rows)

    for ((nameKey, splitKey, group) in groups) {
        val albumId = "$DEVICE:album:${stableId("$nameKey|$splitKey")}"
        val title = mostCommon(group.map(::albumName))
        val artist = albumArtistOf(group)
        val artistId = artistIdOf(artist)
        val ordered = inAlbumOrder(group)
        val artwork = ArtworkRef.Device("album:$albumId", ordered.first().first.uri).encode()

        ordered.forEachIndexed { index, (row, number) ->
            val trackTitle = row.title.orNull() ?: row.fileName.substringBeforeLast('.').ifBlank { "Untitled" }
            val tags = row.tags
            tracks += SourceTrackEntity(
                id = "$DEVICE:${row.id}",
                sourceId = DEVICE,
                nativeId = row.id.toString(),
                title = trackTitle,
                searchKey = searchKey(trackTitle),
                sortKey = sortKey(tags.sortTitle.orNull() ?: trackTitle),
                artist = row.artist.orNull() ?: artist,
                artistId = artistId,
                album = title,
                albumId = albumId,
                trackNo = number,
                discNo = row.disc,
                year = row.year,
                durationMs = row.durationMs,
                addedAt = row.addedAtSeconds,
                mimeType = row.mimeType,
                sizeBytes = row.sizeBytes,
                artwork = artwork,
                uri = row.uri,
                albumOrder = index,
                relinkKey = relinkKey(artist, title, row.disc, number, trackTitle, row.durationMs),
                genre = row.genres.firstOrNull()?.let { genreNames.getValue(genreKey(it)) } ?: "",
                trackGain = tags.trackGain,
                albumGain = tags.albumGain,
                trackPeak = tags.trackPeak,
                albumPeak = tags.albumPeak,
                originalYear = tags.originalYear,
                genres = joinLines(row.genres.map { genreNames.getValue(genreKey(it)) }.distinct()),
                artists = joinLines(tags.artists),
                composer = tags.composer,
                bpm = tags.bpm,
                comment = tags.comment,
                explicit = tags.explicit,
                discTitle = tags.discTitle,
                mbRecordingId = tags.mbRecordingId,
                mbAlbumId = row.mbAlbumId,
                mbReleaseGroupId = tags.mbReleaseGroupId,
                mbArtistIds = joinLines(tags.mbArtistIds),
            )
        }

        albums += AlbumEntity(
            id = albumId,
            sourceId = DEVICE,
            nativeId = stableId("$nameKey|$splitKey"),
            title = title,
            searchKey = searchKey("$title $artist"),
            sortKey = sortKey(group.firstNotNullOfOrNull { it.tags.sortAlbum.orNull() } ?: title),
            artist = artist,
            artistId = artistId,
            // The year it shows: the original release year where known.
            year = group.mapNotNull { it.tags.originalYear ?: it.year }.maxOrNull(),
            songCount = group.size,
            durationMs = group.sumOf { it.durationMs },
            addedAt = group.maxOf { it.addedAtSeconds },
            artwork = artwork,
        )
    }

    // How each album artist is filed, where a file says ("Beatles, The").
    val artistSorts = groups.mapNotNull { (_, _, group) ->
        group.firstNotNullOfOrNull { it.tags.sortAlbumArtist.orNull() }?.let { artistIdOf(albumArtistOf(group)) to it }
    }.toMap()

    val artists = albums.groupBy { it.artistId }.map { (id, owned) ->
        val name = mostCommon(owned.map { it.artist })
        ArtistEntity(
            id = id,
            sourceId = DEVICE,
            name = name,
            searchKey = searchKey(name),
            sortKey = sortKey(artistSorts[id] ?: name),
            albumCount = owned.size,
            songCount = owned.sumOf { it.songCount },
            artwork = owned.maxByOrNull { it.addedAt }?.artwork,
        )
    }

    return DeviceCatalog(tracks, albums, artists)
}

// The album a file belongs to by name; with no album tag, its folder's name.
private fun albumName(row: DeviceRow): String =
    row.album.orNull()
        ?: row.folder?.trimEnd('/')?.substringAfterLast('/').orNull()
        ?: UNKNOWN_ALBUM

// Splits one same-named group only when every file in it can say which
// album it is. Otherwise the whole group is one album.
private fun splitCluster(cluster: List<DeviceRow>): Map<String, List<DeviceRow>> = when {
    cluster.all { it.mbAlbumId.orNull() != null } -> cluster.groupBy { "mb:" + it.mbAlbumId!!.lowercase() }
    cluster.all { it.albumArtist.orNull() != null } -> cluster.groupBy { "by:" + searchKey(it.albumArtist!!) }
    else -> mapOf("" to cluster)
}

// Who the album is by: its album-artist tag, "Various Artists" for a
// compilation, otherwise whoever appears on most of its tracks.
private fun albumArtistOf(group: List<DeviceRow>): String {
    val tagged = group.mapNotNull { it.albumArtist.orNull() }
    return when {
        tagged.isNotEmpty() -> mostCommon(tagged)
        group.any { it.compilation } -> VARIOUS_ARTISTS
        else -> group.mapNotNull { it.artist.orNull() }.takeIf { it.isNotEmpty() }?.let(::mostCommon) ?: UNKNOWN_ARTIST
    }
}

// One spelling per genre across the library, the most common one, so
// "Hip Hop", "hip-hop" and "Hip-Hop/Rap" are the same genre.
private fun genreSpellings(rows: List<DeviceRow>): Map<String, String> =
    rows.flatMap { it.genres }.groupBy(::genreKey).mapValues { (_, names) -> mostCommon(names) }

private fun artistIdOf(name: String) = "$DEVICE:artist:${searchKey(name)}"

// Orders an album's files: disc, then track number, then file name. Where
// the tags have no track numbers, numbers at the start of the file names
// are used, but only when every file has one and no two are the same;
// otherwise a name like "50 Cent - In Da Club" would read as track 50.
private fun inAlbumOrder(group: List<DeviceRow>): List<Pair<DeviceRow, Int?>> {
    val fromNames = group.map { row -> fileNumber.find(row.fileName)?.groupValues?.get(1)?.toInt() }
    val namesUsable = group.size > 1 && fromNames.none { it == null } && fromNames.toSet().size == group.size
    return group.mapIndexed { i, row -> row to (row.track ?: fromNames[i].takeIf { namesUsable }) }
        .sortedWith(
            compareBy<Pair<DeviceRow, Int?>> { it.first.disc ?: 1 }
                .thenBy { it.second ?: Int.MAX_VALUE }
                .thenBy { it.first.fileName.lowercase() },
        )
}

private fun mostCommon(values: List<String>): String =
    values.groupingBy { it }.eachCount().maxWith(compareBy({ it.value }, { it.key.length })).key

// A short id that stays the same for the same input across scans.
private fun stableId(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
