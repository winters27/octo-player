package app.winters.octo.listening

import app.winters.octo.catalog.CopyCount
import app.winters.octo.catalog.ServerCopy

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
