package app.winters.octo.listening

import app.winters.octo.catalog.RatingCopy

// Keeps ratings on the phone and on the server the same, both ways.
//
// Each side is compared with the last rating both agreed on (the synced
// record, kept per server song, with no entry meaning no rating), which
// tells which side changed. The side that changed wins; when both did, the
// phone's wins, since it is the change made here last. A rating is 1 to 5
// stars, and 0 is none.

// What one song's server copies should be set to.
data class RatingSend(val serverIds: List<String>, val rating: Int)

// What brings both sides together, and the record to keep once it is done.
data class RatingPlan(
    // Library songs whose server copies change, and to what.
    val send: Map<String, RatingSend> = emptyMap(),
    // Library songs whose rating on the phone changes, and to what.
    val adopt: Map<String, Int> = emptyMap(),
    // The ratings both sides will agree on, by server song.
    val synced: Map<String, Int> = emptyMap(),
)

fun reconcileRatings(copies: List<RatingCopy>, local: Map<String, Int>, synced: Map<String, Int>): RatingPlan {
    val known = copies.mapTo(HashSet()) { it.serverId }
    // A song that lost its server copy keeps its record, in case it returns.
    val record = HashMap(synced.filterKeys { it !in known })
    val send = HashMap<String, RatingSend>()
    val adopt = HashMap<String, Int>()
    copies.groupBy { it.trackId }.forEach { (song, songCopies) ->
        val ordered = songCopies.sortedBy { it.serverId }
        val ids = ordered.map { it.serverId }
        val here = local[song] ?: 0
        val before = ids.firstNotNullOfOrNull { synced[it] } ?: 0
        // The server's rating: a copy that moved from the record, if any.
        val there = ordered.firstOrNull { it.rating != before }?.rating ?: before
        val target = if (here != before) here else there
        if (ordered.any { it.rating != target }) send[song] = RatingSend(ids, target)
        if (here != target) adopt[song] = target
        if (target > 0) ids.forEach { record[it] = target }
    }
    return RatingPlan(send, adopt, record)
}

// The record once the plan was carried out. A song whose server copies were
// not all set keeps its old record, so the next sync sees the phone's change
// again and sends it again: that is how a rating made offline waits.
fun RatingPlan.settled(failed: Set<String>, before: Map<String, Int>): Map<String, Int> {
    val result = HashMap(synced)
    for (song in failed) {
        for (id in send[song]?.serverIds.orEmpty()) {
            val old = before[id]
            if (old == null) result -= id else result[id] = old
        }
    }
    return result
}

// A rating in range: 1 to 5 stars, anything else none.
fun cleanRating(rating: Int?): Int = rating?.takeIf { it in 1..5 } ?: 0

// Stored one server song per line: "<rating> <server id>".
fun encodeRatings(ratings: Map<String, Int>): Set<String> =
    ratings.filterValues { it in 1..5 }.mapTo(HashSet()) { (id, rating) -> "$rating $id" }

fun decodeRatings(lines: Set<String>): Map<String, Int> =
    lines.mapNotNull { line ->
        val rating = line.substringBefore(" ").toIntOrNull()?.takeIf { it in 1..5 } ?: return@mapNotNull null
        val id = line.substringAfter(" ", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        id to rating
    }.toMap()
