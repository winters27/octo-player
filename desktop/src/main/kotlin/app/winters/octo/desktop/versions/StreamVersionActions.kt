package app.winters.octo.desktop.versions

import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.system.isOpenedFile
import app.winters.octo.playback.isRadio
import app.winters.octo.subsonic.STREAM_VERSIONS_EXTENSION
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.StreamVersions
import app.winters.octo.versions.FeedPlan

// Whether "Try another version" opens for a song: one found online that
// streams from the server (`landed` names no library copy fetched since),
// on a server that lets this person pick versions (`listed`). Never a
// station or a file opened from this computer.
fun streamVersionsOffered(listed: Boolean, song: Song?, landed: String?): Boolean =
    listed && song != null && song.isExternal && !isRadio(song.id) && !isOpenedFile(song.id) && landed == null

// Whether the server in use lets this person pick versions (it lists
// octoStreamVersions only for those who may).
val AppState.listsStreamVersions: Boolean
    get() = connection?.supports(STREAM_VERSIONS_EXTENSION) == true

// The same, for a queue entry here.
fun AppState.offersStreamVersions(entry: QueueEntry?): Boolean {
    val song = entry?.song
    return streamVersionsOffered(listsStreamVersions, song, song?.let { fetches?.landedId(it.id) })
}

// A pick (or Octo's pick back) was taken: what this computer kept of the
// song's old file goes for every id the song has, the entry starts over
// from its start when it still plays, and the list of changes is read for
// the rest.
fun AppState.streamVersionTaken(entry: QueueEntry, answer: StreamVersions) {
    filesChanged(answer.ids.toSet() + entry.song.id)
    if (player.state.value.current?.key == entry.key) player.restartCurrent()
    versions.refreshSoon()
}

// What the change feed found: every song's kept things when it cannot say
// which changed, and in any case the listed songs'.
fun AppState.streamVersionsDropped(plan: FeedPlan) {
    if (plan.dropAll) filesChanged(null)
    if (plan.dropIds.isNotEmpty()) filesChanged(plan.dropIds)
}

// Songs given another file on the server: their transition profiles go,
// and their own lyrics timing (set for the old file). Null: every song's
// profiles, and the lyrics timing of the songs found online in the queue
// (the server's own mark, `isExternal`, is the only one this computer has).
fun AppState.filesChanged(ids: Set<String>?) {
    player.filesChanged(ids)
    val songs = ids ?: player.state.value.queue.mapNotNullTo(HashSet()) { entry -> entry.song.id.takeIf { entry.song.isExternal } }
    if (settings.current.lyrics.offsets.keys.none(songs::contains)) return
    settings.update { s -> s.copy(lyrics = s.lyrics.copy(offsets = s.lyrics.offsets - songs)) }
}
