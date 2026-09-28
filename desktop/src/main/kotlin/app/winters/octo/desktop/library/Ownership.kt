package app.winters.octo.desktop.library

import app.winters.octo.desktop.search.FetchPhase
import app.winters.octo.desktop.system.isOpenedFile
import app.winters.octo.subsonic.Song

// Whether a song is one the server found online rather than a file in the
// library. Octo marks such songs. A song with no mark still counts when the
// server can fetch songs (`canFetch`) and the library, once read, does not
// hold it. A file opened from this computer is never one.
fun isOutsideSong(song: Song, index: LibraryIndex?, canFetch: Boolean): Boolean = when {
    isOpenedFile(song.id) -> false
    song.isExternal -> true
    else -> canFetch && index != null && !index.holds(song)
}

// The ids of the songs in a list that are outside the library. A list the
// server has marked (any song in it carries the mark) is taken at its
// word, so a song added to the library since it was last read is not
// called missing; only a list with no marks falls back on the library.
fun outsideIds(songs: List<Song>, index: LibraryIndex?, canFetch: Boolean): Set<String> {
    val marked = songs.any { it.isExternal }
    return songs.asSequence()
        .filter { if (marked) it.isExternal && !isOpenedFile(it.id) else isOutsideSong(it, index, canFetch) }
        .mapTo(HashSet()) { it.id }
}

// The quiet line under an album's heading while some of its songs are not
// in the library, and the action that adds the ones that can be asked for.
data class LibraryShare(val line: String, val action: String?)

// `owned` counts songs in the library, including any that just arrived;
// `coming` those on their way; `askable` those not asked for yet (or that
// failed and can be asked again). Nothing when the whole album is in.
fun libraryShare(total: Int, owned: Int, coming: Int = 0, askable: Int = total - owned - coming): LibraryShare? {
    if (total <= 0 || owned >= total) return null
    val have = if (owned <= 0) "Not in your library" else "$owned of $total in your library"
    val line = if (coming > 0) "$have, $coming on the way" else have
    val action = when {
        askable <= 0 -> null
        askable == 1 -> "Add the missing song"
        owned <= 0 && coming <= 0 -> "Add all $askable"
        else -> "Add the $askable missing songs"
    }
    return LibraryShare(line, action)
}

// How an album's songs stand, as its heading's line says it: the songs
// outside the library (`outside`, by id) count as in once fetched, and as
// on their way while being fetched. Nothing can be asked for on a server
// that cannot fetch songs.
fun libraryShareOf(songs: List<Song>, outside: Set<String>, phases: Map<String, FetchPhase>, canFetch: Boolean): LibraryShare? {
    val out = songs.filter { it.id in outside }
    val done = out.count { phases[it.id] == FetchPhase.Done }
    val coming = out.count { phases[it.id].let { phase -> phase == FetchPhase.Queued || phase is FetchPhase.Downloading || phase == FetchPhase.Adding } }
    val askable = if (canFetch) out.size - done - coming else 0
    return libraryShare(songs.size, songs.size - out.size + done, coming, askable)
}

// The songs of a list that can be asked for now: outside the library, and
// not asked for yet or failed.
fun askableSongs(songs: List<Song>, outside: Set<String>, phases: Map<String, FetchPhase>): List<Song> =
    songs.filter { it.id in outside }.distinctBy { it.id }.filter { phases[it.id].let { phase -> phase == null || phase == FetchPhase.None || phase is FetchPhase.Failed } }
