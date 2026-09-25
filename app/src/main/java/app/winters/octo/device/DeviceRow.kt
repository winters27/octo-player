package app.winters.octo.device

// One music file as the phone's media library lists it, with the phone's
// own reading of its tags kept as a fallback.
data class DeviceFile(
    val id: Long,
    val uri: String,
    val fileName: String,
    // The folder it sits in, like "Music/Kavinsky/Nightcall/".
    val folder: String?,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val addedAtSeconds: Long,
    val mimeType: String?,
    val fallback: FileTags,
)

// One file ready to be grouped: the file plus the best tags known for it.
// Plain data, so the grouping logic can be tested without a phone.
data class DeviceRow(
    val id: Long,
    val uri: String,
    val fileName: String,
    val folder: String?,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val track: Int?,
    val disc: Int?,
    val year: Int?,
    val compilation: Boolean = false,
    val mbAlbumId: String? = null,
    val genres: List<String> = emptyList(),
    val durationMs: Long,
    val addedAtSeconds: Long,
    val mimeType: String?,
    val sizeBytes: Long?,
)

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
)
