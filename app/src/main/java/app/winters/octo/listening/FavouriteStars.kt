package app.winters.octo.listening

import app.winters.octo.catalog.CopyCount
import app.winters.octo.catalog.ServerCopy
import kotlinx.coroutines.delay

// Favourite albums and artists are kept in step with stars on the server
// exactly as liked songs are (reconcileStars in Stars.kt): the same three-way
// comparison with the last state both sides agreed on. What differs is only
// how a library album or artist finds its server copies.

// The server copies of library albums (or artists), one library album per
// server album: the one that took most of its songs when the library was
// merged, or on a tie the one that kept the server album's own id. A server
// album never counts for two library albums, since a star there would then
// read as starred for both. Ids the library made up because the server gave
// none ("album:..." or "artist:...") cannot be starred and are left out.
fun serverCopiesOf(counts: List<CopyCount>, sourceId: String): List<ServerCopy> =
    counts.groupBy { it.serverRowId }.mapNotNull { (row, links) ->
        val serverId = row.removePrefix("$sourceId:").takeIf { row.startsWith("$sourceId:") } ?: return@mapNotNull null
        if (serverId.isEmpty() || serverId.startsWith("album:") || serverId.startsWith("artist:")) return@mapNotNull null
        val best = links.maxWithOrNull(compareBy<CopyCount>({ it.songs }, { it.libraryId == row }, { it.libraryId })) ?: return@mapNotNull null
        ServerCopy(trackId = best.libraryId, serverId = serverId)
    }

// The server ids one library album or artist is starred through.
fun List<ServerCopy>.serverIdsOf(libraryId: String): List<String> =
    filter { it.trackId == libraryId }.map { it.serverId }.sorted()

// The server ids to star (or unstar) for one change made on the phone:
// none once a newer change undid it (`likedNow` is how it stands now), or
// when the album or artist is only on the phone.
fun idsToSend(copies: List<ServerCopy>, libraryId: String, liked: Boolean, likedNow: Boolean): List<String> =
    if (liked != likedNow) emptyList() else copies.serverIdsOf(libraryId)

// How long to wait before each new try at sending a change, in milliseconds.
// After the last, the next sync of the library sends it.
val FAVOURITE_RETRY_WAITS = listOf(5_000L, 30_000L, 120_000L)

// Runs `attempt` until it says it is done, waiting before each new try.
// False when every try failed.
suspend fun retried(waits: List<Long>, attempt: suspend () -> Boolean): Boolean {
    if (attempt()) return true
    for (wait in waits) {
        delay(wait)
        if (attempt()) return true
    }
    return false
}
