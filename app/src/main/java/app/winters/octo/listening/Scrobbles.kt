package app.winters.octo.listening

import app.winters.octo.subsonic.SubsonicException

// A play waiting to reach the server: which server song, and when it started.
data class PendingPlay(val serverId: String, val startedAt: Long)

// How many plays may wait. Past that the oldest are let go.
const val MAX_PENDING_PLAYS = 500

// Oldest first, so the server hears them in the order they happened.
fun List<PendingPlay>.plusPlay(play: PendingPlay): List<PendingPlay> =
    (this + play).distinct().sortedBy { it.startedAt }.takeLast(MAX_PENDING_PLAYS)

// Whether a failed send is worth trying again: the server could not be
// reached, answered strangely, or refused the login. A server that refused
// the play itself (an unknown song, say) would refuse it again.
fun worthRetrying(e: SubsonicException): Boolean =
    e is SubsonicException.Unreachable || e is SubsonicException.NotSubsonic || e is SubsonicException.WrongCredentials

// Stored one play per line: "<start> <server id>".
fun encodePending(plays: List<PendingPlay>): Set<String> = plays.mapTo(HashSet()) { "${it.startedAt} ${it.serverId}" }

fun decodePending(lines: Set<String>): List<PendingPlay> =
    lines.mapNotNull { line ->
        val start = line.substringBefore(" ").toLongOrNull() ?: return@mapNotNull null
        val id = line.substringAfter(" ", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        PendingPlay(id, start)
    }.sortedBy { it.startedAt }
