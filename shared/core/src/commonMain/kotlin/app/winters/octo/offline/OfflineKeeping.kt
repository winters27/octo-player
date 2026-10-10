package app.winters.octo.offline

import kotlinx.serialization.Serializable
import java.util.Locale

// Why a song is kept on the device: picked by hand, one of the Liked
// songs, or in a playlist kept offline. A song stays while any reason does.
object KeepReason {
    const val BY_HAND = "manual"
    const val LIKED = "liked"
    fun playlist(id: String) = "playlist:$id"
}

// What the listener chose to keep from one server.
@Serializable
data class KeepChoices(
    val byHand: List<String> = emptyList(),
    val liked: Boolean = false,
    val playlists: List<String> = emptyList(),
)

// The songs to keep and why, from the choices and what the server has
// now: its liked songs (when kept) and each kept playlist's songs.
fun wantedCopies(choices: KeepChoices, liked: List<String>, playlists: Map<String, List<String>>): Map<String, Set<String>> {
    val wanted = LinkedHashMap<String, MutableSet<String>>()
    fun add(id: String, reason: String) {
        wanted.getOrPut(id) { linkedSetOf() } += reason
    }
    choices.byHand.forEach { add(it, KeepReason.BY_HAND) }
    if (choices.liked) liked.forEach { add(it, KeepReason.LIKED) }
    choices.playlists.forEach { playlist -> playlists[playlist].orEmpty().forEach { add(it, KeepReason.playlist(playlist)) } }
    return wanted
}

// What to fetch and what to let go, from what is wanted and what is kept.
// A playlist that could not be read keeps its songs: `unsure` holds them.
data class KeepPlan(val fetch: List<String>, val remove: List<String>)

fun keepPlan(wanted: Map<String, Set<String>>, kept: Set<String>, unsure: Set<String> = emptySet()): KeepPlan =
    KeepPlan(
        fetch = wanted.keys.filter { it !in kept },
        remove = kept.filter { it !in wanted && it !in unsure },
    )

// Where a kept song goes inside the offline folder: by artist and album,
// named by its place on the album and its title, with only characters every
// system takes in a file name.
fun keptPath(artist: String?, album: String?, track: Int?, title: String, suffix: String?): String {
    val folder = listOf(artist ?: "Unknown artist", album ?: "Unknown album").map(::fileSafe)
    val number = track?.takeIf { it > 0 }?.let { String.format(Locale.US, "%02d ", it) }.orEmpty()
    val ext = suffix?.lowercase()?.filter(Char::isLetterOrDigit)?.takeIf(String::isNotEmpty) ?: "audio"
    return (folder + "${fileSafe(number + title)}.$ext").joinToString("/")
}

// A name a file can have on Windows, macOS and Linux: no / \ : * ? " < > |,
// no control characters, no dot or space at the end, at most 80 characters.
fun fileSafe(name: String): String {
    val clean = name.map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").trim().trimEnd('.', ' ')
    val short = if (clean.length > 80) clean.take(80).trimEnd('.', ' ') else clean
    return short.ifEmpty { "_" }.let { if (it.uppercase() in RESERVED) "_$it" else it }
}

private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

// "312 songs, 1.9 GB" for the settings line.
fun keptLine(count: Int, bytes: Long): String {
    val size = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    }
    return "${if (count == 1) "1 song" else "$count songs"}, $size"
}
