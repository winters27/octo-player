package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.isFind
import app.winters.octo.subsonic.Song

// A library album's songs as the server sent them (`sent`), resolved to
// library songs and finds in the same order (`resolved`), each find
// numbered as the album numbers it. A find the server marks is outside the
// library; one with no mark is the server's own (ownedAlbumSongs), added
// since the last copy, and shows in its place as a library song that
// streams until the copy brings it in.
fun outsideAlbumSongs(sent: List<Song>, resolved: List<TrackEntity>): List<TrackEntity> {
    val songs = sent.associateBy { findId(it.id) }
    return resolved.mapNotNull { track ->
        if (!isFind(track.id)) return@mapNotNull track
        val song = songs[track.id] ?: return@mapNotNull null
        track.copy(trackNo = song.track?.takeIf { it > 0 }, discNo = song.discNumber?.takeIf { it > 0 })
    }
}

// The songs of a library album's listing (`sent`) that the server owns and
// the copy lacks: unmarked ones that resolved to finds (`resolved`).
fun ownedAlbumSongs(sent: List<Song>, resolved: List<TrackEntity>): List<Song> {
    val finds = resolved.filter { isFind(it.id) }.mapTo(HashSet()) { it.id }
    return sent.filter { !it.isExternal && findId(it.id) in finds }.distinctBy { it.id }
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
