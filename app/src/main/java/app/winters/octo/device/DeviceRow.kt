package app.winters.octo.device

// One music file as the phone's media library describes it. Plain data,
// so the grouping logic can be tested without a phone.
data class DeviceRow(
    val id: Long,
    val uri: String,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val albumId: Long,
    val track: Int?,
    val disc: Int?,
    val year: Int?,
    val durationMs: Long,
    val addedAtSeconds: Long,
    val mimeType: String?,
    val sizeBytes: Long?,
)
