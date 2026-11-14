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
// button and no download. They stay owned once brought in, since a queue or
// row may still hold the find id. The newest OWNED_KEPT are kept while the
// app runs. Safe across threads.
@Singleton
class OwnedFinds internal constructor(private val kept: Int) {
    @Inject constructor() : this(OWNED_KEPT)

    // Oldest first, so the ones past the cap go first.
    private val order = LinkedHashSet<String>()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    operator fun contains(id: String): Boolean = id in _ids.value

    // What one read of the server said: `owned` are its library's songs,
    // and `outside` the songs it marked as found online, which are no
    // longer owned if they were.
    @Synchronized
    fun note(owned: Collection<String>, outside: Collection<String> = emptyList()) {
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
    }

    // The server ids of the owned songs.
    fun serverIds(): List<String> = _ids.value.map { it.removePrefix(FIND_PREFIX) }
}

// Links the finds of owned songs (by server id, on server `sourceId`) that
// the library now holds to their library songs, so a queue or list holding
// a find plays and shows the library song.
internal suspend fun linkOwnedFinds(sources: SourceDao, online: OnlineDao, finds: FindMap, sourceId: String, serverIds: List<String>) {
    val links = serverIds.distinct().chunked(900).flatMap { sources.libraryLinks(sourceId, it) }
    for (link in links) adoptFind(online, finds, findId(link.serverId), link.trackId)
}
