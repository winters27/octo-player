package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.isFind
import app.winters.octo.subsonic.Song

// A library album's songs as the server sent them (`sent`), resolved to
// library songs and finds in the same order (`resolved`). Only songs the
// server marks as outside the library stay finds, each numbered as the
// album numbers it: a song with no mark that the library lacks was added
// to the server since the last copy, and the next copy brings it in.
fun outsideAlbumSongs(sent: List<Song>, resolved: List<TrackEntity>): List<TrackEntity> {
    val marked = sent.filter { it.isExternal }.associateBy { findId(it.id) }
    return resolved.mapNotNull { track ->
        if (!isFind(track.id)) return@mapNotNull track
        val song = marked[track.id] ?: return@mapNotNull null
        track.copy(trackNo = song.track?.takeIf { it > 0 }, discNo = song.discNumber?.takeIf { it > 0 })
    }
}

// A library album whole: the server's songs in the server's order (see
// outsideAlbumSongs), each library one as the library has it and each
// find the library has since taken in (`adoptions`, find to library song)
// as that library song. Library songs the server did not list, such as a
// copy only on the phone, follow in their own order. With no finds from
// the server, the album is the library's as it is.
fun wholeAlbum(library: List<TrackEntity>, server: List<TrackEntity>, adoptions: Map<String, String> = emptyMap()): List<TrackEntity> {
    if (server.none { isFind(it.id) }) return library
    val ours = library.associateBy { it.id }
    val placed = LinkedHashMap<String, TrackEntity>()
    for (track in server) {
        val adopted = if (isFind(track.id)) adoptions[track.id]?.let(ours::get) else null
        val shown = adopted ?: ours[track.id] ?: track
        placed.putIfAbsent(shown.id, shown)
    }
    library.forEach { placed.putIfAbsent(it.id, it) }
    return placed.values.toList()
}
