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
    // When the file joined the collection, in seconds (see fileAddedAt).
    val addedAtSeconds: Long,
    val mimeType: String?,
    val fallback: FileTags,
    // Which storage it is on, like "external_primary" or an SD card's id.
    val volume: String? = null,
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
    // Everything else the file's tags say; empty when they could not be read.
    val tags: FileTags = FileTags(),
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
    tags = tags ?: FileTags(),
)

// Times before this (1 January 1990) are a clock that was never set, not
// when a song was really got.
private const val EARLIEST_REAL_SECONDS = 631_152_000L

// When a phone file joined the collection, in seconds since 1970.
//
// The phone's own "added" time is when the file reached this phone, so a
// library copied over in one go all looks added that day. The file's
// modified time usually survives the copy and says when the song was really
// got, and a tag editor that touches a file later only moves it forward.
// So the earliest real time wins: modified, added, or "taken", which a
// phone may fill in for audio (in milliseconds). A time before 1990 or more
// than a day from now is a broken clock and is passed over. With no real
// time at all, the phone's added time is kept as it is.
fun fileAddedAt(modifiedSeconds: Long, addedSeconds: Long, takenMs: Long?, nowSeconds: Long): Long =
    listOfNotNull(modifiedSeconds, addedSeconds, takenMs?.div(1000))
        .filter { it >= EARLIEST_REAL_SECONDS && it <= nowSeconds + 86_400 }
        .minOrNull()
        ?: addedSeconds.coerceAtLeast(0)
