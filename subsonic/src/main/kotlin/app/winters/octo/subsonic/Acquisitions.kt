package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists when it can say how the
// downloads it was asked for are going.
const val OCTO_ACQUISITIONS = "octoAcquisitions"

// Where one download on the server has got to.
enum class AcquisitionStage(val wire: String) {
    Queued("queued"),
    Searching("searching"),
    Downloading("downloading"),
    Verifying("verifying"),
    Importing("importing"),
    Done("done"),
    Failed("failed"),

    // A stage this app does not know yet, from a newer server.
    Unknown("");

    companion object {
        fun of(text: String?): AcquisitionStage {
            val wire = text?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it != Unknown && it.wire == wire } ?: Unknown
        }
    }
}

// One song the server is downloading for the signed-in user. Any field can
// be missing, and finished ones stay listed for a while.
@Serializable
data class Acquisition(
    // The id that was starred: a find's id without its "find:".
    val id: String = "",
    val artist: String = "",
    val title: String = "",
    val album: String? = null,
    // As the server says it; `stage` is what it means.
    val state: String = "",
    // How far along, from 0 to 1, when the server knows.
    val progress: Float? = null,
    val bytesDone: Long? = null,
    val bytesTotal: Long? = null,
    // Where the file is coming from, in words.
    val source: String? = null,
    val startedAt: String? = null,
    val updatedAt: String? = null,
    // Why it failed, in words.
    val error: String? = null,
    // The song's id in the library once it is there, when the server knows it.
    val libraryId: String? = null,
) {
    val stage: AcquisitionStage get() = AcquisitionStage.of(state)

    // How far along, kept between 0 and 1, or null when unknown.
    val fraction: Float?
        get() = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: bytesDone?.let { done -> bytesTotal?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) } }
}

@Serializable
internal data class Acquisitions(val acquisition: List<Acquisition> = emptyList())

// Whether a server lists an extension at this version.
fun List<Extension>.lists(name: String, version: Int = 1): Boolean = any { it.name == name && version in it.versions }
