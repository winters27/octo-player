package app.winters.octo.desktop.versions

import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.STREAM_VERSION_CHANGES_EXTENSION
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.versions.FeedPlan
import app.winters.octo.versions.StreamVersionCursor
import app.winters.octo.versions.planFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

// Reads which songs were given another version since the last read, from
// the server in use, and hands them to `drop`: the listed ids, or every
// song when the server cannot say (a new list, too many changes, or a first
// read). One read at a time: a read asked for while one is on its way waits
// for that one. Where each server's list was read to is kept in the
// settings, by server id, so a restart reads on from there.
class StreamVersionFeed(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val connection: () -> Connection?,
    private val drop: (FeedPlan) -> Unit,
) {
    private var running: Deferred<Unit>? = null

    // Reads the list once, or waits for the read already on its way. A read
    // that fails (offline, the server down) leaves everything as it was.
    suspend fun refresh() {
        val read = synchronized(this) {
            running?.takeIf { it.isActive } ?: scope.async { readOnce() }.also { running = it }
        }
        read.await()
    }

    fun refreshSoon() {
        scope.launch { refresh() }
    }

    // Before a song found online is asked about (its transition profile),
    // what changed is read, so nothing kept of an old file of it is used.
    suspend fun beforeOpening(song: Song) {
        if (song.isExternal) refresh()
    }

    private suspend fun readOnce() {
        val now = connection() ?: return
        if (!now.supports(STREAM_VERSION_CHANGES_EXTENSION)) return
        val server = now.server.id
        val stored = settings.current.versionCursors[server]?.let(::cursorOf)
        val answer = try {
            now.client.streamVersionChanges(stored?.epoch, stored?.seq)
        } catch (e: SubsonicException) {
            return
        }
        // Not for a server left meanwhile.
        if (connection()?.server?.id != server) return
        val plan = planFeed(stored, answer)
        drop(plan)
        settings.update { it.copy(versionCursors = it.versionCursors + (server to cursorText(plan.next))) }
    }
}

// A cursor as the settings keep it, "<seq> <epoch>", and back; null for
// anything else.
internal fun cursorText(cursor: StreamVersionCursor): String = "${cursor.seq} ${cursor.epoch}"

internal fun cursorOf(text: String): StreamVersionCursor? {
    val seq = text.substringBefore(' ').toLongOrNull() ?: return null
    val epoch = text.substringAfter(' ', "")
    if (epoch.isEmpty()) return null
    return StreamVersionCursor(epoch, seq)
}
