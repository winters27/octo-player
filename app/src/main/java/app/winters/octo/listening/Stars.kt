package app.winters.octo.listening

import app.winters.octo.catalog.ServerCopy
import java.time.Instant

// Keeps likes on the phone and stars on the server the same, both ways.
//
// Each side is compared with the last state both agreed on (the synced
// record, kept as server song ids), which tells which side changed: a like
// or star added on one side is added on the other, and a removal on either
// side wins over the copy the other side still has.

// What brings both sides together, and the record to keep once it is done.
data class StarPlan(
    // Server songs to star and to unstar.
    val star: Set<String> = emptySet(),
    val unstar: Set<String> = emptySet(),
    // Library songs to like and to unlike.
    val like: Set<String> = emptySet(),
    val unlike: Set<String> = emptySet(),
    // The server songs both sides will agree are starred.
    val synced: Set<String> = emptySet(),
)

fun reconcileStars(
    copies: List<ServerCopy>,
    liked: Set<String>,
    starred: Set<String>,
    synced: Set<String>,
): StarPlan {
    val known = copies.mapTo(HashSet()) { it.serverId }
    // A song that lost its server copy keeps its record, in case it returns.
    val record = synced.filterTo(HashSet()) { it !in known }
    val star = HashSet<String>()
    val unstar = HashSet<String>()
    val like = HashSet<String>()
    val unlike = HashSet<String>()
    copies.groupBy({ it.trackId }, { it.serverId }).forEach { (song, ids) ->
        val here = song in liked
        val there = ids.any { it in starred }
        val before = ids.any { it in synced }
        when {
            // Both agree it is liked.
            here && there -> record += ids.filter { it in starred }
            // Unstarred on the server since the last sync.
            here && before -> unlike += song
            // Liked on the phone since the last sync, or before ever syncing.
            here -> {
                star += ids
                record += ids
            }
            // Unliked on the phone since the last sync.
            there && before -> unstar += ids.filter { it in starred }
            // Starred on the server since the last sync.
            there -> {
                like += song
                record += ids.filter { it in starred }
            }
        }
    }
    return StarPlan(star, unstar, like, unlike, record)
}

// The record once the plan was carried out. A star that did not reach the
// server is left out, so the next sync tries it again; an unstar that did not
// is kept, for the same reason.
fun StarPlan.settled(starFailed: Set<String>, unstarFailed: Set<String>): Set<String> =
    synced - starFailed + unstarFailed

// A server time like "2026-09-19T01:44:59.636933547Z", in milliseconds.
fun parseServerTime(text: String?): Long? =
    text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
