package app.winters.octo.offline.today

import app.winters.octo.offline.DownloadPlan
import app.winters.octo.offline.PlaylistMember
import app.winters.octo.offline.Reasons

// A labeled pin: today's offline rules over whole sets, as
// offline/DownloadPlan.kt had them at c2208eaa, frozen as the measure the
// paged rules walk must match. Never edited to follow the app.

// The songs the rules want kept, each with the rules that want it.
fun wantedByRules(
    keepLiked: Boolean,
    liked: Collection<String>,
    playlistSongs: Collection<PlaylistMember>,
): Map<String, Set<String>> {
    val wanted = HashMap<String, MutableSet<String>>()
    if (keepLiked) liked.forEach { wanted.getOrPut(it, ::HashSet) += Reasons.LIKED }
    playlistSongs.forEach { wanted.getOrPut(it.trackId, ::HashSet) += Reasons.playlist(it.playlistId) }
    return wanted
}

// Brings what is kept (`held`, each song with its reasons) in step with
// what the rules want. A download asked for by hand stays until it is
// removed by hand. A rule's reason goes only when the rule no longer wants
// the song, so a song that is briefly missing from the library, as during
// a sync, is not deleted. A song is newly downloaded only if it can be:
// on a server and not already on the phone.
fun planDownloads(
    held: Map<String, Set<String>>,
    wanted: Map<String, Set<String>>,
    downloadable: Set<String>,
): DownloadPlan {
    val change = HashMap<String, Set<String>>()
    val remove = HashSet<String>()
    for ((trackId, reasons) in held) {
        val kept = reasons.filterTo(HashSet()) { it == Reasons.MANUAL } + wanted[trackId].orEmpty()
        when {
            kept.isEmpty() -> remove += trackId
            kept != reasons -> change[trackId] = kept
        }
    }
    val add = wanted.filterKeys { it !in held && it in downloadable }
    return DownloadPlan(add, change, remove)
}
