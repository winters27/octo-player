package app.winters.octo.ui.playlist

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

// Which playlists hold which of the songs, from the rows the database gives.
fun holdingsByPlaylist(rows: List<Pair<String, String>>): Map<String, Set<String>> =
    rows.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

// The words for songs added, like "Added to Road trip" or "Added 5 songs to Road trip".
fun addedMessage(name: String, count: Int): String = if (count == 1) "Added to $name" else "Added $count songs to $name"
