package app.winters.octo.ui.playlist

import app.winters.octo.playlists.ImportReport

// Adding songs to a playlist, as the phone and the desktop both ask and say
// it: which songs are new to the playlist, the question when some are not,
// and the words once they are in.

// What adding songs to a playlist would do: every song once, in the order
// given, split into those not on the playlist yet and those that are.
data class AddPlan(val songs: List<String>, val fresh: List<String>, val repeats: List<String>) {
    // Some are on the playlist already, so ask before adding them again.
    val asks: Boolean get() = repeats.isNotEmpty()

    // A choice between adding every song and only the new ones.
    val offersNewOnly: Boolean get() = songs.size > 1 && fresh.isNotEmpty()
}

fun planAdd(trackIds: List<String>, onPlaylist: Set<String>): AddPlan {
    val songs = trackIds.distinct()
    val (repeats, fresh) = songs.partition { it in onPlaylist }
    return AddPlan(songs, fresh, repeats)
}

// What is asked before songs already on a playlist go on it again.
fun addAgainQuestion(name: String, plan: AddPlan): String = when {
    plan.songs.size == 1 -> "Already in $name. Add again?"
    plan.fresh.isEmpty() -> "These songs are already in $name. Add them again?"
    plan.repeats.size == 1 -> "1 of these songs is already in $name."
    else -> "${plan.repeats.size} of these songs are already in $name."
}

// The button that adds every song anyway: "Add" for one song, "Add again"
// when all are there, "Add all" beside "Add new ones".
fun addAgainChoice(plan: AddPlan): String = when {
    plan.offersNewOnly -> "Add all"
    plan.songs.size == 1 -> "Add"
    else -> "Add again"
}

// The button that adds only the songs not on the playlist yet.
const val ADD_NEW_ONES = "Add new ones"

// The words for songs added, like "Added to Road trip" or "Added 5 songs to Road trip".
fun addedMessage(name: String, count: Int): String = if (count == 1) "Added to $name" else "Added $count songs to $name"

// The playlists added to lately, newest first: `id` goes to the front, and
// only `keep` are remembered.
fun recentPlaylists(recent: List<String>, id: String, keep: Int = RECENT_PLAYLISTS): List<String> =
    (listOf(id) + recent.filter { it != id }).take(keep)

// How many playlists added to lately are remembered.
const val RECENT_PLAYLISTS = 3

// The playlists with those added to lately first, newest first, and the
// rest in the order they came.
fun <T> recentFirst(playlists: List<T>, recent: List<String>, id: (T) -> String): List<T> {
    val rank = recent.withIndex().associate { it.value to it.index }
    return playlists.sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
}

// The name a copy of a playlist gets, and the line that says it was made.
fun playlistCopyName(name: String): String = "${name.trim()} (copy)"

fun copiedMessage(copy: String): String = "Made a copy: $copy"

// "Imported 42 of 45 songs into "Road trip"", or why nothing was.
fun importSummary(report: ImportReport): String = when {
    report.total == 0 -> "\"${report.name}\" has no songs in it."
    report.matched == 0 -> "None of the ${report.total} songs in \"${report.name}\" are in your library."
    else -> "Imported ${report.matched} of ${if (report.total == 1) "1 song" else "${report.total} songs"} into \"${report.name}\""
}
