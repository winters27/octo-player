package app.winters.octo.discovery

import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.findId
import app.winters.octo.playback.FindMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// How many owned songs are remembered before the oldest are let go: more
// than a long radio or playlist holds at once.
internal const val OWNED_KEPT = 2_000

// Songs of the server's own library the phone's copy had not brought in when
// a list named them (see resolveSongs), by the find id they play under. Every
// screen shows them as library songs: no "not in your library" mark, no add
// button and no download, and the heart, which is the server's star of the
// song (`liked`). They stay owned once brought in, since a queue or row may
// still hold the find id. The newest OWNED_KEPT are kept while the app runs.
// Safe across threads.
@Singleton
class OwnedFinds internal constructor(private val kept: Int) {
    @Inject constructor() : this(OWNED_KEPT)

    // Oldest first, so the ones past the cap go first.
    private val order = LinkedHashSet<String>()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    // The owned songs with their heart on: starred on the server as its
    // answer said, or hearted here since.
    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    operator fun contains(id: String): Boolean = id in _ids.value

    // What one read of the server said: `owned` are its library's songs,
    // `outside` the songs it marked as found online, which are no longer
    // owned if they were, and `starred` whether the answer had each owned
    // song starred, where it said.
    @Synchronized
    fun note(owned: Collection<String>, outside: Collection<String> = emptyList(), starred: Map<String, Boolean> = emptyMap()) {
        if (owned.isEmpty() && outside.none { it in order }) return
        order.removeAll(outside.toSet())
        for (id in owned) {
            // Taken out first, so a song seen again moves to the newest end.
            order.remove(id)
            order.add(id)
        }
        val over = order.size - kept
        if (over > 0) order.take(over).forEach(order::remove)
        _ids.value = order.toSet()
        val hearts = _liked.value.filterTo(HashSet()) { it in order }
        for ((id, on) in starred) if (id in order) if (on) hearts += id else hearts -= id
        _liked.value = hearts
    }

    // Turns an owned song's heart over. Answers whether it is on now, or
    // null when the song is not owned.
    @Synchronized
    fun heart(id: String): Boolean? {
        if (id !in order) return null
        val on = id !in _liked.value
        _liked.value = if (on) _liked.value + id else _liked.value - id
        return on
    }

    // The server ids of the owned songs.
    fun serverIds(): List<String> = _ids.value.map { it.removePrefix(FIND_PREFIX) }
}

// A heart on a song the server owns that the phone's copy lacks: it turns
// over at once and the server's star of the song follows (`star`, by its
// server id). Answers whether it is on now, or null for a song that is not
// owned, which has no heart.
internal fun heartOwned(owned: OwnedFinds, findId: String, star: (String, Boolean) -> Unit): Boolean? {
    val on = owned.heart(findId) ?: return null
    star(findId.removePrefix(FIND_PREFIX), on)
    return on
}

// Links the finds of owned songs (by server id, on server `sourceId`) that
// the library now holds to their library songs, so a queue or list holding
// a find plays and shows the library song, and hands each pair to
// `linked` (find id, library song id), which carries its heart over.
internal suspend fun linkOwnedFinds(
    sources: SourceDao,
    online: OnlineDao,
    finds: FindMap,
    sourceId: String,
    serverIds: List<String>,
    linked: suspend (String, String) -> Unit = { _, _ -> },
) {
    val links = serverIds.distinct().chunked(900).flatMap { sources.libraryLinks(sourceId, it) }
    for (link in links) {
        adoptFind(online, finds, findId(link.serverId), link.trackId)
        linked(findId(link.serverId), link.trackId)
    }
}
